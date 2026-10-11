package art.rehra.mineqtt.ui.device;

import art.rehra.mineqtt.device.LinkSetting;
import art.rehra.mineqtt.twin.TwinAdapter;
import art.rehra.mineqtt.twin.core.BindingPreset;
import art.rehra.mineqtt.twin.core.EntityDocument;
import art.rehra.mineqtt.twin.core.EntitySearch;
import art.rehra.mineqtt.twin.core.HaDomains;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The entity picker of the Twin tab (M3 plan D4, D19), as far as both sides share it: which binding keys a type picks
 * for and from which domains, the rows the server answers a search with and how they are made from the twin's
 * candidates, the area groups the client shows them in, and the free-text row for an entity Home Assistant has not
 * shared yet. The client never holds the catalog: it sends a query ({@code TwinSearchPayload}) and gets at most
 * {@value #MAX_ROWS} rows back ({@code TwinResultsPayload}), filtered for the player who may bind. Server-safe and pure.
 */
public final class TwinPicker {
    /** The most rows one answer carries (D4). */
    public static final int MAX_ROWS = 40;
    /** The longest query the picker sends (D4). */
    public static final int MAX_QUERY_LENGTH = EntitySearch.MAX_QUERY_LENGTH;
    /** How long the picker waits after the last key before it searches (D4); the last query always goes out. */
    public static final long DEBOUNCE_MILLIS = 250;
    /** A search a player may send per second on average; a flood beyond the burst is dropped. */
    public static final double SEARCHES_PER_SECOND = 4;
    /** The burst of searches a player may send at once. */
    public static final int SEARCH_BURST = 6;
    /** The longest name, area and value of a row (D4). */
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_AREA_LENGTH = 32;
    public static final int MAX_VALUE_LENGTH = 32;
    /**
     * How long the picker waits for the answer to a search before it sends it again (wave 6 review low): the server drops a
     * search over the player's rate without an answer.
     */
    public static final long RESEND_MILLIS = 1000;
    /** How often the picker sends an unanswered search again, at most. */
    public static final int MAX_RESENDS = 3;
    /** The longest domain filter a search carries and an answer repeats. */
    public static final int MAX_DOMAIN_LENGTH = 64;
    private static final int MAX_ID_LENGTH = 255;
    // An entity id as typed into the search (D4's free-text row).
    private static final Pattern TYPED_ENTITY = Pattern.compile("[a-z0-9_]+\\.[a-z0-9_]+");

    private TwinPicker() {
    }

    /**
     * Why an answer has no rows, or why it says more than its rows: the picker shows it under the search field.
     */
    public enum Notice {
        /** Nothing to add. */
        NONE,
        /** The player may not bind (no {@code mineqtt:twin.bind}): no rows at all (D4). */
        NO_BIND,
        /** The twin is off on this server; the detail says why. */
        OFF,
        /** No entity is known: D4's empty-catalog help. */
        EMPTY,
        /** Entities are known, but none matches the query. */
        NO_MATCH,
        /**
         * A target picks only entities Home Assistant lets Minecraft control (M3-14's control filter), and while the catalog
         * decides, none of the target's domains carries the MineQTT control label.
         */
        NO_CONTROL
    }

    /**
     * One entity of an answer.
     *
     * @param value       its state as text, with its unit when it has one
     * @param rgb         a light's colour while it is on, as {@code 0xRRGGBB}
     * @param control     whether Home Assistant's labels let Minecraft control it (the catalog's word)
     * @param stale       whether its value is retained or older, not live
     * @param unavailable whether Home Assistant reports it unavailable or unknown
     */
    public record Row(String entityId, String name, String area, String value, OptionalInt rgb, boolean control, boolean stale, boolean unavailable) {
        public static final StreamCodec<ByteBuf, Row> STREAM_CODEC = StreamCodec.of(Row::write, Row::read);

        public Row {
            entityId = cap(entityId, MAX_ID_LENGTH);
            name = cap(name, MAX_NAME_LENGTH);
            area = cap(area, MAX_AREA_LENGTH);
            value = cap(value, MAX_VALUE_LENGTH);
            if (rgb.isPresent() && (rgb.getAsInt() & ~0xFFFFFF) != 0) throw new IllegalArgumentException("A row's colour is 0xRRGGBB: " + rgb);
        }

        /** The entity's domain, {@code light} of {@code light.kitchen}. */
        public String domain() {
            int dot = entityId.indexOf('.');
            return dot < 0 ? "" : entityId.substring(0, dot);
        }

        /** What the row's first line shows: the friendly name, else the id. */
        public String label() {
            return name.isEmpty() ? entityId : name;
        }

        private static final StreamCodec<ByteBuf, String> ID = ByteBufCodecs.stringUtf8(MAX_ID_LENGTH);
        private static final StreamCodec<ByteBuf, String> NAME = ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH);
        private static final StreamCodec<ByteBuf, String> AREA = ByteBufCodecs.stringUtf8(MAX_AREA_LENGTH);
        private static final StreamCodec<ByteBuf, String> VALUE = ByteBufCodecs.stringUtf8(MAX_VALUE_LENGTH);

        private static void write(ByteBuf buf, Row row) {
            ID.encode(buf, row.entityId);
            NAME.encode(buf, row.name);
            AREA.encode(buf, row.area);
            VALUE.encode(buf, row.value);
            buf.writeBoolean(row.rgb.isPresent());
            if (row.rgb.isPresent()) buf.writeMedium(row.rgb.getAsInt());
            buf.writeByte((row.control ? 1 : 0) | (row.stale ? 2 : 0) | (row.unavailable ? 4 : 0));
        }

        private static Row read(ByteBuf buf) {
            String id = ID.decode(buf);
            String name = NAME.decode(buf);
            String area = AREA.decode(buf);
            String value = VALUE.decode(buf);
            OptionalInt rgb = buf.readBoolean() ? OptionalInt.of(buf.readUnsignedMedium()) : OptionalInt.empty();
            int flags = buf.readUnsignedByte();
            if ((flags & ~7) != 0) throw new DecoderException("A picker row has unknown flags");
            return new Row(id, name, area, value, rgb, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0);
        }
    }

    /** The rows of an answer on the wire: at most {@value #MAX_ROWS}. */
    public static final StreamCodec<ByteBuf, List<Row>> ROWS_CODEC = StreamCodec.of(
            (buf, rows) -> {
                if (rows.size() > MAX_ROWS) throw new io.netty.handler.codec.EncoderException("An answer has more than " + MAX_ROWS + " rows");
                VarInt.write(buf, rows.size());
                rows.forEach(row -> Row.STREAM_CODEC.encode(buf, row));
            },
            buf -> {
                int size = VarInt.read(buf);
                if (size < 0 || size > MAX_ROWS) throw new DecoderException("An answer has " + size + " rows");
                List<Row> rows = new ArrayList<>(size);
                for (int i = 0; i < size; i++) rows.add(Row.STREAM_CODEC.decode(buf));
                return List.copyOf(rows);
            });

    /**
     * Whether an answer ({@code answerKey}, {@code answerQuery}, {@code answerDomain}) is the one to the search the picker
     * sent last: an answer to an older query or to a chip the player has left is dropped (wave 6 review low).
     */
    public static boolean answers(String key, String query, String domain, String answerKey, String answerQuery, String answerDomain) {
        return answerKey.equals(key) && answerQuery.equals(query) && answerDomain.equals(domain);
    }

    /**
     * Whether the picker sends its last search again: no answer to it came within {@value #RESEND_MILLIS} ms (the server
     * drops a search over the rate without one), at most {@value #MAX_RESENDS} times.
     */
    public static boolean resendDue(boolean answered, int resends, long sinceSentMillis) {
        return !answered && resends < MAX_RESENDS && sinceSentMillis >= RESEND_MILLIS;
    }

    /** A domain filter on the wire: one domain, or {@code ""} for every domain the key picks from. */
    public static final StreamCodec<ByteBuf, String> DOMAIN_CODEC = ByteBufCodecs.stringUtf8(MAX_DOMAIN_LENGTH);

    /**
     * What a search lists (M3-17): Home Assistant entities for a binding key, or the loaded MQTT device presets a device
     * of the type may mirror through, for the Twin tab's "MQTT device" source ({@code twin.device}). Both travel in the
     * same search and answer payloads; the answer repeats the kind, so a screen never takes one for the other.
     */
    public enum SearchKind {
        /** Home Assistant entities, for {@code twin.entity} or a target's key. */
        ENTITY,
        /** MQTT device presets, for {@code twin.device}. */
        PRESET;

        public static final StreamCodec<ByteBuf, SearchKind> STREAM_CODEC = StreamCodec.of(
                (buf, kind) -> VarInt.write(buf, kind.ordinal()),
                buf -> {
                    int ordinal = VarInt.read(buf);
                    SearchKind[] kinds = values();
                    if (ordinal < 0 || ordinal >= kinds.length) throw new DecoderException("Unknown search kind " + ordinal);
                    return kinds[ordinal];
                });
    }

    /** The binding key a preset search is for: the device a preset reads ({@code twin.device}, M3-17). */
    public static final String PRESET_KEY = LinkSetting.TWIN_DEVICE.key();

    /**
     * Whether a search of {@code kind} for {@code key} is one a device of a type with this adapter answers: an entity search
     * for one of its {@link #keys}, a preset search for {@link #PRESET_KEY} on a type with a source.
     */
    public static boolean searchable(TwinAdapter adapter, SearchKind kind, String key) {
        return switch (kind) {
            case ENTITY -> keys(adapter).contains(key);
            case PRESET -> adapter.hasSource() && key.equals(PRESET_KEY);
        };
    }

    /**
     * What a preset search answers a player (M3-17): nothing but why for one without {@code mineqtt:twin.bind}; else the
     * loaded presets whose kind the device mirrors ({@code kinds}, most preferred first), matching the query in their id or
     * name (case-blind), of one kind when {@code filter} is one of them, at most {@value #MAX_ROWS}, ordered by kind, then
     * name, then id. A row is the preset's id, its name, no area, its kind as the value, and whether it sends commands as
     * the control badge. Presets are not the Home Assistant twin's: its being off does not hide them (M3-17 decision 2).
     */
    public static Answer presets(boolean mayBind, List<String> kinds, Collection<BindingPreset> loaded, String query, String filter) {
        if (!mayBind) return new Answer(List.of(), Notice.NO_BIND, "");
        List<String> searched = !filter.isEmpty() && kinds.contains(filter) ? List.of(filter) : kinds;
        List<BindingPreset> fitting = loaded.stream().filter(preset -> searched.contains(preset.kind())).toList();
        if (fitting.isEmpty()) return new Answer(List.of(), Notice.EMPTY, "");
        String needle = query.strip().toLowerCase(Locale.ROOT);
        List<Row> rows = fitting.stream()
                .filter(preset -> needle.isEmpty() || preset.id().contains(needle) || preset.name().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(java.util.Comparator.<BindingPreset>comparingInt(preset -> searched.indexOf(preset.kind()))
                        .thenComparing(preset -> preset.name().toLowerCase(Locale.ROOT)).thenComparing(BindingPreset::id))
                .limit(MAX_ROWS)
                .map(preset -> new Row(preset.id(), preset.name(), "", preset.kind(), OptionalInt.empty(), preset.controllable(), false, false))
                .toList();
        return rows.isEmpty() ? new Answer(List.of(), Notice.NO_MATCH, "") : new Answer(rows, Notice.NONE, "");
    }

    /**
     * The binding keys a device of a type with this adapter picks an entity for, in the Twin tab's order: its source
     * ({@code twin.entity}) when it mirrors, then one key per target ({@code twin.target}; {@code twin.left},
     * {@code twin.right}, M3-14).
     */
    public static List<String> keys(TwinAdapter adapter) {
        List<String> keys = new ArrayList<>();
        if (adapter.hasSource()) keys.add(LinkSetting.TWIN_ENTITY.key());
        adapter.targets().keySet().forEach(name -> keys.add(LinkSetting.TWIN_PREFIX + name));
        return List.copyOf(keys);
    }

    /** Whether {@code key} picks a target: an entity the device switches rather than mirrors (M3-14). */
    public static boolean target(TwinAdapter adapter, String key) {
        return key.startsWith(LinkSetting.TWIN_PREFIX) && adapter.targets().containsKey(key.substring(LinkSetting.TWIN_PREFIX.length()));
    }

    /** The name of the target {@code key} picks for ({@code left} of {@code twin.left}); empty for another key. */
    public static Optional<String> targetName(TwinAdapter adapter, String key) {
        return target(adapter, key) ? Optional.of(key.substring(LinkSetting.TWIN_PREFIX.length())) : Optional.empty();
    }

    /**
     * The domains {@code key} picks from, most preferred first (D4's ranking): the source's for {@code twin.entity}, the
     * targets' for a target (M3-14); none for a key the adapter does not pick for.
     */
    public static List<String> domains(TwinAdapter adapter, String key) {
        if (key.equals(LinkSetting.TWIN_ENTITY.key())) return adapter.hasSource() ? adapter.sourceDomains() : List.of();
        return target(adapter, key) ? adapter.targetDomains() : List.of();
    }

    /**
     * M3-14's control filter: whether a target may pick a candidate. Its domain is one Minecraft may control and
     * {@code twin.haActionAllowlist} allows, and while the catalog decides, Home Assistant labelled it for control (the
     * control blueprint would answer {@code not_labelled} otherwise). Without a catalog the labels are not known here.
     */
    public static boolean controllable(EntitySearch.Candidate candidate, boolean catalogMode, Collection<String> allowedDomains) {
        return HaDomains.controllable(candidate.domain()) && allowedDomains.contains(candidate.domain()) && (!catalogMode || candidate.control());
    }

    /**
     * The domains a search asks for: {@code filter} alone when it is one of the key's (a domain chip), else all of them.
     */
    public static List<String> searched(TwinAdapter adapter, String key, String filter) {
        List<String> domains = domains(adapter, key);
        return !filter.isEmpty() && domains.contains(filter) ? List.of(filter) : domains;
    }

    /**
     * The rows of an answer, made from the twin's ranked candidates: at most {@value #MAX_ROWS}, in ranking order.
     *
     * @param documents what the twin knows of an entity: its freshness, unit and colour
     */
    public static List<Row> rows(List<EntitySearch.Candidate> ranked, Function<String, Optional<EntityDocument>> documents) {
        return rows(ranked, documents, null);
    }

    /** {@link #rows(List, Function)} of a target's search: only what the control filter lets through (M3-14). */
    public static List<Row> rows(List<EntitySearch.Candidate> ranked, Function<String, Optional<EntityDocument>> documents, ControlFilter filter) {
        List<Row> rows = new ArrayList<>();
        for (EntitySearch.Candidate candidate : ranked) {
            if (rows.size() == MAX_ROWS) break;
            if (filter != null && !filter.test(candidate)) continue;
            Optional<EntityDocument> document = documents.apply(candidate.entityId());
            String value = candidate.state().wire();
            Optional<String> unit = document.flatMap(known -> known.value("unit_of_measurement")).filter(JsonElement::isJsonPrimitive)
                    .map(JsonElement::getAsString).filter(text -> !text.isBlank());
            if (unit.isPresent() && candidate.state().known()) value = value + " " + unit.get();
            rows.add(new Row(candidate.entityId(), candidate.name(), candidate.area(), value, document.map(TwinPicker::rgb).orElse(OptionalInt.empty()),
                    candidate.control(), document.map(known -> known.freshness() != EntityDocument.Freshness.LIVE).orElse(false),
                    !candidate.state().known()));
        }
        return List.copyOf(rows);
    }

    /**
     * An answer to a search.
     *
     * @param rows   the rows, grouped by area
     * @param detail what the notice names: why the twin is off; {@code ""} otherwise
     */
    public record Answer(List<Row> rows, Notice notice, String detail) {
        public Answer {
            rows = List.copyOf(rows);
        }
    }

    /**
     * What a search answers a player (D4, D13): nothing but why for one who may not bind or while the twin is off, so the
     * twin is not even asked; else the ranked candidates the read gate let through as rows, grouped by area, and why there
     * are none.
     *
     * @param mayBind  whether the player holds {@code mineqtt:twin.bind}
     * @param off      why the twin is off on the server
     * @param ranked   the twin's candidates for the search, read-gated and ranked ({@code TwinRuntime#entities}); for a
     *                 target all of them, as the control filter comes after the ranking
     * @param anyKnown whether the read gate lets any entity through at all
     * @param filter   M3-14's control filter for a target; null for a source
     */
    public static Answer answer(boolean mayBind, Optional<String> off, Supplier<List<EntitySearch.Candidate>> ranked, BooleanSupplier anyKnown,
                                Function<String, Optional<EntityDocument>> documents, ControlFilter filter, String query) {
        if (!mayBind) return new Answer(List.of(), Notice.NO_BIND, "");
        if (off.isPresent()) return new Answer(List.of(), Notice.OFF, off.get());
        List<Row> rows = rows(ranked.get(), documents, filter);
        if (!rows.isEmpty()) return new Answer(grouped(rows), Notice.NONE, "");
        if (!anyKnown.getAsBoolean()) return new Answer(List.of(), Notice.EMPTY, "");
        // A target in catalog mode with nothing to offer at all: the labels are what is missing.
        boolean unlabelled = filter != null && filter.catalogMode() && query.isBlank();
        return new Answer(List.of(), unlabelled ? Notice.NO_CONTROL : Notice.NO_MATCH, "");
    }

    /**
     * M3-14's control filter of a target's search ({@link #controllable}).
     *
     * @param catalogMode    whether the catalog decides, so its control label counts
     * @param allowedDomains {@code twin.haActionAllowlist}
     */
    public record ControlFilter(boolean catalogMode, Collection<String> allowedDomains) {
        public ControlFilter {
            allowedDomains = List.copyOf(allowedDomains);
        }

        public boolean test(EntitySearch.Candidate candidate) {
            return controllable(candidate, catalogMode, allowedDomains);
        }
    }

    /**
     * The rows grouped by area (D4): each area once, in the order of its best-ranked row, the rows of an area in their
     * ranking order, rows without an area last.
     */
    public static List<Row> grouped(List<Row> rows) {
        Map<String, List<Row>> areas = new LinkedHashMap<>();
        for (Row row : rows) {
            if (!row.area().isEmpty()) areas.computeIfAbsent(row.area().toLowerCase(Locale.ROOT), area -> new ArrayList<>()).add(row);
        }
        List<Row> grouped = new ArrayList<>();
        areas.values().forEach(grouped::addAll);
        rows.stream().filter(row -> row.area().isEmpty()).forEach(grouped::add);
        return List.copyOf(grouped);
    }

    /**
     * D4's free-text row: a query spelled like an entity id of one of {@code domains} that no row lists, offered as
     * "Use light.xyz (not seen yet)"; the bound entity then waits. Players never have to type entity ids.
     */
    public static Optional<String> freeText(String query, List<Row> rows, List<String> domains) {
        String typed = query.strip();
        if (!TYPED_ENTITY.matcher(typed).matches() || typed.length() > MAX_ID_LENGTH) return Optional.empty();
        String domain = typed.substring(0, typed.indexOf('.'));
        if (!domains.contains(domain) || rows.stream().anyMatch(row -> row.entityId().equals(typed))) return Optional.empty();
        return Optional.of(typed);
    }

    /** The name a block is given after the entity it binds (the picker's "Name the block after it"): the friendly name, else the id. */
    public static String blockName(Row row) {
        return DeviceTabs.name(cap(row.label(), LinkSetting.MAX_NAME_LENGTH));
    }

    // A light's colour while it reads one: rgb_color [r, g, b].
    private static OptionalInt rgb(EntityDocument document) {
        Optional<JsonElement> color = document.value("rgb_color");
        if (color.isEmpty() || !color.get().isJsonArray()) return OptionalInt.empty();
        JsonArray rgb = color.get().getAsJsonArray();
        if (rgb.size() != 3) return OptionalInt.empty();
        int packed = 0;
        for (JsonElement channel : rgb) {
            if (!channel.isJsonPrimitive() || !channel.getAsJsonPrimitive().isNumber()) return OptionalInt.empty();
            packed = packed << 8 | Math.clamp(Math.round(channel.getAsDouble()), 0, 255);
        }
        return OptionalInt.of(packed);
    }

    /** The recently bound entities a player keeps for D4's ranking. */
    public static final int MAX_RECENT = 8;

    /** {@code recent} with {@code entity} as the newest, at most {@value #MAX_RECENT}. */
    public static void remember(Set<String> recent, String entity) {
        recent.remove(entity);
        recent.add(entity);
        while (recent.size() > MAX_RECENT) recent.remove(recent.iterator().next());
    }

    private static String cap(String text, int max) {
        if (text.length() <= max) return text;
        int end = max;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }
}

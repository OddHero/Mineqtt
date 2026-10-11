package art.rehra.mineqtt.device.types;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.device.Access;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.Field;
import art.rehra.mineqtt.device.RawLinkSpec;
import art.rehra.mineqtt.device.Setting;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.UnloadedPolicy;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.flow.core.Coerce;
import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.ha.Ha;
import art.rehra.mineqtt.twin.TwinAdapter;
import art.rehra.mineqtt.twin.core.Binding;
import art.rehra.mineqtt.twin.core.DisplayText;
import art.rehra.mineqtt.twin.core.HaDomains;
import art.rehra.mineqtt.twin.core.PortRead;
import art.rehra.mineqtt.twin.core.ValueMap;
import art.rehra.mineqtt.twin.core.ValuePath;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.DyeColor;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The Display Panel ({@code display_panel}): a wall plate that shows a message in up to four lines, and gives it to a
 * comparator as a level (M3 plan D18, design §7.2). Its own Home Assistant {@code text} entity, any wire into its
 * {@code message} port, {@code /mineqtt devices set} and its Raw Link set the message; its colour, glow and text size
 * are settings. Bound to any readable Home Assistant entity, it mirrors that entity's value with its unit instead, read-only,
 * under its own name ({@link #TWIN}).
 * <p>
 * The comparator follows one rule ({@link #comparator}): a gauge for numbers, on/off for words, presence for text. The
 * server lays the text out ({@link DisplayText}); the client only draws the lines.
 */
public final class DisplayPanel {
    /** The longest message: what a Home Assistant {@code text} entity takes. */
    public static final int MAX_MESSAGE_LENGTH = 255;
    /** The colour a new panel's text has. */
    public static final DyeColor DEFAULT_COLOR = DyeColor.CYAN;
    // The attributes the panel reads besides its value: the unit it shows, the range and the options its level needs.
    private static final String[] EXTRA_LEAVES = {"unit_of_measurement", "min", "max", "options"};

    /** A panel's text size: {@link DisplayText.Size} as a setting. */
    public enum Size implements StringRepresentable {
        AUTO(DisplayText.Size.AUTO),
        LARGE(DisplayText.Size.LARGE),
        MEDIUM(DisplayText.Size.MEDIUM),
        SMALL(DisplayText.Size.SMALL);

        private final DisplayText.Size layout;

        Size(DisplayText.Size layout) {
            this.layout = layout;
        }

        /** The layout size it asks for; {@link DisplayText.Size#AUTO} picks one per text. */
        public DisplayText.Size layout() {
            return layout;
        }

        @Override
        public String getSerializedName() {
            return layout.serializedName();
        }
    }

    /** The text it shows; line breaks start a new line. */
    public static final Field<String> MESSAGE = Field.lines("message", MAX_MESSAGE_LENGTH, Access.READ_WRITE);
    /** The comparator level of what it shows ({@link #comparator}). */
    public static final Field<Integer> LEVEL = Field.level("level", Access.READ);
    public static final Field<DyeColor> COLOR = Field.enumOf("color", DyeColor.class, DEFAULT_COLOR, Access.READ_WRITE);
    /** Full-bright text, readable in the dark. */
    public static final Field<Boolean> GLOW = Field.bool("glow", true, Access.READ_WRITE);
    public static final Field<Size> SIZE = Field.enumOf("size", Size.class, Size.AUTO, Access.READ_WRITE);

    /**
     * How a panel mirrors a Home Assistant entity (M3 plan D5, D18): any readable domain, its value (the state, or the
     * value attribute of the domain's first out-port such as a climate's {@code current_temperature}, or
     * {@code twin.path}) as text with its unit and {@code twin.decimals} ({@link ValueMap.Text}). The comparator level
     * comes from the same value with the entity's range or options ({@link #comparator}, an extra of the apply).
     * Read-only (open question 6): its message changes only with the entity. {@code twin.unavailable off} clears it.
     */
    public static final TwinAdapter TWIN = TwinAdapter.builder(MESSAGE.key())
            .source(DisplayPanel::twinReads, HaDomains.readableDomains().toArray(String[]::new))
            .shapes(TwinAdapter.Shape.PATH, TwinAdapter.Shape.RANGE, TwinAdapter.Shape.DECIMALS)
            .leaves(EXTRA_LEAVES)
            .off(MESSAGE.key(), new JsonPrimitive(""))
            .extras(DisplayPanel::twinExtras)
            .build();

    public static final DeviceType TYPE = DeviceType.builder(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "display_panel"))
            .model("Display Panel").unloaded(UnloadedPolicy.QUEUE)
            .field(MESSAGE).field(LEVEL).field(COLOR).field(GLOW).field(SIZE).field(CommonFields.LOCATION)
            .setting(new Setting<>(COLOR)).setting(new Setting<>(GLOW)).setting(new Setting<>(SIZE))
            .entity(Ha.text("message", MESSAGE).name(null))
            .entity(Ha.sensor("level", LEVEL).name("Comparator level").stateClass("measurement").diagnostic().disabledByDefault())
            .entity(Ha.sensor("location", CommonFields.LOCATION).name("Location").diagnostic().disabledByDefault())
            // A status topic is usually retained: a panel shows its last message as soon as it subscribes.
            .rawLink(RawLinkSpec.in(DisplayPanel::rawCommand).withoutCustomPayloads().acceptRetained(true))
            .preview(DisplayPanel::preview)
            .twin(TWIN)
            .build();

    private DisplayPanel() {
    }

    /** A panel's state: its message, what it looks like, and its comparator level. */
    public record Panel(String message, DyeColor color, boolean glow, Size size, int level) {
        public static final Panel DEFAULT = new Panel("", DEFAULT_COLOR, true, Size.AUTO, 0);
        /** The panel in the block entity's {@code state} compound; unreadable values keep their default. */
        public static final Codec<Panel> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                MESSAGE.codec().lenientOptionalFieldOf(MESSAGE.key(), "").forGetter(Panel::message),
                COLOR.codec().lenientOptionalFieldOf(COLOR.key(), DEFAULT_COLOR).forGetter(Panel::color),
                GLOW.codec().lenientOptionalFieldOf(GLOW.key(), true).forGetter(Panel::glow),
                SIZE.codec().lenientOptionalFieldOf(SIZE.key(), Size.AUTO).forGetter(Panel::size),
                LEVEL.codec().lenientOptionalFieldOf(LEVEL.key(), 0).forGetter(Panel::level)
        ).apply(instance, Panel::new));

        public Panel {
            message = ValueCodecs.sanitizeLines(message, MAX_MESSAGE_LENGTH);
            level = Math.clamp(level, 0, PortType.MAX_LEVEL);
        }

        /** The panel a state JSON shows, e.g. an unloaded panel's record; what is missing keeps its default. */
        public static Panel of(JsonObject state) {
            return new Panel(read(state, MESSAGE), read(state, COLOR), read(state, GLOW), read(state, SIZE), read(state, LEVEL));
        }

        /**
         * The panel after a partial command: {@code message}, {@code color}, {@code glow}, {@code size}. A new message
         * from anywhere but the panel's twin gets the level {@link #level(String)} gives it; the twin's own apply brings
         * the level it read with the entity's range or options under {@link TwinAdapter#EXTRAS_KEY}, honoured only then.
         *
         * @param twin whether the command is the panel's own twin apply
         * @return empty when the command has none of these keys in a readable form
         */
        public Optional<Panel> apply(CommandReader command, boolean twin) {
            Optional<String> newMessage = command.get(MESSAGE);
            Optional<DyeColor> newColor = command.get(COLOR);
            Optional<Boolean> newGlow = command.get(GLOW);
            Optional<Size> newSize = command.get(SIZE);
            if (newMessage.isEmpty() && newColor.isEmpty() && newGlow.isEmpty() && newSize.isEmpty()) return Optional.empty();
            String text = newMessage.orElse(message);
            int newLevel = newMessage.isEmpty() ? level : twin ? twinLevel(command).orElseGet(() -> DisplayPanel.level(text)) : DisplayPanel.level(text);
            return Optional.of(new Panel(text, newColor.orElse(color), newGlow.orElse(glow), newSize.orElse(size), newLevel));
        }

        /** The panel with another comparator level, e.g. 0 while its source has no value. */
        public Panel withLevel(int level) {
            return new Panel(message, color, glow, size, level);
        }

        /** Writes every readable field but the location. */
        public StateWriter write(StateWriter out) {
            return out.put(MESSAGE, message).put(LEVEL, level).put(COLOR, color).put(GLOW, glow).put(SIZE, size);
        }

        private static <T> T read(JsonObject state, Field<T> field) {
            JsonElement value = state.get(field.key());
            return value == null ? field.defaultValue() : field.parse(value).orElse(field.defaultValue());
        }

        // The level a twin apply read with the entity's range or options; empty for none or a malformed one.
        private static Optional<Integer> twinLevel(CommandReader command) {
            JsonElement extras = command.json().get(TwinAdapter.EXTRAS_KEY);
            if (extras == null || !extras.isJsonObject()) return Optional.empty();
            JsonElement level = extras.getAsJsonObject().get(LEVEL.key());
            return level == null ? Optional.empty() : LEVEL.parse(level);
        }
    }

    /**
     * What a client draws of a panel (M3 plan D18, D13 #7): finished lines, never the entity, its id or the owner.
     *
     * @param title   the small line over a mirrored panel's value: its device name; null for an unbound panel
     * @param lines   the value laid out on the server, at most {@value DisplayText#MAX_LINES} lines of
     *                {@value DisplayText#MAX_COLUMNS} characters; empty while it shows a point in time
     * @param layout  the size the lines were laid out in, never {@link DisplayText.Size#AUTO}
     * @param size    the size setting, which a client lays a point in time out in
     * @param at      the point in time the message names, as epoch milliseconds, which the client shows relative to its
     *                own clock; empty for any other message
     * @param waiting whether the panel mirrors an entity it has no value of yet ("Waiting for Home Assistant…")
     */
    public record Render(@Nullable String title, List<String> lines, DisplayText.Size layout, Size size, DyeColor color, boolean glow,
                         OptionalLong at, boolean waiting) {
        public Render {
            lines = List.copyOf(lines);
            if (layout == DisplayText.Size.AUTO) throw new IllegalArgumentException("A render has a resolved layout");
        }

        /**
         * What a panel shows: its message laid out in its size ({@link DisplayText#layout}), under its title when it
         * mirrors an entity ({@link DisplayText#titled}); a message that names a point in time travels as that point.
         *
         * @param title the device name of a mirrored panel, null for an unbound one
         */
        public static Render of(Panel panel, @Nullable String title, boolean waiting) {
            OptionalLong at = timestamp(panel.message());
            DisplayText.Layout layout = at.isPresent() ? null
                    : title == null ? DisplayText.layout(panel.message(), panel.size().layout()) : DisplayText.titled(title, panel.message(), panel.size().layout());
            String shownTitle = layout != null ? layout.title() : title == null ? null : DisplayText.titled(title, "", DisplayText.Size.SMALL).title();
            return new Render(shownTitle, layout == null ? List.of() : layout.lines(), layout == null ? DisplayText.Size.LARGE : layout.size(),
                    panel.size(), panel.color(), panel.glow(), at, waiting);
        }
    }

    /**
     * The comparator level of a message, as an unbound panel gives it ({@link #comparator} without a document or a range):
     * {@code "7"} gives 7.
     */
    public static int level(String message) {
        return comparator(new JsonPrimitive(message), JsonNull.INSTANCE, null, null);
    }

    /**
     * The comparator level of a value (M3 plan D18), one rule for every panel: a gauge for numbers, on/off for words,
     * presence for text.
     * <ul>
     *     <li>{@code unavailable}, {@code unknown}, empty text and no value give 0 (a cleared or missing source never
     *     reaches here: the panel's health sets 0 then);</li>
     *     <li>a number is mapped linearly from {@code [min, max]} to 0–15 and clamped: the bounds left null come from the
     *     document's {@code min}/{@code max}, else 0–15, so {@code "7"} gives 7 ({@link ValueMap.LevelByRange});</li>
     *     <li>an on or off spelling ({@code on}, {@code open}, {@code home}, …) gives 15 or 0;</li>
     *     <li>one of the document's {@code options} gives its index, clamped to 15;</li>
     *     <li>any other text gives 15.</li>
     * </ul>
     * Numbers win over the spellings {@code 1} and {@code 0}. Pure.
     *
     * @param document the source's document for its range or options, {@link JsonNull} for none
     */
    public static int comparator(JsonElement value, JsonElement document, @Nullable BigDecimal min, @Nullable BigDecimal max) {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return 0;
        String text = value.getAsString().strip();
        String word = text.toLowerCase(Locale.ROOT);
        if (text.isEmpty() || word.equals(ValueMap.UNAVAILABLE) || word.equals(ValueMap.UNKNOWN)) return 0;
        Optional<BigDecimal> number = Coerce.numeric(value);
        if (number.isPresent()) return new ValueMap.LevelByRange(min, max, false).level(number.get(), document);
        Optional<JsonElement> on = new ValueMap.Bool(false).apply(value);
        if (on.isPresent()) return on.get().getAsBoolean() ? PortType.MAX_LEVEL : 0;
        Optional<JsonElement> option = new ValueMap.LevelByOption(null).apply(value, document);
        return option.map(JsonElement::getAsInt).orElse(PortType.MAX_LEVEL);
    }

    /**
     * The point in time a message names, as epoch milliseconds, so clients show it relative to their own clock ("in 2 h
     * 14 min"): an ISO-8601 date and time with an offset, as Home Assistant writes a timestamp sensor's state
     * ({@link ValueMap.Timestamp}). Empty for anything else, a bare time of day among it.
     */
    public static OptionalLong timestamp(String message) {
        String text = message.strip();
        if (text.isEmpty() || text.indexOf('\n') >= 0) return OptionalLong.empty();
        Optional<JsonElement> instant = new ValueMap.Timestamp().apply(new JsonPrimitive(text));
        return instant.filter(value -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber())
                .map(value -> OptionalLong.of(value.getAsLong())).orElse(OptionalLong.empty());
    }

    /**
     * How a client shows a point in time against its own clock (M3 plan D18), so it moves on without a server update:
     * {@link Kind#NOW} within a minute; within 12 hours relative, {@link Kind#IN} or {@link Kind#AGO} with whole hours
     * and minutes ("in 2 h 14 min"); within a day the clock time ({@link Kind#CLOCK}, "07:30"); further away the date and
     * time ({@link Kind#DATE}). Pure; the client words it.
     *
     * @param hours   whole hours of a relative time
     * @param minutes the minutes after those hours
     * @param local   the point in time on the client's clock
     */
    public record TimeShown(Kind kind, long hours, long minutes, LocalDateTime local) {
        public enum Kind {
            NOW,
            IN,
            AGO,
            CLOCK,
            DATE
        }

        private static final long MINUTE = 60_000;
        private static final long RELATIVE = 12 * 60 * MINUTE;
        private static final long DAY = 24 * 60 * MINUTE;

        /** {@code at} as a client at {@code now} in {@code zone} shows it. */
        public static TimeShown of(long at, long now, ZoneId zone) {
            LocalDateTime local = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), zone);
            long delta = at - now;
            long distance = Math.abs(delta);
            if (distance < MINUTE) return new TimeShown(Kind.NOW, 0, 0, local);
            if (distance < RELATIVE) {
                long minutes = distance / MINUTE;
                return new TimeShown(delta > 0 ? Kind.IN : Kind.AGO, minutes / 60, minutes % 60, local);
            }
            return new TimeShown(distance < DAY ? Kind.CLOCK : Kind.DATE, 0, 0, local);
        }

        /** The clock time, {@code 07:30}. */
        public String clock() {
            return String.format(Locale.ROOT, "%02d:%02d", local.getHour(), local.getMinute());
        }

        /** The date and the clock time, {@code 10-12 07:30}. */
        public String date() {
            return String.format(Locale.ROOT, "%02d-%02d %s", local.getMonthValue(), local.getDayOfMonth(), clock());
        }
    }

    /**
     * What a panel bound to an entity of {@code domain} shows by default: the value of the domain's first out-port (its
     * state, or the attribute it reads such as a climate's {@code current_temperature}), else its state.
     */
    public static ValuePath defaultPath(String domain) {
        String leaf = HaDomains.domain(domain).filter(known -> !known.outPorts().isEmpty())
                .map(known -> known.outPorts().getFirst().leaves().getFirst()).orElse(ValuePath.STATE);
        return leaf.equals(ValuePath.STATE) ? ValuePath.keys(ValuePath.STATE) : ValuePath.keys(ValuePath.ATTRIBUTES, leaf);
    }

    // The value as text with its unit and the binding's decimals, from twin.path or the domain's default.
    private static Map<String, PortRead> twinReads(String domain, Binding.Settings settings) {
        return Map.of(MESSAGE.key(), new PortRead(path(domain, settings), new ValueMap.Text(settings.decimals(), null, MAX_MESSAGE_LENGTH)));
    }

    // The comparator level of the value the message shows, with the binding's range over the entity's own.
    private static JsonObject twinExtras(JsonObject document, String domain, Binding binding) {
        Binding.Settings settings = binding.settings();
        JsonElement value = path(domain, settings).select(document).orElse(JsonNull.INSTANCE);
        JsonObject extras = new JsonObject();
        extras.addProperty(LEVEL.key(), comparator(value, document, settings.min(), settings.max()));
        return extras;
    }

    private static ValuePath path(String domain, Binding.Settings settings) {
        return settings.path() != null ? settings.path() : defaultPath(domain);
    }

    /**
     * A Raw Link payload: the message it shows, sanitised and capped at {@value #MAX_MESSAGE_LENGTH} characters, its
     * line breaks kept. Blank text clears the panel; an empty payload (a cleared retained topic) commands nothing.
     */
    static Optional<JsonObject> rawCommand(String payload, ValueCodecs.CustomPayloads custom) {
        if (payload.isEmpty()) return Optional.empty();
        JsonObject command = new JsonObject();
        command.addProperty(MESSAGE.key(), ValueCodecs.sanitizeLines(payload.strip(), MAX_MESSAGE_LENGTH));
        return Optional.of(command);
    }

    // An unloaded panel's state after a command: applied as a loaded panel applies it, the level from its new message.
    private static JsonObject preview(JsonObject state, CommandReader command) {
        Panel panel = Panel.of(state);
        return panel.apply(command, false).map(next -> {
            JsonObject written = next.write(new StateWriter()).json();
            JsonElement location = state.get(CommonFields.LOCATION.key());
            if (location != null) written.add(CommonFields.LOCATION.key(), location);
            return written;
        }).orElse(state);
    }
}

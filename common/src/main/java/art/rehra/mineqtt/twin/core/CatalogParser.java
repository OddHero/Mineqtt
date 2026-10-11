package art.rehra.mineqtt.twin.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Reads what the catalog blueprint publishes under {@code <statestreamBase>/_mineqtt/} (M3 plan D3), retained at QoS 1:
 * <ul>
 *     <li>{@code catalog/<domain>}: a JSON list of entries, one list per supported domain. An empty list (or a cleared
 *     topic) means no entity of that domain is labelled, so unlabelling clears it.</li>
 *     <li>{@code status}: {@code {"v":1,"blueprint":"catalog","generated":…,"read_label":…,"control_label":…,"entities":N}};
 *     a status with {@code v} switches the read gate to catalog mode.</li>
 * </ul>
 * A domain topic over {@link #MAX_TOPIC_BYTES} (the mirror cap, about 200 entities) or one that is not a JSON list is
 * refused. Entries that are not objects, lack a valid {@code id}, belong to another domain or repeat an id are skipped
 * and counted. Parsing is strict JSON, as Home Assistant's {@code tojson} writes it.
 */
public final class CatalogParser {
    /** A domain list or status larger than this is refused: the mirror subscription cap. */
    public static final int MAX_TOPIC_BYTES = 64 * 1024;
    /** The status version this reader understands; a status needs a {@code v} of at least 1. */
    public static final int VERSION = 1;

    /** Why a whole domain topic was refused. */
    public enum Refusal {
        /** Larger than {@link #MAX_TOPIC_BYTES}. */
        TOO_LARGE,
        /** Not a strict JSON list. */
        MALFORMED
    }

    /**
     * One domain topic, read.
     *
     * @param entries the entries in list order, empty for an empty or cleared list and for a refused topic
     * @param skipped entries left out (not an object, no or an invalid {@code id}, another domain, a repeated id)
     * @param refusal why the whole topic was refused, or null when it was read
     */
    public record DomainList(String domain, List<CatalogEntry> entries, int skipped, Refusal refusal) {
        public DomainList {
            Objects.requireNonNull(domain, "domain");
            entries = List.copyOf(entries);
            if (refusal != null && !entries.isEmpty()) throw new IllegalArgumentException("A refused list has no entries");
        }

        public boolean refused() {
            return refusal != null;
        }
    }

    /**
     * The catalog blueprint's status.
     *
     * @param version      {@code v}
     * @param blueprint    {@code blueprint}, {@code ""} when absent
     * @param generated    {@code generated}, or null when absent or not a timestamp
     * @param readLabel    {@code read_label}, {@code ""} when absent
     * @param controlLabel {@code control_label}, {@code ""} when absent
     * @param entities     {@code entities}, or -1 when absent
     */
    public record Status(int version, String blueprint, Instant generated, String readLabel, String controlLabel, int entities) {
        public Status {
            if (version < 1) throw new IllegalArgumentException("A status version is at least 1: " + version);
            Objects.requireNonNull(blueprint, "blueprint");
            Objects.requireNonNull(readLabel, "readLabel");
            Objects.requireNonNull(controlLabel, "controlLabel");
        }

        /**
         * Catalog health (D3): whether the catalog automation may be off, because {@code generated} is missing or older
         * than {@link #STALE_AFTER} at {@code now}. The last catalog still gates.
         */
        public boolean stale(Instant now) {
            return generated == null || generated.plus(STALE_AFTER).isBefore(now);
        }
    }

    /** A catalog whose status was generated longer ago than this is reported as possibly off (D3: 30 min, 3 cycles). */
    public static final java.time.Duration STALE_AFTER = java.time.Duration.ofMinutes(30);

    private CatalogParser() {
    }

    /** Reads the list published on {@code catalog/<domain>}. A zero-length payload is a cleared topic: no entries. */
    public static DomainList parseDomain(String domain, byte[] payload) {
        if (!StatestreamTopic.DOMAIN.matcher(domain).matches()) throw new IllegalArgumentException("Not a domain: " + domain);
        if (payload.length > MAX_TOPIC_BYTES) return new DomainList(domain, List.of(), 0, Refusal.TOO_LARGE);
        if (payload.length == 0) return new DomainList(domain, List.of(), 0, null);
        Optional<JsonElement> json = EntityDocuments.strictJson(new String(payload, StandardCharsets.UTF_8));
        if (json.isEmpty() || !json.get().isJsonArray()) return new DomainList(domain, List.of(), 0, Refusal.MALFORMED);
        List<CatalogEntry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int skipped = 0;
        for (JsonElement element : json.get().getAsJsonArray()) {
            Optional<CatalogEntry> entry = entry(element);
            if (entry.isEmpty() || !entry.get().domain().equals(domain) || !seen.add(entry.get().entityId())) {
                skipped++;
            } else {
                entries.add(entry.get());
            }
        }
        return new DomainList(domain, entries, skipped, null);
    }

    /** Reads the status; empty when it is not a JSON object with an integer {@code v} of at least 1 or is too large. */
    public static Optional<Status> parseStatus(byte[] payload) {
        if (payload.length == 0 || payload.length > MAX_TOPIC_BYTES) return Optional.empty();
        Optional<JsonElement> json = EntityDocuments.strictJson(new String(payload, StandardCharsets.UTF_8));
        if (json.isEmpty() || !json.get().isJsonObject()) return Optional.empty();
        JsonObject status = json.get().getAsJsonObject();
        Optional<Integer> version = integer(status.get("v"));
        if (version.isEmpty() || version.get() < 1) return Optional.empty();
        return Optional.of(new Status(version.get(), string(status.get("blueprint")).orElse(""), HaTime.parse(status.get("generated")).orElse(null),
                string(status.get("read_label")).orElse(""), string(status.get("control_label")).orElse(""),
                integer(status.get("entities")).orElse(-1)));
    }

    // One entry; empty when it is not an object with a valid id. Missing fields take their defaults.
    private static Optional<CatalogEntry> entry(JsonElement element) {
        if (!element.isJsonObject()) return Optional.empty();
        JsonObject entry = element.getAsJsonObject();
        Optional<String> id = string(entry.get("id")).filter(StatestreamTopic::isEntityId);
        if (id.isEmpty()) return Optional.empty();
        StatestreamValue state = string(entry.get("state")).map(StatestreamValue::of).orElse(StatestreamValue.UNKNOWN);
        JsonElement control = entry.get("control");
        JsonElement attributes = entry.get("attributes");
        return Optional.of(new CatalogEntry(id.get(), string(entry.get("name")).orElse(id.get()), string(entry.get("area")).orElse(""),
                control != null && control.isJsonPrimitive() && control.getAsJsonPrimitive().isBoolean() && control.getAsBoolean(), state,
                HaTime.parse(entry.get("last_updated")).orElse(null),
                attributes != null && attributes.isJsonObject() ? attributes.getAsJsonObject().asMap() : Map.of()));
    }

    private static Optional<String> string(JsonElement json) {
        return json != null && json.isJsonPrimitive() && json.getAsJsonPrimitive().isString() ? Optional.of(json.getAsString()) : Optional.empty();
    }

    private static Optional<Integer> integer(JsonElement json) {
        if (json == null || !json.isJsonPrimitive() || !json.getAsJsonPrimitive().isNumber()) return Optional.empty();
        JsonPrimitive number = json.getAsJsonPrimitive();
        try {
            return Optional.of(number.getAsBigDecimal().intValueExact());
        } catch (ArithmeticException | NumberFormatException e) {
            return Optional.empty();
        }
    }
}

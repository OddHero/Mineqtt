package art.rehra.mineqtt.twin.core;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The statestream fallback scan of the entity catalog (M3 plan D4): without the catalog blueprint, the {@code state} and
 * {@code friendly_name} leaves of every entity the statestream include publishes give the picker its rows. Pure and
 * single-threaded; the caller subscribes {@code <base>/+/+/state} and {@code <base>/+/+/friendly_name}.
 * <ul>
 *     <li>At most {@code maxEntities} entities ({@code twin.catalogMax}), first seen wins; the rest are counted.</li>
 *     <li>Only readable domains ({@link HaDomains#readable}): the D2 privacy domains and unknown ones are never kept.</li>
 *     <li>A cleared {@code state} (a deleted retained topic) forgets the entity; one over {@value #MAX_STATE_BYTES}
 *     bytes is ignored (no Home Assistant state is that long).</li>
 *     <li>A {@code friendly_name} is a JSON string as statestream writes it, kept to {@value #MAX_NAME_LENGTH} characters;
 *     anything else is ignored.</li>
 * </ul>
 */
public final class EntityScan {
    /** The longest friendly name kept. */
    public static final int MAX_NAME_LENGTH = 64;
    /** The longest state kept: Home Assistant's states are at most 255 characters, so 1020 bytes of UTF-8. */
    public static final int MAX_STATE_BYTES = 1024;

    /**
     * One entity the scan saw.
     *
     * @param name its friendly name, or the entity id until one arrives
     */
    public record Row(String entityId, String name, StatestreamValue state) {
        public Row {
            if (!StatestreamTopic.isEntityId(entityId)) throw new IllegalArgumentException("Not an entity id: " + entityId);
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(state, "state");
        }
    }

    private final String base;
    private final int maxEntities;
    private final Map<String, Row> rows = new LinkedHashMap<>();
    private long overflow;

    public EntityScan(String base, int maxEntities) {
        if (maxEntities <= 0) throw new IllegalArgumentException("maxEntities must be positive: " + maxEntities);
        this.base = StatestreamTopic.normalizeBase(base);
        this.maxEntities = maxEntities;
    }

    /** One scanned message; true when a row was added, changed or forgotten. */
    public boolean accept(String topic, byte[] payload) {
        if (!(StatestreamTopic.parse(base, topic) instanceof StatestreamTopic.Entity leaf) || !HaDomains.readable(leaf.domain())) return false;
        String id = leaf.entityId();
        Row row = rows.get(id);
        if (leaf.kind() == StatestreamTopic.LeafKind.STATE) {
            // No Home Assistant state is that long; keeping it would let any client pin 64 KiB per row.
            if (payload.length > MAX_STATE_BYTES) return false;
            StatestreamValue state = StatestreamValue.ofPayload(payload);
            if (state instanceof StatestreamValue.Cleared) return rows.remove(id) != null;
            if (row == null && !room()) return false;
            Row next = new Row(id, row == null ? id : row.name(), state);
            return !next.equals(rows.put(id, next));
        }
        if (!leaf.leaf().equals("friendly_name")) return false;
        Optional<String> name = EntityDocuments.strictJson(new String(payload, StandardCharsets.UTF_8))
                .filter(json -> json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()).map(json -> json.getAsString().strip())
                .filter(text -> !text.isEmpty()).map(text -> text.length() > MAX_NAME_LENGTH ? text.substring(0, MAX_NAME_LENGTH) : text);
        if (name.isEmpty()) return false;
        if (row == null && !room()) return false;
        Row next = new Row(id, name.get(), row == null ? StatestreamValue.UNKNOWN : row.state());
        return !next.equals(rows.put(id, next));
    }

    // Whether a new entity fits; counted when it does not.
    private boolean room() {
        if (rows.size() < maxEntities) return true;
        overflow++;
        return false;
    }

    /** The entities seen, in the order they were first seen. */
    public Map<String, Row> rows() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(rows));
    }

    public int size() {
        return rows.size();
    }

    /** Messages of new entities left out because the scan was full. */
    public long overflow() {
        return overflow;
    }

    /** Forgets every row (the catalog arrived, or the base changed); the overflow count stays. */
    public void clear() {
        rows.clear();
    }
}

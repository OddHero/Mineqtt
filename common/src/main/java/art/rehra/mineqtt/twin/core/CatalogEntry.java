package art.rehra.mineqtt.twin.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One entity of the catalog blueprint's list (M3 plan D3): an entity Home Assistant's admin labelled for MineQTT, with a
 * fresh snapshot of its state.
 *
 * @param entityId    {@code <domain>.<object_id>}
 * @param name        the friendly name; the entity id when the blueprint sent none
 * @param area        the area name, or {@code ""} for none
 * @param control     whether it carries the control label (directly or through its device), so Minecraft may command it
 * @param state       the state, as the statestream {@code state} leaf would read
 * @param lastUpdated Home Assistant's {@code last_updated}, or null when the entry has none; an entry without one never
 *                    replaces a statestream value
 * @param attributes  the blueprint's whitelisted attributes, read-only
 */
public record CatalogEntry(String entityId, String name, String area, boolean control, StatestreamValue state, Instant lastUpdated,
                           Map<String, JsonElement> attributes) {
    public CatalogEntry {
        if (!StatestreamTopic.isEntityId(entityId)) throw new IllegalArgumentException("Not an entity id: " + entityId);
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(state, "state");
        Map<String, JsonElement> copy = new LinkedHashMap<>();
        attributes.forEach((key, value) -> copy.put(Objects.requireNonNull(key, "key"), value == null ? JsonNull.INSTANCE : value.deepCopy()));
        attributes = Collections.unmodifiableMap(copy);
    }

    public String domain() {
        return entityId.substring(0, entityId.indexOf('.'));
    }
}

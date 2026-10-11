package art.rehra.mineqtt.twin.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What MineQTT knows about one Home Assistant entity (M3 plan D1 #5, D3): one shared, immutable snapshot per entity
 * that every binding to it reads. {@link EntityDocuments} folds the independent statestream topics and the catalog into
 * it and replaces it on every change.
 * <p>
 * Attributes are stored as received, a JSON {@code null} included: {@link #attribute} tells a {@code null} (a light
 * that is off, cap §9.4) from an attribute that was never published. What a block may use is {@link #value}, which is
 * empty while the state is not a real value (stale attributes stay retained next to {@code unavailable}, cap §9.5)
 * and for a {@code null}, so a reader keeps what it shows instead of turning black (D1 #6).
 *
 * @param entityId    {@code <domain>.<object_id>}
 * @param state       the state; {@link StatestreamValue#UNKNOWN} when only attributes have arrived
 * @param attributes  every attribute, in arrival order, read-only; {@link #attribute} returns copies of containers
 * @param lastUpdated Home Assistant's {@code last_updated}, or null when not (yet) known
 * @param lastChanged Home Assistant's {@code last_changed}, or null when not (yet) known
 * @param receivedAt  when the winning source last changed, in the caller's clock (milliseconds)
 * @param freshness   {@link Freshness#LIVE} or {@link Freshness#RETAINED}; a document is never {@link Freshness#NONE}
 * @param origin      which source won the merge
 */
public record EntityDocument(String entityId, StatestreamValue state, Map<String, JsonElement> attributes, Instant lastUpdated,
                             Instant lastChanged, long receivedAt, Freshness freshness, Origin origin) {
    /** How current an entity's value is (D3); write-back (D11) refuses to toggle from anything but LIVE. */
    public enum Freshness {
        /**
         * A live statestream value, or a catalog delivered live since the last Home Assistant birth (which also vouches
         * for a statestream value with the same {@code last_updated}).
         */
        LIVE,
        /** Only retained or older data: what the broker kept, or anything from before the last Home Assistant birth. */
        RETAINED,
        /** Nothing known. */
        NONE
    }

    /** The source a document came from. */
    public enum Origin {
        STATESTREAM,
        CATALOG
    }

    public EntityDocument {
        if (!StatestreamTopic.isEntityId(entityId)) throw new IllegalArgumentException("Not an entity id: " + entityId);
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(freshness, "freshness");
        Objects.requireNonNull(origin, "origin");
        if (freshness == Freshness.NONE) throw new IllegalArgumentException("A document is LIVE or RETAINED");
        Map<String, JsonElement> copy = new LinkedHashMap<>();
        attributes.forEach((key, value) -> copy.put(Objects.requireNonNull(key, "key"), value == null ? JsonNull.INSTANCE : value.deepCopy()));
        attributes = Collections.unmodifiableMap(copy);
    }

    public String domain() {
        return entityId.substring(0, entityId.indexOf('.'));
    }

    /** Whether blocks may use the attributes: only next to a real state. */
    public boolean deliverable() {
        return state.known();
    }

    /** An attribute as stored, a JSON null included; empty only when it was never published. Never gated. */
    public Optional<JsonElement> attribute(String key) {
        JsonElement value = attributes.get(key);
        return value == null ? Optional.empty() : Optional.of(value.deepCopy());
    }

    /**
     * An attribute a block may use: empty while the state is not a real value, and for a missing or {@code null}
     * attribute. Empty means "keep the last value", never "black" or "zero".
     */
    public Optional<JsonElement> value(String key) {
        if (!deliverable()) return Optional.empty();
        JsonElement value = attributes.get(key);
        return value == null || value.isJsonNull() ? Optional.empty() : Optional.of(value.deepCopy());
    }

    /**
     * The document a value path runs over (D7): {@code {"state", "attributes": {…}, "last_changed", "last_updated"}},
     * a fresh copy. The state is a string ({@code null} when cleared); the attributes are empty while the state is not a
     * real value; timestamps are Home Assistant's ISO form, or {@code null}.
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.add("state", state.json());
        JsonObject attrs = new JsonObject();
        if (deliverable()) attributes.forEach((key, value) -> attrs.add(key, value.deepCopy()));
        json.add("attributes", attrs);
        json.add(StatestreamTopic.LAST_CHANGED, timestamp(lastChanged));
        json.add(StatestreamTopic.LAST_UPDATED, timestamp(lastUpdated));
        return json;
    }

    /** Whether {@code other} says the same about the entity; {@link #receivedAt} is ignored. */
    public boolean sameContent(EntityDocument other) {
        return other != null && entityId.equals(other.entityId) && state.equals(other.state) && attributes.equals(other.attributes)
                && Objects.equals(lastUpdated, other.lastUpdated) && Objects.equals(lastChanged, other.lastChanged)
                && freshness == other.freshness && origin == other.origin;
    }

    private static JsonElement timestamp(Instant instant) {
        return instant == null ? JsonNull.INSTANCE : new JsonPrimitive(HaTime.format(instant));
    }
}

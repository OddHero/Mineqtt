package art.rehra.mineqtt.twin.core;

import com.google.gson.JsonObject;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One Home Assistant service call MineQTT may ask for (M3 plan D8, D11): {@code <domain>.<service>} and its data. Only
 * {@link HaCalls} builds them, from typed values, and the constructor refuses anything outside the {@link HaCalls}
 * table, so a call that the control blueprint would refuse cannot exist: no {@code homeassistant.*} or cross-domain
 * action, no {@code lock.open}, no data key outside the service's whitelist ({@code code}, {@code entity_id},
 * {@code flash}, {@code effect}, …), no value of the wrong type or size.
 * <p>
 * A call has no target of its own: {@link #target} gives the single, domain-pinned {@code {"entity_id": …}} of a request
 * (D12: one entity string, never a list, an area, a device or a label). Minecraft-free.
 *
 * @param action {@code <domain>.<service>}, a row of {@link HaCalls#services()}
 * @param data   the service data; a copy is kept and handed out, so a call never changes
 */
public record Call(String action, JsonObject data) {
    /**
     * The control blueprint's entity id rule ({@code ^[a-z0-9_]+\.[a-z0-9_]+$}): as lenient as the statestream parser,
     * so entities whose ids predate Home Assistant's stricter {@code VALID_ENTITY_ID} stay controllable. The domain is
     * pinned to the call's own, which is in the table.
     */
    private static final Pattern ENTITY_ID = Pattern.compile("[a-z0-9_]+\\.[a-z0-9_]+");

    public Call {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(data, "data");
        Optional<String> problem = HaCalls.problem(action, data);
        if (problem.isPresent()) throw new IllegalArgumentException(problem.get());
        data = data.deepCopy();
    }

    /** A call without data. */
    public Call(String action) {
        this(action, new JsonObject());
    }

    /** A copy of the service data. */
    @Override
    public JsonObject data() {
        return data.deepCopy();
    }

    /** The entity domain the call is pinned to, e.g. {@code light}. */
    public String domain() {
        return action.substring(0, action.indexOf('.'));
    }

    /** The service, e.g. {@code turn_on}. */
    public String service() {
        return action.substring(action.indexOf('.') + 1);
    }

    /**
     * The request's target for this call: {@code {"entity_id": entityId}}, one entity of this call's own domain.
     *
     * @throws IllegalArgumentException for anything but one valid entity id of {@link #domain()}
     */
    public JsonObject target(String entityId) {
        if (!isEntityId(entityId)) throw new IllegalArgumentException("Not one entity id: " + entityId);
        String domain = entityId.substring(0, entityId.indexOf('.'));
        if (!domain.equals(domain())) throw new IllegalArgumentException(action + " may not target " + entityId + " of another domain");
        JsonObject target = new JsonObject();
        target.addProperty("entity_id", entityId);
        return target;
    }

    /** Whether {@code text} is exactly one Home Assistant entity id. */
    public static boolean isEntityId(String text) {
        return text != null && ENTITY_ID.matcher(text).matches();
    }

    @Override
    public String toString() {
        return action + " " + data;
    }
}

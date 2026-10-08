package art.rehra.mineqtt.device;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * How a device type uses its Raw Link, the user topic kept for wireless redstone and v1 automations (design D2).
 *
 * @param acceptRetained whether an input applies the broker's stored retained value on subscribe
 * @param grammar        how an input turns a payload into a command; null for outputs
 * @param echoKeys       the state keys a migrated v1 input echoes to {@code <topic>/state} (§6.6); empty when the
 *                       type has no v1 state echo
 * @param customPayloads whether slots 2 and 3 are the link's custom on/off payloads, in v1 data and in the slots; a
 *                       type whose payload is a value (the Presence Sensor's count) has none
 */
public record RawLinkSpec(Direction direction, boolean acceptRetained, @Nullable ValueCodecs.RawGrammar grammar,
                          List<String> echoKeys, boolean customPayloads) {
    public enum Direction {
        /** The device follows messages on the topic. */
        IN,
        /** The device publishes to the topic. */
        OUT
    }

    public RawLinkSpec {
        echoKeys = List.copyOf(echoKeys);
        if (direction == Direction.IN && grammar == null) throw new IllegalArgumentException("A Raw Link input needs a grammar");
        if (direction == Direction.OUT && grammar != null) throw new IllegalArgumentException("A Raw Link output has no grammar");
        if (direction == Direction.OUT && !echoKeys.isEmpty()) throw new IllegalArgumentException("A Raw Link output has no state echo");
    }

    /** An input that ignores retained values until {@link #acceptRetained(boolean)} says otherwise. */
    public static RawLinkSpec in(ValueCodecs.RawGrammar grammar) {
        return new RawLinkSpec(Direction.IN, false, grammar, List.of(), true);
    }

    public static RawLinkSpec out() {
        return new RawLinkSpec(Direction.OUT, false, null, List.of(), true);
    }

    public RawLinkSpec acceptRetained(boolean accept) {
        if (direction == Direction.OUT) throw new IllegalStateException("A Raw Link output receives nothing");
        return new RawLinkSpec(direction, accept, grammar, echoKeys, customPayloads);
    }

    /** The v1 state echo of a migrated input: these keys of the device's state, as v1 published them. */
    public RawLinkSpec stateEcho(String... keys) {
        return new RawLinkSpec(direction, acceptRetained, grammar, List.of(keys), customPayloads);
    }

    /** A link without custom payloads: slots 2 and 3 are something else, in v1 data too. */
    public RawLinkSpec withoutCustomPayloads() {
        return new RawLinkSpec(direction, acceptRetained, grammar, echoKeys, false);
    }

    public boolean echoes() {
        return !echoKeys.isEmpty();
    }

    /** The v1 echo of a state: only the echo keys, in their order. */
    public JsonObject echo(JsonObject state) {
        JsonObject echo = new JsonObject();
        for (String key : echoKeys) {
            if (state.has(key)) echo.add(key, state.get(key).deepCopy());
        }
        return echo;
    }
}

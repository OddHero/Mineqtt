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
 * @param off            what an output carries when it is off, which it publishes retained to a topic it leaves (M2 plan
 *                       D2); null for inputs
 * @param retainByDefault whether an output publishes retained while its link has no override ({@link RawLink#retain()})
 * @param level          whether an output carries a level; an output of events (the Wall Switch's presses) has none, so
 *                       it is never retained and has no retain setting
 */
public record RawLinkSpec(Direction direction, boolean acceptRetained, @Nullable ValueCodecs.RawGrammar grammar,
                          List<String> echoKeys, boolean customPayloads, @Nullable OffPayload off, boolean retainByDefault,
                          boolean level) {
    public enum Direction {
        /** The device follows messages on the topic. */
        IN,
        /** The device publishes to the topic. */
        OUT
    }

    /** An output's payload for "off": no signal, nothing counted. */
    @FunctionalInterface
    public interface OffPayload {
        String payload(ValueCodecs.CustomPayloads custom);
    }

    public RawLinkSpec {
        echoKeys = List.copyOf(echoKeys);
        if (direction == Direction.IN && grammar == null) throw new IllegalArgumentException("A Raw Link input needs a grammar");
        if (direction == Direction.OUT && grammar != null) throw new IllegalArgumentException("A Raw Link output has no grammar");
        if (direction == Direction.OUT && !echoKeys.isEmpty()) throw new IllegalArgumentException("A Raw Link output has no state echo");
        if (direction == Direction.OUT && off == null) throw new IllegalArgumentException("A Raw Link output needs an off payload");
        if (direction == Direction.IN && (off != null || retainByDefault)) throw new IllegalArgumentException("A Raw Link input publishes nothing");
        if (!level && (direction == Direction.IN || retainByDefault)) throw new IllegalArgumentException("Only an output that is not retained carries events");
    }

    /** An input that ignores retained values until {@link #acceptRetained(boolean)} says otherwise. */
    public static RawLinkSpec in(ValueCodecs.RawGrammar grammar) {
        return new RawLinkSpec(Direction.IN, false, grammar, List.of(), true, null, false, true);
    }

    /**
     * An output of events, such as the Wall Switch's presses: its payload names the event, so it has no custom payloads,
     * and it carries no level, so it is never retained and leaves nothing behind on a topic it leaves.
     */
    public static RawLinkSpec events() {
        return new RawLinkSpec(Direction.OUT, false, null, List.of(), false, custom -> "", false, false);
    }

    /** An output that does not publish retained until {@link #retainByDefault(boolean)} says otherwise. */
    public static RawLinkSpec out(OffPayload off) {
        return new RawLinkSpec(Direction.OUT, false, null, List.of(), true, off, false, true);
    }

    public RawLinkSpec acceptRetained(boolean accept) {
        if (direction == Direction.OUT) throw new IllegalStateException("A Raw Link output receives nothing");
        return new RawLinkSpec(direction, accept, grammar, echoKeys, customPayloads, off, retainByDefault, level);
    }

    /** Whether an output publishes retained while its link has no override of its own. */
    public RawLinkSpec retainByDefault(boolean retain) {
        if (direction == Direction.IN) throw new IllegalStateException("A Raw Link input publishes nothing");
        return new RawLinkSpec(direction, acceptRetained, grammar, echoKeys, customPayloads, off, retain, level);
    }

    /** The v1 state echo of a migrated input: these keys of the device's state, as v1 published them. */
    public RawLinkSpec stateEcho(String... keys) {
        return new RawLinkSpec(direction, acceptRetained, grammar, List.of(keys), customPayloads, off, retainByDefault, level);
    }

    /** A link without custom payloads: slots 2 and 3 are something else, in v1 data too. */
    public RawLinkSpec withoutCustomPayloads() {
        return new RawLinkSpec(direction, acceptRetained, grammar, echoKeys, false, off, retainByDefault, level);
    }

    /** What an output publishes when it is off, with the link's custom payloads. */
    public String offPayload(ValueCodecs.CustomPayloads custom) {
        if (off == null) throw new IllegalStateException("A Raw Link input publishes nothing");
        return off.payload(custom);
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

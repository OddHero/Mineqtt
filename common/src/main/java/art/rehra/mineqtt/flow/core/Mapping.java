package art.rehra.mineqtt.flow.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.Optional;

/**
 * What a {@link Wire} does with a value on its way (M2 plan §3.2). Later, a function node takes the place of a mapping.
 * The value always passes {@link Coerce} first, so a mapping works in the target port's kind.
 */
public enum Mapping {
    /** The value as the target port takes it. */
    FOLLOW,
    /** The opposite: on is off, level {@code n} is {@code 15 - n}. Only into a bool or a level, never from an event. */
    INVERT,
    /**
     * A T flip-flop: every event, and every rising edge of a bool or a level (off to on), toggles the target, which must
     * be a switch state that toggles; a falling edge does nothing.
     */
    PULSE_TOGGLE;

    /** Whether a wire with this mapping may run from a port of kind {@code from} to one of kind {@code to}. */
    public boolean compatible(PortType from, PortType to, boolean toggles) {
        return switch (this) {
            case FOLLOW -> Coerce.compatible(from, to, toggles);
            case INVERT -> !(from instanceof PortType.Event) && (to instanceof PortType.Bool || to instanceof PortType.Level)
                    && Coerce.compatible(from, to, toggles);
            case PULSE_TOGGLE -> toggles && to instanceof PortType.Bool
                    && (from instanceof PortType.Bool || from instanceof PortType.Level || from instanceof PortType.Event);
        };
    }

    /**
     * {@code value}, sent by a port of kind {@code from}, mapped for a port of kind {@code to}. Empty when the pair is not
     * {@link #compatible}, the value does not fit {@code from} or does not convert ({@link Coerce#coerce}), and for a
     * {@link #PULSE_TOGGLE} value that is no rising edge.
     *
     * @param previous the value this wire carried before; null for none, which counts as off
     */
    public Optional<JsonElement> apply(JsonElement value, JsonElement previous, PortType from, PortType to, boolean toggles) {
        if (!compatible(from, to, toggles)) return Optional.empty();
        return switch (this) {
            case FOLLOW -> Coerce.coerce(value, from, to, toggles);
            case INVERT -> Coerce.coerce(value, from, to, toggles).map(coerced -> to instanceof PortType.Bool
                    ? new JsonPrimitive(!coerced.getAsBoolean())
                    : new JsonPrimitive(PortType.MAX_LEVEL - coerced.getAsInt()));
            case PULSE_TOGGLE -> {
                if (from.problem(value).isPresent()) yield Optional.empty();
                boolean rising = from instanceof PortType.Event || on(value, from) && (previous == null || !on(previous, from));
                yield rising ? Optional.of(new JsonPrimitive(Coerce.TOGGLE)) : Optional.empty();
            }
        };
    }

    // Whether a bool or a level is on; a value that does not fit counts as off.
    private static boolean on(JsonElement value, PortType from) {
        return Coerce.coerce(value, from, PortType.BOOL, false).map(JsonElement::getAsBoolean).orElse(false);
    }
}

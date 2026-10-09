package art.rehra.mineqtt.device;

import art.rehra.mineqtt.flow.core.Coerce;
import art.rehra.mineqtt.flow.core.PortSpec;
import art.rehra.mineqtt.flow.core.PortType;
import com.google.gson.JsonElement;

import java.util.Optional;

/**
 * A device port (M2 plan §3.1), derived from its type's declaration and never written by hand: readable fields and
 * declared events are out-ports, writable fields (settings among them) and declared actions are in-ports. A key is
 * unique among a type's ports of one direction; the Redstone Output's {@code state} is both its on/off field (out)
 * and its switch state action (in). The flows {@link art.rehra.mineqtt.flow.core.MessageBus} routes over it as a
 * {@link PortSpec}.
 *
 * @param toggles whether an event wired to this in-port toggles it ({@link Action#toggles()})
 */
public record Port(String key, Direction direction, PortType kind, Origin origin, boolean toggles) implements PortSpec {
    public enum Direction {
        IN,
        OUT
    }

    /** What declared the port. */
    public enum Origin {
        /** A field: its value out, or a command to it in. */
        FIELD,
        /** A field that is a {@link Setting}: changing it needs CONFIGURE, not USE. */
        SETTING,
        ACTION,
        EVENT
    }

    /** Whether a wire may run from {@code source}, an out-port, to this in-port ({@link Coerce#compatible}). */
    public boolean accepts(Port source) {
        return direction == Direction.IN && source.direction == Direction.OUT && Coerce.compatible(source.kind, kind, toggles);
    }

    /**
     * Why {@code value} cannot be sent to this in-port, if it cannot: what its kind refuses, except {@code TOGGLE} for a
     * port that {@link #toggles} (which {@link Action#parse} and {@link Coerce#coerce} give it). Every check of a value
     * against a port goes through here, not through {@link PortType#problem}.
     */
    public Optional<String> problem(JsonElement value) {
        if (toggles && value.isJsonPrimitive() && value.getAsString().equalsIgnoreCase(Coerce.TOGGLE)) return Optional.empty();
        return kind.problem(value);
    }

    /** {@code power (level)}, for command output. */
    public String describe() {
        return key + " (" + kind.describe() + ")";
    }
}

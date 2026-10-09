package art.rehra.mineqtt.device;

import art.rehra.mineqtt.flow.core.Mapping;
import art.rehra.mineqtt.flow.core.PortType;

import java.util.List;
import java.util.Optional;

/**
 * The wire a player most likely means between two devices (M2 plan §4.2 M2-15): what {@code /mineqtt wires add} uses
 * when it names no mapping, and what the Configurator's Link mode offers first (M2-16). It goes by port keys, never by
 * device type, so a new type with these ports (the Smart Plug's and the Wall Switch's {@code state}, the Wall Switch's
 * {@code press}) is covered without an entry of its own.
 */
public final class DefaultWiring {
    /**
     * The default pairs, first match wins: a Redstone Input's {@code powered} follows into a switch state (Output, Lamp,
     * Plug), a press toggles one, and a Presence Sensor's {@code occupancy} follows into one.
     */
    private static final List<Pair> PAIRS = List.of(
            new Pair("powered", Action.STATE, Mapping.FOLLOW),
            new Pair("press", Action.STATE, Mapping.PULSE_TOGGLE),
            new Pair("occupancy", Action.STATE, Mapping.FOLLOW));

    /** An out-port key, an in-port key and the mapping between them. */
    public record Pair(String from, String to, Mapping mapping) {
    }

    private DefaultWiring() {
    }

    /** The default wire from a device of type {@code from} to one of type {@code to}; empty when no default pair fits. */
    public static Optional<Pair> of(DeviceType from, DeviceType to) {
        for (Pair pair : PAIRS) {
            Optional<Port> out = from.port(Port.Direction.OUT, pair.from());
            Optional<Port> in = to.port(Port.Direction.IN, pair.to());
            if (out.isPresent() && in.isPresent() && pair.mapping().compatible(out.get().kind(), in.get().kind(), in.get().toggles())) {
                return Optional.of(pair);
            }
        }
        return Optional.empty();
    }

    /**
     * The mapping a wire between two ports gets when none is named: an event toggles a switch state that toggles,
     * anything else follows.
     */
    public static Mapping mapping(Port from, Port to) {
        return from.kind() instanceof PortType.Event && to.toggles() ? Mapping.PULSE_TOGGLE : Mapping.FOLLOW;
    }
}

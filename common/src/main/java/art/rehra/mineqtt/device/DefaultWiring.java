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
    /** A timer's press action, its "Trigger now" (M4 plan D2): a wire's event fires the timer. */
    public static final String TRIGGER = "trigger";
    /** A timer's event, published on each fire (M4 plan D2). */
    public static final String FIRED = "fired";

    /**
     * The default pairs, first match wins (M4 plan D15), so the order matters once a type has both a {@code trigger} and
     * a {@code state} in-port (a timer, whose {@code state} arms it):
     * <ol>
     *     <li>a press fires a timer ({@code press→trigger}), and a timer's fire fires the next one ({@code fired→trigger};
     *     the hop limit cuts a loop), before</li>
     *     <li>a Redstone Input's {@code powered} follows into a switch state (Output, Lamp, Plug, and a timer's enable),</li>
     *     <li>a press toggles a switch state, a Presence Sensor's {@code occupancy} follows into one,</li>
     *     <li>a comparator level ({@code signal}, the Block Observer's) follows into a power level (the Redstone Output's),</li>
     *     <li>a timer's fire toggles a switch state (a lamp, an output, a plug).</li>
     * </ol>
     * A Wall Switch's press into a lamp still toggles its {@code state} (a lamp has no {@code trigger}); a lever into a timer
     * arms it through {@code powered→state}.
     */
    private static final List<Pair> PAIRS = List.of(
            new Pair("press", TRIGGER, Mapping.FOLLOW),
            new Pair(FIRED, TRIGGER, Mapping.FOLLOW),
            new Pair("powered", Action.STATE, Mapping.FOLLOW),
            new Pair("press", Action.STATE, Mapping.PULSE_TOGGLE),
            new Pair("occupancy", Action.STATE, Mapping.FOLLOW),
            new Pair("signal", "power", Mapping.FOLLOW),
            new Pair(FIRED, Action.STATE, Mapping.PULSE_TOGGLE));

    /** An out-port key, an in-port key and the mapping between them. */
    public record Pair(String from, String to, Mapping mapping) {
    }

    private DefaultWiring() {
    }

    /** The default pairs in the order they are tried. */
    public static List<Pair> pairs() {
        return PAIRS;
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

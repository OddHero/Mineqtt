package art.rehra.mineqtt.flow.core;

/** What the {@link MessageBus} needs to know of a port to carry a message over a wire: its kind, and whether an event toggles it. */
public interface PortSpec {
    PortType kind();

    /** Whether the port is a switch state that an event, or a {@link Mapping#PULSE_TOGGLE} wire, toggles. */
    boolean toggles();
}

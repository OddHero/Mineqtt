package art.rehra.mineqtt.flow.core;

/** Where a {@link Wire} is valid (M2 plan §3.2). */
public enum Scope {
    /** Anywhere: both ends may be anywhere, made with the Configurator's Link mode or {@code /mineqtt wires}. */
    WIRELESS,
    /**
     * Reserved for data cables: valid while both ends are members of one cable network, suspended (never deleted) while
     * they are not. The registry codec refuses it until cables are built.
     */
    NETWORK,
    /**
     * A wire derived from a device's Home Assistant binding (M3 plan §3.3): built when the device attaches, from an
     * entity's port to the device's or back, and never persisted, so the registry codec refuses it.
     */
    TWIN
}

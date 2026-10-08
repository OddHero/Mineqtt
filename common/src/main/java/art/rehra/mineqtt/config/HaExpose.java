package art.rehra.mineqtt.config;

/** Config {@code ha.expose}: which devices Home Assistant discovers (design §8.8). */
public enum HaExpose {
    /** {@link #ALL} on integrated servers, {@link #OPT_IN} on dedicated servers. */
    AUTO,
    /** Every device is discovered. */
    ALL,
    /** A device is discovered only after a player allowed to expose it ticks "Expose"; migrated v1 lamps stay exposed. */
    OPT_IN,
    /** Nothing is discovered. */
    NONE;

    /** The effective mode, never {@link #AUTO}. */
    public HaExpose resolve(boolean dedicatedServer) {
        if (this != AUTO) return this;
        return dedicatedServer ? OPT_IN : ALL;
    }
}

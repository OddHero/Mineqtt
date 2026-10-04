package art.rehra.mineqtt.blocks.entities;

/**
 * Trigger modes for the Game Time Timer block.
 * Each mode corresponds to a specific time of day in Minecraft's 24000-tick cycle.
 */
public enum GameTimeTrigger {
    /** Sunrise: approximately tick 23000 (just before midnight wraps around) */
    SUNRISE(23000),
    /** Noon: tick 6000 */
    NOON(6000),
    /** Sunset: tick 13000 */
    SUNSET(13000),
    /** Midnight: tick 0 (or 24000) */
    MIDNIGHT(0),
    /** Custom: user-specified time */
    CUSTOM(-1);

    private final int defaultTicks;

    GameTimeTrigger(int defaultTicks) {
        this.defaultTicks = defaultTicks;
    }

    /**
     * Returns the Minecraft tick value for this trigger mode.
     * CUSTOM mode returns -1, indicating the user must provide a custom time.
     */
    public int getDefaultTicks() {
        return defaultTicks;
    }

    /**
     * Returns the trigger mode by its ordinal index.
     */
    public static GameTimeTrigger byIndex(int index) {
        if (index < 0 || index >= values().length) {
            return SUNRISE;
        }
        return values()[index];
    }
}

package art.rehra.mineqtt.blocks.entities;

/**
 * Trigger modes for the Game Time Timer block.
 * Each mode corresponds to a specific time of day in Minecraft's 24000-tick daylight cycle.
 * In Minecraft: 06:00 = tick 0 (sunrise), 12:00 = tick 6000 (noon),
 * 18:00 = tick 12000 (sunset), 00:00 = tick 18000 (midnight).
 */
public enum GameTimeTrigger {
    /** Sunrise: tick 0 (06:00 dawn) */
    SUNRISE(0),
    /** Noon: tick 6000 (12:00 midday) */
    NOON(6000),
    /** Sunset: tick 12000 (18:00 dusk) */
    SUNSET(12000),
    /** Midnight: tick 18000 (00:00 midnight) */
    MIDNIGHT(18000),
    /** Custom: user-specified tick (0-23999) */
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

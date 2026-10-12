package art.rehra.mineqtt.device.timer.core;

import java.util.Arrays;
import java.util.Optional;

/**
 * The four timers (M4 plan D2), each a block, a block entity type and a device type of the same id path. The pulse each
 * fire gives its redstone is fixed per kind, as in v1: one tick for the interval and the cron timer, a wooden button's
 * 30 ticks for the Daylight Timer and the Alarm Clock.
 */
public enum TimerKind {
    INTERVAL("interval_timer", 1),
    /** The Daylight Timer, whose v1 id it keeps. */
    GAME_TIME("game_time_timer", 30),
    ALARM("alarm_clock", 30),
    CRON("cron_timer", 1);

    private final String id;
    private final int pulseTicks;

    TimerKind(String id, int pulseTicks) {
        this.id = id;
        this.pulseTicks = pulseTicks;
    }

    /** The id path of the kind's block, block entity type and device type, e.g. {@code alarm_clock}. */
    public String id() {
        return id;
    }

    /** How long the redstone pulse of a fire lasts, in game ticks. */
    public int pulseTicks() {
        return pulseTicks;
    }

    /** The kind of an id path; empty for any other. */
    public static Optional<TimerKind> byId(String id) {
        return Arrays.stream(values()).filter(kind -> kind.id.equals(id)).findFirst();
    }
}

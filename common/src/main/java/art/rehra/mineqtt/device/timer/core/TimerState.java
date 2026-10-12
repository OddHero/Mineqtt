package art.rehra.mineqtt.device.timer.core;

/**
 * What a timer counts between ticks, saved with its block (M4 plan D4): the v1 timers' own counters under their v1
 * meaning, so a converted timer carries on where it was.
 *
 * @param tickCounter     the interval timer's ticks since it last fired or restarted
 * @param lastTriggerDay  the overworld day whose moment the Daylight Timer or the Alarm Clock last fired for (or was armed
 *                        within); -1 for none
 * @param lastTriggerTick the game time the cron timer last fired at, so one tick never fires twice; -1 for none
 * @param lastTriggerTime the game time of the last fire, scheduled or manual, which its pulse lasts from
 */
public record TimerState(long tickCounter, long lastTriggerDay, long lastTriggerTick, long lastTriggerTime) {
    /** A new timer: nothing counted, never fired. */
    public static final TimerState NEW = new TimerState(0, -1, -1, 0);

    public TimerState withTickCounter(long tickCounter) {
        return new TimerState(tickCounter, lastTriggerDay, lastTriggerTick, lastTriggerTime);
    }

    public TimerState withLastTriggerDay(long lastTriggerDay) {
        return new TimerState(tickCounter, lastTriggerDay, lastTriggerTick, lastTriggerTime);
    }

    public TimerState withLastTriggerTick(long lastTriggerTick) {
        return new TimerState(tickCounter, lastTriggerDay, lastTriggerTick, lastTriggerTime);
    }

    public TimerState withLastTriggerTime(long lastTriggerTime) {
        return new TimerState(tickCounter, lastTriggerDay, lastTriggerTick, lastTriggerTime);
    }
}

package art.rehra.mineqtt.device.timer.core;

import java.util.Optional;

/**
 * One tick of a timer (M4 plan D2), the rules v1 timers followed in their tickers: while its pulse is on a timer only
 * waits for the pulse to end, a disabled timer does nothing, and an enabled one asks its schedule. A manual trigger
 * ({@link #trigger}) fires also while the timer is disabled, and is dropped while a pulse is on.
 *
 * @param action what the block does at this tick
 * @param state  the timer's counters after it
 */
public record TimerStep(Action action, TimerState state) {
    /** What a tick makes the block do. */
    public enum Action {
        NONE,
        /** Fire on schedule: start a pulse and publish {@code fired} with the source {@code schedule}. */
        FIRE,
        /** End the pulse that is on. */
        PULSE_OFF
    }

    /**
     * @param pulsing whether the timer's redstone pulse is on now
     */
    public static TimerStep tick(TimerKind kind, TimerSchedule schedule, boolean enabled, boolean pulsing, TimerNow now, TimerState state) {
        if (pulsing) return new TimerStep(pulseOver(kind, now, state) ? Action.PULSE_OFF : Action.NONE, state);
        if (!enabled) return new TimerStep(Action.NONE, state);
        boolean due = schedule.due(now, state);
        return new TimerStep(due ? Action.FIRE : Action.NONE, schedule.ticked(now, state, due));
    }

    /**
     * A manual fire, "Trigger now": the state with its pulse starting now, or empty while a pulse is on, which drops it
     * as v1 ignored a trigger during a pulse. It leaves the interval's count and the day's lock as they are.
     */
    public static Optional<TimerState> trigger(boolean pulsing, TimerNow now, TimerState state) {
        return pulsing ? Optional.empty() : Optional.of(state.withLastTriggerTime(now.gameTime()));
    }

    /**
     * Whether a pulse that is on has lasted its kind's length; a pulse whose start lies ahead of the game time (data from
     * another world) ends at once.
     */
    public static boolean pulseOver(TimerKind kind, TimerNow now, TimerState state) {
        long elapsed = now.gameTime() - state.lastTriggerTime();
        return elapsed >= kind.pulseTicks() || elapsed < 0;
    }
}

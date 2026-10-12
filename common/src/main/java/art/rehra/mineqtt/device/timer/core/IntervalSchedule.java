package art.rehra.mineqtt.device.timer.core;

import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * The Interval Timer's schedule (M4 plan D2): it counts the ticks on which it is asked and fires when the count reaches
 * the interval, then counts from 0 again. As in v1 the tick that ends a pulse is not counted, so a 1-tick interval
 * fires every other tick. A settings change and enabling restart the count.
 *
 * @param intervalTicks the interval in ticks, at least 1
 */
public record IntervalSchedule(long intervalTicks) implements TimerSchedule {
    public IntervalSchedule {
        if (intervalTicks < 1) throw new IllegalArgumentException("An interval needs at least one tick: " + intervalTicks);
    }

    @Override
    public boolean due(TimerNow now, TimerState state) {
        return state.tickCounter() + 1 >= intervalTicks;
    }

    @Override
    public TimerState ticked(TimerNow now, TimerState state, boolean fired) {
        return fired ? state.withTickCounter(0).withLastTriggerTime(now.gameTime()) : state.withTickCounter(state.tickCounter() + 1);
    }

    @Override
    public TimerState armed(TimerNow now, TimerState state) {
        return restarted(state);
    }

    /** The state after a settings change: the count starts again. */
    public static TimerState restarted(TimerState state) {
        return state.withTickCounter(0);
    }

    @Override
    public Optional<ZonedDateTime> next(TimerNow now, TimerState state) {
        return TimerSchedule.afterTicks(now, remainingTicks(state));
    }

    /** The counted ticks until the next fire. */
    public long remainingTicks(TimerState state) {
        return Math.max(0, intervalTicks - state.tickCounter());
    }
}

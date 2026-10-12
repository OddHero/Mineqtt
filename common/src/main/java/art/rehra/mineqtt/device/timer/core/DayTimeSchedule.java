package art.rehra.mineqtt.device.timer.core;

import art.rehra.mineqtt.util.GameClock;

import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * The schedule of the Daylight Timer and the Alarm Clock (M4 plan D2): once per overworld day, within
 * {@value #WINDOW} ticks either side of a time of day.
 * <p>
 * The day a fire counts for is the day of the moment it fires around, not the day of the tick it fires on: a moment at
 * sunrise (tick 0) opens its window 20 ticks before the day turns, and the v1 timers, which counted the tick's day, fired
 * at both ends of that window. A fire marks its moment's day as handled, so the timer fires once, also when
 * {@code /time set} takes the clock back over the moment on the same day. A timer saved by v1 after its second fire holds
 * the same day, so it does not fire again after the upgrade.
 *
 * @param targetTicks the time of day it fires at, 0 to 23999
 */
public record DayTimeSchedule(int targetTicks) implements TimerSchedule {
    /** How far from its moment a timer still fires: one second either side, as in v1. */
    public static final int WINDOW = 20;

    public DayTimeSchedule {
        if (targetTicks < 0 || targetTicks >= GameClock.TICKS_PER_DAY) throw new IllegalArgumentException("Not a time of day: " + targetTicks);
    }

    @Override
    public boolean due(TimerNow now, TimerState state) {
        return inWindow(now.clockTicks()) && state.lastTriggerDay() != momentDay(now.clockTicks());
    }

    @Override
    public TimerState ticked(TimerNow now, TimerState state, boolean fired) {
        return fired ? state.withLastTriggerDay(momentDay(now.clockTicks())).withLastTriggerTime(now.gameTime()) : state;
    }

    @Override
    public TimerState armed(TimerNow now, TimerState state) {
        return inWindow(now.clockTicks()) ? state.withLastTriggerDay(momentDay(now.clockTicks())) : state;
    }

    /**
     * Also none while the overworld clock stands ({@link TimerNow#clockRunning}): its moment never comes then. The clock
     * ticks until the moment take longer at a rate below 1 ({@code /time rate}) and less time above it.
     */
    @Override
    public Optional<ZonedDateTime> next(TimerNow now, TimerState state) {
        if (!now.clockRunning()) return Optional.empty();
        return TimerSchedule.afterTicks(now, now.serverTicksFor(remainingTicks(now.clockTicks(), state)));
    }

    /** The overworld ticks until the moment it fires next: the coming one, or the one after when today's is handled. */
    public long remainingTicks(long clockTicks, TimerState state) {
        long remaining = GameClock.ticksUntil(clockTicks, targetTicks);
        if (GameClock.day(clockTicks + remaining) == state.lastTriggerDay()) remaining += GameClock.TICKS_PER_DAY;
        return remaining;
    }

    /** Whether the clock is within {@link #WINDOW} ticks of the moment. */
    public boolean inWindow(long clockTicks) {
        return Math.abs(GameClock.ticksSince(clockTicks, targetTicks)) <= WINDOW;
    }

    /** The day of the moment closest to the clock: the day a fire now counts for. */
    public long momentDay(long clockTicks) {
        return GameClock.day(clockTicks - GameClock.ticksSince(clockTicks, targetTicks));
    }
}

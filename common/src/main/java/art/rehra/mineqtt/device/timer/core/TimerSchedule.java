package art.rehra.mineqtt.device.timer.core;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * When a timer fires on its own (M4 plan D2): every so many ticks, once a day at a moment of the overworld clock, or when
 * the wall clock matches a cron expression. Pure, so {@code verifyTimers} runs every rule without a level; the block
 * entity feeds it a {@link TimerNow} each tick and keeps the {@link TimerState} it returns.
 * <p>
 * Whether a timer is enabled and whether its pulse is on are not the schedule's business: {@link TimerStep} asks the
 * schedule only on a tick where the timer is enabled and no pulse is on, as v1 did.
 */
public sealed interface TimerSchedule permits IntervalSchedule, DayTimeSchedule, CronTimerSchedule {
    /**
     * The shortest time ahead a tick-based next fire is given for (D2): closer than that, the estimate says less than the
     * pulse it announces, so it is left empty.
     */
    Duration MIN_ESTIMATE = Duration.ofSeconds(60);

    /** Whether the timer fires on schedule at this tick. */
    boolean due(TimerNow now, TimerState state);

    /** The state after a tick on which the schedule was asked; {@code fired} tells whether it fired. */
    TimerState ticked(TimerNow now, TimerState state, boolean fired);

    /**
     * The state of a timer that was just enabled (D2): enabling never fires at once, so a moment whose window is open now
     * counts as handled for today, and an interval starts counting again.
     */
    TimerState armed(TimerNow now, TimerState state);

    /**
     * When the timer fires next on schedule, in the server's zone and to the second; empty when no fire is known (an
     * invalid cron expression, none within 400 days) or, for the tick-based schedules, when it is under
     * {@link #MIN_ESTIMATE} away or the server's ticks are frozen, and for the day-time schedule while the overworld clock
     * stands.
     */
    Optional<ZonedDateTime> next(TimerNow now, TimerState state);

    /**
     * The wall-clock time {@code ticks} ticks from now at the server's tick rate: an estimate, since a lagging server or
     * {@code /tick rate} moves it. Empty while frozen, at no tick rate, or under {@link #MIN_ESTIMATE} away.
     */
    static Optional<ZonedDateTime> afterTicks(TimerNow now, long ticks) {
        if (now.frozen() || !(now.tickRate() > 0)) return Optional.empty();
        long millis = (long) Math.ceil(ticks * 1000.0 / now.tickRate());
        if (millis < MIN_ESTIMATE.toMillis()) return Optional.empty();
        return Optional.of(now.local().plus(millis, ChronoUnit.MILLIS).truncatedTo(ChronoUnit.SECONDS));
    }
}

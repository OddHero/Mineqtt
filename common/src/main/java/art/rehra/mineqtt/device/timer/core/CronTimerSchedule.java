package art.rehra.mineqtt.device.timer.core;

import art.rehra.mineqtt.util.CronSchedule;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * The Cron Timer's schedule (M4 plan D2): the 7-field {@link CronSchedule} grammar, matched against the wall clock in
 * the server's zone and the tick within the second. An expression that does not parse is kept (a v1 timer may carry one)
 * and never fires, as in v1; an empty one has no schedule.
 *
 * @param expression the expression as the timer keeps it
 * @param schedule   its parsed form; null when it is empty or does not parse
 */
public record CronTimerSchedule(String expression, CronSchedule schedule) implements TimerSchedule {
    /** The longest expression a timer takes. */
    public static final int MAX_LENGTH = 64;

    /**
     * The schedule of an expression, parsed; an empty or invalid one never fires. A saved expression longer than a player
     * may now type (v1 took 128 characters) still fires when it parses.
     */
    public static CronTimerSchedule of(String expression) {
        String text = expression == null ? "" : expression.strip();
        CronSchedule parsed = null;
        if (!text.isEmpty()) {
            try {
                parsed = CronSchedule.parse(text);
            } catch (IllegalArgumentException e) {
                // Kept as it is; it never fires.
            }
        }
        return new CronTimerSchedule(text, parsed);
    }

    /**
     * Why a player's expression is refused, or empty when it is taken: an empty one (no schedule) or one that parses, of
     * at most {@value #MAX_LENGTH} characters.
     */
    public static Optional<String> problem(String expression) {
        String text = expression == null ? "" : expression.strip();
        if (text.isEmpty()) return Optional.empty();
        if (text.length() > MAX_LENGTH) return Optional.of("A cron expression has at most " + MAX_LENGTH + " characters");
        try {
            CronSchedule.parse(text);
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.of(e.getMessage());
        }
    }

    /** Whether the expression parses, so the timer can fire. */
    public boolean valid() {
        return schedule != null;
    }

    @Override
    public boolean due(TimerNow now, TimerState state) {
        return schedule != null && state.lastTriggerTick() != now.gameTime() && schedule.matches(now.local().toLocalDateTime(), now.tickInSecond());
    }

    @Override
    public TimerState ticked(TimerNow now, TimerState state, boolean fired) {
        return fired ? state.withLastTriggerTick(now.gameTime()).withLastTriggerTime(now.gameTime()) : state;
    }

    @Override
    public TimerState armed(TimerNow now, TimerState state) {
        return state;
    }

    /** The next second the expression matches, at most 400 days ahead ({@link CronSchedule#findNextTrigger}). */
    @Override
    public Optional<ZonedDateTime> next(TimerNow now, TimerState state) {
        if (schedule == null) return Optional.empty();
        LocalDateTime next = CronSchedule.findNextTrigger(schedule, now.local().toLocalDateTime(), now.zone());
        return next == null ? Optional.empty() : Optional.of(next.truncatedTo(ChronoUnit.SECONDS).atZone(now.zone()));
    }
}

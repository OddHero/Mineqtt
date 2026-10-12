package art.rehra.mineqtt.util;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minecraft's day clock as people read it (M4 plan D2, D6), shared by the timers and the world telemetry. Pure: plain
 * Java, no game classes, so it sits in a {@code verifyCoreIsolation} scope.
 * <p>
 * A day has {@value #TICKS_PER_DAY} ticks and starts at sunrise: tick 0 is 06:00, 6000 is 12:00, 12000 is 18:00 and
 * 18000 is 00:00. A clock value is the overworld's total ticks ({@code runtime.WorldClock}); its time of day is that
 * value modulo a day, its day number that value divided by a day, both rounded towards negative infinity so a clock set
 * before tick 0 still reads a valid time. The HH:MM conversions round to the nearest minute exactly as the v1 timers
 * did ({@code formatGameTime}, {@code parseAlarmTime}), so a converted timer keeps its moment.
 */
public final class GameClock {
    public static final int TICKS_PER_DAY = 24_000;
    public static final int MINUTES_PER_DAY = 1_440;
    /** The minutes from midnight to tick 0: the day starts at 06:00. */
    private static final int MINUTES_AT_TICK_ZERO = 360;
    /** {@code is_day} (D6): 06:00 up to, not including, 18:00. */
    private static final int DAY_END_TICKS = 12_000;
    private static final Pattern HH_MM = Pattern.compile("([01]\\d|2[0-3]):([0-5]\\d)");

    /** The four named moments of the day (the Daylight Timer's {@code trigger_at}, without {@code custom}). */
    public enum Moment {
        SUNRISE(0),
        NOON(6_000),
        SUNSET(12_000),
        MIDNIGHT(18_000);

        private final int ticks;

        Moment(int ticks) {
            this.ticks = ticks;
        }

        /** The moment's time of day in ticks. */
        public int ticks() {
            return ticks;
        }
    }

    private GameClock() {
    }

    /** The time of day of a clock value, 0 to 23999. */
    public static int timeOfDay(long clockTicks) {
        return (int) Math.floorMod(clockTicks, (long) TICKS_PER_DAY);
    }

    /** The day number of a clock value: whole days since tick 0. */
    public static long day(long clockTicks) {
        return Math.floorDiv(clockTicks, (long) TICKS_PER_DAY);
    }

    /** Whether it is day at this clock value: 06:00 up to 18:00, by the clock alone (rain does not make it night). */
    public static boolean isDay(long clockTicks) {
        return timeOfDay(clockTicks) < DAY_END_TICKS;
    }

    /** A time of day in ticks (any value is taken modulo a day) as {@code HH:MM}, rounded to the nearest minute. */
    public static String format(long timeOfDayTicks) {
        int ticks = timeOfDay(timeOfDayTicks);
        int minutesSinceTickZero = (int) Math.round(ticks * (double) MINUTES_PER_DAY / TICKS_PER_DAY) % MINUTES_PER_DAY;
        int minutes = (minutesSinceTickZero + MINUTES_AT_TICK_ZERO) % MINUTES_PER_DAY;
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    /**
     * A time of day in ticks as {@code HH:MM}, rounded down to whole steps of {@code stepMinutes} game minutes (the
     * telemetry's {@code time_of_day} in 15-minute steps, D6).
     *
     * @param stepMinutes 1 to 1440, a divisor of a day's minutes
     */
    public static String formatRoundedDown(long timeOfDayTicks, int stepMinutes) {
        if (stepMinutes < 1 || MINUTES_PER_DAY % stepMinutes != 0) throw new IllegalArgumentException("Not a step of a day: " + stepMinutes + " minutes");
        int ticks = timeOfDay(timeOfDayTicks);
        // Exact integer minutes since tick 0, rounded down: 1000 ticks are 60 minutes.
        int minutesSinceTickZero = (int) ((long) ticks * MINUTES_PER_DAY / TICKS_PER_DAY);
        int minutes = (minutesSinceTickZero + MINUTES_AT_TICK_ZERO) % MINUTES_PER_DAY;
        minutes -= minutes % stepMinutes;
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    /** Whether {@code text} is a time of day as {@code HH:MM}: 00:00 to 23:59, two digits each. */
    public static boolean isHhMm(String text) {
        return text != null && HH_MM.matcher(text).matches();
    }

    /**
     * {@code HH:MM} as a time of day in ticks, 0 to 23999, rounded to the nearest tick as the v1 alarm clock did; empty
     * for anything that is not {@link #isHhMm}.
     */
    public static Optional<Integer> parse(String text) {
        if (text == null) return Optional.empty();
        Matcher matcher = HH_MM.matcher(text);
        if (!matcher.matches()) return Optional.empty();
        int minutes = Integer.parseInt(matcher.group(1)) * 60 + Integer.parseInt(matcher.group(2));
        int minutesSinceTickZero = Math.floorMod(minutes - MINUTES_AT_TICK_ZERO, MINUTES_PER_DAY);
        return Optional.of((int) Math.round(minutesSinceTickZero * (double) TICKS_PER_DAY / MINUTES_PER_DAY) % TICKS_PER_DAY);
    }

    /**
     * The ticks from {@code clockTicks} until the time of day {@code targetTicks} comes next: 0 when it is that time now,
     * else 1 to 23999.
     */
    public static int ticksUntil(long clockTicks, int targetTicks) {
        return Math.floorMod(timeOfDay(targetTicks) - timeOfDay(clockTicks), TICKS_PER_DAY);
    }

    /**
     * The signed distance from the time of day {@code targetTicks} to that of {@code clockTicks}, the shorter way round
     * the day: -12000 to 11999. Positive when the target has passed today. The timers fire once a day within a window
     * around their target (D2: +-20 ticks).
     */
    public static int ticksSince(long clockTicks, int targetTicks) {
        int difference = Math.floorMod(timeOfDay(clockTicks) - timeOfDay(targetTicks), TICKS_PER_DAY);
        return difference >= TICKS_PER_DAY / 2 ? difference - TICKS_PER_DAY : difference;
    }
}

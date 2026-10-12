package art.rehra.mineqtt.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses and evaluates Minecraft-style 7-field cron expressions.
 *
 * <p>Format: {@code tick second minute hour day month weekday}</p>
 * <ul>
 *   <li>Tick: 0-19 (Minecraft has 20 ticks per second)</li>
 *   <li>Second: 0-59</li>
 *   <li>Minute: 0-59</li>
 *   <li>Hour: 0-23</li>
 *   <li>Day of month: 1-31</li>
 *   <li>Month: 1-12</li>
 *   <li>Weekday: 0-6 (0 = Sunday, 1 = Monday, ..., 6 = Saturday)</li>
 * </ul>
 *
 * <p>Each field supports wildcards, exact values, ranges, lists, and steps: {@code *&#47;5} takes every fifth value from the
 * field's first, {@code 10-30/5} every fifth within the range, {@code 5/15} every fifteenth from 5 to the field's last.</p>
 * <p>Example: {@code * * * 9 * * 1-5} fires every tick, every second, every minute at 9 AM on weekdays.</p>
 */
public class CronSchedule {

    private final Field tick;
    private final Field second;
    private final Field minute;
    private final Field hour;
    private final Field dayOfMonth;
    private final Field month;
    private final Field weekday;

    private CronSchedule(Field tick, Field second, Field minute, Field hour, Field dayOfMonth, Field month, Field weekday) {
        this.tick = tick;
        this.second = second;
        this.minute = minute;
        this.hour = hour;
        this.dayOfMonth = dayOfMonth;
        this.month = month;
        this.weekday = weekday;
    }

    /**
     * Returns {@code true} if the given instant matches this schedule.
     * @param dateTime the wall-clock time to check
     * @param minecraftTick the current Minecraft tick within the second (0-19)
     */
    public boolean matches(LocalDateTime dateTime, int minecraftTick) {
        return tick.matches(minecraftTick % 20)
                && second.matches(dateTime.getSecond())
                && minute.matches(dateTime.getMinute())
                && hour.matches(dateTime.getHour())
                && dayOfMonth.matches(dateTime.getDayOfMonth())
                && month.matches(dateTime.getMonthValue())
                && weekday.matches(toCronWeekday(dateTime.getDayOfWeek()));
    }

    /**
     * Returns {@code true} if the time portion (excluding tick) matches.
     * Used for finding next trigger times at second granularity.
     */
    public boolean matchesTime(LocalDateTime dateTime) {
        return second.matches(dateTime.getSecond())
                && minute.matches(dateTime.getMinute())
                && hour.matches(dateTime.getHour())
                && dayOfMonth.matches(dateTime.getDayOfMonth())
                && month.matches(dateTime.getMonthValue())
                && weekday.matches(toCronWeekday(dateTime.getDayOfWeek()));
    }

    /**
     * Converts Java DayOfWeek (MONDAY=1 ... SUNDAY=7) to cron weekday (0=Sunday ... 6=Saturday).
     */
    private static int toCronWeekday(DayOfWeek dayOfWeek) {
        // Java: MONDAY=1, TUESDAY=2, ..., SUNDAY=7
        // Cron: 0=Sunday, 1=Monday, ..., 6=Saturday
        return (dayOfWeek.getValue() % 7);
    }

    /**
     * Parses a 7-field Minecraft-style cron expression string.
     *
     * @return the parsed schedule
     * @throws IllegalArgumentException if the expression is invalid
     */
    public static CronSchedule parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Cron expression must not be empty");
        }

        String[] parts = expression.trim().split("\\s+");
        if (parts.length != 7) {
            throw new IllegalArgumentException("Cron expression must have exactly 7 fields: tick second minute hour day month weekday");
        }

        Field tick = parseField(parts[0], 0, 19, "tick");
        Field second = parseField(parts[1], 0, 59, "second");
        Field minute = parseField(parts[2], 0, 59, "minute");
        Field hour = parseField(parts[3], 0, 23, "hour");
        Field dayOfMonth = parseField(parts[4], 1, 31, "day of month");
        Field month = parseField(parts[5], 1, 12, "month");
        Field weekday = parseField(parts[6], 0, 6, "weekday");

        return new CronSchedule(tick, second, minute, hour, dayOfMonth, month, weekday);
    }

    /**
     * Returns the field names in order for UI display.
     */
    public static String[] getFieldNames() {
        return new String[]{"Tick", "Second", "Minute", "Hour", "Day", "Month", "Weekday"};
    }

    /**
     * Returns the range description for each field.
     */
    public static String[] getFieldRanges() {
        return new String[]{"0-19", "0-59", "0-59", "0-23", "1-31", "1-12", "0-6"};
    }

    private static Field parseField(String field, int min, int max, String name) {
        List<Integer> values = new ArrayList<>();

        // Handle step: */n or range/n
        int step = 1;
        String base = field;
        int stepIdx = field.indexOf('/');
        if (stepIdx != -1) {
            String stepStr = field.substring(stepIdx + 1);
            try {
                step = Integer.parseInt(stepStr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid step in " + name + " field: " + stepStr);
            }
            if (step <= 0) {
                throw new IllegalArgumentException("Step must be positive in " + name + " field");
            }
            base = field.substring(0, stepIdx);
        }

        String[] segments = base.split(",");
        for (String seg : segments) {
            seg = seg.trim();
            if (seg.isEmpty()) {
                throw new IllegalArgumentException("Empty segment in " + name + " field");
            }

            if (seg.equals("*")) {
                // */n: every n-th value from the field's first.
                for (int i = min; i <= max; i += step) {
                    values.add(i);
                }
            } else {
                // Check for range
                int rangeIdx = seg.indexOf('-');
                if (rangeIdx != -1) {
                    String startStr = seg.substring(0, rangeIdx);
                    String endStr = seg.substring(rangeIdx + 1);
                    int start, end;
                    try {
                        start = Integer.parseInt(startStr);
                        end = Integer.parseInt(endStr);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Invalid range in " + name + " field: " + seg);
                    }
                    if (start < min || start > max || end < min || end > max) {
                        throw new IllegalArgumentException(name + " value out of range (" + min + "-" + max + "): " + seg);
                    }
                    if (start > end) {
                        throw new IllegalArgumentException("Invalid range in " + name + " field: " + seg);
                    }
                    for (int i = start; i <= end; i += step) {
                        values.add(i);
                    }
                } else {
                    int val;
                    try {
                        val = Integer.parseInt(seg);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Invalid value in " + name + " field: " + seg);
                    }
                    if (val < min || val > max) {
                        throw new IllegalArgumentException(name + " value out of range (" + min + "-" + max + "): " + val);
                    }
                    if (stepIdx == -1) {
                        values.add(val);
                    } else {
                        // v/n: every n-th value from v to the field's last.
                        for (int i = val; i <= max; i += step) {
                            values.add(i);
                        }
                    }
                }
            }
        }

        if (values.isEmpty()) {
            throw new IllegalArgumentException("No valid values in " + name + " field");
        }

        return new Field(values);
    }

    /**
     * Validates a cron expression without parsing it fully.
     */
    public static boolean isValid(String expression) {
        try {
            parse(expression);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Returns a human-readable description of the next trigger time, or "-" if no expression set.
     */
    public static String describeNextTrigger(CronSchedule schedule, String expression) {
        if (schedule == null || expression == null || expression.isBlank()) {
            return "-";
        }

        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime now = LocalDateTime.now(zone);
        LocalDateTime next = findNextTrigger(schedule, now, zone);

        if (next == null) {
            return "No future trigger found";
        }

        long seconds = java.time.Duration.between(now, next).getSeconds();
        if (seconds < 60) {
            return "In " + seconds + "s";
        } else if (seconds < 3600) {
            return "In " + (seconds / 60) + "m " + (seconds % 60) + "s";
        } else if (seconds < 86400) {
            return "In " + (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
        } else {
            return "In " + (seconds / 86400) + "d " + ((seconds % 86400) / 3600) + "h";
        }
    }

    /**
     * Finds the next trigger time after the given instant, at most 400 days ahead, or null when there is none.
     * Searches at second granularity (tick field is ignored for next-trigger display). It goes a day at a time and looks
     * at the time of day only on a day whose day of month, month and weekday match, so a search takes at most about 400
     * steps whatever the expression. The result keeps the start's fraction of a second.
     */
    public static LocalDateTime findNextTrigger(CronSchedule schedule, LocalDateTime start, ZoneId zone) {
        LocalDateTime candidate = start.plusSeconds(1);
        LocalDateTime limit = start.plusDays(400); // Search up to ~1 year ahead
        LocalTime floor = candidate.toLocalTime().withNano(0);
        for (LocalDate day = candidate.toLocalDate(); !day.isAfter(limit.toLocalDate()); day = day.plusDays(1)) {
            if (schedule.matchesDate(day)) {
                LocalTime time = schedule.firstTimeFrom(floor);
                if (time != null) {
                    LocalDateTime next = day.atTime(time).withNano(candidate.getNano());
                    return next.isAfter(limit) ? null : next;
                }
            }
            floor = LocalTime.MIDNIGHT;
        }
        return null;
    }

    // Whether the day of month, month and weekday all match.
    private boolean matchesDate(LocalDate day) {
        return dayOfMonth.matches(day.getDayOfMonth()) && month.matches(day.getMonthValue()) && weekday.matches(toCronWeekday(day.getDayOfWeek()));
    }

    // The first time of day at or after from (to the second) whose hour, minute and second match, or null for none.
    private LocalTime firstTimeFrom(LocalTime from) {
        for (int h = hour.ceiling(from.getHour()); h >= 0; h = hour.ceiling(h + 1)) {
            boolean sameHour = h == from.getHour();
            for (int m = minute.ceiling(sameHour ? from.getMinute() : 0); m >= 0; m = minute.ceiling(m + 1)) {
                int s = second.ceiling(sameHour && m == from.getMinute() ? from.getSecond() : 0);
                if (s >= 0) return LocalTime.of(h, m, s);
            }
        }
        return null;
    }

    private static class Field {
        // Bit v is set when the field takes the value v; every field's values lie within 0-59.
        private final long values;

        Field(List<Integer> values) {
            long bits = 0;
            for (int value : values) bits |= 1L << value;
            this.values = bits;
        }

        boolean matches(int value) {
            return value >= 0 && value < Long.SIZE && (values >>> value & 1) != 0;
        }

        // The smallest value the field takes at or above from, or -1 for none.
        int ceiling(int from) {
            if (from >= Long.SIZE) return -1;
            long above = values & (-1L << Math.max(from, 0));
            return above == 0 ? -1 : Long.numberOfTrailingZeros(above);
        }
    }
}

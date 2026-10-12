package art.rehra.mineqtt.device.timer.core;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * The words of a timer's status line (M4 plan D5), "Next 06:00, in 3 min", "Every 5 s", "Invalid cron", "Disabled", from
 * what its state holds; the device type picks the line for its kind.
 */
public final class TimerStatus {
    public static final String DISABLED = "Disabled";
    public static final String NO_SCHEDULE = "No schedule";
    public static final String INVALID_CRON = "Invalid cron";
    public static final String NO_FIRE = "No fire within 400 days";
    private static final DateTimeFormatter TODAY = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter LATER = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ROOT);

    private TimerStatus() {
    }

    /** An interval: "Every 5 s", and when its next fire is a minute or more away ", next in 3 min". */
    public static String every(long value, String unit, String nextFire, Instant now) {
        String every = "Every " + value + " " + unit;
        return NextFire.parse(nextFire).map(next -> every + ", next " + in(now, next)).orElse(every);
    }

    /**
     * A moment of the overworld day: "Next 06:00, in 3 min" with the game time it fires at, or "Next 06:00" while its next
     * fire is under a minute away or the ticks are frozen.
     */
    public static String moment(String time, String nextFire, Instant now) {
        String at = "Next " + time;
        return NextFire.parse(nextFire).map(next -> at + ", " + in(now, next)).orElse(at);
    }

    /**
     * A cron expression: "No schedule" when it is empty, "Invalid cron" when it does not parse, else its next fire in the
     * server's zone, "Next 14:35:00, in 3 min" today or "Next Mon 14:35, in 2 d" later.
     */
    public static String cron(String expression, boolean valid, String nextFire, Instant now, ZoneId zone) {
        if (expression.isBlank()) return NO_SCHEDULE;
        if (!valid) return INVALID_CRON;
        Optional<OffsetDateTime> next = NextFire.parse(nextFire);
        if (next.isEmpty()) return NO_FIRE;
        ZonedDateTime at = next.get().atZoneSameInstant(zone);
        boolean today = at.toLocalDate().equals(now.atZone(zone).toLocalDate());
        return "Next " + (today ? TODAY : LATER).format(at) + ", " + in(now, next.get());
    }

    /** How long until a time: "in 42 s", "in 3 min", "in 5 h", "in 3 d", or "due now" once it has come. */
    public static String in(Instant now, OffsetDateTime next) {
        long seconds = Duration.between(now, next.toInstant()).toSeconds();
        if (seconds <= 0) return "due now";
        if (seconds < 60) return "in " + seconds + " s";
        if (seconds < 3_600) return "in " + seconds / 60 + " min";
        if (seconds < 48 * 3_600) return "in " + seconds / 3_600 + " h";
        return "in " + seconds / 86_400 + " d";
    }
}

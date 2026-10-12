package art.rehra.mineqtt.device.timer.core;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * A timer's {@code next_fire} as its state carries it (M4 plan D2): an ISO-8601 time with its offset, to the second
 * ({@code 2026-10-11T18:00:00+02:00}), which Home Assistant shows as a timestamp, or {@code ""} when no fire is known.
 */
public final class NextFire {
    /** No next fire known: Home Assistant's template turns it into {@code unknown}. */
    public static final String NONE = "";
    /**
     * How far a next fire must move before the state shows the new one (D2): a tick-based estimate drifts a little with
     * every tick of lag, and Home Assistant's recorder should not see each drift.
     */
    public static final Duration DEADBAND = Duration.ofSeconds(5);
    /** How often a timer computes its next fire again besides a fire, a settings change and its attach (D2). */
    public static final Duration RESYNC = Duration.ofSeconds(60);

    private NextFire() {
    }

    /** The text of a next fire; {@link #NONE} for none. */
    public static String format(Optional<ZonedDateTime> next) {
        return next.map(time -> time.truncatedTo(ChronoUnit.SECONDS).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).orElse(NONE);
    }

    /**
     * Whether a newly computed next fire replaces the one the state shows: one of them is none and the other is not, or
     * the two are more than {@link #DEADBAND} apart. A shown text that does not read as a time is always replaced.
     */
    public static boolean moved(String shown, String computed) {
        if (shown.equals(computed)) return false;
        Optional<OffsetDateTime> before = parse(shown);
        Optional<OffsetDateTime> after = parse(computed);
        if (before.isEmpty() || after.isEmpty()) return before.isPresent() || after.isPresent() || !shown.isEmpty();
        return Duration.between(before.get(), after.get()).abs().compareTo(DEADBAND) > 0;
    }

    /** The time a {@code next_fire} text names; empty for {@link #NONE} and anything unreadable. */
    public static Optional<OffsetDateTime> parse(String text) {
        if (text == null || text.isEmpty()) return Optional.empty();
        try {
            return Optional.of(OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}

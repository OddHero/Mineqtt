package art.rehra.mineqtt.telemetry.core;

import java.util.Objects;

/**
 * When the bridge publishes its telemetry (M4 plan D6), against the snapshot it published last. It samples once a
 * second ({@link #SAMPLE_MILLIS}):
 * <ul>
 *     <li>a change of {@code players_online}, {@code players}, {@code is_day} or {@code weather} (or of the keys
 *     themselves, {@code telemetry.enabled} toggled) is published at the next sample, at most once per
 *     {@link #SIGNIFICANT_FLOOR_MILLIS};</li>
 *     <li>any other change at most every {@code telemetry.intervalSeconds}, where {@code tps} counts only once it moved by
 *     {@link #TPS_DEADBAND} and {@code mspt} by {@link #MSPT_DEADBAND} from the published value;</li>
 *     <li>an unchanged snapshot is never published again here (a connect and Home Assistant's birth republish it).</li>
 * </ul>
 * The deadbands and the 15-minute steps of {@code time_of_day} keep Home Assistant's recorder from filling up.
 */
public final class TelemetryThrottle {
    public static final long SAMPLE_MILLIS = 1_000;
    public static final long SIGNIFICANT_FLOOR_MILLIS = 2_000;
    public static final double TPS_DEADBAND = 0.5;
    public static final double MSPT_DEADBAND = 1.0;
    /** The bounds of {@code telemetry.intervalSeconds} ({@code config.StaticDefaults}, which this core cannot import). */
    public static final int MIN_INTERVAL_SECONDS = 5;
    public static final int MAX_INTERVAL_SECONDS = 300;
    // The values are published with one decimal; a step of exactly a deadband counts.
    private static final double EPSILON = 1e-9;

    private TelemetryThrottle() {
    }

    /** {@code telemetry.intervalSeconds} in milliseconds, clamped to its bounds. */
    public static long intervalMillis(int seconds) {
        return Math.clamp(seconds, MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS) * 1_000L;
    }

    /**
     * Whether {@code current} is published now.
     *
     * @param published   the snapshot published last
     * @param publishedAt when it was published, in milliseconds of the same clock as {@code now}
     */
    public static boolean due(TelemetrySnapshot published, long publishedAt, TelemetrySnapshot current, long now, int intervalSeconds) {
        if (!changed(published, current)) return false;
        long since = now - publishedAt;
        return significant(published, current) ? since >= SIGNIFICANT_FLOOR_MILLIS : since >= intervalMillis(intervalSeconds);
    }

    /** A change Home Assistant should see within seconds: the players, day and night, the weather. */
    public static boolean significant(TelemetrySnapshot a, TelemetrySnapshot b) {
        if (a.playersOnline() != b.playersOnline() || a.details().isPresent() != b.details().isPresent()) return true;
        if (a.details().isEmpty()) return false;
        TelemetrySnapshot.Details x = a.details().get();
        TelemetrySnapshot.Details y = b.details().get();
        return !x.players().equals(y.players()) || x.isDay() != y.isDay() || x.weather() != y.weather();
    }

    /** Any change worth publishing: a significant one, or another key beyond the deadbands. */
    public static boolean changed(TelemetrySnapshot a, TelemetrySnapshot b) {
        if (significant(a, b) || a.droppedInbound() != b.droppedInbound() || a.droppedOutbound() != b.droppedOutbound()) return true;
        if (a.details().isEmpty()) return false;
        TelemetrySnapshot.Details x = a.details().get();
        TelemetrySnapshot.Details y = b.details().get();
        return Math.abs(x.tps() - y.tps()) >= TPS_DEADBAND - EPSILON || Math.abs(x.mspt() - y.mspt()) >= MSPT_DEADBAND - EPSILON
                || !Objects.equals(x.timeOfDay(), y.timeOfDay()) || x.day() != y.day();
    }
}

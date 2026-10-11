package art.rehra.mineqtt.twin.core;

import com.google.gson.JsonElement;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Home Assistant timestamps (M3 plan D1 #3). The {@code last_updated} and {@code last_changed} leaves are Python's raw
 * {@code datetime.isoformat()}, not JSON: {@code 2026-10-10T16:58:44.639470+00:00}, or without the fraction when the
 * microseconds are zero ({@code 2026-10-11T04:48:44+00:00}). The catalog carries the same text as a JSON string.
 * Parsing is lenient (any fraction, {@code +00:00} or {@code Z}, surrounding whitespace) and never throws: a value that
 * does not parse is simply absent.
 */
public final class HaTime {
    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter MICROS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS");

    private HaTime() {
    }

    /** An ISO-8601 date-time with an offset; empty when it is anything else. */
    public static Optional<Instant> parse(String text) {
        if (text == null) return Optional.empty();
        try {
            return Optional.of(OffsetDateTime.parse(text.strip(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** A timestamp leaf's payload, as UTF-8. */
    public static Optional<Instant> parse(byte[] payload) {
        return parse(new String(payload, StandardCharsets.UTF_8));
    }

    /** A JSON string holding a timestamp, as the catalog and datetime attributes carry it; empty for anything else. */
    public static Optional<Instant> parse(JsonElement json) {
        if (json == null || !json.isJsonPrimitive() || !json.getAsJsonPrimitive().isString()) return Optional.empty();
        return parse(json.getAsString());
    }

    /** As Home Assistant writes it, in UTC: microseconds only when not zero, and {@code +00:00} rather than {@code Z}. */
    public static String format(Instant instant) {
        OffsetDateTime utc = instant.atOffset(ZoneOffset.UTC);
        return (utc.getNano() / 1000 == 0 ? SECONDS : MICROS).format(utc) + "+00:00";
    }

    /** Whether {@code a} is strictly newer than {@code b}; a missing timestamp is older than every present one. */
    public static boolean newer(Instant a, Instant b) {
        if (a == null) return false;
        return b == null || a.isAfter(b);
    }
}

package art.rehra.mineqtt.device;

import com.google.gson.JsonObject;

import java.time.Instant;

/**
 * The one read-only line a type draws at the foot of its Controls tab (M4 plan D5): "Next 06:00, in 3 min", "Every
 * 5 s", "Invalid cron", "Disabled". The server computes it from the device's state when it builds a view, so it is as
 * fresh as the view; the view carries at most {@value #MAX_LENGTH} characters of it.
 */
@FunctionalInterface
public interface StatusLine {
    /** The most characters (code points) of a status line a device view carries; a longer line is cut with an ellipsis. */
    int MAX_LENGTH = 64;

    /**
     * The line for a device in {@code state} at {@code now}; empty for no line.
     *
     * @param state the state the device publishes, a copy the line may read but should not keep
     */
    String line(JsonObject state, Instant now);

    /** {@code line} as a view carries it: sanitised, stripped, at most {@value #MAX_LENGTH} code points with an ellipsis. */
    static String cap(String line) {
        String text = ValueCodecs.sanitizeText(line).strip();
        if (text.codePointCount(0, text.length()) <= MAX_LENGTH) return text;
        return text.substring(0, text.offsetByCodePoints(0, MAX_LENGTH - 1)) + "…";
    }
}

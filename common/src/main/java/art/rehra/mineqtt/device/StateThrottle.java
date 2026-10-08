package art.rehra.mineqtt.device;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The per-type publish limits of one device's state (design §6.3): at most one publish per {@code minInterval}, where
 * a newer state waits for the interval's end instead of being dropped, and changes inside every numeric deadband are
 * not published at all. The baseline is the last published state, so slow drift is published once it adds up.
 */
final class StateThrottle {
    enum Verdict {
        PUBLISH,
        /** Held until {@link #due}; a newer offer replaces it. */
        DEFER,
        /** Nothing worth publishing changed. */
        SKIP
    }

    private final long minIntervalMillis;
    private final Map<String, Double> deadbands;
    private JsonObject published;
    private long publishedAt;
    private JsonObject deferred;

    StateThrottle(Duration minInterval, Map<String, Double> deadbands) {
        this.minIntervalMillis = minInterval.toMillis();
        this.deadbands = deadbands;
    }

    static StateThrottle of(DeviceType type) {
        return new StateThrottle(type.minInterval(), type.deadbands());
    }

    Verdict offer(JsonObject state, long nowMillis) {
        if (published != null && insignificant(state)) {
            // The newest state is close to what was published, so a held state would now be stale.
            deferred = null;
            return Verdict.SKIP;
        }
        if (published != null && nowMillis - publishedAt < minIntervalMillis) {
            deferred = state;
            return Verdict.DEFER;
        }
        markPublished(state, nowMillis);
        return Verdict.PUBLISH;
    }

    /** The held state once its interval is over, which then counts as published; else null. */
    JsonObject due(long nowMillis) {
        if (deferred == null || nowMillis - publishedAt < minIntervalMillis) return null;
        JsonObject state = deferred;
        markPublished(state, nowMillis);
        return state;
    }

    /** A state published regardless of the limits, e.g. the full republish after a reconnect. */
    void markPublished(JsonObject state, long nowMillis) {
        published = state;
        publishedAt = nowMillis;
        deferred = null;
    }

    private boolean insignificant(JsonObject state) {
        Set<String> keys = new HashSet<>(published.keySet());
        keys.addAll(state.keySet());
        for (String key : keys) {
            JsonElement before = published.get(key);
            JsonElement now = state.get(key);
            if (before != null && before.equals(now)) continue;
            Double band = deadbands.get(key);
            if (band == null || !isNumber(before) || !isNumber(now)) return false;
            if (Math.abs(before.getAsDouble() - now.getAsDouble()) >= band) return false;
        }
        return true;
    }

    private static boolean isNumber(JsonElement value) {
        return value instanceof JsonPrimitive primitive && primitive.isNumber();
    }
}

package art.rehra.mineqtt.presence;

import com.google.gson.JsonObject;

import java.util.Optional;

/**
 * What a presence device publishes beyond presence for a player who consents to full sharing on a server in
 * {@code telemetry.players=full} (M4 plan D10): health rounded to 0.5, the dimension id and the block position.
 * {@link #due} decides when a new reading is published: a dimension change at once, otherwise at most every
 * {@value #MIN_INTERVAL_MILLIS} ms and only after the player moved {@value #POSITION_DEADBAND} blocks or the health
 * changed by {@value #HEALTH_DEADBAND}. Pure.
 *
 * @param health    rounded to 0.5
 * @param dimension the dimension's id, e.g. {@code minecraft:overworld}
 */
public record FullSample(double health, String dimension, int x, int y, int z) {
    /** The shortest time between two readings of one player, unless the dimension changes. */
    public static final long MIN_INTERVAL_MILLIS = 5_000;
    /** How far a player moves before a new position is published, in blocks. */
    public static final double POSITION_DEADBAND = 4;
    /** How far the health changes before it is published (half a heart is 1). */
    public static final double HEALTH_DEADBAND = 1;

    public FullSample {
        if (dimension == null) throw new IllegalArgumentException("A reading needs a dimension");
        health = Math.round(health * 2) / 2.0;
    }

    /**
     * Whether a reading is published now, after {@code last} was published at {@code lastAt}: always the first one and
     * a change of dimension, else only after {@link #MIN_INTERVAL_MILLIS} and when the player moved at least
     * {@link #POSITION_DEADBAND} blocks (straight line) or the health changed by at least {@link #HEALTH_DEADBAND}.
     */
    public static boolean due(Optional<FullSample> last, long lastAt, FullSample now, long nowMillis) {
        if (last.isEmpty() || !last.get().dimension.equals(now.dimension)) return true;
        if (nowMillis - lastAt < MIN_INTERVAL_MILLIS) return false;
        FullSample before = last.get();
        double dx = now.x - before.x;
        double dy = now.y - before.y;
        double dz = now.z - before.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) >= POSITION_DEADBAND || Math.abs(now.health - before.health) >= HEALTH_DEADBAND;
    }

    /** Adds {@code health}, {@code dimension}, {@code x}, {@code y} and {@code z} to a presence state. */
    public JsonObject addTo(JsonObject state) {
        state.addProperty("health", health);
        state.addProperty("dimension", dimension);
        state.addProperty("x", x);
        state.addProperty("y", y);
        state.addProperty("z", z);
        return state;
    }
}

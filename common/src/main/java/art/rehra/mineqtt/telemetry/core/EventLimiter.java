package art.rehra.mineqtt.telemetry.core;

import art.rehra.mineqtt.security.TokenBucket;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The caps of the bridge's player events (M4 plan D7): per player and event type at most one per
 * {@link PlayerEventMessage.Type#minIntervalMillis()} (5 s for join and leave, 1 s for death and advancement), and for
 * the whole bridge {@value #BRIDGE_PER_SECOND} per second. An event over the bridge cap is counted in {@link #dropped()},
 * which the bridge adds to its {@code dropped_outbound}; one within a player's interval is simply not published. Server
 * thread only.
 */
public final class EventLimiter {
    public static final int BRIDGE_PER_SECOND = 10;
    // Entries older than the longest interval no longer limit anything; they are pruned once the map is this large.
    private static final int PRUNE_AT = 256;

    private final LongSupplier millis;
    private final TokenBucket bridge;
    private final Map<Key, Long> lastPublished = new HashMap<>();
    private long dropped;

    public enum Verdict {
        PUBLISH,
        /** Within the player's interval for this event type. */
        PLAYER_CAP,
        /** Over the bridge's events per second; counted. */
        BRIDGE_CAP
    }

    private record Key(UUID player, PlayerEventMessage.Type type) {
    }

    /** @param millis a monotonic clock in milliseconds */
    public EventLimiter(LongSupplier millis) {
        this.millis = millis;
        this.bridge = new TokenBucket(BRIDGE_PER_SECOND, BRIDGE_PER_SECOND, () -> millis.getAsLong() * 1_000_000L);
    }

    /** Whether the event may be published now; a published one starts its player's interval. */
    public Verdict admit(PlayerEventMessage event) {
        long now = millis.getAsLong();
        Key key = new Key(event.player(), event.type());
        Long previous = lastPublished.get(key);
        if (previous != null && now - previous < event.type().minIntervalMillis()) return Verdict.PLAYER_CAP;
        if (!bridge.tryTake()) {
            dropped++;
            return Verdict.BRIDGE_CAP;
        }
        if (lastPublished.size() >= PRUNE_AT) prune(now);
        lastPublished.put(key, now);
        return Verdict.PUBLISH;
    }

    /** Events dropped over the bridge cap since the start. */
    public long dropped() {
        return dropped;
    }

    private void prune(long now) {
        long longest = 0;
        for (PlayerEventMessage.Type type : PlayerEventMessage.Type.values()) longest = Math.max(longest, type.minIntervalMillis());
        long horizon = longest;
        lastPublished.values().removeIf(at -> now - at >= horizon);
    }
}

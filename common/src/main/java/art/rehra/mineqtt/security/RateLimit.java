package art.rehra.mineqtt.security;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** One {@link TokenBucket} per player for one kind of action. Server thread only. */
public final class RateLimit {
    private final int capacity;
    private final double perSecond;
    private final LongSupplier nanoClock;
    private final Map<UUID, TokenBucket> buckets = new HashMap<>();

    /** A burst of {@code perSecond} actions, then {@code perSecond} on average. */
    public RateLimit(int perSecond) {
        this(perSecond, perSecond, System::nanoTime);
    }

    RateLimit(int capacity, double perSecond, LongSupplier nanoClock) {
        this.capacity = capacity;
        this.perSecond = perSecond;
        this.nanoClock = nanoClock;
    }

    public boolean tryTake(UUID player) {
        return buckets.computeIfAbsent(player, p -> new TokenBucket(capacity, perSecond, nanoClock)).tryTake();
    }

    /** The player left; a returning player starts with a full bucket. */
    public void forget(UUID player) {
        buckets.remove(player);
    }

    public void clear() {
        buckets.clear();
    }
}

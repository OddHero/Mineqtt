package art.rehra.mineqtt.security;

import java.util.function.LongSupplier;

/**
 * A rate limit: a burst of up to {@code capacity} actions, then {@code perSecond} on average. It starts full. Not
 * thread-safe; every bucket belongs to the server thread.
 */
public final class TokenBucket {
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private final double capacity;
    private final double perNano;
    private final LongSupplier nanoClock;
    private double tokens;
    private long refilledAt;

    /**
     * @param nanoClock a monotonic clock in nanoseconds, such as {@code System::nanoTime}
     */
    public TokenBucket(int capacity, double perSecond, LongSupplier nanoClock) {
        if (capacity < 1) throw new IllegalArgumentException("Capacity must be at least 1: " + capacity);
        if (!(perSecond > 0)) throw new IllegalArgumentException("Refill rate must be positive: " + perSecond);
        this.capacity = capacity;
        this.perNano = perSecond / NANOS_PER_SECOND;
        this.nanoClock = nanoClock;
        this.tokens = capacity;
        this.refilledAt = nanoClock.getAsLong();
    }

    /** Takes one token if there is one. */
    public boolean tryTake() {
        long now = nanoClock.getAsLong();
        // A clock that steps back refills nothing instead of draining the bucket.
        tokens = Math.min(capacity, tokens + Math.max(0, now - refilledAt) * perNano);
        refilledAt = now;
        if (tokens < 1) return false;
        tokens -= 1;
        return true;
    }
}

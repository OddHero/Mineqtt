package art.rehra.mineqtt.mqtt.core;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Collects outbound messages from any thread and releases them once per tick from the owner thread.
 * <ul>
 *     <li>{@code STATE} messages are coalesced to the latest per topic and skipped when their bytes equal the last
 *     ones released on that topic, unless they are a {@link Outbound#refresh() refresh}. A refresh stays one when a
 *     newer state for the topic replaces it.</li>
 *     <li>{@code EDGE} messages are never coalesced. Each origin (or, without one, each topic) may release
 *     {@link Limits#edgePerOriginPerSecond()} per second; the excess is dropped and counted.</li>
 *     <li>All releases share {@link Limits#globalPerSecond()}. What exceeds it waits for the next flush.</li>
 *     <li>A released message is looped back locally once, connected or not. While disconnected, {@code DROP}
 *     messages are counted and the others wait in a latest-per-topic outbox that is sent first after reconnecting.
 *     A full outbox evicts its oldest {@code COALESCE} entry; {@code KEEP} entries are never evicted.</li>
 * </ul>
 * Per-device minimum intervals and deadbands are applied by the device layer before messages get here.
 */
public final class OutboundBuffer {
    public record Limits(int globalPerSecond, int edgePerOriginPerSecond, int edgeBacklog, int outboxCapacity) {
        public static final Limits DEFAULT = new Limits(500, 40, 10_000, 10_000);

        public Limits {
            if (globalPerSecond <= 0 || edgePerOriginPerSecond <= 0 || edgeBacklog <= 0 || outboxCapacity <= 0) {
                throw new IllegalArgumentException("Outbound limits must be positive");
            }
        }
    }

    /** The broker as seen by one flush. */
    public enum Link {
        CONNECTED,
        /** Temporarily unreachable: the offline policies apply. */
        DISCONNECTED,
        /** No broker at all (MQTT disabled): messages are only looped back. */
        NONE
    }

    public interface Sink {
        /** Delivers a released message to local subscribers. Called once per released message. */
        void loopback(Outbound msg);

        /** Hands a message to the MQTT client. Only called during a {@link Link#CONNECTED} flush. */
        void send(Outbound msg);
    }

    private static final int MAX_IDLE_BUCKETS = 4_096;

    private final Limits limits;
    private final LongSupplier nanoClock;
    private final ConcurrentLinkedQueue<Outbound> intake = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Outbound> failed = new ConcurrentLinkedQueue<>();
    private final LinkedHashMap<String, Outbound> pendingStates = new LinkedHashMap<>();
    private final ArrayDeque<Outbound> pendingEdges = new ArrayDeque<>();
    private final LinkedHashMap<String, Outbound> outbox = new LinkedHashMap<>();
    private final Map<String, byte[]> lastReleased = new HashMap<>();
    private final Map<Object, RateBucket> edgeBuckets = new HashMap<>();
    private final RateBucket globalBucket;
    private final AtomicLong dropped = new AtomicLong();

    public OutboundBuffer(Limits limits, LongSupplier nanoClock) {
        this.limits = limits;
        this.nanoClock = nanoClock;
        this.globalBucket = new RateBucket(limits.globalPerSecond(), nanoClock.getAsLong());
    }

    /** Any thread. */
    public void add(Outbound msg) {
        intake.add(msg);
    }

    /**
     * Any thread: a message the client accepted but could not deliver because the connection or session was lost
     * before its acknowledgement. It goes to the outbox unless the policy is {@code DROP} or a newer message for the
     * topic is already waiting.
     */
    public void sendFailed(Outbound msg) {
        if (msg.offline() == OfflinePolicy.DROP) {
            dropped.incrementAndGet();
        } else {
            failed.add(msg);
        }
    }

    /**
     * Any thread: the broker refused a message (an error reason code such as not authorized) or the client could not
     * encode it. Sending the same message again would fail the same way, so it is only counted as dropped.
     */
    public void countRejected() {
        dropped.incrementAndGet();
    }

    /** Owner thread, after each CONNACK: the broker may have lost state, so nothing is deduplicated against before. */
    public void onConnected() {
        lastReleased.clear();
    }

    /** Owner thread, once per tick. */
    public void flush(Link link, Sink sink) {
        long now = nanoClock.getAsLong();
        acceptIntake(now);
        acceptFailed();

        if (link == Link.CONNECTED) {
            for (Iterator<Outbound> waiting = outbox.values().iterator(); waiting.hasNext(); ) {
                Outbound msg = waiting.next();
                // A newer state for the topic is about to go out; sending the old one first would only flicker.
                if (pendingStates.containsKey(msg.topic())) {
                    waiting.remove();
                    // The broker never got the outbox entry, so the newer state must not be deduplicated away.
                    lastReleased.remove(msg.topic());
                    continue;
                }
                if (!globalBucket.tryTake(now)) break;
                waiting.remove();
                sink.send(msg);
            }
        }
        while (!pendingEdges.isEmpty() && globalBucket.tryTake(now)) {
            release(pendingEdges.poll(), link, sink);
        }
        for (Iterator<Outbound> states = pendingStates.values().iterator(); states.hasNext(); ) {
            Outbound msg = states.next();
            if (!msg.refresh() && Arrays.equals(lastReleased.get(msg.topic()), msg.payload())) {
                states.remove();
                continue;
            }
            if (!globalBucket.tryTake(now)) break;
            states.remove();
            lastReleased.put(msg.topic(), msg.payload());
            release(msg, link, sink);
        }
    }

    /** Owner thread: forgets everything waiting, e.g. on shutdown. */
    public void clear() {
        intake.clear();
        failed.clear();
        pendingStates.clear();
        pendingEdges.clear();
        outbox.clear();
    }

    /** Messages dropped by the edge cap, the offline {@code DROP} policy, outbox eviction or a broker rejection. */
    public long dropped() {
        return dropped.get();
    }

    public int outboxSize() {
        return outbox.size();
    }

    /** Messages accepted but not yet released (excluding the outbox). */
    public int pendingSize() {
        return pendingStates.size() + pendingEdges.size();
    }

    private void acceptIntake(long now) {
        for (Outbound msg; (msg = intake.poll()) != null; ) {
            if (msg.delivery() == Outbound.Delivery.STATE) {
                Outbound replaced = pendingStates.put(msg.topic(), msg);
                if (replaced != null && replaced.refresh() && !msg.refresh()) pendingStates.put(msg.topic(), msg.asRefresh());
            } else if (!edgeBucket(msg, now).tryTake(now) || pendingEdges.size() >= limits.edgeBacklog()) {
                dropped.incrementAndGet();
            } else {
                pendingEdges.add(msg);
            }
        }
    }

    private void acceptFailed() {
        for (Outbound msg; (msg = failed.poll()) != null; ) {
            // The broker may not have it, so the next release of the same bytes must not be deduplicated away.
            lastReleased.remove(msg.topic());
            if (!pendingStates.containsKey(msg.topic()) && !outbox.containsKey(msg.topic())) {
                putOutbox(msg);
            }
        }
    }

    private void release(Outbound msg, Link link, Sink sink) {
        sink.loopback(msg);
        switch (link) {
            case CONNECTED -> sink.send(msg);
            case DISCONNECTED -> {
                if (msg.offline() == OfflinePolicy.DROP) {
                    dropped.incrementAndGet();
                } else {
                    putOutbox(msg);
                }
            }
            case NONE -> {
            }
        }
    }

    private void putOutbox(Outbound msg) {
        // Re-inserting moves the topic to the end, so the outbox stays ordered by latest change.
        outbox.remove(msg.topic());
        outbox.put(msg.topic(), msg);
        if (outbox.size() <= limits.outboxCapacity()) return;
        for (Iterator<Outbound> oldest = outbox.values().iterator(); oldest.hasNext(); ) {
            if (oldest.next().offline() == OfflinePolicy.COALESCE) {
                oldest.remove();
                dropped.incrementAndGet();
                return;
            }
        }
    }

    private RateBucket edgeBucket(Outbound msg, long now) {
        Object key = msg.origin() != null ? msg.origin() : msg.topic();
        RateBucket bucket = edgeBuckets.get(key);
        if (bucket == null) {
            if (edgeBuckets.size() >= MAX_IDLE_BUCKETS) {
                // A full bucket carries no history, so dropping it changes nothing.
                edgeBuckets.values().removeIf(idle -> idle.isFull(now));
            }
            bucket = new RateBucket(limits.edgePerOriginPerSecond(), now);
            edgeBuckets.put(key, bucket);
        }
        return bucket;
    }

    /** A token bucket holding one second's worth of tokens. */
    private static final class RateBucket {
        private final double capacity;
        private final double tokensPerNano;
        private double tokens;
        private long lastRefill;

        RateBucket(int perSecond, long now) {
            this.capacity = perSecond;
            this.tokensPerNano = perSecond / (double) TimeUnit.SECONDS.toNanos(1);
            this.tokens = perSecond;
            this.lastRefill = now;
        }

        boolean tryTake(long now) {
            refill(now);
            if (tokens < 1) return false;
            tokens -= 1;
            return true;
        }

        boolean isFull(long now) {
            refill(now);
            return tokens >= capacity;
        }

        private void refill(long now) {
            if (now > lastRefill) {
                tokens = Math.min(capacity, tokens + (now - lastRefill) * tokensPerNano);
                lastRefill = now;
            }
        }
    }
}

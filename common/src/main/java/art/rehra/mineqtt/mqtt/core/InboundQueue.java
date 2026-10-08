package art.rehra.mineqtt.mqtt.core;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

/**
 * The ordered, bounded hand-over from the MQTT threads to the server thread. Any thread may offer; one thread drains.
 * Entries are never coalesced, so no event is lost to a newer one. The queue is bounded by entry count and by the
 * summed weight of its entries (the bytes they hold); when either is exceeded, the oldest entries are dropped and
 * counted.
 */
public final class InboundQueue<T> {
    public static final int DEFAULT_CAPACITY = 10_000;
    /** 32 MiB: far above any normal backlog, far below what 10 000 entries of untrusted 64 KiB payloads would pin. */
    public static final long DEFAULT_MAX_BYTES = 32L * 1024 * 1024;

    private final ConcurrentLinkedQueue<T> queue = new ConcurrentLinkedQueue<>();
    // ConcurrentLinkedQueue.size() walks the whole queue, so the size is tracked separately.
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final int capacity;
    private final long maxBytes;
    private final ToLongFunction<? super T> weigher;
    private final LongSupplier nanoClock;

    /** @param weigher the bytes an entry holds; must return the same value every time for the same entry */
    public InboundQueue(int capacity, long maxBytes, ToLongFunction<? super T> weigher, LongSupplier nanoClock) {
        if (capacity <= 0) throw new IllegalArgumentException("Queue capacity must be positive: " + capacity);
        if (maxBytes <= 0) throw new IllegalArgumentException("Queue byte budget must be positive: " + maxBytes);
        this.capacity = capacity;
        this.maxBytes = maxBytes;
        this.weigher = weigher;
        this.nanoClock = nanoClock;
    }

    /** Bounded by entry count only. */
    public InboundQueue(int capacity, LongSupplier nanoClock) {
        this(capacity, Long.MAX_VALUE, entry -> 0, nanoClock);
    }

    public InboundQueue() {
        this(DEFAULT_CAPACITY, System::nanoTime);
    }

    public void offer(T entry) {
        queue.add(entry);
        size.incrementAndGet();
        bytes.addAndGet(weigher.applyAsLong(entry));
        while (size.get() > capacity || bytes.get() > maxBytes) {
            // A null poll means the drain thread emptied the queue meanwhile, which made the room instead.
            T oldest = queue.poll();
            if (oldest == null) break;
            taken(oldest);
            dropped.incrementAndGet();
        }
    }

    /**
     * Hands entries to {@code dispatcher} in FIFO order until the queue is empty, {@code maxEntries} were handed out,
     * or {@code budgetNanos} has elapsed. At least one entry is handed out per call, so the queue always progresses.
     * A dispatcher exception is passed to {@code onError} and does not stop the drain.
     *
     * @return the number of entries handed out
     */
    public int drain(long budgetNanos, int maxEntries, Consumer<? super T> dispatcher, BiConsumer<? super T, RuntimeException> onError) {
        long start = nanoClock.getAsLong();
        int handled = 0;
        while (handled < maxEntries && (handled == 0 || nanoClock.getAsLong() - start < budgetNanos)) {
            T entry = queue.poll();
            if (entry == null) break;
            taken(entry);
            handled++;
            try {
                dispatcher.accept(entry);
            } catch (RuntimeException e) {
                onError.accept(entry, e);
            }
        }
        return handled;
    }

    public int size() {
        return Math.max(0, size.get());
    }

    /** The summed weight of the queued entries. */
    public long bytes() {
        return Math.max(0, bytes.get());
    }

    /** Entries dropped because the queue was full. */
    public long dropped() {
        return dropped.get();
    }

    public void clear() {
        for (T entry; (entry = queue.poll()) != null; ) {
            taken(entry);
        }
    }

    private void taken(T entry) {
        size.decrementAndGet();
        bytes.addAndGet(-weigher.applyAsLong(entry));
    }
}

package art.rehra.mineqtt.mqtt.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Recognises the broker's copy of this server's own publishes where the broker sends one back: always on
 * MQTT 3.1.1, which has no no-local, and on MQTT 5 for filters that some handler subscribed without no-local.
 * <p>
 * Each publish handed to the client whose topic such a filter matches is recorded as {@code (topic, sha1(payload))}
 * for a short window. A broker message matching an entry consumes it and is treated as the own copy. A foreign
 * message that is byte-identical to an own publish within the window is mistaken for it; that is the documented
 * cost of a protocol without no-local. Thread-safe.
 */
public final class LocalLoopback {
    public static final long DEFAULT_WINDOW_NANOS = 2_000_000_000L;
    public static final int DEFAULT_CAPACITY = 10_000;

    /** A recognised own copy and the device that published it, which may be null. */
    public record Echo(UUID origin) {
    }

    private record Key(String topic, String digest) {
    }

    private static final class Entry {
        final Key key;
        final UUID origin;
        final long expiresAt;
        boolean consumed;

        Entry(Key key, UUID origin, long expiresAt) {
            this.key = key;
            this.origin = origin;
            this.expiresAt = expiresAt;
        }
    }

    private final long windowNanos;
    private final int capacity;
    private final LongSupplier nanoClock;
    private final ArrayDeque<Entry> byAge = new ArrayDeque<>();
    private final Map<Key, ArrayDeque<Entry>> byKey = new HashMap<>();

    public LocalLoopback(long windowNanos, int capacity, LongSupplier nanoClock) {
        if (windowNanos <= 0 || capacity <= 0) throw new IllegalArgumentException("Echo window and capacity must be positive");
        this.windowNanos = windowNanos;
        this.capacity = capacity;
        this.nanoClock = nanoClock;
    }

    /** Expects {@code copies} broker copies of an own publish, one per matching broker subscription that echoes. */
    public void record(String topic, byte[] payload, UUID origin, int copies) {
        Key key = new Key(topic, digest(payload));
        synchronized (this) {
            long now = nanoClock.getAsLong();
            expire(now);
            for (int i = 0; i < copies; i++) {
                Entry entry = new Entry(key, origin, now + windowNanos);
                byAge.addLast(entry);
                byKey.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(entry);
            }
            while (byAge.size() > capacity) {
                evict(byAge.pollFirst());
            }
        }
    }

    /** Consumes the oldest live entry for this message, if there is one. */
    public Optional<Echo> consume(String topic, byte[] payload) {
        Key key = new Key(topic, digest(payload));
        synchronized (this) {
            expire(nanoClock.getAsLong());
            ArrayDeque<Entry> entries = byKey.get(key);
            if (entries == null) return Optional.empty();
            Entry entry = entries.pollFirst();
            if (entries.isEmpty()) byKey.remove(key);
            entry.consumed = true;
            return Optional.of(new Echo(entry.origin));
        }
    }

    public synchronized int size() {
        expire(nanoClock.getAsLong());
        return byKey.values().stream().mapToInt(ArrayDeque::size).sum();
    }

    private void expire(long now) {
        while (!byAge.isEmpty() && (byAge.peekFirst().consumed || byAge.peekFirst().expiresAt - now <= 0)) {
            evict(byAge.pollFirst());
        }
    }

    private void evict(Entry entry) {
        if (entry.consumed) return;
        ArrayDeque<Entry> entries = byKey.get(entry.key);
        // Entries of one key are recorded in age order, so the evicted one is the head of its key's queue.
        entries.pollFirst();
        if (entries.isEmpty()) byKey.remove(entry.key);
    }

    private static String digest(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(payload));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM must provide SHA-1", e);
        }
    }
}

package art.rehra.mineqtt.twin;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The requests to Home Assistant that wait for their effect (M3 plan D6, D11): a mirrored device changes only when its
 * source does, so between a request and the next applied value of its entity the device is <i>pending</i>, which its
 * clients see as a faint sparkle. A request stops waiting when its entity is applied again, when the control blueprint
 * refuses it ({@code ha_response}), or after {@value #TIMEOUT_MILLIS} ms ("no change from Home Assistant").
 * <p>
 * At most {@value #MAX} requests wait; the oldest gives way to a new one. Pure and Minecraft-free; server thread only.
 */
public final class PendingRequests {
    /** How long a request waits for its entity to change, in milliseconds. */
    public static final long TIMEOUT_MILLIS = 5_000;
    /** How many requests wait at most. */
    public static final int MAX = 256;

    /**
     * One request that waits.
     *
     * @param player   who asked for it; null for the world (a lever, a wire)
     * @param accepted whether the control blueprint answered that it invoked the action
     */
    public record Pending(String id, UUID device, String deviceName, String entityId, String action, TwinRequests.@Nullable Requester player,
                          long sentAt, boolean accepted) {
        public Pending {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(device, "device");
            Objects.requireNonNull(deviceName, "deviceName");
            Objects.requireNonNull(entityId, "entityId");
            Objects.requireNonNull(action, "action");
        }

        Pending accept() {
            return new Pending(id, device, deviceName, entityId, action, player, sentAt, true);
        }
    }

    // Oldest first, by id.
    private final Map<String, Pending> byId = new LinkedHashMap<>();

    /**
     * Adds a request that now waits.
     *
     * @return the request that gave way to it when {@value #MAX} were waiting already
     */
    public Optional<Pending> add(Pending pending) {
        Pending evicted = null;
        if (!byId.containsKey(pending.id()) && byId.size() >= MAX) {
            Iterator<Pending> oldest = byId.values().iterator();
            evicted = oldest.next();
            oldest.remove();
        }
        byId.put(pending.id(), pending);
        return Optional.ofNullable(evicted);
    }

    /** The request of {@code id}, if it still waits. */
    public Optional<Pending> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** The control blueprint invoked the action of {@code id}: it keeps waiting for the entity to change. */
    public void accept(String id) {
        byId.computeIfPresent(id, (key, pending) -> pending.accept());
    }

    /** Stops waiting for {@code id}: the control blueprint refused it. */
    public Optional<Pending> remove(String id) {
        return Optional.ofNullable(byId.remove(id));
    }

    /** The entity was applied again: every request for it stops waiting. */
    public List<Pending> applied(String entityId) {
        return removeIf(pending -> pending.entityId().equals(entityId));
    }

    /** The requests that waited {@value #TIMEOUT_MILLIS} ms or longer by {@code now}, which stop waiting. */
    public List<Pending> expired(long now) {
        return removeIf(pending -> now - pending.sentAt() >= TIMEOUT_MILLIS);
    }

    /** The device went: its requests stop waiting. */
    public List<Pending> forget(UUID device) {
        return removeIf(pending -> pending.device().equals(device));
    }

    /** Whether a request of {@code device} waits. */
    public boolean pending(UUID device) {
        for (Pending pending : byId.values()) {
            if (pending.device().equals(device)) return true;
        }
        return false;
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }

    public int size() {
        return byId.size();
    }

    public void clear() {
        byId.clear();
    }

    private List<Pending> removeIf(Predicate<Pending> test) {
        List<Pending> removed = new ArrayList<>();
        Iterator<Pending> iterator = byId.values().iterator();
        while (iterator.hasNext()) {
            Pending pending = iterator.next();
            if (test.test(pending)) {
                removed.add(pending);
                iterator.remove();
            }
        }
        return removed;
    }
}

package art.rehra.mineqtt.twin.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * When the twin applies a changed entity (M3 plan D9). Statestream has no atomic update: a light turning on arrives as
 * {@code state}, then its timestamps, then each attribute, interleaved with other entities (cap §8). Applying each topic
 * would flash the lamp in its old colour for one delivery, so an entity waits until it had <b>one drain without a new
 * topic</b>, or at most {@value #MAX_WAIT_DRAINS} drains after its first change, and then applies its newest document once.
 * <p>
 * Then each entity applies at most {@value #APPLIES_PER_SECOND} times a second, the newest document winning: Home
 * Assistant effects such as "rainbow" republish constantly, and every lamp re-tint re-meshes its chunk section. A device
 * mirrors exactly one entity, so no device applies more often than that either.
 * <p>
 * Drains are counted by the caller (the twin's step after the inbound drain, which a paused server's pump runs too), the
 * clock is the caller's in milliseconds, so the class is deterministic. Single-threaded and Minecraft-free.
 */
public final class TwinCoalescer {
    /** The most drains an entity waits after its first change while topics keep coming. */
    public static final int MAX_WAIT_DRAINS = 5;
    public static final int APPLIES_PER_SECOND = 4;
    private static final long MIN_APPLY_INTERVAL_MILLIS = 1000 / APPLIES_PER_SECOND;

    // entity -> the drains of its first and its last change since it last applied, in the order entities changed
    private final Map<String, Burst> dirty = new LinkedHashMap<>();
    // entity -> when it last applied
    private final Map<String, Long> applied = new HashMap<>();

    private record Burst(long first, long last) {
    }

    /** A topic changed {@code entity}'s document in drain {@code drain}. */
    public void changed(String entity, long drain) {
        Objects.requireNonNull(entity, "entity");
        dirty.merge(entity, new Burst(drain, drain), (before, now) -> new Burst(before.first(), drain));
    }

    /**
     * The entities to apply in drain {@code drain} at {@code now}, in the order they first changed; they are no longer
     * dirty afterwards. An entity is due once a drain passed without a new topic for it, or {@value #MAX_WAIT_DRAINS}
     * drains after its first change, and once {@value #APPLIES_PER_SECOND}/s allow it; until then it stays dirty.
     */
    public List<String> due(long drain, long now) {
        List<String> due = new ArrayList<>();
        for (Iterator<Map.Entry<String, Burst>> entries = dirty.entrySet().iterator(); entries.hasNext(); ) {
            Map.Entry<String, Burst> entry = entries.next();
            Burst burst = entry.getValue();
            boolean settled = burst.last() < drain || drain - burst.first() >= MAX_WAIT_DRAINS;
            Long last = applied.get(entry.getKey());
            if (!settled || last != null && now - last < MIN_APPLY_INTERVAL_MILLIS) continue;
            entries.remove();
            applied.put(entry.getKey(), now);
            due.add(entry.getKey());
        }
        return due;
    }

    /**
     * An entity that applies now outside {@link #due}, as a device starts to mirror it: it counts towards the rate, and a
     * burst still under way applies again once it settled.
     */
    public void appliedNow(String entity, long now) {
        applied.put(entity, now);
    }

    /** Whether an entity waits to be applied. */
    public boolean pending() {
        return !dirty.isEmpty();
    }

    /** Whether {@code entity} waits to be applied. */
    public boolean pending(String entity) {
        return dirty.containsKey(entity);
    }

    /** Nobody mirrors {@code entity} any more. */
    public void forget(String entity) {
        dirty.remove(entity);
        applied.remove(entity);
    }

    /** Forgets everything (a new statestream base, the twin stopping). */
    public void clear() {
        dirty.clear();
        applied.clear();
    }
}

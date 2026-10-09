package art.rehra.mineqtt.security;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Tells players about a problem once (M2 plan D1): a notice is due when a subject's problem appears or changes, never
 * twice in a row for the same reason, however often the problem is seen again (a sensor publishing every tick, a link
 * judged again at every config reload). Once the problem is gone, its return is news again. Server thread only.
 *
 * @param <K> what a problem belongs to: a device, or a legacy block's topic
 */
public final class NoticeGate<K> {
    private final Map<K, String> current = new HashMap<>();

    /**
     * Records the subject's problem as it is now.
     *
     * @param problem the reason the subject does not work, or null when it works
     * @return whether the problem is news: present, and not the one recorded before
     */
    public boolean update(K subject, @Nullable String problem) {
        if (problem == null) {
            current.remove(subject);
            return false;
        }
        return !Objects.equals(current.put(subject, problem), problem);
    }

    /** The subject's problem as last recorded, or null. */
    public @Nullable String problem(K subject) {
        return current.get(subject);
    }

    /** The subject is gone; a subject with the same key starts without a recorded problem. */
    public void forget(K subject) {
        current.remove(subject);
    }

    public void clear() {
        current.clear();
    }
}

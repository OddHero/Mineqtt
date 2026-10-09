package art.rehra.mineqtt.device;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * The attach guard (M2 plan D7): the devices waiting for their attach, drained once per tick in the order they were
 * queued, each in isolation. An item whose step throws leaves the queue and is reported once, the items after it still
 * attach, and it is not tried again until it is queued again (its chunk loads again, or {@code /mineqtt devices retry}).
 * Free of Minecraft types, so a check drives it with plain values.
 *
 * @param <T> the queued item, compared by {@code equals}
 */
final class AttachQueue<T> {
    private final Set<T> items = new LinkedHashSet<>();

    /**
     * Runs a piece of one device's work in isolation: a {@link RuntimeException} is handed to {@code onFailure} instead
     * of reaching the tick, so the other devices still run.
     *
     * @return what the work returned; false when it threw
     */
    static <T> boolean isolate(T item, Predicate<? super T> work, BiConsumer<? super T, RuntimeException> onFailure) {
        try {
            return work.test(item);
        } catch (RuntimeException e) {
            onFailure.accept(item, e);
            return false;
        }
    }

    void add(T item) {
        items.add(item);
    }

    void addAll(Collection<? extends T> queued) {
        items.addAll(queued);
    }

    void remove(T item) {
        items.remove(item);
    }

    void clear() {
        items.clear();
    }

    boolean isEmpty() {
        return items.isEmpty();
    }

    /** How many queued items match. */
    int count(Predicate<? super T> matches) {
        return (int) items.stream().filter(matches).count();
    }

    /**
     * Runs {@code step} on every item queued now, in order. Items the steps queue meanwhile wait for the next drain; an
     * item a step dequeued is skipped.
     *
     * @param step      true when the item is done (attached or dropped), false to keep it for a later drain
     * @param onFailure gets an item whose step threw, once; the item has left the queue
     */
    void drain(Predicate<? super T> step, BiConsumer<? super T, RuntimeException> onFailure) {
        for (T item : List.copyOf(items)) {
            if (!items.contains(item)) continue;
            boolean done;
            try {
                done = step.test(item);
            } catch (RuntimeException e) {
                items.remove(item);
                onFailure.accept(item, e);
                continue;
            }
            if (done) items.remove(item);
        }
    }
}

package art.rehra.mineqtt.mqtt.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Topic filters that several holders need, each served by one handler on the {@link MqttService}: the first holder of a
 * filter subscribes it, the last one to let go closes that subscription. A holder that joins a filter already open
 * subscribes nothing again, so the broker replays no retained message to the handler for it and every message reaches
 * the handler once, however many hold the filter (the twin's per-leaf statestream subscriptions, M3 plan D1 #8, D17).
 * What a new holder needs to know right away it takes from whatever the handler folded so far.
 * <p>
 * Owner (server) thread only, as {@link MqttService#subscribe}.
 *
 * @param <H> what holds a filter, e.g. a device's UUID
 */
public final class SharedSubscriptions<H> {
    private final MqttService service;
    private final SubOptions options;
    private final InboundHandler handler;
    // filter -> its subscription and holders, in the order the filters were opened
    private final Map<String, Shared<H>> filters = new LinkedHashMap<>();
    // holder -> the filters it holds
    private final Map<H, Set<String>> held = new HashMap<>();

    private static final class Shared<H> {
        final Subscription subscription;
        final Set<H> holders = new LinkedHashSet<>();

        Shared(Subscription subscription) {
            this.subscription = subscription;
        }
    }

    /** @param handler receives every message of every open filter, once */
    public SharedSubscriptions(MqttService service, SubOptions options, InboundHandler handler) {
        this.service = Objects.requireNonNull(service, "service");
        this.options = Objects.requireNonNull(options, "options");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    /**
     * {@code holder} needs {@code filter}.
     *
     * @return whether this opened the filter: it had no holder before
     * @throws IllegalArgumentException for an invalid topic filter
     */
    public boolean hold(H holder, String filter) {
        Objects.requireNonNull(holder, "holder");
        Shared<H> shared = filters.get(filter);
        boolean opened = shared == null;
        if (opened) {
            shared = new Shared<>(service.subscribe(filter, options, handler));
            filters.put(filter, shared);
        }
        shared.holders.add(holder);
        held.computeIfAbsent(holder, key -> new LinkedHashSet<>()).add(filter);
        return opened;
    }

    /**
     * {@code holder} no longer needs {@code filter}; nothing when it did not hold it.
     *
     * @return whether this closed the filter: it was its last holder
     */
    public boolean release(H holder, String filter) {
        Set<String> own = held.get(holder);
        if (own == null || !own.remove(filter)) return false;
        if (own.isEmpty()) held.remove(holder);
        Shared<H> shared = filters.get(filter);
        shared.holders.remove(holder);
        if (!shared.holders.isEmpty()) return false;
        filters.remove(filter);
        shared.subscription.close();
        return true;
    }

    /**
     * {@code holder} needs none of its filters any more.
     *
     * @return the filters this closed
     */
    public List<String> releaseAll(H holder) {
        Set<String> own = held.get(holder);
        if (own == null) return List.of();
        return List.copyOf(own).stream().filter(filter -> release(holder, filter)).toList();
    }

    /** The filters {@code holder} holds, in the order it took them. */
    public Set<String> held(H holder) {
        Set<String> own = held.get(holder);
        return own == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(own));
    }

    /** How many hold {@code filter}; 0 for a filter that is not open. */
    public int holders(String filter) {
        Shared<H> shared = filters.get(filter);
        return shared == null ? 0 : shared.holders.size();
    }

    /** The open filters, in the order they were opened. */
    public Set<String> filters() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(filters.keySet()));
    }

    /** Closes every subscription and forgets every holder. */
    public void clear() {
        filters.values().forEach(shared -> shared.subscription.close());
        filters.clear();
        held.clear();
    }
}

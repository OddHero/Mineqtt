package art.rehra.mineqtt.runtime;

import art.rehra.mineqtt.mqtt.core.ConnectionState;
import art.rehra.mineqtt.mqtt.core.InboundHandler;
import art.rehra.mineqtt.mqtt.core.MqttService;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.mqtt.core.SubOptions;
import art.rehra.mineqtt.mqtt.core.Subscription;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * The runtime's {@link MqttService} as its users see it. It stays the same object when the connection behind it is
 * replaced (a config change of the broker settings), and moves every open subscription, connect listener and the
 * inbound listener over to the replacement, so nobody has to subscribe again. Same threading contract as {@link MqttService}.
 */
final class ReplaceableMqttService implements MqttService {
    private final Set<ReplaceableSubscription> subscriptions = new LinkedHashSet<>();
    private final List<Runnable> connectListeners = new CopyOnWriteArrayList<>();
    private volatile Runnable inboundListener;
    private volatile MqttService current;
    private volatile boolean closed;

    ReplaceableMqttService(MqttService initial) {
        this.current = initial;
    }

    /**
     * Owner thread: closes the current service, then creates its replacement. The old one is closed first because
     * both use the same client id; a broker taking the session over would publish the old one's will after the new
     * one's {@code online}.
     */
    void replace(Supplier<MqttService> next) {
        if (closed) return;
        current.close();
        MqttService replacement = next.get();
        // Registered before the next drain, so the first CONNACK of the replacement already runs them.
        connectListeners.forEach(replacement::onConnected);
        Runnable inbound = inboundListener;
        if (inbound != null) replacement.onInboundAvailable(inbound);
        synchronized (subscriptions) {
            current = replacement;
            for (ReplaceableSubscription subscription : subscriptions) {
                subscription.delegate = replacement.subscribe(subscription.filter, subscription.options, subscription.handler);
            }
        }
    }

    @Override
    public ConnectionState state() {
        return current.state();
    }

    @Override
    public void publish(Outbound msg) {
        current.publish(msg);
    }

    @Override
    public Subscription subscribe(String filter, SubOptions opts, InboundHandler handler) {
        synchronized (subscriptions) {
            ReplaceableSubscription subscription = new ReplaceableSubscription(filter, opts, handler, current.subscribe(filter, opts, handler));
            if (!closed) subscriptions.add(subscription);
            return subscription;
        }
    }

    @Override
    public void onConnected(Runnable listener) {
        connectListeners.add(listener);
        current.onConnected(listener);
    }

    @Override
    public void onInboundAvailable(Runnable listener) {
        inboundListener = listener;
        current.onInboundAvailable(listener);
    }

    @Override
    public boolean hasPendingWork() {
        return current.hasPendingWork();
    }

    @Override
    public void drainInbound(long budgetNanos, int maxMessages) {
        current.drainInbound(budgetNanos, maxMessages);
    }

    @Override
    public void flushOutbound() {
        current.flushOutbound();
    }

    @Override
    public Stats stats() {
        return current.stats();
    }

    @Override
    public void close() {
        synchronized (subscriptions) {
            closed = true;
            subscriptions.clear();
        }
        current.close();
    }

    private final class ReplaceableSubscription implements Subscription {
        private final String filter;
        private final SubOptions options;
        private final InboundHandler handler;
        // Both guarded by the subscriptions set.
        private Subscription delegate;
        private boolean closed;

        ReplaceableSubscription(String filter, SubOptions options, InboundHandler handler, Subscription delegate) {
            this.filter = filter;
            this.options = options;
            this.handler = handler;
            this.delegate = delegate;
        }

        @Override
        public String filter() {
            return filter;
        }

        @Override
        public void close() {
            Subscription open;
            synchronized (subscriptions) {
                if (closed) return;
                closed = true;
                subscriptions.remove(this);
                open = delegate;
            }
            open.close();
        }
    }
}

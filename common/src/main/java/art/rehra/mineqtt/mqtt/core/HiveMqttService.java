package art.rehra.mineqtt.mqtt.core;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttClientBuilder;
import com.hivemq.client.mqtt.exceptions.ConnectionClosedException;
import com.hivemq.client.mqtt.exceptions.ConnectionFailedException;
import com.hivemq.client.mqtt.exceptions.MqttClientStateException;
import com.hivemq.client.mqtt.exceptions.MqttSessionExpiredException;
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedContext;
import com.hivemq.client.mqtt.lifecycle.MqttClientReconnector;
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5ConnAckException;
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAckReasonCode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link MqttService} on the HiveMQ client, for MQTT 5 or 3.1.1.
 * <ul>
 *     <li>One global publish callback feeds the {@link InboundQueue}; it only enforces the payload limit and
 *     recognises own copies ({@link LocalLoopback}). Routing through the {@link TopicTrie} and the per-handler rules
 *     (retained, payload size, loopback) happen on the owner thread. On MQTT 5 the client also announces a maximum
 *     packet size, so the broker discards far larger messages instead of sending them; those are not counted here.
 *     MQTT 3.1.1 has no such limit, which leaves it to the broker's own message size limit.</li>
 *     <li>The broker filter table derives from the trie. Filters are ref-counted, and the broker is subscribed to
 *     the covering set: a filter covered by another registered filter (say {@code a/b} under {@code a/#}) is served
 *     by that subscription, whose options merge all handlers it serves. Brokers such as Mosquitto send one copy per
 *     matching subscription, so this keeps every handler at exactly one delivery. Filters that only partly overlap
 *     ({@code a/+/c} and {@code a/b/+}) are both subscribed, and such a broker still sends two copies of a message
 *     matching both. So may a message that the broker matches between the SUBSCRIBE and the UNSUBSCRIBE of one
 *     table change, which it receives back to back. After every CONNACK the whole table is re-sent in one SUBSCRIBE
 *     (HiveMQ's own resubscribe is off).</li>
 *     <li>A filter the broker refuses (its ACL, typically) never serves another one: the filters it took over are
 *     placed anew and subscribed on their own, which leaves them unsubscribed for one round trip. It is retried
 *     after every CONNACK and takes them over again once the broker accepts it.</li>
 *     <li>A retained message is only delivered to handlers that have no value on its topic yet in the current
 *     connection. The broker sends stored messages again for every SUBSCRIBE that asks for them, including those
 *     for another, joining handler; a handler that already has a value would otherwise go back to the stored one.</li>
 *     <li>Automatic reconnect with backoff, clean session, the will from {@link Options#will()}, which
 *     {@link #close()} also publishes before disconnecting. A publish lost with the connection waits in the outbox
 *     (unless its policy drops it); one the broker refuses is dropped and counted.</li>
 *     <li>A message's {@link Outbound#onAcknowledged() acknowledgement listener} is queued when the broker acknowledges
 *     it and runs during the next drain, after the connect listeners.</li>
 * </ul>
 */
public final class HiveMqttService implements MqttService {
    /** Shown when the first MQTT 5 connect fails in a way a 3.1.1-only broker would cause. */
    public static final String PROTOCOL_HINT = "the broker refused or dropped the MQTT 5 CONNECT - if it only supports MQTT 3.1.1 set protocol: 3.1.1";

    private static final long LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);
    /** Per handler: beyond this many topics a stored retained value may reach it again. */
    private static final int MAX_KNOWN_TOPICS = 4_096;
    private static final int MAX_LOGGED_TOPIC_CHARS = 256;

    /**
     * Tuning. {@code will} may be null.
     *
     * @param will                the last will (the bridge's retained {@code offline}); also published by {@link #close()}
     * @param inboundMaxBytes     the payload and topic bytes the inbound queue may hold before it drops the oldest
     * @param shutdownTimeoutMillis how long {@link #close()} waits in total for the will's acknowledgement and the
     *                              disconnect
     */
    public record Options(Outbound will, int inboundCapacity, long inboundMaxBytes, OutboundBuffer.Limits outboundLimits,
                          long echoWindowNanos, long reconnectInitialDelayMillis, long reconnectMaxDelayMillis,
                          long shutdownTimeoutMillis) {
        public Options {
            if (inboundCapacity <= 0 || inboundMaxBytes <= 0 || outboundLimits == null || echoWindowNanos <= 0
                    || reconnectInitialDelayMillis <= 0 || reconnectMaxDelayMillis < reconnectInitialDelayMillis
                    || shutdownTimeoutMillis <= 0) {
                throw new IllegalArgumentException("Invalid MQTT service options");
            }
        }

        /**
         * Production values: 10 000 queued messages or 32 MiB, 500 msg/s, reconnect after 1 s backing off to 60 s,
         * 2 s shutdown.
         */
        public static Options defaults(Outbound will) {
            return new Options(will, InboundQueue.DEFAULT_CAPACITY, InboundQueue.DEFAULT_MAX_BYTES, OutboundBuffer.Limits.DEFAULT,
                    LocalLoopback.DEFAULT_WINDOW_NANOS, 1_000, 60_000, 2_000);
        }

        public Options withReconnectDelays(long initialMillis, long maxMillis) {
            return new Options(will, inboundCapacity, inboundMaxBytes, outboundLimits, echoWindowNanos, initialMillis, maxMillis,
                    shutdownTimeoutMillis);
        }
    }

    private enum Route {
        /** A message from the broker that is not an own copy. */
        BROKER,
        /** The local copy of an own publish, for handlers with no-local. */
        LOOPBACK,
        /** The broker's copy of an own publish, for handlers without no-local. */
        ECHO
    }

    /** @param session the broker connection the message belongs to, counted up at every CONNACK */
    private record Delivery(Inbound message, Route route, long session) {
        long weight() {
            return message.payload().length + message.topic().length();
        }
    }

    /** The options one broker subscription is sent with: the merge of its handlers'. */
    private record BrokerFilter(int qos, boolean noLocal, boolean acceptsRetained) {
    }

    private final MqttLog log;
    private final Options options;
    private final BrokerConfig config;
    private final MqttClientAdapter client;
    private final InboundQueue<Delivery> inbound;
    private final OutboundBuffer outbound;
    private final LocalLoopback echoes;
    private final OutboundBuffer.Sink sink = new OutboundBuffer.Sink() {
        @Override
        public void loopback(Outbound msg) {
            loopbackLocally(msg);
        }

        @Override
        public void send(Outbound msg) {
            sendToBroker(msg);
        }
    };

    // Guards the trie, the broker filter table and the transitions that decide whether a SUBSCRIBE goes out now.
    private final Object lock = new Object();
    private final TopicTrie<SubscriptionImpl> trie = new TopicTrie<>();
    // The broker is subscribed to a covering set: a filter covered by another registered filter is served by that
    // one's subscription, so a broker that sends one copy per matching subscription cannot deliver a message twice.
    private final Map<String, String> brokerFilterOf = new HashMap<>();
    private final Map<String, Set<String>> servedFilters = new HashMap<>();
    private final Map<String, BrokerFilter> brokerFilters = new HashMap<>();
    // Registered filters the broker refused to subscribe; such a filter never serves another one.
    private final Set<String> refusedFilters = new HashSet<>();
    private final Object enqueueLock = new Object();
    private long nextSeq;

    private volatile ConnectionState state;
    private volatile long session;
    private volatile boolean stopped;
    private boolean everConnected;
    private CompletableFuture<Void> reconnectGate = CompletableFuture.completedFuture(null);
    private final AtomicInteger pendingConnects = new AtomicInteger();
    private final List<Runnable> connectListeners = new CopyOnWriteArrayList<>();
    // Acknowledgement listeners of published messages, queued by the client's threads for the owner thread.
    private final ConcurrentLinkedQueue<Runnable> acknowledged = new ConcurrentLinkedQueue<>();
    private final Set<CompletableFuture<?>> brokerOperations = ConcurrentHashMap.newKeySet();
    private final AtomicLong oversizedInbound = new AtomicLong();
    private final AtomicLong receivedFromBroker = new AtomicLong();
    private final LogLimiter logLimiter = new LogLimiter();

    private HiveMqttService(MqttLog log, Options options, BrokerConfig config, ConnectionState initial) {
        this.log = log;
        this.options = options;
        this.config = config;
        this.inbound = new InboundQueue<>(options.inboundCapacity(), options.inboundMaxBytes(), Delivery::weight, System::nanoTime);
        this.outbound = new OutboundBuffer(options.outboundLimits(), System::nanoTime);
        this.echoes = new LocalLoopback(options.echoWindowNanos(), LocalLoopback.DEFAULT_CAPACITY, System::nanoTime);
        this.state = initial;
        MqttClientAdapter adapter = null;
        if (config != null) {
            try {
                adapter = createAdapter(config);
            } catch (Exception e) {
                // Setup problems (unreadable CA file, password without user on 3.1.1) will not fix themselves.
                this.state = ConnectionState.failed(describe(e));
                log.error("MQTT client setup failed for " + config.redacted(), e);
            }
        }
        this.client = adapter;
    }

    /** A service that connects to {@code config} right away and keeps reconnecting until {@link #close()}. */
    public static HiveMqttService connect(BrokerConfig config, Options options, MqttLog log) {
        HiveMqttService service = new HiveMqttService(log, options, config, ConnectionState.CONNECTING);
        if (service.client != null) {
            log.info("Connecting to MQTT broker " + config.redacted());
            if (config.tls() && config.insecureSkipVerify()) {
                log.warn("TLS certificate verification is DISABLED (insecureSkipVerify) for " + config.redacted());
            }
            if (!config.tls() && (config.caFile() != null || config.insecureSkipVerify())) {
                log.warn("TLS settings are ignored because the broker URI " + config.uri() + " does not use TLS");
            }
            service.client.connect().whenComplete((ignored, error) -> {
                if (error != null) log.debug("Initial MQTT connect attempt failed: " + describe(error));
            });
        }
        return service;
    }

    /** A service without a broker (MQTT disabled): subscriptions and the local loopback work, nothing is sent. */
    public static HiveMqttService disabled(Options options, MqttLog log) {
        return new HiveMqttService(log, options, null, ConnectionState.DISABLED);
    }

    @Override
    public ConnectionState state() {
        return state;
    }

    @Override
    public void publish(Outbound msg) {
        if (stopped) return;
        outbound.add(msg);
    }

    @Override
    public Subscription subscribe(String filter, SubOptions opts, InboundHandler handler) {
        if (!TopicFilters.isValidFilter(filter)) throw new IllegalArgumentException("Invalid topic filter: " + filter);
        SubscriptionImpl subscription = new SubscriptionImpl(filter, opts, handler);
        synchronized (lock) {
            if (stopped) {
                subscription.closed = true;
                return subscription;
            }
            trie.add(filter, subscription);
            handlerAdded(filter, opts.acceptRetained());
        }
        return subscription;
    }

    @Override
    public void onConnected(Runnable listener) {
        connectListeners.add(listener);
    }

    @Override
    public void drainInbound(long budgetNanos, int maxMessages) {
        if (stopped) return;
        if (pendingConnects.getAndSet(0) > 0) {
            outbound.onConnected();
            for (Runnable listener : connectListeners) {
                try {
                    listener.run();
                } catch (RuntimeException e) {
                    log.error("MQTT connect listener failed", e);
                }
            }
        }
        for (Runnable listener; (listener = acknowledged.poll()) != null; ) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.error("MQTT acknowledgement listener failed", e);
            }
        }
        inbound.drain(budgetNanos, maxMessages, this::dispatch, (delivery, e) ->
                warnLimited("dispatch", "Failed to dispatch MQTT message on " + forLog(delivery.message().topic()), e));
    }

    @Override
    public void flushOutbound() {
        if (stopped) return;
        outbound.flush(link(), sink);
    }

    @Override
    public Stats stats() {
        return new Stats(inbound.dropped() + oversizedInbound.get(), outbound.dropped(), inbound.size(), outbound.outboxSize());
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (stopped) return;
            stopped = true;
            reconnectGate.complete(null);
        }
        // When offline there is nothing to disconnect: the reconnect gate calls off a scheduled attempt, and an
        // attempt already in flight is cancelled or disconnected by the client callbacks.
        if (client != null && state.isConnected()) {
            // Whatever the last tick produced still goes out, followed by the bridge's offline.
            outbound.flush(OutboundBuffer.Link.CONNECTED, sink);
            // One deadline for both steps, so a stalled connection holds the caller for the timeout at most once.
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.shutdownTimeoutMillis());
            if (options.will() != null) {
                await(client.publish(options.will()), "publish " + options.will().topic(), deadline);
            }
            await(client.disconnect(), "disconnect", deadline);
        }
        state = ConnectionState.STOPPED;
        inbound.clear();
        outbound.clear();
        acknowledged.clear();
        log.info("MQTT service stopped");
    }

    /** Checks only: waits until every SUBSCRIBE and UNSUBSCRIBE sent so far is acknowledged. */
    boolean awaitBrokerOperations(long timeout, TimeUnit unit) throws InterruptedException {
        CompletableFuture<?>[] operations;
        synchronized (lock) {
            operations = brokerOperations.toArray(CompletableFuture[]::new);
        }
        try {
            CompletableFuture.allOf(operations).get(timeout, unit);
            return true;
        } catch (ExecutionException e) {
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    /** Checks only: every PUBLISH the broker sent, whether or not a handler took it. */
    long receivedFromBroker() {
        return receivedFromBroker.get();
    }

    /** Checks only: the registered filters the broker refused. */
    Set<String> refusedFilters() {
        synchronized (lock) {
            return Set.copyOf(refusedFilters);
        }
    }

    private MqttClientAdapter createAdapter(BrokerConfig config) throws Exception {
        MqttClientBuilder builder = MqttClient.builder()
                .identifier(config.clientId())
                .serverHost(config.host())
                .serverPort(config.port())
                .automaticReconnect()
                .initialDelay(options.reconnectInitialDelayMillis(), TimeUnit.MILLISECONDS)
                .maxDelay(options.reconnectMaxDelayMillis(), TimeUnit.MILLISECONDS)
                .applyAutomaticReconnect()
                .addConnectedListener(context -> onClientConnected())
                .addDisconnectedListener(this::onClientDisconnected);
        if (config.tls()) {
            builder = builder.sslConfig(TlsSupport.sslConfig(config));
        }
        if (config.webSocket()) {
            builder = builder.webSocketConfig().serverPath(config.wsPath()).applyWebSocketConfig();
        }
        return switch (config.protocol()) {
            case MQTT_5 -> new Mqtt5ClientAdapter(builder, config, options.will(), this::receive);
            case MQTT_3_1_1 -> new Mqtt3ClientAdapter(builder, config, options.will(), this::receive);
        };
    }

    // ---- client callbacks (Netty threads) ----

    private void onClientConnected() {
        synchronized (lock) {
            if (stopped) {
                client.disconnect();
                return;
            }
            everConnected = true;
            state = ConnectionState.CONNECTED;
            // Only this callback writes it, before any PUBLISH of the new connection can arrive.
            session = session + 1;
            client.attachReceiver();
            // The session is fresh, so every filter is new to the broker and gets its retained messages. Sending
            // under the lock means a concurrent subscribe() is either in this batch or sent after it.
            List<MqttClientAdapter.BrokerSubscription> table = new ArrayList<>();
            brokerFilters.forEach((filter, merged) -> table.add(brokerSubscription(filter, merged, true)));
            if (!table.isEmpty()) sendSubscribe(table);
        }
        pendingConnects.incrementAndGet();
        log.info("Connected to MQTT broker " + config.redacted());
        if (config.tls() && config.insecureSkipVerify()) {
            log.warn("Connected WITHOUT TLS certificate verification (insecureSkipVerify)");
        }
    }

    private void onClientDisconnected(MqttClientDisconnectedContext context) {
        // After every CONNACK the service re-sends its own filter table; HiveMQ must not replay a stale one.
        context.getReconnector().resubscribeIfSessionExpired(false).republishIfSessionExpired(false);
        MqttClientReconnector reconnector = context.getReconnector();
        String cause;
        synchronized (lock) {
            if (stopped) {
                reconnector.reconnect(false);
                return;
            }
            // HiveMQ's disconnect() cannot cancel a scheduled reconnect, so each one waits for a gate that opens
            // after the backoff delay, or at once when close() runs; a stopped service then calls it off.
            CompletableFuture<Void> gate = new CompletableFuture<>();
            reconnectGate = gate;
            CompletableFuture.delayedExecutor(reconnector.getDelay(TimeUnit.NANOSECONDS), TimeUnit.NANOSECONDS)
                    .execute(() -> gate.complete(null));
            reconnector.reconnectWhen(gate, (ignored, error) -> {
                if (stopped) reconnector.reconnect(false);
            });
            cause = describe(context.getCause());
            if (!everConnected && config.protocol() == ProtocolVersion.MQTT_5 && mayBeProtocolMismatch(unwrap(context.getCause()))) {
                cause = cause + " (" + PROTOCOL_HINT + ")";
            }
            state = ConnectionState.reconnecting(cause);
        }
        warnLimited("disconnected", "MQTT connection to " + config.redacted() + " lost, reconnecting: " + cause, null);
    }

    private void receive(String topic, byte[] payload, boolean retain) {
        receivedFromBroker.incrementAndGet();
        if (payload.length > config.maxInboundBytes()) {
            oversizedInbound.incrementAndGet();
            warnLimited("oversized", "Dropped MQTT message on " + forLog(topic) + ": " + payload.length
                    + " bytes exceed the limit of " + config.maxInboundBytes(), null);
            return;
        }
        // The broker's copy of a live publish never carries the retain flag, so a retained message is no own copy.
        Optional<LocalLoopback.Echo> echo = retain ? Optional.empty() : echoes.consume(topic, payload);
        if (echo.isPresent()) {
            enqueue(topic, payload, retain, true, echo.get().origin(), Route.ECHO);
        } else {
            enqueue(topic, payload, retain, false, null, Route.BROKER);
        }
    }

    // ---- owner thread ----

    private void dispatch(Delivery delivery) {
        List<SubscriptionImpl> targets;
        synchronized (lock) {
            targets = trie.match(delivery.message().topic());
        }
        for (SubscriptionImpl subscription : targets) {
            if (subscription.closed || !accepts(subscription, delivery)) continue;
            try {
                subscription.handler.handle(delivery.message());
            } catch (RuntimeException e) {
                warnLimited("handler:" + subscription.filter, "MQTT handler for " + forLog(subscription.filter) + " failed", e);
            }
        }
    }

    private boolean accepts(SubscriptionImpl subscription, Delivery delivery) {
        SubOptions opts = subscription.opts;
        Inbound msg = delivery.message();
        if (msg.payload().length > opts.maxPayloadBytes()) {
            warnLimited("payload:" + subscription.filter, "Dropped MQTT message on " + forLog(msg.topic()) + " for "
                    + forLog(subscription.filter) + ": " + msg.payload().length + " bytes exceed the limit of " + opts.maxPayloadBytes(), null);
            return false;
        }
        if (msg.retained() && !opts.acceptRetained()) {
            logLimiter.once("retained:" + subscription.filter, () -> log.info("Ignoring retained MQTT messages for "
                    + forLog(subscription.filter) + ", first on " + forLog(msg.topic()) + " (commands are never taken from retained messages)"));
            return false;
        }
        boolean fromOwner = msg.origin() != null && msg.origin().equals(opts.owner());
        boolean deliver = switch (delivery.route()) {
            case BROKER -> true;
            case LOOPBACK -> opts.noLocal() && !fromOwner;
            case ECHO -> !opts.noLocal() && !fromOwner;
        };
        // A stored value is never newer than one the handler already has, own publishes included, so a retained
        // message the broker sends again for another handler's SUBSCRIBE must not take the handler back to it.
        if (opts.acceptRetained() && (deliver || fromOwner)) {
            boolean firstValue = subscription.learn(msg.topic(), delivery.session());
            if (msg.retained() && !firstValue) return false;
        }
        return deliver;
    }

    private void loopbackLocally(Outbound msg) {
        synchronized (lock) {
            boolean anyLocalReader = false;
            for (SubscriptionImpl subscription : trie.match(msg.topic())) {
                anyLocalReader |= subscription.opts.noLocal();
            }
            if (!anyLocalReader) return;
        }
        // Delivered like a live broker message: subscribers of a retained topic see retained=false.
        enqueue(msg.topic(), msg.payload(), false, true, msg.origin(), Route.LOOPBACK);
    }

    private void sendToBroker(Outbound msg) {
        int copies = 0;
        synchronized (lock) {
            for (Map.Entry<String, BrokerFilter> entry : brokerFilters.entrySet()) {
                if (echoesOwnPublishes(entry.getValue()) && TopicFilters.matches(entry.getKey(), msg.topic())) copies++;
            }
        }
        if (copies > 0) echoes.record(msg.topic(), msg.payload(), msg.origin(), copies);
        client.publish(msg).whenComplete((ignored, error) -> {
            if (error == null) {
                if (msg.onAcknowledged() != null && !stopped) acknowledged.add(msg.onAcknowledged());
                return;
            }
            if (isConnectionLoss(error)) {
                outbound.sendFailed(msg);
                warnLimited("publish", "MQTT publish to " + forLog(msg.topic()) + " failed: " + describe(error), null);
            } else {
                // An error reason code (not authorized, quota exceeded...) or a packet the client cannot encode.
                outbound.countRejected();
                logLimiter.once("rejected:" + msg.topic(), () -> log.warn("MQTT broker rejected the publish to "
                        + forLog(msg.topic()) + ", it is dropped: " + describe(error), null));
            }
        });
    }

    private boolean echoesOwnPublishes(BrokerFilter merged) {
        return config.protocol() == ProtocolVersion.MQTT_3_1_1 || !merged.noLocal();
    }

    /** A handler was added to the trie under {@code filter}. Caller holds {@link #lock}. */
    private void handlerAdded(String filter, boolean acceptsRetained) {
        Map<String, BrokerFilter> before = new HashMap<>(brokerFilters);
        Set<String> dirty = new HashSet<>();
        if (!brokerFilterOf.containsKey(filter)) place(filter, dirty);
        dirty.add(brokerFilterOf.get(filter));
        remerge(dirty);
        // A handler that wants retained messages gets them even when its filter is already served.
        syncBroker(before, acceptsRetained ? filter : null);
    }

    /** A handler was removed from the trie under {@code filter}. Caller holds {@link #lock}. */
    private void handlerRemoved(String filter) {
        Map<String, BrokerFilter> before = new HashMap<>(brokerFilters);
        Set<String> dirty = new HashSet<>();
        if (trie.get(filter).isEmpty()) {
            unregister(filter, dirty);
        } else {
            dirty.add(brokerFilterOf.get(filter));
        }
        remerge(dirty);
        syncBroker(before, null);
    }

    /**
     * Serves a newly registered filter: by an existing broker filter that covers it, or as a broker filter of its own
     * that takes over every broker filter it covers. A refused filter receives nothing, so it neither serves nor
     * takes over another filter.
     */
    private void place(String filter, Set<String> dirty) {
        for (Map.Entry<String, Set<String>> entry : servedFilters.entrySet()) {
            if (!refusedFilters.contains(entry.getKey()) && TopicFilters.covers(entry.getKey(), filter)) {
                brokerFilterOf.put(filter, entry.getKey());
                entry.getValue().add(filter);
                dirty.add(entry.getKey());
                return;
            }
        }
        Set<String> served = new LinkedHashSet<>();
        served.add(filter);
        if (!refusedFilters.contains(filter)) {
            for (Iterator<Map.Entry<String, Set<String>>> entries = servedFilters.entrySet().iterator(); entries.hasNext(); ) {
                Map.Entry<String, Set<String>> entry = entries.next();
                if (TopicFilters.covers(filter, entry.getKey())) {
                    entries.remove();
                    brokerFilters.remove(entry.getKey());
                    for (String member : entry.getValue()) {
                        brokerFilterOf.put(member, filter);
                        served.add(member);
                    }
                }
            }
        }
        servedFilters.put(filter, served);
        brokerFilterOf.put(filter, filter);
        dirty.add(filter);
    }

    /** Takes a broker filter apart and places it and the filters it served anew, after its refusal changed. */
    private void replace(String brokerFilter, Set<String> dirty) {
        Set<String> served = servedFilters.remove(brokerFilter);
        brokerFilters.remove(brokerFilter);
        for (String member : served) brokerFilterOf.remove(member);
        place(brokerFilter, dirty);
        for (String member : served) {
            if (!brokerFilterOf.containsKey(member)) place(member, dirty);
        }
    }

    /** Drops a filter without handlers; the filters a dropped broker filter served are placed anew. */
    private void unregister(String filter, Set<String> dirty) {
        // Forgotten with its last handler, which keeps the set bounded; a new handler on it simply tries again.
        refusedFilters.remove(filter);
        String brokerFilter = brokerFilterOf.remove(filter);
        Set<String> served = servedFilters.get(brokerFilter);
        served.remove(filter);
        if (!brokerFilter.equals(filter)) {
            dirty.add(brokerFilter);
            return;
        }
        servedFilters.remove(filter);
        brokerFilters.remove(filter);
        for (String orphan : served) brokerFilterOf.remove(orphan);
        for (String orphan : served) {
            if (!brokerFilterOf.containsKey(orphan)) place(orphan, dirty);
        }
    }

    /** Recomputes the options of the touched broker filters from every handler they serve. */
    private void remerge(Set<String> dirty) {
        for (String brokerFilter : dirty) {
            Set<String> served = servedFilters.get(brokerFilter);
            if (served == null) continue;
            int qos = 0;
            boolean noLocal = true;
            boolean acceptsRetained = false;
            for (String member : served) {
                for (SubscriptionImpl handler : trie.get(member)) {
                    qos = Math.max(qos, handler.opts.qos());
                    noLocal &= handler.opts.noLocal();
                    acceptsRetained |= handler.opts.acceptRetained();
                }
            }
            brokerFilters.put(brokerFilter, new BrokerFilter(qos, noLocal, acceptsRetained));
        }
    }

    /**
     * Brings the broker in line with {@link #brokerFilters}: one SUBSCRIBE for new and changed broker filters, then
     * UNSUBSCRIBE for dropped ones, so a message is never missed in between. {@code replayFilter} (or null) is a
     * filter whose new handler needs the retained messages: its broker filter is re-subscribed with SEND, or, when
     * it is served by a wider one, it is subscribed and at once unsubscribed, which fetches exactly its own retained
     * messages instead of the whole wider scope. Disconnected, nothing is sent: every CONNACK sends the full table.
     */
    private void syncBroker(Map<String, BrokerFilter> before, String replayFilter) {
        if (client == null || !state.isConnected()) return;
        List<MqttClientAdapter.BrokerSubscription> subscribe = new ArrayList<>();
        List<String> unsubscribe = new ArrayList<>();
        brokerFilters.forEach((brokerFilter, merged) -> {
            BrokerFilter previous = before.get(brokerFilter);
            boolean replay = brokerFilter.equals(replayFilter);
            if (previous == null || replay || needsResubscribe(previous, merged)) {
                subscribe.add(brokerSubscription(brokerFilter, merged, previous == null || replay));
            }
        });
        String servedBy = replayFilter == null ? null : brokerFilterOf.get(replayFilter);
        if (servedBy != null && !servedBy.equals(replayFilter) && before.containsKey(servedBy)) {
            // No-local keeps this short-lived MQTT 5 subscription from echoing an own publish.
            int qos = trie.get(replayFilter).stream().mapToInt(handler -> handler.opts.qos()).max().orElse(0);
            subscribe.add(new MqttClientAdapter.BrokerSubscription(replayFilter, qos, true, MqttClientAdapter.RetainedOnSubscribe.SEND));
            unsubscribe.add(replayFilter);
        }
        for (String brokerFilter : before.keySet()) {
            if (!brokerFilters.containsKey(brokerFilter)) unsubscribe.add(brokerFilter);
        }
        if (!subscribe.isEmpty()) sendSubscribe(subscribe);
        for (String filter : unsubscribe) {
            track(client.unsubscribe(filter)).whenComplete((ignored, error) -> {
                if (error != null) warnLimited("unsubscribe", "MQTT unsubscribe from " + forLog(filter) + " failed: " + describe(error), null);
            });
        }
    }

    /**
     * MQTT 3.1.1 resends every retained message of a filter on each SUBSCRIBE and has neither no-local nor retain
     * handling, so only a QoS raise is worth one there. A lower QoS is never worth a SUBSCRIBE.
     */
    private boolean needsResubscribe(BrokerFilter previous, BrokerFilter merged) {
        if (merged.qos() > previous.qos()) return true;
        return config.protocol() == ProtocolVersion.MQTT_5
                && (previous.noLocal() != merged.noLocal() || previous.acceptsRetained() != merged.acceptsRetained());
    }

    private MqttClientAdapter.BrokerSubscription brokerSubscription(String filter, BrokerFilter merged, boolean sendRetained) {
        MqttClientAdapter.RetainedOnSubscribe retained = !merged.acceptsRetained()
                ? MqttClientAdapter.RetainedOnSubscribe.DO_NOT_SEND
                : sendRetained ? MqttClientAdapter.RetainedOnSubscribe.SEND : MqttClientAdapter.RetainedOnSubscribe.SEND_IF_NEW;
        return new MqttClientAdapter.BrokerSubscription(filter, merged.qos(), merged.noLocal(), retained);
    }

    private void sendSubscribe(List<MqttClientAdapter.BrokerSubscription> subscriptions) {
        track(client.subscribe(subscriptions)).whenComplete((refused, error) -> {
            if (error != null) {
                warnLimited("subscribe", "MQTT subscribe failed: " + describe(error), null);
                return;
            }
            if (!refused.isEmpty()) {
                log.warn("MQTT broker refused the subscription to "
                        + String.join(", ", refused.stream().map(HiveMqttService::forLog).toList()));
            }
            synchronized (lock) {
                if (!stopped) subscribeAcknowledged(subscriptions, new HashSet<>(refused));
            }
        });
    }

    /**
     * Applies a SUBACK. A newly refused broker filter is demoted: it and the filters it served are placed anew, so
     * those the broker allows are subscribed on their own. A refused one the broker now accepts is promoted and takes
     * over the filters it covers. Unchanged outcomes, such as the retry at every CONNACK, change nothing. Caller
     * holds {@link #lock}.
     */
    private void subscribeAcknowledged(List<MqttClientAdapter.BrokerSubscription> subscriptions, Set<String> refused) {
        Map<String, BrokerFilter> before = new HashMap<>(brokerFilters);
        Set<String> dirty = new HashSet<>();
        for (MqttClientAdapter.BrokerSubscription subscription : subscriptions) {
            String filter = subscription.filter();
            // The temporary SUBSCRIBE of a retained replay is no broker filter, and the filter may be gone by now.
            if (!filter.equals(brokerFilterOf.get(filter))) continue;
            boolean changed = refused.contains(filter) ? refusedFilters.add(filter) : refusedFilters.remove(filter);
            if (changed) replace(filter, dirty);
        }
        if (dirty.isEmpty()) return;
        remerge(dirty);
        syncBroker(before, null);
    }

    private <T> CompletableFuture<T> track(CompletableFuture<T> operation) {
        brokerOperations.add(operation);
        operation.whenComplete((ignored, error) -> brokerOperations.remove(operation));
        return operation;
    }

    private void enqueue(String topic, byte[] payload, boolean retained, boolean local, UUID origin, Route route) {
        // The sequence number and the queue position are taken together, so seq order is delivery order.
        synchronized (enqueueLock) {
            inbound.offer(new Delivery(new Inbound(topic, payload, retained, local, origin, nextSeq++), route, session));
        }
    }

    private OutboundBuffer.Link link() {
        if (client == null) return OutboundBuffer.Link.NONE;
        return state.isConnected() ? OutboundBuffer.Link.CONNECTED : OutboundBuffer.Link.DISCONNECTED;
    }

    private void await(CompletableFuture<?> operation, String what, long deadlineNanos) {
        try {
            operation.get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            log.warn("MQTT shutdown: " + what + " did not complete: " + describe(e));
        }
    }

    private void warnLimited(String key, String message, Throwable cause) {
        if (logLimiter.allow(key)) log.warn(message, cause);
    }

    /**
     * Whether a publish failed because the connection or its session went away, not because the broker refused it.
     * HiveMQ fails in-flight and queued publishes with these when the clean session ends. Package-private for checks.
     */
    static boolean isConnectionLoss(Throwable error) {
        Throwable cause = unwrap(error);
        return cause instanceof ConnectionClosedException || cause instanceof MqttSessionExpiredException
                || cause instanceof MqttClientStateException;
    }

    /**
     * A 3.1.1-only broker closes the connection, or answers with a 3.1.1 CONNACK. HiveMQ reads the "unacceptable
     * protocol version" CONNACK (Mosquitto 1.5) as UNSUPPORTED_PROTOCOL_VERSION; other CONNACKs it cannot decode
     * surface as decode or closed errors. A TCP failure or any other MQTT 5 refusal is not a protocol mismatch.
     */
    private static boolean mayBeProtocolMismatch(Throwable cause) {
        if (cause instanceof Mqtt5ConnAckException refused) {
            return refused.getMqttMessage().getReasonCode() == Mqtt5ConnAckReasonCode.UNSUPPORTED_PROTOCOL_VERSION;
        }
        return !(cause instanceof ConnectionFailedException);
    }

    private static String describe(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause == null) return "unknown cause";
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** A topic or filter for the log: control characters escaped, so a broker client cannot forge lines, and capped. */
    private static String forLog(String topic) {
        StringBuilder text = new StringBuilder(Math.min(topic.length(), MAX_LOGGED_TOPIC_CHARS) + 3);
        for (int i = 0; i < topic.length(); i++) {
            if (text.length() >= MAX_LOGGED_TOPIC_CHARS) return text.append("...").toString();
            char c = topic.charAt(i);
            if (Character.isISOControl(c)) {
                text.append(String.format("\\u%04x", (int) c));
            } else {
                text.append(c);
            }
        }
        return text.toString();
    }

    private final class SubscriptionImpl implements Subscription {
        final String filter;
        final SubOptions opts;
        final InboundHandler handler;
        volatile boolean closed;
        // Owner thread only, for handlers that accept retained messages: the topics they have a value for.
        private final Set<String> knownTopics = new HashSet<>();
        private long knownSession;

        SubscriptionImpl(String filter, SubOptions opts, InboundHandler handler) {
            this.filter = filter;
            this.opts = opts;
            this.handler = handler;
        }

        @Override
        public String filter() {
            return filter;
        }

        /** Records a value on {@code topic}; returns whether it is the first one in that broker connection. */
        boolean learn(String topic, long session) {
            if (session > knownSession) {
                knownTopics.clear();
                knownSession = session;
            }
            if (knownTopics.contains(topic)) return false;
            // Past the bound a topic stays unknown: its stored value may arrive again, but no value is ever lost.
            if (knownTopics.size() < MAX_KNOWN_TOPICS) knownTopics.add(topic);
            return true;
        }

        @Override
        public void close() {
            synchronized (lock) {
                if (closed) return;
                closed = true;
                if (stopped) return;
                trie.remove(filter, this);
                handlerRemoved(filter);
            }
        }
    }

    /**
     * Rate limits repeated warnings per key, and remembers bounded "log once" keys. The two are kept apart, so a
     * flood of one-off keys cannot evict a rate limit.
     */
    private static final class LogLimiter {
        private static final int MAX_KEYS = 1_024;

        private final Map<String, Long> lastLogged = lru();
        private final Map<String, Boolean> loggedOnce = lru();

        synchronized boolean allow(String key) {
            long now = System.nanoTime();
            Long last = lastLogged.get(key);
            if (last != null && now - last < LOG_INTERVAL_NANOS) return false;
            lastLogged.put(key, now);
            return true;
        }

        void once(String key, Runnable logIt) {
            boolean first;
            synchronized (this) {
                first = loggedOnce.putIfAbsent(key, Boolean.TRUE) == null;
            }
            if (first) logIt.run();
        }

        private static <V> Map<String, V> lru() {
            return new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                    return size() > MAX_KEYS;
                }
            };
        }
    }
}

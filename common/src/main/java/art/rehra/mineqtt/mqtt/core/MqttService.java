package art.rehra.mineqtt.mqtt.core;

/**
 * The one MQTT connection of a server: publishing, ref-counted subscriptions with spec-correct wildcard routing,
 * ordered delivery on the owner (server) thread, and the local loopback that makes same-server links work with the
 * broker down.
 * <p>
 * Threading: {@link #publish} may be called from any thread. Everything else belongs to the owner thread, which
 * calls {@link #drainInbound} and {@link #flushOutbound} once per tick; handlers and connect listeners run there.
 */
public interface MqttService extends AutoCloseable {
    /** Default per-tick drain budget: 5 ms. */
    long DEFAULT_DRAIN_BUDGET_NANOS = 5_000_000L;
    int DEFAULT_MAX_MESSAGES_PER_TICK = 2_000;

    /** Drop counters for the bridge diagnostics. */
    record Stats(long droppedInbound, long droppedOutbound, int queuedInbound, int outboxSize) {
    }

    ConnectionState state();

    /** Any thread, non-blocking. After {@link #close()} it is silently ignored: no reconnect, no exception. */
    void publish(Outbound msg);

    /**
     * Ref-counted per filter: the first subscriber sends SUBSCRIBE, the last {@link Subscription#close()} sends
     * UNSUBSCRIBE. After {@link #close()} it returns an inert subscription.
     *
     * @throws IllegalArgumentException for an invalid topic filter
     */
    Subscription subscribe(String filter, SubOptions opts, InboundHandler handler);

    /** Runs {@code listener} on the owner thread, during the first drain after each CONNACK. */
    void onConnected(Runnable listener);

    /**
     * Runs the connect listeners after a CONNACK, then the {@link Outbound#onAcknowledged() acknowledgement listeners}
     * of messages the broker acknowledged, then the handlers of queued messages in arrival order, within the budget.
     */
    void drainInbound(long budgetNanos, int maxMessages);

    /** Releases buffered outbound messages to the client and the local loopback. */
    void flushOutbound();

    Stats stats();

    /**
     * Publishes the configured shutdown message (the bridge's {@code offline}), waits a bounded time for its
     * acknowledgement, disconnects and enters {@code STOPPED}. Blocks the calling thread for at most a few seconds.
     */
    @Override
    void close();
}

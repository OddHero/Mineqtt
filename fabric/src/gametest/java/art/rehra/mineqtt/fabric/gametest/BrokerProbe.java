package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.datatypes.MqttTopicFilter;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import com.hivemq.client.mqtt.mqtt5.Mqtt5ClientBuilder;
import com.hivemq.client.mqtt.mqtt5.message.auth.Mqtt5SimpleAuth;
import com.hivemq.client.mqtt.mqtt5.message.auth.Mqtt5SimpleAuthBuilder;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import com.hivemq.client.mqtt.mqtt5.message.subscribe.Mqtt5Subscription;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A second client on the test broker, standing in for any other party: it publishes probes and clears the retained
 * topics a suite left behind. It is a plain HiveMQ client, independent of the MineQTT code under test, and connects with
 * the same credentials as MineQTT.
 */
final class BrokerProbe implements AutoCloseable {
    /** Retained messages arrive right after the subscription; this much silence means all of them were delivered. */
    private static final long RETAINED_QUIET_MILLIS = 1_000;

    private final Mqtt5BlockingClient client;

    private BrokerProbe(Mqtt5BlockingClient client) {
        this.client = client;
    }

    static BrokerProbe connect(BrokerConfig broker) {
        Mqtt5ClientBuilder builder = Mqtt5Client.builder()
                .identifier("mineqtt-gametest-probe-" + UUID.randomUUID().toString().substring(0, 8))
                .serverHost(broker.host())
                .serverPort(broker.port());
        if (broker.webSocket()) {
            builder = builder.webSocketConfig().serverPath(broker.wsPath()).applyWebSocketConfig();
        }
        if (broker.username() != null || broker.password() != null) {
            builder = builder.simpleAuth(simpleAuth(broker));
        }
        Mqtt5BlockingClient client = builder.buildBlocking();
        client.connect();
        return new BrokerProbe(client);
    }

    void publish(String topic, String payload, boolean retain) {
        client.publishWith().topic(topic).payload(payload.getBytes(StandardCharsets.UTF_8)).retain(retain)
                .qos(MqttQos.AT_LEAST_ONCE).send();
    }

    /** The retained message on {@code topic} as a fresh subscriber gets it, or empty when there is none. */
    Optional<String> retained(String topic) {
        try (Mqtt5BlockingClient.Mqtt5Publishes publishes = client.publishes(MqttGlobalPublishFilter.ALL)) {
            client.subscribeWith().topicFilter(topic).send();
            Optional<Mqtt5Publish> message = publishes.receive(RETAINED_QUIET_MILLIS, TimeUnit.MILLISECONDS);
            client.unsubscribeWith().topicFilter(topic).send();
            return message.filter(Mqtt5Publish::isRetain).map(publish -> new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading the retained message of " + topic, e);
        }
    }

    /**
     * Subscribes to {@code topic} until the listener is closed. The game ticks only while the test waits for ticks, so
     * a suite polls the listener between {@code waitTicks} instead of blocking on the broker.
     */
    Listener listen(String topic) {
        Mqtt5BlockingClient.Mqtt5Publishes publishes = client.publishes(MqttGlobalPublishFilter.ALL);
        try {
            client.subscribeWith().topicFilter(topic).send();
        } catch (RuntimeException e) {
            publishes.close();
            throw e;
        }
        return new Listener(topic, publishes);
    }

    /** A subscription of {@link #listen}. */
    final class Listener implements AutoCloseable {
        private final String topic;
        private final MqttTopicFilter filter;
        private final Mqtt5BlockingClient.Mqtt5Publishes publishes;

        private Listener(String topic, Mqtt5BlockingClient.Mqtt5Publishes publishes) {
            this.topic = topic;
            this.filter = MqttTopicFilter.of(topic);
            this.publishes = publishes;
        }

        /**
         * The next message published live on its topic since the subscription, retained copies skipped; empty when none
         * came yet. Every subscription of the probe delivers into each listener's stream, so the others' messages are
         * skipped too.
         */
        Optional<String> nextLive() {
            Optional<Mqtt5Publish> message;
            while ((message = publishes.receiveNow()).isPresent()) {
                if (!message.get().isRetain() && filter.matches(message.get().getTopic())) {
                    return Optional.of(new String(message.get().getPayloadAsBytes(), StandardCharsets.UTF_8));
                }
            }
            return Optional.empty();
        }

        @Override
        public void close() {
            try {
                client.unsubscribeWith().topicFilter(topic).send();
            } finally {
                publishes.close();
            }
        }
    }

    /** Clears every retained message under any of {@code filters}, subscribed at once; returns the cleared topics. */
    Set<String> clearRetained(Collection<String> filters) {
        Set<String> retained = new LinkedHashSet<>();
        if (filters.isEmpty()) return retained;
        try (Mqtt5BlockingClient.Mqtt5Publishes publishes = client.publishes(MqttGlobalPublishFilter.ALL)) {
            client.subscribeWith()
                    .addSubscriptions(filters.stream().map(filter -> Mqtt5Subscription.builder().topicFilter(filter).build()))
                    .send();
            Optional<Mqtt5Publish> message;
            while ((message = publishes.receive(RETAINED_QUIET_MILLIS, TimeUnit.MILLISECONDS)).isPresent()) {
                if (message.get().isRetain()) retained.add(message.get().getTopic().toString());
            }
            client.unsubscribeWith().addTopicFilters(filters.stream().map(MqttTopicFilter::of)).send();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while collecting retained messages of " + filters, e);
        }
        for (String topic : retained) {
            client.publishWith().topic(topic).retain(true).qos(MqttQos.AT_LEAST_ONCE).send();
        }
        return retained;
    }

    @Override
    public void close() {
        client.disconnect();
    }

    // The same credentials MineQTT under test connects with.
    private static Mqtt5SimpleAuth simpleAuth(BrokerConfig broker) {
        Mqtt5SimpleAuthBuilder.Complete auth = broker.username() != null
                ? Mqtt5SimpleAuth.builder().username(broker.username())
                : Mqtt5SimpleAuth.builder().password(StandardCharsets.UTF_8.encode(CharBuffer.wrap(broker.password())));
        if (broker.username() != null && broker.password() != null) {
            auth = auth.password(StandardCharsets.UTF_8.encode(CharBuffer.wrap(broker.password())));
        }
        return auth.build();
    }
}

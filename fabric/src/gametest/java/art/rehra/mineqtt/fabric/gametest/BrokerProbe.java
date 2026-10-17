package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.datatypes.MqttTopic;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A second client on the test broker, standing in for any other party: it publishes probes and clears the retained
 * topics a suite left behind. It is a plain HiveMQ client, independent of the MineQTT code under test, and connects with
 * the same credentials as MineQTT.
 * <p>
 * Every message the client receives goes through one callback, which hands it at once to each open reader (a
 * {@link Listener}, or the short-lived reader of {@link #retained} and {@link #clearRetained}) whose filter matches, into
 * that reader's own queue. So any number of listeners on one probe each see every message of their topic, whenever and
 * in whatever order they are read. A filter stays subscribed on the broker while any reader holds it: reading a
 * retained message or closing one of two listeners on a topic leaves the others subscribed.
 */
final class BrokerProbe implements AutoCloseable {
    /** Retained messages arrive right after the subscription; this much silence means all of them were delivered. */
    private static final long RETAINED_QUIET_MILLIS = 1_000;

    private final Mqtt5BlockingClient client;
    private final List<Reader> readers = new CopyOnWriteArrayList<>();
    // How many readers hold each filter; the broker subscription goes with the last one.
    private final Map<String, Integer> held = new HashMap<>();

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
        BrokerProbe probe = new BrokerProbe(builder.buildBlocking());
        // Without backpressure: the callback runs on the client's event loop for every message and only queues it.
        probe.client.toAsync().publishes(MqttGlobalPublishFilter.ALL, probe::dispatch);
        probe.client.connect();
        return probe;
    }

    private void dispatch(Mqtt5Publish publish) {
        for (Reader reader : readers) {
            if (reader.matches(publish.getTopic())) reader.messages.add(publish);
        }
    }

    void publish(String topic, String payload, boolean retain) {
        client.publishWith().topic(topic).payload(payload.getBytes(StandardCharsets.UTF_8)).retain(retain)
                .qos(MqttQos.AT_LEAST_ONCE).send();
    }

    /** The retained message on {@code topic} as a fresh subscriber gets it, or empty when there is none. */
    Optional<String> retained(String topic) {
        try (Reader reader = open(List.of(topic))) {
            Mqtt5Publish message = reader.messages.poll(RETAINED_QUIET_MILLIS, TimeUnit.MILLISECONDS);
            return Optional.ofNullable(message).filter(Mqtt5Publish::isRetain)
                    .map(publish -> new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8));
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
        return new Listener(open(List.of(topic)));
    }

    /** A subscription of {@link #listen}. */
    final class Listener implements AutoCloseable {
        private final Reader reader;

        private Listener(Reader reader) {
            this.reader = reader;
        }

        /**
         * The next message published live on its topic since the subscription, retained copies skipped; empty when none
         * came yet. Only its own topic's messages reach it, whatever else the probe listens to.
         */
        Optional<String> nextLive() {
            Mqtt5Publish message;
            while ((message = reader.messages.poll()) != null) {
                if (!message.isRetain()) return Optional.of(new String(message.getPayloadAsBytes(), StandardCharsets.UTF_8));
            }
            return Optional.empty();
        }

        @Override
        public void close() {
            reader.close();
        }
    }

    /** Clears every retained message under any of {@code filters}, subscribed at once; returns the cleared topics. */
    Set<String> clearRetained(Collection<String> filters) {
        Set<String> retained = new LinkedHashSet<>();
        if (filters.isEmpty()) return retained;
        try (Reader reader = open(List.copyOf(filters))) {
            Mqtt5Publish message;
            while ((message = reader.messages.poll(RETAINED_QUIET_MILLIS, TimeUnit.MILLISECONDS)) != null) {
                if (message.isRetain()) retained.add(message.getTopic().toString());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while collecting retained messages of " + filters, e);
        }
        for (String topic : retained) {
            client.publishWith().topic(topic).retain(true).qos(MqttQos.AT_LEAST_ONCE).send();
        }
        return retained;
    }

    /**
     * A reader of the filters, registered before its SUBSCRIBE goes out so it misses nothing the broker sends in reply.
     * The SUBSCRIBE goes out also for a filter another reader holds, as a fresh subscriber gets the retained messages.
     */
    private Reader open(List<String> filters) {
        Reader reader = new Reader(filters);
        readers.add(reader);
        try {
            synchronized (held) {
                for (String filter : filters) held.merge(filter, 1, Integer::sum);
            }
            client.subscribeWith()
                    .addSubscriptions(filters.stream().map(filter -> Mqtt5Subscription.builder().topicFilter(filter).build()))
                    .send();
        } catch (RuntimeException e) {
            reader.close();
            throw e;
        }
        return reader;
    }

    /** The queue of one reader: every message on any of its filters since it opened. */
    private final class Reader implements AutoCloseable {
        private final List<String> filters;
        private final List<MqttTopicFilter> parsed = new ArrayList<>();
        private final LinkedBlockingQueue<Mqtt5Publish> messages = new LinkedBlockingQueue<>();
        private boolean closed;

        private Reader(List<String> filters) {
            this.filters = filters;
            for (String filter : filters) parsed.add(MqttTopicFilter.of(filter));
        }

        private boolean matches(MqttTopic topic) {
            for (MqttTopicFilter filter : parsed) {
                if (filter.matches(topic)) return true;
            }
            return false;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            readers.remove(this);
            List<String> released = new ArrayList<>();
            synchronized (held) {
                for (String topic : filters) {
                    if (held.merge(topic, -1, Integer::sum) <= 0) {
                        held.remove(topic);
                        released.add(topic);
                    }
                }
            }
            if (!released.isEmpty()) {
                client.unsubscribeWith().addTopicFilters(released.stream().map(MqttTopicFilter::of)).send();
            }
        }
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

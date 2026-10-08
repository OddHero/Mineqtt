package art.rehra.mineqtt.mqtt.core;

import com.hivemq.client.mqtt.MqttClientBuilder;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3ClientBuilder;
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3SubAckException;
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuth;
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuthBuilder;
import com.hivemq.client.mqtt.mqtt3.message.subscribe.Mqtt3Subscribe;
import com.hivemq.client.mqtt.mqtt3.message.subscribe.Mqtt3Subscription;
import com.hivemq.client.mqtt.mqtt3.message.subscribe.suback.Mqtt3SubAck;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * MQTT 3.1.1: clean session. The protocol has neither no-local nor retain handling, so {@link HiveMqttService}
 * filters own copies through {@link LocalLoopback} and drops retained messages per handler. Nor can the client limit
 * the size of the packets it receives: a message is read whole before the inbound payload limit drops it, so the
 * broker's own limit (Mosquitto {@code message_size_limit}) has to keep huge messages away.
 */
final class Mqtt3ClientAdapter implements MqttClientAdapter {
    private final Mqtt3AsyncClient client;
    private final int keepAliveSec;
    private final Receiver receiver;

    /**
     * @param will the last will, or null
     * @throws IllegalArgumentException when a password is set without a username, which MQTT 3.1.1 cannot send
     */
    Mqtt3ClientAdapter(MqttClientBuilder base, BrokerConfig config, Outbound will, Receiver receiver) {
        Mqtt3ClientBuilder builder = base.useMqttVersion3();
        if (config.username() != null) {
            Mqtt3SimpleAuthBuilder.Complete auth = Mqtt3SimpleAuth.builder().username(config.username());
            if (config.password() != null) auth = auth.password(config.passwordUtf8());
            builder = builder.simpleAuth(auth.build());
        } else if (config.password() != null) {
            throw new IllegalArgumentException("MQTT 3.1.1 cannot send a password without a username");
        }
        if (will != null) {
            builder = builder.willPublish()
                    .topic(will.topic())
                    .payload(will.payload())
                    .qos(MqttClientAdapter.qos(will.qos()))
                    .retain(will.retain())
                    .applyWillPublish();
        }
        this.client = builder.buildAsync();
        this.keepAliveSec = config.keepAliveSec();
        this.receiver = receiver;
    }

    @Override
    public void attachReceiver() {
        client.publishes(MqttGlobalPublishFilter.ALL,
                publish -> receiver.receive(publish.getTopic().toString(), publish.getPayloadAsBytes(), publish.isRetain()));
    }

    @Override
    public CompletableFuture<Void> connect() {
        return client.connectWith()
                .cleanSession(true)
                .keepAlive(keepAliveSec)
                .send()
                .thenApply(connAck -> null);
    }

    @Override
    public CompletableFuture<Void> publish(Outbound msg) {
        return client.publishWith()
                .topic(msg.topic())
                .payload(msg.payload())
                .qos(MqttClientAdapter.qos(msg.qos()))
                .retain(msg.retain())
                .send()
                .thenApply(publish -> null);
    }

    @Override
    public CompletableFuture<List<String>> subscribe(List<BrokerSubscription> subscriptions) {
        List<Mqtt3Subscription> entries = new ArrayList<>(subscriptions.size());
        for (BrokerSubscription subscription : subscriptions) {
            entries.add(Mqtt3Subscription.builder()
                    .topicFilter(subscription.filter())
                    .qos(MqttClientAdapter.qos(subscription.qos()))
                    .build());
        }
        return client.subscribe(Mqtt3Subscribe.builder().addSubscriptions(entries).build())
                .handle((subAck, error) -> {
                    // HiveMQ fails the future when every return code is a failure, and completes it otherwise.
                    if (error instanceof CompletionException wrapped && wrapped.getCause() != null) error = wrapped.getCause();
                    if (error instanceof Mqtt3SubAckException refused) subAck = refused.getMqttMessage();
                    else if (error != null) throw new CompletionException(error);
                    return refusedFilters(subscriptions, subAck);
                });
    }

    @Override
    public CompletableFuture<Void> unsubscribe(String filter) {
        return client.unsubscribeWith().addTopicFilter(filter).send();
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        return client.disconnect();
    }

    private static List<String> refusedFilters(List<BrokerSubscription> subscriptions, Mqtt3SubAck subAck) {
        List<String> refused = new ArrayList<>();
        for (int i = 0; i < subscriptions.size(); i++) {
            if (i >= subAck.getReturnCodes().size() || subAck.getReturnCodes().get(i).isError()) {
                refused.add(subscriptions.get(i).filter());
            }
        }
        return refused;
    }
}

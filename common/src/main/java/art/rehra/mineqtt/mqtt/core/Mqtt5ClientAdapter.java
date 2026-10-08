package art.rehra.mineqtt.mqtt.core;

import com.hivemq.client.mqtt.MqttClientBuilder;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5ClientBuilder;
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5SubAckException;
import com.hivemq.client.mqtt.mqtt5.message.auth.Mqtt5SimpleAuth;
import com.hivemq.client.mqtt.mqtt5.message.auth.Mqtt5SimpleAuthBuilder;
import com.hivemq.client.mqtt.mqtt5.message.connect.Mqtt5ConnectRestrictions;
import com.hivemq.client.mqtt.mqtt5.message.subscribe.Mqtt5RetainHandling;
import com.hivemq.client.mqtt.mqtt5.message.subscribe.Mqtt5Subscribe;
import com.hivemq.client.mqtt.mqtt5.message.subscribe.Mqtt5Subscription;
import com.hivemq.client.mqtt.mqtt5.message.subscribe.suback.Mqtt5SubAck;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * MQTT 5: clean start with session expiry 0, no-local and retain handling per subscription, and a maximum packet size
 * derived from the inbound payload limit.
 */
final class Mqtt5ClientAdapter implements MqttClientAdapter {
    /** Room for everything in a PUBLISH but its payload: a topic name of up to 64 KiB, the headers and properties. */
    private static final int PACKET_OVERHEAD_BYTES = 68 * 1024;

    private final Mqtt5AsyncClient client;
    private final int keepAliveSec;
    private final int maximumPacketSize;
    private final Receiver receiver;

    /** @param will the last will, or null */
    Mqtt5ClientAdapter(MqttClientBuilder base, BrokerConfig config, Outbound will, Receiver receiver) {
        Mqtt5ClientBuilder builder = base.useMqttVersion5();
        if (config.username() != null || config.password() != null) {
            builder = builder.simpleAuth(simpleAuth(config));
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
        this.maximumPacketSize = (int) Math.min((long) config.maxInboundBytes() + PACKET_OVERHEAD_BYTES,
                Mqtt5ConnectRestrictions.DEFAULT_MAXIMUM_PACKET_SIZE);
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
                .cleanStart(true)
                .sessionExpiryInterval(0)
                .keepAlive(keepAliveSec)
                // The broker must not send larger packets, and HiveMQ refuses one from the fixed header, before
                // buffering it; without this a single PUBLISH of up to 256 MiB would be read before being dropped.
                .restrictions().maximumPacketSize(maximumPacketSize).applyRestrictions()
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
                .thenApply(result -> {
                    // A PUBACK with an error reason code completes the future normally and reports it here.
                    result.getError().ifPresent(error -> {
                        throw new CompletionException(error);
                    });
                    return null;
                });
    }

    @Override
    public CompletableFuture<List<String>> subscribe(List<BrokerSubscription> subscriptions) {
        List<Mqtt5Subscription> entries = new ArrayList<>(subscriptions.size());
        for (BrokerSubscription subscription : subscriptions) {
            entries.add(Mqtt5Subscription.builder()
                    .topicFilter(subscription.filter())
                    .qos(MqttClientAdapter.qos(subscription.qos()))
                    .noLocal(subscription.noLocal())
                    .retainHandling(switch (subscription.retained()) {
                        case SEND -> Mqtt5RetainHandling.SEND;
                        case SEND_IF_NEW -> Mqtt5RetainHandling.SEND_IF_SUBSCRIPTION_DOES_NOT_EXIST;
                        case DO_NOT_SEND -> Mqtt5RetainHandling.DO_NOT_SEND;
                    })
                    .build());
        }
        return client.subscribe(Mqtt5Subscribe.builder().addSubscriptions(entries).build())
                .handle((subAck, error) -> {
                    // HiveMQ fails the future when every reason code is an error, and completes it otherwise.
                    if (error instanceof CompletionException wrapped && wrapped.getCause() != null) error = wrapped.getCause();
                    if (error instanceof Mqtt5SubAckException refused) subAck = refused.getMqttMessage();
                    else if (error != null) throw new CompletionException(error);
                    return refusedFilters(subscriptions, subAck);
                });
    }

    @Override
    public CompletableFuture<Void> unsubscribe(String filter) {
        return client.unsubscribeWith().addTopicFilter(filter).send().thenApply(unsubAck -> null);
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        return client.disconnect();
    }

    private static List<String> refusedFilters(List<BrokerSubscription> subscriptions, Mqtt5SubAck subAck) {
        List<String> refused = new ArrayList<>();
        for (int i = 0; i < subscriptions.size(); i++) {
            if (i >= subAck.getReasonCodes().size() || subAck.getReasonCodes().get(i).isError()) {
                refused.add(subscriptions.get(i).filter());
            }
        }
        return refused;
    }

    private static Mqtt5SimpleAuth simpleAuth(BrokerConfig config) {
        Mqtt5SimpleAuthBuilder.Complete auth = config.username() != null
                ? Mqtt5SimpleAuth.builder().username(config.username())
                : Mqtt5SimpleAuth.builder().password(config.passwordUtf8());
        if (config.username() != null && config.password() != null) {
            auth = auth.password(config.passwordUtf8());
        }
        return auth.build();
    }
}

package art.rehra.mineqtt.mqtt.core;

import com.hivemq.client.mqtt.datatypes.MqttQos;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** The protocol-specific half of {@link HiveMqttService}: one HiveMQ client of either MQTT version. */
interface MqttClientAdapter {
    /** Which retained messages the broker sends on SUBSCRIBE. MQTT 3.1.1 always sends them. */
    enum RetainedOnSubscribe { SEND, SEND_IF_NEW, DO_NOT_SEND }

    record BrokerSubscription(String filter, int qos, boolean noLocal, RetainedOnSubscribe retained) {
    }

    /** Receives every PUBLISH from the broker, on a Netty thread. */
    @FunctionalInterface
    interface Receiver {
        void receive(String topic, byte[] payload, boolean retain);
    }

    /** Clean session, keep alive from the config; the will and credentials are part of the client. */
    CompletableFuture<Void> connect();

    /**
     * Routes every incoming PUBLISH to the {@link Receiver}. HiveMQ ends global publish flows together with the
     * session, which with a clean session is every disconnect, so this runs on each CONNACK, before any SUBSCRIBE.
     */
    void attachReceiver();

    /** Completes when the broker acknowledged the message (QoS 0: when it was written). */
    CompletableFuture<Void> publish(Outbound msg);

    /** One SUBSCRIBE for all of them; completes with the filters the broker refused. */
    CompletableFuture<List<String>> subscribe(List<BrokerSubscription> subscriptions);

    CompletableFuture<Void> unsubscribe(String filter);

    CompletableFuture<Void> disconnect();

    static MqttQos qos(int qos) {
        return MqttQos.fromCode(qos);
    }
}

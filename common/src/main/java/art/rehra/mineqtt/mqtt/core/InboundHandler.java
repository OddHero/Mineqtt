package art.rehra.mineqtt.mqtt.core;

/** Receives inbound messages on the thread that calls {@link MqttService#drainInbound}. */
@FunctionalInterface
public interface InboundHandler {
    void handle(Inbound msg);
}

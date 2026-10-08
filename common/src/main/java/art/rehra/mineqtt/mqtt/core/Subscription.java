package art.rehra.mineqtt.mqtt.core;

/** One handler's registration on a topic filter. Closing it is idempotent. */
public interface Subscription extends AutoCloseable {
    String filter();

    @Override
    void close();
}

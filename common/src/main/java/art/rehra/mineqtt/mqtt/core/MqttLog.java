package art.rehra.mineqtt.mqtt.core;

/**
 * The logging sink of {@code mqtt.core}, which may not depend on a logging framework; the game wires it to the mod
 * logger. {@code cause} may be null.
 */
public interface MqttLog {
    void debug(String message);

    void info(String message);

    void warn(String message, Throwable cause);

    void error(String message, Throwable cause);

    default void warn(String message) {
        warn(message, null);
    }
}

package art.rehra.mineqtt.mqtt.core;

/**
 * Where the broker connection stands, plus a human-readable detail for {@code /mineqtt status}: the last disconnect
 * cause while {@code RECONNECTING} (including the MQTT 3.1.1 hint), the reason when {@code FAILED}, else empty.
 */
public record ConnectionState(Status status, String detail) {
    public enum Status {
        /** No broker is configured; local loopback still works. */
        DISABLED,
        /** The first connect attempt is in flight. */
        CONNECTING,
        CONNECTED,
        /** The connection is down and automatic reconnect is pending. */
        RECONNECTING,
        /** {@link MqttService#close()} ran; nothing reconnects any more. */
        STOPPED,
        /** The client could not be set up (e.g. an unreadable CA file); nothing will connect. */
        FAILED
    }

    public static final ConnectionState DISABLED = new ConnectionState(Status.DISABLED, "");
    public static final ConnectionState CONNECTING = new ConnectionState(Status.CONNECTING, "");
    public static final ConnectionState CONNECTED = new ConnectionState(Status.CONNECTED, "");
    public static final ConnectionState STOPPED = new ConnectionState(Status.STOPPED, "");

    public ConnectionState {
        if (status == null) throw new IllegalArgumentException("Connection status must be set");
        if (detail == null) detail = "";
    }

    public static ConnectionState reconnecting(String cause) {
        return new ConnectionState(Status.RECONNECTING, cause);
    }

    public static ConnectionState failed(String reason) {
        return new ConnectionState(Status.FAILED, reason);
    }

    public boolean isConnected() {
        return status == Status.CONNECTED;
    }

    @Override
    public String toString() {
        return detail.isEmpty() ? status.name() : status.name() + " (" + detail + ")";
    }
}

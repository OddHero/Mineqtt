package art.rehra.mineqtt.mqtt.core;

import java.nio.file.Path;

/**
 * Everything about the broker connection that is not part of the broker URI. {@code username}, {@code password} and
 * {@code caFile} may be null. {@link #toString()} never reveals the credentials.
 *
 * @param clientId           MQTT client identifier, {@code mineqtt_<bridgeId>}
 * @param caFile             PEM file with the CA certificates to trust instead of the JVM truststore (TLS only)
 * @param insecureSkipVerify accept any server certificate and host name (TLS only); logged loudly on every connect
 * @param keepAliveSec       MQTT keep alive in seconds, 0 disables it
 * @param maxInboundBytes    inbound messages with a larger payload are dropped before they are queued. On MQTT 5 it
 *                           also bounds the packets the broker may send at all; MQTT 3.1.1 relies on the broker's
 *                           message size limit for that.
 */
public record BrokerSettings(String clientId, String username, char[] password, Path caFile, boolean insecureSkipVerify,
                             int keepAliveSec, ProtocolVersion protocol, int maxInboundBytes) {
    public static final int DEFAULT_KEEP_ALIVE_SEC = 60;
    /**
     * The largest per-subscription cap: the Home Assistant catalog's 256 KiB, so a domain list over the parser's 64 KiB
     * reaches it and is refused visibly. Every subscription sets its own, smaller cap (mirror sources 64 KiB, commands
     * 16 KiB), which the service applies before any handler sees a message.
     */
    public static final int DEFAULT_MAX_INBOUND_BYTES = 256 * 1024;

    public BrokerSettings {
        if (clientId == null || clientId.isBlank()) throw new IllegalArgumentException("MQTT client id must not be blank");
        if (keepAliveSec < 0 || keepAliveSec > 65_535) throw new IllegalArgumentException("Keep alive must be 0-65535 seconds: " + keepAliveSec);
        if (protocol == null) throw new IllegalArgumentException("MQTT protocol must be set");
        if (maxInboundBytes <= 0) throw new IllegalArgumentException("Inbound payload limit must be positive: " + maxInboundBytes);
    }

    /** Anonymous, JVM truststore, default keep alive and payload limit. */
    public static BrokerSettings defaults(String clientId, ProtocolVersion protocol) {
        return new BrokerSettings(clientId, null, null, null, false, DEFAULT_KEEP_ALIVE_SEC, protocol, DEFAULT_MAX_INBOUND_BYTES);
    }

    @Override
    public String toString() {
        return "BrokerSettings[clientId=" + clientId + ", username=" + (username == null ? "none" : "***")
                + ", password=" + (password == null ? "none" : "***") + ", caFile=" + caFile
                + ", insecureSkipVerify=" + insecureSkipVerify + ", keepAliveSec=" + keepAliveSec
                + ", protocol=" + protocol.configValue() + ", maxInboundBytes=" + maxInboundBytes + "]";
    }
}

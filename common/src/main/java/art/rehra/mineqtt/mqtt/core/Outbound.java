package art.rehra.mineqtt.mqtt.core;

import com.google.gson.JsonElement;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A message to publish. The payload array belongs to the message once it is constructed and must not be changed.
 *
 * @param origin         the publishing device, which the local loopback never delivers back to; may be null
 * @param refresh        a {@code STATE} message that is sent even when its bytes equal the last ones released on its
 *                       topic: a republish for a peer that may have lost them (Home Assistant's birth)
 * @param onAcknowledged runs on the owner thread, during a drain, once the broker acknowledged exactly this message
 *                       (its PUBACK at QoS 1). It never runs for a message that is not sent: coalesced into a newer
 *                       state, deduplicated, dropped, without a broker, or still waiting when the service closes. May
 *                       be null.
 */
public record Outbound(String topic, byte[] payload, int qos, boolean retain, OfflinePolicy offline,
                       Delivery delivery, UUID origin, boolean refresh, Runnable onAcknowledged) {
    public enum Delivery {
        /** Coalesced to the latest per topic and skipped when its bytes equal the last ones published. */
        STATE,
        /** Never coalesced, so every edge survives; rate-capped per origin. */
        EDGE
    }

    public Outbound {
        if (!TopicFilters.isValidTopic(topic)) throw new IllegalArgumentException("Invalid publish topic: " + topic);
        if (payload == null) throw new IllegalArgumentException("Payload must not be null, use an empty array");
        if (qos < 0 || qos > 2) throw new IllegalArgumentException("QoS must be 0, 1 or 2: " + qos);
        if (offline == null || delivery == null) throw new IllegalArgumentException("Offline policy and delivery must be set");
    }

    /** A message that is no refresh and has no acknowledgement listener. */
    public Outbound(String topic, byte[] payload, int qos, boolean retain, OfflinePolicy offline, Delivery delivery, UUID origin) {
        this(topic, payload, qos, retain, offline, delivery, origin, false, null);
    }

    /** Retained device, group or bridge state. */
    public static Outbound state(String topic, JsonElement json) {
        return new Outbound(topic, utf8(json), 1, true, OfflinePolicy.COALESCE, Delivery.STATE, null);
    }

    /** Retained text state, e.g. availability {@code online}/{@code offline}. */
    public static Outbound state(String topic, String text) {
        return new Outbound(topic, text.getBytes(StandardCharsets.UTF_8), 1, true, OfflinePolicy.COALESCE, Delivery.STATE, null);
    }

    /** Deletes a retained message; kept through disconnects so ghosts never survive. */
    public static Outbound clear(String topic) {
        return new Outbound(topic, new byte[0], 1, true, OfflinePolicy.KEEP, Delivery.STATE, null);
    }

    public static Outbound event(String topic, JsonElement json) {
        return new Outbound(topic, utf8(json), 1, false, OfflinePolicy.DROP, Delivery.EDGE, null);
    }

    /** A raw edge that is not retained: dropped while offline, since a late pulse would be wrong. */
    public static Outbound raw(String topic, byte[] payload, int qos, UUID origin) {
        return new Outbound(topic, payload, qos, false, OfflinePolicy.DROP, Delivery.EDGE, origin);
    }

    /**
     * A retained raw edge, the current level of wireless redstone: every edge is sent, and the latest level waits while
     * its origin is over the edge cap or the broker is unreachable, so the broker ends up holding it.
     */
    public static Outbound rawRetained(String topic, byte[] payload, int qos, UUID origin) {
        return new Outbound(topic, payload, qos, true, OfflinePolicy.COALESCE, Delivery.EDGE, origin);
    }

    /**
     * The last retained raw message an output leaves on a topic (its off payload, or a clear): nothing publishes there
     * again to correct it, so like {@link #clear} it is never evicted from the outbox.
     */
    public static Outbound rawRetainedFinal(String topic, byte[] payload, int qos, UUID origin) {
        return new Outbound(topic, payload, qos, true, OfflinePolicy.KEEP, Delivery.EDGE, origin);
    }

    /** This message as a {@link #refresh()}: it is sent even when the broker got the same bytes before. */
    public Outbound asRefresh() {
        return new Outbound(topic, payload, qos, retain, offline, delivery, origin, true, onAcknowledged);
    }

    /** This message with {@code listener} as its {@link #onAcknowledged()}. */
    public Outbound whenAcknowledged(Runnable listener) {
        return new Outbound(topic, payload, qos, retain, offline, delivery, origin, refresh, listener);
    }

    public String text() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static byte[] utf8(JsonElement json) {
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }
}

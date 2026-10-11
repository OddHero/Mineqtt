package art.rehra.mineqtt.flow.core;

import com.google.gson.JsonElement;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A message on the {@link MessageBus} (M2 plan §3.1), Node-RED's {@code msg}. Only the bus makes them, and nothing
 * changes their payload.
 *
 * @param msgId    fresh per emission; a function node will copy it, as Node-RED's {@code _msgid}
 * @param kind     a value (last-value semantics) or an event (an edge, never deduplicated)
 * @param payload  typed by the source port; at most {@link #MAX_PAYLOAD_BYTES} serialized and {@link #MAX_DEPTH} deep
 * @param topic    Node-RED's {@code msg.topic}: the source port key, or the MQTT topic for a Raw Link input; null for none
 * @param source   the port that sent it
 * @param hops     the wires it travelled over; it travels in the message, never down a device's command path
 * @param root     what caused the chain it belongs to (M3 plan D10); it travels with the message as its hops do, and is
 *                 never persisted
 * @param gameTime when it was emitted, in game ticks (ordering and diagnostics)
 */
public record FlowMessage(UUID msgId, Kind kind, JsonElement payload, String topic, Endpoint source, int hops, Root root, long gameTime) {
    /** 16 KiB of UTF-8 JSON. */
    public static final int MAX_PAYLOAD_BYTES = 16 * 1024;
    /** Objects and arrays nested at most this deep. */
    public static final int MAX_DEPTH = 16;

    public enum Kind {
        /** A port's new value; a wire passes it on only when it differs from the one before. */
        VALUE,
        /** Something happened (a press, a pulse); every one is passed on. */
        EVENT
    }

    public FlowMessage {
        Objects.requireNonNull(msgId, "msgId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(root, "root");
        if (hops < 0) throw new IllegalArgumentException("Hops must not be negative: " + hops);
    }

    /** Why {@code payload} may not travel on the bus, or empty when it may: it is nested too deep, or too large. */
    public static Optional<String> payloadProblem(JsonElement payload) {
        // Depth first: it is bounded, while serializing an absurdly deep value is not.
        if (deeperThan(payload, MAX_DEPTH)) return Optional.of("nested deeper than " + MAX_DEPTH);
        int bytes = payload.toString().getBytes(StandardCharsets.UTF_8).length;
        return bytes <= MAX_PAYLOAD_BYTES ? Optional.empty() : Optional.of(bytes + " bytes is larger than " + MAX_PAYLOAD_BYTES);
    }

    /** This message as it arrives over one more wire, carrying {@code payload} as its target port takes it; its root stays. */
    FlowMessage traversed(JsonElement payload) {
        return new FlowMessage(msgId, kind, payload, topic, source, hops + 1, root, gameTime);
    }

    // Whether an object or array nests more than `levels` deep; it never descends further than that.
    private static boolean deeperThan(JsonElement value, int levels) {
        if (value.isJsonObject()) {
            if (levels == 0) return true;
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                if (deeperThan(entry.getValue(), levels - 1)) return true;
            }
        } else if (value.isJsonArray()) {
            if (levels == 0) return true;
            for (JsonElement element : value.getAsJsonArray()) {
                if (deeperThan(element, levels - 1)) return true;
            }
        }
        return false;
    }
}

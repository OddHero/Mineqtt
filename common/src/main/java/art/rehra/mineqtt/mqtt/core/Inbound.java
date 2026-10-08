package art.rehra.mineqtt.mqtt.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * A received message.
 *
 * @param retained whether the broker delivered it as a stored retained message
 * @param local    whether this server published it: a loopback copy or the broker's copy of an own publish
 * @param origin   the publishing device of a local message; null for foreign messages and origin-less publishes
 * @param seq      arrival number, increasing in queue order
 */
public record Inbound(String topic, byte[] payload, boolean retained, boolean local, UUID origin, long seq) {
    /** The payload as UTF-8, never the platform charset. */
    public String text() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    /** The payload parsed as (lenient) JSON; empty for an empty payload or on a parse failure. */
    public Optional<JsonElement> json() {
        if (payload.length == 0) return Optional.empty();
        try {
            return Optional.of(JsonParser.parseString(text()));
        } catch (JsonParseException e) {
            return Optional.empty();
        }
    }
}

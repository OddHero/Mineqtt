package art.rehra.mineqtt.twin.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * The state of a Home Assistant entity as statestream publishes it (M3 plan D1 #2). The {@code state} leaf is
 * {@code str(state)} in raw UTF-8, never JSON: {@code Turbo Mode}, {@code Grüße "quoted" / slash}, {@code 07:30:00},
 * {@code true} and {@code 007} are all text, so this never parses it (cap §7, §10.1; this corrects design §5.2, which
 * said "try JSON first"). Home Assistant's two placeholder states are values of their own, and a zero-length payload,
 * which is how statestream publishes an empty state (it deletes the retained topic, cap §6), is {@link Cleared}:
 * distinct from the text {@code ""}, which only the catalog can carry.
 */
public sealed interface StatestreamValue permits StatestreamValue.Text, StatestreamValue.Unknown, StatestreamValue.Unavailable,
        StatestreamValue.Cleared {
    /** Home Assistant's {@code unknown}. */
    Unknown UNKNOWN = new Unknown();
    /** Home Assistant's {@code unavailable}. */
    Unavailable UNAVAILABLE = new Unavailable();
    /** A zero-length state payload. */
    Cleared CLEARED = new Cleared();

    /** The payload of a {@code state} leaf: zero length is {@link Cleared}, anything else {@link #of(String)} of its UTF-8. */
    static StatestreamValue ofPayload(byte[] payload) {
        if (payload.length == 0) return CLEARED;
        return of(new String(payload, StandardCharsets.UTF_8));
    }

    /** A state string: {@code unknown} and {@code unavailable} (exactly, as Home Assistant writes them) or text. */
    static StatestreamValue of(String state) {
        return switch (state) {
            case "unknown" -> UNKNOWN;
            case "unavailable" -> UNAVAILABLE;
            default -> new Text(state);
        };
    }

    /** Whether this is a real value; attributes next to any other state are stale (D1 #6, cap §9.5). */
    default boolean known() {
        return this instanceof Text;
    }

    /** The text of a real value; empty for the placeholders and {@link Cleared}. */
    default Optional<String> text() {
        return this instanceof Text(String value) ? Optional.of(value) : Optional.empty();
    }

    /** The string Home Assistant had: the text, {@code unknown}, {@code unavailable}, or {@code ""} when cleared. */
    String wire();

    /** As the entity document carries it: a JSON string, or JSON null when cleared. */
    default JsonElement json() {
        return this instanceof Cleared ? JsonNull.INSTANCE : new JsonPrimitive(wire());
    }

    /** A real state; {@link #of(String)} maps {@code unknown} and {@code unavailable} to their own values instead. */
    record Text(String value) implements StatestreamValue {
        public Text {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String wire() {
            return value;
        }
    }

    record Unknown() implements StatestreamValue {
        @Override
        public String wire() {
            return "unknown";
        }
    }

    record Unavailable() implements StatestreamValue {
        @Override
        public String wire() {
            return "unavailable";
        }
    }

    record Cleared() implements StatestreamValue {
        @Override
        public String wire() {
            return "";
        }
    }
}

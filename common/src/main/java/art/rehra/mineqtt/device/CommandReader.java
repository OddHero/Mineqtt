package art.rehra.mineqtt.device;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Optional;
import java.util.Set;

/**
 * A partial command: only the keys it has change the device. Values are decoded through the fields' codecs, so they
 * arrive clamped and typed, and an unreadable value reads as absent.
 */
public final class CommandReader {
    private final JsonObject command;

    public CommandReader(JsonObject command) {
        this.command = command.deepCopy();
    }

    /**
     * The field's value, if the command sets it. An inline field is read from the whole command object, so its codec
     * decides which of its keys are optional.
     */
    public <T> Optional<T> get(Field<T> field) {
        if (!field.access().writable()) return Optional.empty();
        if (field.inline()) return field.parse(command);
        JsonElement value = command.get(field.key());
        return value == null ? Optional.empty() : field.parse(value);
    }

    public boolean has(String key) {
        return command.has(key);
    }

    /** A key that is no field, such as a switch's {@code state}, as text; empty for objects and arrays. */
    public Optional<String> text(String key) {
        JsonElement value = command.get(key);
        return value != null && value.isJsonPrimitive() ? Optional.of(value.getAsString()) : Optional.empty();
    }

    public Set<String> keys() {
        return command.keySet();
    }

    public JsonObject json() {
        return command.deepCopy();
    }
}

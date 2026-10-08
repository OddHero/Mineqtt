package art.rehra.mineqtt.device;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import java.util.Map;

/** Collects a device's state JSON, one readable field at a time, through the fields' codecs. */
public final class StateWriter {
    private final JsonObject json = new JsonObject();

    /**
     * @throws IllegalArgumentException for a write-only field or a value its codec cannot encode
     */
    public <T> StateWriter put(Field<T> field, T value) {
        if (!field.access().readable()) throw new IllegalArgumentException("Field " + field.key() + " is write-only");
        JsonElement encoded = field.codec().encodeStart(JsonOps.INSTANCE, value)
                .getOrThrow(error -> new IllegalArgumentException("Field " + field.key() + ": " + error));
        if (field.inline()) {
            for (Map.Entry<String, JsonElement> entry : encoded.getAsJsonObject().entrySet()) {
                json.add(entry.getKey(), entry.getValue());
            }
        } else {
            json.add(field.key(), encoded);
        }
        return this;
    }

    public JsonObject json() {
        return json;
    }
}

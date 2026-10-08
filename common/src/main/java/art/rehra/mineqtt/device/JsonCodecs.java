package art.rehra.mineqtt.device;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

/** Codecs for JSON kept in the registry file. */
final class JsonCodecs {
    /**
     * A JSON object stored as its text. Converting it to NBT tags instead would turn booleans into bytes and drop
     * nulls, and the registry publishes these objects again as they are.
     */
    static final Codec<JsonObject> OBJECT = Codec.STRING.comapFlatMap(JsonCodecs::parseObject, JsonObject::toString);

    private JsonCodecs() {
    }

    private static DataResult<JsonObject> parseObject(String text) {
        try {
            JsonElement json = JsonParser.parseString(text);
            return json.isJsonObject() ? DataResult.success(json.getAsJsonObject()) : DataResult.error(() -> "Not a JSON object: " + text);
        } catch (JsonParseException e) {
            return DataResult.error(() -> "Invalid JSON: " + e.getMessage());
        }
    }
}

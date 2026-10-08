package art.rehra.mineqtt.device;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.StringRepresentable;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A typed key of a device's state JSON and commands. The codec is a Mojang DFU codec, so the same field is written to
 * JSON for MQTT and to NBT for the block entity; it is lenient on input (§8.9: numbers are clamped, garbage is an
 * error, text is sanitised).
 *
 * @param inline whether the value's own keys sit at the top level of the state object instead of under {@code key}
 *               (the Home Assistant JSON light needs {@code state}, {@code brightness}, {@code color} side by side).
 *               The codec of an inline field encodes to an object.
 */
public record Field<T>(String key, Codec<T> codec, T defaultValue, Access access, boolean inline) {
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    public Field {
        if (!KEY.matcher(key).matches()) throw new IllegalArgumentException("Invalid field key: " + key);
        if (codec == null || defaultValue == null || access == null) throw new IllegalArgumentException("Field " + key + " is incomplete");
    }

    public static Field<Boolean> bool(String key, Access access) {
        return new Field<>(key, ValueCodecs.BOOL, false, access, false);
    }

    /** An integer clamped to {@code [min, max]}; defaults to {@code min}. */
    public static Field<Integer> intRange(String key, int min, int max, Access access) {
        return new Field<>(key, ValueCodecs.intRange(min, max), min, access, false);
    }

    /** One of an enum's serialized names; defaults to its first constant. */
    public static <E extends Enum<E> & StringRepresentable> Field<E> enumOf(String key, Class<E> type, Access access) {
        return new Field<>(key, ValueCodecs.enumOf(type), type.getEnumConstants()[0], access, false);
    }

    /** Free text, sanitised and capped at {@link ValueCodecs#MAX_TEXT_LENGTH}. */
    public static Field<String> text(String key, Access access) {
        return new Field<>(key, ValueCodecs.TEXT, "", access, false);
    }

    /** A value whose keys are written at the top level of the state object; see {@link #inline()}. */
    public static <T> Field<T> inline(String key, MapCodec<T> codec, T defaultValue, Access access) {
        return new Field<>(key, codec.codec(), defaultValue, access, true);
    }

    /** Decodes a JSON value from MQTT; empty when it is unreadable. */
    public Optional<T> parse(JsonElement json) {
        try {
            return codec.parse(JsonOps.INSTANCE, json).result();
        } catch (NumberFormatException e) {
            // Gson throws for a JSON number beyond its limits (1e999999999) when a codec reads it as a number.
            return Optional.empty();
        }
    }
}

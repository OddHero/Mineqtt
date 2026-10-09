package art.rehra.mineqtt.device;

import art.rehra.mineqtt.flow.core.Coerce;
import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.flow.core.PortType;
import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.StringRepresentable;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * A typed key of a device's state JSON and commands. The codec is a Mojang DFU codec, so the same field is written to
 * JSON for MQTT and to NBT for the block entity; it is lenient on input (§8.9: numbers are clamped, garbage is an
 * error, text is sanitised). The key is also the field's port id (M2 plan §3.1), frozen like the state JSON and the
 * {@code /set/<field>} topics: a field is never renamed, a new port is a new key.
 *
 * @param inline whether the value's own keys sit at the top level of the state object instead of under {@code key}
 *               (the Home Assistant JSON light needs {@code state}, {@code brightness}, {@code color} side by side).
 *               The codec of an inline field encodes to an object.
 * @param kind   what the field's port carries; it picks the GUI widget and the strict bounds of a {@link Setting}
 */
public record Field<T>(String key, Codec<T> codec, T defaultValue, Access access, boolean inline, PortType kind) {
    public Field {
        if (!Endpoint.isPortKey(key)) throw new IllegalArgumentException("Invalid field key: " + key);
        if (codec == null || defaultValue == null || access == null || kind == null) throw new IllegalArgumentException("Field " + key + " is incomplete");
    }

    public static Field<Boolean> bool(String key, Access access) {
        return new Field<>(key, ValueCodecs.BOOL, false, access, false, PortType.BOOL);
    }

    /** A redstone or light level 0–15; defaults to 0. */
    public static Field<Integer> level(String key, Access access) {
        return new Field<>(key, ValueCodecs.intRange(0, PortType.MAX_LEVEL), 0, access, false, PortType.LEVEL);
    }

    /** An integer clamped to {@code [min, max]}; defaults to {@code min}. */
    public static Field<Integer> intRange(String key, int min, int max, Access access) {
        return intRange(key, min, max, min, null, access);
    }

    /**
     * An integer clamped to {@code [min, max]}.
     *
     * @param unit what the number counts, e.g. {@code s}; null for none
     */
    public static Field<Integer> intRange(String key, int min, int max, int defaultValue, String unit, Access access) {
        return new Field<>(key, ValueCodecs.intRange(min, max), defaultValue, access, false, PortType.number(min, max, true, unit));
    }

    /**
     * A decimal number clamped to {@code [min, max]} and rounded to {@code decimals} places, e.g. a temperature.
     *
     * @param unit what the number measures, e.g. {@code °C}; null for none
     */
    public static Field<Double> decimal(String key, double min, double max, int decimals, double defaultValue, String unit, Access access) {
        return new Field<>(key, ValueCodecs.decimal(min, max, decimals), defaultValue, access, false, PortType.number(min, max, false, unit));
    }

    /** One of an enum's serialized names; defaults to its first constant. */
    public static <E extends Enum<E> & StringRepresentable> Field<E> enumOf(String key, Class<E> type, Access access) {
        return new Field<>(key, ValueCodecs.enumOf(type), type.getEnumConstants()[0], access, false, PortType.enumOf(names(type)));
    }

    /**
     * An on/off state written as one of an enum's two serialized names ({@code ON}/{@code OFF}) and read as any
     * {@code BOOL} value ({@link ValueCodecs#onOff}). Both names must be {@link Coerce#bool(String)} spellings; it
     * defaults to the first constant, which must be the "off" one.
     */
    public static <E extends Enum<E> & StringRepresentable> Field<E> onOff(String key, Class<E> type, Access access) {
        E[] constants = type.getEnumConstants();
        if (constants.length != 2 || names(type).stream().anyMatch(name -> Coerce.bool(name).isEmpty())
                || Coerce.bool(constants[0].getSerializedName()).orElse(true)) {
            throw new IllegalArgumentException("Field " + key + " needs an off and an on value");
        }
        return new Field<>(key, ValueCodecs.onOff(type), constants[0], access, false, PortType.BOOL);
    }

    /** Free text, sanitised and capped at {@link ValueCodecs#MAX_TEXT_LENGTH}. */
    public static Field<String> text(String key, Access access) {
        return new Field<>(key, ValueCodecs.TEXT, "", access, false, PortType.text(ValueCodecs.MAX_TEXT_LENGTH));
    }

    /** A structured value, e.g. a location; a {@code JSON} port. */
    public static <T> Field<T> json(String key, Codec<T> codec, T defaultValue, Access access) {
        return new Field<>(key, codec, defaultValue, access, false, PortType.JSON);
    }

    /** A value whose keys are written at the top level of the state object; see {@link #inline()}. */
    public static <T> Field<T> inline(String key, MapCodec<T> codec, T defaultValue, Access access, PortType kind) {
        return new Field<>(key, codec.codec(), defaultValue, access, true, kind);
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

    /** The value as the codec writes it to JSON, e.g. to check it against {@link #kind()}. */
    public JsonElement encode(T value) {
        return codec.encodeStart(JsonOps.INSTANCE, value).getOrThrow(error -> new IllegalArgumentException("Field " + key + ": " + error));
    }

    private static <E extends Enum<E> & StringRepresentable> List<String> names(Class<E> type) {
        return Arrays.stream(type.getEnumConstants()).map(StringRepresentable::getSerializedName).toList();
    }
}

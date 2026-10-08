package art.rehra.mineqtt.device;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Encoder;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.DyeColor;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Typed values from MQTT payloads, never NBT, block ids or commands (design §1, §8.9). Numbers are read and written
 * with {@link Locale#ROOT}, text is UTF-8.
 * <ul>
 *     <li>Lenient field codecs: a value may arrive as JSON, as a {@code /set/<field>} plain value or from NBT.</li>
 *     <li>The Raw Link grammars that turn a v1-style payload into a device command (§3.3).</li>
 *     <li>Colours, colour temperatures and the partial merge of Home Assistant JSON light commands.</li>
 * </ul>
 */
public final class ValueCodecs {
    /** The longest text value, in code points. */
    public static final int MAX_TEXT_LENGTH = 256;
    /** The colour temperature range of a Smart Lamp, as announced in its discovery. */
    public static final int MIN_KELVIN = 2000;
    public static final int MAX_KELVIN = 6500;
    // The mired range v1 lamps accepted from Home Assistant's color_temp.
    private static final int MIN_MIREDS = 153;
    private static final int MAX_MIREDS = 500;
    // A color_temp at or above this is in kelvin (Home Assistant with color_temp_kelvin), below it in mireds (v1).
    private static final int KELVIN_THRESHOLD = 1000;
    private static final int REDSTONE_MAX = 15;
    // The most digits, and the largest exponent either way, a number may have. Rounding 1e999999999 builds a
    // billion-digit integer and stalls the server thread; no field means a value that large or that small.
    private static final int MAX_DIGITS = 40;

    public static final Codec<Boolean> BOOL = lenient(Codec.BOOL, ValueCodecs::decodeBool);
    /** Free text: control characters and Minecraft formatting codes are stripped, the length is capped. */
    public static final Codec<String> TEXT = lenient(Codec.STRING, json -> scalarText(json).map(ValueCodecs::sanitizeText));

    /**
     * v1 raw payloads for a redstone level: custom payloads first, then {@code ON}/{@code TRUE}/{@code 1} (15),
     * {@code OFF}/{@code FALSE}/{@code 0} (0), {@code TOGGLE} and integers clamped to 0–15. Gives a {@code power}
     * or {@code state: TOGGLE} command.
     */
    public static final RawGrammar REDSTONE = (payload, custom) -> {
        if (payload.equals(custom.on())) return Optional.of(command("power", new JsonPrimitive(REDSTONE_MAX)));
        if (payload.equals(custom.off())) return Optional.of(command("power", new JsonPrimitive(0)));
        String text = payload.trim();
        if (text.equalsIgnoreCase("TOGGLE")) return Optional.of(command("state", new JsonPrimitive("TOGGLE")));
        Optional<Boolean> on = bool(text);
        if (on.isPresent()) return Optional.of(command("power", new JsonPrimitive(on.get() ? REDSTONE_MAX : 0)));
        return integer(text).map(value -> command("power", new JsonPrimitive(clamp(value, 0, REDSTONE_MAX))));
    };

    /**
     * Raw payloads of a lamp: custom payloads, a Home Assistant JSON light command, {@code ON}/{@code OFF}/
     * {@code TOGGLE} and their boolean spellings, or a colour ({@code #rrggbb}, {@code rgb(r,g,b)}, {@code r,g,b} or
     * a dye name), which also turns the lamp on as in v1.
     */
    public static final RawGrammar LIGHT = (payload, custom) -> {
        if (payload.equals(custom.on())) return Optional.of(command("state", new JsonPrimitive("ON")));
        if (payload.equals(custom.off())) return Optional.of(command("state", new JsonPrimitive("OFF")));
        String text = payload.trim();
        if (text.startsWith("{")) return strictJson(text).filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject);
        if (text.equalsIgnoreCase("TOGGLE")) return Optional.of(command("state", new JsonPrimitive("TOGGLE")));
        Optional<Boolean> on = bool(text);
        if (on.isPresent()) return Optional.of(command("state", new JsonPrimitive(on.get() ? "ON" : "OFF")));
        return color(text).map(rgb -> {
            JsonObject command = command("state", new JsonPrimitive("ON"));
            command.add("color", rgb.toJson());
            return command;
        });
    };

    private static final Pattern RGB_FUNCTION = Pattern.compile("rgb\\((.*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX_COLOR = Pattern.compile("#([0-9a-fA-F]{6})");
    // Control characters, and a section sign with the formatting code after it.
    private static final Pattern UNSAFE_TEXT = Pattern.compile("§.?|\\p{Cc}", Pattern.DOTALL);

    private ValueCodecs() {
    }

    /** Turns a Raw Link payload into a partial command for {@link DeviceHost#applyCommand}. */
    @FunctionalInterface
    public interface RawGrammar {
        /** @return the command, or empty when the payload means nothing to this grammar */
        Optional<JsonObject> command(String payload, CustomPayloads custom);
    }

    /** A Raw Link's own on/off payloads (v1 slots 2 and 3); they win over the grammar. Either may be null. */
    public record CustomPayloads(@Nullable String on, @Nullable String off) {
        public static final CustomPayloads NONE = new CustomPayloads(null, null);
    }

    /** An integer field: numbers and numeric strings are rounded and clamped to {@code [min, max]}; anything else is an error. */
    public static Codec<Integer> intRange(int min, int max) {
        if (min > max) throw new IllegalArgumentException("Empty range " + min + ".." + max);
        return lenient(Codec.INT, json -> decimal(json)
                .map(value -> DataResult.success(clamp(value.setScale(0, RoundingMode.HALF_UP), min, max)))
                .orElseGet(() -> DataResult.error(() -> "Not a number: " + json)));
    }

    /** An enum field, written as its serialized name and read case-insensitively. */
    public static <E extends Enum<E> & StringRepresentable> Codec<E> enumOf(Class<E> type) {
        E[] constants = type.getEnumConstants();
        return lenient(Codec.STRING.comap(E::getSerializedName), json -> scalarText(json).flatMap(name -> {
            for (E constant : constants) {
                if (constant.getSerializedName().equalsIgnoreCase(name.trim())) return DataResult.success(constant);
            }
            return DataResult.error(() -> "Not one of the values of " + type.getSimpleName() + ": " + name);
        }));
    }

    /** {@code on}/{@code true}/{@code 1} or {@code off}/{@code false}/{@code 0}, case-insensitive. */
    public static Optional<Boolean> bool(String text) {
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "on", "true", "1" -> Optional.of(true);
            case "off", "false", "0" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    /**
     * A decimal number in {@link Locale#ROOT} notation ({@code 21.5}, never {@code 21,5}); no NaN or infinity, and
     * nothing with more than {@value #MAX_DIGITS} digits or an exponent beyond ±{@value #MAX_DIGITS}
     * ({@code 1e999999999}), which would be costly to round.
     */
    public static Optional<BigDecimal> number(String text) {
        try {
            BigDecimal value = new BigDecimal(text.trim());
            return value.precision() <= MAX_DIGITS && Math.abs(value.scale()) <= MAX_DIGITS ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** A whole {@link #number}; {@code 7.0} and garbage are not. */
    public static Optional<BigDecimal> integer(String text) {
        return number(text).filter(value -> value.stripTrailingZeros().scale() <= 0 && !text.contains("."));
    }

    /** A number with a fixed count of decimals, always in {@link Locale#ROOT} notation. */
    public static String format(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    public static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Text without control characters and formatting codes, at most {@link #MAX_TEXT_LENGTH} code points. */
    public static String sanitizeText(String text) {
        String clean = UNSAFE_TEXT.matcher(text).replaceAll("");
        return clean.codePointCount(0, clean.length()) <= MAX_TEXT_LENGTH ? clean : clean.substring(0, clean.offsetByCodePoints(0, MAX_TEXT_LENGTH));
    }

    /**
     * The value of a {@code /set/<field>} payload: {@code true}/{@code false}, a number, a JSON object or array, or
     * else the trimmed text as a string (e.g. {@code TOGGLE}).
     */
    public static JsonElement plainValue(String payload) {
        String text = payload.trim();
        if (text.equals("true") || text.equals("false")) return new JsonPrimitive(Boolean.parseBoolean(text));
        Optional<BigDecimal> number = number(text);
        if (number.isPresent()) return new JsonPrimitive(number.get());
        if (text.startsWith("{") || text.startsWith("[")) {
            Optional<JsonElement> json = strictJson(text);
            if (json.isPresent()) return json.get();
        }
        return new JsonPrimitive(text);
    }

    /** A colour as text: {@code #rrggbb}, {@code rgb(r,g,b)}, {@code r,g,b} or a dye colour name such as {@code light_blue}. */
    public static Optional<Rgb> color(String text) {
        String trimmed = text.trim();
        Matcher hex = HEX_COLOR.matcher(trimmed);
        if (hex.matches()) return Optional.of(Rgb.ofPacked(Integer.parseInt(hex.group(1), 16)));
        Matcher function = RGB_FUNCTION.matcher(trimmed);
        if (function.matches()) return rgbTriple(function.group(1));
        if (trimmed.contains(",")) return rgbTriple(trimmed);
        DyeColor dye = DyeColor.byName(trimmed.toLowerCase(Locale.ROOT).replace(' ', '_'), null);
        return dye == null ? Optional.empty() : Optional.of(Rgb.ofPacked(dye.getTextColor()));
    }

    /**
     * A colour in any of Home Assistant's forms: {@code {r,g,b}} (missing parts are 0), {@code [r,g,b]},
     * {@code {h,s}} (hue 0–360, saturation 0–100), CIE {@code {x,y}}, or text as in {@link #color(String)}.
     */
    public static Optional<Rgb> color(JsonElement json) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) return color(json.getAsString());
        if (json.isJsonArray()) {
            JsonArray array = json.getAsJsonArray();
            if (array.size() != 3) return Optional.empty();
            Optional<BigDecimal> r = decimal(array.get(0)), g = decimal(array.get(1)), b = decimal(array.get(2));
            return r.isPresent() && g.isPresent() && b.isPresent() ? Optional.of(new Rgb(round(r.get()), round(g.get()), round(b.get()))) : Optional.empty();
        }
        if (!json.isJsonObject()) return Optional.empty();
        JsonObject object = json.getAsJsonObject();
        if (object.has("r") || object.has("g") || object.has("b")) {
            Optional<Integer> r = component(object, "r"), g = component(object, "g"), b = component(object, "b");
            return r.isPresent() && g.isPresent() && b.isPresent() ? Optional.of(new Rgb(r.get(), g.get(), b.get())) : Optional.empty();
        }
        if (object.has("h") && object.has("s")) {
            Optional<BigDecimal> h = decimal(object.get("h")), s = decimal(object.get("s"));
            return h.isPresent() && s.isPresent() ? Optional.of(hsToRgb(h.get().doubleValue(), s.get().doubleValue())) : Optional.empty();
        }
        if (object.has("x") && object.has("y")) {
            Optional<BigDecimal> x = decimal(object.get("x")), y = decimal(object.get("y"));
            return x.isPresent() && y.isPresent() ? Optional.of(xyToRgb(x.get().doubleValue(), y.get().doubleValue())) : Optional.empty();
        }
        return Optional.empty();
    }

    /** A colour temperature from Home Assistant's {@code color_temp} in mireds, within the lamp's kelvin range. */
    public static int miredsToKelvin(double mireds) {
        double clamped = Math.clamp(mireds, MIN_MIREDS, MAX_MIREDS);
        return Math.clamp(Math.round(1_000_000 / clamped), MIN_KELVIN, MAX_KELVIN);
    }

    /** The colour of a black body at {@code kelvin} (Tanner Helland's approximation, as v1 used). */
    public static Rgb kelvinToRgb(int kelvin) {
        double temp = Math.clamp(kelvin, 1000, 40000) / 100.0;
        double r = temp <= 66 ? 255 : 329.698727446 * Math.pow(temp - 60, -0.1332047592);
        double g = temp <= 66 ? 99.4708025861 * Math.log(temp) - 161.1195681661 : 288.1221695283 * Math.pow(temp - 60, -0.0755148492);
        double b = temp >= 66 ? 255 : temp <= 19 ? 0 : 138.5177312231 * Math.log(temp - 10) - 305.0447927307;
        return new Rgb((int) Math.round(r), (int) Math.round(g), (int) Math.round(b));
    }

    /** An 8-bit colour; components are clamped to 0–255. */
    public record Rgb(int r, int g, int b) {
        public static final Rgb WHITE = new Rgb(255, 255, 255);

        public Rgb {
            r = Math.clamp(r, 0, 255);
            g = Math.clamp(g, 0, 255);
            b = Math.clamp(b, 0, 255);
        }

        public static Rgb ofPacked(int rgb) {
            return new Rgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        }

        public int packed() {
            return (r << 16) | (g << 8) | b;
        }

        /** {@code {"r":…,"g":…,"b":…}}, the form Home Assistant's JSON light uses. */
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("r", r);
            json.addProperty("g", g);
            json.addProperty("b", b);
            return json;
        }
    }

    /**
     * The state of a JSON-schema light.
     *
     * @param brightness 0–255
     * @param kelvin     the colour temperature when the light is in {@code color_temp} mode, else null ({@code rgb} mode)
     */
    public record LightState(boolean on, int brightness, Rgb color, @Nullable Integer kelvin) {
        public static final LightState DEFAULT = new LightState(false, 255, Rgb.WHITE, null);
        /** The keys a light's state has at the top level of its device's state JSON. */
        public static final List<String> KEYS = List.of("state", "brightness", "color_mode", "color", "color_temp", "color_temp_kelvin");
        /**
         * The light's keys at the top level of a state object: {@code state}, {@code brightness}, {@code color_mode},
         * {@code color} and, in {@code color_temp} mode, the temperature in kelvin as {@code color_temp} (what Home
         * Assistant reads from a light discovered with {@code color_temp_kelvin}) and as {@code color_temp_kelvin}.
         * Reading is as lenient as {@link #merge}: what is missing or unreadable keeps the default.
         */
        public static final MapCodec<LightState> MAP_CODEC = new MapCodec<>() {
            @Override
            public <T> Stream<T> keys(DynamicOps<T> ops) {
                return KEYS.stream().map(ops::createString);
            }

            @Override
            public <T> DataResult<LightState> decode(DynamicOps<T> ops, MapLike<T> input) {
                JsonObject json = new JsonObject();
                input.entries().forEach(entry -> ops.getStringValue(entry.getFirst())
                        .ifSuccess(key -> json.add(key, ops.convertTo(JsonOps.INSTANCE, entry.getSecond()))));
                return DataResult.success(DEFAULT.merge(json));
            }

            @Override
            public <T> RecordBuilder<T> encode(LightState light, DynamicOps<T> ops, RecordBuilder<T> builder) {
                builder.add("state", ops.createString(light.on() ? "ON" : "OFF"));
                builder.add("brightness", ops.createInt(light.brightness()));
                builder.add("color_mode", ops.createString(light.colorMode()));
                builder.add("color", JsonOps.INSTANCE.convertTo(ops, light.color().toJson()));
                if (light.kelvin() != null) {
                    builder.add("color_temp", ops.createInt(light.kelvin()));
                    builder.add("color_temp_kelvin", ops.createInt(light.kelvin()));
                }
                return builder;
            }
        };

        public LightState {
            brightness = Math.clamp(brightness, 0, 255);
            if (kelvin != null) kelvin = Math.clamp(kelvin, MIN_KELVIN, MAX_KELVIN);
        }

        /** {@code rgb} or {@code color_temp}, as Home Assistant's {@code color_mode}. */
        public String colorMode() {
            return kelvin == null ? "rgb" : "color_temp";
        }

        /**
         * Applies a partial Home Assistant JSON light command: {@code state} (ON, OFF, TOGGLE or a boolean),
         * {@code brightness}, {@code color} in any form of {@link #color(JsonElement)}, {@code color_temp_kelvin}
         * and {@code color_temp}. Home Assistant sends {@code color_temp} in kelvin to a light discovered with
         * {@code color_temp_kelvin}, v1 automations sent mireds: a value of {@value #KELVIN_THRESHOLD} or more is
         * kelvin, a smaller one mireds. Missing keys keep their value; unknown keys and unreadable values are ignored.
         * A colour switches to {@code rgb} mode, a temperature to {@code color_temp} mode.
         */
        public LightState merge(JsonObject command) {
            boolean on = this.on;
            int brightness = this.brightness;
            Rgb color = this.color;
            Integer kelvin = this.kelvin;
            JsonElement state = command.get("state");
            if (state != null && state.isJsonPrimitive()) {
                String text = state.getAsString();
                if (text.equalsIgnoreCase("TOGGLE")) on = !on;
                else if (state.getAsJsonPrimitive().isBoolean()) on = state.getAsBoolean();
                else if (text.equalsIgnoreCase("ON") || text.equalsIgnoreCase("OFF")) on = text.equalsIgnoreCase("ON");
            }
            Optional<BigDecimal> newBrightness = command.has("brightness") ? decimal(command.get("brightness")) : Optional.empty();
            if (newBrightness.isPresent()) brightness = clamp(newBrightness.get().setScale(0, RoundingMode.HALF_UP), 0, 255);
            Optional<Rgb> newColor = command.has("color") ? ValueCodecs.color(command.get("color")) : Optional.empty();
            if (newColor.isPresent()) {
                color = newColor.get();
                kelvin = null;
            }
            Optional<Integer> newKelvin = command.has("color_temp_kelvin")
                    ? decimal(command.get("color_temp_kelvin")).map(value -> clamp(value.setScale(0, RoundingMode.HALF_UP), MIN_KELVIN, MAX_KELVIN))
                    : command.has("color_temp") ? decimal(command.get("color_temp")).map(ValueCodecs::colorTemp) : Optional.empty();
            if (newKelvin.isPresent()) {
                kelvin = newKelvin.get();
                color = kelvinToRgb(kelvin);
            }
            return new LightState(on, brightness, color, kelvin);
        }
    }

    // color_temp in kelvin or in mireds, see LightState#merge.
    private static int colorTemp(BigDecimal value) {
        if (value.compareTo(BigDecimal.valueOf(KELVIN_THRESHOLD)) >= 0) return clamp(value.setScale(0, RoundingMode.HALF_UP), MIN_KELVIN, MAX_KELVIN);
        return miredsToKelvin(value.doubleValue());
    }

    private static <A> Codec<A> lenient(Encoder<A> encoder, Function<JsonElement, DataResult<A>> parser) {
        return Codec.of(encoder, new Decoder<>() {
            @Override
            public <T> DataResult<Pair<A, T>> decode(DynamicOps<T> ops, T input) {
                return parser.apply(ops.convertTo(JsonOps.INSTANCE, input)).map(value -> Pair.of(value, ops.empty()));
            }
        });
    }

    private static DataResult<Boolean> decodeBool(JsonElement json) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isBoolean()) return DataResult.success(json.getAsBoolean());
        Optional<Boolean> value = json.isJsonPrimitive() ? bool(json.getAsString()) : Optional.empty();
        return value.map(DataResult::success).orElseGet(() -> DataResult.error(() -> "Not a boolean: " + json));
    }

    // Strings, numbers and booleans as text; objects and arrays are not text.
    private static DataResult<String> scalarText(JsonElement json) {
        return json.isJsonPrimitive() ? DataResult.success(json.getAsString()) : DataResult.error(() -> "Not a text value: " + json);
    }

    // A JSON number, or a string holding one; never a boolean.
    private static Optional<BigDecimal> decimal(JsonElement json) {
        if (!json.isJsonPrimitive()) return Optional.empty();
        JsonPrimitive primitive = json.getAsJsonPrimitive();
        if (primitive.isNumber()) return number(primitive.getAsString());
        return primitive.isString() ? number(primitive.getAsString()) : Optional.empty();
    }

    private static Optional<Integer> component(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null ? Optional.of(0) : decimal(value).map(ValueCodecs::round);
    }

    private static Optional<Rgb> rgbTriple(String values) {
        String[] parts = values.split(",");
        if (parts.length != 3) return Optional.empty();
        Optional<BigDecimal> r = number(parts[0]), g = number(parts[1]), b = number(parts[2]);
        return r.isPresent() && g.isPresent() && b.isPresent() ? Optional.of(new Rgb(round(r.get()), round(g.get()), round(b.get()))) : Optional.empty();
    }

    private static Rgb hsToRgb(double hue, double saturation) {
        double h = ((hue % 360) + 360) % 360 / 60;
        double s = Math.clamp(saturation, 0, 100) / 100;
        double c = s;
        double x = c * (1 - Math.abs(h % 2 - 1));
        double m = 1 - c;
        double[] rgb = switch ((int) h) {
            case 0 -> new double[]{c, x, 0};
            case 1 -> new double[]{x, c, 0};
            case 2 -> new double[]{0, c, x};
            case 3 -> new double[]{0, x, c};
            case 4 -> new double[]{x, 0, c};
            default -> new double[]{c, 0, x};
        };
        return new Rgb((int) Math.round((rgb[0] + m) * 255), (int) Math.round((rgb[1] + m) * 255), (int) Math.round((rgb[2] + m) * 255));
    }

    // CIE 1931 xy at full brightness to sRGB (D65), as v1 converted it.
    private static Rgb xyToRgb(double x, double y) {
        double cx = Math.clamp(x, 0, 1);
        double cy = Math.clamp(y, 0.0001, 1);
        double luminance = 1.0;
        double bigX = luminance / cy * cx;
        double bigZ = luminance / cy * (1 - cx - cy);
        double r = bigX * 3.2406 - luminance * 1.5372 - bigZ * 0.4986;
        double g = -bigX * 0.9689 + luminance * 1.8758 + bigZ * 0.0415;
        double b = bigX * 0.0557 - luminance * 0.2040 + bigZ * 1.0570;
        double max = Math.max(1, Math.max(r, Math.max(g, b)));
        return new Rgb(gamma(r / max), gamma(g / max), gamma(b / max));
    }

    private static int gamma(double linear) {
        double value = Math.max(0, linear);
        double corrected = value > 0.0031308 ? 1.055 * Math.pow(value, 1 / 2.4) - 0.055 : 12.92 * value;
        return (int) Math.round(corrected * 255);
    }

    private static Optional<JsonElement> strictJson(String text) {
        try {
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setStrictness(Strictness.STRICT);
            JsonElement json = JsonParser.parseReader(reader);
            return reader.peek() == JsonToken.END_DOCUMENT ? Optional.of(json) : Optional.empty();
        } catch (JsonParseException | IOException e) {
            return Optional.empty();
        }
    }

    private static JsonObject command(String key, JsonElement value) {
        JsonObject command = new JsonObject();
        command.add(key, value);
        return command;
    }

    private static int round(BigDecimal value) {
        return clamp(value.setScale(0, RoundingMode.HALF_UP), Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    private static int clamp(BigDecimal value, int min, int max) {
        if (value.compareTo(BigDecimal.valueOf(min)) < 0) return min;
        if (value.compareTo(BigDecimal.valueOf(max)) > 0) return max;
        return value.intValue();
    }
}

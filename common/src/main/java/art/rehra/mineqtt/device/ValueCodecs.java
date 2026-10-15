package art.rehra.mineqtt.device;

import art.rehra.mineqtt.flow.core.Coerce;
import art.rehra.mineqtt.flow.core.Colors;
import art.rehra.mineqtt.flow.core.PortType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
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
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Typed values from MQTT payloads, never NBT, block ids or commands (design §1, §8.9). Numbers are read and written
 * with {@link Locale#ROOT}, text is UTF-8.
 * <ul>
 *     <li>Lenient field codecs: a value may arrive as JSON, as a {@code /set/<field>} plain value or from NBT.</li>
 *     <li>The Raw Link grammars that turn a v1-style payload into a device command (§3.3).</li>
 *     <li>Colours, colour temperatures, effects and the partial merge of Home Assistant JSON light commands, and the
 *     fade of their {@code transition}.</li>
 * </ul>
 */
public final class ValueCodecs {
    /** The longest text value, in code points. */
    public static final int MAX_TEXT_LENGTH = 256;
    /** The colour temperature range of a Smart Lamp, as announced in its discovery. */
    public static final int MIN_KELVIN = 2000;
    public static final int MAX_KELVIN = 6500;
    /** A light's brightness, the kind of {@code brightness} in a light command. */
    public static final PortType.Number BRIGHTNESS = PortType.integer(0, 255);
    /** A Smart Lamp's colour temperature in kelvin, the kind of {@code color_temp_kelvin} in a light command. */
    public static final PortType.Number KELVIN = PortType.number(MIN_KELVIN, MAX_KELVIN, true, "K");
    /** The longest fade a light command may ask for with {@code transition}, in seconds. */
    public static final int MAX_TRANSITION_SECONDS = 3600;
    /** A light command's {@code transition} in seconds; Home Assistant sends fractions such as {@code 0.5}. */
    public static final PortType.Number TRANSITION = PortType.number(0, MAX_TRANSITION_SECONDS, false, "s");
    /** A light command's {@code effect}: the names of {@link LightEffect}. */
    public static final PortType.Enum EFFECT = PortType.enumOf(LightEffect.names());
    private static final int TICKS_PER_SECOND = 20;
    // The mired range v1 lamps accepted from Home Assistant's color_temp.
    private static final int MIN_MIREDS = 153;
    private static final int MAX_MIREDS = 500;
    // A color_temp at or above this is in kelvin (Home Assistant with color_temp_kelvin), below it in mireds (v1).
    private static final int KELVIN_THRESHOLD = 1000;
    private static final int REDSTONE_MAX = 15;
    private static final String TOGGLE = Coerce.TOGGLE;
    private static final String PLACE = "place";
    private static final String CLEAR = "clear";

    public static final Codec<Boolean> BOOL = lenient(Codec.BOOL, ValueCodecs::decodeBool);
    /** Free text: control characters and Minecraft formatting codes are stripped, the length is capped. */
    public static final Codec<String> TEXT = lenient(Codec.STRING, json -> scalarText(json).map(ValueCodecs::sanitizeText));

    /**
     * Free text that keeps its line breaks ({@link #sanitizeLines}), capped at {@code maxLength} code points, e.g. a
     * Display Panel's message, which a Home Assistant {@code text} entity caps at 255.
     *
     * @throws IllegalArgumentException for a cap that is not positive or above {@link #MAX_TEXT_LENGTH}
     */
    public static Codec<String> lines(int maxLength) {
        if (maxLength <= 0 || maxLength > MAX_TEXT_LENGTH) throw new IllegalArgumentException("Text is capped at 1.." + MAX_TEXT_LENGTH + ", not " + maxLength);
        return lenient(Codec.STRING, json -> scalarText(json).map(text -> sanitizeLines(text, maxLength)));
    }

    /**
     * v1 raw payloads for a redstone level: custom payloads first, then {@code ON}/{@code TRUE}/{@code 1} (15),
     * {@code OFF}/{@code FALSE}/{@code 0} (0), {@code TOGGLE} and integers clamped to 0–15. Gives a {@code power}
     * or {@code state: TOGGLE} command.
     */
    public static final RawGrammar REDSTONE = (payload, custom) -> {
        if (payload.isEmpty()) return Optional.empty();
        if (payload.equals(custom.on())) return Optional.of(command("power", new JsonPrimitive(REDSTONE_MAX)));
        if (payload.equals(custom.off())) return Optional.of(command("power", new JsonPrimitive(0)));
        String text = payload.trim();
        if (text.equalsIgnoreCase(TOGGLE)) return Optional.of(command("state", new JsonPrimitive(TOGGLE)));
        Optional<Boolean> on = bool(text);
        if (on.isPresent()) return Optional.of(command("power", new JsonPrimitive(on.get() ? REDSTONE_MAX : 0)));
        return integer(text).map(value -> command("power", new JsonPrimitive(clamp(value, 0, REDSTONE_MAX))));
    };

    /**
     * v1 raw payloads of a plug: custom payloads first, then {@code ON}/{@code TRUE}/{@code 1}, {@code OFF}/
     * {@code FALSE}/{@code 0} and {@code TOGGLE}; any other payload, a level among them, means nothing. Gives a
     * {@code state} command.
     */
    public static final RawGrammar SWITCH = (payload, custom) -> {
        if (payload.isEmpty()) return Optional.empty();
        if (payload.equals(custom.on())) return Optional.of(command("state", new JsonPrimitive("ON")));
        if (payload.equals(custom.off())) return Optional.of(command("state", new JsonPrimitive("OFF")));
        String text = payload.trim();
        if (text.equalsIgnoreCase(TOGGLE)) return Optional.of(command("state", new JsonPrimitive(TOGGLE)));
        return bool(text).map(on -> command("state", new JsonPrimitive(on ? "ON" : "OFF")));
    };

    /**
     * Raw payloads of a lamp: custom payloads, a Home Assistant JSON light command, {@code ON}/{@code OFF}/
     * {@code TOGGLE} and their boolean spellings, or a colour ({@code #rrggbb}, {@code rgb(r,g,b)}, {@code r,g,b} or
     * a dye name), which also turns the lamp on as in v1.
     */
    public static final RawGrammar LIGHT = (payload, custom) -> {
        if (payload.isEmpty()) return Optional.empty();
        if (payload.equals(custom.on())) return Optional.of(command("state", new JsonPrimitive("ON")));
        if (payload.equals(custom.off())) return Optional.of(command("state", new JsonPrimitive("OFF")));
        String text = payload.trim();
        if (text.startsWith("{")) return strictJson(text).filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject);
        if (text.equalsIgnoreCase(TOGGLE)) return Optional.of(command("state", new JsonPrimitive(TOGGLE)));
        Optional<Boolean> on = bool(text);
        if (on.isPresent()) return Optional.of(command("state", new JsonPrimitive(on.get() ? "ON" : "OFF")));
        return color(text).map(rgb -> {
            JsonObject command = command("state", new JsonPrimitive("ON"));
            command.add("color", rgb.toJson());
            return command;
        });
    };

    /**
     * Raw payloads of a Block Placer (M4 plan D12): {@code place}, {@code ON} or another true spelling places the block it
     * is set to, {@code clear}, {@code OFF} or another false spelling clears the block it placed, and anything else is a
     * block to set and place at once: {@code minecraft:white_wool}, {@code minecraft:oak_stairs[facing=east]} or
     * {@code {"id":"minecraft:oak_stairs","properties":{"facing":"east"}}}. The grammar reads only the shape; the device
     * checks the block ({@link PlacerRules#parse}), so a payload with block entity data reaches it to be refused and
     * counted. Gives {@code place}, {@code clear} or {@code block} with {@code place}.
     */
    public static final RawGrammar PLACER = (payload, custom) -> {
        String text = payload.trim();
        if (text.isEmpty()) return Optional.empty();
        if (text.equalsIgnoreCase(PLACE)) return Optional.of(command(PLACE, new JsonPrimitive(Coerce.PRESS)));
        if (text.equalsIgnoreCase(CLEAR)) return Optional.of(command(CLEAR, new JsonPrimitive(Coerce.PRESS)));
        Optional<Boolean> on = bool(text);
        if (on.isPresent()) return Optional.of(command(on.get() ? PLACE : CLEAR, new JsonPrimitive(Coerce.PRESS)));
        JsonObject command = command("block", new JsonPrimitive(text));
        command.addProperty(PLACE, Coerce.PRESS);
        return Optional.of(command);
    };

    private ValueCodecs() {
    }

    /**
     * Turns a Raw Link payload into a partial command for {@link DeviceHost#applyCommand}. An empty payload clears a
     * retained level on the broker and commands nothing, also when a custom payload is empty.
     */
    @FunctionalInterface
    public interface RawGrammar {
        /** @return the command, or empty when the payload means nothing to this grammar */
        Optional<JsonObject> command(String payload, CustomPayloads custom);
    }

    /** A Raw Link's own on/off payloads (v1 slots 2 and 3); they win over the grammar. Either may be null. */
    public record CustomPayloads(@Nullable String on, @Nullable String off) {
        public static final CustomPayloads NONE = new CustomPayloads(null, null);

        /** Whether either payload toggles its receivers ({@link #isToggle}) instead of setting a level. */
        public boolean toggles() {
            return on != null && isToggle(on) || off != null && isToggle(off);
        }
    }

    /**
     * Whether a raw payload toggles the receiver instead of setting a level: {@code TOGGLE} (trimmed, any case) as the raw
     * grammars read it, or a JSON light command whose {@code state} is {@code TOGGLE}.
     */
    public static boolean isToggle(String payload) {
        String text = payload.trim();
        if (text.equalsIgnoreCase(TOGGLE)) return true;
        return text.startsWith("{") && strictJson(text).filter(JsonElement::isJsonObject)
                .map(json -> json.getAsJsonObject().get("state"))
                .filter(state -> state.isJsonPrimitive() && state.getAsString().equalsIgnoreCase(TOGGLE))
                .isPresent();
    }

    /** An integer field: numbers and numeric strings are rounded and clamped to {@code [min, max]}; anything else is an error. */
    public static Codec<Integer> intRange(int min, int max) {
        if (min > max) throw new IllegalArgumentException("Empty range " + min + ".." + max);
        return lenient(Codec.INT, json -> decimal(json)
                .map(value -> DataResult.success(clamp(value.setScale(0, RoundingMode.HALF_UP), min, max)))
                .orElseGet(() -> DataResult.error(() -> "Not a number: " + json)));
    }

    /**
     * A decimal field: numbers and numeric strings are clamped to {@code [min, max]} and rounded half up to
     * {@code decimals} places; anything else is an error.
     */
    public static Codec<Double> decimal(double min, double max, int decimals) {
        if (!(min <= max)) throw new IllegalArgumentException("Empty range " + min + ".." + max);
        return lenient(Codec.DOUBLE, json -> decimal(json)
                .map(value -> DataResult.success(Math.clamp(value.setScale(decimals, RoundingMode.HALF_UP).doubleValue(), min, max)))
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

    /**
     * An on/off enum of two constants, off first ({@link Field#onOff}): written as its serialized name, read as any
     * {@link Coerce#bool(JsonElement)} value, so it accepts all its {@code BOOL} kind does.
     */
    public static <E extends Enum<E> & StringRepresentable> Codec<E> onOff(Class<E> type) {
        E[] constants = type.getEnumConstants();
        return lenient(Codec.STRING.comap(E::getSerializedName), json -> Coerce.bool(json)
                .map(on -> DataResult.success(constants[on ? 1 : 0]))
                .orElseGet(() -> DataResult.error(() -> "Not on or off: " + json)));
    }

    /** {@code on}/{@code true}/{@code 1} or {@code off}/{@code false}/{@code 0}, case-insensitive. */
    public static Optional<Boolean> bool(String text) {
        return Coerce.bool(text);
    }

    /**
     * A decimal number in {@link Locale#ROOT} notation ({@code 21.5}, never {@code 21,5}); no NaN or infinity, and
     * nothing with a huge count of digits or exponent ({@code 1e999999999}), which would be costly to round
     * ({@link Coerce#number}).
     */
    public static Optional<BigDecimal> number(String text) {
        return Coerce.number(text);
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
        return Coerce.sanitize(text, MAX_TEXT_LENGTH);
    }

    /**
     * Text sanitised like {@link #sanitizeText} that keeps its lines: every line break ({@code \r\n}, {@code \r},
     * {@code \n} and the other Unicode ones) becomes one {@code \n}, tabs and other spacing control characters become a
     * space, so no two words run together, and the rest goes as {@link Coerce#sanitize} has it; at most
     * {@code maxLength} code points, line breaks included.
     */
    public static String sanitizeLines(String text, int maxLength) {
        String spaced = text.replaceAll("\\R", "\n").replaceAll("[\\t\\x0B\\f]", " ");
        List<String> lines = new ArrayList<>();
        for (String line : spaced.split("\n", -1)) lines.add(Coerce.sanitize(line, maxLength));
        String clean = String.join("\n", lines);
        return clean.codePointCount(0, clean.length()) <= maxLength ? clean : clean.substring(0, clean.offsetByCodePoints(0, maxLength));
    }

    /**
     * Command text without one surrounding pair of matching {@code "} or {@code '}: {@code "Living Room"} is
     * {@code Living Room}. A greedy command argument keeps the quotes a player types around a name. Quotes inside, an
     * unmatched quote and a lone quote character stay.
     */
    public static String unquote(String text) {
        if (text.length() < 2) return text;
        char first = text.charAt(0);
        return (first == '"' || first == '\'') && text.charAt(text.length() - 1) == first ? text.substring(1, text.length() - 1) : text;
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
        return rgb(Colors.parse(text));
    }

    /**
     * A colour in any of Home Assistant's forms: {@code {r,g,b}} (missing parts are 0), {@code [r,g,b]},
     * {@code {h,s}} (hue 0–360, saturation 0–100), CIE {@code {x,y}}, or text as in {@link #color(String)}.
     */
    public static Optional<Rgb> color(JsonElement json) {
        return rgb(Colors.parse(json));
    }

    /** A colour temperature from Home Assistant's {@code color_temp} in mireds, within the lamp's kelvin range. */
    public static int miredsToKelvin(double mireds) {
        double clamped = Math.clamp(mireds, MIN_MIREDS, MAX_MIREDS);
        return Math.clamp(Math.round(1_000_000 / clamped), MIN_KELVIN, MAX_KELVIN);
    }

    /**
     * The fade a light command asks for: its {@code transition} in seconds (a number or a numeric string, clamped to
     * {@value #MAX_TRANSITION_SECONDS}) as game ticks; 0, at once, when it has none or an unreadable one.
     */
    public static int transitionTicks(JsonObject command) {
        JsonElement transition = command.get("transition");
        if (transition == null) return 0;
        return decimal(transition)
                .map(seconds -> clamp(seconds.multiply(BigDecimal.valueOf(TICKS_PER_SECOND)).setScale(0, RoundingMode.HALF_UP), 0, MAX_TRANSITION_SECONDS * TICKS_PER_SECOND))
                .orElse(0);
    }

    /** The colour of a black body at {@code kelvin} (Tanner Helland's approximation, as v1 used). */
    public static Rgb kelvinToRgb(int kelvin) {
        return Rgb.ofPacked(Colors.kelvin(kelvin));
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
     * A light's effect, which the clients draw from the lamp's render data (design §4.4). Written as its serialized
     * name; {@link #NONE} is Home Assistant's {@code off}, which the effect list offers first, so a user can end an effect.
     */
    public enum LightEffect implements StringRepresentable {
        NONE("off"),
        /** The light breathes: it dims and brightens again, all lamps in step. */
        PULSE("pulse"),
        /** The light flickers like a flame, each lamp on its own. */
        CANDLE("candle");

        private final String name;

        LightEffect(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }

        /** Every serialized name, {@code off} first: Home Assistant's {@code effect_list}. */
        public static List<String> names() {
            return Stream.of(values()).map(LightEffect::getSerializedName).toList();
        }

        /** The effect of a name, without regard to case or spaces around it; {@code none} is {@code off}, too. */
        public static Optional<LightEffect> byName(String name) {
            String text = name.trim();
            if (text.equalsIgnoreCase("none")) return Optional.of(NONE);
            return Stream.of(values()).filter(effect -> effect.name.equalsIgnoreCase(text)).findFirst();
        }
    }

    /**
     * The state of a JSON-schema light.
     *
     * @param brightness 0–255
     * @param kelvin     the colour temperature when the light is in {@code color_temp} mode, else null ({@code rgb} mode)
     * @param effect     what the light shows on top of its colour and brightness
     */
    public record LightState(boolean on, int brightness, Rgb color, @Nullable Integer kelvin, LightEffect effect) {
        public static final LightState DEFAULT = new LightState(false, 255, Rgb.WHITE, null);
        /** The keys a light's state has at the top level of its device's state JSON. */
        public static final List<String> KEYS = List.of("state", "brightness", "color_mode", "color", "color_temp", "color_temp_kelvin", "effect");
        /**
         * The light's keys at the top level of a state object: {@code state}, {@code brightness}, {@code color_mode},
         * {@code color}, in {@code color_temp} mode the temperature in kelvin as {@code color_temp} (what Home
         * Assistant reads from a light discovered with {@code color_temp_kelvin}) and as {@code color_temp_kelvin}, and
         * {@code effect}, always written, since Home Assistant keeps the last effect it read while the key is missing.
         * Reading is as lenient as {@link #merge}: what is missing or unreadable keeps the default, so a light saved
         * before effects existed has none.
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
                builder.add("effect", ops.createString(light.effect().getSerializedName()));
                return builder;
            }
        };

        public LightState {
            brightness = Math.clamp(brightness, 0, 255);
            if (kelvin != null) kelvin = Math.clamp(kelvin, MIN_KELVIN, MAX_KELVIN);
            Objects.requireNonNull(effect, "effect");
        }

        /** A light without an effect. */
        public LightState(boolean on, int brightness, Rgb color, @Nullable Integer kelvin) {
            this(on, brightness, color, kelvin, LightEffect.NONE);
        }

        /** A placed copy of a lamp (M2 plan D14): its colour, brightness and effect stay, and it is off. */
        public LightState placed() {
            return new LightState(false, brightness, color, kelvin, effect);
        }

        /** {@code rgb} or {@code color_temp}, as Home Assistant's {@code color_mode}. */
        public String colorMode() {
            return kelvin == null ? "rgb" : "color_temp";
        }

        /**
         * Applies a partial Home Assistant JSON light command: {@code state} (ON, OFF, TOGGLE or a boolean),
         * {@code brightness}, {@code color} in any form of {@link #color(JsonElement)}, {@code color_temp_kelvin},
         * {@code color_temp} and {@code effect} ({@link LightEffect#byName}). Home Assistant sends {@code color_temp} in
         * kelvin to a light discovered with {@code color_temp_kelvin}, v1 automations sent mireds: a value of
         * {@value #KELVIN_THRESHOLD} or more is kelvin, a smaller one mireds. Missing keys keep their value; unknown keys
         * and unreadable values are ignored, and so is {@code transition}, which says how to get to the new state, not
         * what it is ({@link #transitionTicks}). A colour switches to {@code rgb} mode, a temperature to
         * {@code color_temp} mode.
         */
        public LightState merge(JsonObject command) {
            boolean on = this.on;
            int brightness = this.brightness;
            Rgb color = this.color;
            Integer kelvin = this.kelvin;
            LightEffect effect = this.effect;
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
            JsonElement newEffect = command.get("effect");
            if (newEffect != null && newEffect.isJsonPrimitive()) effect = LightEffect.byName(newEffect.getAsString()).orElse(effect);
            return new LightState(on, brightness, color, kelvin, effect);
        }

        /**
         * The light shown {@code fraction} (0–1) of the way from this one to {@code target}: the brightness and the colour
         * in between, and the target's effect from the start. A light that is off counts as brightness 0 in the other
         * end's colour, so switching on fades up in the new colour and switching off fades down in the old one; on the
         * way the light is on while its brightness is above 0. Between two colour temperatures the temperature moves,
         * else the colour. From a light that is off to one that stays off nothing fades: the target from the start.
         */
        public LightState towards(LightState target, double fraction) {
            if (fraction >= 1 || !on && !target.on) return target;
            double t = Math.max(0, fraction);
            int fromBrightness = on ? brightness : 0;
            int toBrightness = target.on ? target.brightness : 0;
            int shownBrightness = mix(fromBrightness, toBrightness, t);
            if (kelvin != null && target.kelvin != null && on && target.on) {
                int shownKelvin = mix(kelvin, target.kelvin, t);
                return new LightState(shownBrightness > 0, shownBrightness, kelvinToRgb(shownKelvin), shownKelvin, target.effect);
            }
            Rgb from = on ? color : target.color;
            Rgb to = target.on ? target.color : color;
            Rgb shownColor = new Rgb(mix(from.r(), to.r(), t), mix(from.g(), to.g(), t), mix(from.b(), to.b(), t));
            return new LightState(shownBrightness > 0, shownBrightness, shownColor, null, target.effect);
        }

        private static int mix(int from, int to, double t) {
            return (int) Math.round(from + (to - from) * t);
        }

        /**
         * Why a light command a player sent does not fit, or empty when it does. Stricter than {@link #merge}, which reads
         * Home Assistant leniently: only {@code state} ({@code ON}, {@code OFF}, {@code TOGGLE} or a boolean),
         * {@code brightness} 0–255, {@code color} as {@code {r,g,b}}, {@code color_temp_kelvin} within the lamp's range,
         * {@code effect} by its name and {@code transition} in seconds up to {@value #MAX_TRANSITION_SECONDS}, at least one
         * of them, and a colour or a temperature but not both. Nothing is clamped.
         */
        public static Optional<String> commandProblem(JsonObject command) {
            if (command.isEmpty()) return Optional.of("an empty light command");
            if (command.has("color") && command.has("color_temp_kelvin")) return Optional.of("a colour and a colour temperature at once");
            for (var entry : command.entrySet()) {
                JsonElement value = entry.getValue();
                Optional<String> problem = switch (entry.getKey()) {
                    // As merge reads it: NBT has no boolean, so a client sends the words.
                    case "state" -> value.isJsonPrimitive() && (value.getAsJsonPrimitive().isBoolean()
                            || Stream.of("ON", "OFF", Coerce.TOGGLE).anyMatch(value.getAsString()::equalsIgnoreCase))
                            ? Optional.empty() : Optional.of("not ON, OFF or TOGGLE: " + value);
                    case "brightness" -> BRIGHTNESS.problem(value);
                    case "color" -> PortType.COLOR.problem(value);
                    case "color_temp_kelvin" -> KELVIN.problem(value);
                    case "effect" -> EFFECT.problem(value);
                    case "transition" -> TRANSITION.problem(value);
                    default -> Optional.of("not a light key");
                };
                if (problem.isPresent()) return Optional.of(entry.getKey() + ": " + problem.get());
            }
            return Optional.empty();
        }
    }

    /**
     * A light on its way from what it showed to a new state over {@code ticks} game ticks from game time {@code start}
     * (a command's {@code transition}). Only the target is the light's state; the way there is what its block shows.
     */
    public record LightTransition(LightState from, LightState to, long start, int ticks) {
        public LightTransition {
            if (ticks <= 0) throw new IllegalArgumentException("A transition takes at least one tick: " + ticks);
        }

        /** What the light shows at game time {@code now}: {@link #from} at the start, {@link #to} from the end on. */
        public LightState at(long now) {
            return from.towards(to, (now - start) / (double) ticks);
        }

        public boolean done(long now) {
            return now - start >= ticks;
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
        return Coerce.numeric(json);
    }

    private static Optional<Rgb> rgb(OptionalInt packed) {
        return packed.isPresent() ? Optional.of(Rgb.ofPacked(packed.getAsInt())) : Optional.empty();
    }

    /** A strict JSON document, nothing after it; empty for anything else. */
    static Optional<JsonElement> strictJson(String text) {
        return art.rehra.mineqtt.twin.core.EntityDocuments.strictJson(text);
    }

    private static JsonObject command(String key, JsonElement value) {
        JsonObject command = new JsonObject();
        command.add(key, value);
        return command;
    }

    private static int clamp(BigDecimal value, int min, int max) {
        if (value.compareTo(BigDecimal.valueOf(min)) < 0) return min;
        if (value.compareTo(BigDecimal.valueOf(max)) > 0) return max;
        return value.intValue();
    }
}

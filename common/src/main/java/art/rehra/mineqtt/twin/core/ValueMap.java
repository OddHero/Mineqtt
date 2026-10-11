package art.rehra.mineqtt.twin.core;

import art.rehra.mineqtt.flow.core.Coerce;
import art.rehra.mineqtt.flow.core.Colors;
import art.rehra.mineqtt.flow.core.PortType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * How a value read by a {@link ValuePath} becomes the value of a device port (M3 plan D7, design §5.2): one map per port
 * kind, over the coercion helpers of {@link Coerce} and {@link Colors}. Every map is pure and total: a value it cannot
 * read gives empty, which means "no change" (the device holds its last value), never a default. NaN, infinities, huge
 * exponents and garbage are refused, Home Assistant's {@code unavailable} and {@code unknown} mean no value, and
 * {@code null} (a light's colour while it is off) keeps the last value instead of meaning black or zero. The one exception
 * is an entity that is off: Home Assistant writes {@code null} for the attributes an off light, fan or cover has no value
 * for (a light's {@code brightness}, cap §9.4), so while the document's state reads off ({@link #reportsOff}) a
 * {@link LevelByRange} gives level 0 and a {@link Text} the state itself, instead of holding the value from before.
 * <p>
 * Numbers are read and written in {@link Locale#ROOT} notation whatever the default locale. A map may read sibling
 * values from the whole document the path ran over ({@link #attribute}): the range of an {@code input_number}, the
 * options of an {@code input_select}, the unit of a sensor. Minecraft-free.
 */
public sealed interface ValueMap permits ValueMap.Bool, ValueMap.LevelByBool, ValueMap.LevelByRange, ValueMap.LevelByOption,
        ValueMap.Numeric, ValueMap.Color, ValueMap.Text, ValueMap.Timestamp, ValueMap.Light, ValueMap.LightSwitch {
    /** {@link Text#decimals} that keeps Home Assistant's own formatting and only shortens long float noise. */
    int AUTO_DECIMALS = -1;
    int MAX_DECIMALS = 3;
    /** The states Home Assistant writes for an entity it has no value for; every map refuses them. */
    String UNAVAILABLE = "unavailable";
    String UNKNOWN = "unknown";
    /** The years a timestamp may name; anything outside is refused ({@link #instant}). */
    int MIN_YEAR = 0;
    int MAX_YEAR = 9999;

    /** What this map produces, so a binding can check it against the device port. */
    PortType kind();

    /**
     * The port value for {@code value}, the result of the path; empty when the value means nothing to this map.
     *
     * @param document the whole document the path ran over, for sibling values; {@link JsonNull} for none
     */
    Optional<JsonElement> apply(JsonElement value, JsonElement document);

    /** {@link #apply(JsonElement, JsonElement)} without a document: only the map's own settings count. */
    default Optional<JsonElement> apply(JsonElement value) {
        return apply(value, JsonNull.INSTANCE);
    }

    /** On or off as a JSON boolean, through the {@link #bool} spellings; transitional states such as {@code opening} hold. */
    record Bool(boolean invert) implements ValueMap {
        @Override
        public PortType kind() {
            return PortType.BOOL;
        }

        @Override
        public Optional<JsonElement> apply(JsonElement value, JsonElement document) {
            return bool(value).map(on -> new JsonPrimitive(on != invert));
        }
    }

    /** A redstone level by on or off: 15 or 0 (swapped when inverted). */
    record LevelByBool(boolean invert) implements ValueMap {
        @Override
        public PortType kind() {
            return PortType.LEVEL;
        }

        @Override
        public Optional<JsonElement> apply(JsonElement value, JsonElement document) {
            return bool(value).map(on -> new JsonPrimitive(on != invert ? PortType.MAX_LEVEL : 0));
        }
    }

    /**
     * A redstone level by a number mapped linearly from {@code [min, max]} to 0–15, clamped and rounded half up.
     * <ul>
     *     <li>A bound left null comes from the document's {@code min}/{@code max} attribute (an {@code input_number}),
     *     else {@value #DEFAULT_MIN}–{@value #DEFAULT_MAX}, so an unbound {@code "7"} gives 7.</li>
     *     <li>A reversed range ({@code min > max}) maps descending: {@code min} is 0, {@code max} is 15.</li>
     *     <li>An equal range is a threshold: 15 from {@code min} up, 0 below.</li>
     *     <li>{@code invert} gives {@code 15 - level}.</li>
     *     <li>{@code null} while the document's state reads off ({@link #reportsOff}, a light's brightness while it is
     *     off) is level 0 (15 inverted); otherwise it holds.</li>
     * </ul>
     */
    record LevelByRange(BigDecimal min, BigDecimal max, boolean invert) implements ValueMap {
        public static final int DEFAULT_MIN = 0;
        public static final int DEFAULT_MAX = PortType.MAX_LEVEL;

        /** Both bounds from the document, or the defaults. */
        public static LevelByRange fromDocument(boolean invert) {
            return new LevelByRange(null, null, invert);
        }

        @Override
        public PortType kind() {
            return PortType.LEVEL;
        }

        @Override
        public Optional<JsonElement> apply(JsonElement value, JsonElement document) {
            if (value.isJsonNull()) return reportsOff(document) ? Optional.of(new JsonPrimitive(invert ? PortType.MAX_LEVEL : 0)) : Optional.empty();
            return number(value).map(number -> new JsonPrimitive(level(number, document)));
        }

        /** The level of {@code value} with this map's range over {@code document}. */
        public int level(BigDecimal value, JsonElement document) {
            BigDecimal low = low(document), high = high(document);
            int level;
            if (low.compareTo(high) == 0) {
                level = value.compareTo(low) >= 0 ? PortType.MAX_LEVEL : 0;
            } else {
                // Scaled before the division, so a value halfway between two levels (12.5 over 0..15) rounds up as it
                // should instead of landing a rounding error below the half.
                BigDecimal span = high.subtract(low);
                BigDecimal scaled = value.subtract(low).multiply(BigDecimal.valueOf(PortType.MAX_LEVEL)).divide(span, MathContext.DECIMAL64);
                BigDecimal max = BigDecimal.valueOf(PortType.MAX_LEVEL);
                level = scaled.max(BigDecimal.ZERO).min(max).setScale(0, RoundingMode.HALF_UP).intValue();
            }
            return invert ? PortType.MAX_LEVEL - level : level;
        }

        /**
         * The number a level stands for, the way back for a write ({@code input_number.set_value}): linear over the range,
         * snapped to the document's {@code step} from {@code min} when it has one, and kept within the range. An equal
         * range gives its bound.
         */
        public BigDecimal value(int level, JsonElement document) {
            int shown = Math.clamp(invert ? PortType.MAX_LEVEL - level : level, 0, PortType.MAX_LEVEL);
            BigDecimal low = low(document), high = high(document);
            BigDecimal span = high.subtract(low);
            BigDecimal value = low.add(span.multiply(BigDecimal.valueOf(shown)).divide(BigDecimal.valueOf(PortType.MAX_LEVEL), MathContext.DECIMAL64));
            Optional<BigDecimal> step = attributeNumber(document, "step").filter(s -> s.signum() > 0);
            if (step.isPresent()) {
                BigDecimal steps = value.subtract(low).divide(step.get(), MathContext.DECIMAL64).setScale(0, RoundingMode.HALF_UP);
                value = low.add(steps.multiply(step.get()));
            }
            BigDecimal bottom = low.min(high), top = low.max(high);
            return plain(value.max(bottom).min(top).round(MathContext.DECIMAL64));
        }

        private BigDecimal low(JsonElement document) {
            return min != null ? min : attributeNumber(document, "min").orElse(BigDecimal.valueOf(DEFAULT_MIN));
        }

        private BigDecimal high(JsonElement document) {
            return max != null ? max : attributeNumber(document, "max").orElse(BigDecimal.valueOf(DEFAULT_MAX));
        }
    }

    /**
     * A redstone level by the index of the current option, counted from 0 and clamped to 15: {@code Turbo Mode} in
     * {@code ["Off", "Eco", "Turbo Mode"]} is 2. Options left null come from the document's {@code options} attribute;
     * an exact match wins over one without regard to case, and a value that is no option holds.
     */
    record LevelByOption(List<String> options) implements ValueMap {
        public LevelByOption {
            if (options != null) options = List.copyOf(options);
        }

        @Override
        public PortType kind() {
            return PortType.LEVEL;
        }

        @Override
        public Optional<JsonElement> apply(JsonElement value, JsonElement document) {
            Optional<String> text = scalar(value);
            if (text.isEmpty()) return Optional.empty();
            List<String> list = options != null ? options : attributeStrings(document, "options");
            OptionalInt index = optionIndex(text.get(), list);
            return index.isPresent() ? Optional.of(new JsonPrimitive(Math.min(index.getAsInt(), PortType.MAX_LEVEL))) : Optional.empty();
        }

        /**
         * The option a level stands for, the way back for a write ({@code input_select.select_option}): the option at that
         * index, the last one for a level past the end. Empty when there are no options.
         */
        public Optional<String> option(int level, JsonElement document) {
            List<String> list = options != null ? options : attributeStrings(document, "options");
            if (list.isEmpty()) return Optional.empty();
            return Optional.of(list.get(Math.clamp(level, 0, list.size() - 1)));
        }
    }

    /** A number fitted to {@code kind}: clamped to its range, and rounded half up when it is integral ({@link Coerce}). */
    record Numeric(PortType.Number kind) implements ValueMap {
        // Any number a payload can carry, so Coerce only fits it to the target.
        private static final PortType.Number ANY = PortType.number(-Double.MAX_VALUE, Double.MAX_VALUE, false, null);

        @Override
        public Optional<JsonElement> apply(JsonElement value, JsonElement document) {
            return number(value).flatMap(number -> Coerce.coerce(new JsonPrimitive(number), ANY, kind, false));
        }
    }

    /** A colour as {@code {r,g,b}}, from any of the {@link #color} forms. */
    record Color() implements ValueMap {
        @Override
        public PortType kind() {
            return PortType.COLOR;
        }

        @Override
        public Optional<JsonElement> apply(JsonElement value, JsonElement document) {
            OptionalInt rgb = color(value);
            return rgb.isPresent() ? Optional.of(rgbJson(rgb.getAsInt())) : Optional.empty();
        }
    }

    /**
     * Text from a scalar, at most {@code maxLength} code points, sanitised.
     * <ul>
     *     <li>A number gets {@code decimals} places (0–{@value #MAX_DECIMALS}), or with {@link #AUTO_DECIMALS} keeps Home
     *     Assistant's own string ({@code 10.62}, {@code 50.0}, {@code 007}) unless it has an exponent or more than
     *     {@value #MAX_DECIMALS} decimals, which are rounded to two ({@code 33.333333333333336} is {@code 33.33}; below 1,
     *     three significant digits).</li>
     *     <li>A number gets the unit, {@code unit} or else the document's {@code unit_of_measurement}; {@code ""} means
     *     none. As Home Assistant shows it, {@code %} and {@code °} follow without a space ({@code 50%}), other units with
     *     one ({@code 21.4 °C}).</li>
     *     <li>A time of day {@code 07:30:00} is {@code 07:30}; other text stays as it is.</li>
     *     <li>{@code NaN}, {@code Infinity} and {@code -Infinity} (any case, also {@code inf}) are refused (D7).</li>
     *     <li>{@code null} while the document's state reads off ({@link #reportsOff}, a light's brightness while it is
     *     off) is that state ({@code off}); otherwise it holds.</li>
     * </ul>
     */
    record Text(int decimals, String unit, int maxLength) implements ValueMap {
        public Text {
            if (decimals < AUTO_DECIMALS || decimals > MAX_DECIMALS) throw new IllegalArgumentException("Decimals are auto or 0.." + MAX_DECIMALS + ", not " + decimals);
            if (maxLength <= 0) throw new IllegalArgumentException("Text needs a positive length");
        }

        @Override
        public PortType kind() {
            return PortType.text(maxLength);
        }

        @Override
        public Optional<JsonElement> apply(JsonElement value, JsonElement document) {
            // An attribute an off entity has no value for (a light's brightness) shows that it is off, not the old value.
            if (value.isJsonNull() && reportsOff(document)) value = document.getAsJsonObject().get("state");
            Optional<String> scalar = scalar(value);
            if (scalar.isEmpty()) return Optional.empty();
            String raw = scalar.get();
            // D7: NaN and the infinities are refused, never shown (json.dumps writes a float NaN attribute as NaN).
            if (nonFinite(raw)) return Optional.empty();
            Optional<String> time = timeOfDay(raw);
            Optional<BigDecimal> number = number(value);
            String text;
            if (time.isPresent()) {
                text = time.get();
            } else if (number.isPresent()) {
                String shownUnit = unit != null ? unit : attributeString(document, "unit_of_measurement").orElse("");
                text = withUnit(formatNumber(raw.strip(), number.get(), decimals), shownUnit);
            } else {
                text = raw;
            }
            return Optional.of(new JsonPrimitive(Coerce.sanitize(text, maxLength)));
        }
    }

    /**
     * A point in time as epoch milliseconds (a JSON number), from ISO-8601 with an offset, with or without a fraction
     * ({@code 2026-10-11T04:48:44+00:00}, {@code 2026-10-10T16:58:44.639470+00:00}); the client formats it. A time of day
     * ({@code 07:30:00}) becomes the text {@code 07:30}. Bare numbers are refused: Home Assistant's {@code timestamp}
     * attribute means seconds since midnight or since the epoch depending on the entity.
     */
    record Timestamp() implements ValueMap {
        @Override
        public PortType kind() {
            return PortType.JSON;
        }

        @Override
        public Optional<JsonElement> apply(JsonElement value, JsonElement document) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return Optional.empty();
            String text = value.getAsString();
            Optional<JsonElement> instant = instant(text).map(time -> new JsonPrimitive(time.toEpochMilli()));
            return instant.isPresent() ? instant : timeOfDay(text).map(JsonPrimitive::new);
        }
    }

    /**
     * A JSON-schema light command (the {@code LIGHT} kind a Smart Lamp's {@code light} port takes) from a light's state and
     * attributes: Home Assistant's entity document ({@code {"state", "attributes": {…}}}) or a flat payload such as
     * zigbee2mqtt's ({@code {"state":"ON","brightness":…,"color":{…}}}).
     * <ul>
     *     <li>Off is {@code {"state":"OFF"}} alone, so brightness and colour stay for the next on.</li>
     *     <li>On carries {@code brightness} (0 is shown as 1: Home Assistant reports a light on at brightness 0).</li>
     *     <li>In {@code color_mode} {@code color_temp} the colour is {@code color_temp_kelvin} (or zigbee2mqtt's
     *     {@code color_temp} in mireds); otherwise {@code rgb_color}, which Home Assistant always sends while a colour
     *     light is on whatever its mode, then {@code hs_color}, {@code xy_color}, a {@code color} object and last a
     *     {@code color_temp_kelvin}.</li>
     *     <li>Missing or {@code null} attributes are left out, so the lamp keeps what it had.</li>
     * </ul>
     */
    record Light() implements ValueMap {
        @Override
        public PortType kind() {
            return PortType.LIGHT;
        }

        @Override
        public Optional<JsonElement> apply(JsonElement value, JsonElement document) {
            if (!value.isJsonObject()) return Optional.empty();
            JsonObject source = value.getAsJsonObject();
            JsonElement state = source.get(ValuePath.STATE);
            Optional<Boolean> on = state == null ? Optional.empty() : bool(state);
            if (on.isEmpty()) return Optional.empty();
            JsonObject command = new JsonObject();
            command.addProperty("state", on.get() ? "ON" : "OFF");
            if (!on.get()) return Optional.of(command);
            JsonObject attributes = source.get(ValuePath.ATTRIBUTES) instanceof JsonObject nested ? nested : source;
            attributeNumber(attributes, "brightness")
                    .ifPresent(brightness -> command.addProperty("brightness", clampRound(brightness, 1, 255)));
            String mode = attributeString(attributes, "color_mode").orElse("");
            OptionalInt kelvin = mode.equals("color_temp") ? kelvin(attributes) : OptionalInt.empty();
            if (kelvin.isPresent()) {
                command.addProperty("color_temp_kelvin", kelvin.getAsInt());
                return Optional.of(command);
            }
            OptionalInt rgb = lightColor(attributes);
            if (rgb.isPresent()) {
                command.add("color", rgbJson(rgb.getAsInt()));
            } else {
                attributeNumber(attributes, "color_temp_kelvin").filter(k -> k.signum() > 0)
                        .ifPresent(k -> command.addProperty("color_temp_kelvin", clampRound(k, 1, Integer.MAX_VALUE)));
            }
            return Optional.of(command);
        }

        private static OptionalInt kelvin(JsonObject attributes) {
            Optional<BigDecimal> kelvin = attributeNumber(attributes, "color_temp_kelvin").filter(k -> k.signum() > 0);
            if (kelvin.isPresent()) return OptionalInt.of(clampRound(kelvin.get(), 1, Integer.MAX_VALUE));
            Optional<BigDecimal> mireds = attributeNumber(attributes, "color_temp").filter(m -> m.signum() > 0);
            return mireds.isPresent()
                    ? OptionalInt.of(clampRound(BigDecimal.valueOf(1_000_000).divide(mireds.get(), MathContext.DECIMAL64), 1, Integer.MAX_VALUE))
                    : OptionalInt.empty();
        }

        private static OptionalInt lightColor(JsonObject attributes) {
            JsonElement rgb = attributes.get("rgb_color");
            if (rgb != null && rgb.isJsonArray()) {
                OptionalInt color = Colors.parse(rgb);
                if (color.isPresent()) return color;
            }
            Optional<double[]> hs = pair(attributes.get("hs_color"));
            if (hs.isPresent()) return OptionalInt.of(Colors.hs(hs.get()[0], hs.get()[1]));
            Optional<double[]> xy = pair(attributes.get("xy_color"));
            if (xy.isPresent()) return OptionalInt.of(Colors.xy(xy.get()[0], xy.get()[1]));
            JsonElement color = attributes.get("color");
            return color != null && color.isJsonObject() ? Colors.parse(color) : OptionalInt.empty();
        }
    }

    /**
     * A JSON-schema light command from on or off alone (D5: a Smart Lamp that mirrors a {@code switch},
     * {@code input_boolean}, {@code fan} or {@code binary_sensor}): on is the light at full brightness in {@code rgb}
     * ({@code {"state":"ON","brightness":255,"color":{r,g,b}}}), off is {@code {"state":"OFF"}} alone. The {@link #bool}
     * spellings decide; a transitional state holds.
     *
     * @param rgb the colour an on light shows, {@code 0xRRGGBB}
     */
    record LightSwitch(int rgb) implements ValueMap {
        /** White, as a Smart Lamp shows a switch that is on. */
        public static final LightSwitch WHITE = new LightSwitch(0xFFFFFF);

        public LightSwitch {
            if ((rgb & ~0xFFFFFF) != 0) throw new IllegalArgumentException("Not a 0xRRGGBB colour: " + Integer.toHexString(rgb));
        }

        @Override
        public PortType kind() {
            return PortType.LIGHT;
        }

        @Override
        public Optional<JsonElement> apply(JsonElement value, JsonElement document) {
            return bool(value).map(on -> {
                JsonObject command = new JsonObject();
                command.addProperty("state", on ? "ON" : "OFF");
                if (on) {
                    command.addProperty("brightness", 255);
                    command.add("color", rgbJson(rgb));
                }
                return command;
            });
        }
    }

    // ---- shared readers, also for adapters (comparators, write-back) ----

    /**
     * On or off: a JSON boolean, the number 0 or 1, or a spelling (trimmed, any case). On is {@code on}, {@code true},
     * {@code 1}, {@code open}, {@code home}, {@code unlocked}, {@code detected}, {@code playing}; off is {@code off},
     * {@code false}, {@code 0}, {@code closed}, {@code not_home}, {@code locked}, {@code clear}, {@code idle},
     * {@code standby}. Anything else, transitional states ({@code opening}, {@code unlocking}) among them, is empty.
     */
    static Optional<Boolean> bool(JsonElement value) {
        if (!value.isJsonPrimitive()) return Optional.empty();
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isString()) return bool(primitive.getAsString());
        return Coerce.bool(value);
    }

    /**
     * Whether {@code document} (an entity document, {@code {"state", "attributes": {…}}}) has a state that reads off by
     * {@link #bool}: a light, switch or fan that is off, a closed cover. False for any other document, {@link JsonNull}
     * among them.
     */
    static boolean reportsOff(JsonElement document) {
        if (document == null || !document.isJsonObject()) return false;
        JsonElement state = document.getAsJsonObject().get("state");
        return state != null && bool(state).filter(on -> !on).isPresent();
    }

    /** {@link #bool(JsonElement)} for text. */
    static Optional<Boolean> bool(String text) {
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "on", "true", "1", "open", "home", "unlocked", "detected", "playing" -> Optional.of(true);
            case "off", "false", "0", "closed", "not_home", "locked", "clear", "idle", "standby" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    /**
     * A number from a JSON number or a {@link Locale#ROOT} string ({@code "21.5"}, never {@code "21,5"}); NaN, infinities,
     * huge exponents, booleans and garbage are empty ({@link Coerce#numeric}).
     */
    static Optional<BigDecimal> number(JsonElement value) {
        return Coerce.numeric(value);
    }

    /**
     * A colour as {@code 0xRRGGBB}: every {@link Colors#parse(JsonElement)} form, plus {@code [h,s]} (a two-element list,
     * Home Assistant's {@code hs_color}) and a colour temperature in kelvin as a bare number from 1000 to 40000.
     */
    static OptionalInt color(JsonElement value) {
        if (value.isJsonArray()) {
            Optional<double[]> hs = pair(value);
            if (hs.isPresent()) return OptionalInt.of(Colors.hs(hs.get()[0], hs.get()[1]));
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            return number(value).filter(k -> k.compareTo(BigDecimal.valueOf(1000)) >= 0 && k.compareTo(BigDecimal.valueOf(40000)) <= 0)
                    .map(k -> OptionalInt.of(Colors.kelvin(k.intValue())))
                    .orElse(OptionalInt.empty());
        }
        return Colors.parse(value);
    }

    /**
     * An ISO-8601 date and time with an offset, with or without a fraction of a second, in the years
     * {@value #MIN_YEAR}..{@value #MAX_YEAR}. ISO allows years up to ±999 999 999, which parse but overflow epoch
     * milliseconds ({@link Instant#toEpochMilli()} throws), so a message such as {@code +999999999-01-01T00:00:00Z} is
     * no point in time.
     */
    static Optional<Instant> instant(String text) {
        try {
            OffsetDateTime time = OffsetDateTime.parse(text.strip());
            if (time.getYear() < MIN_YEAR || time.getYear() > MAX_YEAR) return Optional.empty();
            return Optional.of(time.toInstant());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** A time of day {@code HH:mm[:ss[.fraction]]} as {@code HH:mm}: {@code 07:30:00} is {@code 07:30}. */
    static Optional<String> timeOfDay(String text) {
        try {
            // ISO_LOCAL_TIME: two-digit hours and minutes, optional seconds and fraction, nothing else.
            LocalTime time = LocalTime.parse(text.strip());
            return Optional.of(String.format(Locale.ROOT, "%02d:%02d", time.getHour(), time.getMinute()));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /**
     * {@code number} as text: {@code original} (its string form) with {@link #AUTO_DECIMALS} unless that has an
     * exponent or more than {@value #MAX_DECIMALS} decimals, else rounded half up to {@code decimals} places.
     */
    static String formatNumber(String original, BigDecimal number, int decimals) {
        if (decimals == AUTO_DECIMALS) {
            if (plainDecimal(original)) return original;
            BigDecimal rounded = number.abs().compareTo(BigDecimal.ONE) >= 0
                    ? number.setScale(2, RoundingMode.HALF_UP)
                    : number.round(new MathContext(3, RoundingMode.HALF_UP));
            return plain(rounded).toPlainString();
        }
        return number.setScale(decimals, RoundingMode.HALF_UP).toPlainString();
    }

    /** A value and its unit as Home Assistant shows them: {@code 50%}, {@code 90°}, {@code 21.4 °C}; no unit for {@code ""}. */
    static String withUnit(String value, String unit) {
        String trimmed = unit.strip();
        if (trimmed.isEmpty()) return value;
        return value + (trimmed.equals("%") || trimmed.equals("°") ? "" : " ") + trimmed;
    }

    /** The index of {@code value} in {@code options}: an exact match first, then one without regard to case. */
    static OptionalInt optionIndex(String value, List<String> options) {
        int index = options.indexOf(value);
        if (index < 0) {
            for (int i = 0; i < options.size() && index < 0; i++) {
                if (options.get(i).equalsIgnoreCase(value)) index = i;
            }
        }
        return index < 0 ? OptionalInt.empty() : OptionalInt.of(index);
    }

    /**
     * An attribute of a document: from its {@code attributes} object when it has one (a Home Assistant entity document),
     * else from the document itself (a flat MQTT payload). Empty when absent or {@code null}.
     */
    static Optional<JsonElement> attribute(JsonElement document, String key) {
        if (!document.isJsonObject()) return Optional.empty();
        JsonObject object = document.getAsJsonObject();
        JsonObject attributes = object.get(ValuePath.ATTRIBUTES) instanceof JsonObject nested ? nested : object;
        JsonElement value = attributes.get(key);
        return value == null || value.isJsonNull() ? Optional.empty() : Optional.of(value);
    }

    static Optional<BigDecimal> attributeNumber(JsonElement document, String key) {
        return attribute(document, key).flatMap(ValueMap::number);
    }

    static Optional<String> attributeString(JsonElement document, String key) {
        return attribute(document, key).filter(value -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()).map(JsonElement::getAsString);
    }

    /** A list attribute's string elements, in order; other elements are skipped. Empty when there is no such list. */
    static List<String> attributeStrings(JsonElement document, String key) {
        List<String> strings = new ArrayList<>();
        attribute(document, key).filter(JsonElement::isJsonArray).ifPresent(list -> {
            for (JsonElement element : list.getAsJsonArray()) {
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) strings.add(element.getAsString());
            }
        });
        return strings;
    }

    // NaN or an infinity as text, in any case and with an optional sign (Python's json.dumps and float() spellings).
    private static boolean nonFinite(String text) {
        String word = text.strip().toLowerCase(Locale.ROOT);
        if (word.startsWith("+") || word.startsWith("-")) word = word.substring(1);
        return word.equals("nan") || word.equals("inf") || word.equals("infinity");
    }

    // A scalar as text, Home Assistant's no-value states and null refused.
    private static Optional<String> scalar(JsonElement value) {
        if (!value.isJsonPrimitive()) return Optional.empty();
        String text = value.getAsString();
        return text.equals(UNAVAILABLE) || text.equals(UNKNOWN) ? Optional.empty() : Optional.of(text);
    }

    // Two numbers in a list: hs_color or xy_color.
    private static Optional<double[]> pair(JsonElement value) {
        if (value == null || !value.isJsonArray()) return Optional.empty();
        JsonArray array = value.getAsJsonArray();
        if (array.size() != 2) return Optional.empty();
        Optional<BigDecimal> a = number(array.get(0)), b = number(array.get(1));
        return a.isPresent() && b.isPresent() ? Optional.of(new double[]{a.get().doubleValue(), b.get().doubleValue()}) : Optional.empty();
    }

    private static JsonObject rgbJson(int rgb) {
        JsonObject json = new JsonObject();
        json.addProperty("r", rgb >> 16 & 0xFF);
        json.addProperty("g", rgb >> 8 & 0xFF);
        json.addProperty("b", rgb & 0xFF);
        return json;
    }

    private static int clampRound(BigDecimal value, int min, int max) {
        return value.max(BigDecimal.valueOf(min)).min(BigDecimal.valueOf(max)).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    // Without trailing zeros, and never in exponent notation for a whole number (100, not 1E+2).
    private static BigDecimal plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.UNNECESSARY) : stripped;
    }

    // HA's own number text that auto decimals keep: an optional sign, digits, and at most MAX_DECIMALS decimals; no exponent.
    private static boolean plainDecimal(String text) {
        int start = !text.isEmpty() && (text.charAt(0) == '-' || text.charAt(0) == '+') ? 1 : 0;
        int dot = text.indexOf('.');
        int end = dot < 0 ? text.length() : dot;
        if (end == start || !digits(text, start, end)) return false;
        if (dot < 0) return true;
        int decimals = text.length() - dot - 1;
        return decimals >= 1 && decimals <= MAX_DECIMALS && digits(text, dot + 1, text.length());
    }

    private static boolean digits(String text, int from, int to) {
        for (int i = from; i < to; i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') return false;
        }
        return true;
    }
}

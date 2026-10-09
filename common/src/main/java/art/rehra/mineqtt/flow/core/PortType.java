package art.rehra.mineqtt.flow.core;

import com.google.gson.JsonElement;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What a port carries (M2 plan §3.1): the kind of a device field, setting, action or event. The same tag picks the
 * GUI widget, bounds what a client may send and decides which wires coerce ({@link Coerce}). Minecraft-free, like all of
 * {@code flow.core}; values are Gson JSON, as in a device's state and commands.
 */
public sealed interface PortType permits PortType.Bool, PortType.Level, PortType.Number, PortType.Enum, PortType.Text,
        PortType.Color, PortType.Light, PortType.Json, PortType.Event {
    Bool BOOL = new Bool();
    Level LEVEL = new Level();
    Color COLOR = new Color();
    Light LIGHT = new Light();
    Json JSON = new Json();
    /** The highest redstone or light level. */
    int MAX_LEVEL = 15;

    /**
     * Why a value does not fit this kind, or empty when it does. Strict, unlike the lenient field codecs that read
     * Home Assistant's commands: a number out of range is refused instead of clamped, so a client sending one is
     * caught.
     */
    Optional<String> problem(JsonElement value);

    /** A short description for command output, e.g. {@code level} or {@code number 1..8 blocks}. */
    String describe();

    static Number number(double min, double max, boolean integral, String unit) {
        return new Number(min, max, integral, unit);
    }

    /** Whole numbers from {@code min} to {@code max}, without a unit. */
    static Number integer(long min, long max) {
        return new Number(min, max, true, null);
    }

    static Enum enumOf(List<String> values) {
        return new Enum(values);
    }

    static Text text(int maxLength) {
        return new Text(maxLength);
    }

    static Event event(String... types) {
        return new Event(List.of(types));
    }

    /** On or off: a JSON boolean, {@code 0}/{@code 1} (NBT bytes) or one of the {@link Coerce#bool} spellings. */
    record Bool() implements PortType {
        @Override
        public Optional<String> problem(JsonElement value) {
            return Coerce.bool(value).isPresent() ? Optional.empty() : Optional.of("not on or off: " + value);
        }

        @Override
        public String describe() {
            return "bool";
        }
    }

    /** A redstone or light level, a whole number 0–15. */
    record Level() implements PortType {
        @Override
        public Optional<String> problem(JsonElement value) {
            return Number.check(value, 0, MAX_LEVEL, true);
        }

        @Override
        public String describe() {
            return "level";
        }
    }

    /**
     * A number from {@code min} to {@code max}.
     *
     * @param integral whether only whole numbers fit
     * @param unit     e.g. {@code s} or {@code blocks}; null for none
     */
    record Number(double min, double max, boolean integral, String unit) implements PortType {
        public Number {
            if (!(min <= max)) throw new IllegalArgumentException("Empty number range " + min + ".." + max);
        }

        @Override
        public Optional<String> problem(JsonElement value) {
            return check(value, min, max, integral);
        }

        @Override
        public String describe() {
            return "number " + plain(min) + ".." + plain(max) + (unit == null ? "" : " " + unit);
        }

        static Optional<String> check(JsonElement value, double min, double max, boolean integral) {
            Optional<BigDecimal> number = Coerce.decimal(value);
            if (number.isEmpty()) return Optional.of("not a number: " + value);
            BigDecimal decimal = number.get();
            if (integral && decimal.stripTrailingZeros().scale() > 0) return Optional.of("not a whole number: " + value);
            if (decimal.compareTo(BigDecimal.valueOf(min)) < 0 || decimal.compareTo(BigDecimal.valueOf(max)) > 0) {
                return Optional.of(value + " is not within " + plain(min) + ".." + plain(max));
            }
            return Optional.empty();
        }

        private static String plain(double value) {
            return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value) : Double.toString(value);
        }
    }

    /** One of a fixed set of names, matched without regard to case. */
    record Enum(List<String> values) implements PortType {
        public Enum {
            values = List.copyOf(values);
            if (values.isEmpty()) throw new IllegalArgumentException("An enum needs values");
        }

        @Override
        public Optional<String> problem(JsonElement value) {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String name = value.getAsString();
                if (values.stream().anyMatch(candidate -> candidate.equalsIgnoreCase(name))) return Optional.empty();
            }
            return Optional.of("not one of " + String.join(", ", values) + ": " + value);
        }

        @Override
        public String describe() {
            return "enum " + String.join("|", values);
        }
    }

    /** A string of at most {@code maxLength} code points. */
    record Text(int maxLength) implements PortType {
        public Text {
            if (maxLength <= 0) throw new IllegalArgumentException("Text needs a positive length");
        }

        @Override
        public Optional<String> problem(JsonElement value) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return Optional.of("not text: " + value);
            String text = value.getAsString();
            int length = text.codePointCount(0, text.length());
            return length <= maxLength ? Optional.empty() : Optional.of("text of " + length + " characters is longer than " + maxLength);
        }

        @Override
        public String describe() {
            return "text " + maxLength;
        }
    }

    /** An 8-bit colour as Home Assistant's light writes it: {@code {"r":255,"g":128,"b":0}}. */
    record Color() implements PortType {
        @Override
        public Optional<String> problem(JsonElement value) {
            return Coerce.rgb(value).isPresent() ? Optional.empty() : Optional.of("not a colour {r,g,b} with 0-255: " + value);
        }

        @Override
        public String describe() {
            return "color";
        }
    }

    /**
     * A JSON-schema light command or state, its keys side by side ({@code state}, {@code brightness}, {@code color},
     * …); the device's codec reads the keys it knows.
     */
    record Light() implements PortType {
        @Override
        public Optional<String> problem(JsonElement value) {
            return value.isJsonObject() ? Optional.empty() : Optional.of("not a light object: " + value);
        }

        @Override
        public String describe() {
            return "light";
        }
    }

    /** Any JSON value, e.g. a location; the device's codec decides what it reads. */
    record Json() implements PortType {
        @Override
        public Optional<String> problem(JsonElement value) {
            return value.isJsonNull() ? Optional.of("null") : Optional.empty();
        }

        @Override
        public String describe() {
            return "json";
        }
    }

    /**
     * Events of the given types: an event object {@code {"event_type":"press",…}}, or its type alone.
     *
     * @param types the event types, e.g. {@code press} or {@code pulse}
     */
    record Event(List<String> types) implements PortType {
        private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9_]{0,63}");
        public static final String TYPE_KEY = "event_type";

        public Event {
            types = List.copyOf(types);
            if (types.isEmpty()) throw new IllegalArgumentException("An event port needs event types");
            for (String type : types) {
                if (!TYPE.matcher(type).matches()) throw new IllegalArgumentException("Invalid event type: " + type);
            }
        }

        @Override
        public Optional<String> problem(JsonElement value) {
            JsonElement type = value.isJsonObject() ? value.getAsJsonObject().get(TYPE_KEY) : value;
            if (type != null && type.isJsonPrimitive() && type.getAsJsonPrimitive().isString()
                    && types.contains(type.getAsString().toLowerCase(Locale.ROOT))) {
                return Optional.empty();
            }
            return Optional.of("not an event of type " + String.join(", ", types) + ": " + value);
        }

        @Override
        public String describe() {
            return "event " + String.join("|", types);
        }
    }
}

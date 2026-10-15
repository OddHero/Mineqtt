package art.rehra.mineqtt.flow.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * How a value of one {@link PortType} reaches a port of another (M2 plan §3.1), the one coercion table that wires and,
 * from M3, value maps use. {@link #compatible} decides when a wire is created, so an incompatible pair never reaches
 * {@link #coerce} at run time:
 * <ul>
 *     <li>the same kind: the value, fitted to the target (clamped, rounded, cut);</li>
 *     <li>{@code BOOL → LEVEL}: true is 15, false 0; {@code LEVEL → BOOL}: true when above 0;</li>
 *     <li>{@code NUMBER → LEVEL}: rounded and clamped;</li>
 *     <li>{@code EVENT → action}: fires it ({@code PRESS}); {@code EVENT → switch state}: {@code TOGGLE};</li>
 *     <li>{@code TEXT → BOOL}: the {@link #bool(String)} spellings; {@code COLOR → BOOL}: true when not black;</li>
 *     <li>anything {@code → TEXT}: its string form, sanitised.</li>
 * </ul>
 * Minecraft-free, so the bool spellings and the text sanitiser live here and the device codecs use them.
 */
public final class Coerce {
    /** What fires an action, as Home Assistant's buttons send it. */
    public static final String PRESS = "PRESS";
    /** What flips a switch state. */
    public static final String TOGGLE = "TOGGLE";
    // Control characters and Minecraft formatting codes (§ and the code after it).
    private static final Pattern UNSAFE_TEXT = Pattern.compile("§.?|\\p{Cc}", Pattern.DOTALL);
    private static final int MAX_CHANNEL = 255;
    // The most digits, and the largest exponent either way, a number read from text may have. Rounding 1e999999999
    // builds a billion-digit integer and stalls the server thread; no value means a number that large or that small.
    private static final int MAX_DIGITS = 40;

    private Coerce() {
    }

    /**
     * Whether a wire from a port of kind {@code from} may end at one of kind {@code to}.
     *
     * @param toggles whether the target is a switch state that an event toggles ({@code state} actions)
     */
    public static boolean compatible(PortType from, PortType to, boolean toggles) {
        return switch (to) {
            case PortType.Text ignoredText -> !(from instanceof PortType.Event);
            case PortType.Bool ignoredBool -> from instanceof PortType.Bool || from instanceof PortType.Level || from instanceof PortType.Text
                    || from instanceof PortType.Color || from instanceof PortType.Event && toggles;
            case PortType.Level ignoredLevel -> from instanceof PortType.Bool || from instanceof PortType.Level || from instanceof PortType.Number;
            case PortType.Number ignoredNumber -> from instanceof PortType.Number;
            case PortType.Enum target -> from instanceof PortType.Enum source
                    && source.values().stream().allMatch(value -> target.values().stream().anyMatch(value::equalsIgnoreCase));
            case PortType.Color ignoredColor -> from instanceof PortType.Color;
            case PortType.Light ignoredLight -> from instanceof PortType.Light;
            case PortType.Json ignoredJson -> from instanceof PortType.Json;
            case PortType.Event ignoredEvent -> from instanceof PortType.Event;
        };
    }

    /**
     * {@code value}, sent by a port of kind {@code from}, as a port of kind {@code to} takes it; empty when the pair is
     * not {@link #compatible}, the value does not fit {@code from}, or text names no boolean.
     */
    public static Optional<JsonElement> coerce(JsonElement value, PortType from, PortType to, boolean toggles) {
        if (!compatible(from, to, toggles) || from.problem(value).isPresent()) return Optional.empty();
        return switch (to) {
            case PortType.Text text -> Optional.of(new JsonPrimitive(sanitize(text(value), text.maxLength())));
            case PortType.Bool ignoredBool -> switch (from) {
                case PortType.Level ignoredLevel -> Optional.of(new JsonPrimitive(decimal(value).orElseThrow().signum() > 0));
                case PortType.Color ignoredColor -> Optional.of(new JsonPrimitive(rgb(value).orElseThrow() != 0));
                case PortType.Event ignoredEvent -> Optional.of(new JsonPrimitive(TOGGLE));
                default -> bool(value).map(JsonPrimitive::new);
            };
            case PortType.Level ignoredLevel -> from instanceof PortType.Bool
                    ? Optional.of(new JsonPrimitive(bool(value).orElseThrow() ? PortType.MAX_LEVEL : 0))
                    : Optional.of(new JsonPrimitive(fit(decimal(value).orElseThrow(), 0, PortType.MAX_LEVEL, true)));
            case PortType.Number number -> Optional.of(new JsonPrimitive(fit(decimal(value).orElseThrow(), number.min(), number.max(), number.integral())));
            case PortType.Enum target -> {
                String name = value.getAsString();
                yield target.values().stream().filter(name::equalsIgnoreCase).findFirst().map(JsonPrimitive::new);
            }
            case PortType.Event ignoredEvent -> Optional.of(new JsonPrimitive(PRESS));
            case PortType.Color ignoredColor -> Optional.of(value.deepCopy());
            case PortType.Light ignoredLight -> Optional.of(value.deepCopy());
            case PortType.Json ignoredJson -> Optional.of(value.deepCopy());
        };
    }

    /** {@code on}/{@code true}/{@code 1} or {@code off}/{@code false}/{@code 0}, case-insensitive. */
    public static Optional<Boolean> bool(String text) {
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "on", "true", "1" -> Optional.of(true);
            case "off", "false", "0" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    /** A JSON boolean, the number 0 or 1, or a {@link #bool(String)} spelling. */
    public static Optional<Boolean> bool(JsonElement value) {
        if (!value.isJsonPrimitive()) return Optional.empty();
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) return Optional.of(primitive.getAsBoolean());
        if (primitive.isNumber()) {
            return decimal(value).filter(number -> number.compareTo(BigDecimal.ZERO) == 0 || number.compareTo(BigDecimal.ONE) == 0)
                    .map(number -> number.signum() > 0);
        }
        return bool(primitive.getAsString());
    }

    /** A JSON number as a decimal; empty for anything else, NaN and infinities included. */
    public static Optional<BigDecimal> decimal(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return Optional.empty();
        try {
            return Optional.of(new BigDecimal(value.getAsJsonPrimitive().getAsString()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * A decimal number in {@link Locale#ROOT} notation ({@code 21.5}, never {@code 21,5}), spaces around it ignored; no
     * NaN or infinity, and nothing with more than {@value #MAX_DIGITS} digits or an exponent beyond ±{@value #MAX_DIGITS}
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

    /** A JSON number, or a string holding one, as {@link #number(String)} reads it; never a boolean. */
    public static Optional<BigDecimal> numeric(JsonElement value) {
        if (!value.isJsonPrimitive()) return Optional.empty();
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        return primitive.isNumber() || primitive.isString() ? number(primitive.getAsString()) : Optional.empty();
    }

    /** An 8-bit colour {@code {"r":…,"g":…,"b":…}} with whole channels 0–255 as {@code 0xRRGGBB}; empty for anything else. */
    public static OptionalInt rgb(JsonElement value) {
        if (!value.isJsonObject()) return OptionalInt.empty();
        JsonObject object = value.getAsJsonObject();
        int rgb = 0;
        for (String channel : new String[]{"r", "g", "b"}) {
            JsonElement part = object.get(channel);
            Optional<BigDecimal> number = part == null ? Optional.empty() : decimal(part);
            if (number.isEmpty() || PortType.Number.check(part, 0, MAX_CHANNEL, true).isPresent()) return OptionalInt.empty();
            rgb = rgb << 8 | number.get().intValue();
        }
        return OptionalInt.of(rgb);
    }

    /** Text without control characters and Minecraft formatting codes, at most {@code maxLength} code points. */
    public static String sanitize(String text, int maxLength) {
        String clean = UNSAFE_TEXT.matcher(text).replaceAll("");
        return clean.codePointCount(0, clean.length()) <= maxLength ? clean : clean.substring(0, clean.offsetByCodePoints(0, maxLength));
    }

    // A value's string form: a string as it is, any other value as JSON.
    private static String text(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : value.toString();
    }

    // A number clamped to [min, max] (before rounding, so a huge exponent is never expanded), whole when integral.
    private static Number fit(BigDecimal value, double min, double max, boolean integral) {
        BigDecimal clamped = value.max(BigDecimal.valueOf(min)).min(BigDecimal.valueOf(max));
        if (!integral) return clamped;
        return clamped.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}

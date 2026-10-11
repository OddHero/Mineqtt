package art.rehra.mineqtt.flow.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Colours as {@code 0xRRGGBB}, read from the forms MQTT payloads and Home Assistant use: {@code #rrggbb},
 * {@code rgb(r,g,b)}, {@code r,g,b}, a dye colour name, {@code {r,g,b}}, {@code [r,g,b]}, hue and saturation, CIE xy and
 * colour temperatures. Minecraft-free, so the device codecs and the twin's value maps share one implementation; the dye
 * names are Minecraft's {@code DyeColor} text colours, which {@code verifyValueCodecs} keeps equal to the game's.
 */
public final class Colors {
    public static final int WHITE = 0xFFFFFF;
    private static final Pattern RGB_FUNCTION = Pattern.compile("rgb\\((.*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX_COLOR = Pattern.compile("#([0-9a-fA-F]{6})");
    private static final int MAX_CHANNEL = 255;
    private static final Map<String, Integer> DYES = dyeTable();

    private Colors() {
    }

    /** The 16 dye colour names (Minecraft's serialized names, e.g. {@code light_blue}) with their text colours, in dye id order. */
    public static Map<String, Integer> dyes() {
        return DYES;
    }

    /** The text colour of a dye name, without regard to case; a space stands for {@code _} ({@code light blue}). */
    public static OptionalInt named(String name) {
        Integer rgb = DYES.get(name.trim().toLowerCase(Locale.ROOT).replace(' ', '_'));
        return rgb == null ? OptionalInt.empty() : OptionalInt.of(rgb);
    }

    /** A colour as text: {@code #rrggbb}, {@code rgb(r,g,b)}, {@code r,g,b} (channels rounded and clamped) or a dye name. */
    public static OptionalInt parse(String text) {
        String trimmed = text.trim();
        Matcher hex = HEX_COLOR.matcher(trimmed);
        if (hex.matches()) return OptionalInt.of(Integer.parseInt(hex.group(1), 16));
        Matcher function = RGB_FUNCTION.matcher(trimmed);
        if (function.matches()) return triple(function.group(1));
        if (trimmed.contains(",")) return triple(trimmed);
        return named(trimmed);
    }

    /**
     * A colour in any of Home Assistant's JSON forms: {@code {r,g,b}} (missing parts are 0), {@code [r,g,b]},
     * {@code {h,s}} (hue 0–360, saturation 0–100), CIE {@code {x,y}}, or text as in {@link #parse(String)}. Channels
     * are rounded and clamped to 0–255; numbers may be JSON numbers or numeric strings ({@link Coerce#numeric}).
     */
    public static OptionalInt parse(JsonElement json) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) return parse(json.getAsString());
        if (json.isJsonArray()) {
            JsonArray array = json.getAsJsonArray();
            if (array.size() != 3) return OptionalInt.empty();
            return rgb(Coerce.numeric(array.get(0)), Coerce.numeric(array.get(1)), Coerce.numeric(array.get(2)));
        }
        if (!json.isJsonObject()) return OptionalInt.empty();
        JsonObject object = json.getAsJsonObject();
        if (object.has("r") || object.has("g") || object.has("b")) {
            return rgb(component(object, "r"), component(object, "g"), component(object, "b"));
        }
        if (object.has("h") && object.has("s")) {
            Optional<BigDecimal> h = Coerce.numeric(object.get("h")), s = Coerce.numeric(object.get("s"));
            return h.isPresent() && s.isPresent() ? OptionalInt.of(hs(h.get().doubleValue(), s.get().doubleValue())) : OptionalInt.empty();
        }
        if (object.has("x") && object.has("y")) {
            Optional<BigDecimal> x = Coerce.numeric(object.get("x")), y = Coerce.numeric(object.get("y"));
            return x.isPresent() && y.isPresent() ? OptionalInt.of(xy(x.get().doubleValue(), y.get().doubleValue())) : OptionalInt.empty();
        }
        return OptionalInt.empty();
    }

    /** Channels clamped to 0–255, packed as {@code 0xRRGGBB}. */
    public static int rgb(int r, int g, int b) {
        return Math.clamp(r, 0, MAX_CHANNEL) << 16 | Math.clamp(g, 0, MAX_CHANNEL) << 8 | Math.clamp(b, 0, MAX_CHANNEL);
    }

    /** Hue in degrees (any angle) and saturation 0–100 (clamped) at full value, as Home Assistant's {@code hs_color}. */
    public static int hs(double hue, double saturation) {
        double h = ((hue % 360) + 360) % 360 / 60;
        double c = Math.clamp(saturation, 0, 100) / 100;
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
        return rgb((int) Math.round((rgb[0] + m) * 255), (int) Math.round((rgb[1] + m) * 255), (int) Math.round((rgb[2] + m) * 255));
    }

    /** CIE 1931 xy at full brightness to sRGB (D65), as v1 converted it. */
    public static int xy(double x, double y) {
        double cx = Math.clamp(x, 0, 1);
        double cy = Math.clamp(y, 0.0001, 1);
        double luminance = 1.0;
        double bigX = luminance / cy * cx;
        double bigZ = luminance / cy * (1 - cx - cy);
        double r = bigX * 3.2406 - luminance * 1.5372 - bigZ * 0.4986;
        double g = -bigX * 0.9689 + luminance * 1.8758 + bigZ * 0.0415;
        double b = bigX * 0.0557 - luminance * 0.2040 + bigZ * 1.0570;
        double max = Math.max(1, Math.max(r, Math.max(g, b)));
        return rgb(gamma(r / max), gamma(g / max), gamma(b / max));
    }

    /** The colour of a black body at {@code kelvin}, clamped to 1000–40000 (Tanner Helland's approximation, as v1 used). */
    public static int kelvin(int kelvin) {
        double temp = Math.clamp(kelvin, 1000, 40000) / 100.0;
        double r = temp <= 66 ? 255 : 329.698727446 * Math.pow(temp - 60, -0.1332047592);
        double g = temp <= 66 ? 99.4708025861 * Math.log(temp) - 161.1195681661 : 288.1221695283 * Math.pow(temp - 60, -0.0755148492);
        double b = temp >= 66 ? 255 : temp <= 19 ? 0 : 138.5177312231 * Math.log(temp - 10) - 305.0447927307;
        return rgb((int) Math.round(r), (int) Math.round(g), (int) Math.round(b));
    }

    private static OptionalInt triple(String values) {
        String[] parts = values.split(",");
        if (parts.length != 3) return OptionalInt.empty();
        return rgb(Coerce.number(parts[0]), Coerce.number(parts[1]), Coerce.number(parts[2]));
    }

    private static OptionalInt rgb(Optional<BigDecimal> r, Optional<BigDecimal> g, Optional<BigDecimal> b) {
        if (r.isEmpty() || g.isEmpty() || b.isEmpty()) return OptionalInt.empty();
        return OptionalInt.of(rgb(channel(r.get()), channel(g.get()), channel(b.get())));
    }

    // A missing channel of {r,g,b} is 0.
    private static Optional<BigDecimal> component(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null ? Optional.of(BigDecimal.ZERO) : Coerce.numeric(value);
    }

    // Rounded half up and clamped before it is narrowed, so a huge channel is 255, never an overflowed int.
    private static int channel(BigDecimal value) {
        return value.max(BigDecimal.ZERO).min(BigDecimal.valueOf(MAX_CHANNEL)).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private static int gamma(double linear) {
        double value = Math.max(0, linear);
        double corrected = value > 0.0031308 ? 1.055 * Math.pow(value, 1 / 2.4) - 0.055 : 12.92 * value;
        return (int) Math.round(corrected * 255);
    }

    // Minecraft 26.2's DyeColor text colours without their opaque alpha, in id order (javap of DyeColor.<clinit>, 2026-10-10).
    private static Map<String, Integer> dyeTable() {
        Map<String, Integer> dyes = new LinkedHashMap<>();
        dyes.put("white", 0xFFFFFF);
        dyes.put("orange", 0xFF681F);
        dyes.put("magenta", 0xFF00FF);
        dyes.put("light_blue", 0x9AC0CD);
        dyes.put("yellow", 0xFFFF00);
        dyes.put("lime", 0xBFFF00);
        dyes.put("pink", 0xFF69B4);
        dyes.put("gray", 0x808080);
        dyes.put("light_gray", 0xD3D3D3);
        dyes.put("cyan", 0x00FFFF);
        dyes.put("purple", 0xA020F0);
        dyes.put("blue", 0x0000FF);
        dyes.put("brown", 0x8B4513);
        dyes.put("green", 0x00FF00);
        dyes.put("red", 0xFF0000);
        dyes.put("black", 0x000000);
        return Collections.unmodifiableMap(dyes);
    }
}

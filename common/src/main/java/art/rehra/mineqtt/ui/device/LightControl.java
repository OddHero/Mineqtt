package art.rehra.mineqtt.ui.device;

import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.device.ValueCodecs.LightState;
import art.rehra.mineqtt.device.ValueCodecs.Rgb;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

/**
 * What the Controls tab's light picker ({@link DeviceTabs.Widget#LIGHT}) shows and sends, server-safe so the checks
 * hold it to what the server takes. A colour is picked as hue and saturation at full value, its brightness apart, as
 * Home Assistant's JSON light keeps them; a colour temperature in kelvin. Every command also switches the light on, as
 * picking a colour or a brightness does in Home Assistant; the switch beside the picker turns it off.
 */
public final class LightControl {
    /** The hue slider's range in degrees; 360 would be red again. */
    public static final int MAX_HUE = 359;
    /** The saturation slider's range in percent. */
    public static final int MAX_SATURATION = 100;
    /** The brightness slider's range: a light that is on has at least 1, as in Home Assistant. */
    public static final int MIN_BRIGHTNESS = 1;
    public static final int MAX_BRIGHTNESS = 255;
    /** The colour temperature slider's step in kelvin. */
    public static final int KELVIN_STEP = 50;

    private LightControl() {
    }

    /** A hue in degrees and a saturation in percent. */
    public record HueSaturation(int hue, int saturation) {
        public HueSaturation {
            hue = Math.clamp(hue, 0, MAX_HUE);
            saturation = Math.clamp(saturation, 0, MAX_SATURATION);
        }

        /** The hue and saturation of a colour; a grey's hue is 0. */
        public static HueSaturation of(Rgb color) {
            int max = Math.max(color.r(), Math.max(color.g(), color.b()));
            int min = Math.min(color.r(), Math.min(color.g(), color.b()));
            if (max == 0) return new HueSaturation(0, 0);
            double chroma = max - min;
            double hue;
            if (chroma == 0) hue = 0;
            else if (max == color.r()) hue = 60 * (((color.g() - color.b()) / chroma) % 6);
            else if (max == color.g()) hue = 60 * ((color.b() - color.r()) / chroma + 2);
            else hue = 60 * ((color.r() - color.g()) / chroma + 4);
            int degrees = (int) Math.round((hue + 360) % 360) % 360;
            return new HueSaturation(Math.min(degrees, MAX_HUE), (int) Math.round(chroma * MAX_SATURATION / max));
        }

        /** The colour at full value. */
        public Rgb rgb() {
            double s = saturation / (double) MAX_SATURATION;
            double h = hue / 60.0;
            double x = s * (1 - Math.abs(h % 2 - 1));
            double[] chroma = switch ((int) h) {
                case 0 -> new double[]{s, x, 0};
                case 1 -> new double[]{x, s, 0};
                case 2 -> new double[]{0, s, x};
                case 3 -> new double[]{0, x, s};
                case 4 -> new double[]{x, 0, s};
                default -> new double[]{s, 0, x};
            };
            double m = 1 - s;
            return new Rgb((int) Math.round((chroma[0] + m) * 255), (int) Math.round((chroma[1] + m) * 255), (int) Math.round((chroma[2] + m) * 255));
        }
    }

    /** The light a device's state shows; what is missing or unreadable keeps the default, as the lamp reads it. */
    public static LightState light(JsonObject state) {
        return LightState.MAP_CODEC.codec().parse(JsonOps.INSTANCE, state).result().orElse(LightState.DEFAULT);
    }

    /** The brightness a picker shows for a light: at least {@link #MIN_BRIGHTNESS}. */
    public static int brightness(LightState light) {
        return Math.max(light.brightness(), MIN_BRIGHTNESS);
    }

    /** A brightness in percent, as the picker labels it. */
    public static int percent(int brightness) {
        return (int) Math.round(brightness * 100.0 / MAX_BRIGHTNESS);
    }

    /** {@code {"state":"ON","brightness":…}}. */
    public static JsonObject brightness(int brightness) {
        JsonObject command = on();
        command.addProperty("brightness", Math.clamp(brightness, MIN_BRIGHTNESS, MAX_BRIGHTNESS));
        return command;
    }

    /** {@code {"state":"ON","color":{"r":…,"g":…,"b":…}}}: the light in {@code rgb} mode. */
    public static JsonObject color(HueSaturation color) {
        JsonObject command = on();
        command.add("color", color.rgb().toJson());
        return command;
    }

    /** {@code {"state":"ON","color_temp_kelvin":…}}: the light in {@code color_temp} mode. */
    public static JsonObject kelvin(int kelvin) {
        JsonObject command = on();
        command.addProperty("color_temp_kelvin", Math.clamp(kelvin, ValueCodecs.MIN_KELVIN, ValueCodecs.MAX_KELVIN));
        return command;
    }

    /**
     * One command that does what {@code earlier} and then {@code later} do: the later one's keys win, and a colour drops
     * an earlier colour temperature and the other way round, as the light's mode follows the last one picked.
     */
    public static JsonObject merge(JsonObject earlier, JsonObject later) {
        JsonObject merged = earlier.deepCopy();
        if (later.has("color")) merged.remove("color_temp_kelvin");
        if (later.has("color_temp_kelvin")) merged.remove("color");
        later.entrySet().forEach(entry -> merged.add(entry.getKey(), entry.getValue().deepCopy()));
        return merged;
    }

    // The words, not a boolean: a client's NBT has none, and the server reads the state as the lamp merges it.
    private static JsonObject on() {
        JsonObject command = new JsonObject();
        command.addProperty("state", "ON");
        return command;
    }
}

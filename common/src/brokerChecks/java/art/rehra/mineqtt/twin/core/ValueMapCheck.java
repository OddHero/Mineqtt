package art.rehra.mineqtt.twin.core;

import art.rehra.mineqtt.flow.core.Colors;
import art.rehra.mineqtt.flow.core.PortType;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;

/**
 * {@link ValueMap} and {@link DisplayText} (M3 plan D7, D18), run by Gradle's verifyValueMap task under a German default
 * locale: the bool spellings and transitional states, linear ranges (clamped, reversed, equal, inverted, from the
 * document, and back for a write), the options index, numbers fitted to a port, every colour form, the light composite
 * over the captured light states (cap §9.1 to §9.5), text with decimals and units, timestamps, NaN, infinities and
 * garbage refused everywhere, every output fitting its map's kind, and the panel layout (wrap, sizes, 4 × 32).
 */
public final class ValueMapCheck {
    private static final List<JsonElement> GARBAGE = List.of(new JsonPrimitive(Double.NaN), new JsonPrimitive(Double.POSITIVE_INFINITY),
            new JsonPrimitive(Double.NEGATIVE_INFINITY), new JsonPrimitive("NaN"), new JsonPrimitive("Infinity"), new JsonPrimitive("-Infinity"),
            new JsonPrimitive("1e999999999"), new JsonPrimitive("0x10"), new JsonPrimitive(""), new JsonPrimitive("  "), new JsonPrimitive("21,5"),
            new JsonPrimitive("unavailable"), new JsonPrimitive("unknown"), JsonNull.INSTANCE, new JsonObject(), json("[1,2,3,4,5]"));

    public static void main(String[] args) {
        Locale.setDefault(Locale.GERMANY);
        bools();
        levels();
        ranges();
        options();
        numbers();
        colors();
        lights();
        texts();
        timestamps();
        garbage();
        kinds();
        layout();
        presetSamples();
        System.out.println("Value map checks passed.");
    }

    private static void bools() {
        ValueMap bool = new ValueMap.Bool(false);
        for (String on : List.of("on", "true", "1", "open", "home", "unlocked", "detected", "playing", " ON ", "Open", "TRUE")) {
            check(bool.apply(new JsonPrimitive(on)).orElseThrow().getAsBoolean(), "On: " + on);
        }
        for (String off : List.of("off", "false", "0", "closed", "not_home", "locked", "clear", "idle", "standby", "OFF", "Not_Home")) {
            check(!bool.apply(new JsonPrimitive(off)).orElseThrow().getAsBoolean(), "Off: " + off);
        }
        for (String hold : List.of("opening", "closing", "unlocking", "locking", "jammed", "paused", "buffering", "unavailable", "unknown", "cool", "42", "")) {
            check(bool.apply(new JsonPrimitive(hold)).isEmpty(), "Holds (no change): " + hold);
        }
        check(bool.apply(new JsonPrimitive(true)).orElseThrow().getAsBoolean() && !bool.apply(new JsonPrimitive(false)).orElseThrow().getAsBoolean(), "JSON booleans");
        check(bool.apply(new JsonPrimitive(1)).orElseThrow().getAsBoolean() && bool.apply(new JsonPrimitive(42)).isEmpty(), "Numbers 0 and 1 only");
        check(!new ValueMap.Bool(true).apply(new JsonPrimitive("on")).orElseThrow().getAsBoolean()
                && new ValueMap.Bool(true).apply(new JsonPrimitive("closed")).orElseThrow().getAsBoolean(), "Invert");
        check(new ValueMap.Bool(true).apply(new JsonPrimitive("opening")).isEmpty(), "Invert keeps a transitional state holding");
    }

    private static void levels() {
        ValueMap level = new ValueMap.LevelByBool(false);
        check(level.apply(new JsonPrimitive("on")).orElseThrow().getAsInt() == 15 && level.apply(new JsonPrimitive("closed")).orElseThrow().getAsInt() == 0, "Bool to 15/0");
        check(new ValueMap.LevelByBool(true).apply(new JsonPrimitive("open")).orElseThrow().getAsInt() == 0, "Inverted bool level");
        check(level.apply(new JsonPrimitive("unlocking")).isEmpty(), "A transitional state holds the level");
    }

    private static void ranges() {
        ValueMap.LevelByRange defaults = ValueMap.LevelByRange.fromDocument(false);
        check(level(defaults, "7", null) == 7 && level(defaults, "7.5", null) == 8 && level(defaults, "7.49", null) == 7, "Default 0..15, rounded half up");
        check(level(defaults, "-3", null) == 0 && level(defaults, "99", null) == 15, "Clamped to 0..15");
        // M3-15: scaled before the division, so the half is exact (12.5 / 15 * 15 used to land just below 12.5).
        check(level(defaults, "12.5", null) == 13 && level(defaults, "0.5", null) == 1 && level(new ValueMap.LevelByRange(BigDecimal.ZERO, BigDecimal.valueOf(30), false),
                "25", null) == 13, "A value halfway between two levels rounds up");
        JsonElement inputNumber = json("{\"state\":\"42.5\",\"attributes\":{\"min\":0.0,\"max\":100.0,\"step\":0.5,\"unit_of_measurement\":\"%\"}}");
        check(level(defaults, "42.5", inputNumber) == 6, "The range comes from the input_number's min/max (cap §9.6): 42.5% is 6");
        check(level(defaults, "100.0", inputNumber) == 15 && level(defaults, "0.0", inputNumber) == 0, "The ends of the range");
        ValueMap.LevelByRange percent = new ValueMap.LevelByRange(BigDecimal.ZERO, BigDecimal.valueOf(100), false);
        check(level(percent, "66", null) == 10, "A fan's percentage 66 is 10");
        check(level(new ValueMap.LevelByRange(BigDecimal.ZERO, BigDecimal.valueOf(255), false), "128", null) == 8, "Brightness 128 of 255 is 8");
        // Cap §9.4: an off light's brightness is null. Off is level 0 (15 inverted), not the level from before.
        ValueMap.LevelByRange brightness = new ValueMap.LevelByRange(BigDecimal.ZERO, BigDecimal.valueOf(255), false);
        JsonElement off = json("{\"state\":\"off\",\"attributes\":{\"brightness\":null}}");
        check(brightness.apply(JsonNull.INSTANCE, off).orElseThrow().getAsInt() == 0
                        && new ValueMap.LevelByRange(BigDecimal.ZERO, BigDecimal.valueOf(255), true).apply(JsonNull.INSTANCE, off).orElseThrow().getAsInt() == 15
                        && brightness.apply(JsonNull.INSTANCE, json("{\"state\":\"closed\"}")).orElseThrow().getAsInt() == 0,
                "null while the state reads off is level 0, inverted 15");
        check(brightness.apply(JsonNull.INSTANCE, json("{\"state\":\"on\",\"attributes\":{\"brightness\":null}}")).isEmpty()
                        && brightness.apply(JsonNull.INSTANCE, json("{\"state\":\"unavailable\"}")).isEmpty() && brightness.apply(JsonNull.INSTANCE, json("{\"state\":\"opening\"}")).isEmpty()
                        && brightness.apply(JsonNull.INSTANCE, JsonNull.INSTANCE).isEmpty() && brightness.apply(JsonNull.INSTANCE, json("{\"attributes\":{}}")).isEmpty(),
                "null holds while the state is on, unavailable, transitional or missing");
        ValueMap.LevelByRange temperature = new ValueMap.LevelByRange(BigDecimal.valueOf(15), BigDecimal.valueOf(30), false);
        check(level(temperature, "22", null) == 7 && level(temperature, "10", null) == 0 && level(temperature, "35", null) == 15, "current_temperature over 15..30");
        check(level(new ValueMap.LevelByRange(BigDecimal.valueOf(10), BigDecimal.valueOf(20), false), "15", inputNumber) == 8, "Own bounds win over the document's");
        check(level(new ValueMap.LevelByRange(null, BigDecimal.valueOf(50), false), "25", inputNumber) == 8, "One own bound, the other from the document");
        ValueMap.LevelByRange reversed = new ValueMap.LevelByRange(BigDecimal.valueOf(100), BigDecimal.ZERO, false);
        check(level(reversed, "100", null) == 0 && level(reversed, "0", null) == 15 && level(reversed, "20", null) == 12 && level(reversed, "150", null) == 0,
                "A reversed range maps descending, clamped");
        ValueMap.LevelByRange equal = new ValueMap.LevelByRange(BigDecimal.TEN, BigDecimal.TEN, false);
        check(level(equal, "9.99", null) == 0 && level(equal, "10", null) == 15 && level(equal, "11", null) == 15, "An equal range is a threshold");
        check(level(new ValueMap.LevelByRange(BigDecimal.ZERO, BigDecimal.valueOf(100), true), "66", null) == 5, "Invert gives 15 - level");
        check(level(defaults, "1e+21", null) == 15 && level(defaults, "-1e+21", null) == 0, "Huge but valid numbers clamp");
        check(defaults.apply(new JsonPrimitive("21,5")).isEmpty(), "Locale.GERMANY: 21,5 is no number");
        check(level(percent, "33.333333333333336", null) == 5, "Long floats");
        // The way back, for set_value.
        check(defaults.value(15, inputNumber).compareTo(BigDecimal.valueOf(100)) == 0 && defaults.value(0, inputNumber).signum() == 0, "Level to value: the ends");
        check(defaults.value(6, inputNumber).toPlainString().equals("40") && defaults.value(7, inputNumber).toPlainString().equals("46.5"),
                "Level to value snaps to the step from min: " + defaults.value(7, inputNumber));
        check(percent.value(5, JsonNull.INSTANCE).toPlainString().equals("33.33333333333333"), "Without a step: " + percent.value(5, JsonNull.INSTANCE));
        check(reversed.value(15, JsonNull.INSTANCE).signum() == 0 && reversed.value(0, JsonNull.INSTANCE).intValue() == 100, "A reversed range back");
        check(equal.value(9, JsonNull.INSTANCE).compareTo(BigDecimal.TEN) == 0, "An equal range back is its bound");
        check(new ValueMap.LevelByRange(BigDecimal.ZERO, BigDecimal.valueOf(100), true).value(15, JsonNull.INSTANCE).signum() == 0, "Inverted back");
        check(defaults.value(99, JsonNull.INSTANCE).intValue() == 15 && defaults.value(-4, JsonNull.INSTANCE).signum() == 0, "Levels out of 0..15 are clamped");
        for (int l = 0; l <= 15; l++) {
            check(level(defaults, defaults.value(l, inputNumber).toPlainString(), inputNumber) == l, "Level " + l + " survives the way back and forth");
        }
    }

    private static void options() {
        JsonElement select = json("{\"state\":\"Turbo Mode\",\"attributes\":{\"options\":[\"Off\",\"Eco\",\"Turbo Mode\"]}}");
        ValueMap byDocument = new ValueMap.LevelByOption(null);
        check(byDocument.apply(new JsonPrimitive("Turbo Mode"), select).orElseThrow().getAsInt() == 2, "Turbo Mode is option 2 (cap §9.7)");
        check(byDocument.apply(new JsonPrimitive("Off"), select).orElseThrow().getAsInt() == 0, "The first option is 0");
        check(byDocument.apply(new JsonPrimitive("turbo mode"), select).orElseThrow().getAsInt() == 2, "Without regard to case");
        check(byDocument.apply(new JsonPrimitive("Boost"), select).isEmpty() && byDocument.apply(new JsonPrimitive("Eco")).isEmpty(), "Not an option, or no options: holds");
        check(new ValueMap.LevelByOption(List.of("a", "A")).apply(new JsonPrimitive("A")).orElseThrow().getAsInt() == 1, "An exact match wins");
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) many.add("o" + i);
        check(new ValueMap.LevelByOption(many).apply(new JsonPrimitive("o19")).orElseThrow().getAsInt() == 15, "Indexes past 15 clamp");
        check(byDocument.apply(new JsonPrimitive("unavailable"), json("{\"attributes\":{\"options\":[\"unavailable\"]}}")).isEmpty(), "unavailable is never an option");
    }

    private static void numbers() {
        ValueMap brightness = new ValueMap.Numeric(PortType.integer(0, 255));
        check(brightness.apply(new JsonPrimitive(300)).orElseThrow().getAsInt() == 255 && brightness.apply(new JsonPrimitive(-1)).orElseThrow().getAsInt() == 0, "Clamped to the port");
        check(brightness.apply(new JsonPrimitive("128.6")).orElseThrow().getAsInt() == 129, "A Locale.ROOT string, rounded for an integral port");
        ValueMap decimal = new ValueMap.Numeric(PortType.number(-50, 50, false, "°C"));
        check(decimal.apply(new JsonPrimitive("21.4")).orElseThrow().getAsDouble() == 21.4 && decimal.apply(json("1e+21")).orElseThrow().getAsDouble() == 50, "Decimal ports keep the fraction, clamp huge values");
        check(brightness.apply(new JsonPrimitive(true)).isEmpty() && brightness.apply(new JsonPrimitive("abc")).isEmpty() && brightness.apply(new JsonPrimitive("12,5")).isEmpty(),
                "Booleans, words and locale decimals are no numbers");
    }

    private static void colors() {
        ValueMap color = new ValueMap.Color();
        check(rgb(color, json("[255,120,10]")) == 0xFF780A, "[r,g,b] (rgb_color)");
        check(rgb(color, json("{\"r\":255,\"g\":120,\"b\":10}")) == 0xFF780A && rgb(color, json("{\"r\":255}")) == 0xFF0000, "{r,g,b}, missing parts 0");
        check(rgb(color, new JsonPrimitive("#ff780a")) == 0xFF780A && rgb(color, new JsonPrimitive("#FF780A")) == 0xFF780A, "#rrggbb");
        check(rgb(color, new JsonPrimitive("red")) == 0xFF0000 && rgb(color, new JsonPrimitive("Light Blue")) == Colors.dyes().get("light_blue"), "Dye names");
        check(rgb(color, new JsonPrimitive("rgb(1, 2, 3)")) == 0x010203 && rgb(color, new JsonPrimitive("300,-5,7")) == 0xFF0007, "rgb() and r,g,b, clamped");
        check(rgb(color, json("{\"h\":120,\"s\":100}")) == 0x00FF00, "{h,s}");
        check(rgb(color, json("[26.939,96.078]")) == 0xFF780A, "[h,s] (hs_color) gives the same colour as rgb_color (cap §9.2)");
        int xy = rgb(color, json("{\"x\":0.62,\"y\":0.366}"));
        check((xy >> 16 & 0xFF) == 255 && (xy & 0xFF) < 60, "{x,y} is red-orange: " + Integer.toHexString(xy));
        check(rgb(color, new JsonPrimitive(2700)) == Colors.kelvin(2700) && rgb(color, new JsonPrimitive(6500)) == Colors.kelvin(6500), "Kelvin");
        check(color.apply(new JsonPrimitive(500)).isEmpty() && color.apply(new JsonPrimitive(50000)).isEmpty(), "Numbers outside 1000..40000 K are no colour");
        check(color.apply(JsonNull.INSTANCE).isEmpty(), "null (a light that is off, cap §9.4) keeps the last colour, never black");
        check(color.apply(json("[93,65,40,87]")).isEmpty() && color.apply(json("[0,0,0,87,93]")).isEmpty(), "rgbw and rgbww lists are no rgb");
        check(color.apply(new JsonPrimitive("nope")).isEmpty() && color.apply(json("[1,\"x\",3]")).isEmpty() && color.apply(json("{\"h\":1}")).isEmpty(), "Garbage");
        check(color.apply(json("[1e999999999,0,0]")).isEmpty(), "A channel with a huge exponent is refused, never rounded");
    }

    // The light composite over the captured light states.
    private static void lights() {
        ValueMap light = new ValueMap.Light();
        check(light(light, "{\"state\":\"on\",\"attributes\":{\"color_mode\":\"brightness\",\"brightness\":0,\"supported_color_modes\":[\"brightness\"]}}")
                .equals(json("{\"state\":\"ON\",\"brightness\":1}")), "On at brightness 0 shows at 1 (cap §9.1)");
        check(light(light, "{\"state\":\"on\",\"attributes\":{\"color_mode\":\"hs\",\"brightness\":200,\"color_temp_kelvin\":null,\"hs_color\":[26.939,96.078],"
                + "\"rgb_color\":[255,120,10],\"xy_color\":[0.62,0.366],\"effect\":\"rainbow\"}}")
                .equals(json("{\"state\":\"ON\",\"brightness\":200,\"color\":{\"r\":255,\"g\":120,\"b\":10}}")), "hs mode reads rgb_color (cap §9.2)");
        check(light(light, "{\"state\":\"on\",\"attributes\":{\"supported_color_modes\":[\"rgbww\"],\"color_mode\":\"rgbww\",\"brightness\":180,"
                + "\"rgb_color\":[93,65,40],\"rgbww_color\":[0,0,0,87,93]}}")
                .equals(json("{\"state\":\"ON\",\"brightness\":180,\"color\":{\"r\":93,\"g\":65,\"b\":40}}")), "An RGBWW light without kelvin topics (cap §9.3)");
        check(light(light, "{\"state\":\"on\",\"attributes\":{\"color_mode\":\"color_temp\",\"brightness\":255,\"color_temp_kelvin\":2700,\"rgb_color\":[255,167,87]}}")
                .equals(json("{\"state\":\"ON\",\"brightness\":255,\"color_temp_kelvin\":2700}")), "color_temp mode gives kelvin, not the colour");
        check(light(light, "{\"state\":\"on\",\"attributes\":{\"color_mode\":\"color_temp\",\"rgb_color\":[255,167,87]}}")
                .equals(json("{\"state\":\"ON\",\"color\":{\"r\":255,\"g\":167,\"b\":87}}")), "color_temp mode without a kelvin topic falls back to rgb_color");
        check(light(light, "{\"state\":\"off\",\"attributes\":{\"effect\":null,\"color_mode\":null,\"brightness\":null,\"color_temp_kelvin\":null,\"hs_color\":null,"
                + "\"rgb_color\":null,\"xy_color\":null,\"supported_color_modes\":[\"color_temp\",\"hs\"]}}")
                .equals(json("{\"state\":\"OFF\"}")), "Off is the state alone; the null colours keep the last (cap §9.4)");
        check(light.apply(json("{\"state\":\"unavailable\",\"attributes\":{\"brightness\":255,\"rgb_color\":[1,2,3]}}")).isEmpty()
                && light.apply(json("{\"state\":\"unknown\"}")).isEmpty() && light.apply(json("{\"attributes\":{}}")).isEmpty(),
                "Unavailable, unknown or no state: nothing, whatever stale attributes say (cap §9.5)");
        check(light(light, "{\"state\":\"on\"}").equals(json("{\"state\":\"ON\"}")), "A switch: the state alone");
        check(light(light, "{\"state\":\"on\",\"attributes\":{\"brightness\":null,\"rgb_color\":null,\"hs_color\":[0,100]}}")
                .equals(json("{\"state\":\"ON\",\"color\":{\"r\":255,\"g\":0,\"b\":0}}")), "hs_color when rgb_color is null");
        check(light(light, "{\"state\":\"on\",\"attributes\":{\"xy_color\":[0.7,0.29]}}").getAsJsonObject("color").get("r").getAsInt() == 255, "xy_color as the last HA form");
        check(light(light, "{\"state\":\"on\",\"attributes\":{\"color_temp_kelvin\":4000}}").equals(json("{\"state\":\"ON\",\"color_temp_kelvin\":4000}")),
                "Kelvin without a colour mode and without colours");
        check(light(light, "{\"state\":\"on\",\"attributes\":{\"brightness\":999}}").get("brightness").getAsInt() == 255
                && light(light, "{\"state\":\"on\",\"attributes\":{\"brightness\":\"lots\"}}").equals(json("{\"state\":\"ON\"}")), "Brightness clamped; garbage left out");
        // zigbee2mqtt's flat payloads.
        JsonObject z2m = light(light, "{\"state\":\"ON\",\"brightness\":254,\"color_mode\":\"xy\",\"color\":{\"x\":0.7,\"y\":0.29},\"linkquality\":120}");
        check(z2m.get("brightness").getAsInt() == 254 && z2m.getAsJsonObject("color").get("r").getAsInt() == 255, "A zigbee2mqtt xy light");
        check(light(light, "{\"state\":\"ON\",\"color_mode\":\"color_temp\",\"color_temp\":370}").equals(json("{\"state\":\"ON\",\"color_temp_kelvin\":2703}")),
                "A zigbee2mqtt colour temperature in mireds");
        check(light(light, "{\"state\":\"OFF\",\"brightness\":254}").equals(json("{\"state\":\"OFF\"}")), "A zigbee2mqtt light off");
        check(light.apply(new JsonPrimitive("on")).isEmpty(), "A light needs an object");
        // A lamp mirroring a switch-like entity: on in white at full brightness, off alone (D5).
        ValueMap white = ValueMap.LightSwitch.WHITE;
        check(white.apply(new JsonPrimitive("on")).orElseThrow().equals(json("{\"state\":\"ON\",\"brightness\":255,\"color\":{\"r\":255,\"g\":255,\"b\":255}}")),
                "A switch that is on lights the lamp white at full brightness");
        check(white.apply(new JsonPrimitive("off")).orElseThrow().equals(json("{\"state\":\"OFF\"}")), "A switch that is off is the state alone");
        check(new ValueMap.LightSwitch(0x123456).apply(new JsonPrimitive("on")).orElseThrow().getAsJsonObject().getAsJsonObject("color").get("g").getAsInt() == 0x34,
                "Another colour");
        check(white.apply(new JsonPrimitive("opening")).isEmpty() && white.apply(new JsonPrimitive("unavailable")).isEmpty(), "A transitional or missing state holds");
        try {
            new ValueMap.LightSwitch(0x1000000);
            throw new AssertionError("A colour beyond 0xFFFFFF is refused");
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    private static void texts() {
        ValueMap auto = new ValueMap.Text(ValueMap.AUTO_DECIMALS, null, 64);
        check(text(auto, json("33.333333333333336"), null).equals("33.33"), "Auto shortens float noise: 33.333333333333336 is 33.33");
        check(text(auto, new JsonPrimitive("10.62"), null).equals("10.62") && text(auto, new JsonPrimitive("50.0"), null).equals("50.0")
                && text(auto, new JsonPrimitive("007"), null).equals("007") && text(auto, new JsonPrimitive("4"), null).equals("4"),
                "Auto keeps Home Assistant's own string");
        check(text(auto, new JsonPrimitive("0.000123456"), null).equals("0.000123") && text(auto, new JsonPrimitive("0.1234"), null).equals("0.123"),
                "Below 1, three significant digits");
        check(text(auto, json("1e+21"), null).equals("1000000000000000000000") && text(auto, new JsonPrimitive("-5.5"), null).equals("-5.5"),
                "Exponents are written out; signs stay");
        check(text(auto, new JsonPrimitive("33.335"), null).equals("33.335") && text(auto, new JsonPrimitive("33.3355"), null).equals("33.34"), "Up to three decimals are kept");
        ValueMap zero = new ValueMap.Text(0, "", 64), one = new ValueMap.Text(1, "", 64), three = new ValueMap.Text(3, "", 64);
        check(text(zero, new JsonPrimitive("10.62"), null).equals("11") && text(one, new JsonPrimitive("10.62"), null).equals("10.6")
                && text(three, new JsonPrimitive("10.62"), null).equals("10.620") && text(one, new JsonPrimitive("10.65"), null).equals("10.7"),
                "Fixed decimals round half up");
        check(text(one, json("33.333333333333336"), null).equals("33.3"), "No comma under Locale.GERMANY");
        JsonElement sensor = json("{\"state\":\"10.62\",\"attributes\":{\"unit_of_measurement\":\"\\u00b0C\",\"device_class\":\"temperature\"}}");
        check(text(auto, new JsonPrimitive("10.62"), sensor).equals("10.62 °C"), "The unit from the document, ensure_ascii decoded (cap §9.6)");
        check(text(auto, new JsonPrimitive("42.5"), json("{\"attributes\":{\"unit_of_measurement\":\"%\"}}")).equals("42.5%")
                && text(auto, new JsonPrimitive("90"), json("{\"attributes\":{\"unit_of_measurement\":\"°\"}}")).equals("90°"),
                "% and ° follow without a space");
        check(text(new ValueMap.Text(ValueMap.AUTO_DECIMALS, "", 64), new JsonPrimitive("10.62"), sensor).equals("10.62")
                && text(new ValueMap.Text(ValueMap.AUTO_DECIMALS, "lx", 64), new JsonPrimitive("100"), sensor).equals("100 lx"), "An own unit wins; \"\" is none");
        check(text(auto, new JsonPrimitive("Turbo Mode"), sensor).equals("Turbo Mode"), "Words get no unit");
        check(text(auto, new JsonPrimitive("07:30:00"), null).equals("07:30") && text(auto, new JsonPrimitive("23:59"), null).equals("23:59"), "A time of day is HH:mm");
        check(text(auto, new JsonPrimitive("Grüße \"quoted\" / slash"), null).equals("Grüße \"quoted\" / slash"), "Raw UTF-8 text stays (cap §7)");
        check(text(auto, new JsonPrimitive("§cRed\u0007"), null).equals("Red"), "Sanitised");
        check(text(new ValueMap.Text(ValueMap.AUTO_DECIMALS, null, 5), new JsonPrimitive("abcdefgh"), null).equals("abcde"), "Cut to the port's length");
        check(text(auto, new JsonPrimitive(true), null).equals("true"), "A boolean as text");
        check(auto.apply(json("{\"a\":1}")).isEmpty() && auto.apply(json("[1]")).isEmpty() && auto.apply(JsonNull.INSTANCE).isEmpty(), "Objects, lists and null are no text");
        check(auto.apply(new JsonPrimitive("unavailable")).isEmpty() && auto.apply(new JsonPrimitive("unknown")).isEmpty(), "No-value states are not shown as text");
        check(text(auto, JsonNull.INSTANCE, json("{\"state\":\"off\",\"attributes\":{\"brightness\":null}}")).equals("off")
                        && auto.apply(JsonNull.INSTANCE, json("{\"state\":\"on\",\"attributes\":{\"brightness\":null}}")).isEmpty(),
                "null of an off light shows that it is off (never its old brightness); of an on one it holds");
        for (JsonElement nonFinite : List.of(new JsonPrimitive("NaN"), new JsonPrimitive("Infinity"), new JsonPrimitive("-Infinity"), new JsonPrimitive(" nan "),
                new JsonPrimitive("+inf"), new JsonPrimitive("-INF"), new JsonPrimitive(Double.NaN), new JsonPrimitive(Double.NEGATIVE_INFINITY))) {
            check(auto.apply(nonFinite).isEmpty() && new ValueMap.Text(1, "°C", 64).apply(nonFinite, JsonNull.INSTANCE).isEmpty(), "D7: Text refuses " + nonFinite);
        }
        check(text(auto, new JsonPrimitive("Nancy"), null).equals("Nancy") && text(auto, new JsonPrimitive("info"), null).equals("info"), "Words that only start alike stay");
        try {
            new ValueMap.Text(4, null, 10);
            throw new AssertionError("Four decimals are refused");
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    private static void timestamps() {
        ValueMap timestamp = new ValueMap.Timestamp();
        check(timestamp.apply(new JsonPrimitive("2026-10-11T04:48:44+00:00")).orElseThrow().getAsLong() == Instant.parse("2026-10-11T04:48:44Z").toEpochMilli(),
                "ISO without a fraction (a timestamp sensor)");
        check(timestamp.apply(new JsonPrimitive("2026-10-10T16:58:44.639470+00:00")).orElseThrow().getAsLong() == Instant.parse("2026-10-10T16:58:44.639Z").toEpochMilli(),
                "ISO with microseconds (last_updated, input_button)");
        check(timestamp.apply(new JsonPrimitive("2026-10-10T18:58:44+02:00")).orElseThrow().getAsLong() == Instant.parse("2026-10-10T16:58:44Z").toEpochMilli(), "Offsets");
        check(timestamp.apply(new JsonPrimitive("07:30:00")).orElseThrow().getAsString().equals("07:30"), "A time of day is the text HH:mm");
        check(timestamp.apply(new JsonPrimitive(27000)).isEmpty() && timestamp.apply(new JsonPrimitive("yesterday")).isEmpty()
                && timestamp.apply(new JsonPrimitive("2026-10-10T16:58:44")).isEmpty() && timestamp.apply(new JsonPrimitive("25:00")).isEmpty(),
                "Bare numbers, words, times without an offset and impossible times are refused");
        // ISO allows years up to ±999 999 999: they parse, but epoch milliseconds overflow (a server crash once a panel showed one).
        for (String extreme : List.of("+999999999-01-01T00:00:00Z", "-999999999-01-01T00:00:00Z", "+300000000-01-01T00:00:00Z", "+10000-01-01T00:00:00Z",
                "-0001-12-31T23:59:59Z")) {
            check(timestamp.apply(new JsonPrimitive(extreme)).isEmpty(), "A year outside 0..9999 is no point in time: " + extreme);
        }
        check(timestamp.apply(new JsonPrimitive("9999-12-31T23:59:59Z")).orElseThrow().getAsLong() == Instant.parse("9999-12-31T23:59:59Z").toEpochMilli()
                        && timestamp.apply(new JsonPrimitive("0000-01-01T00:00:00Z")).orElseThrow().getAsLong() == Instant.parse("0000-01-01T00:00:00Z").toEpochMilli(),
                "The years 0 and 9999 are still read");
    }

    // NaN, infinities and garbage never map, whatever the map.
    private static void garbage() {
        List<ValueMap> numeric = List.of(new ValueMap.Bool(false), new ValueMap.LevelByBool(false), ValueMap.LevelByRange.fromDocument(false),
                new ValueMap.Numeric(PortType.integer(0, 255)), new ValueMap.Color(), new ValueMap.Timestamp(), new ValueMap.Light(),
                new ValueMap.LevelByOption(null), ValueMap.LightSwitch.WHITE);
        for (ValueMap map : numeric) {
            for (JsonElement value : GARBAGE) {
                check(map.apply(value, JsonNull.INSTANCE).isEmpty(), map + " refuses " + value);
            }
        }
        check(ValueMap.LevelByRange.fromDocument(false).apply(new JsonPrimitive("7"), json("{\"attributes\":{\"min\":\"NaN\",\"max\":\"x\"}}"))
                .orElseThrow().getAsInt() == 7, "Garbage bounds in the document fall back to the defaults");
    }

    // Every output fits the kind its map declares.
    private static void kinds() {
        record Case(ValueMap map, JsonElement value, JsonElement document) {
        }
        JsonElement doc = json("{\"attributes\":{\"min\":0,\"max\":100,\"options\":[\"a\",\"b\"],\"unit_of_measurement\":\"W\"}}");
        List<Case> cases = List.of(new Case(new ValueMap.Bool(true), new JsonPrimitive("open"), doc),
                new Case(new ValueMap.LevelByBool(false), new JsonPrimitive("on"), doc),
                new Case(ValueMap.LevelByRange.fromDocument(false), new JsonPrimitive("55.5"), doc),
                new Case(new ValueMap.LevelByOption(null), new JsonPrimitive("b"), doc),
                new Case(new ValueMap.Numeric(PortType.integer(0, 255)), new JsonPrimitive("1e3"), doc),
                new Case(new ValueMap.Color(), json("[26.9,96.1]"), doc),
                new Case(new ValueMap.Text(2, null, 12), new JsonPrimitive("1234567.891"), doc),
                new Case(new ValueMap.Timestamp(), new JsonPrimitive("2026-10-11T04:48:44+00:00"), doc),
                new Case(new ValueMap.Light(), json("{\"state\":\"on\",\"attributes\":{\"brightness\":0,\"rgb_color\":[300,1,2]}}"), doc),
                new Case(ValueMap.LightSwitch.WHITE, new JsonPrimitive("on"), doc));
        for (Case c : cases) {
            JsonElement out = c.map().apply(c.value(), c.document()).orElseThrow(() -> new AssertionError(c.map() + " maps " + c.value()));
            check(c.map().kind().problem(out).isEmpty(), c.map() + " gives a " + c.map().kind().describe() + ": " + out);
        }
        check(new ValueMap.Text(2, null, 12).apply(new JsonPrimitive("1234567.891"), doc).orElseThrow().getAsString().equals("1234567.89 W"), "Text of a long number fits 12");
    }

    private static void layout() {
        DisplayText.Layout temperature = DisplayText.layout("21.4 °C", DisplayText.Size.AUTO);
        check(temperature.size() == DisplayText.Size.LARGE && temperature.lines().equals(List.of("21.4 °C")) && !temperature.truncated(), "A short value is large");
        DisplayText.Layout word = DisplayText.layout("Temperature", DisplayText.Size.AUTO);
        check(word.size() == DisplayText.Size.MEDIUM && word.lines().equals(List.of("Temperature")), "Auto avoids splitting a word: " + word);
        DisplayText.Layout three = DisplayText.layout("The front door is open", DisplayText.Size.AUTO);
        check(three.size() == DisplayText.Size.MEDIUM && three.lines().equals(List.of("The front", "door is open")), "Two medium lines: " + three);
        DisplayText.Layout cut = DisplayText.layout("Hello wonderful world", DisplayText.Size.LARGE);
        check(cut.lines().equals(List.of("Hello", "wonderful…")) && cut.truncated(), "Large cuts with an ellipsis: " + cut);
        DisplayText.Layout full = DisplayText.layout("abcdefghij abcdefghij", DisplayText.Size.LARGE);
        check(full.lines().equals(List.of("abcdefghij", "abcdefghij")) && !full.truncated(), "Exactly full is not cut");
        DisplayText.Layout overfull = DisplayText.layout("abcdefghij abcdefghij x", DisplayText.Size.LARGE);
        check(overfull.lines().equals(List.of("abcdefghij", "abcdefghi…")) && overfull.truncated(), "A full last line gives a character to the ellipsis: " + overfull);
        DisplayText.Layout split = DisplayText.layout("abcdefghijklmnopqrstuvwxyz", DisplayText.Size.SMALL);
        check(split.lines().equals(List.of("abcdefghijklmnop", "qrstuvwxyz")), "A long word is split: " + split);
        check(DisplayText.layout("abcdefghijklmnopqrstuvwxyz", DisplayText.Size.AUTO).size() == DisplayText.Size.MEDIUM,
                "When every size must split a word, auto takes the largest that shows the text whole");
        String message = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. ".repeat(3).substring(0, 255);
        DisplayText.Layout longText = DisplayText.layout(message, DisplayText.Size.AUTO);
        check(longText.size() == DisplayText.Size.SMALL && longText.truncated() && longText.lines().size() == 4 && longText.lines().get(3).endsWith("…"),
                "255 characters: small, four lines, cut: " + longText);
        check(DisplayText.layout("Line one\nLine two\r\nthree", DisplayText.Size.SMALL).lines().equals(List.of("Line one", "Line two", "three")), "Line breaks");
        check(DisplayText.layout("\n\nmiddle\n\n", DisplayText.Size.SMALL).lines().equals(List.of("middle")), "Blank lines at the ends are dropped");
        check(DisplayText.layout("§aGreen\tLeaf", DisplayText.Size.SMALL).lines().equals(List.of("Green Leaf")), "Sanitised; a tab is a space");
        DisplayText.Layout emoji = DisplayText.layout("😀".repeat(20), DisplayText.Size.SMALL);
        check(emoji.lines().get(0).codePointCount(0, emoji.lines().get(0).length()) == 16 && emoji.lines().size() == 2, "Code points, not chars");
        DisplayText.Layout empty = DisplayText.layout("", DisplayText.Size.AUTO);
        check(empty.size() == DisplayText.Size.LARGE && empty.lines().isEmpty() && !empty.truncated(), "Empty text");
        DisplayText.Layout titled = DisplayText.titled("Porch Lamp Living Room", "21.4 °C", DisplayText.Size.AUTO);
        check("Porch Lamp…".equals(titled.title()) && titled.size() == DisplayText.Size.LARGE && titled.lines().equals(List.of("21.4 °C")), "A titled value: " + titled);
        DisplayText.Layout titledSmall = DisplayText.titled("Note", message, DisplayText.Size.SMALL);
        check(titledSmall.lines().size() == DisplayText.TITLED_VALUE_LINES && titledSmall.truncated(), "A titled value takes at most three lines");
        check(DisplayText.titled("", "x", DisplayText.Size.AUTO).title() == null, "An empty title is none");
        check(DisplayText.Size.byName("Medium").orElseThrow() == DisplayText.Size.MEDIUM && DisplayText.Size.byName("huge").isEmpty()
                && DisplayText.Size.LARGE.lines() == 2 && DisplayText.Size.LARGE.columns() == 10 && DisplayText.Size.MEDIUM.lines() == 3
                && DisplayText.Size.MEDIUM.columns() == 13 && DisplayText.Size.SMALL.lines() == 4 && DisplayText.Size.SMALL.columns() == 16, "The sizes of D18");
        // Whatever the text, the tag stays within 4 × 32 (the Layout constructor enforces it, so this only has to not throw).
        Random random = new Random(42);
        String alphabet = "ab cd\nEF §x😀\t–.";
        for (int i = 0; i < 2000; i++) {
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(300);
            for (int j = 0; j < length; j++) text.appendCodePoint(alphabet.codePointAt(alphabet.offsetByCodePoints(0, random.nextInt(alphabet.codePointCount(0, alphabet.length())))));
            for (DisplayText.Size size : DisplayText.Size.values()) {
                DisplayText.Layout layout = random.nextBoolean() ? DisplayText.layout(text.toString(), size) : DisplayText.titled(text.toString(), text.toString(), size);
                int lines = layout.lines().size() + (layout.title() == null ? 0 : 1);
                check(lines <= DisplayText.MAX_LINES && layout.size() != DisplayText.Size.AUTO, "Within 4 lines: " + layout);
                for (String line : layout.lines()) check(line.codePointCount(0, line.length()) <= DisplayText.MAX_COLUMNS && !line.contains("\n"), "Within 32: " + line);
            }
        }
    }

    private static int level(ValueMap map, String value, JsonElement document) {
        return map.apply(new JsonPrimitive(value), document == null ? JsonNull.INSTANCE : document).orElseThrow(() -> new AssertionError(map + " maps " + value)).getAsInt();
    }

    private static int rgb(ValueMap map, JsonElement value) {
        JsonObject color = map.apply(value).orElseThrow(() -> new AssertionError("No colour from " + value)).getAsJsonObject();
        return color.get("r").getAsInt() << 16 | color.get("g").getAsInt() << 8 | color.get("b").getAsInt();
    }

    private static JsonObject light(ValueMap map, String document) {
        return map.apply(json(document)).orElseThrow(() -> new AssertionError("No light from " + document)).getAsJsonObject();
    }

    private static String text(ValueMap map, JsonElement value, JsonElement document) {
        Optional<JsonElement> out = map.apply(value, document == null ? JsonNull.INSTANCE : document);
        return out.orElseThrow(() -> new AssertionError(map + " gives no text for " + value)).getAsString();
    }

    // M3-17: the maps read the documents MQTT device presets make of zigbee2mqtt, Tasmota and Shelly payloads.
    private static void presetSamples() {
        ValueMap bool = new ValueMap.Bool(false);
        check(bool.apply(new JsonPrimitive("ON")).orElseThrow().getAsBoolean() && !bool.apply(new JsonPrimitive("OFF")).orElseThrow().getAsBoolean(),
                "zigbee2mqtt's and Tasmota's ON and OFF");
        check(bool.apply(new JsonPrimitive(true)).orElseThrow().getAsBoolean(), "Shelly's output true");
        check(bool.apply(new JsonPrimitive("TOGGLE")).isEmpty(), "A TOGGLE echo holds");
        BindingPreset light = PresetCodec.parse("check:z2m_light", json("{\"kind\":\"light\",\"state_topic\":\"zigbee2mqtt/{device}\",\"state\":\"state\","
                + "\"on\":\"ON\",\"off\":\"OFF\",\"attributes\":{\"brightness\":\"brightness\",\"color\":\"color\",\"color_mode\":\"color_mode\","
                + "\"color_temp\":\"color_temp\"}}")).preset().orElseThrow();
        EntityDocument xy = light.document("zigbee2mqtt/m3bulb", "{\"state\":\"ON\",\"brightness\":200,\"color\":{\"x\":0.62,\"y\":0.366},\"color_mode\":\"xy\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8), false, 0);
        JsonObject lamp = new ValueMap.Light().apply(xy.toJson(), xy.toJson()).orElseThrow().getAsJsonObject();
        check(lamp.get("state").getAsString().equals("ON") && lamp.get("brightness").getAsInt() == 200
                        && Colors.parse(lamp.get("color")).orElseThrow() == rgb(new ValueMap.Color(), json("{\"x\":0.62,\"y\":0.366}")),
                "A zigbee2mqtt light's xy colour reaches the light composite: " + lamp);
        EntityDocument warm = light.document("zigbee2mqtt/m3bulb", "{\"state\":\"ON\",\"brightness\":120,\"color_temp\":370,\"color_mode\":\"color_temp\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8), false, 0);
        JsonObject warmLamp = new ValueMap.Light().apply(warm.toJson(), warm.toJson()).orElseThrow().getAsJsonObject();
        check(warmLamp.get("color_temp_kelvin").getAsInt() == 2703, "Its mireds become kelvin in color_temp mode: " + warmLamp);
        EntityDocument off = light.document("zigbee2mqtt/m3bulb", "{\"state\":\"OFF\",\"brightness\":120}".getBytes(java.nio.charset.StandardCharsets.UTF_8), false, 0);
        check(new ValueMap.Light().apply(off.toJson(), off.toJson()).orElseThrow().getAsJsonObject().get("state").getAsString().equals("OFF"), "Off is off");
        BindingPreset plug = PresetCodec.parse("check:tasmota", json("{\"kind\":\"switch\",\"state_topic\":\"stat/{device}/POWER\",\"on\":\"ON\",\"off\":\"OFF\"}"))
                .preset().orElseThrow();
        ValueMap level = new ValueMap.LevelByBool(false);
        EntityDocument power = plug.document("stat/m3plug/POWER", "ON".getBytes(java.nio.charset.StandardCharsets.UTF_8), true, 0);
        check(level.apply(power.toJson().get("state"), power.toJson()).orElseThrow().getAsInt() == 15, "Tasmota's POWER ON is level 15");
        BindingPreset shelly = PresetCodec.parse("check:shelly", json("{\"kind\":\"switch\",\"state_topic\":\"{device}/status/switch:0\",\"state\":\"output\","
                + "\"on\":true,\"off\":false}")).preset().orElseThrow();
        EntityDocument relay = shelly.document("m3relay/status/switch:0", "{\"id\":0,\"output\":false,\"apower\":0}".getBytes(java.nio.charset.StandardCharsets.UTF_8), false, 0);
        check(level.apply(relay.toJson().get("state"), relay.toJson()).orElseThrow().getAsInt() == 0, "A Shelly relay's output false is level 0");
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

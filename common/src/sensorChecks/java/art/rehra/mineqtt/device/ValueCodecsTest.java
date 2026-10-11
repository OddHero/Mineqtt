package art.rehra.mineqtt.device;

import art.rehra.mineqtt.device.ValueCodecs.LightEffect;
import art.rehra.mineqtt.device.ValueCodecs.LightState;
import art.rehra.mineqtt.device.ValueCodecs.LightTransition;
import art.rehra.mineqtt.device.ValueCodecs.Rgb;
import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.flow.core.Colors;
import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.ha.Ha;
import art.rehra.mineqtt.ha.HaEntity;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Typed values from MQTT payloads, run by Gradle's verifyValueCodecs task: the v1 raw grammars, custom payloads, the
 * Home Assistant JSON light merge, colours and kelvin, the lenient field codecs, number formatting under a German
 * default locale, UTF-8 text, quotes around command text, and the consistency rules of a device type declaration.
 */
public final class ValueCodecsTest {
    private static final ValueCodecs.CustomPayloads NONE = ValueCodecs.CustomPayloads.NONE;

    private enum Mode implements StringRepresentable {
        SWITCH("switch"), LEVEL("level");

        private final String name;

        Mode(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Locale.setDefault(Locale.GERMANY);
        redstoneGrammar();
        switchGrammar();
        lightGrammar();
        lightMerge();
        lightEffects();
        lightTransitions();
        colors();
        fieldCodecs();
        numbersAndText();
        deviceTypes();
        System.out.println("Value codec checks passed.");
    }

    private static void redstoneGrammar() {
        check(power("ON") == 15 && power("true") == 15 && power("1") == 15 && power(" on ") == 15, "ON, TRUE and 1 mean 15");
        check(power("OFF") == 0 && power("False") == 0 && power("0") == 0, "OFF, FALSE and 0 mean 0");
        check(power("7") == 7 && power("15") == 15, "Integers set the level");
        check(power("99") == 15 && power("-3") == 0 && power("99999999999999999999") == 15, "Integers are clamped to 0-15");
        check(redstone("TOGGLE", NONE).equals(json("{\"state\":\"TOGGLE\"}")), "TOGGLE is passed to the device");
        check(redstone("1e999999999", NONE) == null, "Huge exponents mean nothing");
        check(redstone("garbage", NONE) == null && redstone("7.5", NONE) == null && redstone("", NONE) == null, "Garbage means nothing");
        ValueCodecs.CustomPayloads custom = new ValueCodecs.CustomPayloads("open", "ON");
        check(redstone("open", custom).equals(json("{\"power\":15}")), "A custom on payload means 15");
        check(redstone("ON", custom).equals(json("{\"power\":0}")), "Custom payloads win over the grammar");
        check(redstone("Open", custom) == null, "Custom payloads match exactly, as in v1");
    }

    /** v1's plug grammar (M2-18): on, off and toggle only, with the custom payloads first. */
    private static void switchGrammar() {
        check(switchState("ON").equals("ON") && switchState("true").equals("ON") && switchState("1").equals("ON") && switchState(" on ").equals("ON"),
                "ON, TRUE and 1 switch it on");
        check(switchState("OFF").equals("OFF") && switchState("False").equals("OFF") && switchState("0").equals("OFF"), "OFF, FALSE and 0 switch it off");
        check(switchState("TOGGLE").equals("TOGGLE") && switchState("toggle").equals("TOGGLE"), "TOGGLE is passed to the device");
        check(plug("7", NONE) == null && plug("15", NONE) == null && plug("-1", NONE) == null, "A level means nothing to a plug, as in v1");
        check(plug("garbage", NONE) == null && plug("{\"state\":\"ON\"}", NONE) == null && plug("", NONE) == null, "Garbage, JSON and the empty payload mean nothing");
        ValueCodecs.CustomPayloads custom = new ValueCodecs.CustomPayloads("brew", "ON");
        check(plug("brew", custom).equals(json("{\"state\":\"ON\"}")), "A custom on payload switches it on");
        check(plug("ON", custom).equals(json("{\"state\":\"OFF\"}")), "Custom payloads win over the grammar");
        check(plug("Brew", custom) == null, "Custom payloads match exactly, as in v1");
        check(plug("", new ValueCodecs.CustomPayloads("", null)) == null, "An empty payload never commands, also when a custom payload is empty");
    }

    private static void lightGrammar() {
        check(light("ON").equals(json("{\"state\":\"ON\"}")) && light("0").equals(json("{\"state\":\"OFF\"}")), "Plain on/off");
        check(light("toggle").equals(json("{\"state\":\"TOGGLE\"}")), "TOGGLE");
        check(light("{\"state\":\"ON\",\"brightness\":128}").equals(json("{\"state\":\"ON\",\"brightness\":128}")), "HA JSON passes through");
        check(light("{broken") == null && light("[1,2,3]") == null, "Broken or non-object JSON means nothing");
        check(light("#ff8000").equals(json("{\"state\":\"ON\",\"color\":{\"r\":255,\"g\":128,\"b\":0}}")), "#rrggbb turns the lamp on with that colour");
        check(light("rgb(1, 2, 3)").getAsJsonObject("color").equals(json("{\"r\":1,\"g\":2,\"b\":3}")), "rgb(r,g,b)");
        check(light("300,-5,7").getAsJsonObject("color").equals(json("{\"r\":255,\"g\":0,\"b\":7}")), "r,g,b is clamped");
        check(light("light blue").has("color") && light("red").has("color"), "Dye colour names");
        check(light("chartreuse-ish") == null, "Unknown names mean nothing");
        check(light("1e99999999,0,0") == null && light("rgb(1e-99999999,0,0)") == null, "Colours with huge exponents mean nothing");
        check(ValueCodecs.LIGHT.command("party", new ValueCodecs.CustomPayloads("party", null)).orElseThrow().equals(json("{\"state\":\"ON\"}")), "Custom lamp payloads");
    }

    private static void lightMerge() {
        ValueCodecs.LightState off = ValueCodecs.LightState.DEFAULT;
        ValueCodecs.LightState on = off.merge(json("{\"state\":\"ON\",\"brightness\":128}"));
        check(on.on() && on.brightness() == 128 && on.color().equals(ValueCodecs.Rgb.WHITE), "State and brightness, colour kept");
        ValueCodecs.LightState red = on.merge(json("{\"color\":{\"r\":255,\"g\":0,\"b\":0}}"));
        check(red.on() && red.brightness() == 128 && red.color().equals(new ValueCodecs.Rgb(255, 0, 0)) && red.colorMode().equals("rgb"), "A partial colour command keeps the rest");
        ValueCodecs.LightState warm = red.merge(json("{\"color_temp_kelvin\":2700}"));
        check(warm.kelvin() == 2700 && warm.colorMode().equals("color_temp") && warm.color().r() == 255 && warm.color().b() < 200, "Kelvin switches to color_temp mode");
        check(warm.merge(json("{\"color_temp_kelvin\":100000}")).kelvin() == ValueCodecs.MAX_KELVIN, "Kelvin is clamped to the lamp's range");
        check(warm.merge(json("{\"color_temp\":370}")).kelvin() == 2703, "Mireds convert to kelvin");
        check(warm.merge(json("{\"color_temp\":4000}")).kelvin() == 4000, "Home Assistant's color_temp in kelvin (color_temp_kelvin: true) stays kelvin");
        check(warm.merge(json("{\"color_temp\":999}")).kelvin() == ValueCodecs.MIN_KELVIN, "Below 1000 it is mireds, clamped to the mired range");
        check(warm.merge(json("{\"color\":[0,0,255]}")).kelvin() == null, "A colour leaves color_temp mode");
        check(!red.merge(json("{\"state\":\"TOGGLE\"}")).on() && off.merge(json("{\"state\":true}")).on(), "TOGGLE and boolean states");
        ValueCodecs.LightState same = red.merge(json("{\"brightness\":\"lots\",\"color\":\"nope\",\"state\":\"maybe\",\"unknown\":1}"));
        check(same.equals(red), "Unreadable values and unknown keys are ignored");
        check(red.merge(json("{\"brightness\":1e99999999,\"color\":[1e-99999999,0,0],\"color_temp_kelvin\":1e99999999}")).equals(red)
                && red.merge(json("{\"color\":{\"r\":1e99999999}}")).equals(red), "Numbers with huge exponents are ignored, never rounded");
        check(red.merge(json("{\"brightness\":999}")).brightness() == 255 && red.merge(json("{\"brightness\":\"12.6\"}")).brightness() == 13, "Brightness is rounded and clamped");
        check(red.merge(json("{\"color\":{\"h\":120,\"s\":100}}")).color().equals(new ValueCodecs.Rgb(0, 255, 0)), "Hue and saturation");
        ValueCodecs.Rgb xy = red.merge(json("{\"color\":{\"x\":0.64,\"y\":0.33}}")).color();
        check(xy.r() == 255 && xy.g() < 60 && xy.b() < 60, "CIE xy red: " + xy);
    }

    /** M2-25: the light's effect in commands, in its state and on the clients. */
    private static void lightEffects() {
        LightState red = LightState.DEFAULT.merge(json("{\"state\":\"ON\",\"color\":{\"r\":255,\"g\":0,\"b\":0}}"));
        check(red.effect() == LightEffect.NONE, "A light has no effect until a command sets one");
        LightState candle = red.merge(json("{\"effect\":\"candle\"}"));
        check(candle.effect() == LightEffect.CANDLE && candle.on() && candle.color().equals(red.color()), "An effect keeps the rest of the light");
        check(red.merge(json("{\"effect\":\" PULSE \"}")).effect() == LightEffect.PULSE, "Effect names are read without regard to case or spaces");
        check(candle.merge(json("{\"effect\":\"off\"}")).effect() == LightEffect.NONE && candle.merge(json("{\"effect\":\"none\"}")).effect() == LightEffect.NONE,
                "off (Home Assistant's) and none end the effect");
        check(candle.merge(json("{\"effect\":\"disco\"}")).equals(candle) && candle.merge(json("{\"effect\":{\"name\":\"pulse\"}}")).equals(candle),
                "An unknown or unreadable effect is ignored");
        check(candle.merge(json("{\"brightness\":40,\"color\":{\"r\":0,\"g\":0,\"b\":255}}")).effect() == LightEffect.CANDLE
                && candle.merge(json("{\"state\":\"OFF\"}")).effect() == LightEffect.CANDLE, "Other keys, also OFF, keep the effect");
        check(candle.placed().effect() == LightEffect.CANDLE && !candle.placed().on(), "A placed copy keeps the effect, switched off");
        check(LightEffect.names().equals(List.of("off", "pulse", "candle")), "The effect list starts with off: " + LightEffect.names());

        JsonObject state = LightState.MAP_CODEC.codec().encodeStart(JsonOps.INSTANCE, red).getOrThrow().getAsJsonObject();
        check(state.get("effect").getAsString().equals("off"), "The state always names the effect, off when there is none: " + state);
        JsonObject candleState = LightState.MAP_CODEC.codec().encodeStart(JsonOps.INSTANCE, candle).getOrThrow().getAsJsonObject();
        check(candleState.get("effect").getAsString().equals("candle")
                && LightState.MAP_CODEC.codec().parse(JsonOps.INSTANCE, candleState).getOrThrow().equals(candle), "The effect round-trips: " + candleState);
        state.remove("effect");
        check(LightState.MAP_CODEC.codec().parse(JsonOps.INSTANCE, state).getOrThrow().equals(red), "A state saved before effects existed has none");

        check(LightState.commandProblem(json("{\"effect\":\"candle\"}")).isEmpty() && LightState.commandProblem(json("{\"effect\":\"OFF\"}")).isEmpty(),
                "A player may set an effect by its name");
        check(LightState.commandProblem(json("{\"effect\":\"disco\"}")).isPresent() && LightState.commandProblem(json("{\"effect\":3}")).isPresent(),
                "An unknown effect is refused from a player");

        // The clients' animation: a pulse in step on every lamp, a candle on its own.
        check(SmartLamp.effectFactor(LightEffect.NONE, 17, 5) == 1, "No effect keeps the tint");
        float brightest = 1, dimmest = 1;
        for (long time = 0; time < 40; time++) {
            float pulse = SmartLamp.effectFactor(LightEffect.PULSE, time, 1);
            check(pulse == SmartLamp.effectFactor(LightEffect.PULSE, time + 40, 99) && pulse == SmartLamp.effectFactor(LightEffect.PULSE, time, -7),
                    "A pulse repeats every 40 ticks, the same on every lamp");
            brightest = Math.max(brightest, pulse);
            dimmest = Math.min(dimmest, pulse);
        }
        check(SmartLamp.effectFactor(LightEffect.PULSE, 0, 1) == 1 && Math.abs(SmartLamp.effectFactor(LightEffect.PULSE, 20, 1) - 0.3f) < 1e-6
                && brightest == 1 && Math.abs(dimmest - 0.3f) < 1e-6, "A pulse breathes from full down to 30 % and back");
        Set<Float> flickers = new HashSet<>();
        boolean apart = false;
        for (long time = 0; time < 200; time++) {
            float flame = SmartLamp.effectFactor(LightEffect.CANDLE, time, 42);
            check(flame >= 0.5f && flame <= 1 && flame == SmartLamp.effectFactor(LightEffect.CANDLE, time, 42), "A candle flickers within 50-100 %, the same way each time: " + flame);
            check(Math.abs(flame * 10 - Math.round(flame * 10)) < 1e-4, "In steps of a tenth, so a client redraws only when it moves a step: " + flame);
            flickers.add(flame);
            apart |= flame != SmartLamp.effectFactor(LightEffect.CANDLE, time, 43);
        }
        check(flickers.size() >= 3, "A candle flickers: " + flickers);
        check(apart, "Two candles flicker each their own way");
    }

    /** M2-25: a command's transition, and the way a light fades to its new state. */
    private static void lightTransitions() {
        check(ValueCodecs.transitionTicks(json("{\"transition\":2}")) == 40 && ValueCodecs.transitionTicks(json("{\"transition\":0.5}")) == 10
                && ValueCodecs.transitionTicks(json("{\"transition\":\"1.5\"}")) == 30, "Seconds, also fractions and text, are ticks");
        check(ValueCodecs.transitionTicks(json("{\"state\":\"ON\"}")) == 0 && ValueCodecs.transitionTicks(json("{\"transition\":-3}")) == 0
                && ValueCodecs.transitionTicks(json("{\"transition\":\"soon\"}")) == 0 && ValueCodecs.transitionTicks(json("{\"transition\":true}")) == 0
                && ValueCodecs.transitionTicks(json("{\"transition\":1e99999999}")) == 0, "None, negative or unreadable: at once");
        check(ValueCodecs.transitionTicks(json("{\"transition\":99999}")) == ValueCodecs.MAX_TRANSITION_SECONDS * 20, "At most an hour");
        check(LightState.commandProblem(json("{\"state\":\"ON\",\"transition\":2.5}")).isEmpty()
                && LightState.commandProblem(json("{\"transition\":3601}")).isPresent() && LightState.commandProblem(json("{\"transition\":\"x\"}")).isPresent(),
                "A player's transition is seconds up to an hour");
        LightState off = LightState.DEFAULT.merge(json("{\"color\":{\"r\":255,\"g\":0,\"b\":0}}"));
        check(off.merge(json("{\"transition\":2}")).equals(off), "A transition alone changes no state");

        LightState on = off.merge(json("{\"state\":\"ON\",\"brightness\":200,\"effect\":\"pulse\"}"));
        LightState start = off.towards(on, 0);
        check(!start.on() && start.brightness() == 0 && start.effect() == LightEffect.PULSE, "Switching on starts dark, with the new effect at once: " + start);
        LightState half = off.towards(on, 0.5);
        check(half.on() && half.brightness() == 100 && half.color().equals(on.color()), "Half way up, in the new colour: " + half);
        check(off.towards(on, 1).equals(on) && off.towards(on, 7).equals(on) && off.towards(on, -1).equals(start), "The end is the new state");
        LightState down = on.towards(off, 0.75);
        check(down.on() && down.brightness() == 50 && down.color().equals(on.color()), "Switching off fades down in the old colour: " + down);
        LightState blue = on.merge(json("{\"color\":{\"r\":0,\"g\":0,\"b\":255}}"));
        check(on.towards(blue, 0.5).color().equals(new Rgb(128, 0, 128)) && on.towards(blue, 0.5).brightness() == 200, "The colour moves at the same brightness");
        LightState warm = on.merge(json("{\"color_temp_kelvin\":2700}"));
        LightState cold = warm.merge(json("{\"color_temp_kelvin\":6500}"));
        LightState between = warm.towards(cold, 0.5);
        check(between.kelvin() == 4600 && between.color().equals(ValueCodecs.kelvinToRgb(4600)), "Between two temperatures the temperature moves: " + between);
        check(on.towards(warm, 0.5).kelvin() == null, "From a colour to a temperature the colour moves");
        LightState offBlue = off.merge(json("{\"color\":{\"r\":0,\"g\":0,\"b\":255}}"));
        check(off.towards(offBlue, 0).equals(offBlue) && off.towards(offBlue, 0.5).equals(offBlue),
                "Off to off shows the new colour from the start, no fade back to the old one");

        // HA's {"state":"ON","brightness":200,"transition":2}: the block light rises over 40 ticks, a step every 2.
        LightTransition fade = new LightTransition(off, on, 1000, ValueCodecs.transitionTicks(json("{\"transition\":2}")));
        int level = -1;
        for (long time = 1000; time <= 1040; time += SmartLamp.TRANSITION_STEP_TICKS) {
            int next = SmartLamp.lightLevel(fade.at(time));
            check(next >= level, "The light level never falls on the way up: " + level + " -> " + next + " at " + time);
            check(fade.done(time) == (time == 1040), "Done after 40 ticks, not before");
            level = next;
        }
        check(SmartLamp.lightLevel(fade.at(1000)) == 0 && SmartLamp.lightLevel(fade.at(1020)) == 6 && level == 12 && fade.at(1040).equals(on),
                "From dark through 6 to 12, ending on the state itself");
        try {
            new LightTransition(off, on, 0, 0);
            throw new AssertionError("A transition of no ticks is refused");
        } catch (IllegalArgumentException expected) {
            // A command without a transition applies at once instead.
        }
    }

    private static void colors() {
        check(ValueCodecs.color("#FF8040").orElseThrow().packed() == 0xFF8040, "#RRGGBB is case-insensitive");
        check(ValueCodecs.color("#ff80").isEmpty() && ValueCodecs.color("rgb(1,2)").isEmpty(), "Malformed colours");
        check(ValueCodecs.miredsToKelvin(153) == ValueCodecs.MAX_KELVIN && ValueCodecs.miredsToKelvin(500) == 2000 && ValueCodecs.miredsToKelvin(10) == ValueCodecs.MAX_KELVIN,
                "Mireds are clamped to the v1 range");
        ValueCodecs.Rgb candle = ValueCodecs.kelvinToRgb(2000);
        ValueCodecs.Rgb daylight = ValueCodecs.kelvinToRgb(6500);
        check(candle.r() == 255 && candle.b() < daylight.b() && daylight.b() > 240, "Kelvin to colour: " + candle + " " + daylight);
        check(ValueCodecs.Rgb.ofPacked(0x123456).equals(new ValueCodecs.Rgb(0x12, 0x34, 0x56)), "Packed colours");
        // flow.core cannot see DyeColor, so its dye table is a copy that must stay equal to the game's (text colours are opaque ARGB).
        Map<String, Integer> dyes = new LinkedHashMap<>();
        for (DyeColor dye : DyeColor.values()) dyes.put(dye.getName(), dye.getTextColor() & 0xFFFFFF);
        check(new ArrayList<>(Colors.dyes().entrySet()).equals(new ArrayList<>(dyes.entrySet())), "Colors' dye table is DyeColor's text colours in id order: " + dyes);
        check(ValueCodecs.color("light blue").orElseThrow().packed() == (DyeColor.LIGHT_BLUE.getTextColor() & 0xFFFFFF), "Dye names keep their colour");
    }

    private static void fieldCodecs() {
        Codec<Integer> level = ValueCodecs.intRange(0, 15);
        check(decode(level, new JsonPrimitive(7)) == 7 && decode(level, new JsonPrimitive(42)) == 15 && decode(level, new JsonPrimitive(-1)) == 0, "Integers are clamped");
        check(decode(level, new JsonPrimitive("12")) == 12 && decode(level, new JsonPrimitive(6.5)) == 7, "Numeric strings and decimals are read");
        check(fails(level, new JsonPrimitive("NaN")) && fails(level, new JsonPrimitive("12,5")) && fails(level, new JsonPrimitive(true)) && fails(level, json("{}")),
                "NaN, locale decimals, booleans and objects are errors");
        check(level.parse(NbtOps.INSTANCE, StringTag.valueOf("9")).getOrThrow() == 9, "The same codec reads NBT");
        check(fails(level, ValueCodecs.plainValue("1e99999999")) && fails(level, new JsonPrimitive("1e-99999999")),
                "Numbers with huge exponents are errors, never rounded");
        Field<Integer> power = Field.intRange("power", 0, 15, Access.READ_WRITE);
        check(power.parse(JsonParser.parseString("1e999999999")).isEmpty() && power.parse(new JsonPrimitive(9)).orElseThrow() == 9,
                "A JSON number beyond Gson's limits is unreadable, not an exception");
        check(new CommandReader(json("{\"power\":1e999999999}")).get(power).isEmpty(), "A command reads such a number as absent");

        check(decode(ValueCodecs.BOOL, new JsonPrimitive("ON")) && !decode(ValueCodecs.BOOL, new JsonPrimitive(0)) && decode(ValueCodecs.BOOL, new JsonPrimitive(true)),
                "Booleans from ON/OFF, 1/0 and true/false");
        check(fails(ValueCodecs.BOOL, new JsonPrimitive("maybe")) && fails(ValueCodecs.BOOL, new JsonPrimitive(2)), "Other values are no booleans");
        Tag stored = ValueCodecs.BOOL.encodeStart(NbtOps.INSTANCE, true).getOrThrow();
        check(stored.equals(ByteTag.valueOf(true)) && ValueCodecs.BOOL.parse(NbtOps.INSTANCE, stored).getOrThrow(), "Booleans round-trip through NBT");

        Codec<Mode> mode = ValueCodecs.enumOf(Mode.class);
        check(decode(mode, new JsonPrimitive("LEVEL")) == Mode.LEVEL && fails(mode, new JsonPrimitive("turbo")), "Enums by serialized name, case-insensitive");
        check(mode.encodeStart(JsonOps.INSTANCE, Mode.SWITCH).getOrThrow().getAsString().equals("switch"), "Enums are written by serialized name");

        check(decode(ValueCodecs.TEXT, new JsonPrimitive("§cRed\u0000 Grüße\n")).equals("Red Grüße"), "Formatting codes and control characters are stripped");
        check(decode(ValueCodecs.TEXT, new JsonPrimitive("x".repeat(300))).length() == ValueCodecs.MAX_TEXT_LENGTH, "Text is capped");
        check(decode(ValueCodecs.TEXT, new JsonPrimitive(12)).equals("12"), "Scalars read as text");

        JsonElement location = CommonFields.LOCATION.codec().encodeStart(JsonOps.INSTANCE, GlobalPos.of(Level.OVERWORLD, new BlockPos(120, 64, -35))).getOrThrow();
        check(location.equals(json("{\"dimension\":\"minecraft:overworld\",\"pos\":[120,64,-35]}")), "Location as in the state examples: " + location);
    }

    private static void numbersAndText() {
        check(Locale.getDefault().equals(Locale.GERMANY), "The check runs under a German default locale");
        check(ValueCodecs.format(21.45, 1).equals("21.5") && ValueCodecs.format(3, 2).equals("3.00"), "Locale.ROOT formatting: " + ValueCodecs.format(21.45, 1));
        check(ValueCodecs.number("21.5").orElseThrow().doubleValue() == 21.5 && ValueCodecs.number("21,5").isEmpty(), "Locale.ROOT parsing");
        check(ValueCodecs.integer("1e3").orElseThrow().intValue() == 1000 && ValueCodecs.integer("7.0").isEmpty(), "Whole numbers only");
        check(ValueCodecs.number("1e999999999").isEmpty() && ValueCodecs.number("-1e-999999999").isEmpty() && ValueCodecs.number("0E-999999999").isEmpty()
                && ValueCodecs.number("1".repeat(41)).isEmpty() && ValueCodecs.number("1e40").isPresent() && ValueCodecs.number("0.30000000000000004").isPresent(),
                "Numbers are bounded in digits and exponent");
        check(ValueCodecs.plainValue("1e999999999").equals(new JsonPrimitive("1e999999999")), "A plain number out of bounds stays text");
        check(ValueCodecs.plainValue(" TOGGLE ").equals(new JsonPrimitive("TOGGLE")), "Plain text stays text");
        check(ValueCodecs.plainValue("7").getAsInt() == 7 && ValueCodecs.plainValue("false").equals(new JsonPrimitive(false)), "Plain numbers and booleans");
        check(ValueCodecs.plainValue("{\"r\":1}").isJsonObject() && ValueCodecs.plainValue("{nope").getAsString().equals("{nope"), "Plain JSON, strictly");
        check(ValueCodecs.unquote("\"Living Room Lamp\"").equals("Living Room Lamp"), "One pair of double quotes is stripped");
        check(ValueCodecs.unquote("'a'").equals("a"), "One pair of single quotes is stripped");
        check(ValueCodecs.unquote("\"a").equals("\"a") && ValueCodecs.unquote("a'").equals("a'"), "An unmatched quote stays");
        check(ValueCodecs.unquote("\"a'").equals("\"a'") && ValueCodecs.unquote("'a\"").equals("'a\""), "Different quote characters are no pair");
        check(ValueCodecs.unquote("\"\"").isEmpty() && ValueCodecs.unquote("''").isEmpty(), "An empty quoted text is empty");
        check(ValueCodecs.unquote("\"").equals("\"") && ValueCodecs.unquote("").isEmpty(), "A lone quote is no pair");
        check(ValueCodecs.unquote("\"Say \"hi\"\"").equals("Say \"hi\"") && ValueCodecs.unquote("Bob's 'lamp'").equals("Bob's 'lamp'"),
                "Quotes inside stay, and only the outer pair goes");
        check(ValueCodecs.unquote("\"\"a\"\"").equals("\"a\""), "Only one pair is stripped");
        String umlauts = "Küche ☀ 東京";
        check(new String(ValueCodecs.utf8(umlauts), StandardCharsets.UTF_8).equals(umlauts) && ValueCodecs.utf8(umlauts).length == 17, "Text is UTF-8");
    }

    private static void deviceTypes() {
        Field<Integer> power = Field.intRange("power", 0, 15, Access.READ_WRITE);
        Field<Mode> modeField = Field.enumOf("mode", Mode.class, Access.READ_WRITE);
        Field<Boolean> powered = Field.bool("powered", Access.READ);
        DeviceType output = DeviceType.builder(id("output")).model("Redstone Output").groupable(true)
                .field(power).field(modeField).field(CommonFields.LOCATION)
                .entity(Ha.switchOf("output", power).on(15).off(0).name(null))
                .entity(Ha.number("power_level", power).range(0, 15).slider())
                .entity(Ha.select("mode", modeField).config())
                .entity(Ha.sensor("location", CommonFields.LOCATION).diagnostic().disabledByDefault())
                .action(Action.state())
                .rawLink(RawLinkSpec.in(ValueCodecs.REDSTONE).acceptRetained(true))
                .build();
        HaEntity.Select select = (HaEntity.Select) output.entities().get(2);
        check(select.options().equals(List.of("switch", "level")) && select.meta().category() == HaEntity.Category.CONFIG, "Select options from the enum");
        check(output.entities().getFirst().meta().name() == null && output.entities().get(1).meta().name().equals("Power level"), "Entity names");
        check(output.defaultSlug(UUID.fromString("3f9c0000-0000-4000-8000-000000000000")).equals("output_3f9c"), "Default slug");
        check(output.defaultName(UUID.fromString("3f9c0000-0000-4000-8000-000000000000")).equals("Redstone Output 3F9C"), "Default name");

        JsonObject state = new StateWriter().put(power, 7).put(modeField, Mode.LEVEL).put(CommonFields.LOCATION, GlobalPos.of(Level.NETHER, BlockPos.ZERO)).json();
        check(state.get("power").getAsInt() == 7 && state.get("mode").getAsString().equals("level"), "State through the field codecs");
        check(output.writableState(state).keySet().equals(Set.of("power", "mode")), "The writable part of a state");
        JsonObject preview = output.preview(state, json("{\"power\":\"99\",\"mode\":\"bogus\",\"location\":\"x\"}"));
        check(preview.get("power").getAsInt() == 15 && preview.get("mode").getAsString().equals("level") && preview.has("location"),
                "The default preview sets writable fields through their codecs and ignores the rest: " + preview);
        check(state.get("power").getAsInt() == 7, "A preview never changes the state it starts from");
        CommandReader reader = new CommandReader(json("{\"power\":3,\"state\":\"ON\",\"location\":{}}"));
        check(reader.get(power).orElseThrow() == 3 && reader.text("state").orElseThrow().equals("ON") && reader.get(CommonFields.LOCATION).isEmpty(),
                "Commands read writable fields only");

        Field<ValueCodecs.Rgb> color = Field.inline("color", RGB_MAP, ValueCodecs.Rgb.WHITE, Access.READ_WRITE, PortType.COLOR);
        JsonObject inline = new StateWriter().put(color, new ValueCodecs.Rgb(1, 2, 3)).json();
        check(inline.equals(json("{\"r\":1,\"g\":2,\"b\":3}")), "Inline fields write their keys at the top level");
        check(new CommandReader(json("{\"r\":9,\"g\":8,\"b\":7,\"x\":1}")).get(color).orElseThrow().equals(new ValueCodecs.Rgb(9, 8, 7)), "Inline fields read the whole command");

        failsToBuild(DeviceType.builder(id("a")).model("A").field(power).entity(Ha.number("n", Field.intRange("other", 0, 1, Access.READ_WRITE))), "An entity over an undeclared field");
        failsToBuild(DeviceType.builder(id("b")).model("B").field(powered).entity(Ha.switchOf("s", powered).on(true).off(false)), "A command entity over a read-only field");
        failsToBuild(DeviceType.builder(id("c")).model("C").field(power).entity(Ha.sensor("p", power)), "A writable field nothing commands");
        failsToBuild(DeviceType.builder(id("d")).model("D").field(power).field(power).entity(Ha.number("p", power)), "A field declared twice");
        failsToBuild(DeviceType.builder(id("e")).model("E").field(power).entity(Ha.switchOf("s", power)), "A switch without on/off values");
        failsToBuild(DeviceType.builder(id("f")).model("F").unloaded(UnloadedPolicy.UNAVAILABLE).groupable(true).field(power).entity(Ha.number("p", power)),
                "A groupable sensor");
        failsToBuild(DeviceType.builder(id("g")).field(power).entity(Ha.number("p", power)), "A type without a model");
        DeviceType sensor = DeviceType.builder(id("sensor")).model("Sensor").unloaded(UnloadedPolicy.UNAVAILABLE)
                .field(powered).entity(Ha.binarySensor("powered", powered).deviceClass("power")).build();
        check(sensor.deadbands().isEmpty() && sensor.rawLink().isEmpty() && !sensor.groupable(), "A minimal sensor");
    }

    private static final MapCodec<ValueCodecs.Rgb> RGB_MAP = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ValueCodecs.intRange(0, 255).fieldOf("r").forGetter(ValueCodecs.Rgb::r),
            ValueCodecs.intRange(0, 255).fieldOf("g").forGetter(ValueCodecs.Rgb::g),
            ValueCodecs.intRange(0, 255).fieldOf("b").forGetter(ValueCodecs.Rgb::b)
    ).apply(instance, ValueCodecs.Rgb::new));

    private static int power(String payload) {
        return redstone(payload, NONE).get("power").getAsInt();
    }

    private static JsonObject redstone(String payload, ValueCodecs.CustomPayloads custom) {
        return ValueCodecs.REDSTONE.command(payload, custom).orElse(null);
    }

    private static String switchState(String payload) {
        return plug(payload, NONE).get("state").getAsString();
    }

    private static JsonObject plug(String payload, ValueCodecs.CustomPayloads custom) {
        return ValueCodecs.SWITCH.command(payload, custom).orElse(null);
    }

    private static JsonObject light(String payload) {
        return ValueCodecs.LIGHT.command(payload, NONE).orElse(null);
    }

    private static <T> T decode(Codec<T> codec, JsonElement value) {
        return codec.parse(JsonOps.INSTANCE, value).getOrThrow();
    }

    private static boolean fails(Codec<?> codec, JsonElement value) {
        return codec.parse(JsonOps.INSTANCE, value).isError();
    }

    private static void failsToBuild(DeviceType.Builder builder, String message) {
        try {
            builder.build();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError(message + " must not build");
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("mineqtt", path);
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

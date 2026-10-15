package art.rehra.mineqtt.twin.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@link ValuePath} (M3 plan D7), run by Gradle's verifyValuePath task: the grammar and its error messages, the depth and
 * length caps, the canonical text, selection over the captured statestream values (cap §9.6 {@code a_dict},
 * {@code a_list}, a forecast list), missing versus {@code null}, the statestream leaf of a path, and raw payloads (strict
 * JSON, plain text, the 64 KiB cap, invalid UTF-8, deep nesting).
 */
public final class ValuePathCheck {
    public static void main(String[] args) {
        Locale.setDefault(Locale.GERMANY);
        grammar();
        refused();
        caps();
        canonical();
        select();
        entityDocument();
        leaves();
        payloads();
        presetPayloads();
        System.out.println("Value path checks passed.");
    }

    // Every production of the grammar, alone and combined.
    private static void grammar() {
        check(ValuePath.parse("").whole() && ValuePath.parse("   ").equals(ValuePath.WHOLE), "\"\" is the whole payload, spaces around ignored");
        check(steps("state").equals(List.of(new ValuePath.Key("state"))), "A segment");
        check(steps("a_dict.nested.b").equals(List.of(key("a_dict"), key("nested"), key("b"))), "Dotted segments");
        check(steps("forecast[0].temperature").equals(List.of(key("forecast"), new ValuePath.Index(0), key("temperature"))), "An index then a segment");
        check(steps("a[0][12]").equals(List.of(key("a"), new ValuePath.Index(0), new ValuePath.Index(12))), "Indexes in a row");
        check(steps("attributes[\"unit.of measurement\"]").equals(List.of(key("attributes"), key("unit.of measurement"))), "A quoted key with a dot and a space");
        check(steps("a[\"x\\\"y\"]").equals(List.of(key("a"), key("x\"y"))) && steps("a[\"b\\\\s\"]").equals(List.of(key("a"), key("b\\s"))),
                "Escaped quote and backslash in a quoted key");
        check(steps("a[\"]\"]").equals(List.of(key("a"), key("]"))) && steps("a[\"\"]").equals(List.of(key("a"), key(""))), "A bracket and the empty key, quoted");
        check(steps("[0].name").equals(List.of(new ValuePath.Index(0), key("name"))) && steps("[\"k\"]").equals(List.of(key("k"))),
                "A path may start with an index or a quoted key (a payload that is a list)");
        check(steps("temperatur_außen.wert-2").equals(List.of(key("temperatur_außen"), key("wert-2"))), "Letters beyond ASCII, digits, _ and - in a segment");
        check(steps("a.0").equals(List.of(key("a"), key("0"))), "A digit segment is a key, not an index");
    }

    private static void refused() {
        for (String text : List.of("a.", ".a", "a..b", "a[", "a[]", "a[01]", "a[-1]", "a[x]", "a[\"x]", "a[\"x\"", "a[\"x\\q\"]", "a[\"x\ny\"]",
                "a b", "a.[0]", "a]", "a[0]b", "a.\"b\"", "a[0", "€uro$", "a.b!")) {
            Optional<String> problem = ValuePath.problem(text);
            check(problem.isPresent(), "Refused: " + text);
            check(problem.get().startsWith("The path \"" + text.strip() + "\""), "The message names the path: " + problem.get());
        }
        check(ValuePath.problem("a[x]").orElseThrow().contains("at 3"), "The message gives the position: " + ValuePath.problem("a[x]").orElseThrow());
        check(ValuePath.problem("a[\"x\\q\"]").orElseThrow().contains("escape"), "An unknown escape is named");
        check(ValuePath.problem("forecast[0].temperature").isEmpty(), "problem is empty for a path");
        try {
            ValuePath.parse("a..b");
            throw new AssertionError("parse throws for a malformed path");
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    private static void caps() {
        String sixteen = String.join(".", Collections.nCopies(ValuePath.MAX_DEPTH, "a"));
        check(ValuePath.parse(sixteen).steps().size() == 16, "16 steps are allowed");
        check(ValuePath.problem(sixteen + ".a").orElseThrow().contains("more than 16 steps"), "17 steps are refused");
        check(ValuePath.problem(sixteen + "[0]").isPresent(), "An index counts as a step");
        String longKey = "k".repeat(ValuePath.MAX_LENGTH);
        check(ValuePath.parse(longKey).steps().size() == 1, "256 characters are allowed");
        check(ValuePath.problem(longKey + "k").orElseThrow().contains("longer than 256"), "257 characters are refused");
        check(ValuePath.parse("a[999999999]").steps().get(1).equals(new ValuePath.Index(999_999_999)) && ValuePath.problem("a[1000000000]").isPresent(),
                "Indexes have at most nine digits, so they never overflow an int");
        check(ValuePath.keys("attributes", "unit_of_measurement").equals(ValuePath.parse("attributes.unit_of_measurement")), "keys() builds a path");
        expectThrows(() -> ValuePath.keys(sixteen.split("\\.")).steps().size(), false, "keys() takes 16 steps");
        expectThrows(() -> ValuePath.keys((sixteen + ".a").split("\\.")), true, "keys() refuses 17 steps");
        expectThrows(() -> ValuePath.keys("x".repeat(300)), true, "keys() refuses an overlong path");
        expectThrows(() -> new ValuePath.Index(-1), true, "A negative index is refused");
    }

    // toString is canonical and reads back to an equal path.
    private static void canonical() {
        for (String text : List.of("", "state", "a_dict.nested.b", "forecast[0].temperature", "a[\"x.y\"].b", "[0]", "[\"k k\"][2]", "a[\"x\\\"y\\\\z\"]", "a[\"\"]")) {
            ValuePath path = ValuePath.parse(text);
            check(path.toString().equals(text), "Canonical: " + text + " -> " + path);
            check(ValuePath.parse(path.toString()).equals(path) && ValuePath.parse(path.toString()).hashCode() == path.hashCode(), "Round trip: " + text);
        }
        check(ValuePath.parse("a[\"b\"]").toString().equals("a.b") && ValuePath.parse("[\"b\"].c").toString().equals("b.c"),
                "A quoted key that may be bare is written bare");
        check(!ValuePath.parse("a.b").equals(ValuePath.parse("a[\"b.c\"]")), "Different paths differ");
    }

    // Selection over captured attribute values (cap §9.6, §9.10) and a weather forecast list.
    private static void select() {
        JsonElement document = json("{\"a_dict\":{\"k\":1,\"nested\":{\"b\":true}},\"a_list\":[1,2.5,\"x\"],\"a_none\":null,"
                + "\"obj_attr\":{\"a\":[1,2],\"b\":null},\"forecast\":[{\"temperature\":12.5,\"condition\":\"rainy\"},{\"temperature\":14}],"
                + "\"unit.of\":\"°C\"}");
        check(at(document, "a_dict.nested.b").equals(new JsonPrimitive(true)), "a_dict.nested.b");
        check(at(document, "forecast[0].temperature").equals(new JsonPrimitive(12.5)), "forecast[0].temperature");
        check(at(document, "forecast[1].temperature").getAsInt() == 14, "forecast[1].temperature");
        check(at(document, "a_list[2]").getAsString().equals("x") && at(document, "obj_attr.a[1]").getAsInt() == 2, "Indexes into lists");
        check(at(document, "[\"unit.of\"]").getAsString().equals("°C"), "A quoted key with a dot");
        check(at(document, "").equals(document), "\"\" selects the whole payload");
        check(ValuePath.parse("a_none").select(document).equals(Optional.of(JsonNull.INSTANCE))
                        && ValuePath.parse("obj_attr.b").select(document).equals(Optional.of(JsonNull.INSTANCE)),
                "A null at the end is present, as JsonNull");
        for (String missing : List.of("nope", "a_dict.nope", "forecast[2]", "forecast[0].nope", "a_list.k", "a_dict[0]", "a_none.k", "a_none[0]",
                "a_dict.k.x", "a_list[2].x", "[0]")) {
            check(ValuePath.parse(missing).select(document).isEmpty(), "Missing: " + missing);
        }
        check(ValuePath.parse("[1]").select(json("[\"a\",\"b\"]")).orElseThrow().getAsString().equals("b"), "A payload that is a list");
        check(ValuePath.parse("a").select(new JsonPrimitive("on")).isEmpty() && ValuePath.WHOLE.select(new JsonPrimitive("on")).orElseThrow().getAsString().equals("on"),
                "A scalar has no keys, but is the whole payload");
    }

    // A Home Assistant entity document as the twin folds it (cap §9.2), and the paths the adapters use on it.
    private static void entityDocument() {
        JsonElement light = json("{\"state\":\"on\",\"last_updated\":\"2026-10-10T16:58:44.639470+00:00\",\"last_changed\":\"2026-10-10T16:58:44.639470+00:00\","
                + "\"attributes\":{\"color_mode\":\"hs\",\"brightness\":200,\"color_temp_kelvin\":null,\"hs_color\":[26.939,96.078],"
                + "\"rgb_color\":[255,120,10],\"xy_color\":[0.62,0.366],\"effect_list\":[\"rainbow\",\"off\"],\"friendly_name\":\"Bed Light\"}}");
        check(at(light, "state").getAsString().equals("on"), "state is the raw string");
        check(at(light, "attributes.brightness").getAsInt() == 200 && at(light, "attributes.rgb_color[1]").getAsInt() == 120, "Attributes, also into a list");
        check(at(light, "attributes.color_temp_kelvin").isJsonNull(), "A null attribute is present");
        check(at(light, "attributes.effect_list[0]").getAsString().equals("rainbow"), "A list attribute's element");
        check(at(light, "last_updated").getAsString().startsWith("2026-10-10T16:58:44"), "A timestamp leaf");
        check(ValuePath.parse("friendly_name").select(light).isEmpty(), "Attributes sit under attributes, not at the top");
    }

    private static void leaves() {
        check(leaf("state").equals(Optional.of("state")) && leaf("last_updated").equals(Optional.of("last_updated"))
                && leaf("last_changed").equals(Optional.of("last_changed")), "state and the timestamps are their own leaves");
        check(leaf("attributes.brightness").equals(Optional.of("brightness")), "attributes.x reads the leaf x");
        check(leaf("attributes.forecast[0].temperature").equals(Optional.of("forecast")), "Deeper paths read the attribute's leaf");
        check(leaf("attributes[\"unit_of_measurement\"]").equals(Optional.of("unit_of_measurement")), "A quoted attribute key");
        check(leaf("state[0]").equals(Optional.of("state")), "Steps after the leaf do not change it");
        for (String none : List.of("", "attributes", "attributes[0]", "friendly_name", "[0]", "[1].state", "attributes[\"\"]",
                "attributes[\"a/b\"]", "attributes[\"x+\"]", "attributes[\"#\"]", "attributes[\"#\"].x")) {
            check(leaf(none).isEmpty(), "No single leaf: " + none);
        }
        check(leaf("attributes.state").equals(Optional.of("state")), "An attribute named like a reserved leaf maps to that topic (D1.4, documented)");
    }

    private static void payloads() {
        check(payload("{\"state\":\"ON\",\"brightness\":254}").orElseThrow().isJsonObject(), "A JSON object");
        check(payload("ON").orElseThrow().equals(new JsonPrimitive("ON")), "Plain text is a string (Tasmota)");
        check(payload("21.5").orElseThrow().getAsJsonPrimitive().isNumber() && payload("true").orElseThrow().getAsBoolean(), "JSON scalars");
        check(payload("\"007\"").orElseThrow().getAsString().equals("007"), "A JSON string keeps its digits");
        check(payload("NaN").orElseThrow().equals(new JsonPrimitive("NaN")) && payload("{\"a\":1} trailing").orElseThrow().isJsonPrimitive(),
                "Strict JSON: NaN and trailing text are plain strings");
        check(payload("Grüße \"quoted\" / slash").orElseThrow().getAsString().equals("Grüße \"quoted\" / slash"), "UTF-8 text stays raw");
        check(ValuePath.payload(new byte[0]).isEmpty(), "A zero-length payload is a cleared retained message, not a value");
        byte[] max = new byte[ValuePath.MAX_PAYLOAD_BYTES];
        Arrays.fill(max, (byte) 'a');
        check(ValuePath.payload(max).isPresent(), "64 KiB is read");
        check(ValuePath.payload(Arrays.copyOf(max, max.length + 1)).isEmpty(), "One byte more is refused");
        check(ValuePath.payload(new byte[]{'o', (byte) 0xC3, 'n'}).isEmpty(), "Invalid UTF-8 is refused");
        String deep = "[".repeat(1000) + "]".repeat(1000);
        check(payload(deep).orElseThrow().isJsonPrimitive(), "JSON nested past Gson's limit is plain text, never an exception");
        JsonElement z2m = payload("{\"state\":\"ON\",\"brightness\":254,\"color\":{\"x\":0.3,\"y\":0.3},\"linkquality\":120}").orElseThrow();
        check(ValuePath.parse("color.x").select(z2m).orElseThrow().getAsDouble() == 0.3, "A zigbee2mqtt state path");
        check(ValuePath.parse("color.x").read("{\"color\":{\"x\":0.5}}".getBytes(StandardCharsets.UTF_8)).orElseThrow().getAsDouble() == 0.5
                && ValuePath.WHOLE.read("ON".getBytes(StandardCharsets.UTF_8)).orElseThrow().getAsString().equals("ON")
                && ValuePath.parse("a").read("ON".getBytes(StandardCharsets.UTF_8)).isEmpty(), "read() is payload then select");
    }

    // M3-17: the payloads the built-in presets read, as zigbee2mqtt, Tasmota, Shelly Gen2 and ESPHome publish them.
    private static void presetPayloads() {
        JsonElement sensor = payload("{\"temperature\":21.5,\"humidity\":48.2,\"battery\":100,\"linkquality\":87}").orElseThrow();
        check(ValuePath.parse("temperature").select(sensor).orElseThrow().getAsDouble() == 21.5
                && ValuePath.parse("humidity").select(sensor).orElseThrow().getAsDouble() == 48.2, "A zigbee2mqtt sensor's readings");
        JsonElement contact = payload("{\"contact\":false,\"battery\":91}").orElseThrow();
        check(!ValuePath.parse("contact").select(contact).orElseThrow().getAsBoolean(), "A zigbee2mqtt contact's boolean");
        check(ValuePath.WHOLE.read("ON".getBytes(StandardCharsets.UTF_8)).orElseThrow().getAsString().equals("ON")
                && ValuePath.WHOLE.read("OFF".getBytes(StandardCharsets.UTF_8)).orElseThrow().getAsString().equals("OFF"), "Tasmota's stat/<device>/POWER is plain text");
        JsonElement tasmota = payload("{\"Time\":\"2026-10-11T08:00:00\",\"POWER\":\"ON\",\"Wifi\":{\"RSSI\":76}}").orElseThrow();
        check(ValuePath.parse("POWER").select(tasmota).orElseThrow().getAsString().equals("ON") && ValuePath.parse("Wifi.RSSI").select(tasmota).orElseThrow().getAsInt() == 76,
                "Tasmota's tele/<device>/STATE");
        JsonElement shelly = payload("{\"id\":0,\"source\":\"button\",\"output\":true,\"apower\":12.5,\"voltage\":230.1,"
                + "\"aenergy\":{\"total\":3.2,\"by_minute\":[0.0,0.1,0.2]},\"temperature\":{\"tC\":41.2,\"tF\":106.2}}").orElseThrow();
        check(ValuePath.parse("output").select(shelly).orElseThrow().getAsBoolean() && ValuePath.parse("temperature.tC").select(shelly).orElseThrow().getAsDouble() == 41.2
                && ValuePath.parse("aenergy.by_minute[2]").select(shelly).orElseThrow().getAsDouble() == 0.2, "A Shelly Gen2 switch status");
        JsonElement esphome = payload("{\"state\":\"ON\",\"color_mode\":\"rgb\",\"brightness\":128,\"color\":{\"r\":255,\"g\":0,\"b\":0}}").orElseThrow();
        check(ValuePath.parse("color.r").select(esphome).orElseThrow().getAsInt() == 255, "An ESPHome light's JSON state");
        check(ValuePath.WHOLE.read("21.5".getBytes(StandardCharsets.UTF_8)).orElseThrow().getAsDouble() == 21.5, "An ESPHome sensor's plain number");
    }

    private static List<ValuePath.Step> steps(String text) {
        return ValuePath.parse(text).steps();
    }

    private static ValuePath.Key key(String name) {
        return new ValuePath.Key(name);
    }

    private static JsonElement at(JsonElement document, String path) {
        return ValuePath.parse(path).select(document).orElseThrow(() -> new AssertionError("Nothing at " + path));
    }

    private static Optional<String> leaf(String path) {
        return ValuePath.parse(path).statestreamLeaf();
    }

    private static Optional<JsonElement> payload(String text) {
        return ValuePath.payload(text.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    private static void expectThrows(Runnable action, boolean throwsExpected, String message) {
        boolean threw = false;
        try {
            action.run();
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw == throwsExpected, message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

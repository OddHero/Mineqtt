package art.rehra.mineqtt.twin;

import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.twin.core.BindingPreset;
import art.rehra.mineqtt.twin.core.Call;
import art.rehra.mineqtt.twin.core.EntityDocument;
import art.rehra.mineqtt.twin.core.HaCalls;
import art.rehra.mineqtt.twin.core.HaDomains;
import art.rehra.mineqtt.twin.core.PresetCodec;
import art.rehra.mineqtt.twin.core.StatestreamValue;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * MQTT device presets (M3 plan M3-17), run by Gradle's verifyPresetLoad task on the isolated, Minecraft-free classpath
 * with the source resources' directory as its argument, under a German default locale:
 * <ul>
 *     <li>every built-in in {@code data/mineqtt/mineqtt/binding_presets/} parses, its topics take a device name, every
 *     command renders for every call its service can have, and the rendered commands are what the devices expect;</li>
 *     <li>placeholders take typed values only, JSON-escaped by construction, left out when a call lacks them, clamped to
 *     the preset's ranges; text templates take numbers only;</li>
 *     <li>a bad datapack preset is skipped with a problem line naming it, and the good ones still load;</li>
 *     <li>device names that would make a wildcard, an empty level or a $-topic are refused;</li>
 *     <li>payloads become the documents the twin adapters read (state spellings, attributes, cleared, unknown,
 *     freshness), under document ids no two topics share;</li>
 *     <li>the command topics are judged by the ACL ({@link TopicAcl#checkPublish}: only with {@code zigbee2mqtt/+/set}
 *     allowlisted), the state topics by {@link TopicAcl#checkTopicSource}, which keeps a datapack preset off the
 *     statestream namespace.</li>
 * </ul>
 */
public final class PresetLoadCheck {
    private static final List<String> BUILT_INS = List.of("mineqtt:esphome_light", "mineqtt:esphome_sensor", "mineqtt:generic_switch", "mineqtt:generic_topic",
            "mineqtt:shelly_gen2_switch", "mineqtt:tasmota_power", "mineqtt:zigbee2mqtt_contact", "mineqtt:zigbee2mqtt_light", "mineqtt:zigbee2mqtt_sensor",
            "mineqtt:zigbee2mqtt_switch");
    private static final UUID STEVE = UUID.fromString("00000000-0000-4000-8000-00000000000a");

    static void main(String[] args) throws IOException {
        Locale.setDefault(Locale.GERMANY);
        if (args.length != 1) throw new IllegalArgumentException("Usage: PresetLoadCheck <common/src/main/resources>");
        Map<String, BindingPreset> builtIns = builtIns(Path.of(args[0]));
        everyBuiltIn(builtIns);
        renders(builtIns);
        escaping();
        skipped();
        devices(builtIns);
        documents(builtIns);
        acl(builtIns);
        System.out.println("Preset load checks passed (" + builtIns.size() + " built-in presets).");
    }

    // The built-ins as the data reload reads them: data/<namespace>/mineqtt/binding_presets/<path>.json is <namespace>:<path>.
    private static Map<String, BindingPreset> builtIns(Path resources) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        Path data = resources.resolve("data");
        try (Stream<Path> namespaces = Files.list(data)) {
            for (Path namespace : namespaces.toList()) {
                Path directory = namespace.resolve("mineqtt").resolve("binding_presets");
                if (!Files.isDirectory(directory)) continue;
                try (Stream<Path> presets = Files.walk(directory)) {
                    for (Path file : presets.filter(path -> path.toString().endsWith(".json")).toList()) {
                        String path = directory.relativize(file).toString().replace('\\', '/');
                        files.put(namespace.getFileName() + ":" + path.substring(0, path.length() - ".json".length()), Files.readString(file, StandardCharsets.UTF_8));
                    }
                }
            }
        }
        PresetCodec.Loaded loaded = PresetCodec.loadAll(files);
        check(loaded.problems().isEmpty(), "Every built-in preset loads: " + loaded.problems());
        check(List.copyOf(loaded.presets().keySet()).equals(BUILT_INS), "The built-ins are the design's list plus the generic on/off topic: " + loaded.presets().keySet());
        return loaded.presets();
    }

    // Each built-in: a readable kind, topics for a device name, and every command rendering for every call its service has.
    private static void everyBuiltIn(Map<String, BindingPreset> builtIns) {
        for (BindingPreset preset : builtIns.values()) {
            check(HaDomains.readable(preset.kind()), preset.id() + " is of a kind MineQTT reads");
            check(preset.deviceProblem("m3dev").isEmpty() && !preset.stateTopic("m3dev").contains(BindingPreset.DEVICE), preset.id() + " takes a device name");
            check(preset.controllable() == preset.commandTopic("m3dev").isPresent(), preset.id() + " sends commands exactly when it has a command topic");
            for (String service : preset.commands().keySet()) {
                for (Call full : fullCalls(preset.kind(), service)) {
                    BindingPreset.Rendered rendered = preset.render(full);
                    check(rendered instanceof BindingPreset.Command, preset.id() + " renders " + service + " with every value: " + rendered);
                    BindingPreset.Command command = (BindingPreset.Command) rendered;
                    check(!command.payload().contains("{") || command.json(), preset.id() + "'s text " + service + " resolves every placeholder: " + command.payload());
                    if (command.json()) {
                        JsonElement json = JsonParser.parseString(command.payload());
                        check(!json.toString().matches(".*\\{[a-z_]+}.*"), preset.id() + "'s " + service + " resolves every placeholder: " + json);
                    }
                }
                Call bare = new Call(preset.kind() + "." + service);
                check(preset.render(bare) instanceof BindingPreset.Command, preset.id() + " renders " + service + " without data too");
            }
        }
    }

    // The commands the devices expect.
    private static void renders(Map<String, BindingPreset> builtIns) {
        BindingPreset light = builtIns.get("mineqtt:zigbee2mqtt_light");
        check(light.commandTopic("m3bulb").orElseThrow().equals("zigbee2mqtt/m3bulb/set"), "zigbee2mqtt's command topic");
        expectJson(light.render(call("light.turn_on", "{\"brightness\":255,\"rgb_color\":[255,0,0]}")),
                "{\"state\":\"ON\",\"brightness\":254,\"color\":{\"r\":255,\"g\":0,\"b\":0}}", "zigbee2mqtt's brightness is clamped to 254, the colour as r, g, b");
        expectJson(light.render(call("light.turn_on", "{\"color_temp_kelvin\":2700}")), "{\"state\":\"ON\",\"color_temp\":370}", "a colour temperature in mireds");
        expectJson(light.render(call("light.turn_on", "{\"brightness\":128,\"transition\":2}")), "{\"state\":\"ON\",\"brightness\":128,\"transition\":2}",
                "a member whose value the call lacks is left out");
        expectJson(light.render(new Call("light.turn_off")), "{\"state\":\"OFF\"}", "turn_off");
        expectJson(light.render(new Call("light.toggle")), "{\"state\":\"TOGGLE\"}", "a RETAINED-only click toggles (D11)");
        expectText(builtIns.get("mineqtt:tasmota_power").render(new Call("switch.turn_on")), "ON", "Tasmota's POWER ON");
        expectText(builtIns.get("mineqtt:tasmota_power").render(new Call("switch.toggle")), "TOGGLE", "Tasmota's TOGGLE");
        check(builtIns.get("mineqtt:tasmota_power").commandTopic("m3plug").orElseThrow().equals("cmnd/m3plug/POWER"), "Tasmota's command topic");
        expectText(builtIns.get("mineqtt:shelly_gen2_switch").render(new Call("switch.turn_off")), "off", "Shelly Gen2's off");
        check(builtIns.get("mineqtt:shelly_gen2_switch").commandTopic("shellyplus1-a8032ab").orElseThrow().equals("shellyplus1-a8032ab/command/switch:0"),
                "Shelly Gen2's command topic");
        expectJson(builtIns.get("mineqtt:zigbee2mqtt_switch").render(new Call("switch.turn_on")), "{\"state\":\"ON\"}", "a zigbee2mqtt switch");
        expectJson(builtIns.get("mineqtt:esphome_light").render(call("light.turn_on", "{\"brightness\":255}")), "{\"state\":\"ON\",\"brightness\":255}",
                "ESPHome takes brightness 255");
        check(light.render(new Call("switch.turn_on")) instanceof BindingPreset.Refused, "A call of another domain is refused");
        check(builtIns.get("mineqtt:generic_switch").render(new Call("switch.turn_on")) instanceof BindingPreset.Refused refused
                && refused.reason().contains("cannot turn on"), "A read-only preset refuses every call");
        BindingPreset noToggle = parse("check:no_toggle", "{\"kind\":\"switch\",\"state_topic\":\"x/{device}\",\"command_topic\":\"x/{device}/set\","
                + "\"commands\":{\"turn_on\":\"1\",\"turn_off\":\"0\"}}");
        check(noToggle.render(new Call("switch.toggle")) instanceof BindingPreset.Refused, "A preset without a toggle refuses the RETAINED-only toggle locally");
        BindingPreset dimmer = parse("check:dimmer", "{\"kind\":\"light\",\"state_topic\":\"stat/{device}/RESULT\",\"command_topic\":\"cmnd/{device}/Dimmer\","
                + "\"commands\":{\"turn_on\":\"{brightness_pct}\",\"turn_off\":\"0\"}}");
        expectText(dimmer.render(call("light.turn_on", "{\"brightness\":128}")), "50", "A text template with a number");
        check(dimmer.render(new Call("light.turn_on")) instanceof BindingPreset.Refused, "A text template without its number is refused, never sent half-filled");
        // Wave 6 review: a JSON template whose members all lack their value renders nothing, so it is refused, never sent as {}.
        BindingPreset bare = parse("check:bare", "{\"kind\":\"light\",\"state_topic\":\"b/{device}\",\"command_topic\":\"b/{device}/set\","
                + "\"commands\":{\"turn_on\":{\"brightness\":\"{brightness}\"},\"turn_off\":{\"state\":\"OFF\"}}}");
        expectJson(bare.render(call("light.turn_on", "{\"brightness\":128}")), "{\"brightness\":128}", "A JSON template with its value");
        check(bare.render(new Call("light.turn_on")) instanceof BindingPreset.Refused refusedBare && refusedBare.reason().contains("turn on"),
                "A JSON template whose members all lack their value is refused, never sent as {}");
        BindingPreset arrayed = parse("check:arrayed", "{\"kind\":\"light\",\"state_topic\":\"a/{device}\",\"command_topic\":\"a/{device}/set\","
                + "\"commands\":{\"turn_on\":[\"on\",\"{brightness}\"],\"turn_off\":[\"off\"]}}");
        expectJson(arrayed.render(call("light.turn_on", "{\"brightness\":128}")), "[\"on\",128]", "An array template with its value");
        check(arrayed.render(new Call("light.turn_on")) instanceof BindingPreset.Refused, "An array template missing an element is refused, never sent as {}");
    }

    // Placeholders insert typed values through Gson: nothing a value holds can break out of its JSON string.
    private static void escaping() {
        BindingPreset select = parse("check:select", "{\"kind\":\"select\",\"state_topic\":\"m/{device}\",\"command_topic\":\"m/{device}/set\","
                + "\"commands\":{\"select_option\":{\"option\":\"{option}\",\"note\":\"picked {option}!\",\"list\":[\"{option}\"]}}}");
        String hostile = "a\"},\"x\":{\"y\":\"\\\\ {brightness} </>";
        JsonObject data = new JsonObject();
        data.addProperty("option", hostile);
        BindingPreset.Rendered rendered = select.render(new Call("select.select_option", data));
        check(rendered instanceof BindingPreset.Command command && command.json(), "An option renders: " + rendered);
        JsonObject json = JsonParser.parseString(((BindingPreset.Command) rendered).payload()).getAsJsonObject();
        check(json.keySet().equals(java.util.Set.of("option", "note", "list")), "No member is injected: " + json.keySet());
        check(json.get("option").getAsString().equals(hostile) && json.get("note").getAsString().equals("picked " + hostile + "!")
                && json.getAsJsonArray("list").get(0).getAsString().equals(hostile), "The option comes back exactly, escaped, its braces never substituted: " + json);
        List<String> problems = PresetCodec.parse("check:text_option", JsonParser.parseString("{\"kind\":\"select\",\"state_topic\":\"m/{device}\","
                + "\"command_topic\":\"m/{device}/set\",\"commands\":{\"select_option\":\"{option}\"}}")).problems();
        check(problems.stream().anyMatch(problem -> problem.contains("text and takes no {option}")), "A text template may not take text: " + problems);
        JsonObject number = select.render(new Call("select.select_option", option("7"))) instanceof BindingPreset.Command command
                ? JsonParser.parseString(command.payload()).getAsJsonObject() : new JsonObject();
        check(number.get("option").getAsJsonPrimitive().isString(), "A text value stays a JSON string even when it looks like a number");
    }

    // A bad datapack preset is skipped with a line that names it; the others load.
    private static void skipped() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("pack:good", "{\"kind\":\"switch\",\"state_topic\":\"good/{device}\"}");
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("pack:unknown_key", "{\"kind\":\"switch\",\"state_topic\":\"a/{device}\",\"colour\":1}");
        bad.put("pack:no_kind", "{\"state_topic\":\"a/{device}\"}");
        bad.put("pack:private_kind", "{\"kind\":\"person\",\"state_topic\":\"a/{device}\"}");
        bad.put("pack:no_device", "{\"kind\":\"switch\",\"state_topic\":\"a/b\"}");
        bad.put("pack:wildcard", "{\"kind\":\"switch\",\"state_topic\":\"a/+/{device}\"}");
        bad.put("pack:hash", "{\"kind\":\"switch\",\"state_topic\":\"a/{device}/#\"}");
        bad.put("pack:empty_level", "{\"kind\":\"switch\",\"state_topic\":\"a//{device}\"}");
        bad.put("pack:dollar", "{\"kind\":\"switch\",\"state_topic\":\"$SYS/{device}\"}");
        bad.put("pack:stray_brace", "{\"kind\":\"switch\",\"state_topic\":\"a/{name}/{device}\"}");
        bad.put("pack:unknown_service", "{\"kind\":\"switch\",\"state_topic\":\"a/{device}\",\"command_topic\":\"a/{device}/set\",\"commands\":{\"explode\":\"1\"}}");
        bad.put("pack:unknown_placeholder", "{\"kind\":\"light\",\"state_topic\":\"a/{device}\",\"command_topic\":\"a/{device}/set\","
                + "\"commands\":{\"turn_on\":{\"code\":\"{code}\"}}}");
        bad.put("pack:wrong_service_data", "{\"kind\":\"light\",\"state_topic\":\"a/{device}\",\"command_topic\":\"a/{device}/set\","
                + "\"commands\":{\"turn_off\":{\"brightness\":\"{brightness}\"}}}");
        bad.put("pack:literal_brace", "{\"kind\":\"switch\",\"state_topic\":\"a/{device}\",\"command_topic\":\"a/{device}/set\",\"commands\":{\"turn_on\":\"{ON}\"}}");
        bad.put("pack:read_only_commands", "{\"kind\":\"binary_sensor\",\"state_topic\":\"a/{device}\",\"command_topic\":\"a/{device}/set\","
                + "\"commands\":{\"turn_on\":\"1\"}}");
        bad.put("pack:commands_without_topic", "{\"kind\":\"switch\",\"state_topic\":\"a/{device}\",\"commands\":{\"turn_on\":\"1\"}}");
        bad.put("pack:topic_without_commands", "{\"kind\":\"switch\",\"state_topic\":\"a/{device}\",\"command_topic\":\"a/{device}/set\"}");
        bad.put("pack:on_without_off", "{\"kind\":\"switch\",\"state_topic\":\"a/{device}\",\"on\":\"ON\"}");
        bad.put("pack:gps_attribute", "{\"kind\":\"sensor\",\"state_topic\":\"a/{device}\",\"attributes\":{\"latitude\":\"lat\"}}");
        bad.put("pack:bad_path", "{\"kind\":\"sensor\",\"state_topic\":\"a/{device}\",\"state\":\"a[\"}");
        bad.put("pack:bad_range", "{\"kind\":\"light\",\"state_topic\":\"a/{device}\",\"ranges\":{\"brightness\":[10,1]}}");
        bad.put("pack:huge_template", "{\"kind\":\"switch\",\"state_topic\":\"a/{device}\",\"command_topic\":\"a/{device}/set\",\"commands\":{\"turn_on\":\""
                + "x".repeat(BindingPreset.MAX_TEMPLATE_BYTES) + "\"}}");
        bad.put("pack:not_json", "{\"kind\":");
        bad.put("pack:array", "[]");
        bad.put("pack:Upper", "{\"kind\":\"switch\",\"state_topic\":\"a/{device}\"}");
        bad.put("pack:too_large", "{\"kind\":\"switch\",\"state_topic\":\"a/{device}\",\"name\":\"" + "x".repeat(PresetCodec.MAX_FILE_BYTES) + "\"}");
        files.putAll(bad);
        PresetCodec.Loaded loaded = PresetCodec.loadAll(files);
        check(loaded.presets().keySet().equals(java.util.Set.of("pack:good")), "Only the good preset loads: " + loaded.presets().keySet());
        check(loaded.problems().size() == bad.size(), "One problem line per bad preset: " + loaded.problems().size() + " for " + bad.size());
        for (String id : bad.keySet()) {
            check(loaded.problems().stream().anyMatch(problem -> problem.startsWith("binding preset " + id + " skipped: ")), "The line names " + id + ": " + loaded.problems());
        }
    }

    // Device names that would make another topic than the preset's are refused before any ACL is asked.
    private static void devices(Map<String, BindingPreset> builtIns) {
        BindingPreset z2m = builtIns.get("mineqtt:zigbee2mqtt_light");
        for (String name : List.of("", "#", "a/#", "+", "living+room", "/lamp", "lamp/", "a//b", "$SYS", "lamp\nx", "lamp\u0000", "x".repeat(BindingPreset.MAX_DEVICE_BYTES + 1))) {
            check(z2m.deviceProblem(name).isPresent(), "The device name '" + name.replace("\n", "\\n").replace("\u0000", "\\0") + "' is refused");
        }
        for (String name : List.of("m3bulb", "Living room lamp", "kitchen/ceiling", "Küche", "0x00158d0001a2b3c4")) {
            check(z2m.deviceProblem(name).isEmpty(), "The device name '" + name + "' is taken");
        }
        check(builtIns.get("mineqtt:generic_topic").stateTopic("home/garden/temperature").equals("home/garden/temperature"), "The generic topic is the device name itself");
        check(builtIns.get("mineqtt:esphome_light").stateTopic("livingroom/light/ceiling").equals("livingroom/light/ceiling/state"), "ESPHome's state topic");
    }

    // Payloads become the documents the adapters read.
    private static void documents(Map<String, BindingPreset> builtIns) {
        BindingPreset light = builtIns.get("mineqtt:zigbee2mqtt_light");
        EntityDocument on = light.document("zigbee2mqtt/m3bulb", bytes("{\"state\":\"ON\",\"brightness\":200,\"color\":{\"x\":0.62,\"y\":0.366},\"color_mode\":\"xy\",\"linkquality\":99}"),
                false, 5);
        check(on.state().equals(new StatestreamValue.Text("on")) && on.freshness() == EntityDocument.Freshness.LIVE && on.domain().equals("light"),
                "A zigbee2mqtt ON is a live light on: " + on);
        check(on.attributes().keySet().equals(java.util.Set.of("brightness", "color", "color_mode")) && on.value("brightness").orElseThrow().getAsInt() == 200,
                "Only the attributes the preset names, under Home Assistant's names: " + on.attributes().keySet());
        check(light.document("zigbee2mqtt/m3bulb", bytes("{\"state\":\"OFF\"}"), true, 5).state().equals(new StatestreamValue.Text("off"))
                && light.document("zigbee2mqtt/m3bulb", bytes("{\"state\":\"OFF\"}"), true, 5).freshness() == EntityDocument.Freshness.RETAINED, "A retained OFF is a retained off");
        check(light.document("zigbee2mqtt/m3bulb", new byte[0], true, 5).state() == StatestreamValue.CLEARED, "A cleared retained state");
        check(light.document("zigbee2mqtt/m3bulb", bytes("{\"brightness\":3}"), false, 5).state() == StatestreamValue.UNKNOWN, "No state at the path is unknown");
        check(light.document("zigbee2mqtt/m3bulb", new byte[]{'o', (byte) 0xC3}, false, 5).state() == StatestreamValue.UNKNOWN, "No UTF-8 is unknown");
        BindingPreset contact = builtIns.get("mineqtt:zigbee2mqtt_contact");
        check(contact.document("zigbee2mqtt/door", bytes("{\"contact\":true,\"battery\":90}"), false, 0).state().equals(new StatestreamValue.Text("off")),
                "A closed contact (contact true) is a door sensor's off");
        check(contact.document("zigbee2mqtt/door", bytes("{\"contact\":false}"), false, 0).state().equals(new StatestreamValue.Text("on")),
                "An open contact (contact false) is on");
        check(contact.document("zigbee2mqtt/door", bytes("{\"contact\":true,\"battery\":90}"), false, 0).value("battery").orElseThrow().getAsInt() == 90,
                "A sensor preset keeps the payload's other readings as attributes");
        check(builtIns.get("mineqtt:tasmota_power").document("stat/m3plug/POWER", bytes("ON"), true, 0).state().equals(new StatestreamValue.Text("on")), "Tasmota's ON");
        check(builtIns.get("mineqtt:tasmota_power").document("stat/m3plug/POWER", bytes("on"), true, 0).state().equals(new StatestreamValue.Text("on")),
                "A spelling matches in any case");
        check(builtIns.get("mineqtt:shelly_gen2_switch").document("s/status/switch:0", bytes("{\"output\":true}"), false, 0).state().equals(new StatestreamValue.Text("on")),
                "Shelly's output true");
        EntityDocument temperature = builtIns.get("mineqtt:zigbee2mqtt_sensor").document("zigbee2mqtt/m3temp", bytes("{\"temperature\":21.5,\"humidity\":48,\"latitude\":52.1}"), false, 0);
        check(temperature.state().equals(new StatestreamValue.Text("21.5")) && temperature.value("humidity").isPresent() && temperature.value("latitude").isEmpty(),
                "A sensor's state and readings, never GPS: " + temperature);
        check(builtIns.get("mineqtt:esphome_sensor").document("n/sensor/t/state", bytes("unavailable"), false, 0).state() == StatestreamValue.UNAVAILABLE,
                "unavailable as Home Assistant spells it");
        EntityDocument long_ = builtIns.get("mineqtt:generic_topic").document("a/b", bytes("x".repeat(400)), false, 0);
        check(long_.state().wire().length() == BindingPreset.MAX_STATE_LENGTH, "A state is cut to 255 characters");
        String id = BindingPreset.documentId("light", "zigbee2mqtt/m3bulb");
        check(id.matches("light\\.mqtt_[0-9a-f]{16}") && id.equals(on.entityId()) && !id.equals(BindingPreset.documentId("light", "zigbee2mqtt/m3bulb2"))
                && !id.equals(BindingPreset.documentId("switch", "zigbee2mqtt/m3bulb")), "Document ids are stable and per topic and kind: " + id);
    }

    // The ACL judges the topics: a command topic only when allowlisted, a state topic never in the statestream namespace.
    private static void acl(Map<String, BindingPreset> builtIns) {
        TopicAcl closed = acl(List.of());
        TopicAcl open = acl(List.of("zigbee2mqtt/+/set", "cmnd/+/POWER"));
        TopicAcl.Subject player = new TopicAcl.Subject(STEVE, false, false);
        String set = builtIns.get("mineqtt:zigbee2mqtt_light").commandTopic("m3bulb").orElseThrow();
        check(closed.checkPublish(player, set) == TopicAcl.Decision.NOT_ALLOWLISTED, "zigbee2mqtt/m3bulb/set is refused without an allowlist entry");
        check(open.checkPublish(player, set) == TopicAcl.Decision.ALLOWED, "...and allowed with zigbee2mqtt/+/set");
        check(open.checkPublish(player, builtIns.get("mineqtt:tasmota_power").commandTopic("m3plug").orElseThrow()) == TopicAcl.Decision.ALLOWED, "cmnd/+/POWER");
        for (BindingPreset preset : builtIns.values()) {
            String device = preset.id().equals("mineqtt:generic_topic") || preset.id().equals("mineqtt:generic_switch") ? "home/m3" : "m3dev";
            check(closed.checkTopicSource(player, preset.stateTopic(device)).allowed(), preset.id() + "'s state topic needs no allowlist entry to be read");
        }
        BindingPreset sneaky = parse("pack:sneaky", "{\"kind\":\"binary_sensor\",\"state_topic\":\"ha_statestream/{device}\"}");
        check(closed.checkTopicSource(player, sneaky.stateTopic("person/steve/state")) == TopicAcl.Decision.DENY_STATESTREAM_SOURCE,
                "A datapack preset on the statestream base is refused when a device would mirror it");
        check(closed.checkTopicSource(player, builtIns.get("mineqtt:generic_topic").stateTopic("ha_statestream/lock/front/state")) == TopicAcl.Decision.DENY_STATESTREAM_SOURCE,
                "So is the generic topic aimed at it");
        BindingPreset discovery = parse("pack:discovery", "{\"kind\":\"switch\",\"state_topic\":\"x/{device}\",\"command_topic\":\"homeassistant/{device}/set\","
                + "\"commands\":{\"turn_on\":\"1\"}}");
        check(open.checkPublish(new TopicAcl.Subject(STEVE, true, true), discovery.commandTopic("switch/x").orElseThrow()) == TopicAcl.Decision.DENY_DISCOVERY,
                "A command topic in the discovery prefix is hard-denied, for admins too");
    }

    private static TopicAcl acl(List<String> extra) {
        List<String> rawAllow = new java.util.ArrayList<>(TopicAcl.defaultRawAllow("mineqtt", "check"));
        rawAllow.addAll(extra);
        return new TopicAcl(new TopicAcl.Rules("mineqtt", "check", "homeassistant", "ha_statestream", rawAllow, false, false));
    }

    // Calls of `service` with every data key the service has, of the type the service-call table takes: one with the colour
    // and one with the colour temperature where it takes both, as Home Assistant refuses both in one call.
    private static List<Call> fullCalls(String domain, String service) {
        List<String> keys = HaCalls.services().get(domain).get(service);
        if (keys.contains("rgb_color") && keys.contains("color_temp_kelvin")) {
            return List.of(call(domain, service, keys.stream().filter(key -> !key.equals("color_temp_kelvin")).toList()),
                    call(domain, service, keys.stream().filter(key -> !key.equals("rgb_color")).toList()));
        }
        return List.of(call(domain, service, keys));
    }

    private static Call call(String domain, String service, List<String> keys) {
        JsonObject data = new JsonObject();
        for (String key : keys) {
            switch (key) {
                case "rgb_color" -> {
                    JsonArray rgb = new JsonArray();
                    rgb.add(10);
                    rgb.add(20);
                    rgb.add(30);
                    data.add(key, rgb);
                }
                case "option" -> data.addProperty(key, "Turbo");
                case "color_temp_kelvin" -> data.addProperty(key, 4000);
                case "transition" -> data.addProperty(key, 1);
                default -> data.addProperty(key, 50);
            }
        }
        return new Call(domain + "." + service, data);
    }

    private static Call call(String action, String data) {
        return new Call(action, JsonParser.parseString(data).getAsJsonObject());
    }

    private static JsonObject option(String value) {
        JsonObject data = new JsonObject();
        data.add("option", new JsonPrimitive(value));
        return data;
    }

    private static BindingPreset parse(String id, String json) {
        PresetCodec.Parsed parsed = PresetCodec.parse(id, JsonParser.parseString(json));
        check(parsed.preset().isPresent(), id + " parses: " + parsed.problems());
        return parsed.preset().orElseThrow();
    }

    private static void expectJson(BindingPreset.Rendered rendered, String expected, String what) {
        check(rendered instanceof BindingPreset.Command command && command.json() && JsonParser.parseString(command.payload()).equals(JsonParser.parseString(expected)),
                what + ": " + rendered);
    }

    private static void expectText(BindingPreset.Rendered rendered, String expected, String what) {
        check(rendered instanceof BindingPreset.Command command && !command.json() && command.payload().equals(expected), what + ": " + rendered);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

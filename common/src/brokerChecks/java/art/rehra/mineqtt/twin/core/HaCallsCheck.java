package art.rehra.mineqtt.twin.core;

import art.rehra.mineqtt.flow.core.Coerce;
import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.twin.core.HaCalls.Mirror;
import art.rehra.mineqtt.twin.core.HaCalls.Outcome;
import art.rehra.mineqtt.twin.core.HaCalls.Reason;
import art.rehra.mineqtt.twin.core.HaCalls.Refused;
import art.rehra.mineqtt.twin.core.HaCalls.Send;
import art.rehra.mineqtt.twin.core.HaCalls.Skip;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link HaDomains}, {@link HaCalls} and {@link Call} (M3 plan D2, D5, D8, D11, D12), run by Gradle's verifyHaCalls
 * task: every domain's ports and leaves as the D8 table declares them; every call each in-port makes; the service table
 * equal to the control blueprint's {@code services} line as the plan fixes it, and the default and selectable domains
 * equal to its {@code allowed_domains}; the per-service data-key whitelist and its value types; {@code lock.open}, lock
 * codes and {@code homeassistant.*} impossible, also for hostile values; one domain-pinned target; the D2 privacy domains
 * unreadable; the freshness-aware switching of D11; the D5 edge mapping; and a seeded fuzz over every domain, port and
 * hostile value that never yields a call outside the table. The blueprint files themselves (the argument names their
 * folder) are compared too (D15): the control blueprint since M3-10.
 */
public final class HaCallsCheck {
    // The control blueprint's services line exactly as the M3 plan fixes it (D12); the blueprint file joins in M3-10.
    private static final String PLAN_SERVICES = "{\"light\": {\"turn_on\": [\"brightness\", \"rgb_color\", \"color_temp_kelvin\", \"transition\"], "
            + "\"turn_off\": [\"transition\"], \"toggle\": []}, \"switch\": {\"turn_on\": [], \"turn_off\": [], \"toggle\": []}, "
            + "\"input_boolean\": {\"turn_on\": [], \"turn_off\": [], \"toggle\": []}, \"fan\": {\"turn_on\": [\"percentage\"], \"turn_off\": [], "
            + "\"toggle\": [], \"set_percentage\": [\"percentage\"]}, \"cover\": {\"open_cover\": [], \"close_cover\": [], \"stop_cover\": [], "
            + "\"toggle\": [], \"set_cover_position\": [\"position\"]}, \"scene\": {\"turn_on\": [\"transition\"]}, \"script\": {\"turn_on\": []}, "
            + "\"input_button\": {\"press\": []}, \"button\": {\"press\": []}, \"input_number\": {\"set_value\": [\"value\"]}, "
            + "\"number\": {\"set_value\": [\"value\"]}, \"input_select\": {\"select_option\": [\"option\"]}, \"select\": {\"select_option\": [\"option\"]}, "
            + "\"input_text\": {\"set_value\": [\"value\"]}, \"text\": {\"set_value\": [\"value\"]}, \"lock\": {\"lock\": [], \"unlock\": []}, "
            + "\"climate\": {\"set_temperature\": [\"temperature\"]}}";
    // The blueprint's allowed_domains default and selector options (D12).
    private static final List<String> PLAN_DEFAULT_DOMAINS = List.of("light", "switch", "input_boolean", "fan", "cover", "scene", "script",
            "input_button", "button", "input_number", "number", "input_select", "select", "input_text", "text");
    private static final List<String> PLAN_DOMAIN_OPTIONS = List.of("light", "switch", "input_boolean", "fan", "cover", "scene", "script",
            "input_button", "button", "input_number", "number", "input_select", "select", "input_text", "text", "lock", "climate");
    // Services no in-port sends yet: listed so the blueprint allows them, reached by nothing in M3.
    private static final Set<String> UNREACHED = Set.of("cover.stop_cover");
    private static final Set<String> REACHED = new HashSet<>();

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Usage: HaCallsCheck <docs/blueprints/automation/mineqtt>");
        Path blueprints = Path.of(args[0]);
        controlBlueprint(BlueprintFile.read(blueprints.resolve("mineqtt_control.yaml")));
        catalogBlueprint(BlueprintFile.read(blueprints.resolve("mineqtt_catalog.yaml")));
        domainTable();
        attributes();
        privacy();
        serviceTable();
        everyCall();
        switching();
        lights();
        dataWhitelist();
        impossible();
        singleTarget();
        edges();
        fuzz();
        reachability();
        System.out.println("HA call checks passed.");
    }

    /**
     * D15, M3-10: the control blueprint file equals the code. Its {@code # mineqtt:services v1} line is {@link HaCalls}'s
     * table (equal and in order); {@code min_version} is 2025.2.0; the request topic's default ends in
     * {@code bridge/ha_request}; the answer goes to the request topic with {@code ha_response} for {@code ha_request} and
     * never back to the request topic; the default domains are {@link HaDomains#DEFAULT_CONTROL_DOMAINS} (the
     * {@code twin.haActionAllowlist} default) and the selectable ones the controllable domains; one entity per call, a
     * queued mode that warns when full, and every verdict the code knows. A missing marker, or a services line an editor
     * broke over several lines, fails.
     */
    private static void controlBlueprint(BlueprintFile control) {
        JsonElement services = control.markedJson("mineqtt:services v1", "services");
        check(services.equals(HaCalls.servicesJson()) && services.toString().equals(HaCalls.servicesJson().toString()),
                "The control blueprint's services line equals HaCalls, in order: " + services);
        check(control.jsonValue("min_version", "blueprint", "homeassistant").map(JsonElement::getAsString).orElse("").equals("2025.2.0"),
                "The control blueprint needs Home Assistant 2025.2.0");
        check(control.value("domain", "blueprint").orElse("").equals("automation"), "It is an automation blueprint");
        String topic = control.value("default", "blueprint", "input", "request_topic").orElse("");
        check(topic.endsWith("/bridge/ha_request") && topic.equals("mineqtt/+/bridge/ha_request"), "The request topic's default serves every bridge: " + topic);
        check(control.value("topic", "triggers").orElse("").equals("!input request_topic") || control.contains("topic: !input request_topic"),
                "The trigger listens on the request topic input");
        check(control.contains("trigger.topic | regex_replace('/ha_request$', '/ha_response')") && control.contains("# mineqtt:reply"),
                "The answer goes to the request topic with ha_response for ha_request");
        check(control.contains("value_template: \"{{ reply_topic != trigger.topic }}\""), "An answer never goes back to a request topic (no loop)");
        check(control.contains("retain: false"), "Answers are never retained");
        JsonElement defaults = control.jsonValue("default", "blueprint", "input", "allowed_domains").orElseThrow();
        check(defaults.equals(JsonParser.parseString(new com.google.gson.Gson().toJson(HaDomains.DEFAULT_CONTROL_DOMAINS))),
                "The default domains equal HaDomains.DEFAULT_CONTROL_DOMAINS (the twin.haActionAllowlist default): " + defaults);
        JsonElement options = control.jsonValue("options", "blueprint", "input", "allowed_domains", "selector", "select").orElseThrow();
        Set<String> selectable = new HashSet<>();
        options.getAsJsonArray().forEach(option -> selectable.add(option.getAsString()));
        check(selectable.equals(new HashSet<>(HaDomains.controllableDomains())) && options.getAsJsonArray().size() == selectable.size(),
                "The selectable domains are the controllable ones: " + options);
        check(control.value("default", "blueprint", "input", "control_label").orElse("").equals("mineqtt_control"), "The control label defaults to mineqtt_control");
        check(control.value("mode").orElse("").equals("queued") && control.value("max_exceeded").orElse("").equals("warning"),
                "Queued, and a full queue is a warning in Home Assistant's log");
        check(control.contains("entity_id: \"{{ entity }}\"") && control.count("action: \"{{ domain ~ '.' ~ service }}\"") == 1,
                "One call, pinned to the entity's own domain, at one entity");
        for (String verdict : List.of("pong", "bad_request", "domain_mismatch", "no_such_entity", "domain_not_allowed", "action_not_allowed",
                "not_labelled", "accepted")) {
            check(control.contains("-%}" + verdict), "The blueprint answers " + verdict);
        }
        check(control.contains("for k, v in req.data.items() if k in services[domain][service]"), "Data keys pass only through the services line");
        fails(() -> control.without("# mineqtt:services v1").markedJson("mineqtt:services v1", "services"), "A removed marker fails");
        fails(() -> control.replacing("services: {\"light\"", "services:\n    {\"light\"").markedJson("mineqtt:services v1", "services"),
                "A services line broken over several lines fails");
        fails(() -> control.replacing("services: {", "services: {,").markedJson("mineqtt:services v1", "services"), "A services line that is no JSON fails");
    }

    /**
     * D3, D15, M3-11: the catalog blueprint file equals the code. Its {@code # mineqtt:attributes v1} line is
     * {@link HaDomains#attributesJson()} (equal and in order), so it publishes exactly the readable domains, the D2
     * privacy domains never, and per domain only whitelisted attributes; {@code min_version} 2025.2.0; the D3 triggers
     * (start, automation reload, entity and device registry, every 10 min, the sync topic ending in
     * {@code bridge/ha_sync}); mode restart behind a 2 s delay; retained lists under {@code <base>/_mineqtt/catalog/} and
     * the status (v, blueprint, generated, labels, entities) last. A missing or broken marker fails.
     */
    private static void catalogBlueprint(BlueprintFile catalog) {
        JsonElement attributes = catalog.markedJson("mineqtt:attributes v1", "attributes");
        check(attributes.equals(HaDomains.attributesJson()) && attributes.toString().equals(HaDomains.attributesJson().toString()),
                "The catalog blueprint's attribute line equals HaDomains, in order: " + HaDomains.attributesJson());
        check(new ArrayList<>(attributes.getAsJsonObject().keySet()).equals(HaDomains.readableDomains()), "It lists every readable domain and no other");
        HaDomains.PRIVATE_DOMAINS.forEach(domain -> check(!attributes.getAsJsonObject().has(domain), "The catalog never publishes " + domain));
        check(catalog.jsonValue("min_version", "blueprint", "homeassistant").map(JsonElement::getAsString).orElse("").equals("2025.2.0"),
                "The catalog blueprint needs Home Assistant 2025.2.0");
        check(catalog.value("default", "blueprint", "input", "statestream_base").orElse("").equals("ha_statestream"), "The base defaults to ha_statestream (D2)");
        check(catalog.value("default", "blueprint", "input", "sync_topic").orElse("").equals("mineqtt/+/bridge/ha_sync"), "The sync topic serves every bridge");
        check(catalog.value("default", "blueprint", "input", "read_label").orElse("").equals("mineqtt_read")
                && catalog.value("default", "blueprint", "input", "control_label").orElse("").equals("mineqtt_control"), "The two labels");
        check(catalog.value("mode").orElse("").equals("restart") && catalog.contains("seconds: 2"), "Mode restart behind a 2 s delay collapses bursts");
        for (String trigger : List.of("trigger: homeassistant", "event_type: automation_reloaded", "event_type: entity_registry_updated",
                "event_type: device_registry_updated", "minutes: /10", "topic: !input sync_topic")) {
            check(catalog.contains(trigger), "The catalog runs on " + trigger);
        }
        check(catalog.contains("topic: \"{{ base }}/_mineqtt/catalog/{{ repeat.item }}\"") && catalog.contains("topic: \"{{ base }}/_mineqtt/status\"")
                && catalog.count("retain: true") == 2, "Retained lists per domain and a retained status under <base>/_mineqtt/");
        check(catalog.contains("for a in attributes[domain]"), "Each entry's attributes come from the whitelist only");
        // A whitelisted attribute may be a datetime object (a script's last_triggered once it ran), which to_json cannot
        // write: one such attribute would end the whole catalog run (found against Home Assistant 2026.10.0).
        check(HaDomains.domain("script").orElseThrow().attributes().contains("last_triggered")
                        && catalog.contains("{%- if v is not string and v.isoformat is defined -%}{%- set v = v.isoformat() -%}{%- endif -%}"),
                "A point-in-time attribute is written as its ISO text");
        for (String key : List.of("'v': version", "'blueprint': 'catalog'", "'generated'", "'read_label'", "'control_label'", "'entities'")) {
            check(catalog.contains(key), "The status carries " + key);
        }
        for (String key : List.of("'id': id", "'name'", "'area'", "'control'", "'state'", "'last_updated'", "'attributes'")) {
            check(catalog.contains(key), "An entry carries " + key);
        }
        fails(() -> catalog.without("# mineqtt:attributes v1").markedJson("mineqtt:attributes v1", "attributes"), "A removed attribute marker fails");
        fails(() -> catalog.replacing("attributes: {\"light\"", "attributes:\n    {\"light\"").markedJson("mineqtt:attributes v1", "attributes"),
                "An attribute line broken over several lines fails");
    }

    // The D8 table, port by port: kinds, toggling, the leaves each out-port reads, and the two states of bool domains.
    private static void domainTable() {
        Map<String, String[]> expected = new LinkedHashMap<>();
        expected.put("light", new String[]{"state bool, brightness int 0..255, color color, light light", "state bool toggles, light light"});
        expected.put("switch", new String[]{"state bool", "state bool toggles"});
        expected.put("input_boolean", new String[]{"state bool", "state bool toggles"});
        expected.put("fan", new String[]{"state bool, percentage int 0..100", "state bool toggles, percentage int 0..100"});
        expected.put("cover", new String[]{"state bool, position int 0..100", "state bool toggles, position int 0..100"});
        expected.put("binary_sensor", new String[]{"state bool", ""});
        expected.put("sensor", new String[]{"value text 255, number number", ""});
        expected.put("counter", new String[]{"value int", ""});
        expected.put("input_number", new String[]{"value number", "value number"});
        expected.put("number", new String[]{"value number", "value number"});
        expected.put("input_select", new String[]{"option text 255", "option text 255"});
        expected.put("select", new String[]{"option text 255", "option text 255"});
        expected.put("input_text", new String[]{"value text 255", "value text 255"});
        expected.put("text", new String[]{"value text 255", "value text 255"});
        expected.put("scene", new String[]{"", "activate event activate"});
        expected.put("script", new String[]{"", "activate event activate"});
        expected.put("button", new String[]{"", "press event press"});
        expected.put("input_button", new String[]{"", "press event press"});
        expected.put("lock", new String[]{"state bool", "state bool toggles"});
        expected.put("climate", new String[]{"current_temperature number, temperature number", "temperature number"});
        for (String id : HaDomains.PRIVATE_DOMAINS) expected.put(id, new String[]{"", ""});
        check(HaDomains.all().stream().map(HaDomains.Domain::id).collect(Collectors.toSet()).equals(expected.keySet()),
                "The declared domains are the D8 table plus the D2 privacy list: " + HaDomains.all().stream().map(HaDomains.Domain::id).toList());
        expected.forEach((id, ports) -> {
            HaDomains.Domain domain = HaDomains.domain(id).orElseThrow();
            check(describe(domain.outPorts()).equals(ports[0]), id + " out-ports: " + describe(domain.outPorts()));
            check(describe(domain.inPorts()).equals(ports[1]), id + " in-ports: " + describe(domain.inPorts()));
            check(domain.controllable() == !ports[1].isEmpty(), id + " is controllable exactly when it has in-ports");
            check(domain.readable() == !HaDomains.PRIVATE_DOMAINS.contains(id), id + " is readable unless private");
            domain.outPorts().forEach(port -> check(!port.toggles() && !port.leaves().isEmpty(), id + "." + port.key() + " reads leaves and does not toggle"));
            domain.inPorts().forEach(port -> check(port.leaves().isEmpty(), id + "." + port.key() + " in-port reads no leaf"));
        });
        check(leaves("light", "light").equals(List.of("state", "brightness", "rgb_color", "color_temp_kelvin", "color_mode")),
                "The light composite reads the lamp's leaves (D1 #8): " + leaves("light", "light"));
        check(leaves("light", "color").equals(List.of("rgb_color")), "The colour comes from rgb_color in every colour mode (cap §9.2)");
        check(leaves("cover", "position").equals(List.of("current_position")), "A cover's position is its current_position");
        check(leaves("sensor", "value").equals(List.of("state", "unit_of_measurement")), "A sensor reads its unit next to its state");
        check(leaves("sensor", "number").equals(List.of("state", "unit_of_measurement")), "As a number too");
        check(leaves("input_number", "value").equals(List.of("state", "min", "max")), "An input_number reads its range (D5 analog Output)");
        check(leaves("input_select", "option").equals(List.of("state", "options")), "A select reads its options (the options index)");
        check(leaves("climate", "current_temperature").equals(List.of("current_temperature")), "A climate's temperatures are attributes");
        check(bools("light").equals("on/off") && bools("switch").equals("on/off") && bools("input_boolean").equals("on/off")
                && bools("fan").equals("on/off") && bools("binary_sensor").equals("on/off"), "On/off domains");
        check(bools("cover").equals("open/closed"), "A cover's bool is open");
        check(bools("lock").equals("unlocked/locked"), "A lock's bool is unlocked, as D7 and Home Assistant's lock binary sensors read it");
        for (String id : List.of("sensor", "counter", "input_number", "input_select", "input_text", "scene", "button", "climate", "person")) {
            check(HaDomains.domain(id).orElseThrow().bool().isEmpty(), id + " has no bool state");
        }
        HaDomains.BoolStates cover = HaDomains.domain("cover").orElseThrow().bool().orElseThrow();
        check(cover.read("open").equals(Optional.of(true)) && cover.read("closed").equals(Optional.of(false)) && cover.read("opening").isEmpty(),
                "Transitional states are neither (cap §9.8)");

        HaDomains.Domain light = HaDomains.domain("light").orElseThrow();
        check(light.readPort("light").orElseThrow().kind() == PortType.LIGHT, "A typed read port wins");
        HaDomains.Port generic = light.readPort(HaDomains.VALUE_PORT).orElseThrow();
        check(generic.kind() instanceof PortType.Text && generic.leaves().equals(List.of("state")), "Any readable entity has the generic value port");
        HaDomains.Port attribute = light.readPort("attr_brightness").orElseThrow();
        check(attribute.kind() == PortType.JSON && attribute.leaves().equals(List.of("brightness")), "attr_<key> reads a whitelisted attribute");
        check(light.readPort("attr_effect_list").isEmpty() && light.readPort("attr_").isEmpty() && light.readPort("attr_Brightness").isEmpty()
                && light.readPort("nothing").isEmpty(), "Other read ports do not exist");
        // A sensor's state is a number or text (D8): value reads any state as text, number reads it as a number.
        HaDomains.Domain sensor = HaDomains.domain("sensor").orElseThrow();
        check(sensor.readPort(HaDomains.VALUE_PORT).orElseThrow().kind() instanceof PortType.Text text && text.problem(json("\"sunny\"")).isEmpty()
                && text.problem(json("\"2026-10-11T04:48:44+00:00\"")).isEmpty(), "A sensor's value port takes a text state");
        check(sensor.readPort(HaDomains.NUMBER_PORT).orElseThrow().kind() instanceof PortType.Number number && number.problem(json("12.5")).isEmpty()
                && number.problem(json("\"sunny\"")).isPresent(), "Its number port a number, and no text");
        check(Coerce.compatible(sensor.readPort(HaDomains.NUMBER_PORT).orElseThrow().kind(), PortType.LEVEL, false)
                && !Coerce.compatible(sensor.readPort(HaDomains.VALUE_PORT).orElseThrow().kind(), PortType.LEVEL, false),
                "Only the number port wires to a level");
        check(HaDomains.readable("light") && HaDomains.controllable("light") && HaDomains.readable("sensor") && !HaDomains.controllable("sensor"),
                "readable and controllable");

        fails(() -> new HaDomains.Port("state", HaDomains.Direction.OUT, PortType.BOOL, true, List.of("state")), "An out-port never toggles");
        fails(() -> new HaDomains.Port("state", HaDomains.Direction.OUT, PortType.BOOL, false, List.of()), "An out-port reads a leaf");
        fails(() -> new HaDomains.Port("state", HaDomains.Direction.IN, PortType.BOOL, false, List.of("state")), "An in-port reads none");
        fails(() -> new HaDomains.Port("Bad Key", HaDomains.Direction.IN, PortType.BOOL, false, List.of()), "Port keys are checked");
        fails(() -> new HaDomains.Port("level", HaDomains.Direction.IN, PortType.LEVEL, true, List.of()), "Only a bool toggles");
    }

    // The attribute whitelists: the leaves every out-port reads are on them, and no position, picture, token or code is.
    private static void attributes() {
        for (HaDomains.Domain domain : HaDomains.all()) {
            for (String attribute : domain.attributes()) {
                check(!HaDomains.NEVER_ATTRIBUTES.contains(attribute), domain.id() + " whitelists " + attribute);
                check(domain.readableLeaf(attribute), domain.id() + " may read its whitelisted " + attribute);
            }
            if (domain.readable()) {
                check(domain.attributes().containsAll(HaDomains.COMMON_ATTRIBUTES), domain.id() + " whitelists the common attributes");
                check(domain.readableLeaf("state"), domain.id() + " may read its state");
                check(domain.readableLeaf("last_changed") && domain.readableLeaf("last_updated"), domain.id() + " may read its timestamps (D1 #8, D18)");
            }
            for (String never : HaDomains.NEVER_ATTRIBUTES) {
                check(!domain.readableLeaf(never) && domain.readPort("attr_" + never).isEmpty(), domain.id() + " never reads " + never);
            }
            for (HaDomains.Port port : domain.outPorts()) {
                port.leaves().forEach(leaf -> check(domain.readableLeaf(leaf), domain.id() + "." + port.key() + " reads " + leaf + " off its whitelist"));
            }
        }
        check(!HaDomains.domain("lock").orElseThrow().readableLeaf("code_format") && !HaDomains.domain("lock").orElseThrow().readableLeaf("changed_by"),
                "A lock never shows its code format or who used it");
        check(!HaDomains.domain("light").orElseThrow().readableLeaf("effect_list") && !HaDomains.domain("sensor").orElseThrow().readableLeaf("entity_picture"),
                "Attributes off the whitelist are unreadable");
        fails(() -> new HaDomains.Domain("weather", List.of(), List.of(), List.of("friendly_name", "entity_picture"), true, Optional.empty()),
                "No domain may whitelist a picture");
        fails(() -> new HaDomains.Domain("weather", List.of(HaDomains.Port.out("temperature", PortType.JSON, "temperature")), List.of(),
                List.of("friendly_name"), true, Optional.empty()), "An out-port reads only whitelisted leaves");
        fails(() -> new HaDomains.Domain("x", List.of(HaDomains.Port.out("state", PortType.BOOL, "state")), List.of(), List.of(), true, Optional.empty()),
                "A bool state needs its two states");
        JsonObject whitelist = HaDomains.attributesJson();
        check(new ArrayList<>(whitelist.keySet()).equals(HaDomains.readableDomains()), "The attribute line lists every readable domain, in order");
        check(whitelist.getAsJsonArray("light").toString().equals("[\"friendly_name\",\"device_class\",\"brightness\",\"rgb_color\","
                + "\"color_temp_kelvin\",\"color_mode\",\"supported_color_modes\",\"min_color_temp_kelvin\",\"max_color_temp_kelvin\"]"),
                "The light whitelist: " + whitelist.getAsJsonArray("light"));
        check(whitelist.getAsJsonArray("lock").toString().equals("[\"friendly_name\",\"device_class\"]"), "A lock shares only its name and class");
    }

    // D2: person, device_tracker, zone, camera and alarm_control_panel cannot be read, bound or called; unknown domains neither.
    private static void privacy() {
        check(HaDomains.PRIVATE_DOMAINS.equals(List.of("person", "device_tracker", "zone", "camera", "alarm_control_panel")), "The D2 privacy list");
        for (String id : HaDomains.PRIVATE_DOMAINS) {
            HaDomains.Domain domain = HaDomains.domain(id).orElseThrow();
            check(!domain.readable() && !domain.controllable() && !HaDomains.readable(id) && !HaDomains.controllable(id), id + " is unreadable");
            check(domain.outPorts().isEmpty() && domain.inPorts().isEmpty() && domain.attributes().isEmpty(), id + " has no ports or attributes");
            for (String key : List.of("value", "state", "attr_friendly_name", "attr_latitude", "attr_entity_picture")) {
                check(domain.readPort(key).isEmpty(), id + " has no read port " + key);
            }
            check(!domain.readableLeaf("state") && !domain.readableLeaf("friendly_name") && !domain.readableLeaf("latitude")
                    && !domain.readableLeaf("last_changed") && !domain.readableLeaf("last_updated"), id + " reads no leaf");
            check(!HaDomains.readableDomains().contains(id) && !HaDomains.controllableDomains().contains(id) && !HaDomains.attributesJson().has(id)
                    && !HaDomains.DEFAULT_CONTROL_DOMAINS.contains(id) && !HaCalls.services().containsKey(id), id + " is in no list");
            for (String port : List.of("state", "value", "arm", "disarm")) {
                refused(HaCalls.resolve(id, port, new JsonPrimitive(true), Mirror.live("home")), Reason.PRIVATE_DOMAIN, id + "." + port);
            }
            refused(HaCalls.edge(id, true, Mirror.NONE), Reason.PRIVATE_DOMAIN, id + " edge");
        }
        for (String id : List.of("weather", "media_player", "homeassistant", "notify", "", "Light", "_mineqtt")) {
            check(HaDomains.domain(id).isEmpty() && !HaDomains.readable(id) && !HaDomains.controllable(id), "Unknown domain " + id);
            refused(HaCalls.resolve(id, "state", new JsonPrimitive(true), Mirror.NONE), Reason.UNKNOWN_DOMAIN, "Unknown domain " + id);
        }
        check(!HaDomains.readable("zone") && HaDomains.domain("zone").isPresent(), "zone.home (GPS, occupancy, cap §9.9) is declared unreadable");
    }

    // The service table equals the plan's blueprint line; the default and selectable domains equal the blueprint inputs.
    private static void serviceTable() {
        JsonObject plan = JsonParser.parseString(PLAN_SERVICES).getAsJsonObject();
        check(HaCalls.servicesJson().equals(plan), "HaCalls equals the blueprint's services line: " + HaCalls.servicesJson());
        check(HaCalls.servicesJson().toString().equals(plan.toString()), "In the same order: " + HaCalls.servicesJson());
        check(HaDomains.DEFAULT_CONTROL_DOMAINS.equals(PLAN_DEFAULT_DOMAINS), "The default control domains equal the blueprint default");
        check(new HashSet<>(HaDomains.controllableDomains()).equals(new HashSet<>(PLAN_DOMAIN_OPTIONS)), "The controllable domains equal the selector options");
        check(HaCalls.services().keySet().equals(new HashSet<>(HaDomains.controllableDomains())), "Every controllable domain has services, and no other");
        check(HaDomains.controllableDomains().containsAll(HaDomains.DEFAULT_CONTROL_DOMAINS), "The defaults are controllable");
        Set<String> optIn = new LinkedHashSet<>(HaDomains.controllableDomains());
        HaDomains.DEFAULT_CONTROL_DOMAINS.forEach(optIn::remove);
        check(optIn.equals(Set.of("lock", "climate")), "lock and climate are opt-in (open question 2): " + optIn);
        check(HaCalls.services().get("lock").keySet().equals(Set.of("lock", "unlock")), "A lock only locks and unlocks");
        for (Map<String, List<String>> services : HaCalls.services().values()) {
            services.values().forEach(keys -> keys.forEach(key -> check(!HaCalls.BLOCKED_DATA_KEYS.contains(key), "Blocked key " + key + " whitelisted")));
        }
        fails(() -> HaCalls.services().put("homeassistant", Map.of()), "The table is read-only");
        fails(() -> HaCalls.services().get("lock").put("open", List.of()), "A domain's services are read-only");
        check(HaCalls.dataKeys("light.turn_on").orElseThrow().equals(List.of("brightness", "rgb_color", "color_temp_kelvin", "transition")), "dataKeys");
        check(HaCalls.dataKeys("light").isEmpty() && HaCalls.dataKeys("light.turn_on.x").isEmpty() && HaCalls.dataKeys("light.flash").isEmpty()
                && HaCalls.dataKeys(".turn_on").isEmpty() && !HaCalls.allowed("lock.open") && HaCalls.allowed("lock.unlock"), "dataKeys refuses");
    }

    // Every in-port of every domain, with a value of its kind and nothing known, makes the call the table names.
    private static void everyCall() {
        expect("light", "state", json("true"), Mirror.NONE, "light.turn_on {}");
        expect("light", "state", json("false"), Mirror.NONE, "light.turn_off {}");
        expect("light", "light", json("{\"state\":\"ON\",\"brightness\":128,\"color\":{\"r\":255,\"g\":0,\"b\":0}}"), Mirror.NONE,
                "light.turn_on {\"brightness\":128,\"rgb_color\":[255,0,0]}");
        expect("switch", "state", json("\"ON\""), Mirror.NONE, "switch.turn_on {}");
        expect("switch", "state", json("\"off\""), Mirror.NONE, "switch.turn_off {}");
        expect("input_boolean", "state", json("1"), Mirror.NONE, "input_boolean.turn_on {}");
        expect("input_boolean", "state", json("0"), Mirror.NONE, "input_boolean.turn_off {}");
        expect("fan", "state", json("true"), Mirror.NONE, "fan.turn_on {}");
        expect("fan", "state", json("false"), Mirror.NONE, "fan.turn_off {}");
        expect("fan", "percentage", json("40"), Mirror.NONE, "fan.set_percentage {\"percentage\":40}");
        expect("cover", "state", json("true"), Mirror.NONE, "cover.open_cover {}");
        expect("cover", "state", json("false"), Mirror.NONE, "cover.close_cover {}");
        expect("cover", "state", json("\"closed\""), Mirror.NONE, "cover.close_cover {}");
        expect("cover", "position", json("30"), Mirror.NONE, "cover.set_cover_position {\"position\":30}");
        expect("input_number", "value", json("42.5"), Mirror.NONE, "input_number.set_value {\"value\":42.5}");
        expect("number", "value", json("7"), Mirror.NONE, "number.set_value {\"value\":7}");
        expect("number", "value", json("7.0"), Mirror.NONE, "number.set_value {\"value\":7}");
        expect("number", "value", json("1e+21"), Mirror.NONE, "number.set_value {\"value\":1.0E21}");
        expect("input_select", "option", json("\"Turbo Mode\""), Mirror.NONE, "input_select.select_option {\"option\":\"Turbo Mode\"}");
        expect("select", "option", json("\"eco\""), Mirror.NONE, "select.select_option {\"option\":\"eco\"}");
        expect("input_text", "value", json("\"Grüße \\\"quoted\\\" / slash\""), Mirror.NONE, "input_text.set_value {\"value\":\"Grüße \\\"quoted\\\" / slash\"}");
        expect("text", "value", json("\"§cRed\\u0007 text\""), Mirror.NONE, "text.set_value {\"value\":\"Red text\"}");
        expect("scene", "activate", json("\"PRESS\""), Mirror.NONE, "scene.turn_on {}");
        expect("script", "activate", json("{\"event_type\":\"activate\"}"), Mirror.NONE, "script.turn_on {}");
        expect("button", "press", json("\"press\""), Mirror.NONE, "button.press {}");
        expect("input_button", "press", json("\"PRESS\""), Mirror.NONE, "input_button.press {}");
        expect("lock", "state", json("true"), Mirror.NONE, "lock.unlock {}");
        expect("lock", "state", json("false"), Mirror.NONE, "lock.lock {}");
        expect("lock", "state", json("\"unlocked\""), Mirror.NONE, "lock.unlock {}");
        expect("lock", "state", json("\"LOCKED\""), Mirror.NONE, "lock.lock {}");
        expect("climate", "temperature", json("21.5"), Mirror.NONE, "climate.set_temperature {\"temperature\":21.5}");
        String longText = "x".repeat(300);
        Call cut = HaCalls.call("input_text", "value", new JsonPrimitive(longText), Mirror.NONE).orElseThrow();
        check(cut.data().get("value").getAsString().length() == HaDomains.MAX_TEXT, "Text is cut to 255 characters");

        // Every in-port of every controllable domain has a call, and each in-port's bad values are refused.
        for (HaDomains.Domain domain : HaDomains.all()) {
            for (HaDomains.Port port : domain.inPorts()) {
                Outcome outcome = HaCalls.resolve(domain.id(), port.key(), sample(port.kind()), Mirror.NONE);
                check(outcome instanceof Send, domain.id() + "." + port.key() + " makes a call: " + outcome);
                check(outcome.sent().orElseThrow().domain().equals(domain.id()), domain.id() + "." + port.key() + " stays in its domain");
                for (JsonElement bad : List.of(json("{\"a\":1}"), json("[1,2]"), JsonNull.INSTANCE, new JsonPrimitive(Double.NaN), json("\"maybe\""))) {
                    if (port.kind() instanceof PortType.Light && bad.isJsonObject()) continue;
                    if (port.kind() instanceof PortType.Text && bad.isJsonPrimitive() && bad.getAsJsonPrimitive().isString()) continue;
                    refused(HaCalls.resolve(domain.id(), port.key(), bad, Mirror.NONE), Reason.BAD_VALUE, domain.id() + "." + port.key() + " " + bad);
                }
            }
            for (String key : List.of("open", "code", "brightness", "set_value", "nothing")) {
                if (domain.inPort(key).isPresent()) continue;
                Outcome outcome = HaCalls.resolve(domain.id(), key, new JsonPrimitive(true), Mirror.NONE);
                check(outcome instanceof Refused, domain.id() + "." + key + " is refused: " + outcome);
            }
        }
        refused(HaCalls.resolve("sensor", "value", json("3"), Mirror.NONE), Reason.READ_ONLY, "A sensor is read-only");
        refused(HaCalls.resolve("binary_sensor", "state", json("true"), Mirror.NONE), Reason.READ_ONLY, "A binary_sensor is read-only");
        refused(HaCalls.resolve("counter", "value", json("3"), Mirror.NONE), Reason.READ_ONLY, "A counter is read-only");
        refused(HaCalls.resolve("light", "brightness", json("3"), Mirror.NONE), Reason.NO_SUCH_PORT, "Brightness is no light in-port");
        refused(HaCalls.resolve("fan", "percentage", json("101"), Mirror.NONE), Reason.BAD_VALUE, "A percentage over 100");
        refused(HaCalls.resolve("cover", "position", json("12.5"), Mirror.NONE), Reason.BAD_VALUE, "A fractional position");
        refused(HaCalls.resolve("input_number", "value", json("\"42\""), Mirror.NONE), Reason.BAD_VALUE, "A numeric string is no number");
        refused(HaCalls.resolve("scene", "activate", json("\"pressed\""), Mirror.NONE), Reason.BAD_VALUE, "Only PRESS or the event activates a scene");
        refused(HaCalls.resolve("button", "press", json("true"), Mirror.NONE), Reason.BAD_VALUE, "A button needs a press");
    }

    // D11: explicit values dedupe against a LIVE mirror only; TOGGLE becomes the explicit opposite when LIVE, toggle when
    // RETAINED, and is refused when nothing is known or the entity is unavailable, unknown or cleared.
    private static void switching() {
        JsonElement on = json("true");
        JsonElement off = json("false");
        JsonElement toggle = new JsonPrimitive(Coerce.TOGGLE);
        skipped(HaCalls.resolve("light", "state", on, Mirror.live("on")), "LIVE on, desired on: no request");
        skipped(HaCalls.resolve("light", "state", off, Mirror.live("off")), "LIVE off, desired off: no request");
        expect("light", "state", on, Mirror.live("off"), "light.turn_on {}");
        expect("light", "state", off, Mirror.live("on"), "light.turn_off {}");
        expect("light", "state", toggle, Mirror.live("on"), "light.turn_off {}");
        expect("light", "state", toggle, Mirror.live("off"), "light.turn_on {}");
        expect("light", "state", json("\"toggle\""), Mirror.live("off"), "light.turn_on {}");
        expect("light", "state", toggle, Mirror.retained("on"), "light.toggle {}");
        expect("light", "state", toggle, Mirror.retained("off"), "light.toggle {}");
        expect("light", "state", on, Mirror.retained("on"), "light.turn_on {}");
        expect("light", "state", on, Mirror.NONE, "light.turn_on {}");
        refused(HaCalls.resolve("light", "state", toggle, Mirror.NONE), Reason.NOT_LIVE, "Nothing known: no toggle");
        for (String placeholder : List.of("unavailable", "unknown", "")) {
            refused(HaCalls.resolve("light", "state", toggle, Mirror.live(placeholder)), Reason.UNAVAILABLE, "LIVE " + placeholder + ": no toggle");
            refused(HaCalls.resolve("switch", "state", toggle, Mirror.retained(placeholder)), Reason.UNAVAILABLE, "RETAINED " + placeholder + ": no toggle");
            expect("light", "state", on, Mirror.live(placeholder), "light.turn_on {}");
        }
        Outcome cleared = HaCalls.resolve("light", "state", toggle, Mirror.live(""));
        check(cleared instanceof Refused(Reason ignoredReason, String detail) && detail.contains("cleared"), "A cleared state is named: " + cleared);
        fails(() -> new Mirror(Mirror.Freshness.NONE, "on"), "Nothing known has no state");
        check(!Mirror.NONE.known() && Mirror.retained("on").known() && !Mirror.live("unknown").known(), "Mirror.known");

        skipped(HaCalls.resolve("cover", "state", json("\"open\""), Mirror.live("open")), "A LIVE open cover is not opened again");
        expect("cover", "state", toggle, Mirror.live("open"), "cover.close_cover {}");
        expect("cover", "state", toggle, Mirror.live("closed"), "cover.open_cover {}");
        expect("cover", "state", toggle, Mirror.live("opening"), "cover.toggle {}");
        expect("cover", "state", on, Mirror.live("opening"), "cover.open_cover {}");
        expect("fan", "state", toggle, Mirror.retained("on"), "fan.toggle {}");
        expect("input_boolean", "state", toggle, Mirror.live("on"), "input_boolean.turn_off {}");

        expect("lock", "state", toggle, Mirror.live("locked"), "lock.unlock {}");
        expect("lock", "state", toggle, Mirror.live("unlocked"), "lock.lock {}");
        skipped(HaCalls.resolve("lock", "state", off, Mirror.live("locked")), "A LIVE locked lock is not locked again");
        skipped(HaCalls.resolve("lock", "state", json("\"unlocked\""), Mirror.live("unlocked")), "A LIVE unlocked lock is not unlocked again");
        expect("lock", "state", on, Mirror.live("locked"), "lock.unlock {}");
        refused(HaCalls.resolve("lock", "state", toggle, Mirror.retained("locked")), Reason.NOT_LIVE, "A lock never toggles from a stale state");
        refused(HaCalls.resolve("lock", "state", toggle, Mirror.live("jammed")), Reason.UNCLEAR_STATE, "A jammed lock does not toggle");
        refused(HaCalls.resolve("lock", "state", toggle, Mirror.live("open")), Reason.UNCLEAR_STATE, "An open lock does not toggle");
        refused(HaCalls.resolve("lock", "state", toggle, Mirror.NONE), Reason.NOT_LIVE, "A lock with nothing known does not toggle");
        refused(HaCalls.resolve("light", "state", json("\"maybe\""), Mirror.NONE), Reason.BAD_VALUE, "Not a bool");
        refused(HaCalls.resolve("light", "state", json("2"), Mirror.NONE), Reason.BAD_VALUE, "2 is not a bool");
        refused(HaCalls.resolve("light", "state", json("\"locked\""), Mirror.NONE), Reason.BAD_VALUE, "Another domain's states are not a light's");
    }

    // The Smart Lamp's light port: brightness clamped, one of colour and colour temperature, transitions, unknown keys ignored.
    private static void lights() {
        expect("light", "light", json("{\"brightness\":200}"), Mirror.live("on"), "light.turn_on {\"brightness\":200}");
        skipped(HaCalls.resolve("light", "light", json("{\"state\":\"ON\"}"), Mirror.live("on")), "A bare ON while LIVE on");
        skipped(HaCalls.resolve("light", "light", json("{}"), Mirror.live("on")), "An empty command while LIVE on");
        expect("light", "light", json("{\"state\":\"OFF\",\"transition\":2,\"brightness\":9}"), Mirror.NONE, "light.turn_off {\"transition\":2}");
        skipped(HaCalls.resolve("light", "light", json("{\"state\":\"OFF\",\"transition\":2}"), Mirror.live("off")), "OFF while LIVE off");
        expect("light", "light", json("{\"state\":\"ON\",\"color_mode\":\"color_temp\",\"color_temp_kelvin\":2700,\"color\":{\"r\":255,\"g\":166,\"b\":87}}"),
                Mirror.NONE, "light.turn_on {\"color_temp_kelvin\":2700}");
        expect("light", "light", json("{\"state\":\"ON\",\"color_mode\":\"rgb\",\"color_temp_kelvin\":2700,\"color\":{\"r\":1,\"g\":2,\"b\":3}}"),
                Mirror.NONE, "light.turn_on {\"rgb_color\":[1,2,3]}");
        expect("light", "light", json("{\"color_temp_kelvin\":100}"), Mirror.NONE, "light.turn_on {\"color_temp_kelvin\":1000}");
        expect("light", "light", json("{\"color_temp_kelvin\":99999}"), Mirror.NONE, "light.turn_on {\"color_temp_kelvin\":40000}");
        expect("light", "light", json("{\"brightness\":300}"), Mirror.NONE, "light.turn_on {\"brightness\":255}");
        expect("light", "light", json("{\"brightness\":-5}"), Mirror.NONE, "light.turn_on {\"brightness\":0}");
        expect("light", "light", json("{\"brightness\":127.6}"), Mirror.NONE, "light.turn_on {\"brightness\":128}");
        expect("light", "light", json("{\"brightness\":1e300}"), Mirror.NONE, "light.turn_on {\"brightness\":255}");
        expect("light", "light", json("{\"transition\":99999}"), Mirror.NONE, "light.turn_on {\"transition\":6553}");
        expect("light", "light", json("{\"transition\":-1}"), Mirror.NONE, "light.turn_on {\"transition\":0}");
        expect("light", "light", json("{\"transition\":0.5}"), Mirror.NONE, "light.turn_on {\"transition\":0.5}");
        expect("light", "light", json("{\"state\":\"TOGGLE\",\"brightness\":50}"), Mirror.live("off"), "light.turn_on {\"brightness\":50}");
        expect("light", "light", json("{\"state\":\"TOGGLE\",\"brightness\":50,\"transition\":1}"), Mirror.live("on"), "light.turn_off {\"transition\":1}");
        expect("light", "light", json("{\"state\":\"TOGGLE\",\"brightness\":50}"), Mirror.retained("on"), "light.toggle {}");
        refused(HaCalls.resolve("light", "light", json("{\"state\":\"TOGGLE\"}"), Mirror.live("unavailable")), Reason.UNAVAILABLE, "Toggle while unavailable");
        // What the lamp writes as its state (color_mode, color_temp, effect) and smuggled keys never reach the data.
        JsonObject hostile = json("{\"state\":\"ON\",\"brightness\":10,\"effect\":\"rainbow\",\"flash\":\"long\",\"entity_id\":\"light.other\","
                + "\"code\":\"1234\",\"rgb_color\":[9,9,9],\"color_temp\":300,\"device_id\":\"x\",\"area_id\":\"kitchen\",\"profile\":\"relax\"}").getAsJsonObject();
        expect("light", "light", hostile, Mirror.NONE, "light.turn_on {\"brightness\":10}");
        refused(HaCalls.resolve("light", "light", json("\"ON\""), Mirror.NONE), Reason.BAD_VALUE, "A light command is an object");
        refused(HaCalls.resolve("light", "light", json("{\"brightness\":\"bright\"}"), Mirror.NONE), Reason.BAD_VALUE, "A word brightness");
        refused(HaCalls.resolve("light", "light", json("{\"brightness\":\"128\"}"), Mirror.NONE), Reason.BAD_VALUE, "A string brightness");
        refused(HaCalls.resolve("light", "light", json("{\"color\":{\"r\":256,\"g\":0,\"b\":0}}"), Mirror.NONE), Reason.BAD_VALUE, "A channel over 255");
        refused(HaCalls.resolve("light", "light", json("{\"color\":[255,0,0]}"), Mirror.NONE), Reason.BAD_VALUE, "The lamp's colour is {r,g,b}");
        refused(HaCalls.resolve("light", "light", json("{\"color_temp_kelvin\":\"warm\"}"), Mirror.NONE), Reason.BAD_VALUE, "A word kelvin");
        refused(HaCalls.resolve("light", "light", json("{\"transition\":\"slow\"}"), Mirror.NONE), Reason.BAD_VALUE, "A word transition");
        refused(HaCalls.resolve("light", "light", json("{\"state\":\"dim\"}"), Mirror.NONE), Reason.BAD_VALUE, "A word state");
        JsonObject nan = new JsonObject();
        nan.add("brightness", new JsonPrimitive(Double.NaN));
        refused(HaCalls.resolve("light", "light", nan, Mirror.NONE), Reason.BAD_VALUE, "A NaN brightness");
    }

    // The per-service data-key whitelist and the type of every key: a Call outside it cannot be made.
    private static void dataWhitelist() {
        HaCalls.services().forEach((domain, services) -> services.forEach((service, keys) -> {
            String action = domain + "." + service;
            JsonObject data = new JsonObject();
            keys.forEach(key -> data.add(key, validValue(domain, key)));
            // A colour and a colour temperature exclude each other, so the full set is tried once with each.
            if (data.has("rgb_color")) {
                JsonObject kelvin = data.deepCopy();
                kelvin.remove("rgb_color");
                check(new Call(action, kelvin).data().equals(kelvin), action + " with every key but the colour");
                fails(() -> new Call(action, data), action + " with a colour and a colour temperature");
                data.remove("color_temp_kelvin");
            }
            Call call = new Call(action, data);
            check(call.data().equals(data) && call.domain().equals(domain) && call.service().equals(service), action + " with every key");
            check(new Call(action).data().isEmpty(), action + " without data");
            for (String key : List.of("entity_id", "device_id", "area_id", "floor_id", "label_id", "code", "flash", "effect", "variables",
                    "profile", "white", "target", "action")) {
                JsonObject smuggled = new JsonObject();
                smuggled.addProperty(key, "x");
                fails(() -> new Call(action, smuggled), action + " may not carry " + key);
            }
        }));
        fails(() -> new Call("light.turn_on", data("brightness", json("256"))), "Brightness over 255");
        fails(() -> new Call("light.turn_on", data("brightness", json("1.5"))), "A fractional brightness");
        fails(() -> new Call("light.turn_on", data("brightness", json("\"128\""))), "A string brightness");
        fails(() -> new Call("light.turn_on", data("rgb_color", json("[1,2]"))), "Two channels");
        fails(() -> new Call("light.turn_on", data("rgb_color", json("{\"r\":1,\"g\":2,\"b\":3}"))), "rgb_color is a list");
        fails(() -> new Call("light.turn_on", data("rgb_color", json("[1,2,300]"))), "A channel over 255");
        fails(() -> new Call("light.turn_on", data("color_temp_kelvin", json("500"))), "Kelvin under 1000");
        fails(() -> new Call("light.turn_off", data("brightness", json("10"))), "turn_off carries no brightness");
        fails(() -> new Call("light.toggle", data("transition", json("1"))), "toggle carries nothing");
        fails(() -> new Call("light.turn_on", data("transition", json("7000"))), "A transition over Home Assistant's clamp");
        fails(() -> new Call("fan.set_percentage", data("percentage", json("101"))), "A percentage over 100");
        fails(() -> new Call("input_number.set_value", data("value", json("\"x\""))), "An input_number's value is a number");
        fails(() -> new Call("input_number.set_value", data("value", new JsonPrimitive(Double.POSITIVE_INFINITY))), "Infinity");
        fails(() -> new Call("input_text.set_value", data("value", json("1"))), "An input_text's value is text");
        fails(() -> new Call("text.set_value", data("value", json("\"a\\nb\""))), "Control characters");
        fails(() -> new Call("text.set_value", data("value", json("\"§4red\""))), "Formatting codes");
        fails(() -> new Call("text.set_value", data("value", new JsonPrimitive("x".repeat(256)))), "Over 255 characters");
        fails(() -> new Call("select.select_option", data("option", json("[\"a\"]"))), "An option is text");
        fails(() -> new Call("script.turn_on", data("variables", json("{\"a\":1}"))), "Script variables");
        fails(() -> new Call("climate.set_temperature", data("temperature", json("\"warm\""))), "A word temperature");
        new Call("scene.turn_on", data("transition", json("2.5")));
        new Call("text.set_value", data("value", new JsonPrimitive("x".repeat(255))));
        JsonObject source = data("brightness", json("10"));
        Call call = new Call("light.turn_on", source);
        source.addProperty("brightness", 99);
        call.data().addProperty("flash", "long");
        check(call.data().toString().equals("{\"brightness\":10}"), "A call keeps and hands out copies");
    }

    // lock.open, lock codes, homeassistant.* and any action outside the table cannot be built or resolved.
    private static void impossible() {
        for (String action : List.of("lock.open", "homeassistant.turn_on", "homeassistant.turn_off", "homeassistant.toggle", "homeassistant.restart",
                "homeassistant.stop", "alarm_control_panel.alarm_disarm", "notify.notify", "script.reload", "automation.trigger", "shell_command.run",
                "light.turn_on.extra", "LIGHT.turn_on", "light.turn_on ", " light.turn_on", "light", ".turn_on", "light.", "light.TURN_ON",
                "light..turn_on", "input_boolean.reload", "cover.open_cover_tilt", "light.turn_on\n", "person.turn_on", "camera.snapshot")) {
            fails(() -> new Call(action), "No call " + action);
            check(!HaCalls.allowed(action), action + " is not allowed");
        }
        fails(() -> new Call("lock.lock", data("code", json("\"1234\""))), "A lock code");
        fails(() -> new Call("lock.unlock", data("code", json("1234"))), "A numeric lock code");
        for (String port : List.of("state", "open", "code", "lock")) {
            for (JsonElement value : hostileValues()) {
                for (Mirror mirror : mirrors()) {
                    Optional<Call> call = HaCalls.call("lock", port, value, mirror);
                    call.ifPresent(made -> check((made.action().equals("lock.lock") || made.action().equals("lock.unlock")) && made.data().isEmpty(),
                            "A lock only locks or unlocks, with no data: " + made));
                }
            }
        }
        for (String port : List.of("state", "turn_on", "restart")) {
            refused(HaCalls.resolve("homeassistant", port, json("true"), Mirror.NONE), Reason.UNKNOWN_DOMAIN, "homeassistant." + port);
        }
    }

    // One entity of the call's own domain: never a list, a wildcard, an area, a device or another domain's entity.
    private static void singleTarget() {
        check(Arrays.stream(Call.class.getRecordComponents()).map(RecordComponent::getName).toList().equals(List.of("action", "data")),
                "A call has no target component");
        Call call = new Call("light.turn_on");
        JsonObject target = call.target("light.kitchen_ceiling");
        check(target.toString().equals("{\"entity_id\":\"light.kitchen_ceiling\"}"), "The single target: " + target);
        check(new Call("input_boolean.turn_on").target("input_boolean.m3_flag").get("entity_id").isJsonPrimitive(), "The target is one string");
        for (String bad : Arrays.asList("light.kitchen,light.hall", "light.kitchen light.hall", "[\"light.kitchen\"]", "all", "none", "light.*",
                "light", "light.", ".kitchen", "Light.Kitchen", "light.kitchen\n", "light.küche", "light.kitchen-hall", "light.kitchen/hall",
                "light.kitchen#", "light.+",
                "switch.kitchen", "input_boolean.m3_flag", "area.kitchen", "light.kitchen.extra", "", null)) {
            fails(() -> call.target(bad), "Not a single light target: " + bad);
        }
        check(Call.isEntityId("light.kitchen") && Call.isEntityId("sensor.a1_b2") && !Call.isEntityId("light.kitchen hall") && !Call.isEntityId(null),
                "The blueprint's entity id rule");
        for (String old : List.of("light._kitchen", "light.kitchen_", "light.a__b")) {
            check(call.target(old).get("entity_id").getAsString().equals(old), "An id older than Home Assistant's strict rule stays controllable: " + old);
        }
        fails(() -> new Call("input_boolean.turn_on").target("light.m3_lamp"), "domain_mismatch is refused before it is sent");
    }

    // D5: a rising edge switches on, opens, activates or presses; a falling edge switches off, closes, or does nothing.
    private static void edges() {
        for (String domain : List.of("light", "switch", "input_boolean", "fan")) {
            check(action(HaCalls.edge(domain, true, Mirror.NONE)).equals(domain + ".turn_on"), domain + " rising edge");
            check(action(HaCalls.edge(domain, false, Mirror.NONE)).equals(domain + ".turn_off"), domain + " falling edge");
        }
        check(action(HaCalls.edge("cover", true, Mirror.NONE)).equals("cover.open_cover"), "cover rising edge");
        check(action(HaCalls.edge("cover", false, Mirror.NONE)).equals("cover.close_cover"), "cover falling edge");
        for (String domain : List.of("scene", "script")) {
            check(action(HaCalls.edge(domain, true, Mirror.NONE)).equals(domain + ".turn_on"), domain + " rising edge activates");
            skipped(HaCalls.edge(domain, false, Mirror.NONE), domain + " falling edge does nothing");
        }
        for (String domain : List.of("button", "input_button")) {
            check(action(HaCalls.edge(domain, true, Mirror.NONE)).equals(domain + ".press"), domain + " rising edge presses");
            skipped(HaCalls.edge(domain, false, Mirror.NONE), domain + " falling edge does nothing");
        }
        for (String domain : List.of("lock", "climate", "input_number", "number", "input_select", "select", "input_text", "text")) {
            refused(HaCalls.edge(domain, true, Mirror.NONE), Reason.NO_EDGE, domain + " is no edge target");
            refused(HaCalls.edge(domain, false, Mirror.NONE), Reason.NO_EDGE, domain + " is no edge target");
        }
        refused(HaCalls.edge("sensor", true, Mirror.NONE), Reason.READ_ONLY, "A sensor is no target");
        refused(HaCalls.edge("binary_sensor", true, Mirror.NONE), Reason.READ_ONLY, "A binary_sensor is no target");
        refused(HaCalls.edge("weather", true, Mirror.NONE), Reason.UNKNOWN_DOMAIN, "An unknown domain is no target");
        // LIVE dedupe against the target entity (M3-14).
        skipped(HaCalls.edge("light", true, Mirror.live("on")), "A rising edge into a LIVE on light sends nothing");
        skipped(HaCalls.edge("cover", false, Mirror.live("closed")), "A falling edge into a LIVE closed cover sends nothing");
        check(action(HaCalls.edge("light", false, Mirror.live("on"))).equals("light.turn_off"), "A falling edge into a LIVE on light");
        check(action(HaCalls.edge("switch", true, Mirror.retained("on"))).equals("switch.turn_on"), "No dedupe against a RETAINED value");
        check(action(HaCalls.edge("scene", true, Mirror.live("2026-10-10T08:00:00+00:00"))).equals("scene.turn_on"), "A scene activates every time");
    }

    // Every domain (and unknown ones), every port name, hostile values and every mirror: never a throw, never a call
    // outside the table, outside the entity's domain, with a blocked or unlisted key, or that the Call rules refuse.
    private static void fuzz() {
        Random random = new Random(0x4D33_2D34L);
        List<String> domains = new ArrayList<>(HaDomains.all().stream().map(HaDomains.Domain::id).toList());
        domains.addAll(List.of("homeassistant", "weather", "notify", "automation"));
        Set<String> ports = new LinkedHashSet<>(List.of("state", "light", "open", "code", "value", "option", "press", "activate", "percentage",
                "position", "temperature", "brightness", "turn_on", "attr_code", ""));
        List<JsonElement> values = hostileValues();
        List<Mirror> mirrors = mirrors();
        int calls = 0;
        for (int i = 0; i < 40_000; i++) {
            String domain = domains.get(random.nextInt(domains.size()));
            String port = new ArrayList<>(ports).get(random.nextInt(ports.size()));
            JsonElement value = values.get(random.nextInt(values.size()));
            Mirror mirror = mirrors.get(random.nextInt(mirrors.size()));
            Outcome outcome = i % 5 == 0 ? HaCalls.edge(domain, random.nextBoolean(), mirror) : HaCalls.resolve(domain, port, value, mirror);
            if (!(outcome instanceof Send(Call call))) continue;
            calls++;
            REACHED.add(call.action());
            check(call.domain().equals(domain), "A call stays in its domain: " + domain + " " + call);
            List<String> keys = HaCalls.dataKeys(call.action()).orElseThrow(() -> new AssertionError("Not in the table: " + call));
            for (String key : call.data().keySet()) {
                check(keys.contains(key) && !HaCalls.BLOCKED_DATA_KEYS.contains(key), call + " carries " + key);
            }
            check(!call.action().equals("lock.open") && !call.domain().equals("homeassistant"), "Impossible call " + call);
            check(new Call(call.action(), call.data()).equals(call), "A call round-trips: " + call);
            check(call.data().toString().getBytes(StandardCharsets.UTF_8).length <= 1024, "Small data: " + call);
        }
        check(calls > 1000, "The fuzz made calls: " + calls);
    }

    // Every service of the table is reached by some in-port, value and mirror, except those listed for the blueprint only.
    private static void reachability() {
        for (Map.Entry<String, Map<String, List<String>>> domain : HaCalls.services().entrySet()) {
            for (String service : domain.getValue().keySet()) {
                String action = domain.getKey() + "." + service;
                check(REACHED.contains(action) != UNREACHED.contains(action), action + (UNREACHED.contains(action) ? " is reached after all" : " is never reached"));
            }
        }
    }

    private static List<JsonElement> hostileValues() {
        List<JsonElement> values = new ArrayList<>(List.of(json("true"), json("false"), json("0"), json("1"), json("2"), json("-1"), json("40"),
                json("42.5"), json("1e+21"), json("1e999"), json("\"ON\""), json("\"off\""), json("\"TOGGLE\""), json("\"toggle\""), json("\"PRESS\""),
                json("\"press\""), json("\"activate\""), json("\"open\""), json("\"closed\""), json("\"locked\""), json("\"unlocked\""), json("\"1234\""),
                json("\"\""), json("\"Turbo Mode\""), json("\"§4red\\u0000\""), JsonNull.INSTANCE, new JsonPrimitive(Double.NaN),
                new JsonPrimitive(Double.NEGATIVE_INFINITY), json("[255,0,0]"), json("[\"light.a\",\"light.b\"]"), json("{\"event_type\":\"press\"}"),
                json("{\"event_type\":\"activate\"}"), json("{}"), json("{\"state\":\"ON\"}"), json("{\"state\":\"OFF\",\"transition\":3}"),
                json("{\"state\":\"TOGGLE\",\"brightness\":7}"), json("{\"code\":\"1234\",\"state\":\"ON\"}"),
                json("{\"entity_id\":\"lock.front\",\"brightness\":5,\"flash\":\"short\",\"effect\":\"x\",\"color\":{\"r\":1,\"g\":2,\"b\":3}}"),
                json("{\"color_temp_kelvin\":3000,\"color_mode\":\"color_temp\",\"transition\":1e9}"), new JsonPrimitive("x".repeat(400))));
        return values;
    }

    private static List<Mirror> mirrors() {
        List<Mirror> mirrors = new ArrayList<>(List.of(Mirror.NONE));
        for (String state : List.of("on", "off", "open", "closed", "opening", "locked", "unlocked", "jammed", "unavailable", "unknown", "", "42")) {
            mirrors.add(Mirror.live(state));
            mirrors.add(Mirror.retained(state));
        }
        return mirrors;
    }

    // A value every in-port of this kind accepts.
    private static JsonElement sample(PortType kind) {
        return switch (kind) {
            case PortType.Bool ignoredBool -> json("true");
            case PortType.Light ignoredLight -> json("{\"state\":\"ON\",\"brightness\":100}");
            case PortType.Event event -> new JsonPrimitive(event.types().getFirst());
            case PortType.Number number -> new JsonPrimitive(number.integral() ? 50 : 21.5);
            case PortType.Text ignoredText -> json("\"hello\"");
            default -> throw new AssertionError("No sample for " + kind.describe());
        };
    }

    // A value of a data key's type.
    private static JsonElement validValue(String domain, String key) {
        return switch (key) {
            case "brightness" -> json("128");
            case "rgb_color" -> json("[255,128,0]");
            case "color_temp_kelvin" -> json("2700");
            case "transition" -> json("0.5");
            case "percentage", "position" -> json("40");
            case "temperature" -> json("21.5");
            case "option" -> json("\"eco\"");
            case "value" -> domain.equals("input_text") || domain.equals("text") ? json("\"note\"") : json("42.5");
            default -> throw new AssertionError("No value for " + domain + " " + key);
        };
    }

    private static String describe(List<HaDomains.Port> ports) {
        return ports.stream().map(port -> port.key() + " " + kindName(port.kind()) + (port.toggles() ? " toggles" : "")).collect(Collectors.joining(", "));
    }

    private static String kindName(PortType kind) {
        return switch (kind) {
            case PortType.Number number -> {
                boolean bounded = number.max() - number.min() < 1e15;
                yield (number.integral() ? "int" : "number") + (bounded ? " " + (long) number.min() + ".." + (long) number.max() : "");
            }
            case PortType.Text text -> "text " + text.maxLength();
            case PortType.Event event -> "event " + String.join("|", event.types());
            default -> kind.describe();
        };
    }

    private static List<String> leaves(String domain, String port) {
        return HaDomains.domain(domain).orElseThrow().outPort(port).orElseThrow().leaves();
    }

    private static String bools(String domain) {
        HaDomains.BoolStates states = HaDomains.domain(domain).orElseThrow().bool().orElseThrow();
        return states.on() + "/" + states.off();
    }

    private static void expect(String domain, String port, JsonElement value, Mirror mirror, String expected) {
        Outcome outcome = HaCalls.resolve(domain, port, value, mirror);
        check(outcome instanceof Send, domain + "." + port + " " + value + " " + mirror + " sends " + expected + ", not " + outcome);
        Call call = outcome.sent().orElseThrow();
        REACHED.add(call.action());
        check(call.toString().equals(expected), domain + "." + port + " " + value + " " + mirror + ": " + call + ", expected " + expected);
        check(HaCalls.call(domain, port, value, mirror).equals(Optional.of(call)), "call() agrees with resolve()");
    }

    private static String action(Outcome outcome) {
        check(outcome instanceof Send, "Expected a call, got " + outcome);
        Call call = outcome.sent().orElseThrow();
        REACHED.add(call.action());
        return call.action();
    }

    private static void skipped(Outcome outcome, String message) {
        check(outcome instanceof Skip && outcome.sent().isEmpty(), message + ": " + outcome);
    }

    private static void refused(Outcome outcome, Reason reason, String message) {
        check(outcome instanceof Refused refused && refused.reason() == reason && outcome.sent().isEmpty(), message + ": " + outcome);
    }

    private static JsonObject data(String key, JsonElement value) {
        JsonObject data = new JsonObject();
        data.add(key, value);
        return data;
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    private static void fails(Runnable action, String message) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("Expected a failure: " + message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

package art.rehra.mineqtt.device;

import art.rehra.mineqtt.device.types.DisplayPanel;
import art.rehra.mineqtt.device.types.RedstoneInput;
import art.rehra.mineqtt.device.types.RedstoneOutput;
import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.device.types.SmartPlug;
import art.rehra.mineqtt.device.types.WallSwitch;
import art.rehra.mineqtt.flow.core.Coerce;
import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.mqtt.core.MqttLog;
import art.rehra.mineqtt.mqtt.core.OfflinePolicy;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.runtime.TopicScheme;
import art.rehra.mineqtt.twin.HaSink;
import art.rehra.mineqtt.twin.PendingRequests;
import art.rehra.mineqtt.twin.TwinGate;
import art.rehra.mineqtt.twin.TwinRequests;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.Level;
import art.rehra.mineqtt.flow.core.FlowMessage;
import art.rehra.mineqtt.flow.core.Mapping;
import art.rehra.mineqtt.flow.core.MessageBus;
import art.rehra.mineqtt.flow.core.PortSpec;
import art.rehra.mineqtt.flow.core.Root;
import art.rehra.mineqtt.flow.core.Scope;
import art.rehra.mineqtt.flow.core.Wire;
import art.rehra.mineqtt.twin.BindingPresets;
import art.rehra.mineqtt.twin.TwinAdapter;
import art.rehra.mineqtt.twin.core.Binding;
import art.rehra.mineqtt.twin.core.BindingPreset;
import art.rehra.mineqtt.twin.core.Call;
import art.rehra.mineqtt.twin.core.EntityDocument;
import art.rehra.mineqtt.twin.core.EntityDocuments;
import art.rehra.mineqtt.twin.core.EntityGlobs;
import art.rehra.mineqtt.twin.core.HaCalls;
import art.rehra.mineqtt.twin.core.HaDomains;
import art.rehra.mineqtt.twin.core.PresetCodec;
import art.rehra.mineqtt.twin.core.StatestreamValue;
import art.rehra.mineqtt.twin.core.TwinCoalescer;
import art.rehra.mineqtt.twin.core.TwinHealth;
import art.rehra.mineqtt.twin.core.ValuePath;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Incoming state never becomes a request (M3 plan D10, design §5.2), run by Gradle's verifyMirrorNoLoop task. No
 * {@code DeviceRuntime} can be built without a server (M2 follow-ups), so this checks the pure parts every path goes
 * through, with the synthetic twin type {@link TwinTestTypes}:
 * <ul>
 *     <li>the route table: the mirrored device's own apply ({@code TWIN}) never forwards and never queues; a player's or
 *     a wire's command to a mirrored port forwards and does not apply; Home Assistant, groups and the paused Raw Link
 *     are refused;</li>
 *     <li>reading: what a document gives is in-port values only, which become a {@code TWIN} command, never a call; a
 *     fuzz of documents never yields anything else;</li>
 *     <li>the side doors: a new wire's seed into a mirrored port is skipped, a bound device's queued command is dropped
 *     at its attach, and its Raw Link input pauses;</li>
 *     <li>a real {@link MessageBus}: whatever is emitted inside a {@code TWIN}, {@code HA}, {@code RAW} or
 *     {@code STARTUP} extent reaches a mirrored target with that root, through relays too, so the forward sees it and
 *     its root gate (M3-9) can refuse it.</li>
 * </ul>
 * M3-8 adds the lamp's read side; M3-9 the write-back ({@link TwinRequests}): the root gate for every root, through a
 * relay and the server's power-on, the lamp's freshness-aware toggles and dedupe, the rates, the circuit breaker, the
 * offline drop, and advisory answers that never change a device.
 */
public final class MirrorNoLoopCheck {
    private static final DeviceType TYPE = TwinTestTypes.TYPE;
    private static final TwinAdapter ADAPTER = TwinTestTypes.ADAPTER;
    private static final Binding SWITCH = TwinTestTypes.bound("switch.porch", true);

    static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        typeRules();
        routes();
        mirroredKeys();
        reading();
        readingFuzz();
        forwarding();
        sideDoors();
        bus();
        lampBurst();
        lampSequences();
        levelSequences();
        gate();
        lampToggles();
        levelForwards();
        targets();
        repeaterLoop();
        repeaterLoopFast();
        rates();
        rateRefusals();
        twoTargets();
        leverBursts();
        breaker();
        offline();
        answers();
        powerOn();
        displayPanel();
        presets();
        targetShare();
        System.out.println("Mirror no-loop checks passed.");
    }

    /**
     * M3-15: a mirrored Display Panel never forwards (read-only, M3 plan open question 6). Control cannot be turned on
     * for it, its adapter yields no call for any command, and every edit of its message (a player, a wire, Home
     * Assistant's /set, a group, its Raw Link) is refused with a reason that says where to change it; only its twin
     * applies, loaded. Its settings (colour, glow, size) touch nothing it mirrors, so they apply as always, and a new
     * wire into its message is never seeded.
     */
    private static void displayPanel() {
        TwinAdapter twin = DisplayPanel.TWIN;
        Binding washer = Binding.of(twin.sourceEnd("sensor.washer"));
        TwinRules.Rights everything = new TwinRules.Rights(true, true, EntityGlobs.ALL, Optional.empty());
        TwinRules.Edited control = TwinRules.edit(twin, washer, new DeviceConfig.TwinControl(true), everything, everything, false);
        check(control.binding() == null && control.refusal() != null && control.refusal().contains("read-only"), "Control cannot be turned on for a panel: " + control);
        check(twin.problem(washer.withControl(true)).orElse("").contains("read-only"), "A stored binding with control is refused for a panel");
        for (String command : List.of("{\"message\":\"x\"}", "{\"message\":\"\"}", "{\"message\":\"x\",\"color\":\"red\"}", "{\"color\":\"red\"}")) {
            JsonObject json = JsonParser.parseString(command).getAsJsonObject();
            check(twin.forward(json, HaCalls.Mirror.NONE, washer.withControl(true)).isEmpty() && twin.forward(json, HaCalls.Mirror.NONE, washer).isEmpty(),
                    "A panel yields no call for " + command);
        }
        JsonObject message = JsonParser.parseString("{\"message\":\"Hello\"}").getAsJsonObject();
        JsonObject color = JsonParser.parseString("{\"color\":\"red\"}").getAsJsonObject();
        check(twin.touchesMirror(message) && !twin.touchesMirror(color), "The message is mirrored, the colour is a setting");
        DeviceRuntime.Mirrored mirrored = new DeviceRuntime.Mirrored(true, washer.controls());
        check(!washer.controls(), "A panel's binding never controls");
        for (boolean attached : new boolean[]{false, true}) {
            for (CommandSource source : new CommandSource[]{CommandSource.PLAYER, CommandSource.BINDING, CommandSource.HA, CommandSource.GROUP, CommandSource.RAW}) {
                check(DeviceRuntime.route(false, DisplayPanel.TYPE, attached, source, mirrored) == DeviceRuntime.Route.REFUSE,
                        source + " to a mirrored panel's message is refused (loaded " + attached + ")");
            }
            check(DeviceRuntime.route(false, DisplayPanel.TYPE, attached, CommandSource.TWIN, mirrored) == (attached ? DeviceRuntime.Route.APPLY : DeviceRuntime.Route.DROP),
                    "Only its twin applies, while it is loaded");
            check(DeviceRuntime.route(false, DisplayPanel.TYPE, attached, CommandSource.PLAYER, DeviceRuntime.Mirrored.NONE)
                    == (attached ? DeviceRuntime.Route.APPLY : DeviceRuntime.Route.QUEUE), "A setting applies, or queues while unloaded");
        }
        check(DeviceRuntime.refusal("laundry_panel", washer, CommandSource.PLAYER, false, true)
                        .equals("laundry_panel mirrors sensor.washer and only shows it; change it in Home Assistant"),
                "A player hears that the panel only shows its entity: " + DeviceRuntime.refusal("laundry_panel", washer, CommandSource.PLAYER, false, true));
        check(DeviceRuntime.refusal("laundry_panel", washer, CommandSource.HA, false, true).equals("laundry_panel mirrors sensor.washer; change it in Home Assistant")
                        && DeviceRuntime.refusal("laundry_panel", washer, CommandSource.RAW, false, true).contains("Raw Link is paused")
                        && DeviceRuntime.refusal("laundry_panel", washer.withRefused("not allowed"), CommandSource.BINDING, false, true).contains("binding is refused"),
                "Home Assistant, the Raw Link and a refused binding are told why too");
        check(TwinRules.seedSkipped(washer, twin, message) && !TwinRules.seedSkipped(washer, twin, color) && !TwinRules.seedSkipped(null, twin, message),
                "A new wire never seeds a mirrored panel's message");
        check(TwinRules.landingPending(washer, twin, JsonParser.parseString("{\"message\":\"x\",\"size\":\"large\"}").getAsJsonObject())
                .equals(JsonParser.parseString("{\"size\":\"large\"}").getAsJsonObject()), "Only its settings land at its attach");
        // Reading never yields a call: a thousand statestream values of every readable domain read into a message and a
        // level, each a command the panel takes as its twin, none a request.
        java.util.Random random = new java.util.Random(15);
        List<String> domains = twin.sourceDomains();
        String[] values = {"21.4", "on", "off", "unavailable", "unknown", "", "NaN", "1e999", "Turbo", "2026-10-11T07:30:00+00:00", "-3", "07:30:00", "§ahi"};
        for (int i = 0; i < 1000; i++) {
            String domain = domains.get(random.nextInt(domains.size()));
            String entity = domain + ".fuzz_" + i;
            EntityDocuments documents = new EntityDocuments("ha_statestream", EntityDocuments.Limits.DEFAULT);
            String topic = "ha_statestream/" + domain + "/fuzz_" + i + "/";
            documents.accept(topic + "state", values[random.nextInt(values.length)].getBytes(java.nio.charset.StandardCharsets.UTF_8), random.nextBoolean(), 0);
            if (random.nextBoolean()) documents.accept(topic + "unit_of_measurement", "\"%\"".getBytes(java.nio.charset.StandardCharsets.UTF_8), false, 1);
            Optional<art.rehra.mineqtt.twin.core.EntityDocument> document = documents.document(entity);
            if (document.isEmpty()) continue;
            Binding binding = Binding.of(twin.sourceEnd(entity));
            Optional<JsonObject> command = DisplayPanel.TYPE.twinCommand(document.get(), binding);
            command.ifPresent(found -> {
                check(found.keySet().stream().allMatch(key -> key.equals("message") || key.equals(TwinAdapter.EXTRAS_KEY)), "Only the message and its extras: " + found);
                check(DisplayPanel.Panel.DEFAULT.apply(new CommandReader(found), true).isPresent(), "The panel takes its twin's command: " + found);
                check(twin.forward(found, HaCalls.Mirror.NONE, binding.withControl(true)).isEmpty(), "Reading never yields a call: " + found);
            });
        }
    }

    /** A {@link TwinRequests} on a recording transport and a clock the check sets (M3-9). */
    static final class Requests implements TwinRequests.Listener {
        static final TopicScheme TOPICS = new TopicScheme("mineqtt", "check");
        final List<Outbound> published = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<TwinRequests.Notice> notices = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<String> debugs = new ArrayList<>();
        boolean connected = true;
        long now = 1_000_000;
        int bridge = 20;
        List<String> allowed = HaDomains.DEFAULT_CONTROL_DOMAINS;
        final TwinRequests requests;

        Requests() {
            requests = new TwinRequests(new TwinRequests.Transport() {
                @Override
                public boolean connected() {
                    return connected;
                }

                @Override
                public void publish(Outbound message) {
                    published.add(message);
                }
            }, () -> TOPICS, () -> allowed, () -> bridge, () -> now, new Random(0x4d339L), new MqttLog() {
                @Override
                public void debug(String message) {
                    debugs.add(message);
                }

                @Override
                public void info(String message) {
                }

                @Override
                public void warn(String message, Throwable cause) {
                    warnings.add(message);
                }

                @Override
                public void error(String message, Throwable cause) {
                }
            });
            requests.setListener(this);
        }

        @Override
        public void pendingChanged(UUID device, boolean pending) {
            events.add((pending ? "pending " : "done ") + device);
        }

        @Override
        public void pausedChanged(UUID device, boolean paused) {
            events.add((paused ? "paused " : "resumed ") + device);
        }

        @Override
        public void notice(TwinRequests.Notice notice) {
            notices.add(notice);
        }

        @Override
        public void rateRefused(UUID device) {
            events.add("rate " + device);
        }

        /** The request published last, as JSON. */
        JsonObject last() {
            return JsonParser.parseString(published.getLast().text()).getAsJsonObject();
        }

        /** The actions of the requests published, in order. */
        List<String> actions() {
            return published.stream().map(message -> JsonParser.parseString(message.text()).getAsJsonObject())
                    .filter(json -> json.has("action")).map(json -> json.get("action").getAsString()).toList();
        }

        void answer(String id, String status, boolean retained) {
            JsonObject answer = new JsonObject();
            answer.addProperty("id", id);
            answer.addProperty("status", status);
            answer.addProperty("entity", "light.kitchen");
            answer.addProperty("blueprint", 1);
            requests.response(answer.toString().getBytes(StandardCharsets.UTF_8), retained);
        }
    }

    /** The built-in MQTT device presets as the data reload loads them (M3-17), from the classpath's datapack. */
    public static PresetCodec.Loaded builtInPresets() {
        Map<String, String> files = new HashMap<>();
        for (String name : List.of("zigbee2mqtt_light", "zigbee2mqtt_switch", "zigbee2mqtt_sensor", "zigbee2mqtt_contact", "tasmota_power", "shelly_gen2_switch",
                "esphome_light", "esphome_sensor", "generic_topic", "generic_switch")) {
            try (java.io.InputStream in = MirrorNoLoopCheck.class.getClassLoader().getResourceAsStream("data/mineqtt/mineqtt/binding_presets/" + name + ".json")) {
                check(in != null, "The built-in preset " + name + " is on the classpath");
                files.put("mineqtt:" + name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        }
        PresetCodec.Loaded loaded = PresetCodec.loadAll(files);
        check(loaded.problems().isEmpty() && loaded.presets().size() == files.size(), "The built-in presets load: " + loaded.problems());
        return loaded;
    }

    /**
     * M3-17: an MQTT device preset's source under the same root gate. A lamp bound to a zigbee2mqtt light reads its payload
     * as in-port values only (a {@code TWIN} command, never a call); a forward of every root reaches the preset's command
     * topic for PLAYER and WORLD only, TWIN, HA, STARTUP and RAW are refused for their root and publish nothing; the
     * command is the preset's, typed; the ACL refusing the command topic sends nothing; the LIVE mirror dedupes, a
     * RETAINED one toggles; a preset without a toggle refuses it locally; the breaker pauses a world-caused stream; a
     * Tasmota plug sends its text command.
     */
    private static void presets() {
        BindingPresets.replace(builtInPresets());
        BindingPreset z2m = BindingPresets.get("mineqtt:zigbee2mqtt_light").orElseThrow();
        Binding lamp = new Binding(new Endpoint.TopicRef("zigbee2mqtt/m3bulb", ""), Map.of(), true,
                Binding.Settings.DEFAULT.withPreset(new Binding.PresetRef(z2m.id(), "m3bulb", "light")), null);
        check(SmartLamp.TWIN.problem(lamp).isEmpty(), "A lamp takes a zigbee2mqtt light: " + SmartLamp.TWIN.problem(lamp));
        check(SmartPlug.TWIN.problem(lamp.withControl(false)).isEmpty() && DisplayPanel.TWIN.problem(lamp.withControl(false)).isEmpty(),
                "So do a plug and a panel (a light is one of their source domains)");
        Binding sensor = lamp.withSettings(Binding.Settings.DEFAULT.withPreset(new Binding.PresetRef("mineqtt:zigbee2mqtt_sensor", "m3bulb", "sensor"))).withControl(false);
        check(SmartLamp.TWIN.problem(sensor).orElse("").contains("cannot mirror a sensor"), "A lamp refuses a sensor preset: " + SmartLamp.TWIN.problem(sensor));
        Binding presetless = new Binding(new Endpoint.TopicRef("zigbee2mqtt/m3bulb", ""), Map.of(), false, Binding.Settings.DEFAULT, null);
        check(SmartLamp.TWIN.problem(presetless).orElse("").contains("device preset"), "A topic without a preset is refused");

        // Reading: in-port values only, which the lamp applies as TWIN.
        EntityDocument document = z2m.document("zigbee2mqtt/m3bulb", "{\"state\":\"ON\",\"brightness\":200,\"color\":{\"x\":0.62,\"y\":0.366}}"
                .getBytes(StandardCharsets.UTF_8), true, 0);
        JsonObject read = SmartLamp.TYPE.twinCommand(document, lamp).orElseThrow();
        check(read.get("state").getAsString().equals("ON") && read.get("brightness").getAsInt() == 200 && read.has("color"), "The lamp reads the bulb: " + read);
        DeviceRuntime.Route applied = DeviceRuntime.route(false, SmartLamp.TYPE, true, CommandSource.TWIN, new DeviceRuntime.Mirrored(true, lamp.controls()));
        check(applied == DeviceRuntime.Route.APPLY, "Its own apply is applied, never forwarded: " + applied);
        EntityDocument other = z2m.document("zigbee2mqtt/other", "{\"state\":\"ON\"}".getBytes(StandardCharsets.UTF_8), false, 0);
        check(SmartLamp.TWIN.read(other, lamp).isEmpty(), "Another topic's document reads nothing");

        TwinRequests.PresetTarget allowed = new TwinRequests.PresetTarget("zigbee2mqtt/m3bulb", "zigbee2mqtt/m3bulb/set", z2m, topic -> Optional.empty());
        HaCalls.Mirror live = HaCalls.Mirror.live("on");
        for (Root root : Root.values()) {
            Requests harness = new Requests();
            UUID device = UUID.randomUUID();
            TwinRequests.Result result = harness.requests.forwardPreset(origin(device, root == Root.PLAYER ? STEVE : null), allowed, SmartLamp.TWIN, lamp, toggle(),
                    live, root);
            if (TwinGate.mayRequest(root)) {
                check(result instanceof TwinRequests.Sent sent && sent.action().equals("light.turn_off") && harness.published.size() == 1
                        && harness.published.getFirst().topic().equals("zigbee2mqtt/m3bulb/set") && !harness.published.getFirst().retain()
                        && JsonParser.parseString(harness.published.getFirst().text()).equals(JsonParser.parseString("{\"state\":\"OFF\"}")),
                        "A " + root + " cause switches the bulb off through its command topic: " + result);
                check(harness.requests.pending(device), "...and the lamp waits for the bulb's next state");
                harness.requests.applied(TwinRequests.topicKey("zigbee2mqtt/m3bulb"));
                check(!harness.requests.pending(device), "...which ends the wait");
            } else {
                check(result instanceof TwinRequests.Refused refused && refused.refusal() == TwinRequests.Refusal.ROOT && harness.published.isEmpty()
                        && !harness.requests.pending(device), root + " never switches the bulb: " + result);
            }
            check(HaSink.outcome(result) == (TwinGate.mayRequest(root) ? MessageBus.Outcome.TAKEN : MessageBus.Outcome.ROOT), "The bus counts " + root + " so");
        }

        Requests harness = new Requests();
        UUID device = UUID.randomUUID();
        TwinRequests.PresetTarget denied = new TwinRequests.PresetTarget("zigbee2mqtt/m3bulb", "zigbee2mqtt/m3bulb/set", z2m,
                topic -> Optional.of(topic + ": not in the raw topic allowlist (acl.rawAllow)"));
        TwinRequests.Result refused = harness.requests.forwardPreset(origin(device, STEVE), denied, SmartLamp.TWIN, lamp, toggle(), live, Root.PLAYER);
        check(refused instanceof TwinRequests.Refused acl && acl.refusal() == TwinRequests.Refusal.NOT_ALLOWED && acl.reason().contains("acl.rawAllow")
                && harness.published.isEmpty(), "A command topic the ACL refuses the owner sends nothing, with the reason: " + refused);
        TwinRequests.Result dedupe = harness.requests.forwardPreset(origin(device, STEVE), allowed, SmartLamp.TWIN, lamp, command(Action.STATE, new JsonPrimitive("ON")),
                live, Root.PLAYER);
        check(dedupe instanceof TwinRequests.Skipped && harness.published.isEmpty(), "The LIVE mirror already on sends nothing: " + dedupe);
        TwinRequests.Result retained = harness.requests.forwardPreset(origin(device, STEVE), allowed, SmartLamp.TWIN, lamp, toggle(),
                new HaCalls.Mirror(HaCalls.Mirror.Freshness.RETAINED, "on", document.toJson()), Root.PLAYER);
        check(retained instanceof TwinRequests.Sent && harness.published.getLast().text().equals("{\"state\":\"TOGGLE\"}"), "A RETAINED mirror toggles: " + retained);
        harness.now += 10_000;
        JsonObject colour = new JsonObject();
        colour.addProperty("brightness", 255);
        JsonObject red = new JsonObject();
        red.addProperty("r", 255);
        colour.add("color", red);
        TwinRequests.Result picked = harness.requests.forwardPreset(origin(device, STEVE), allowed, SmartLamp.TWIN, lamp, colour, live, Root.PLAYER);
        check(picked instanceof TwinRequests.Sent && JsonParser.parseString(harness.published.getLast().text())
                        .equals(JsonParser.parseString("{\"state\":\"ON\",\"brightness\":254,\"color\":{\"r\":255,\"g\":0,\"b\":0}}")),
                "A picked colour and brightness reach the bulb typed, the brightness in zigbee2mqtt's range: " + harness.published.getLast().text());
        BindingPreset noToggle = PresetCodec.parse("check:no_toggle", JsonParser.parseString("{\"kind\":\"switch\",\"state_topic\":\"x/{device}\","
                + "\"command_topic\":\"x/{device}/set\",\"commands\":{\"turn_on\":\"1\",\"turn_off\":\"0\"}}")).preset().orElseThrow();
        Binding plain = new Binding(new Endpoint.TopicRef("x/p", ""), Map.of(), true, Binding.Settings.DEFAULT.withPreset(new Binding.PresetRef("check:no_toggle", "p", "switch")), null);
        int before = harness.published.size();
        harness.now += 10_000;
        TwinRequests.Result noToggled = harness.requests.forwardPreset(origin(device, STEVE), new TwinRequests.PresetTarget("x/p", "x/p/set", noToggle, topic -> Optional.empty()),
                SmartPlug.TWIN, plain, toggle(), new HaCalls.Mirror(HaCalls.Mirror.Freshness.RETAINED, "on", new JsonObject()), Root.PLAYER);
        check(noToggled instanceof TwinRequests.Refused local && local.refusal() == TwinRequests.Refusal.LOCAL && harness.published.size() == before,
                "A preset without a toggle refuses a RETAINED-only toggle locally: " + noToggled);

        // The breaker: a stream the world causes pauses the device after 8 commands in 10 s.
        Requests looped = new Requests();
        UUID input = UUID.randomUUID();
        TwinRequests.Result last = null;
        for (int i = 1; i <= 9; i++) {
            looped.now += 300;
            last = looped.requests.forwardPreset(origin(input, null), allowed, SmartLamp.TWIN, lamp, command(Action.STATE, new JsonPrimitive(i % 2 == 0 ? "ON" : "OFF")),
                    HaCalls.Mirror.NONE, Root.WORLD);
        }
        check(last instanceof TwinRequests.Refused paused && paused.refusal() == TwinRequests.Refusal.PAUSED && looped.requests.paused(input)
                && looped.published.size() == TwinRequests.BREAKER_LIMIT, "A world-caused stream to a preset device trips the breaker: " + last);

        // A command held for the rate is checked against the ACL again when it would be sent: an admin removed the topic.
        Requests closing = new Requests();
        UUID bulb = UUID.randomUUID();
        boolean[] open = {true};
        TwinRequests.PresetTarget reloadable = new TwinRequests.PresetTarget("zigbee2mqtt/m3bulb", "zigbee2mqtt/m3bulb/set", z2m,
                topic -> open[0] ? Optional.empty() : Optional.of(topic + ": not in the raw topic allowlist (acl.rawAllow)"));
        TwinRequests.Result waited = null;
        for (int brightness = 10; brightness <= 50; brightness += 10) {
            closing.now += 50;
            waited = closing.requests.forwardPreset(origin(bulb, null), reloadable, SmartLamp.TWIN, lamp, command("brightness", new JsonPrimitive(brightness)), live, Root.WORLD);
        }
        check(waited instanceof TwinRequests.Held && closing.published.size() == 4, "The 5th brightness for the bulb waits: " + waited);
        open[0] = false;
        closing.now += 1_000;
        closing.requests.tick();
        check(closing.published.size() == 4 && closing.requests.status().waiting() == 0, "...and is not sent once the ACL refuses its command topic");

        // A Tasmota plug: its text command on cmnd/<device>/POWER.
        BindingPreset tasmota = BindingPresets.get("mineqtt:tasmota_power").orElseThrow();
        Binding plug = new Binding(new Endpoint.TopicRef("stat/m3plug/POWER", ""), Map.of(), true,
                Binding.Settings.DEFAULT.withPreset(new Binding.PresetRef(tasmota.id(), "m3plug", "switch")), null);
        Requests plugs = new Requests();
        TwinRequests.Result on = plugs.requests.forwardPreset(origin(UUID.randomUUID(), null), new TwinRequests.PresetTarget("stat/m3plug/POWER", "cmnd/m3plug/POWER", tasmota,
                topic -> Optional.empty()), SmartPlug.TWIN, plug, command(Action.STATE, new JsonPrimitive("ON")), HaCalls.Mirror.NONE, Root.WORLD);
        check(on instanceof TwinRequests.Sent && plugs.published.getFirst().topic().equals("cmnd/m3plug/POWER") && plugs.published.getFirst().text().equals("ON"),
                "A Tasmota plug gets ON on cmnd/m3plug/POWER: " + on);
        EntityDocument power = tasmota.document("stat/m3plug/POWER", "ON".getBytes(StandardCharsets.UTF_8), true, 0);
        check(SmartPlug.TYPE.twinCommand(power, plug).orElseThrow().get("power").getAsInt() == 15, "...and mirrors stat/m3plug/POWER ON as on");
        BindingPresets.replace(PresetCodec.Loaded.EMPTY);
    }

    /**
     * Wave 5 finding 3: a device with targets only is judged by its targets' share like a mirroring device by its
     * source's. Every target shared: no health (it reads nothing); one that left the catalog: NOT_SHARED, and it is the
     * entity a press is not sent to; labelled again: healthy; a refused binding stays REFUSED.
     */
    private static void targetShare() {
        Binding input = new Binding(null, Map.of("powered", TwinAdapter.targetEnd("scene.m3_scene")), false, Binding.Settings.DEFAULT, null);
        Binding wall = new Binding(null, Map.of("left", TwinAdapter.targetEnd("scene.m3_scene"), "right", TwinAdapter.targetEnd("script.m3_script")), false,
                Binding.Settings.DEFAULT, null);
        Set<String> shared = new java.util.HashSet<>(Set.of("scene.m3_scene", "script.m3_script"));
        check(TwinRules.targetHealth(input, shared::contains) == null && TwinRules.targetHealth(wall, shared::contains) == null,
                "Targets that are shared show no health");
        shared.remove("script.m3_script");
        check(TwinRules.targetHealth(wall, shared::contains) == TwinHealth.NOT_SHARED && TwinRules.unsharedTarget(wall, shared::contains).orElseThrow().equals("script.m3_script"),
                "A target whose entity left the catalog is NOT_SHARED, and names the entity a press is not sent to");
        check(TwinRules.targetHealth(input, shared::contains) == null, "...while a device whose targets are all shared stays healthy");
        shared.add("script.m3_script");
        check(TwinRules.targetHealth(wall, shared::contains) == null, "Labelled again, the target heals");
        check(TwinRules.targetHealth(wall.withRefused("not allowed"), shared::contains) == TwinHealth.REFUSED, "A refused binding stays REFUSED");
    }

    private static TwinRequests.Origin origin(UUID device, TwinRequests.Requester player) {
        return new TwinRequests.Origin(device, DeviceRecord.objectId(device), "Porch Lamp", player);
    }

    private static final TwinRequests.Requester STEVE = new TwinRequests.Requester(UUID.randomUUID(), "Steve");
    // A lamp bound to light.kitchen with control on; a method, as SmartLamp needs the bootstrap first.
    private static Binding lampLight() {
        return Binding.of(SmartLamp.TWIN.sourceEnd("light.kitchen")).withControl(true);
    }

    private static JsonObject toggle() {
        return command(Action.STATE, new JsonPrimitive(Coerce.TOGGLE));
    }

    /**
     * D10's gate: whatever a mirrored device is asked, a request is built only for a player's or the world's cause. A
     * forward and a target delivery of every root: TWIN, HA, STARTUP and RAW are refused for their root (and counted so),
     * publish nothing and leave nothing waiting; PLAYER and WORLD send. The bus maps a root refusal to {@code ROOT}.
     */
    private static void gate() {
        for (Root root : Root.values()) {
            Requests harness = new Requests();
            UUID device = UUID.randomUUID();
            TwinRequests.Result forwarded = harness.requests.forward(origin(device, root == Root.PLAYER ? STEVE : null), "light.kitchen", SmartLamp.TWIN,
                    lampLight(), toggle(), HaCalls.Mirror.live("on"), root);
            TwinRequests.Result targeted = new HaSink(harness.requests).deliver(origin(device, null), TwinAdapter.targetEnd("scene.movie"),
                    new JsonPrimitive(Coerce.PRESS), HaCalls.Mirror.NONE, root);
            if (TwinGate.mayRequest(root)) {
                check(forwarded instanceof TwinRequests.Sent sent && sent.action().equals("light.turn_off") && targeted instanceof TwinRequests.Sent,
                        "A " + root + " cause sends: " + forwarded + " " + targeted);
                check(harness.actions().equals(List.of("light.turn_off", "scene.turn_on")), "...exactly those two requests: " + harness.actions());
            } else {
                check(forwarded instanceof TwinRequests.Refused refused && refused.refusal() == TwinRequests.Refusal.ROOT
                        && targeted instanceof TwinRequests.Refused other && other.refusal() == TwinRequests.Refusal.ROOT, root + " is refused for its root: " + forwarded);
                check(harness.published.isEmpty() && !harness.requests.pending(device) && harness.events.isEmpty(), "...and builds nothing for it (" + root + ")");
                check(harness.requests.status().roots().get(root) == 2, "...counted as " + root);
                check(HaSink.outcome(forwarded) == MessageBus.Outcome.ROOT, "The bus drops it for its root");
            }
        }
        check(HaSink.outcome(new TwinRequests.Sent("x", "light.turn_on")) == MessageBus.Outcome.TAKEN
                && HaSink.outcome(new TwinRequests.Skipped("already on")) == MessageBus.Outcome.TAKEN
                && HaSink.outcome(new TwinRequests.Held("light.turn_on")) == MessageBus.Outcome.TAKEN
                && HaSink.outcome(new TwinRequests.Refused(TwinRequests.Refusal.RATE, "too fast")) == MessageBus.Outcome.REFUSED,
                "Sent, held and needless requests are taken; a rate refusal is refused, not a root drop");
    }

    /**
     * D11's freshness-aware right-click on a lamp mirroring a light: one PLAYER toggle gives exactly one request,
     * {@code turn_off} from a LIVE on, {@code toggle} from a RETAINED value, and a local refusal (nothing sent) while the
     * entity is unavailable, cleared or unknown to the twin. An explicit value the LIVE mirror shows sends nothing
     * (dedupe); without a LIVE value it is sent. A lamp mirroring a switch switches it, a colour pick there is refused,
     * and a binary_sensor is read-only.
     */
    private static void lampToggles() {
        Requests harness = new Requests();
        UUID device = UUID.randomUUID();
        TwinRequests.Origin origin = origin(device, STEVE);
        TwinRequests.Result result = harness.requests.forward(origin, "light.kitchen", SmartLamp.TWIN, lampLight(), toggle(), HaCalls.Mirror.live("on"), Root.PLAYER);
        check(result instanceof TwinRequests.Sent && harness.actions().equals(List.of("light.turn_off")), "A toggle of a LIVE on is one turn_off: " + harness.actions());
        check(harness.last().get("target").getAsJsonObject().get("entity_id").getAsString().equals("light.kitchen")
                && harness.last().getAsJsonObject("origin").get("player").getAsString().equals("Steve"), "...for light.kitchen, by Steve: " + harness.last());
        harness.now += 2_000;
        harness.requests.forward(origin, "light.kitchen", SmartLamp.TWIN, lampLight(), toggle(), HaCalls.Mirror.retained("on"), Root.PLAYER);
        check(harness.actions().getLast().equals("light.toggle"), "A toggle of a RETAINED value is light.toggle: " + harness.actions());
        int sent = harness.published.size();
        for (HaCalls.Mirror unknown : List.of(HaCalls.Mirror.live("unavailable"), HaCalls.Mirror.retained("unknown"), HaCalls.Mirror.live(""), HaCalls.Mirror.NONE)) {
            harness.now += 2_000;
            TwinRequests.Result refused = harness.requests.forward(origin, "light.kitchen", SmartLamp.TWIN, lampLight(), toggle(), unknown, Root.PLAYER);
            check(refused instanceof TwinRequests.Refused local && local.refusal() == TwinRequests.Refusal.LOCAL, "A toggle of " + unknown + " is refused locally: " + refused);
        }
        check(harness.published.size() == sent, "...and sends nothing");
        harness.now += 2_000;
        check(harness.requests.forward(origin, "light.kitchen", SmartLamp.TWIN, lampLight(), command(Action.STATE, new JsonPrimitive("ON")), HaCalls.Mirror.live("on"),
                Root.WORLD) instanceof TwinRequests.Skipped && harness.published.size() == sent, "An explicit on while LIVE on sends nothing (dedupe)");
        check(harness.requests.forward(origin, "light.kitchen", SmartLamp.TWIN, lampLight(), command(Action.STATE, new JsonPrimitive("ON")), HaCalls.Mirror.retained("on"),
                Root.WORLD) instanceof TwinRequests.Sent && harness.actions().getLast().equals("light.turn_on"), "...but is sent while the value is only retained");
        harness.now += 2_000;
        JsonObject picked = new JsonObject();
        picked.addProperty("brightness", 128);
        JsonObject red = new JsonObject();
        red.addProperty("r", 255);
        red.addProperty("g", 0);
        red.addProperty("b", 0);
        picked.add("color", red);
        harness.requests.forward(origin, "light.kitchen", SmartLamp.TWIN, lampLight(), picked, HaCalls.Mirror.live("on"), Root.PLAYER);
        JsonObject data = harness.last().getAsJsonObject("data");
        check(harness.actions().getLast().equals("light.turn_on") && data.get("brightness").getAsInt() == 128
                && data.get("rgb_color").toString().equals("[255,0,0]"), "A picked colour and brightness are one turn_on with both: " + harness.last());
        harness.now += 2_000;
        JsonObject warm = new JsonObject();
        warm.addProperty("color_temp", 370);
        harness.requests.forward(origin, "light.kitchen", SmartLamp.TWIN, lampLight(), warm, HaCalls.Mirror.live("on"), Root.PLAYER);
        check(harness.last().getAsJsonObject("data").get("color_temp_kelvin").getAsInt() == 2703 && !harness.last().getAsJsonObject("data").has("rgb_color"),
                "A colour temperature in mireds is sent in kelvin: " + harness.last());

        Binding porch = Binding.of(SmartLamp.TWIN.sourceEnd("switch.porch")).withControl(true);
        harness.now += 2_000;
        harness.requests.forward(origin, "switch.porch", SmartLamp.TWIN, porch, toggle(), HaCalls.Mirror.live("off"), Root.PLAYER);
        check(harness.actions().getLast().equals("switch.turn_on"), "A lamp mirroring a switch switches it: " + harness.actions());
        sent = harness.published.size();
        check(harness.requests.forward(origin, "switch.porch", SmartLamp.TWIN, porch, picked, HaCalls.Mirror.live("on"), Root.PLAYER) instanceof TwinRequests.Refused,
                "A colour pick on a lamp mirroring a switch is refused");
        Binding door = Binding.of(SmartLamp.TWIN.sourceEnd("binary_sensor.door")).withControl(true);
        TwinRequests.Result door1 = harness.requests.forward(origin, "binary_sensor.door", SmartLamp.TWIN, door, toggle(), HaCalls.Mirror.live("on"), Root.PLAYER);
        check(door1 instanceof TwinRequests.Refused readOnly && readOnly.reason().contains("cannot be controlled"), "A binary_sensor is read-only: " + door1);
        check(harness.published.size() == sent, "...neither sends anything");
        check(harness.requests.forward(origin, "light.kitchen", SmartLamp.TWIN, lampLight().withControl(false), toggle(), HaCalls.Mirror.live("on"), Root.PLAYER)
                instanceof TwinRequests.Skipped && harness.published.size() == sent, "Without control the adapter asks for nothing");
    }

    /**
     * The rates (D10, D11), each over the last second: 5 toggles in a second leave 4, the 5th refused; a player's
     * requests over two devices are capped as well, and so is the bridge ({@code twin.maxRequestsPerSecond}). A
     * brightness over the rate waits instead, the newest replacing it, and is sent once when the second is over.
     */
    private static void rates() {
        Requests harness = new Requests();
        UUID device = UUID.randomUUID();
        List<TwinRequests.Result> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            harness.now += 150;
            JsonObject state = command(Action.STATE, new JsonPrimitive(i % 2 == 0 ? "ON" : "OFF"));
            results.add(harness.requests.forward(origin(device, null), "light.kitchen", SmartLamp.TWIN, lampLight(), state, HaCalls.Mirror.NONE, Root.WORLD));
        }
        check(harness.published.size() == 4 && results.getLast() instanceof TwinRequests.Held, "5 world switches in a second send 4, the 5th waits: " + results);
        harness.now += 1_000;
        harness.requests.tick();
        check(harness.published.size() == 5 && harness.last().get("action").getAsString().equals("light.turn_on"),
                "...and is sent in the next second, so the entity ends as the world left it (wave 5 finding 1)");
        harness.now += 1_000;
        check(harness.requests.forward(origin(device, null), "light.kitchen", SmartLamp.TWIN, lampLight(), toggle(), HaCalls.Mirror.live("on"), Root.WORLD)
                instanceof TwinRequests.Sent, "...and the next second sends again");

        // Over the rate, a brightness waits and the newest wins.
        Requests analog = new Requests();
        UUID dimmer = UUID.randomUUID();
        for (int brightness = 10; brightness <= 80; brightness += 10) {
            analog.now += 50;
            analog.requests.forward(origin(dimmer, null), "light.kitchen", SmartLamp.TWIN, lampLight(), command("brightness", new JsonPrimitive(brightness)),
                    HaCalls.Mirror.live("on"), Root.WORLD);
        }
        check(analog.published.size() == 4 && analog.requests.pending(), "4 brightnesses go at once, the rest waits: " + analog.published.size());
        analog.requests.tick();
        check(analog.published.size() == 4, "...while the second lasts");
        analog.now += 1_000;
        analog.requests.tick();
        check(analog.published.size() == 5 && analog.last().getAsJsonObject("data").get("brightness").getAsInt() == 80,
                "...then the newest one alone: " + analog.last());
        analog.now += 2_000;
        analog.requests.tick();
        check(analog.published.size() == 5, "...once");

        // A value held when the device's binding changes (control off, unbound, another entity) is never sent.
        Requests rebound = new Requests();
        UUID lamp = UUID.randomUUID();
        for (int brightness = 10; brightness <= 50; brightness += 10) {
            rebound.now += 50;
            rebound.requests.forward(origin(lamp, null), "light.kitchen", SmartLamp.TWIN, lampLight(), command("brightness", new JsonPrimitive(brightness)),
                    HaCalls.Mirror.live("on"), Root.WORLD);
        }
        check(rebound.requests.status().waiting() == 1, "The 5th brightness waits");
        rebound.requests.dropHeld(lamp);
        rebound.now += 1_000;
        rebound.requests.tick();
        check(rebound.published.size() == 4 && rebound.requests.status().waiting() == 0, "...and is dropped with the binding it was for");

        // A value held when twin.haActionAllowlist loses its domain (a config reload) is asked again and never sent.
        Requests reloaded = new Requests();
        UUID dimmed = UUID.randomUUID();
        for (int brightness = 10; brightness <= 50; brightness += 10) {
            reloaded.now += 50;
            reloaded.requests.forward(origin(dimmed, null), "light.kitchen", SmartLamp.TWIN, lampLight(), command("brightness", new JsonPrimitive(brightness)),
                    HaCalls.Mirror.live("on"), Root.WORLD);
        }
        check(reloaded.requests.status().waiting() == 1, "The 5th brightness waits");
        reloaded.allowed = List.of("switch");
        reloaded.now += 1_000;
        reloaded.requests.tick();
        check(reloaded.published.size() == 4 && reloaded.requests.status().waiting() == 0
                        && reloaded.requests.status().refused().getOrDefault(TwinRequests.Refusal.NOT_ALLOWED, 0L) == 1,
                "...and is dropped, counted as not allowed, once its domain left twin.haActionAllowlist: " + reloaded.requests.status());

        Requests player = new Requests();
        for (int i = 0; i < 5; i++) {
            player.now += 10;
            player.requests.forward(origin(UUID.randomUUID(), STEVE), "light.kitchen", SmartLamp.TWIN, lampLight(), command(Action.STATE, new JsonPrimitive("ON")),
                    HaCalls.Mirror.NONE, Root.PLAYER);
        }
        check(player.published.size() == TwinRequests.PLAYER_PER_SECOND, "A player causes at most 4 a second over any devices: " + player.published.size());

        Requests bridge = new Requests();
        bridge.bridge = 3;
        for (int i = 0; i < 5; i++) {
            bridge.now += 10;
            bridge.requests.forward(origin(UUID.randomUUID(), null), "light.kitchen", SmartLamp.TWIN, lampLight(), command(Action.STATE, new JsonPrimitive("ON")),
                    HaCalls.Mirror.NONE, Root.WORLD);
        }
        check(bridge.published.size() == 3, "The bridge sends at most twin.maxRequestsPerSecond: " + bridge.published.size());

        Requests notAllowed = new Requests();
        notAllowed.allowed = List.of("switch");
        TwinRequests.Result refused = notAllowed.requests.forward(origin(device, null), "light.kitchen", SmartLamp.TWIN, lampLight(), toggle(),
                HaCalls.Mirror.live("on"), Root.WORLD);
        check(refused instanceof TwinRequests.Refused allow && allow.refusal() == TwinRequests.Refusal.NOT_ALLOWED && notAllowed.published.isEmpty(),
                "A domain outside twin.haActionAllowlist is refused: " + refused);
    }

    /**
     * The circuit breaker (D10): requests the world caused, spaced within the rate as a loop through delayed redstone sends
     * them, pass 8 times in 10 s; the 9th trips it, pausing the device's control (CONTROL_PAUSED; the runtime tells the
     * owner). A paused device sends nothing, a player's command included, until it is resumed, and then counts anew. A
     * player's own commands are not counted (wave 4 review): dragging a picker for 10 s sends at most 4 a second and
     * never pauses the lamp, while the same stream from the world does.
     */
    private static void breaker() {
        Requests harness = new Requests();
        UUID device = UUID.randomUUID();
        TwinRequests.Result last = null;
        for (int i = 1; i <= 9; i++) {
            harness.now += 300;
            last = harness.requests.forward(origin(device, null), "light.kitchen", SmartLamp.TWIN, lampLight(),
                    command(Action.STATE, new JsonPrimitive(i % 2 == 0 ? "ON" : "OFF")), HaCalls.Mirror.NONE, Root.WORLD);
            if (i <= TwinRequests.BREAKER_LIMIT) check(last instanceof TwinRequests.Sent, "Request " + i + " in 10 s is sent: " + last);
        }
        check(last instanceof TwinRequests.Refused paused && paused.refusal() == TwinRequests.Refusal.PAUSED && harness.published.size() == 8,
                "The 9th request in 10 s trips the breaker: " + last);
        check(harness.requests.paused(device) && harness.events.contains("paused " + device)
                && harness.notices.stream().anyMatch(notice -> notice.kind() == TwinRequests.Notice.Kind.PAUSED && notice.player() == null),
                "...pausing the device's control and noticing it");
        harness.now += 60_000;
        check(harness.requests.forward(origin(device, STEVE), "light.kitchen", SmartLamp.TWIN, lampLight(), toggle(), HaCalls.Mirror.live("on"), Root.PLAYER)
                instanceof TwinRequests.Refused && harness.published.size() == 8, "A paused device sends nothing, however long it waits");
        check(harness.requests.resume(device) && !harness.requests.paused(device) && harness.events.contains("resumed " + device), "Resume ends the pause");
        check(harness.requests.forward(origin(device, STEVE), "light.kitchen", SmartLamp.TWIN, lampLight(), toggle(), HaCalls.Mirror.live("on"), Root.PLAYER)
                instanceof TwinRequests.Sent, "...and the device sends again");
        check(!harness.requests.resume(device), "Resuming a device that is not paused changes nothing");

        // A player drags the brightness picker for 10 s: the Controls tab sends every 100 ms, the server ticks every 50 ms.
        int[] dragged = drag(STEVE, Root.PLAYER);
        check(dragged[0] == 0, "A player's 10 s drag never pauses the lamp");
        check(dragged[1] >= 30 && dragged[2] <= TwinRequests.DEVICE_PER_SECOND, "...sends steadily, at most 4 in any second: " + dragged[1] + " sent, "
                + dragged[2] + " in the busiest second");
        check(drag(null, Root.WORLD)[0] == 1, "The same stream from the world trips the breaker");
    }

    /**
     * A brightness that changes every 100 ms for 10 s, ticking every 50 ms, caused by {@code player} in {@code root}.
     *
     * @return whether the device ended paused (1) or not (0), the requests published, and the most published in any second
     */
    private static int[] drag(TwinRequests.Requester player, Root root) {
        Requests harness = new Requests();
        UUID device = UUID.randomUUID();
        List<Long> times = new ArrayList<>();
        for (int step = 0; step < 200; step++) {
            harness.now += 50;
            int before = harness.published.size();
            if (step % 2 == 0) {
                harness.requests.forward(origin(device, player), "light.kitchen", SmartLamp.TWIN, lampLight(),
                        command("brightness", new JsonPrimitive(1 + step)), HaCalls.Mirror.live("on"), root);
            }
            harness.requests.tick();
            for (int i = before; i < harness.published.size(); i++) times.add(harness.now);
        }
        int busiest = 0;
        for (int i = 0; i < times.size(); i++) {
            int inSecond = 0;
            for (int j = i; j < times.size() && times.get(j) - times.get(i) < TwinRequests.WINDOW_MILLIS; j++) inSecond++;
            busiest = Math.max(busiest, inSecond);
        }
        return new int[]{harness.requests.paused(device) ? 1 : 0, times.size(), busiest};
    }

    /** D11: a request is never kept for later; while the bridge is offline it is refused, so the player hears it. */
    private static void offline() {
        Requests harness = new Requests();
        harness.connected = false;
        TwinRequests.Result result = harness.requests.forward(origin(UUID.randomUUID(), STEVE), "light.kitchen", SmartLamp.TWIN, lampLight(), toggle(),
                HaCalls.Mirror.live("on"), Root.PLAYER);
        check(result instanceof TwinRequests.Refused offline && offline.refusal() == TwinRequests.Refusal.OFFLINE && offline.reason().contains("offline")
                && harness.published.isEmpty(), "While offline a request is dropped and said so: " + result);
        check(harness.requests.ping(answer -> {
        }).isPresent() && !harness.requests.sync("command"), "Nor is a ping or a sync sent");
        harness.connected = true;
        harness.requests.forward(origin(UUID.randomUUID(), STEVE), "light.kitchen", SmartLamp.TWIN, lampLight(), toggle(), HaCalls.Mirror.live("on"), Root.PLAYER);
        check(harness.requests.ping(answer -> {
        }).isEmpty() && harness.requests.sync("command") && harness.published.size() == 3, "Online a request, a ping and a sync go out");
        check(harness.published.stream().allMatch(message -> message.offline() == OfflinePolicy.DROP && !message.retain()),
                "...each as the service drops it while offline, never retained");
    }

    /**
     * {@code ha_response} is advisory (D11): it ends a request's wait or tells why HA refused it, and nothing else. A
     * lamp's light stays as it is through every answer, forged ones included; only a TWIN apply (here: the entity applied
     * again) changes it. A retained, oversized or unknown answer is dropped; a request without answer times out.
     */
    private static void answers() {
        Requests harness = new Requests();
        UUID device = UUID.randomUUID();
        TwinRequests.Sent sent = (TwinRequests.Sent) harness.requests.forward(origin(device, STEVE), "light.kitchen", SmartLamp.TWIN, lampLight(), toggle(),
                HaCalls.Mirror.live("off"), Root.PLAYER);
        check(harness.requests.pending(device) && harness.events.equals(List.of("pending " + device)), "A sent request waits: " + harness.events);
        harness.answer(sent.id(), "accepted", true);
        harness.answer(sent.id(), "not_labelled", true);
        check(harness.requests.pending(device) && harness.requests.status().droppedAnswers() == 2, "A retained answer is dropped");
        JsonObject big = new JsonObject();
        big.addProperty("id", sent.id());
        big.addProperty("status", "not_labelled");
        big.addProperty("pad", "x".repeat(TwinRequests.MAX_RESPONSE_BYTES));
        harness.requests.response(big.toString().getBytes(StandardCharsets.UTF_8), false);
        harness.requests.response("not json".getBytes(StandardCharsets.UTF_8), false);
        check(harness.requests.pending(device) && harness.requests.status().droppedAnswers() == 4, "An oversized or malformed answer is dropped");
        harness.answer("ffffffffffff", "not_labelled", false);
        check(harness.requests.pending(device) && harness.requests.status().unmatched() == 1, "An answer naming no waiting request is ignored");
        harness.answer(sent.id(), "accepted", false);
        check(harness.requests.pending(device) && harness.notices.isEmpty(), "accepted keeps it waiting for the entity to change");
        harness.requests.applied("light.kitchen");
        check(!harness.requests.pending(device) && harness.events.getLast().equals("done " + device), "The entity's next apply ends the wait");

        harness.now += 2_000;
        TwinRequests.Sent refused = (TwinRequests.Sent) harness.requests.forward(origin(device, STEVE), "light.kitchen", SmartLamp.TWIN, lampLight(), toggle(),
                HaCalls.Mirror.live("off"), Root.PLAYER);
        harness.answer(refused.id(), "not_labelled", false);
        check(!harness.requests.pending(device) && harness.notices.size() == 1 && harness.notices.getFirst().kind() == TwinRequests.Notice.Kind.REFUSED
                && harness.notices.getFirst().detail().equals("not_labelled") && STEVE.equals(harness.notices.getFirst().player()),
                "A refusal ends the wait and tells the player why: " + harness.notices);
        check(harness.requests.answer(device).orElseThrow().status().equals("not_labelled"), "...and is kept as the device's last answer");

        harness.now += 2_000;
        harness.requests.forward(origin(device, STEVE), "light.kitchen", SmartLamp.TWIN, lampLight(), toggle(), HaCalls.Mirror.live("off"), Root.PLAYER);
        harness.now += PendingRequests.TIMEOUT_MILLIS - 1;
        harness.requests.tick();
        check(harness.requests.pending(device), "A request waits up to 5 s");
        harness.now += 1;
        harness.requests.tick();
        check(!harness.requests.pending(device) && harness.notices.getLast().kind() == TwinRequests.Notice.Kind.TIMEOUT,
                "...then its player hears: no change from Home Assistant");
        // Forged answers of every status for a request that waits: they reach the listener as the end of a wait or a
        // notice at most (its only methods; none applies anything to a device), and nothing is sent.
        harness.now += 2_000;
        TwinRequests.Sent waiting = (TwinRequests.Sent) harness.requests.forward(origin(device, STEVE), "light.kitchen", SmartLamp.TWIN, lampLight(), toggle(),
                HaCalls.Mirror.live("off"), Root.PLAYER);
        int published = harness.published.size();
        for (String status : List.of("accepted", "pong", "on", "{\"state\":\"ON\"}", "bad_request", "domain_mismatch", "no_such_entity", "domain_not_allowed",
                "action_not_allowed", "not_labelled")) {
            harness.answer(waiting.id(), status, false);
            harness.answer(sent.id(), status, false);
            harness.answer(refused.id(), status, false);
        }
        check(harness.published.size() == published, "No answer sends anything");
        check(harness.events.stream().allMatch(event -> event.startsWith("pending ") || event.startsWith("done ")), "...or does anything but end a wait: " + harness.events);
        check(harness.notices.getLast().kind() == TwinRequests.Notice.Kind.REFUSED && harness.notices.getLast().detail().equals("on"),
                "An unknown status ends the wait as a refusal, its text only shown sanitised");
        check(DeviceRuntime.answerText("§cforged\nline").getContents() instanceof TranslatableContents translatable
                && translatable.getKey().equals("mineqtt.twin.status.other") && translatable.getArgs()[0] instanceof String shown
                && !shown.contains("§") && !shown.contains("\n") && shown.contains("forged"), "A forged status reaches players sanitised");
        check(DeviceRuntime.answerText("not_labelled").getContents() instanceof TranslatableContents known
                && known.getKey().equals("mineqtt.twin.status.not_labelled"), "A known status has its sentence");

        Requests ping = new Requests();
        List<String> heard = new ArrayList<>();
        check(ping.requests.ping(heard::add).isEmpty() && ping.last().get("ping").getAsBoolean() && !ping.last().has("action"), "A ping is a request without an action");
        ping.answer(ping.last().get("id").getAsString(), "pong", false);
        check(heard.size() == 1 && heard.getFirst().startsWith("pong"), "...answered by pong: " + heard);
        check(ping.requests.ping(heard::add).isEmpty(), "Another ping may follow");
        ping.now += TwinRequests.PING_TIMEOUT_MILLIS;
        ping.requests.tick();
        check(heard.size() == 2 && heard.getLast().startsWith("no answer"), "...and one without answer says so after 5 s: " + heard);
        check(ping.requests.sync("command") && ping.published.getLast().topic().equals("mineqtt/check/bridge/ha_sync"), "A sync goes to bridge/ha_sync");
    }

    /**
     * The server's start never switches a real device (D10): {@link DeviceRuntime#powerOnRecords} powers on an unloaded
     * Redstone Output whose power-on behaviour is on, and its changed out-ports, wired into a mirrored device, reach the
     * forward as {@code STARTUP}, which the gate refuses. Without the power-on's extent the change would be the world's.
     */
    private static void powerOn() {
        if (DeviceTypes.get(RedstoneOutput.TYPE.id()) == null) DeviceTypes.registerBuiltIn();
        DeviceRegistry registry = new DeviceRegistry("power_on_0001", UUID.randomUUID());
        DeviceRecord output = new DeviceRecord(UUID.randomUUID(), RedstoneOutput.TYPE.id(), GlobalPos.of(Level.OVERWORLD, new BlockPos(0, -60, 0)), "output", "output");
        output.setLastState(new RedstoneOutput.Output(0, 15, RedstoneOutput.Mode.LEVEL, RedstoneOutput.PowerOn.ON).write(new StateWriter()).json());
        registry.add(output);
        UUID mirrored = UUID.randomUUID();
        Endpoint outputPower = Endpoint.port(output.id(), "power"), mirroredPower = Endpoint.port(mirrored, "power");
        PortSpec in = TYPE.port(Port.Direction.IN, "power").orElseThrow();
        Requests harness = new Requests();
        List<Root> roots = new ArrayList<>();
        MessageBus bus = new MessageBus(new MessageBus.Wiring() {
            @Override
            public List<Wire> from(Endpoint source) {
                return source.equals(outputPower) ? List.of(wire(outputPower, mirroredPower)) : List.of();
            }

            @Override
            public Optional<? extends PortSpec> out(Endpoint source) {
                return RedstoneOutput.TYPE.port(Port.Direction.OUT, ((Endpoint.PortRef) source).port());
            }

            @Override
            public Optional<? extends PortSpec> in(Endpoint target) {
                return Optional.of(in);
            }
        }, (wire, message) -> {
            roots.add(message.root());
            JsonObject command = TYPE.inPortCommand("power", message.payload());
            return HaSink.outcome(harness.requests.forward(origin(mirrored, null), "switch.porch", ADAPTER, SWITCH, command, HaCalls.Mirror.NONE, message.root()));
        }, new MessageBus.Listener() {
            @Override
            public void loopCut(Wire wire, FlowMessage message) {
            }

            @Override
            public void failed(Wire wire, FlowMessage message, RuntimeException e) {
                throw new AssertionError("Delivery threw", e);
            }
        }, System::nanoTime);
        Set<UUID> changed = DeviceRuntime.powerOnRecords(registry, bus, (source, key, value) -> bus.emit(FlowMessage.Kind.VALUE, source, key, value, 0),
                (record, e) -> {
                    throw new AssertionError("Power-on threw", e);
                });
        check(changed.equals(Set.of(output.id())) && RedstoneOutput.Output.of(output.lastState()).power() == 15, "The output powers on at its last level");
        drainAll(bus);
        check(roots.equals(List.of(Root.STARTUP)), "Its change reaches the mirrored device as STARTUP: " + roots);
        check(harness.published.isEmpty() && bus.dropped(MessageBus.Drop.ROOT) == 1, "...which sends nothing and is dropped for its root");
        check(bus.currentRoot().isEmpty(), "The power-on's extent ends with it");

        // M3-12: a mirrored output's source owns its level, so its power-on behaviour does not apply (D6): it keeps the
        // level it had until the source's value lands, and nothing leaves its ports.
        DeviceRecord bound = new DeviceRecord(UUID.randomUUID(), RedstoneOutput.TYPE.id(), GlobalPos.of(Level.OVERWORLD, new BlockPos(2, -60, 0)), "door", "door");
        JsonObject held = new RedstoneOutput.Output(15, 15, RedstoneOutput.Mode.LEVEL, RedstoneOutput.PowerOn.OFF).write(new StateWriter()).json();
        bound.setLastState(held);
        bound.setTwin(Binding.of(RedstoneOutput.TWIN.sourceEnd("binary_sensor.front_door")).withRefused("refused or not, it is bound"));
        registry.add(bound);
        roots.clear();
        Set<UUID> again = DeviceRuntime.powerOnRecords(registry, bus, (source, key, value) -> bus.emit(FlowMessage.Kind.VALUE, source, key, value, 0),
                (record, e) -> {
                    throw new AssertionError("Power-on threw", e);
                });
        drainAll(bus);
        check(!again.contains(bound.id()) && bound.lastState().equals(held) && roots.isEmpty(), "A bound output keeps its level at power-on: " + bound.lastState());
    }

    /**
     * M3-8, D9: a light turning on arrives as {@code state}, then its attributes, within one drain; the lamp applies the
     * entity once, at the next drain, with the colour and brightness together (no flash of the old colour). Topics that
     * keep coming apply at most every {@value TwinCoalescer#MAX_WAIT_DRAINS} drains, and never more than
     * {@value TwinCoalescer#APPLIES_PER_SECOND} times a second.
     */
    private static void lampBurst() {
        EntityDocuments documents = new EntityDocuments("ha_statestream", EntityDocuments.Limits.DEFAULT);
        TwinCoalescer coalescer = new TwinCoalescer();
        long drain = 0;
        for (String[] leaf : new String[][]{{"state", "on"}, {"last_updated", "2026-10-11T04:48:44.639470+00:00"}, {"brightness", "128"}, {"rgb_color", "[255, 0, 0]"}}) {
            documents.accept("ha_statestream/light/m3_lamp/" + leaf[0], leaf[1].getBytes(StandardCharsets.UTF_8), false, 0)
                    .changed().forEach(entity -> coalescer.changed(entity, 0));
        }
        check(coalescer.due(drain, 0).isEmpty(), "A burst still arriving in this drain waits");
        List<String> due = coalescer.due(++drain, 50);
        check(due.equals(List.of("light.m3_lamp")) && coalescer.due(++drain, 100).isEmpty(), "...and applies once at the next drain: " + due);
        Binding binding = Binding.of(SmartLamp.TWIN.sourceEnd("light.m3_lamp"));
        ValueCodecs.LightState light = ValueCodecs.LightState.DEFAULT.merge(SmartLamp.TYPE.twinCommand(documents.document("light.m3_lamp").orElseThrow(), binding).orElseThrow());
        check(light.equals(new ValueCodecs.LightState(true, 128, ValueCodecs.Rgb.ofPacked(0xFF0000), null)), "...red at 128 in one step: " + light);

        // An effect such as rainbow republishes the colour every drain for 3 s.
        int applies = 0;
        long now = 1000;
        for (int i = 0; i < 60; i++) {
            coalescer.changed("light.m3_lamp", drain);
            applies += coalescer.due(drain, now).size();
            drain++;
            now += 50;
        }
        check(applies >= 3 && applies <= 3 * TwinCoalescer.APPLIES_PER_SECOND, "A constant republish applies every few drains, at most 4/s: " + applies);
        coalescer.forget("light.m3_lamp");
        check(!coalescer.pending(), "An entity nobody mirrors waits for nothing");

        // The per-entity cap itself (wave 4 review): the bursts above settle on MAX_WAIT_DRAINS, which alone spaces them
        // 300 ms apart. Bursts that settle after one quiet drain (a change every other drain, 50 ms drains), and constant
        // changes on a paused server's pump (10 ms drains), would apply 10 and 16 times a second without the cap.
        for (long[] pattern : new long[][]{{50, 2}, {10, 1}}) {
            List<Long> times = burstApplies(pattern[0], (int) pattern[1]);
            check(times.size() >= 10, "Changes every " + pattern[1] + " drain(s) of " + pattern[0] + " ms keep applying: " + times.size());
            for (int i = 1; i < times.size(); i++) {
                check(times.get(i) - times.get(i - 1) >= 1000 / TwinCoalescer.APPLIES_PER_SECOND,
                        "...at most 4 times a second (" + pattern[0] + " ms drains): applies at " + times);
            }
        }
    }

    // When an entity that changes every `every` drains, for 5 s of `drainMillis` drains, applies.
    private static List<Long> burstApplies(long drainMillis, int every) {
        TwinCoalescer coalescer = new TwinCoalescer();
        List<Long> times = new ArrayList<>();
        long now = 0;
        for (long drain = 0; now < 5_000; drain++, now += drainMillis) {
            if (drain % every == 0) coalescer.changed("light.rainbow", drain);
            if (!coalescer.due(drain, now).isEmpty()) times.add(now);
        }
        return times;
    }

    /**
     * M3-8: 1000 seeded statestream sequences into a lamp, as the twin carries them: folded into documents
     * ({@link EntityDocuments}), coalesced ({@link TwinCoalescer}), emitted in a {@code TWIN} extent over the lamp's derived
     * wire ({@link TwinWiring}) on a real {@link MessageBus}, read by the lamp's adapter and merged into its light, whose
     * change it emits to a lamp wired after it. Every delivery, and everything emitted downstream, carries {@code TWIN};
     * each command fills only the keys the source owns and routes as a {@code TWIN} apply (or drop while unloaded), never
     * a forward; and the adapter yields no call for any of them, control or not.
     */
    private static void lampSequences() {
        Random random = new Random(0x4d338L);
        UUID lamp = UUID.randomUUID(), next = UUID.randomUUID();
        Endpoint lampOut = Endpoint.port(lamp, "light"), nextIn = Endpoint.port(next, "light");
        // The bus's rates run on the sequences' clock, 50 ms a drain, as on a server.
        long[] nanos = {0};
        PortSpec lightIn = SmartLamp.TYPE.port(Port.Direction.IN, "light").orElseThrow(), lightOut = SmartLamp.TYPE.port(Port.Direction.OUT, "light").orElseThrow();
        Wire downstream = wire(lampOut, nextIn);
        TwinWiring twin = new TwinWiring();
        String[] entities = {"light.kitchen", "switch.porch", "binary_sensor.door", "fan.attic"};
        String[] states = {"on", "off", "unavailable", "unknown", "", "opening", "ON", "true", "42"};
        String[] attributeValues = {"128", "0", "255", "999", "null", "[255, 0, 0]", "[12, 200, 7]", "\"hs\"", "\"color_temp\"", "2700", "NaN", "garbage", ""};
        String[] attributes = {"brightness", "rgb_color", "color_temp_kelvin", "color_mode", "last_updated"};
        EntityDocuments[] documents = new EntityDocuments[1];
        Binding[] binding = new Binding[1];
        ValueCodecs.LightState[] light = new ValueCodecs.LightState[1];
        List<Root> roots = new ArrayList<>();
        int[] applied = new int[1];
        MessageBus[] holder = new MessageBus[1];
        Requests requests = new Requests();
        MessageBus bus = new MessageBus(new MessageBus.Wiring() {
            @Override
            public List<Wire> from(Endpoint source) {
                return source instanceof Endpoint.HaEntityRef ? twin.from(source) : source.equals(lampOut) ? List.of(downstream) : List.of();
            }

            @Override
            public Optional<? extends PortSpec> out(Endpoint source) {
                if (source instanceof Endpoint.HaEntityRef entity) {
                    return HaDomains.domain(TwinAdapter.domainOf(entity.entityId())).flatMap(domain -> domain.readPort(entity.port()));
                }
                return Optional.of(lightOut);
            }

            @Override
            public Optional<? extends PortSpec> in(Endpoint target) {
                return Optional.of(lightIn);
            }
        }, (wire, message) -> {
            roots.add(message.root());
            if (wire.scope() != Scope.TWIN) return MessageBus.Outcome.TAKEN;
            String entity = ((Endpoint.HaEntityRef) wire.from()).entityId();
            Optional<JsonObject> command = SmartLamp.TYPE.twinCommand(documents[0].document(entity).orElseThrow(), binding[0]);
            if (command.isEmpty()) return MessageBus.Outcome.TAKEN;
            check(SmartLamp.TWIN.mirroredKeys().containsAll(command.get().keySet()), "A read fills the keys the source owns only: " + command.get());
            DeviceRuntime.Mirrored mirrored = new DeviceRuntime.Mirrored(SmartLamp.TWIN.touchesMirror(command.get()), binding[0].controls());
            check(DeviceRuntime.route(false, SmartLamp.TYPE, true, CommandSource.TWIN, mirrored) == DeviceRuntime.Route.APPLY
                    && DeviceRuntime.route(false, SmartLamp.TYPE, false, CommandSource.TWIN, mirrored) == DeviceRuntime.Route.DROP, "...and applies as TWIN, never forwarding");
            // Even handed to the write-back as it is, with control on, a read in its TWIN extent sends nothing.
            for (HaCalls.Mirror mirror : List.of(HaCalls.Mirror.NONE, HaCalls.Mirror.live("on"))) {
                TwinRequests.Result result = requests.requests.forward(origin(lamp, null), entity, SmartLamp.TWIN, binding[0].withControl(true), command.get(), mirror,
                        message.root());
                check(result instanceof TwinRequests.Refused refused && refused.refusal() == TwinRequests.Refusal.ROOT && requests.published.isEmpty(),
                        "Reading never yields a request: " + result);
            }
            applied[0]++;
            ValueCodecs.LightState merged = light[0].merge(command.get());
            if (!merged.equals(light[0])) {
                light[0] = merged;
                // The lamp publishes its new state on the stack of the delivery, as its block entity does.
                holder[0].emit(FlowMessage.Kind.VALUE, lampOut, "light", SmartLamp.command(merged), 0);
            }
            return MessageBus.Outcome.TAKEN;
        }, new MessageBus.Listener() {
            @Override
            public void loopCut(Wire wire, FlowMessage message) {
                throw new AssertionError("A twin wire is no loop");
            }

            @Override
            public void failed(Wire wire, FlowMessage message, RuntimeException e) {
                throw new AssertionError("Delivery threw", e);
            }
        }, () -> nanos[0]);
        holder[0] = bus;

        int bursts = 0;
        for (int round = 0; round < 1000; round++) {
            String entity = entities[random.nextInt(entities.length)];
            documents[0] = new EntityDocuments("ha_statestream", EntityDocuments.Limits.DEFAULT);
            binding[0] = Binding.of(SmartLamp.TWIN.sourceEnd(entity)).withSettings(Binding.Settings.DEFAULT
                    .withUnavailable(random.nextBoolean() ? Binding.Unavailable.HOLD : Binding.Unavailable.OFF));
            light[0] = ValueCodecs.LightState.DEFAULT;
            twin.set(lamp, TwinWiring.sourceWire(lamp, "light", SmartLamp.TWIN.sourceEnd(entity), UUID.randomUUID())).ifPresent(old -> bus.forgetWire(old.id()));
            bus.forgetWire(twin.of(lamp).orElseThrow().id());
            TwinCoalescer coalescer = new TwinCoalescer();
            String topic = "ha_statestream/" + entity.replace('.', '/') + "/";
            long drain = 0, now = round * 100_000L;
            int steps = 1 + random.nextInt(12);
            for (int step = 0; step <= steps + TwinCoalescer.MAX_WAIT_DRAINS + 6; step++) {
                if (step < steps) {
                    String leaf = random.nextInt(3) == 0 ? "state" : attributes[random.nextInt(attributes.length)];
                    String value = leaf.equals("state") ? states[random.nextInt(states.length)] : attributeValues[random.nextInt(attributeValues.length)];
                    if (leaf.equals("last_updated") && random.nextBoolean()) value = "2026-10-11T04:48:4" + random.nextInt(10) + "+00:00";
                    long drainNow = drain;
                    documents[0].accept(topic + leaf, value.getBytes(StandardCharsets.UTF_8), random.nextInt(4) == 0, now)
                            .changed().forEach(changed -> coalescer.changed(changed, drainNow));
                    // Several topics often share a drain, as a burst does.
                    if (random.nextInt(3) != 0) continue;
                }
                List<String> due = coalescer.due(drain, now);
                bursts += due.size();
                for (String changed : due) {
                    JsonObject payload = documents[0].document(changed).orElseThrow().toJson();
                    bus.withRoot(Root.TWIN, () -> bus.emit(FlowMessage.Kind.VALUE, SmartLamp.TWIN.sourceEnd(changed), changed, payload, 0));
                }
                bus.drain();
                drain++;
                now += 50;
                nanos[0] += 50_000_000L;
            }
            check(!coalescer.pending() && !bus.hasQueued(), "Every burst applied in the end");
        }
        check(bursts > 500 && applied[0] > 100, "The sequences applied: " + bursts + " bursts, " + applied[0] + " reads");
        check(!roots.isEmpty() && roots.stream().allMatch(root -> root == Root.TWIN), "Every delivery and every downstream emission carries TWIN");
        check(bus.dropped(MessageBus.Drop.ROOT) == 0 && bus.dropped(MessageBus.Drop.FAILED) == 0, "Nothing failed or was refused for its root");
    }

    /**
     * M3-12: 1000 seeded statestream sequences each into a Redstone Output and a Smart Plug, as the twin carries them:
     * folded, coalesced, emitted in a {@code TWIN} extent over the device's derived wire on a real {@link MessageBus} and
     * read by its adapter, with random settings (invert, range, path, unavailable). Every delivery carries {@code TWIN};
     * each command fills only the device's level with 0 to 15 and routes as a {@code TWIN} apply (or drop while
     * unloaded); handed to the write-back with control on, in its extent, it sends nothing.
     */
    private static void levelSequences() {
        levelSequences(RedstoneOutput.TYPE, new String[]{"switch.porch", "binary_sensor.door", "lock.front", "cover.garage", "input_number.level",
                "sensor.outdoor", "climate.hall", "input_select.mode", "light.porch"}, 0x4d340L);
        levelSequences(SmartPlug.TYPE, new String[]{"switch.heater", "input_boolean.flag", "light.desk", "fan.attic"}, 0x4d341L);
    }

    private static void levelSequences(DeviceType type, String[] entities, long seed) {
        TwinAdapter adapter = type.twin().orElseThrow();
        Random random = new Random(seed);
        UUID device = UUID.randomUUID();
        long[] nanos = {0};
        TwinWiring twin = new TwinWiring();
        String[] states = {"on", "off", "unavailable", "unknown", "", "opening", "unlocking", "locked", "open", "closed", "42.5", "-3", "1e400", "NaN", "Eco", "Turbo Mode"};
        String[] attributes = {"min", "max", "step", "options", "brightness", "percentage", "current_temperature", "last_updated"};
        String[] attributeValues = {"0", "100", "0.5", "255", "-20", "null", "[\"Off\", \"Eco\", \"Turbo Mode\"]", "garbage", "NaN", "\"x\"", ""};
        String[] paths = {"attributes.brightness", "attributes.percentage", "attributes.current_temperature", "state", "last_updated"};
        EntityDocuments[] documents = new EntityDocuments[1];
        Binding[] binding = new Binding[1];
        List<Root> roots = new ArrayList<>();
        int[] applied = new int[1];
        Requests requests = new Requests();
        PortSpec powerIn = type.port(Port.Direction.IN, adapter.primary()).orElseThrow();
        MessageBus bus = new MessageBus(new MessageBus.Wiring() {
            @Override
            public List<Wire> from(Endpoint source) {
                return twin.from(source);
            }

            @Override
            public Optional<? extends PortSpec> out(Endpoint source) {
                return source instanceof Endpoint.HaEntityRef entity
                        ? HaDomains.domain(TwinAdapter.domainOf(entity.entityId())).flatMap(domain -> domain.readPort(entity.port())) : Optional.empty();
            }

            @Override
            public Optional<? extends PortSpec> in(Endpoint target) {
                return Optional.of(powerIn);
            }
        }, (wire, message) -> {
            roots.add(message.root());
            String entity = ((Endpoint.HaEntityRef) wire.from()).entityId();
            EntityDocument document = documents[0].document(entity).orElseThrow();
            Optional<JsonObject> command = type.twinCommand(document, binding[0]);
            if (command.isEmpty()) return MessageBus.Outcome.TAKEN;
            int level = command.get().get(adapter.primary()).getAsInt();
            check(command.get().keySet().equals(Set.of(adapter.primary())) && level >= 0 && level <= 15, type + ": a read fills the level only, 0 to 15: " + command.get());
            DeviceRuntime.Mirrored mirrored = new DeviceRuntime.Mirrored(adapter.touchesMirror(command.get()), binding[0].controls());
            check(DeviceRuntime.route(false, type, true, CommandSource.TWIN, mirrored) == DeviceRuntime.Route.APPLY
                    && DeviceRuntime.route(false, type, false, CommandSource.TWIN, mirrored) == DeviceRuntime.Route.DROP, "...and applies as TWIN, never forwarding");
            HaCalls.Mirror known = new HaCalls.Mirror(HaCalls.Mirror.Freshness.LIVE, document.state().wire(), document.toJson());
            for (HaCalls.Mirror mirror : List.of(HaCalls.Mirror.NONE, known)) {
                TwinRequests.Result result = requests.requests.forward(origin(device, null), entity, adapter, binding[0].withControl(true), command.get(), mirror, message.root());
                check(result instanceof TwinRequests.Refused refused && refused.refusal() == TwinRequests.Refusal.ROOT && requests.published.isEmpty(),
                        type + ": reading never yields a request: " + result);
            }
            applied[0]++;
            return MessageBus.Outcome.TAKEN;
        }, new MessageBus.Listener() {
            @Override
            public void loopCut(Wire wire, FlowMessage message) {
                throw new AssertionError("A twin wire is no loop");
            }

            @Override
            public void failed(Wire wire, FlowMessage message, RuntimeException e) {
                throw new AssertionError("Delivery threw", e);
            }
        }, () -> nanos[0]);

        for (int round = 0; round < 1000; round++) {
            String entity = entities[random.nextInt(entities.length)];
            documents[0] = new EntityDocuments("ha_statestream", EntityDocuments.Limits.DEFAULT);
            Binding.Settings settings = Binding.Settings.DEFAULT.withUnavailable(random.nextBoolean() ? Binding.Unavailable.HOLD : Binding.Unavailable.OFF);
            if (adapter.shapes().contains(TwinAdapter.Shape.INVERT)) settings = settings.withInvert(random.nextBoolean());
            if (adapter.shapes().contains(TwinAdapter.Shape.RANGE) && random.nextBoolean()) {
                settings = settings.withMin(BigDecimal.valueOf(random.nextInt(50) - 25)).withMax(BigDecimal.valueOf(random.nextInt(300) - 25));
            }
            if (adapter.shapes().contains(TwinAdapter.Shape.PATH) && random.nextInt(3) == 0) {
                ValuePath path = ValuePath.parse(paths[random.nextInt(paths.length)]);
                if (TwinRules.pathProblem(TwinAdapter.domainOf(entity), path).isEmpty()) settings = settings.withPath(path);
            }
            binding[0] = Binding.of(adapter.sourceEnd(entity)).withSettings(settings);
            twin.set(device, TwinWiring.sourceWire(device, adapter.primary(), adapter.sourceEnd(entity), UUID.randomUUID())).ifPresent(old -> bus.forgetWire(old.id()));
            bus.forgetWire(twin.of(device).orElseThrow().id());
            TwinCoalescer coalescer = new TwinCoalescer();
            String topic = "ha_statestream/" + entity.replace('.', '/') + "/";
            long drain = 0, now = round * 100_000L;
            int steps = 1 + random.nextInt(12);
            for (int step = 0; step <= steps + TwinCoalescer.MAX_WAIT_DRAINS + 6; step++) {
                if (step < steps) {
                    String leaf = random.nextInt(3) == 0 ? "state" : attributes[random.nextInt(attributes.length)];
                    String value = leaf.equals("state") ? states[random.nextInt(states.length)] : attributeValues[random.nextInt(attributeValues.length)];
                    long drainNow = drain;
                    documents[0].accept(topic + leaf, value.getBytes(StandardCharsets.UTF_8), random.nextInt(4) == 0, now)
                            .changed().forEach(changed -> coalescer.changed(changed, drainNow));
                    if (random.nextInt(3) != 0) continue;
                }
                for (String changed : coalescer.due(drain, now)) {
                    JsonObject payload = documents[0].document(changed).orElseThrow().toJson();
                    bus.withRoot(Root.TWIN, () -> bus.emit(FlowMessage.Kind.VALUE, adapter.sourceEnd(changed), changed, payload, 0));
                }
                bus.drain();
                drain++;
                now += 50;
                nanos[0] += 50_000_000L;
            }
            check(!coalescer.pending() && !bus.hasQueued(), type + ": every burst applied in the end");
        }
        check(applied[0] > 300, type + ": the sequences applied: " + applied[0] + " reads");
        check(!roots.isEmpty() && roots.stream().allMatch(root -> root == Root.TWIN), type + ": every delivery carries TWIN");
        check(bus.dropped(MessageBus.Drop.ROOT) == 0 && bus.dropped(MessageBus.Drop.FAILED) == 0, type + ": nothing failed or was refused for its root");
    }

    /**
     * M3-12 write-back through {@link TwinRequests}: a player's level on an output mirroring an {@code input_number} is one
     * {@code set_value} of the value it stands for; an output or a plug mirroring a {@code binary_sensor} never forwards
     * (it has no in-port), and a plug's right-click from the world is one switch request.
     */
    private static void levelForwards() {
        Requests harness = new Requests();
        UUID output = UUID.randomUUID();
        Binding level = Binding.of(RedstoneOutput.TWIN.sourceEnd("input_number.m3_level")).withControl(true);
        JsonObject document = JsonParser.parseString("{\"state\":\"42.5\",\"attributes\":{\"min\":0.0,\"max\":100.0,\"step\":0.5}}").getAsJsonObject();
        TwinRequests.Result sent = harness.requests.forward(origin(output, STEVE), "input_number.m3_level", RedstoneOutput.TWIN, level,
                command("power", new JsonPrimitive(11)), new HaCalls.Mirror(HaCalls.Mirror.Freshness.LIVE, "42.5", document), Root.PLAYER);
        check(sent instanceof TwinRequests.Sent && harness.actions().equals(List.of("input_number.set_value"))
                && harness.last().getAsJsonObject("data").toString().equals("{\"value\":73.5}"), "Level 11 of 0..100 in steps of 0.5 is set_value 73.5: " + harness.last());
        Binding door = Binding.of(RedstoneOutput.TWIN.sourceEnd("binary_sensor.door")).withControl(true);
        harness.now += 2_000;
        TwinRequests.Result readOnly = harness.requests.forward(origin(output, STEVE), "binary_sensor.door", RedstoneOutput.TWIN, door,
                command("power", new JsonPrimitive(15)), HaCalls.Mirror.live("off"), Root.PLAYER);
        check(readOnly instanceof TwinRequests.Refused refused && refused.refusal() == TwinRequests.Refusal.LOCAL && refused.reason().contains("cannot be controlled")
                && harness.published.size() == 1, "An output mirroring a binary_sensor never forwards: " + readOnly);
        check(DeviceRuntime.route(false, RedstoneOutput.TYPE, true, CommandSource.HA, new DeviceRuntime.Mirrored(true, true)) == DeviceRuntime.Route.REFUSE,
                "Home Assistant's /set to a mirrored output is refused");
        UUID plug = UUID.randomUUID();
        Binding heater = Binding.of(SmartPlug.TWIN.sourceEnd("switch.heater")).withControl(true);
        harness.now += 2_000;
        check(harness.requests.forward(origin(plug, null), "switch.heater", SmartPlug.TWIN, heater, toggle(), HaCalls.Mirror.retained("on"), Root.WORLD)
                instanceof TwinRequests.Sent && harness.actions().getLast().equals("switch.toggle"), "A plug's toggle of a retained value is switch.toggle");
        check(harness.requests.forward(origin(plug, null), "switch.heater", SmartPlug.TWIN, heater, toggle(), HaCalls.Mirror.live("on"), Root.TWIN)
                instanceof TwinRequests.Refused refusedRoot && refusedRoot.refusal() == TwinRequests.Refusal.ROOT, "...and refused for a TWIN root");
    }

    /**
     * M3-14 through the real wiring: a Redstone Input's target wire ({@link TwinWiring#targetWire}) leaves its
     * {@code powered} out-port next to its registry wires ({@link RegistryWiring}), ends at the entity's in-port, and the
     * sink translates the edge as {@code DeviceRuntime.deliverTarget} does ({@link HaSink}). A lever's edges (WORLD) ask
     * {@code turn_on}, then {@code turn_off}; a falling edge into a scene asks nothing; an edge the LIVE target already
     * shows asks nothing (dedupe); an edge inside a TWIN extent (a mirrored output's same-tick neighbour cascade) is
     * refused for its root. A Wall Switch's press on its bus-only {@code left} event activates a scene.
     */
    private static void targets() {
        if (DeviceTypes.get(RedstoneInput.TYPE.id()) == null) DeviceTypes.registerBuiltIn();
        DeviceRegistry registry = new DeviceRegistry("targets_0001", UUID.randomUUID());
        DeviceRecord input = new DeviceRecord(UUID.randomUUID(), RedstoneInput.TYPE.id(), GlobalPos.of(Level.OVERWORLD, new BlockPos(0, -60, 0)), "plate", "plate");
        DeviceRecord wall = new DeviceRecord(UUID.randomUUID(), WallSwitch.TYPE.id(), GlobalPos.of(Level.OVERWORLD, new BlockPos(2, -60, 0)), "switch", "switch");
        registry.add(input);
        registry.add(wall);
        TwinWiring twin = new TwinWiring();
        Endpoint.HaEntityRef flag = TwinAdapter.targetEnd("input_boolean.m3_flag");
        twin.setTargets(input.id(), Map.of("powered", TwinWiring.targetWire(input.id(), "powered", flag, UUID.randomUUID())));
        twin.setTargets(wall.id(), Map.of("left", TwinWiring.targetWire(wall.id(), "left", TwinAdapter.targetEnd("scene.m3_scene"), UUID.randomUUID())));
        Requests harness = new Requests();
        HaCalls.Mirror[] mirror = {HaCalls.Mirror.NONE};
        List<TwinRequests.Result> results = new ArrayList<>();
        long[] nanos = {0};
        MessageBus bus = new MessageBus(new RegistryWiring(registry, twin), (wire, message) -> {
            if (!(wire.to() instanceof Endpoint.HaEntityRef target) || !(wire.from() instanceof Endpoint.PortRef source)) return MessageBus.Outcome.REFUSED;
            TwinAdapter adapter = DeviceTypes.get(registry.record(source.node()).type()).twin().orElseThrow();
            TwinRequests.Result result = new HaSink(harness.requests).deliver(origin(source.node(), null), adapter, source.port(), target, message.payload(), mirror[0], message.root());
            results.add(result);
            return HaSink.outcome(result);
        }, new MessageBus.Listener() {
            @Override
            public void loopCut(Wire wire, FlowMessage message) {
                throw new AssertionError("A target wire is no loop");
            }

            @Override
            public void failed(Wire wire, FlowMessage message, RuntimeException e) {
                throw new AssertionError("Delivery threw", e);
            }
        }, () -> nanos[0]);
        Endpoint powered = Endpoint.port(input.id(), "powered");
        check(new RegistryWiring(registry, twin).from(powered).size() == 1 && new RegistryWiring(registry, twin).in(flag).isPresent(),
                "The input's powered out-port has its target wire, whose end is the entity's in-port");
        // The runtime's sink takes a target wire to Home Assistant, although it is a TWIN wire like a source wire.
        Wire targetWire = twin.targets(input.id()).get("powered");
        check(targetWire.scope() == Scope.TWIN && DeviceRuntime.delivers(targetWire) == DeviceRuntime.Delivers.TARGET
                        && DeviceRuntime.delivers(TwinWiring.sourceWire(input.id(), "power", SmartLamp.TWIN.sourceEnd("light.kitchen"), UUID.randomUUID()))
                        == DeviceRuntime.Delivers.TWIN_SOURCE
                        && DeviceRuntime.delivers(wire(powered, Endpoint.port(wall.id(), "press_left"))) == DeviceRuntime.Delivers.DEVICE,
                "A target wire is delivered to Home Assistant, a source wire to its device, a registry wire as a command");

        // A lever: WORLD, outside any extent.
        bus.emit(FlowMessage.Kind.VALUE, powered, "powered", new JsonPrimitive(true), 0);
        drainAll(bus);
        harness.now += 2_000;
        nanos[0] += 2_000_000_000L;
        bus.emit(FlowMessage.Kind.VALUE, powered, "powered", new JsonPrimitive(false), 0);
        drainAll(bus);
        check(harness.actions().equals(List.of("input_boolean.turn_on", "input_boolean.turn_off")) && !harness.last().getAsJsonObject("origin").has("player"),
                "A lever's edges ask turn_on, then turn_off, naming no player: " + harness.actions());

        // An edge the LIVE target shows already asks nothing.
        harness.now += 2_000;
        nanos[0] += 2_000_000_000L;
        mirror[0] = HaCalls.Mirror.live("on");
        bus.emit(FlowMessage.Kind.VALUE, powered, "powered", new JsonPrimitive(true), 0);
        drainAll(bus);
        check(results.getLast() instanceof TwinRequests.Skipped && harness.published.size() == 2, "LIVE dedupe against the target entity: " + results.getLast());

        // An edge in a TWIN extent: a mirrored output's setBlock cascade powering the input in the same tick.
        harness.now += 2_000;
        nanos[0] += 2_000_000_000L;
        mirror[0] = HaCalls.Mirror.live("on");
        bus.withRoot(Root.TWIN, () -> bus.emit(FlowMessage.Kind.VALUE, powered, "powered", new JsonPrimitive(false), 0));
        drainAll(bus);
        check(results.getLast() instanceof TwinRequests.Refused refused && refused.refusal() == TwinRequests.Refusal.ROOT && harness.published.size() == 2
                && bus.dropped(MessageBus.Drop.ROOT) == 1, "An edge inside a TWIN extent is refused for its root: " + results.getLast());

        // A falling edge into a scene asks nothing; a rising one activates it.
        twin.setTargets(input.id(), Map.of("powered", TwinWiring.targetWire(input.id(), "powered", TwinAdapter.targetEnd("scene.m3_scene"), UUID.randomUUID())))
                .forEach(old -> bus.forgetWire(old.id()));
        mirror[0] = HaCalls.Mirror.NONE;
        harness.now += 2_000;
        bus.emit(FlowMessage.Kind.VALUE, powered, "powered", new JsonPrimitive(false), 0);
        drainAll(bus);
        check(results.getLast() instanceof TwinRequests.Skipped && harness.published.size() == 2, "A falling edge on a scene requests nothing: " + results.getLast());
        harness.now += 2_000;
        bus.emit(FlowMessage.Kind.VALUE, powered, "powered", new JsonPrimitive(true), 0);
        drainAll(bus);
        check(harness.actions().getLast().equals("scene.turn_on"), "A rising edge activates the scene");

        // The Wall Switch: its press (left, bus-only) activates the scene; its hold asks nothing; press_left is no target.
        harness.now += 2_000;
        Endpoint left = Endpoint.port(wall.id(), "left");
        bus.emit(FlowMessage.Kind.EVENT, left, "left", WallSwitch.buttonEvent(WallSwitch.Gesture.PRESS, WallSwitch.Button.LEFT), 0);
        drainAll(bus);
        check(harness.actions().size() == 4 && harness.actions().getLast().equals("scene.turn_on"), "The left button's press activates its scene: " + harness.actions());
        harness.now += 2_000;
        bus.emit(FlowMessage.Kind.EVENT, left, "left", WallSwitch.buttonEvent(WallSwitch.Gesture.HOLD, WallSwitch.Button.LEFT), 0);
        drainAll(bus);
        check(results.getLast() instanceof TwinRequests.Skipped && harness.actions().size() == 4, "A hold asks nothing in M3");
        check(new RegistryWiring(registry, twin).from(Endpoint.port(wall.id(), "press_left")).isEmpty(), "No target leaves the press_left action");
        check(bus.dropped(MessageBus.Drop.UNROUTABLE) == 0 && bus.dropped(MessageBus.Drop.VALUE) == 0, "Every target message was routable and fit its out-port");
    }

    /**
     * D10's loops outside the proof, bounded by the breaker (M3-14): a Redstone Output mirroring {@code switch.m3_loop}
     * inverted drives, through a repeater, a Redstone Input whose target is the same switch. Each repeater delay starts a
     * new WORLD extent, so each edge passes the root gate; Home Assistant answers each request by switching the entity,
     * which flips the output again. The breaker sends 8 requests and pauses the input at the 9th, and nothing more is
     * sent afterwards.
     */
    private static void repeaterLoop() {
        Requests harness = new Requests();
        UUID input = UUID.randomUUID();
        Binding output = Binding.of(RedstoneOutput.TWIN.sourceEnd("switch.m3_loop")).withSettings(Binding.Settings.DEFAULT.withInvert(true));
        Endpoint.HaEntityRef target = TwinAdapter.targetEnd("switch.m3_loop");
        String state = "on";
        int requests = 0;
        TwinRequests.Result last = null;
        for (int cycle = 0; cycle < 20 && !harness.requests.paused(input); cycle++) {
            JsonObject document = JsonParser.parseString("{\"state\":\"" + state + "\",\"attributes\":{}}").getAsJsonObject();
            EntityDocuments documents = new EntityDocuments("ha_statestream", EntityDocuments.Limits.DEFAULT);
            documents.accept("ha_statestream/switch/m3_loop/state", state.getBytes(StandardCharsets.UTF_8), false, harness.now);
            int level = RedstoneOutput.TYPE.twinCommand(documents.document("switch.m3_loop").orElseThrow(), output).orElseThrow().get("power").getAsInt();
            // The repeater's delay: the input's edge starts a new WORLD extent a few ticks later.
            harness.now += 300;
            last = new HaSink(harness.requests).deliver(origin(input, null), RedstoneInput.TWIN, "powered", target, new JsonPrimitive(level > 0),
                    new HaCalls.Mirror(HaCalls.Mirror.Freshness.LIVE, state, document), Root.WORLD);
            if (last instanceof TwinRequests.Sent sent) {
                requests++;
                // Home Assistant switches the entity; statestream reports it live.
                state = sent.action().endsWith("turn_on") ? "on" : "off";
            }
        }
        check(requests == TwinRequests.BREAKER_LIMIT && harness.published.size() == TwinRequests.BREAKER_LIMIT,
                "The repeater loop sends " + TwinRequests.BREAKER_LIMIT + " requests: " + harness.actions());
        check(last instanceof TwinRequests.Refused paused && paused.refusal() == TwinRequests.Refusal.PAUSED && harness.requests.paused(input)
                        && harness.events.contains("paused " + input),
                "...and the 9th trips the breaker, pausing the input: " + last);
        harness.now += 60_000;
        check(new HaSink(harness.requests).deliver(origin(input, null), RedstoneInput.TWIN, "powered", target, new JsonPrimitive(true), HaCalls.Mirror.live("off"), Root.WORLD)
                instanceof TwinRequests.Refused && harness.published.size() == TwinRequests.BREAKER_LIMIT, "A paused input sends nothing until it is resumed");
    }

    /**
     * Wave 5 finding 1, with Home Assistant answering as fast as the real one did: the repeater loop (an Output mirroring
     * switch.m3_loop inverted → a repeater → a Redstone Input targeting it) sent 6 requests in about 1.5 s, then the
     * device rate refused the 7th edge, which ended the loop with the world and the entity disagreeing, nothing paused
     * and nothing logged. Now an explicit state the world asked for waits over the rate instead (newest wins), so the
     * loop goes on at 4 a second and the breaker pauses the input, with its warning; nothing is sent after the pause.
     */
    private static void repeaterLoopFast() {
        Requests harness = new Requests();
        UUID input = UUID.randomUUID();
        Binding output = Binding.of(RedstoneOutput.TWIN.sourceEnd("switch.m3_loop")).withSettings(Binding.Settings.DEFAULT.withInvert(true));
        Endpoint.HaEntityRef target = TwinAdapter.targetEnd("switch.m3_loop");
        String[] state = {"on"};
        // when Home Assistant applies a request, and the state it applies; when the repeater's delayed edge reaches the input
        List<long[]> answers = new ArrayList<>();
        List<String> answerStates = new ArrayList<>();
        long edgeAt = harness.now + 100;
        int seen = 0;
        List<TwinRequests.Result> results = new ArrayList<>();
        for (int step = 0; step < 400 && !harness.requests.paused(input); step++) {
            harness.now += 50;
            for (int i = 0; i < answers.size(); i++) {
                if (answers.get(i)[0] > harness.now) continue;
                state[0] = answerStates.get(i);
                answers.remove(i);
                answerStates.remove(i);
                i--;
                // statestream reports it live; the output flips, and the repeater carries the edge 100 ms later
                edgeAt = harness.now + 100;
            }
            if (edgeAt > 0 && edgeAt <= harness.now) {
                edgeAt = -1;
                EntityDocuments documents = new EntityDocuments("ha_statestream", EntityDocuments.Limits.DEFAULT);
                documents.accept("ha_statestream/switch/m3_loop/state", state[0].getBytes(StandardCharsets.UTF_8), false, harness.now);
                int level = RedstoneOutput.TYPE.twinCommand(documents.document("switch.m3_loop").orElseThrow(), output).orElseThrow().get("power").getAsInt();
                JsonObject document = JsonParser.parseString("{\"state\":\"" + state[0] + "\",\"attributes\":{}}").getAsJsonObject();
                results.add(new HaSink(harness.requests).deliver(origin(input, null), RedstoneInput.TWIN, "powered", target, new JsonPrimitive(level > 0),
                        new HaCalls.Mirror(HaCalls.Mirror.Freshness.LIVE, state[0], document), Root.WORLD));
            }
            harness.requests.tick();
            // Each request published reaches Home Assistant, which applies it 0.1 s later: with the repeater, an edge every
            // 0.2 s, faster than the device rate, as the real run's 6 requests in about 1.5 s were at times.
            for (; seen < harness.published.size(); seen++) {
                String action = JsonParser.parseString(harness.published.get(seen).text()).getAsJsonObject().get("action").getAsString();
                answers.add(new long[]{harness.now + 100});
                answerStates.add(action.endsWith("turn_on") ? "on" : "off");
            }
        }
        check(harness.requests.paused(input) && harness.events.contains("paused " + input), "The fast repeater loop now ends paused: " + results);
        check(harness.published.size() == TwinRequests.BREAKER_LIMIT, "...after exactly " + TwinRequests.BREAKER_LIMIT + " requests: " + harness.actions());
        check(results.stream().anyMatch(result -> result instanceof TwinRequests.Held), "...as an edge over the rate waits instead of being dropped");
        check(harness.warnings.stream().anyMatch(warning -> warning.contains("more than " + TwinRequests.BREAKER_LIMIT) && warning.contains("switch.m3_loop")),
                "...and logged: " + harness.warnings);
        check(harness.notices.stream().anyMatch(notice -> notice.kind() == TwinRequests.Notice.Kind.PAUSED), "...and noticed to the owner");
        int sent = harness.published.size();
        harness.now += 5_000;
        harness.requests.tick();
        check(new HaSink(harness.requests).deliver(origin(input, null), RedstoneInput.TWIN, "powered", target, new JsonPrimitive(true), HaCalls.Mirror.live("off"), Root.WORLD)
                instanceof TwinRequests.Refused paused && paused.refusal() == TwinRequests.Refusal.PAUSED && harness.published.size() == sent,
                "Paused, it sends nothing until resumed (/mineqtt devices bind <input> resume)");
        check(harness.requests.resume(input), "...which resumes it");
    }

    /**
     * Wave 5 finding 1: a request a rate refuses is counted per device with its reason (for its info, its status line and
     * the Twin tab's Diagnostics), and logged once per burst: a world cause as a warning (the entity and the game may
     * disagree), a player's at debug (the player hears it). A world-caused refusal counts towards the breaker, so a
     * target the rates keep refusing pauses as a looping one does; a player's switch is still refused, never held.
     * Resuming clears the count.
     */
    private static void rateRefusals() {
        Requests harness = new Requests();
        UUID wall = UUID.randomUUID();
        List<TwinRequests.Result> results = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            harness.now += 100;
            results.add(new HaSink(harness.requests).deliver(origin(wall, null), WallSwitch.TWIN, "left", TwinAdapter.targetEnd("scene.movie"),
                    eventPayload(), HaCalls.Mirror.NONE, Root.WORLD));
        }
        check(harness.published.size() == TwinRequests.DEVICE_PER_SECOND && results.getLast() instanceof TwinRequests.Refused rate
                && rate.refusal() == TwinRequests.Refusal.RATE, "Presses over the rate are refused, never held (a scene is no state): " + results);
        TwinRequests.RateRefusals counted = harness.requests.rateRefusals(wall).orElseThrow();
        check(counted.refused() == 2 && counted.entityId().equals("scene.movie") && counted.reason().contains("4 requests a second"),
                "...counted per device with the last reason: " + counted);
        // Wave 6 open finding 2: each refusal tells the device, whose Twin tab shows the count.
        check(harness.events.stream().filter(("rate " + wall)::equals).count() == 2, "...and each refusal is heard, so the Twin tab shows it: " + harness.events);
        check(harness.warnings.size() == 1 && harness.warnings.getFirst().contains("scene.movie") && harness.warnings.getFirst().contains("disagree"),
                "...and logged once for the burst: " + harness.warnings);
        // Past the breaker's window: a press within the rate is sent, which ends the burst, so the next refusal is logged.
        harness.now += TwinRequests.BREAKER_WINDOW_MILLIS + 1_000;
        results.add(new HaSink(harness.requests).deliver(origin(wall, null), WallSwitch.TWIN, "left", TwinAdapter.targetEnd("scene.movie"),
                eventPayload(), HaCalls.Mirror.NONE, Root.WORLD));
        check(results.getLast() instanceof TwinRequests.Sent, "A press within the rate is sent again");
        for (int i = 0; i < 4; i++) {
            harness.now += 50;
            results.add(new HaSink(harness.requests).deliver(origin(wall, null), WallSwitch.TWIN, "left", TwinAdapter.targetEnd("scene.movie"), eventPayload(),
                    HaCalls.Mirror.NONE, Root.WORLD));
        }
        check(results.getLast() instanceof TwinRequests.Refused && harness.warnings.size() == 2 && !harness.requests.paused(wall),
                "...which ended the burst: the next refusal is logged again: " + harness.warnings);
        check(harness.requests.rateRefusals(wall).orElseThrow().refused() == 3, "...and counted on: " + harness.requests.rateRefusals(wall));

        // The rates keep refusing a target: refusals the world caused count towards the breaker, so it pauses having sent 4.
        Requests flood = new Requests();
        UUID input = UUID.randomUUID();
        TwinRequests.Result last = null;
        int attempts = 0;
        while (attempts < 40 && !flood.requests.paused(input)) {
            flood.now += 60;
            attempts++;
            last = new HaSink(flood.requests).deliver(origin(input, null), WallSwitch.TWIN, "left", TwinAdapter.targetEnd("scene.movie"), eventPayload(),
                    HaCalls.Mirror.NONE, Root.WORLD);
        }
        check(flood.requests.paused(input) && last instanceof TwinRequests.Refused paused && paused.refusal() == TwinRequests.Refusal.PAUSED
                        && attempts == TwinRequests.BREAKER_LIMIT + 1 && flood.published.size() == TwinRequests.DEVICE_PER_SECOND,
                "A target the rates keep refusing pauses like a loop (CONTROL_PAUSED) at the 9th attempt, 4 of them sent: " + attempts + " " + last);
        check(flood.warnings.stream().anyMatch(warning -> warning.contains("more than " + TwinRequests.BREAKER_LIMIT)), "...with the breaker's warning: " + flood.warnings);
        check(flood.requests.resume(input) && flood.requests.rateRefusals(input).isEmpty(), "Resume clears its refusals");

        Requests players = new Requests();
        UUID lamp = UUID.randomUUID();
        TwinRequests.Result clicked = null;
        for (int i = 0; i < 5; i++) {
            players.now += 50;
            clicked = players.requests.forward(origin(lamp, STEVE), "light.kitchen", SmartLamp.TWIN, lampLight(), command(Action.STATE, new JsonPrimitive(i % 2 == 0 ? "ON" : "OFF")),
                    HaCalls.Mirror.NONE, Root.PLAYER);
        }
        check(clicked instanceof TwinRequests.Refused rate && rate.refusal() == TwinRequests.Refusal.RATE && players.published.size() == 4,
                "A player's switch over the rate is still refused, never sent late (D11): " + clicked);
        check(players.requests.rateRefusals(lamp).orElseThrow().refused() == 1 && players.warnings.isEmpty() && players.debugs.size() == 1
                        && !players.requests.paused(lamp), "...counted, logged at debug (the player hears it) and not counted towards the breaker");
        check(TwinRequests.explicitState(new Call("light.turn_on")) && !TwinRequests.explicitState(new Call("light.toggle"))
                        && !TwinRequests.explicitState(new Call("scene.turn_on")) && !TwinRequests.explicitState(new Call("button.press")),
                "Only an explicit state waits for the rate: not a toggle, a scene or a press");
    }

    /**
     * Wave 6 review: a world-caused explicit state waits over the rate, and a Wall Switch's two targets each wait in their
     * own slot. With one slot per device, the right button's press replaced (or, sent within the rate, dropped) the left
     * button's held one, so a press was lost with nothing counted or logged.
     */
    private static void twoTargets() {
        Endpoint.HaEntityRef kitchen = TwinAdapter.targetEnd("light.kitchen");
        Endpoint.HaEntityRef hall = TwinAdapter.targetEnd("light.hall");
        // Both held in the same second: both are sent once the rate allows.
        Requests harness = new Requests();
        UUID wall = UUID.randomUUID();
        List<TwinRequests.Result> results = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            harness.now += 100;
            results.add(press(harness, wall, i % 2 == 0, kitchen, hall));
        }
        check(harness.published.size() == TwinRequests.DEVICE_PER_SECOND && results.get(4) instanceof TwinRequests.Held
                && results.get(5) instanceof TwinRequests.Held && harness.requests.status().waiting() == 2,
                "Left and right presses over the rate both wait, each for its own entity: " + results);
        harness.now += 1_000;
        harness.requests.tick();
        check(harness.published.size() == 6 && entities(harness).subList(4, 6).equals(List.of("light.kitchen", "light.hall"))
                && harness.requests.rateRefusals(wall).isEmpty(), "...and both are sent in the next second, none lost: " + entities(harness));

        // A press of the other button sent within the rate leaves the held one waiting.
        Requests later = new Requests();
        UUID other = UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            later.now += 100;
            press(later, other, i % 2 == 0, kitchen, hall);
        }
        later.now += 100;
        check(press(later, other, true, kitchen, hall) instanceof TwinRequests.Held, "The 5th press, left, waits");
        later.now += 650;
        check(press(later, other, false, kitchen, hall) instanceof TwinRequests.Sent, "The right press after the first left one left the window is sent");
        for (int i = 0; i < 10; i++) {
            later.now += 100;
            later.requests.tick();
        }
        check(later.published.size() == 6 && entities(later).get(5).equals("light.kitchen") && later.requests.status().waiting() == 0,
                "...and the left press that waited is still sent: " + entities(later));
    }

    /**
     * Wave 6 review: a lever flipped every 150 ms into a Redstone Input targeting a light, Home Assistant applying each
     * request 100 ms later. The 5th edge (on) waits for the rate; the 6th (off) is a dedupe skip, as the light already
     * shows off. The waiting turn_on was sent late anyway, leaving the light on with the lever off; now the skip drops it.
     * With an odd count the waiting state is the last one and is sent. A waiting brightness survives a skipped "on" and
     * is dropped by a skipped "off".
     */
    private static void leverBursts() {
        // Not 8 edges: the state that waited is sent at 1.15 s and applied at 1.25 s, and the 8th edge (off, 1.2 s) dedupes
        // against the light's off before that, an open finding of the wave 6 review fixes (a dedupe against a mirror with
        // a request in flight).
        for (int edges = 5; edges <= 7; edges++) {
            Requests harness = new Requests();
            UUID input = UUID.randomUUID();
            Endpoint.HaEntityRef target = TwinAdapter.targetEnd("light.x");
            String[] state = {"off"};
            List<long[]> answers = new ArrayList<>();
            List<String> answerStates = new ArrayList<>();
            List<TwinRequests.Result> results = new ArrayList<>();
            long start = harness.now;
            int seen = 0;
            boolean lever = false;
            for (int step = 1; step <= 80; step++) {
                harness.now = start + step * 50L;
                for (int i = 0; i < answers.size(); i++) {
                    if (answers.get(i)[0] > harness.now) continue;
                    state[0] = answerStates.remove(i);
                    answers.remove(i);
                    i--;
                }
                long at = harness.now - start;
                if (at % 150 == 0 && at / 150 <= edges) {
                    lever = !lever;
                    JsonObject document = JsonParser.parseString("{\"state\":\"" + state[0] + "\",\"attributes\":{}}").getAsJsonObject();
                    results.add(new HaSink(harness.requests).deliver(origin(input, null), RedstoneInput.TWIN, "powered", target, new JsonPrimitive(lever),
                            new HaCalls.Mirror(HaCalls.Mirror.Freshness.LIVE, state[0], document), Root.WORLD));
                    check(!(results.getLast() instanceof TwinRequests.Skipped) || harness.requests.status().waiting() == 0,
                            edges + " edges: an edge the light already shows drops the state that waited, before any tick: " + results);
                }
                harness.requests.tick();
                for (; seen < harness.published.size(); seen++) {
                    String action = JsonParser.parseString(harness.published.get(seen).text()).getAsJsonObject().get("action").getAsString();
                    answers.add(new long[]{harness.now + 100});
                    answerStates.add(action.endsWith("turn_on") ? "on" : "off");
                }
            }
            check(results.stream().anyMatch(result -> result instanceof TwinRequests.Held), edges + " edges: one waits for the rate: " + results);
            check(state[0].equals(lever ? "on" : "off") && harness.requests.status().waiting() == 0 && !harness.requests.paused(input),
                    edges + " edges end with the light as the lever left it (" + (lever ? "on" : "off") + "): " + state[0] + " " + harness.actions() + " " + results);
        }

        // A waiting brightness survives a skipped "on", which it does not contradict, and a skipped "off" drops it.
        Requests dimmer = new Requests();
        UUID lamp = UUID.randomUUID();
        for (int brightness = 10; brightness <= 50; brightness += 10) {
            dimmer.now += 50;
            dimmer.requests.forward(origin(lamp, null), "light.kitchen", SmartLamp.TWIN, lampLight(), command("brightness", new JsonPrimitive(brightness)),
                    HaCalls.Mirror.live("on"), Root.WORLD);
        }
        check(dimmer.requests.status().waiting() == 1, "The 5th brightness waits");
        dimmer.now += 50;
        check(dimmer.requests.forward(origin(lamp, null), "light.kitchen", SmartLamp.TWIN, lampLight(), command(Action.STATE, new JsonPrimitive("ON")),
                HaCalls.Mirror.live("on"), Root.WORLD) instanceof TwinRequests.Skipped && dimmer.requests.status().waiting() == 1,
                "...and still waits after an \"on\" the light already shows");
        dimmer.now += 50;
        check(dimmer.requests.forward(origin(lamp, null), "light.kitchen", SmartLamp.TWIN, lampLight(), command(Action.STATE, new JsonPrimitive("OFF")),
                HaCalls.Mirror.live("off"), Root.WORLD) instanceof TwinRequests.Skipped && dimmer.requests.status().waiting() == 0,
                "...but an \"off\" the light already shows drops it, which would turn the light on");
        dimmer.now += 1_000;
        dimmer.requests.tick();
        check(dimmer.published.size() == 4, "...so it is never sent: " + dimmer.actions());
    }

    // A press of the Wall Switch's left (or right) button, whose target light shows on (or off) LIVE.
    private static TwinRequests.Result press(Requests harness, UUID wall, boolean left, Endpoint.HaEntityRef leftTarget, Endpoint.HaEntityRef rightTarget) {
        WallSwitch.Button button = left ? WallSwitch.Button.LEFT : WallSwitch.Button.RIGHT;
        return new HaSink(harness.requests).deliver(origin(wall, null), WallSwitch.TWIN, button.getSerializedName(), left ? leftTarget : rightTarget,
                WallSwitch.buttonEvent(WallSwitch.Gesture.PRESS, button), HaCalls.Mirror.live(left ? "on" : "off"), Root.WORLD);
    }

    // The entity of every request published, in order.
    private static List<String> entities(Requests harness) {
        return harness.published.stream()
                .map(message -> JsonParser.parseString(message.text()).getAsJsonObject().getAsJsonObject("target").get("entity_id").getAsString()).toList();
    }

    // A press of the Wall Switch's left button, as its bus-only left event carries it (D16).
    private static JsonElement eventPayload() {
        return WallSwitch.buttonEvent(WallSwitch.Gesture.PRESS, WallSwitch.Button.LEFT);
    }

    // DeviceType.Builder#build refuses an adapter that does not fit the ports (M3 plan §3.1), so the rules hold at type build.
    private static void typeRules() {
        TwinAdapter.Reads power = (domain, settings) -> TwinTestTypes.ADAPTER.reads(domain, settings);
        refusedType(TwinAdapter.builder("brightness").source(power, "switch").build(), "a primary port that is no in-port");
        refusedType(TwinAdapter.builder("power").source((domain, settings) -> Map.of("fade", TwinTestTypes.ADAPTER.reads(domain, settings).get("power")), "switch")
                .build(), "a read into a port it does not mirror");
        refusedType(TwinAdapter.builder("power").source(power, "switch").target("target", Action.STATE).targetDomains("scene").build(),
                "a target on an action, which is an in-port (D16)");
        refusedType(TwinAdapter.builder("power").source(power, "switch").target("entity", TwinTestTypes.PRESSED).targetDomains("scene").build(),
                "a target named like a fixed twin key");
        refusedType(TwinAdapter.builder("power").source(power, "switch").off("brightness", new JsonPrimitive(0)).build(), "an off value for no in-port");
        check(fails(() -> TwinAdapter.builder("power").build()), "An adapter needs a source or a target");
        check(fails(() -> TwinAdapter.builder("power").source(power, "person").build()), "An adapter never mirrors a privacy domain");
        check(fails(() -> TwinAdapter.builder("power").source(power, "switch").target("target", TwinTestTypes.PRESSED).targetDomains("sensor").build()),
                "An adapter never switches a read-only domain");
        check(fails(() -> TwinAdapter.builder("power").target("target", TwinTestTypes.PRESSED).targetDomains("scene")
                .forward((command, domain, mirror, binding) -> List.of()).build()), "An adapter without a source does not forward");
        check(fails(() -> DeviceType.builder(net.minecraft.resources.Identifier.fromNamespaceAndPath("mineqtt", "test_binding_entity")).model("Test")
                .field(TwinTestTypes.POWER).action(Action.state()).entity(art.rehra.mineqtt.ha.Ha.switchOf("binding", TwinTestTypes.POWER).on(15).off(0))
                .twin(TwinTestTypes.ADAPTER).build()), "No entity is keyed like the binding sensor");
        check(TYPE.twin().orElseThrow() == ADAPTER && TwinTestTypes.TYPE.linkSetting("twin.entity").isPresent(), "The test type declares its adapter");
    }

    private static void refusedType(TwinAdapter adapter, String what) {
        check(!fails(() -> typeWith(TwinTestTypes.ADAPTER)), "The same type builds with a fitting adapter");
        check(fails(() -> typeWith(adapter)), "A type refuses " + what);
    }

    private static DeviceType typeWith(TwinAdapter adapter) {
        return DeviceType.builder(net.minecraft.resources.Identifier.fromNamespaceAndPath("mineqtt", "test_refused")).model("Test")
                .field(TwinTestTypes.POWER).field(TwinTestTypes.FADE).setting(new Setting<>(TwinTestTypes.FADE)).action(Action.state())
                .event(EventSpec.of(TwinTestTypes.PRESSED)).entity(art.rehra.mineqtt.ha.Ha.switchOf("output", TwinTestTypes.POWER).on(15).off(0))
                .entity(art.rehra.mineqtt.ha.Ha.number("fade", TwinTestTypes.FADE).range(0, 10)).twin(adapter).build();
    }

    private static boolean fails(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalStateException | IllegalArgumentException expected) {
            return true;
        }
    }

    private static void routes() {
        DeviceRuntime.Mirrored[] all = {DeviceRuntime.Mirrored.NONE, new DeviceRuntime.Mirrored(true, true), new DeviceRuntime.Mirrored(true, false),
                new DeviceRuntime.Mirrored(false, true)};
        for (DeviceType type : new DeviceType[]{TYPE, null}) {
            for (boolean group : new boolean[]{false, true}) {
                for (boolean attached : new boolean[]{false, true}) {
                    for (DeviceRuntime.Mirrored mirrored : all) {
                        DeviceRuntime.Route route = DeviceRuntime.route(group, type, attached, CommandSource.TWIN, mirrored);
                        check(route != DeviceRuntime.Route.FORWARD, "TWIN never forwards: " + group + " " + attached + " " + mirrored);
                        check(route != DeviceRuntime.Route.QUEUE, "TWIN never queues: " + group + " " + attached + " " + mirrored);
                    }
                }
            }
        }
        DeviceRuntime.Mirrored control = new DeviceRuntime.Mirrored(true, true);
        for (CommandSource source : new CommandSource[]{CommandSource.PLAYER, CommandSource.BINDING}) {
            for (boolean attached : new boolean[]{false, true}) {
                DeviceRuntime.Route route = DeviceRuntime.route(false, TYPE, attached, source, control);
                check(route == DeviceRuntime.Route.FORWARD, source + " to a mirrored port forwards (loaded " + attached + ")");
                check(route != DeviceRuntime.Route.APPLY && route != DeviceRuntime.Route.QUEUE, source + " to a mirrored port applies nothing locally");
            }
        }
        for (CommandSource source : new CommandSource[]{CommandSource.HA, CommandSource.GROUP, CommandSource.RAW}) {
            for (boolean attached : new boolean[]{false, true}) {
                check(DeviceRuntime.route(false, TYPE, attached, source, control) == DeviceRuntime.Route.REFUSE, source + " to a mirrored port is refused");
            }
        }
        // The refusal says why, as the player and the log read it (D6).
        check(DeviceRuntime.refusal("porch_lamp", SWITCH, CommandSource.HA, true).equals("porch_lamp mirrors switch.porch; control it in Home Assistant or in game"),
                "Home Assistant's /set is told where to control it");
        check(DeviceRuntime.refusal("porch_lamp", SWITCH, CommandSource.RAW, true).contains("Raw Link is paused"), "The Raw Link is paused");
        check(DeviceRuntime.refusal("porch_lamp", SWITCH.withControl(false), CommandSource.PLAYER, false).endsWith("its control is off"),
                "A player hears that control is off");
        check(DeviceRuntime.refusal("porch_lamp", SWITCH, CommandSource.PLAYER, false).contains("mineqtt:twin.control"),
                "An owner who lost the control permission is named as the reason");
        check(DeviceRuntime.refusal("porch_lamp", SWITCH.withRefused("switch.porch is not in twin.allowedEntities"), CommandSource.BINDING, false)
                .contains("binding is refused"), "A refused binding says so");

        // An unloaded device routes by its record's copy judged under the rules in force (wave 4 review): a copy stored
        // before light.kitchen left twin.allowedEntities, or before the twin was turned off, refuses instead of forwarding.
        Binding copy = lampLight();
        TwinRules.Rights allowed = new TwinRules.Rights(true, true, EntityGlobs.ALL, Optional.empty());
        check(DeviceRuntime.route(false, SmartLamp.TYPE, false, CommandSource.BINDING,
                new DeviceRuntime.Mirrored(true, TwinRules.recheck(SmartLamp.TWIN, copy, allowed).controls())) == DeviceRuntime.Route.FORWARD,
                "An unloaded lamp's copy the rules allow forwards a wire's command");
        for (TwinRules.Rights now : List.of(new TwinRules.Rights(true, true, EntityGlobs.of(List.of("switch.*")), Optional.empty()),
                new TwinRules.Rights(true, true, EntityGlobs.ALL, Optional.of("no statestream base")))) {
            Binding judged = TwinRules.recheck(SmartLamp.TWIN, copy, now);
            for (CommandSource source : new CommandSource[]{CommandSource.PLAYER, CommandSource.BINDING}) {
                check(DeviceRuntime.route(false, SmartLamp.TYPE, false, source, new DeviceRuntime.Mirrored(true, judged.controls())) == DeviceRuntime.Route.REFUSE
                        && DeviceRuntime.refusal("porch_lamp", judged, source, false).contains("binding is refused"),
                        "...one they refuse now refuses " + source + " and says why: " + judged.refused());
            }
        }
    }

    // Only the ports the source owns are routed as commands to the mirror; a setting applies as always.
    private static void mirroredKeys() {
        check(ADAPTER.mirroredKeys().equals(Set.of("power", Action.STATE)), "The primary port and the state action are mirrored");
        check(ADAPTER.touchesMirror(command("power", new JsonPrimitive(7))) && ADAPTER.touchesMirror(command(Action.STATE, new JsonPrimitive("TOGGLE"))),
                "A command to power or the state action goes to the mirror");
        check(!ADAPTER.touchesMirror(command("fade", new JsonPrimitive(3))), "A setting does not");
        // ...and it lands when the device attaches (sideDoors, TwinRules.landingPending).
        DeviceRuntime.Mirrored setting = DeviceRuntime.Mirrored.NONE;
        check(DeviceRuntime.route(false, TYPE, true, CommandSource.HA, setting) == DeviceRuntime.Route.APPLY
                && DeviceRuntime.route(false, TYPE, false, CommandSource.PLAYER, setting) == DeviceRuntime.Route.QUEUE,
                "A setting of a bound device applies, or queues while it is unloaded, as for any device");
    }

    private static void reading() {
        EntityDocument on = document("switch.porch", "on", Map.of());
        check(ADAPTER.read(on, SWITCH).equals(Map.of("power", new JsonPrimitive(15))), "A switch on reads power 15: " + ADAPTER.read(on, SWITCH));
        check(TYPE.twinCommand(on, SWITCH).orElseThrow().equals(command("power", new JsonPrimitive(15))), "...as the command {\"power\":15}");
        Binding inverted = SWITCH.withSettings(SWITCH.settings().withInvert(true));
        check(TYPE.twinCommand(on, inverted).orElseThrow().equals(command("power", new JsonPrimitive(0))), "Inverted it reads 0");
        check(TYPE.twinCommand(document("switch.porch", "opening", Map.of()), SWITCH).isEmpty(), "A state that is neither holds");
        check(TYPE.twinCommand(document("switch.other", "on", Map.of()), SWITCH).isEmpty(), "Another entity's document reads nothing");
        check(TYPE.twinCommand(on, SWITCH.withRefused("refused")).isEmpty(), "A refused binding reads nothing");
        EntityDocument unavailable = document("switch.porch", null, Map.of());
        check(TYPE.twinCommand(unavailable, SWITCH).isEmpty(), "Unavailable holds by default (D6: a door must not move)");
        Binding off = SWITCH.withSettings(SWITCH.settings().withUnavailable(Binding.Unavailable.OFF));
        check(TYPE.twinCommand(unavailable, off).orElseThrow().equals(command("power", new JsonPrimitive(0))), "twin.unavailable off turns it off");
        Binding number = TwinTestTypes.bound("input_number.volume", false);
        EntityDocument half = document("input_number.volume", "50", Map.of("min", new JsonPrimitive(0), "max", new JsonPrimitive(100)));
        check(TYPE.twinCommand(half, number).orElseThrow().equals(command("power", new JsonPrimitive(8))), "An input_number maps over its own range");
        Binding narrowed = number.withSettings(number.settings().withMin(BigDecimal.ZERO).withMax(BigDecimal.valueOf(50)));
        check(TYPE.twinCommand(half, narrowed).orElseThrow().equals(command("power", new JsonPrimitive(15))), "twin.min and twin.max narrow it");
    }

    // 1000 random documents into the adapter: the result is in-port values of mirrored ports only, which route as TWIN
    // (apply while loaded, drop while not), never as anything that leaves the game.
    private static void readingFuzz() {
        Random random = new Random(0x4d337L);
        String[] entities = {"switch.porch", "input_boolean.flag", "input_number.volume", "light.kitchen"};
        String[] states = {"on", "off", "true", "1", "unavailable", "unknown", "", "opening", "42.5", "-3", "NaN", "1e400", "ON", "Turbo Mode", "007"};
        for (int round = 0; round < 1000; round++) {
            String entity = entities[random.nextInt(entities.length)];
            String state = states[random.nextInt(states.length)];
            Map<String, JsonElement> attributes = new HashMap<>();
            if (random.nextBoolean()) attributes.put("min", new JsonPrimitive(random.nextInt(200) - 100));
            if (random.nextBoolean()) attributes.put("max", new JsonPrimitive(random.nextInt(200) - 100));
            EntityDocument document = document(entity, state.isEmpty() ? null : state, attributes);
            Binding.Settings settings = new Binding.Settings(random.nextBoolean(), random.nextBoolean() ? BigDecimal.valueOf(random.nextInt(50)) : null,
                    random.nextBoolean() ? BigDecimal.valueOf(random.nextInt(100)) : null, -1,
                    random.nextBoolean() ? Binding.Unavailable.HOLD : Binding.Unavailable.OFF, null);
            Binding binding = new Binding(new Endpoint.HaEntityRef(entity, "state"), Map.of(), random.nextBoolean(), settings, null);
            Optional<JsonObject> command = TYPE.twinCommand(document, binding);
            if (command.isEmpty()) continue;
            check(ADAPTER.mirroredKeys().containsAll(command.get().keySet()), "A read fills mirrored ports only: " + command.get());
            check(command.get().get("power").getAsInt() >= 0 && command.get().get("power").getAsInt() <= 15, "...within the port's range: " + command.get());
            check(DeviceRuntime.route(false, TYPE, true, CommandSource.TWIN, new DeviceRuntime.Mirrored(true, binding.controls())) == DeviceRuntime.Route.APPLY
                    && DeviceRuntime.route(false, TYPE, false, CommandSource.TWIN, new DeviceRuntime.Mirrored(true, binding.controls())) == DeviceRuntime.Route.DROP,
                    "...and applies as TWIN, never forwarding");
        }
    }

    // A forward builds calls only for a working binding with control, of the source's own domain.
    private static void forwarding() {
        HaCalls.Mirror live = HaCalls.Mirror.live("on");
        List<Call> calls = ADAPTER.calls(command(Action.STATE, new JsonPrimitive("TOGGLE")), live, SWITCH);
        check(calls.size() == 1 && calls.getFirst().action().equals("switch.turn_off"), "TOGGLE from a LIVE on is turn_off: " + calls);
        check(ADAPTER.calls(command("power", new JsonPrimitive(15)), live, SWITCH).isEmpty()
                && ADAPTER.forward(command("power", new JsonPrimitive(15)), live, SWITCH).getFirst() instanceof HaCalls.Skip, "LIVE dedupe: on while on sends nothing");
        check(ADAPTER.forward(command(Action.STATE, new JsonPrimitive("TOGGLE")), live, SWITCH.withControl(false)).isEmpty(), "No control, no call");
        check(ADAPTER.forward(command(Action.STATE, new JsonPrimitive("TOGGLE")), live, SWITCH.withRefused("refused")).isEmpty(), "A refused binding, no call");
        check(ADAPTER.forward(command("fade", new JsonPrimitive(2)), live, SWITCH).isEmpty(), "A setting is never forwarded");
        check(ADAPTER.forward(command(Action.STATE, new JsonPrimitive("TOGGLE")), HaCalls.Mirror.live("unavailable"), SWITCH).getFirst() instanceof HaCalls.Refused,
                "A toggle of an unavailable entity is refused, with its reason");
        TwinAdapter crossDomain = TwinAdapter.builder("power")
                .source((domain, settings) -> TwinTestTypes.ADAPTER.reads(domain, settings), "switch")
                .forward((command, domain, mirror, binding) -> List.of(new HaCalls.Send(new Call("light.turn_on", new JsonObject())),
                        new HaCalls.Send(new Call("switch.turn_on", new JsonObject()))))
                .build();
        check(crossDomain.calls(command("power", new JsonPrimitive(15)), HaCalls.Mirror.NONE, SWITCH).stream().map(Call::action).toList()
                .equals(List.of("switch.turn_on")), "A call of another domain than the source's is dropped (D12)");
    }

    private static void sideDoors() {
        JsonObject power = command("power", new JsonPrimitive(15));
        check(TwinRules.seedSkipped(SWITCH, ADAPTER, power), "A new wire's seed into a mirrored port is skipped");
        check(TwinRules.seedSkipped(SWITCH.withRefused("refused"), ADAPTER, power), "...also while the binding is refused (it is still bound)");
        check(!TwinRules.seedSkipped(SWITCH, ADAPTER, command("fade", new JsonPrimitive(2))), "A seed into a setting is not");
        check(!TwinRules.seedSkipped(null, ADAPTER, power) && !TwinRules.seedSkipped(SWITCH, null, power), "Nor one into an unbound device");
        Binding targetOnly = new Binding(null, Map.of(TwinTestTypes.PRESSED, TwinAdapter.targetEnd("scene.movie")), false, Binding.Settings.DEFAULT, null);
        check(!TwinRules.seedSkipped(targetOnly, ADAPTER, power), "Nor one into a device that only has targets");

        JsonObject mixed = command("fade", new JsonPrimitive(3));
        mixed.addProperty("power", 15);
        check(command("fade", new JsonPrimitive(3)).equals(TwinRules.landingPending(SWITCH, ADAPTER, mixed)),
                "At its attach a bound device lands what was queued for a setting, never for a mirrored port");
        check(TwinRules.landingPending(SWITCH, ADAPTER, power) == null && TwinRules.landingPending(SWITCH.withRefused("refused"), ADAPTER, power) == null,
                "...so a queued mirrored value alone leaves nothing, refused binding or not");
        check(TwinRules.landingPending(null, ADAPTER, mixed) == mixed && TwinRules.landingPending(targetOnly, ADAPTER, mixed) == mixed,
                "An unbound one's, or one with targets only, lands whole");
        check(TwinRules.landingPending(SWITCH, null, mixed) == null, "A bound device without an adapter lands nothing");
        // The route table and the attach guard together (wave 4 review): whatever any source queues for an unloaded bound
        // device lands whole at its attach, with no mirrored key in it.
        JsonObject[] commands = {command("fade", new JsonPrimitive(3)), power, command(Action.STATE, new JsonPrimitive(Coerce.TOGGLE)), mixed};
        for (JsonObject queued : commands) {
            for (CommandSource source : CommandSource.values()) {
                for (boolean control : new boolean[]{false, true}) {
                    DeviceRuntime.Route route = DeviceRuntime.route(false, TYPE, false, source, new DeviceRuntime.Mirrored(ADAPTER.touchesMirror(queued), control));
                    if (route != DeviceRuntime.Route.QUEUE) continue;
                    check(queued.equals(TwinRules.landingPending(SWITCH, ADAPTER, queued)) && !ADAPTER.touchesMirror(queued),
                            "What " + source + " queues for a bound device lands whole: " + queued);
                }
            }
        }
        JsonObject effect = command("effect", new JsonPrimitive("candle"));
        check(DeviceRuntime.route(false, SmartLamp.TYPE, false, CommandSource.PLAYER, new DeviceRuntime.Mirrored(SmartLamp.TWIN.touchesMirror(effect), true))
                        == DeviceRuntime.Route.QUEUE && effect.equals(TwinRules.landingPending(lampLight(), SmartLamp.TWIN, effect)),
                "A mirrored lamp's effect queues while it is unloaded and lands when it attaches");

        DeviceLink link = linkWithRaw();
        check(!DeviceRuntime.rawPaused(link), "An unbound device follows its Raw Link input");
        link.setTwin(SWITCH);
        check(DeviceRuntime.rawPaused(link), "A bound device's Raw Link input is paused");
        link.setTwin(targetOnly);
        check(!DeviceRuntime.rawPaused(link), "Targets alone do not pause it");
        link.setTwin(null);
        check(!DeviceRuntime.rawPaused(link), "Unbound, it follows again");
    }

    /**
     * The bus carries the root of what started a chain to the mirrored target (D10): a plain device's change emitted
     * inside an extent, relayed by another device, arrives at the forward with the extent's root, so only in-game roots
     * can pass the gate. The recording sink stands in for {@code DeviceRuntime.deliverWire}: a message into the mirrored
     * device's power is a {@link CommandSource#BINDING} command, routed by the real route table and forwarded through the
     * real {@link TwinRequests}, whose gate refuses every root but an in-game one.
     */
    private static void bus() {
        UUID plain = UUID.randomUUID(), relay = UUID.randomUUID(), mirrored = UUID.randomUUID();
        Endpoint plainOut = Endpoint.port(plain, "power"), relayIn = Endpoint.port(relay, "power"), relayOut = Endpoint.port(relay, "fade");
        Endpoint mirroredIn = Endpoint.port(mirrored, "power");
        PortSpec out = TYPE.port(Port.Direction.OUT, "power").orElseThrow(), in = TYPE.port(Port.Direction.IN, "power").orElseThrow();
        PortSpec fadeOut = TYPE.port(Port.Direction.OUT, "fade").orElseThrow();
        Map<Endpoint, List<Wire>> wires = new HashMap<>();
        wires.put(plainOut, List.of(wire(plainOut, mirroredIn), wire(plainOut, relayIn)));
        wires.put(relayOut, List.of(wire(relayOut, mirroredIn)));
        List<Root> forwarded = new ArrayList<>();
        List<Root> relayed = new ArrayList<>();
        Requests requests = new Requests();
        MessageBus[] holder = new MessageBus[1];
        MessageBus bus = new MessageBus(new MessageBus.Wiring() {
            @Override
            public List<Wire> from(Endpoint source) {
                return wires.getOrDefault(source, List.of());
            }

            @Override
            public Optional<? extends PortSpec> out(Endpoint source) {
                return Optional.of(source.equals(relayOut) ? fadeOut : out);
            }

            @Override
            public Optional<? extends PortSpec> in(Endpoint target) {
                return Optional.of(in);
            }
        }, (wire, message) -> {
            if (wire.to().equals(relayIn)) {
                // The relay is a plain device: it applies and emits its change on the stack of the delivery.
                relayed.add(message.root());
                holder[0].emit(FlowMessage.Kind.VALUE, relayOut, "fade", new JsonPrimitive(message.payload().getAsInt() % 11), 0);
                return MessageBus.Outcome.TAKEN;
            }
            JsonObject command = TYPE.inPortCommand("power", message.payload());
            DeviceRuntime.Route route = DeviceRuntime.route(false, TYPE, true, CommandSource.BINDING,
                    new DeviceRuntime.Mirrored(ADAPTER.touchesMirror(command), SWITCH.controls()));
            check(route == DeviceRuntime.Route.FORWARD, "A wire's message into the mirrored port is a forward: " + route);
            forwarded.add(message.root());
            // The real write-back, as DeviceRuntime's forwarder hands it the command and the message's root.
            return HaSink.outcome(requests.requests.forward(origin(mirrored, null), "switch.porch", ADAPTER, SWITCH, command, HaCalls.Mirror.NONE, message.root()));
        }, new MessageBus.Listener() {
            @Override
            public void loopCut(Wire wire, FlowMessage message) {
            }

            @Override
            public void failed(Wire wire, FlowMessage message, RuntimeException e) {
                throw new AssertionError("Delivery threw", e);
            }
        }, System::nanoTime);
        holder[0] = bus;

        int value = 1;
        for (Root root : Root.values()) {
            forwarded.clear();
            relayed.clear();
            long dropped = bus.dropped(MessageBus.Drop.ROOT);
            int published = requests.published.size();
            // A second apart, so the rates never decide here.
            requests.now += 2_000;
            int emitted = value++;
            bus.withRoot(root, () -> bus.emit(FlowMessage.Kind.VALUE, plainOut, "power", new JsonPrimitive(emitted), 0));
            drainAll(bus);
            check(forwarded.equals(List.of(root, root)) && relayed.equals(List.of(root)),
                    "Emitted in a " + root + " extent, directly and through a relay, the forward sees " + root + ": " + forwarded + " " + relayed);
            check(bus.dropped(MessageBus.Drop.ROOT) - dropped == (root.inGame() ? 0 : 2), "A " + root + " forward is dropped as root: " + root.inGame());
            check(requests.published.size() - published == (root.inGame() ? 2 : 0),
                    "...so a " + root + " chain sends " + (root.inGame() ? "two requests" : "nothing, also through the same-tick relay"));
        }
        forwarded.clear();
        requests.now += 2_000;
        bus.emit(FlowMessage.Kind.VALUE, plainOut, "power", new JsonPrimitive(14), 0);
        drainAll(bus);
        check(forwarded.equals(List.of(Root.WORLD, Root.WORLD)), "Outside any extent it is WORLD (a lever), which may forward: " + forwarded);
    }

    // Drains until nothing is queued: a drain stops at its time budget, and the first deliveries load classes.
    private static void drainAll(MessageBus bus) {
        for (int i = 0; i < 100 && bus.hasQueued(); i++) bus.drain();
        check(!bus.hasQueued(), "The bus drains");
    }

    private static Wire wire(Endpoint from, Endpoint to) {
        return new Wire(UUID.randomUUID(), from, to, Mapping.FOLLOW, Scope.WIRELESS, UUID.randomUUID(), true);
    }

    private static DeviceLink linkWithRaw() {
        DeviceLink link = new DeviceLink(TYPE);
        CompoundTag data = new CompoundTag();
        data.store("id", UUIDUtil.CODEC, UUID.randomUUID());
        data.store("raw", RawLink.CODEC, new RawLink("mineqtt/shared/porch", RawLinkSpec.Direction.IN, null, null, 0, null, null, false, null, 0, null,
                RawLink.Edges.BOTH));
        CompoundTag tag = new CompoundTag();
        tag.put(DeviceLink.KEY, data);
        link.load(TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, tag), false);
        return link;
    }

    /** A LIVE statestream document; a null state is {@code unavailable}. */
    static EntityDocument document(String entity, String state, Map<String, JsonElement> attributes) {
        return new EntityDocument(entity, state == null ? StatestreamValue.UNAVAILABLE : StatestreamValue.of(state), attributes, Instant.EPOCH, Instant.EPOCH, 0,
                EntityDocument.Freshness.LIVE, EntityDocument.Origin.STATESTREAM);
    }

    static JsonObject command(String key, JsonElement value) {
        JsonObject command = new JsonObject();
        command.add(key, value);
        return command;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

package art.rehra.mineqtt.device;

import art.rehra.mineqtt.device.types.ClimateSensor;
import art.rehra.mineqtt.device.types.DisplayPanel;
import art.rehra.mineqtt.device.types.PresenceSensor;
import art.rehra.mineqtt.device.types.RedstoneInput;
import art.rehra.mineqtt.device.types.RedstoneOutput;
import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.device.types.SmartPlug;
import art.rehra.mineqtt.device.types.WallSwitch;
import art.rehra.mineqtt.flow.core.Coerce;
import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.flow.core.FlowMessage;
import art.rehra.mineqtt.flow.core.Mapping;
import art.rehra.mineqtt.flow.core.MessageBus;
import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.flow.core.Scope;
import art.rehra.mineqtt.flow.core.Wire;
import art.rehra.mineqtt.ha.Ha;
import art.rehra.mineqtt.ha.HaEntity;
import art.rehra.mineqtt.security.AccessMode;
import art.rehra.mineqtt.twin.TwinAdapter;
import art.rehra.mineqtt.twin.core.Binding;
import art.rehra.mineqtt.twin.core.Call;
import art.rehra.mineqtt.twin.core.EntityDocument;
import art.rehra.mineqtt.twin.core.EntityDocuments;
import art.rehra.mineqtt.twin.core.EntityGlobs;
import art.rehra.mineqtt.twin.core.HaCalls;
import art.rehra.mineqtt.twin.core.ValuePath;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.EncoderException;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The declaration side of devices (M2 plan §3.1, M2-4), run by Gradle's verifyDeviceTypes task (bootstrapped): every
 * registered type builds, its ports are derived from fields, settings, actions and events and stay what the HA/MQTT
 * contract froze, Home Assistant events and commands have their declarations, settings and link settings refuse what
 * does not fit, which port pairs a wire may join (verifyMessageBus has the coercion table) and how wires route between
 * devices of the registry, and the device view's stream
 * codec and cap, which the largest state of every type fits. No block entity is constructed.
 */
public final class DeviceTypesCheck {
    private static final UUID DEVICE = UUID.fromString("3f9c0000-0000-4000-8000-000000000001");
    private static final BlockPos POS = new BlockPos(12, -60, -7);
    private static final DeviceView.Ownership OWNED = new DeviceView.Ownership(DeviceView.Owner.KNOWN, false);
    private static final int JSON_FIELD_ALLOWANCE = 1024;

    static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        DeviceTypes.registerBuiltIn();
        List<DeviceType> types = DeviceTypes.all();
        check(types.equals(List.of(SmartLamp.TYPE, RedstoneOutput.TYPE, RedstoneInput.TYPE, PresenceSensor.TYPE, SmartPlug.TYPE, ClimateSensor.TYPE,
                WallSwitch.TYPE, DisplayPanel.TYPE)), "The eight built-in types are registered: " + types);
        int ports = 0;
        for (DeviceType type : types) {
            declaration(type);
            settingsParse(type);
            viewRoundTrip(type);
            worstCaseState(type);
            ports += type.ports().size();
        }
        frozenPorts();
        buildRules();
        lenientCommandsStay();
        linkSettings();
        actions();
        events();
        portKinds();
        portCompatibility();
        wiring();
        linkRules();
        viewCaps();
        climate();
        wallSwitch();
        lampTwin();
        outputTwin();
        plugTwin();
        targetTwins();
        displayPanel();
        System.out.println("Device type checks passed: " + types.size() + " types, " + ports + " ports.");
    }

    // Ports are derived from the declaration, one per readable field and event (out) and writable field and action (in).
    private static void declaration(DeviceType type) {
        Set<String> seen = new HashSet<>();
        for (Port port : type.ports()) check(seen.add(port.direction() + " " + port.key()), type + ": port keys are unique per direction: " + port.key());
        int expected = 0;
        for (Field<?> field : type.fields()) {
            check(field.kind() != null, type + ": field " + field.key() + " has a kind");
            check(field.kind().problem(encodeDefault(field)).isEmpty(), type + ": the default of " + field.key() + " fits its kind " + field.kind().describe());
            if (field.access().readable()) {
                expected++;
                Port out = type.port(Port.Direction.OUT, field.key()).orElseThrow(() -> new AssertionError(type + ": no out-port for " + field.key()));
                check(out.kind().equals(field.kind()) && out.origin() == Port.Origin.FIELD, type + ": out-port " + field.key() + " is the field's");
            }
            if (field.access().writable()) {
                expected++;
                Port in = type.port(Port.Direction.IN, field.key()).orElseThrow(() -> new AssertionError(type + ": no in-port for " + field.key()));
                boolean setting = type.setting(field.key()).isPresent();
                check(in.kind().equals(field.kind()) && in.origin() == (setting ? Port.Origin.SETTING : Port.Origin.FIELD) && !in.toggles(),
                        type + ": in-port " + field.key() + " is the field's, marked as a setting when it is one");
            }
        }
        for (Setting<?> setting : type.settings()) {
            check(setting.kind() != null && setting.kind().equals(setting.field().kind()) && type.fields().contains(setting.field()),
                    type + ": setting " + setting.key() + " has its field's kind");
        }
        for (EventSpec event : type.events()) {
            expected++;
            Port out = type.port(Port.Direction.OUT, event.type()).orElseThrow(() -> new AssertionError(type + ": no event port " + event.type()));
            check(out.origin() == Port.Origin.EVENT && out.kind().equals(PortType.event(event.type())), type + ": event port " + event.type());
        }
        for (Action action : type.actions()) {
            expected++;
            Port in = type.port(Port.Direction.IN, action.key()).orElseThrow(() -> new AssertionError(type + ": no action port " + action.key()));
            check(in.origin() == Port.Origin.ACTION && in.kind().equals(action.kind()) && in.toggles() == action.toggles(), type + ": action port " + action.key());
            if (action.toggles()) {
                check(action.parse("TOGGLE").isPresent() && action.parse("on").isPresent() && action.parse("dim").isEmpty(), type + ": action " + action.key() + " parses");
            } else if (action.kind() instanceof PortType.Event) {
                check(action.parse("").isPresent() && action.parse("press").isPresent() && action.parse("x").isEmpty(), type + ": action " + action.key() + " fires");
            }
        }
        check(type.ports().size() == expected, type + ": no port besides fields, events and actions: " + type.ports());
        for (HaEntity<?> entity : type.entities()) {
            switch (entity) {
                case HaEntity.Event event -> event.eventTypes().forEach(eventType -> check(type.port(Port.Direction.OUT, eventType).isPresent(),
                        type + ": the Home Assistant event " + eventType + " has an event port"));
                case HaEntity.DeviceTrigger trigger -> check(type.port(Port.Direction.OUT, trigger.event()).isPresent(), type + ": trigger " + trigger.key() + " has an event port");
                case HaEntity.Button _, HaEntity.Notify _ -> check(type.action(entity.key()).isPresent(), type + ": " + entity.key() + " has its action");
                case HaEntity.Switch<?> _, HaEntity.Light _ -> check(type.port(Port.Direction.IN, Action.STATE).isPresent(), type + ": " + entity.key() + " has a state in-port");
                default -> {
                }
            }
        }
    }

    // The port ids and kinds are the HA/MQTT contract: never renamed, a new port is a new key.
    private static void frozenPorts() {
        expectPorts(RedstoneOutput.TYPE, List.of(
                "OUT state bool FIELD", "OUT power level FIELD", "OUT mode enum level|switch FIELD",
                "OUT power_on_behaviour enum previous|off|on FIELD", "OUT last_level number 1..15 FIELD", "OUT location json FIELD",
                "IN power level FIELD", "IN mode enum level|switch SETTING", "IN power_on_behaviour enum previous|off|on SETTING",
                "IN state bool ACTION toggles"));
        expectPorts(RedstoneInput.TYPE, List.of(
                "OUT powered bool FIELD", "OUT signal level FIELD", "OUT location json FIELD", "OUT pulse event pulse EVENT"));
        expectPorts(SmartLamp.TYPE, List.of(
                "OUT light light FIELD", "OUT light_level level FIELD", "OUT location json FIELD",
                "IN light light FIELD", "IN state bool ACTION toggles"));
        expectPorts(PresenceSensor.TYPE, List.of(
                "OUT occupancy bool FIELD", "OUT count number 0..2147483647 FIELD", "OUT detect enum players|mobs|all FIELD",
                "OUT range number 1..8 blocks FIELD", "OUT clear_delay number 0..3600 s FIELD", "OUT filter text 512 FIELD", "OUT location json FIELD",
                "IN detect enum players|mobs|all SETTING", "IN range number 1..8 blocks SETTING", "IN clear_delay number 0..3600 s SETTING",
                "IN filter text 512 SETTING"));
        // The Output's switch, power and power-on ports with the same keys and kinds (M2-18), without its mode and last level.
        expectPorts(SmartPlug.TYPE, List.of(
                "OUT state bool FIELD", "OUT power level FIELD", "OUT power_on_behaviour enum previous|off|on FIELD", "OUT location json FIELD",
                "IN power level FIELD", "IN power_on_behaviour enum previous|off|on SETTING", "IN state bool ACTION toggles"));
        for (String key : List.of("state", "power", "power_on_behaviour")) {
            check(out(SmartPlug.TYPE, key).kind().equals(out(RedstoneOutput.TYPE, key).kind()), "The plug's " + key + " is the Output's port");
        }
        check(settingKeys(SmartPlug.TYPE.settings()).equals(List.of("power_on_behaviour")), "Plug settings: power-on behaviour only");
        check(SmartPlug.TYPE.use().map(Action::key).equals(Optional.of(Action.STATE)) && SmartPlug.TYPE.groupable()
                        && SmartPlug.TYPE.unloadedPolicy() == UnloadedPolicy.QUEUE,
                "A right-click switches the plug; it is groupable and queues commands while unloaded");
        // Readings out only; the interval and the Raw Link's reading in (M2-19).
        expectPorts(ClimateSensor.TYPE, List.of(
                "OUT temperature number -50..120 °C FIELD", "OUT humidity number 0..100 % FIELD", "OUT illuminance number 0..101000 lx FIELD",
                "OUT biome text 256 FIELD", "OUT interval number 1..3600 s FIELD", "OUT raw_value enum temperature|humidity|illuminance FIELD",
                "OUT location json FIELD",
                "IN interval number 1..3600 s SETTING", "IN raw_value enum temperature|humidity|illuminance SETTING"));
        check(settingKeys(ClimateSensor.TYPE.settings()).equals(List.of("interval", "raw_value")), "Climate settings: interval and the Raw Link's reading");
        check(ClimateSensor.TYPE.unloadedPolicy() == UnloadedPolicy.UNAVAILABLE && !ClimateSensor.TYPE.groupable() && ClimateSensor.TYPE.use().isEmpty()
                        && ClimateSensor.TYPE.minInterval().equals(Duration.ofSeconds(1))
                        && ClimateSensor.TYPE.deadbands().equals(Map.of("temperature", 0.2, "humidity", 1.0)),
                "The climate sensor is unavailable while unloaded, publishes at most once a second, with 0.2 °C and 1 % deadbands");
        // Three events with their button out, a press of either button in (M2-22); the bus-only per-button events with their
        // gesture are new keys after them (M3-14, D16), so the frozen ports stay as they were.
        expectPorts(WallSwitch.TYPE, List.of(
                "OUT location json FIELD", "OUT press event press EVENT", "OUT double_press event double_press EVENT", "OUT hold event hold EVENT",
                "OUT left event left EVENT", "OUT right event right EVENT",
                "IN press_left event press_left ACTION", "IN press_right event press_right ACTION"));
        check(WallSwitch.TYPE.settings().isEmpty() && WallSwitch.TYPE.unloadedPolicy() == UnloadedPolicy.UNAVAILABLE && !WallSwitch.TYPE.groupable()
                        && WallSwitch.TYPE.use().map(Action::key).equals(Optional.of("press_left")) && WallSwitch.TYPE.remote(),
                "A Wall Switch has no settings, is unavailable while unloaded (a queued press would be a stale click), is used by hand and was a v1 remote");
        for (EventSpec event : WallSwitch.TYPE.events()) {
            boolean perButton = event.busOnly();
            check(perButton == List.of("left", "right").contains(event.type()), "Only the per-button events are bus-only: " + event.type());
            check(event.attributes().equals(perButton ? Map.of("gesture", PortType.enumOf(List.of("press", "double_press", "hold")))
                    : Map.of("button", PortType.enumOf(List.of("left", "right")))), "Every Wall Switch event names its button, or its gesture: " + event.type());
        }
        // A message in and out, its level out, the look as settings (M3-15).
        String colors = "white|orange|magenta|light_blue|yellow|lime|pink|gray|light_gray|cyan|purple|blue|brown|green|red|black";
        expectPorts(DisplayPanel.TYPE, List.of(
                "OUT message text 255 FIELD", "OUT level level FIELD", "OUT color enum " + colors + " FIELD", "OUT glow bool FIELD",
                "OUT size enum auto|large|medium|small FIELD", "OUT location json FIELD",
                "IN message text 255 FIELD", "IN color enum " + colors + " SETTING", "IN glow bool SETTING", "IN size enum auto|large|medium|small SETTING"));
        check(settingKeys(RedstoneOutput.TYPE.settings()).equals(List.of("mode", "power_on_behaviour")), "Output settings: mode and power-on behaviour");
        check(settingKeys(PresenceSensor.TYPE.settings()).equals(List.of("detect", "range", "clear_delay", "filter")),
                "Presence settings: detect, range, clear delay and the mob filter (M2-12)");
        check(SmartLamp.TYPE.settings().isEmpty() && RedstoneInput.TYPE.settings().isEmpty(), "The lamp's light is a command (USE), not a setting; the input has none");
        check(RedstoneInput.TYPE.events().equals(List.of(RedstoneInput.PULSE_EVENT)) && RedstoneInput.PULSE_EVENT.attributes().equals(Map.of("signal", PortType.LEVEL)),
                "The input declares its pulse with the signal");
    }

    private static void expectPorts(DeviceType type, List<String> expected) {
        List<String> actual = type.ports().stream().map(port -> port.direction() + " " + port.key() + " " + port.kind().describe() + " " + port.origin()
                + (port.toggles() ? " toggles" : "")).toList();
        check(actual.equals(expected), type + " ports are frozen: " + actual);
    }

    private static void buildRules() {
        Field<Integer> power = Field.level("power", Access.READ_WRITE);
        Field<Boolean> powered = Field.bool("powered", Access.READ);
        Field<Boolean> on = Field.bool("state", Access.READ_WRITE);
        failsToBuild(base("a").entity(Ha.event("pulse", "pulse")), "no declared event", "A Home Assistant event without its declared event");
        failsToBuild(base("b").entity(Ha.deviceTrigger("button_short_press", "left", "press", "button")), "no declared event",
                "A device trigger without its declared event");
        EventSpec press = EventSpec.of("press").attribute("button", PortType.enumOf(List.of("left", "right")));
        failsToBuild(base("b2").event(press).entity(Ha.deviceTrigger("button_short_press", "left", "press", "side")), "does not carry",
                "A device trigger on an attribute its event does not carry");
        failsToBuild(base("b3").event(press).entity(Ha.deviceTrigger("button_short_press", "middle", "press", "button")), "never carries",
                "A device trigger waiting for a value its event never carries");
        // M3-14, D16: a bus-only event is published nowhere, so no Home Assistant entity may wait for it.
        EventSpec busPress = EventSpec.busOnly("press").attribute("button", PortType.enumOf(List.of("left", "right")));
        failsToBuild(base("b4").event(busPress).entity(Ha.event("action", "press")), "only the bus carries", "An event entity on a bus-only event");
        failsToBuild(base("b5").event(busPress).entity(Ha.deviceTrigger("button_short_press", "left", "press", "button")), "only the bus carries",
                "A device trigger on a bus-only event");
        check(busPress.busOnly() && !press.busOnly() && busPress.attribute("x", PortType.BOOL).busOnly() && base("b6").event(busPress).build().port(Port.Direction.OUT, "press").isPresent(),
                "A bus-only event keeps its flag through attributes and is an out-port like any event");
        failsToBuild(base("c").entity(Ha.button("trigger_now")), "no declared action", "A button without its action");
        failsToBuild(base("d").entity(Ha.notify("message")), "no declared action", "A notify without its action");
        failsToBuild(DeviceType.builder(id("e")).model("E").field(power).entity(Ha.switchOf("s", power).on(15).off(0)), "state action",
                "A switch without a state in-port");
        failsToBuild(DeviceType.builder(id("f")).model("F").field(SmartLamp.LIGHT).entity(Ha.light("l", SmartLamp.LIGHT)), "state action",
                "A light without a state in-port");
        failsToBuild(DeviceType.builder(id("g")).model("G").field(power).entity(Ha.number("p", power)).action(new Action("power", PortType.LEVEL, false)),
                "over the writable field", "An action over a writable field of its key");
        failsToBuild(base("h").action(Action.press("go")).action(Action.press("go")), "action go twice", "An action declared twice");
        failsToBuild(base("i").event(EventSpec.of("pulse")).event(EventSpec.of("pulse")), "event pulse twice", "An event declared twice");
        failsToBuild(base("j").event(EventSpec.of("powered")), "two OUT ports", "An event named like a readable field");
        failsToBuild(DeviceType.builder(id("k")).model("K").field(Field.text("name", Access.READ)).entity(Ha.sensor("n", Field.text("name", Access.READ))),
                "link setting", "A field named like a link setting");
        failsToBuild(DeviceType.builder(id("l")).model("L").field(power).setting(new Setting<>(power)).setting(new Setting<>(power)), "setting power twice",
                "A setting declared twice");
        failsToBuild(DeviceType.builder(id("plug")).model("Plug").field(on).entity(Ha.switchOf("plug", on).on(true).off(false)), "state action",
                "A switch over a writable state field still needs the state action, the only state in-port that toggles");
        failsToBuild(base("n").field(Field.inline("blob", ValueCodecs.LightState.MAP_CODEC, ValueCodecs.LightState.DEFAULT, Access.READ_WRITE, PortType.JSON)), "which is no light",
                "A writable inline field of a kind whose command keys nothing checks");
        failsToBuild(base("o").use("trigger_now"), "which is no declared action", "A use by hand of an undeclared action");
        failsToBuild(base("p").action(new Action("level", PortType.LEVEL, false)).use("level"), "neither toggles nor fires",
                "A use by hand of an action that needs a value");
        check(base("q").action(Action.press("ring")).use("ring").build().use().orElseThrow().handValue().equals(Optional.of(Coerce.PRESS))
                && SmartLamp.TYPE.use().orElseThrow().handValue().equals(Optional.of(Coerce.TOGGLE)),
                "A hand fires a pressed action and toggles a switch state");
        DeviceType declared = base("m").entity(Ha.event("pulse", "pulse")).event(EventSpec.of("pulse")).entity(Ha.button("trigger_now"))
                .action(Action.press("trigger_now")).build();
        check(declared.port(Port.Direction.OUT, "pulse").isPresent() && declared.port(Port.Direction.IN, "trigger_now").isPresent(), "Declared events and actions build");
        expectFailure(() -> new Setting<>(powered), "A setting over a read-only field");
        expectFailure(() -> new Setting<>(SmartLamp.LIGHT), "A setting over an inline field");
    }

    private static DeviceType.Builder base(String path) {
        Field<Boolean> powered = Field.bool("powered", Access.READ);
        return DeviceType.builder(id(path)).model("Check").unloaded(UnloadedPolicy.UNAVAILABLE).field(powered).entity(Ha.binarySensor("powered", powered));
    }

    // Setting.parse takes what fits the kind and refuses values out of range and garbage.
    private static void settingsParse(DeviceType type) {
        for (Setting<?> setting : type.settings()) {
            String what = type + " setting " + setting.key();
            switch (setting.kind()) {
                case PortType.Number number -> {
                    accepts(setting, IntTag.valueOf((int) number.min()), new JsonPrimitive((int) number.min()), what);
                    accepts(setting, IntTag.valueOf((int) number.max()), new JsonPrimitive((int) number.max()), what);
                    refuses(setting, IntTag.valueOf((int) number.min() - 1), what + " below its range");
                    refuses(setting, IntTag.valueOf((int) number.max() + 1), what + " above its range");
                    if (number.integral()) refuses(setting, DoubleTag.valueOf(number.min() + 0.5), what + " not whole");
                    refuses(setting, StringTag.valueOf(Integer.toString((int) number.min())), what + " as text");
                }
                case PortType.Enum enumKind -> {
                    for (String value : enumKind.values()) accepts(setting, StringTag.valueOf(value.toUpperCase(Locale.ROOT)), new JsonPrimitive(value), what);
                    refuses(setting, StringTag.valueOf("nope"), what + " unknown name");
                    refuses(setting, IntTag.valueOf(0), what + " as a number");
                }
                case PortType.Bool _ -> {
                    accepts(setting, ByteTag.valueOf(true), new JsonPrimitive(true), what);
                    refuses(setting, StringTag.valueOf("maybe"), what + " garbage");
                    refuses(setting, IntTag.valueOf(2), what + " 2");
                }
                case PortType.Text text -> {
                    // The empty text is every text setting's default; what else a text is, its codec decides.
                    accepts(setting, StringTag.valueOf(""), new JsonPrimitive(""), what);
                    refuses(setting, StringTag.valueOf("x".repeat(text.maxLength() + 1)), what + " over its length");
                    refuses(setting, IntTag.valueOf(0), what + " as a number");
                }
                default -> throw new AssertionError(what + ": no samples for kind " + setting.kind().describe());
            }
            if (!(setting.kind() instanceof PortType.Text)) refuses(setting, StringTag.valueOf("garbage"), what + " garbage");
        }
    }

    private static void accepts(Setting<?> setting, Tag tag, JsonElement expected, String what) {
        DataResult<?> result = setting.parse(tag);
        check(result.result().isPresent(), what + " accepts " + tag + ": " + result);
        check(encode(setting.field(), result.result().get()).equals(expected), what + " reads " + tag + " as " + expected);
    }

    private static void refuses(Setting<?> setting, Tag tag, String what) {
        check(setting.parse(tag).result().isEmpty(), what + " refuses " + tag);
    }

    @SuppressWarnings("unchecked")
    private static <T> JsonElement encode(Field<T> field, Object value) {
        return field.encode((T) value);
    }

    // Home Assistant's /set stays lenient: only the client's settings are strict.
    private static void lenientCommandsStay() {
        check(RedstoneOutput.POWER.parse(new JsonPrimitive(99)).orElseThrow() == 15, "HA's power 99 is clamped to 15");
        check(PresenceSensor.RANGE.parse(new JsonPrimitive("7.6")).orElseThrow() == 8, "HA's range \"7.6\" is rounded to 8");
        check(new Setting<>(PresenceSensor.RANGE).parse(IntTag.valueOf(99)).result().isEmpty(), "while the setting refuses 99");
        check(RedstoneOutput.Output.DEFAULT.apply(new CommandReader(json("{\"power\":99}"))).orElseThrow().power() == 15, "A command still clamps");
        // An on/off field reads every value its BOOL kind accepts, as a setting and from a wire.
        Field<RedstoneOutput.Switch> onOff = Field.onOff("state", RedstoneOutput.Switch.class, Access.READ_WRITE);
        check(new Setting<>(onOff).parse(ByteTag.valueOf(true)).result().orElseThrow() == RedstoneOutput.Switch.ON
                && onOff.parse(new JsonPrimitive(false)).orElseThrow() == RedstoneOutput.Switch.OFF && onOff.parse(new JsonPrimitive("on")).orElseThrow() == RedstoneOutput.Switch.ON
                && onOff.parse(new JsonPrimitive("dim")).isEmpty(), "An on/off field reads booleans and ON/OFF");
        check(onOff.encode(RedstoneOutput.Switch.ON).equals(new JsonPrimitive("ON")), "An on/off field writes its serialized name");
    }

    /**
     * M3-9, D5, D11: what a command to a mirrored lamp with control asks of its source, through the service-call table:
     * the light's state, brightness, colour (any form the lamp reads) and colour temperature (kelvin or mireds) for a
     * light, without its effect; on and off for a switch-like entity.
     */
    private static void lampForward(TwinAdapter twin) {
        Binding light = Binding.of(twin.sourceEnd("light.kitchen")).withControl(true);
        JsonObject blue = new JsonObject();
        blue.addProperty("state", "ON");
        blue.addProperty("brightness", 100);
        blue.addProperty("color", "#0000ff");
        blue.addProperty("effect", "pulse");
        List<Call> calls = twin.calls(blue, HaCalls.Mirror.live("off"), light);
        check(calls.size() == 1 && calls.getFirst().action().equals("light.turn_on") && calls.getFirst().data().toString().equals("{\"brightness\":100,\"rgb_color\":[0,0,255]}"),
                "A lamp command becomes one turn_on with brightness and rgb_color, never its effect: " + calls);
        JsonObject kelvin = new JsonObject();
        kelvin.addProperty("color_temp_kelvin", 3000);
        check(twin.calls(kelvin, HaCalls.Mirror.live("on"), light).getFirst().data().toString().equals("{\"color_temp_kelvin\":3000}"),
                "A colour temperature is sent in kelvin");
        JsonObject off = new JsonObject();
        off.addProperty("state", "OFF");
        off.addProperty("transition", 2);
        check(twin.calls(off, HaCalls.Mirror.live("on"), light).getFirst().toString().equals("light.turn_off {\"transition\":2}"), "OFF with a fade is turn_off with it");
        JsonObject toggle = new JsonObject();
        toggle.addProperty("state", "TOGGLE");
        check(twin.forward(toggle, HaCalls.Mirror.NONE, light).getFirst() instanceof HaCalls.Refused refused && refused.reason() == HaCalls.Reason.NOT_LIVE,
                "A toggle with nothing known is refused locally");
        Binding fan = Binding.of(twin.sourceEnd("fan.attic")).withControl(true);
        check(twin.calls(off, HaCalls.Mirror.live("on"), fan).getFirst().action().equals("fan.turn_off"), "A lamp mirroring a fan switches it off");
        check(twin.calls(off, HaCalls.Mirror.live("on"), light.withControl(false)).isEmpty(), "Without control nothing is asked");
    }

    private static void linkSettings() {
        check(keys(SmartLamp.TYPE.linkSettings()).equals(List.of("name", "expose", "access", "raw.channel", "raw.private", "raw.topic", "raw.on", "raw.off",
                "twin.entity", "twin.device", "twin.preset", "twin.unavailable", "twin.control", "twin.resume")),
                "A Raw Link input has no retain and no QoS; the lamp's twin keys come last, control last (M3-9), then Resume (M3-13)");
        check(keys(RedstoneOutput.TYPE.linkSettings()).equals(List.of("name", "expose", "access", "raw.channel", "raw.private", "raw.topic", "raw.on", "raw.off",
                "twin.entity", "twin.device", "twin.preset", "twin.path", "twin.invert", "twin.min", "twin.max", "twin.unavailable", "twin.control", "twin.resume")),
                "The output's Raw Link is an input too; its twin keys have the path and range rows (M3-12)");
        check(keys(SmartPlug.TYPE.linkSettings()).equals(List.of("name", "expose", "access", "raw.channel", "raw.private", "raw.topic", "raw.on", "raw.off",
                "twin.entity", "twin.device", "twin.preset", "twin.unavailable", "twin.control", "twin.resume")), "The plug's twin keys have no rows (M3-12)");
        check(keys(RedstoneInput.TYPE.linkSettings()).equals(List.of("name", "expose", "access", "raw.channel", "raw.private", "raw.topic", "raw.retain", "raw.qos",
                "raw.on", "raw.off", "twin.target", "twin.resume")),
                "A Raw Link output has retain and QoS; the input's one twin target comes last (M3-14), then Resume (M3-13)");
        check(keys(PresenceSensor.TYPE.linkSettings()).equals(List.of("name", "expose", "access", "raw.channel", "raw.private", "raw.topic", "raw.retain", "raw.qos")),
                "The presence sensor's count has no custom payloads");
        check(keys(ClimateSensor.TYPE.linkSettings()).equals(keys(PresenceSensor.TYPE.linkSettings())), "The climate sensor's reading has no custom payloads");
        check(keys(base("n").build().linkSettings()).equals(List.of("name", "expose", "access")), "A type without a Raw Link");
        check(RedstoneInput.TYPE.linkSetting("raw.qos").isPresent() && SmartLamp.TYPE.linkSetting("raw.qos").isEmpty(), "Link settings by key");
        check(LinkSetting.NAME.gate() == LinkSetting.Gate.CONFIGURE && LinkSetting.EXPOSE.gate() == LinkSetting.Gate.EXPOSE
                && LinkSetting.ACCESS.gate() == LinkSetting.Gate.ACCESS && LinkSetting.RAW_TOPIC.gate() == LinkSetting.Gate.CONFIGURE, "Gates");

        check(value(LinkSetting.NAME, StringTag.valueOf(" Garage ")).equals("Garage"), "A name is stripped");
        check(value(LinkSetting.NAME, StringTag.valueOf("§cRed\u0007")).equals("Red"), "A name is sanitised");
        check(value(LinkSetting.NAME, StringTag.valueOf("\"Quoted\"")).equals("\"Quoted\""), "A name keeps the quotes a player types (F7)");
        check(value(LinkSetting.NAME, StringTag.valueOf("n".repeat(64))).length() == 64, "A name of 64 characters");
        refused(LinkSetting.NAME, StringTag.valueOf("n".repeat(65)), "A name of 65 characters");
        refused(LinkSetting.NAME, StringTag.valueOf("   "), "A blank name");
        refused(LinkSetting.NAME, StringTag.valueOf("§c"), "A name that is only a formatting code");
        refused(LinkSetting.NAME, IntTag.valueOf(5), "A name that is a number");

        check(value(LinkSetting.EXPOSE, StringTag.valueOf("on")).equals(Optional.of(true)) && value(LinkSetting.EXPOSE, StringTag.valueOf("OFF")).equals(Optional.of(false))
                && value(LinkSetting.EXPOSE, StringTag.valueOf("default")).isEmpty(), "Exposure on, off and default");
        refused(LinkSetting.EXPOSE, StringTag.valueOf("maybe"), "An unknown exposure");
        for (AccessMode mode : AccessMode.values()) {
            check(value(LinkSetting.ACCESS, StringTag.valueOf(mode.getSerializedName().toUpperCase(Locale.ROOT))) == mode, "Access " + mode);
        }
        refused(LinkSetting.ACCESS, StringTag.valueOf("owner"), "An unknown access mode");

        for (LinkSetting<String> topic : List.of(LinkSetting.RAW_TOPIC, LinkSetting.RAW_CHANNEL, LinkSetting.RAW_PRIVATE)) {
            check(value(topic, StringTag.valueOf("home/garage")).equals("home/garage") && value(topic, StringTag.valueOf("")).isEmpty(), topic.key() + ": a topic, or empty for none");
            check(value(topic, StringTag.valueOf("t".repeat(256))).length() == 256, topic.key() + " of 256 bytes");
            refused(topic, StringTag.valueOf("t".repeat(257)), topic.key() + " of 257 bytes");
            refused(topic, StringTag.valueOf("€".repeat(86)), topic.key() + " of 86 characters but 258 bytes");
            refused(topic, StringTag.valueOf("home/#"), topic.key() + " with a wildcard");
            refused(topic, StringTag.valueOf("home/+/x"), topic.key() + " with a level wildcard");
        }
        check(value(LinkSetting.RAW_QOS, IntTag.valueOf(0)) == 0 && value(LinkSetting.RAW_QOS, ByteTag.valueOf((byte) 1)) == 1, "QoS 0 and 1");
        refused(LinkSetting.RAW_QOS, IntTag.valueOf(2), "QoS 2");
        refused(LinkSetting.RAW_QOS, IntTag.valueOf(-1), "QoS -1");
        refused(LinkSetting.RAW_QOS, StringTag.valueOf("1"), "QoS as text");
        check(value(LinkSetting.RAW_RETAIN, ByteTag.valueOf(true)) && !value(LinkSetting.RAW_RETAIN, ByteTag.valueOf(false)), "Retain on and off");
        refused(LinkSetting.RAW_RETAIN, StringTag.valueOf("maybe"), "Retain garbage");
        for (LinkSetting<String> payload : List.of(LinkSetting.RAW_ON, LinkSetting.RAW_OFF)) {
            check(value(payload, StringTag.valueOf("p".repeat(1024))).length() == 1024 && value(payload, StringTag.valueOf("")).isEmpty(), payload.key() + " of 1 KiB, or empty");
            refused(payload, StringTag.valueOf("p".repeat(1025)), payload.key() + " over 1 KiB");
            refused(payload, StringTag.valueOf("ü".repeat(513)), payload.key() + " of 1026 bytes");
        }
        check(LinkSetting.isKey("name") && LinkSetting.isKey("raw.topic") && !LinkSetting.isKey("power"), "Link setting keys");
    }

    private static <T> T value(LinkSetting<T> setting, Tag tag) {
        DataResult<T> result = setting.parse(tag);
        return result.result().orElseThrow(() -> new AssertionError(setting.key() + " refuses " + tag + ": " + result));
    }

    private static void refused(LinkSetting<?> setting, Tag tag, String message) {
        check(setting.parse(tag).result().isEmpty(), message + " is refused");
    }

    private static void actions() {
        Action state = Action.state();
        check(state.parse(" toggle ").orElseThrow().getAsString().equals("TOGGLE") && state.parse("on").orElseThrow().getAsString().equals("ON")
                && state.parse("false").orElseThrow().getAsString().equals("OFF") && state.parse("1").orElseThrow().getAsString().equals("ON")
                && state.parse("dim").isEmpty(), "The state action takes ON, OFF, TOGGLE and boolean spellings");
        check(state.command(new JsonPrimitive("ON")).equals(json("{\"state\":\"ON\"}")), "The state action's command is Home Assistant's");
        // The command reaches the devices as Home Assistant's does: ON restores the output's last level, TOGGLE flips the lamp.
        RedstoneOutput.Output dimmed = new RedstoneOutput.Output(0, 6, RedstoneOutput.Mode.LEVEL, RedstoneOutput.PowerOn.PREVIOUS);
        check(dimmed.apply(new CommandReader(state.command(state.parse("on").orElseThrow()))).orElseThrow().power() == 6, "ON restores the output's last level");
        check(ValueCodecs.LightState.DEFAULT.merge(state.command(state.parse("toggle").orElseThrow())).on(), "TOGGLE turns the lamp on");
        Action press = Action.press("trigger_now");
        check(press.kind().equals(PortType.event("trigger_now")) && press.parse("").orElseThrow().getAsString().equals("PRESS")
                && press.parse("Press").isPresent() && press.parse("x").isEmpty(), "A pressed action fires with PRESS or nothing");
        Action message = new Action("message", PortType.text(5), false);
        check(message.parse("hello").isPresent() && message.parse("hello!").isEmpty(), "Another action's value must fit its kind");
        check(message.parse("42").orElseThrow().equals(new JsonPrimitive("42")) && message.parse("true").orElseThrow().equals(new JsonPrimitive("true")),
                "A text action keeps text that looks like a number or a boolean");
        Action level = new Action("level", PortType.LEVEL, false);
        check(level.parse(" 7 ").orElseThrow().getAsInt() == 7 && level.parse("16").isEmpty() && level.parse("x").isEmpty(), "A level action reads a number");
        expectFailure(() -> new Action("power", PortType.LEVEL, true), "A toggling action that is no switch state");
        expectFailure(() -> new Action("Bad", PortType.BOOL, false), "An invalid action key");
    }

    private static void events() {
        check(RedstoneInput.TYPE.eventProblem(RedstoneInput.pulse(11)).isEmpty(), "The input's pulse is declared");
        check(RedstoneInput.pulse(11).equals(json("{\"event_type\":\"pulse\",\"signal\":11}")), "The pulse payload is unchanged");
        check(RedstoneInput.TYPE.eventProblem(json("{\"event_type\":\"pulse\",\"signal\":16}")).isPresent(), "A pulse beyond level 15");
        check(RedstoneInput.TYPE.eventProblem(json("{\"event_type\":\"pulse\"}")).isPresent(), "A pulse without its signal");
        check(RedstoneInput.TYPE.eventProblem(json("{\"event_type\":\"pulse\",\"signal\":3,\"x\":1}")).isPresent(), "A pulse with an undeclared attribute");
        check(RedstoneInput.TYPE.eventProblem(json("{\"event_type\":\"press\"}")).isPresent(), "An undeclared event type");
        check(RedstoneInput.TYPE.eventProblem(json("{\"signal\":3}")).isPresent(), "An event without a type");
        check(SmartLamp.TYPE.eventProblem(RedstoneInput.pulse(1)).isPresent(), "A lamp declares no pulse");
        expectFailure(() -> EventSpec.of("pulse").attribute("event_type", PortType.JSON), "An attribute named event_type");
        expectFailure(() -> EventSpec.of("Pulse"), "An invalid event type");
    }

    // The strict validators behind settings, actions and wires.
    private static void portKinds() {
        check(PortType.LEVEL.problem(new JsonPrimitive(15)).isEmpty() && PortType.LEVEL.problem(new JsonPrimitive(16)).isPresent()
                && PortType.LEVEL.problem(new JsonPrimitive(7.5)).isPresent() && PortType.LEVEL.problem(new JsonPrimitive("7")).isPresent()
                && PortType.LEVEL.problem(new JsonPrimitive(7.0)).isEmpty(), "LEVEL: whole numbers 0-15");
        check(PortType.BOOL.problem(new JsonPrimitive("Off")).isEmpty() && PortType.BOOL.problem(new JsonPrimitive(1)).isEmpty()
                && PortType.BOOL.problem(new JsonPrimitive(2)).isPresent() && PortType.BOOL.problem(new JsonPrimitive("maybe")).isPresent(), "BOOL spellings");
        check(PortType.number(0, 1, false, null).problem(new JsonPrimitive(0.25)).isEmpty() && PortType.integer(0, 1).problem(new JsonPrimitive(0.25)).isPresent(),
                "NUMBER: whole only when integral");
        check(PortType.number(0, 10, true, null).problem(new JsonPrimitive(new BigDecimal("1e999999999"))).isPresent(), "A huge number is out of range, not expanded");
        check(PortType.text(3).problem(new JsonPrimitive("😀😀😀")).isEmpty() && PortType.text(3).problem(new JsonPrimitive("abcd")).isPresent()
                && PortType.text(3).problem(new JsonPrimitive(1)).isPresent(), "TEXT counts code points and takes strings only");
        check(PortType.COLOR.problem(json("{\"r\":255,\"g\":0,\"b\":9}")).isEmpty() && PortType.COLOR.problem(json("{\"r\":256,\"g\":0,\"b\":0}")).isPresent()
                && PortType.COLOR.problem(json("{\"r\":1,\"g\":2}")).isPresent(), "COLOR: {r,g,b} 0-255");
        check(PortType.event("press").problem(json("{\"event_type\":\"press\",\"button\":\"left\"}")).isEmpty()
                && PortType.event("press").problem(new JsonPrimitive("press")).isEmpty() && PortType.event("press").problem(json("{\"event_type\":\"hold\"}")).isPresent(),
                "EVENT: an event of its types");
        check(PortType.LIGHT.problem(json("{\"state\":\"ON\"}")).isEmpty() && PortType.LIGHT.problem(new JsonPrimitive("ON")).isPresent(), "LIGHT: an object");
        check(PortType.enumOf(List.of("a", "b")).problem(new JsonPrimitive("B")).isEmpty() && PortType.JSON.problem(JsonNull.INSTANCE).isPresent(),
                "ENUM ignores case; JSON is anything but null");
        check(ValueCodecs.bool("On").equals(Coerce.bool("On")) && ValueCodecs.sanitizeText("§aHi\n").equals("Hi"), "The device codecs use flow.core's spellings and sanitiser");
    }

    // The default pairs of M2-15's wiring are compatible; mismatched pairs are refused when a wire is made.
    private static void portCompatibility() {
        Port powered = out(RedstoneInput.TYPE, "powered");
        check(in(RedstoneOutput.TYPE, "state").accepts(powered) && in(SmartLamp.TYPE, "state").accepts(powered), "Input powered -> Output and Lamp state");
        check(in(SmartLamp.TYPE, "state").accepts(out(PresenceSensor.TYPE, "occupancy")), "Presence occupancy -> Lamp state");
        check(in(RedstoneOutput.TYPE, "state").accepts(out(RedstoneInput.TYPE, "pulse")), "A pulse toggles the output's state");
        check(!in(RedstoneOutput.TYPE, "power").accepts(out(RedstoneInput.TYPE, "pulse")), "A pulse sets no power level");
        check(in(RedstoneOutput.TYPE, "power").accepts(out(RedstoneInput.TYPE, "signal")) && in(RedstoneOutput.TYPE, "power").accepts(out(PresenceSensor.TYPE, "count")),
                "Signal and count -> power");
        check(in(SmartLamp.TYPE, "state").accepts(out(RedstoneOutput.TYPE, "state")), "Output state -> Lamp state");
        check(!in(RedstoneOutput.TYPE, "mode").accepts(out(PresenceSensor.TYPE, "count")), "A count selects no mode");
        check(!out(RedstoneOutput.TYPE, "power").accepts(powered), "Wires end at in-ports only");
        // The bus routes over device ports as they are (Port is its PortSpec), with each mapping's own pairs.
        Port state = in(RedstoneOutput.TYPE, "state");
        Port pulse = out(RedstoneInput.TYPE, "pulse");
        check(Mapping.PULSE_TOGGLE.compatible(powered.kind(), state.kind(), state.toggles()) && Mapping.PULSE_TOGGLE.compatible(pulse.kind(), state.kind(), state.toggles())
                && Mapping.INVERT.compatible(powered.kind(), in(SmartLamp.TYPE, "state").kind(), true), "Input powered or pulse -> Output state toggles; powered -> Lamp state inverts");
        check(!Mapping.INVERT.compatible(pulse.kind(), state.kind(), state.toggles())
                && !Mapping.PULSE_TOGGLE.compatible(powered.kind(), in(RedstoneOutput.TYPE, "power").kind(), false), "An event inverts nothing; a power level does not toggle");
        // TOGGLE, which a pulse on a switch state coerces to, is a value of that port though not of its BOOL kind.
        JsonElement toggle = Coerce.coerce(RedstoneInput.pulse(11), PortType.event("pulse"), PortType.BOOL, true).orElseThrow();
        check(in(RedstoneOutput.TYPE, "state").problem(toggle).isEmpty() && in(SmartLamp.TYPE, "state").problem(new JsonPrimitive("toggle")).isEmpty()
                && PortType.BOOL.problem(toggle).isPresent(), "A switch state takes TOGGLE");
        check(new Port("on", Port.Direction.IN, PortType.BOOL, Port.Origin.FIELD, false).problem(toggle).isPresent()
                && in(RedstoneOutput.TYPE, "state").problem(new JsonPrimitive("dim")).isPresent(), "Other values still meet the kind");
    }

    /**
     * M2-15: wires between real device types. The default pairs, the out-port values a state gives and the command an
     * in-port gets, the rules a new wire must pass, and the message bus routing over the registry: a message reaches each
     * wired device and group as its command, in the order the wires were made; a removed device takes its wires along,
     * and a wire whose end has no port is unroutable.
     */
    private static void wiring() {
        DeviceType switchType = DeviceType.builder(id("switch")).model("Switch").unloaded(UnloadedPolicy.UNAVAILABLE)
                .event(EventSpec.of("press")).entity(Ha.event("press", "press")).build();
        check(DefaultWiring.of(RedstoneInput.TYPE, RedstoneOutput.TYPE).equals(Optional.of(new DefaultWiring.Pair("powered", "state", Mapping.FOLLOW)))
                && DefaultWiring.of(RedstoneInput.TYPE, SmartLamp.TYPE).equals(Optional.of(new DefaultWiring.Pair("powered", "state", Mapping.FOLLOW))),
                "Input powered follows into an Output's and a Lamp's state");
        check(DefaultWiring.of(RedstoneInput.TYPE, SmartPlug.TYPE).equals(Optional.of(new DefaultWiring.Pair("powered", "state", Mapping.FOLLOW)))
                        && DefaultWiring.of(PresenceSensor.TYPE, SmartPlug.TYPE).equals(Optional.of(new DefaultWiring.Pair("occupancy", "state", Mapping.FOLLOW))),
                "Input powered and Presence occupancy follow into a Plug's state");
        check(DefaultWiring.of(PresenceSensor.TYPE, SmartLamp.TYPE).equals(Optional.of(new DefaultWiring.Pair("occupancy", "state", Mapping.FOLLOW))),
                "Presence occupancy follows into a Lamp's state");
        check(DefaultWiring.of(switchType, SmartLamp.TYPE).equals(Optional.of(new DefaultWiring.Pair("press", "state", Mapping.PULSE_TOGGLE))),
                "A press toggles a switch state");
        check(DefaultWiring.of(switchType, SmartPlug.TYPE).equals(Optional.of(new DefaultWiring.Pair("press", "state", Mapping.PULSE_TOGGLE))),
                "A press toggles a plug");
        check(DefaultWiring.of(SmartLamp.TYPE, RedstoneInput.TYPE).isEmpty() && DefaultWiring.of(RedstoneInput.TYPE, PresenceSensor.TYPE).isEmpty(),
                "No default into a device without a state in-port");
        check(DefaultWiring.mapping(out(RedstoneInput.TYPE, "pulse"), in(RedstoneOutput.TYPE, "state")) == Mapping.PULSE_TOGGLE
                && DefaultWiring.mapping(out(RedstoneInput.TYPE, "powered"), in(SmartLamp.TYPE, "state")) == Mapping.FOLLOW
                && DefaultWiring.mapping(out(RedstoneInput.TYPE, "signal"), in(RedstoneOutput.TYPE, "power")) == Mapping.FOLLOW,
                "A wire without a mapping toggles from an event, else follows");

        Map<String, JsonElement> lamp = SmartLamp.TYPE.outputs(defaultState(SmartLamp.TYPE));
        check(List.copyOf(lamp.keySet()).equals(List.of("light", "light_level", "location")) && lamp.get("light").isJsonObject()
                && lamp.get("light").getAsJsonObject().keySet().equals(Set.of("state", "brightness", "color_mode", "color", "effect"))
                && PortType.LIGHT.problem(lamp.get("light")).isEmpty(), "A lamp's out-ports carry its light as one object: " + lamp);
        Map<String, JsonElement> output = RedstoneOutput.TYPE.outputs(defaultState(RedstoneOutput.TYPE));
        check(List.copyOf(output.keySet()).equals(List.of("state", "power", "mode", "power_on_behaviour", "last_level", "location"))
                && output.get("state").equals(new JsonPrimitive("OFF")), "An output's out-ports carry its fields as its state holds them: " + output);
        check(RedstoneOutput.TYPE.outputs(json("{\"power\":3}")).keySet().equals(Set.of("power")), "A field the state lacks emits nothing");
        check(SmartLamp.TYPE.inPortCommand("light", json("{\"state\":\"ON\",\"brightness\":9}")).equals(json("{\"state\":\"ON\",\"brightness\":9}"))
                && SmartLamp.TYPE.inPortCommand("state", new JsonPrimitive("TOGGLE")).equals(json("{\"state\":\"TOGGLE\"}"))
                && RedstoneOutput.TYPE.inPortCommand("power", new JsonPrimitive(7)).equals(json("{\"power\":7}")),
                "An in-port's command: {port: value}, an inline field's value with its keys at the top level");
        JsonObject lampState = defaultState(SmartLamp.TYPE);
        check(SmartLamp.TYPE.changedOutputs(lampState, lampState.deepCopy()).isEmpty(), "An unchanged state emits nothing");
        check(SmartLamp.TYPE.changedOutputs(null, lampState).equals(SmartLamp.TYPE.outputs(lampState)), "A device without a state yet emits every output");
        JsonObject dimmed = lampState.deepCopy();
        dimmed.addProperty("brightness", 9);
        Map<String, JsonElement> changed = SmartLamp.TYPE.changedOutputs(lampState, dimmed);
        check(!lampState.get("brightness").equals(dimmed.get("brightness")) && changed.keySet().equals(Set.of("light"))
                && changed.get("light").getAsJsonObject().get("brightness").equals(dimmed.get("brightness")), "A changed brightness emits the light as one object: " + changed);

        DeviceRegistry registry = new DeviceRegistry("wiring_0001", UUID.randomUUID());
        DeviceRecord input = register(registry, RedstoneInput.TYPE.id(), "input", 0);
        DeviceRecord out = register(registry, RedstoneOutput.TYPE.id(), "output", 1);
        DeviceRecord lamp1 = register(registry, SmartLamp.TYPE.id(), "lamp_1", 2);
        DeviceRecord lamp2 = register(registry, SmartLamp.TYPE.id(), "lamp_2", 3);
        DeviceRecord gadget = register(registry, id("gadget"), "gadget", 4);
        DeviceGroup lamps = registry.createGroup(UUID.randomUUID(), "Lamps");
        registry.joinGroup(lamps, lamp1);
        registry.joinGroup(lamps, lamp2);
        DeviceGroup empty = registry.createGroup(UUID.randomUUID(), "Empty");
        Endpoint powered = Endpoint.port(input.id(), "powered");
        Endpoint outputState = Endpoint.port(out.id(), "state");
        Endpoint lampsState = Endpoint.groupPort(lamps.id(), "state");
        check(RegistryWiring.problem(registry, powered, outputState, Mapping.FOLLOW).isEmpty()
                && RegistryWiring.problem(registry, powered, lampsState, Mapping.INVERT).isEmpty()
                && RegistryWiring.problem(registry, Endpoint.port(lamp1.id(), "light_level"), lampsState, Mapping.FOLLOW).isEmpty(),
                "Compatible ports of registered devices and groups may be wired, a member to its own group too");
        Map<String, Optional<String>> refused = new LinkedHashMap<>();
        refused.put("its source a group", RegistryWiring.problem(registry, Endpoint.groupPort(lamps.id(), "light_level"), outputState, Mapping.FOLLOW));
        refused.put("its own port", RegistryWiring.problem(registry, outputState, Endpoint.port(out.id(), "state"), Mapping.FOLLOW));
        refused.put("another port of itself", RegistryWiring.problem(registry, Endpoint.port(out.id(), "state"), Endpoint.port(out.id(), "power"), Mapping.FOLLOW));
        refused.put("no such out-port", RegistryWiring.problem(registry, Endpoint.port(input.id(), "nope"), outputState, Mapping.FOLLOW));
        refused.put("no such in-port", RegistryWiring.problem(registry, powered, Endpoint.port(out.id(), "nope"), Mapping.FOLLOW));
        refused.put("an in-port as its source", RegistryWiring.problem(registry, Endpoint.port(lamp1.id(), "state"), outputState, Mapping.FOLLOW));
        refused.put("kinds that do not fit", RegistryWiring.problem(registry, Endpoint.port(input.id(), "pulse"), Endpoint.port(out.id(), "power"), Mapping.FOLLOW));
        refused.put("a mapping that cannot carry it", RegistryWiring.problem(registry, Endpoint.port(input.id(), "pulse"), outputState, Mapping.INVERT));
        refused.put("an unregistered target", RegistryWiring.problem(registry, powered, Endpoint.port(UUID.randomUUID(), "state"), Mapping.FOLLOW));
        refused.put("an unregistered source", RegistryWiring.problem(registry, Endpoint.port(UUID.randomUUID(), "powered"), outputState, Mapping.FOLLOW));
        refused.put("a group without members", RegistryWiring.problem(registry, powered, Endpoint.groupPort(empty.id(), "state"), Mapping.FOLLOW));
        refused.put("a type this MineQTT does not know", RegistryWiring.problem(registry, powered, Endpoint.port(gadget.id(), "state"), Mapping.FOLLOW));
        refused.put("a topic end", RegistryWiring.problem(registry, powered, new Endpoint.TopicRef("a/b", ""), Mapping.FOLLOW));
        refused.put("a Home Assistant end", RegistryWiring.problem(registry, powered, new Endpoint.HaEntityRef("light.kitchen", "state"), Mapping.FOLLOW));
        refused.put("a wire from a Home Assistant end", RegistryWiring.problem(registry, new Endpoint.HaEntityRef("light.kitchen", "state"), outputState, Mapping.FOLLOW));
        refused.forEach((what, problem) -> check(problem.isPresent(), "A wire to " + what + " is refused"));

        Wire toOutput = wire(powered, outputState, Mapping.FOLLOW);
        Wire toLamps = wire(powered, lampsState, Mapping.INVERT);
        Wire pulse = wire(Endpoint.port(input.id(), "pulse"), outputState, Mapping.PULSE_TOGGLE);
        Wire toGadget = wire(powered, Endpoint.port(gadget.id(), "state"), Mapping.FOLLOW);
        registry.addWire(toOutput);
        check(RegistryWiring.problem(registry, powered, outputState, Mapping.INVERT).orElse("").contains("wired already"), "Two ports are wired once");
        registry.addWire(toLamps);
        registry.addWire(pulse);
        // The registry keeps a wire to a type this MineQTT does not know (another version made it); the bus cannot route it.
        registry.addWire(toGadget);
        List<String> delivered = new ArrayList<>();
        MessageBus bus = new MessageBus(new RegistryWiring(registry), (wire, message) -> {
            RegistryWiring.Command command = RegistryWiring.command(registry, wire.to(), message.payload()).orElseThrow();
            delivered.add(command.target() + " " + command.command());
            return MessageBus.Outcome.TAKEN;
        }, new MessageBus.Listener() {
            @Override
            public void loopCut(Wire wire, FlowMessage message) {
                throw new AssertionError("No loop here");
            }

            @Override
            public void failed(Wire wire, FlowMessage message, RuntimeException e) {
                throw new AssertionError("No delivery throws here", e);
            }
        }, System::nanoTime);
        check(bus.emit(FlowMessage.Kind.VALUE, powered, "powered", new JsonPrimitive(true), 0), "A wired out-port emits");
        check(!bus.emit(FlowMessage.Kind.VALUE, Endpoint.port(lamp1.id(), "light_level"), "light_level", new JsonPrimitive(3), 0), "An unwired out-port emits nothing");
        bus.drain();
        check(delivered.equals(List.of(out.id() + " {\"state\":true}", lamps.id() + " {\"state\":false}")),
                "A value reaches each wired device and group as its command, in the order the wires were made: " + delivered);
        check(bus.dropped(MessageBus.Drop.UNROUTABLE) == 1, "A wire whose target has no port is unroutable");
        delivered.clear();
        bus.emit(FlowMessage.Kind.EVENT, Endpoint.port(input.id(), "pulse"), "pulse", RedstoneInput.pulse(15), 0);
        bus.drain();
        check(delivered.equals(List.of(out.id() + " {\"state\":\"TOGGLE\"}")), "A pulse toggles the output: " + delivered);
        delivered.clear();
        registry.remove(out.id());
        check(registry.wiresFrom(powered).equals(List.of(toLamps, toGadget)) && registry.wire(pulse.id()) == null, "A removed device takes its wires along");
        bus.emit(FlowMessage.Kind.VALUE, powered, "powered", new JsonPrimitive(false), 0);
        bus.drain();
        check(delivered.equals(List.of(lamps.id() + " {\"state\":true}")), "Only the wires left carry: " + delivered);
    }

    /**
     * The pure rules of the Configurator's Link mode (M2-16): {@link LinkRules#allowed} refuses what the registry refuses
     * ({@link RegistryWiring#problem}) before it asks who may configure what, the port picker offers every compatible pair
     * with the default first and the target's group after the target, a wired pair says so, and a device's links are the
     * wires at it and into its group. Who may wire is checked in {@code verifySecurityGuards}.
     */
    private static void linkRules() {
        DeviceRegistry registry = new DeviceRegistry("links_0001", UUID.randomUUID());
        DeviceRecord input = register(registry, RedstoneInput.TYPE.id(), "input", 0);
        DeviceRecord out = register(registry, RedstoneOutput.TYPE.id(), "output", 1);
        DeviceRecord lamp1 = register(registry, SmartLamp.TYPE.id(), "lamp_1", 2);
        DeviceRecord lamp2 = register(registry, SmartLamp.TYPE.id(), "lamp_2", 3);
        DeviceRecord gadget = register(registry, id("gadget"), "gadget", 4);
        DeviceGroup lamps = registry.createGroup(UUID.randomUUID(), "Lamps");
        registry.joinGroup(lamps, lamp1);
        registry.joinGroup(lamps, lamp2);
        UUID player = UUID.randomUUID();
        Endpoint powered = Endpoint.port(input.id(), "powered");
        Predicate<UUID> everyone = device -> true;
        for (Wire refused : List.of(wire(powered, Endpoint.port(input.id(), "signal"), Mapping.FOLLOW),
                wire(Endpoint.port(input.id(), "pulse"), Endpoint.port(out.id(), "power"), Mapping.FOLLOW),
                wire(powered, Endpoint.port(gadget.id(), "state"), Mapping.FOLLOW))) {
            Wire owned = new Wire(refused.id(), refused.from(), refused.to(), refused.mapping(), Scope.WIRELESS, player, true);
            check(LinkRules.allowed(registry, owned, everyone, 0).equals(RegistryWiring.problem(registry, owned.from(), owned.to(), owned.mapping()))
                    && LinkRules.allowed(registry, owned, everyone, 0).isPresent(), "Link mode refuses what the registry refuses: " + owned.describe());
        }

        List<LinkRules.Option> toOutput = LinkRules.options(registry, input.id(), out.id());
        LinkRules.Option first = toOutput.getFirst();
        check(first.isDefault() && first.from().equals(powered) && first.to().equals(Endpoint.port(out.id(), "state"))
                && first.mappings().equals(List.of(Mapping.FOLLOW, Mapping.INVERT, Mapping.PULSE_TOGGLE)) && first.wired().isEmpty(),
                "The picker offers the input's powered following into the output's state first: " + first);
        check(toOutput.stream().filter(LinkRules.Option::isDefault).count() == 1, "One default pair");
        for (LinkRules.Option option : toOutput) {
            Port from = RedstoneInput.TYPE.port(Port.Direction.OUT, RegistryWiring.key(option.from())).orElseThrow();
            Port to = RedstoneOutput.TYPE.port(Port.Direction.IN, RegistryWiring.key(option.to())).orElseThrow();
            check(!option.mappings().isEmpty() && option.mappings().stream().allMatch(mapping -> mapping.compatible(from.kind(), to.kind(), to.toggles()))
                    && Set.copyOf(option.mappings()).equals(Set.copyOf(Stream.of(Mapping.values()).filter(mapping -> mapping.compatible(from.kind(), to.kind(), to.toggles())).toList())),
                    "Each pair offers exactly the mappings that join its ports: " + option);
        }
        check(toOutput.stream().noneMatch(option -> RegistryWiring.key(option.from()).equals("location")),
                "A port no in-port takes is not offered (the location)");
        Optional<LinkRules.Option> pulse = toOutput.stream().filter(option -> RegistryWiring.key(option.from()).equals("pulse")).findFirst();
        check(pulse.isPresent() && pulse.get().mappings().getFirst() == Mapping.PULSE_TOGGLE, "A pulse into a state offers toggling first");

        List<LinkRules.Option> toLamp = LinkRules.options(registry, input.id(), lamp1.id());
        int firstGroup = toLamp.indexOf(toLamp.stream().filter(option -> option.to() instanceof Endpoint.GroupPortRef).findFirst().orElseThrow());
        check(toLamp.getFirst().isDefault() && toLamp.getFirst().to().equals(Endpoint.port(lamp1.id(), "state"))
                && toLamp.subList(firstGroup, toLamp.size()).stream().allMatch(option -> option.to() instanceof Endpoint.GroupPortRef port && port.group().equals(lamps.id()) && !option.isDefault())
                && toLamp.stream().filter(option -> option.to() instanceof Endpoint.GroupPortRef).count() == toLamp.stream().filter(option -> option.to() instanceof Endpoint.PortRef).count(),
                "A member's group is offered after the member, port for port: " + toLamp);
        check(LinkRules.options(registry, input.id(), input.id()).isEmpty() && LinkRules.options(registry, input.id(), gadget.id()).isEmpty()
                && LinkRules.options(registry, lamp1.id(), input.id()).isEmpty() && LinkRules.options(registry, UUID.randomUUID(), out.id()).isEmpty(),
                "Nothing is offered to the device itself, to an unknown type, to a device without in-ports or from a device that is gone");

        Wire made = new Wire(UUID.randomUUID(), powered, Endpoint.port(out.id(), "state"), Mapping.INVERT, Scope.WIRELESS, player, true);
        registry.addWire(made);
        LinkRules.Option wired = LinkRules.options(registry, input.id(), out.id()).getFirst();
        check(wired.wired().equals(Optional.of(made.id())) && wired.mappings().getFirst() == Mapping.INVERT,
                "A wired pair names its wire and offers its mapping first, for the unlink");

        Wire intoGroup = new Wire(UUID.randomUUID(), powered, Endpoint.groupPort(lamps.id(), "state"), Mapping.FOLLOW, Scope.WIRELESS, player, true);
        registry.addWire(intoGroup);
        check(LinkRules.touches(registry, made, input.id()) && LinkRules.touches(registry, made, out.id()) && !LinkRules.touches(registry, made, lamp1.id()),
                "A wire concerns the devices at its ends");
        check(LinkRules.touches(registry, intoGroup, lamp1.id()) && LinkRules.touches(registry, intoGroup, lamp2.id()) && !LinkRules.touches(registry, intoGroup, out.id()),
                "A wire into a group concerns its members");
        check(LinkRules.devices(registry, intoGroup).equals(List.of(input.id(), lamp1.id(), lamp2.id())) && LinkRules.devices(registry, made).equals(List.of(input.id(), out.id())),
                "The devices a wire touches: its source, then its target or every member of its group");
        registry.leaveGroup(lamp2);
        check(!LinkRules.touches(registry, intoGroup, lamp2.id()), "A device that left the group no longer has its wires");
    }

    private static DeviceRecord register(DeviceRegistry registry, Identifier type, String slug, int x) {
        DeviceRecord record = new DeviceRecord(UUID.randomUUID(), type, GlobalPos.of(Level.OVERWORLD, new BlockPos(x, -60, 0)), slug, slug);
        registry.add(record);
        return record;
    }

    private static Wire wire(Endpoint from, Endpoint to, Mapping mapping) {
        return new Wire(UUID.randomUUID(), from, to, mapping, Scope.WIRELESS, UUID.randomUUID(), true);
    }

    // Every readable field at its default, as a device of the type writes its state.
    private static JsonObject defaultState(DeviceType type) {
        StateWriter state = new StateWriter();
        for (Field<?> field : type.fields()) {
            if (field.access().readable()) putDefault(state, field);
        }
        return state.json();
    }

    private static Port in(DeviceType type, String key) {
        return type.port(Port.Direction.IN, key).orElseThrow(() -> new AssertionError(type + " has no in-port " + key));
    }

    private static Port out(DeviceType type, String key) {
        return type.port(Port.Direction.OUT, key).orElseThrow(() -> new AssertionError(type + " has no out-port " + key));
    }

    // A device view of every type round-trips through its stream codec.
    private static void viewRoundTrip(DeviceType type) {
        DeviceLink link = new DeviceLink(type);
        link.assign(DEVICE, type.defaultName(DEVICE), type.defaultSlug(DEVICE));
        type.rawLink().ifPresent(spec -> link.setRaw(new RawLink("mineqtt/shared/garage", spec.direction(), spec.customPayloads() ? "open" : null, null,
                spec.direction() == RawLinkSpec.Direction.OUT ? 1 : 0, null, null, false, null, RawTopics.VERSION, "garage", RawLink.Edges.BOTH)));
        StateWriter state = new StateWriter();
        for (Field<?> field : type.fields()) {
            if (field.access().readable()) putDefault(state, field);
        }
        DeviceView.Registration registration = new DeviceView.Registration("mineqtt/world_7f3a", true, Optional.of("Garage"));
        DeviceView.Diagnostics diagnostics = new DeviceView.Diagnostics(Optional.of(DEVICE), DeviceView.Availability.DISCONNECTED, "reconnecting",
                Optional.of(new DeviceView.Traffic("mineqtt/world_7f3a/garage/set", 14, 12_000, Optional.of("{\"state\":\"ON\"}"))),
                Optional.of(new DeviceView.Traffic("mineqtt/world_7f3a/garage", 1_200, 0, Optional.empty())), 3, 1, 7, 2, 5);
        DeviceView view = DeviceView.of(POS, link, registration, true, state.json(), type.defaultSlug(DEVICE) + " | online", null,
                new DeviceView.Grants(true, true, false, false), new DeviceView.Ownership(DeviceView.Owner.NOT_VERIFIED, true), diagnostics);
        DeviceView copy = roundTrip(view);
        check(copy.equals(view), type + ": the view round-trips: " + copy);
        check(copy.diagnostics().equals(diagnostics) && copy.ownership().owner() == DeviceView.Owner.NOT_VERIFIED && copy.ownership().claimMod(),
                type + ": ownership and diagnostics survive, a redacted payload stays absent");
        check(copy.registration().topic(copy.slug()).equals(Optional.of("mineqtt/world_7f3a/" + type.defaultSlug(DEVICE))),
                type + ": the view gives the device's state topic");
        check(copy.stateJson().equals(state.json()) && copy.type().equals(type.id()) && copy.privateMode() && copy.legacyItems() == 0,
                type + ": state, type, private mode and the legacy item count survive");
        type.rawLink().ifPresent(spec -> check(copy.raw().orElseThrow().retains() == (spec.direction() == RawLinkSpec.Direction.OUT && spec.retainByDefault()),
                type + ": the view says whether the link retains"));
        check(!copy.controlsOnly(), type + ": a view of a player who may build is no controls-only view");

        // P2: a player who may not build gets what the Controls tab and the title show, and nothing else.
        DeviceView.Grants usesOnly = new DeviceView.Grants(true, false, false, false);
        DeviceView redacted = DeviceView.of(POS, link, registration, true, state.json(), type.defaultSlug(DEVICE) + " | online", "refused here", usesOnly,
                new DeviceView.Ownership(DeviceView.Owner.KNOWN, true), diagnostics).redacted();
        DeviceView redactedCopy = roundTrip(redacted);
        check(redactedCopy.equals(redacted) && redactedCopy.controlsOnly(), type + ": a controls-only view round-trips as one");
        check(redactedCopy.pos().equals(POS) && redactedCopy.type().equals(type.id()) && redactedCopy.name().equals(view.name())
                && redactedCopy.grants().equals(usesOnly) && redactedCopy.stateJson().equals(state.json()), type + ": it keeps position, type, name, grants and state");
        check(redactedCopy.slug().isEmpty() && redactedCopy.ownerName().isEmpty() && redactedCopy.expose().isEmpty()
                        && redactedCopy.registration().equals(DeviceView.Registration.NONE) && redactedCopy.raw().isEmpty() && !redactedCopy.privateMode()
                        && redactedCopy.status().isEmpty() && redactedCopy.problem().isEmpty() && redactedCopy.ownership().owner() == DeviceView.Owner.NOBODY
                        && !redactedCopy.ownership().claimMod() && redactedCopy.access() == AccessMode.DEFAULT && redactedCopy.legacyItems() == 0,
                type + ": it names no topic, owner, group, exposure or status: " + redactedCopy);
        check(redactedCopy.diagnostics().equals(DeviceView.Diagnostics.notRunning(null)), type + ": and carries no id, traffic or counts");
    }

    /**
     * The largest state a type can publish fits a view, as the server builds views of live state for open screens (M2-6):
     * every text field at its longest in four-byte characters, each escaped at worst as two six-byte unicode escapes, and every
     * JSON field (a location) given {@value #JSON_FIELD_ALLOWANCE} bytes beyond its default.
     */
    private static void worstCaseState(DeviceType type) {
        StateWriter state = new StateWriter();
        int allowance = 0;
        for (Field<?> field : type.fields()) {
            if (!field.access().readable()) continue;
            putDefault(state, field);
            if (field.kind() instanceof PortType.Text text) allowance += text.maxLength() * 12;
            if (field.kind() instanceof PortType.Json) allowance += JSON_FIELD_ALLOWANCE;
        }
        int bytes = ValueCodecs.utf8(state.json().toString()).length + allowance;
        check(bytes <= DeviceView.MAX_STATE_BYTES, type + ": its largest state of about " + bytes + " bytes fits a view's " + DeviceView.MAX_STATE_BYTES);
    }

    private static <T> void putDefault(StateWriter state, Field<T> field) {
        state.put(field, field.defaultValue());
    }

    private static DeviceView roundTrip(DeviceView view) {
        ByteBuf buf = Unpooled.buffer();
        DeviceView.STREAM_CODEC.encode(buf, view);
        check(buf.readableBytes() <= DeviceView.MAX_BYTES, "A view fits " + DeviceView.MAX_BYTES + " bytes: " + buf.readableBytes());
        DeviceView copy = DeviceView.STREAM_CODEC.decode(buf);
        check(buf.readableBytes() == 0, "A view is read to its end");
        return copy;
    }

    private static void viewCaps() {
        DeviceLink link = new DeviceLink(RedstoneInput.TYPE);
        String emoji = "😀".repeat(600);
        link.assign(DEVICE, emoji, "garage_input");
        link.setRaw(new RawLink("t".repeat(2000), RawLinkSpec.Direction.OUT, "TOGGLE", "p".repeat(5000), 0, true, null, false, "r".repeat(3000), RawTopics.VERSION, null, RawLink.Edges.BOTH));
        DeviceView.Traffic longMessage = new DeviceView.Traffic("€".repeat(600), 16 * 1024, 3_600_000, Optional.of(emoji));
        DeviceView capped = DeviceView.of(POS, link, new DeviceView.Registration("r".repeat(2000), false, Optional.of(emoji)), false,
                json("{\"powered\":true}"), "s".repeat(4000), "q".repeat(4000), DeviceView.Grants.NONE, new DeviceView.Ownership(DeviceView.Owner.NOBODY, false),
                new DeviceView.Diagnostics(Optional.of(DEVICE), DeviceView.Availability.FAULTED, "f".repeat(4000), Optional.of(longMessage), Optional.of(longMessage),
                        Long.MAX_VALUE, 0, 1, 0, 0));
        DeviceView.Traffic cut = capped.diagnostics().lastIn().orElseThrow();
        check(ValueCodecs.utf8(capped.diagnostics().detail()).length <= 1024 && ValueCodecs.utf8(cut.topic()).length <= LinkSetting.MAX_TOPIC_BYTES && cut.topic().endsWith("…")
                && ValueCodecs.utf8(cut.payload().orElseThrow()).length <= DeviceView.MAX_SHOWN_PAYLOAD_BYTES && cut.bytes() == 16 * 1024,
                "The fault, a message's topic and its shown payload are cut; its size stays whole");
        check(ValueCodecs.utf8(capped.name()).length <= 1024 && capped.name().endsWith("…") && capped.name().codePoints().noneMatch(c -> c >= 0xD800 && c <= 0xDFFF),
                "A long name is cut without splitting a surrogate pair");
        DeviceView.Raw raw = capped.raw().orElseThrow();
        check(ValueCodecs.utf8(raw.topic()).length <= 1024 && ValueCodecs.utf8(raw.off().orElseThrow()).length <= LinkSetting.MAX_PAYLOAD_BYTES
                && ValueCodecs.utf8(capped.status()).length <= 1536 && raw.channel().isEmpty(), "Topics, payloads and the status are cut");
        check(ValueCodecs.utf8(capped.registration().topicRoot()).length <= 1024 && ValueCodecs.utf8(capped.registration().group().orElseThrow()).length <= 1024,
                "The topic root and the group name are cut");
        check(DeviceView.Registration.NONE.topic("garage").isEmpty(), "A device that is not registered has no state topic");
        check(!raw.retains() && raw.retain().equals(Optional.of(true)), "A toggling payload is never retained, whatever the override says");
        check(roundTrip(capped).equals(capped), "A capped view round-trips");
        // Every text at its cap and the largest state still fit 32 KiB.
        String state = "{\"x\":\"" + "x".repeat(DeviceView.MAX_STATE_BYTES - 8) + "\"}";
        DeviceView largest = new DeviceView(POS, Identifier.fromNamespaceAndPath("mineqtt", "d".repeat(240)), capped.name(), "s".repeat(48), "o".repeat(256),
                AccessMode.PUBLIC, Optional.of(true), new DeviceView.Registration(capped.registration().topicRoot(), true, capped.registration().group()),
                DeviceView.Grants.NONE, state,
                Optional.of(new DeviceView.Raw(DeviceView.cap("€".repeat(500), 1024), Optional.of(DeviceView.cap("€".repeat(500), 1024)), RawLinkSpec.Direction.OUT, 1,
                        Optional.of(false), false, Optional.of("a".repeat(1024)), Optional.of("b".repeat(1024)), Optional.of(DeviceView.cap("€".repeat(600), 1536)), RawLink.Edges.BOTH)),
                true, DeviceView.cap("€".repeat(600), 1536), Optional.of(DeviceView.cap("€".repeat(600), 1536)), new DeviceView.Ownership(DeviceView.Owner.KNOWN, true),
                new DeviceView.Diagnostics(Optional.of(DEVICE), DeviceView.Availability.FAULTED, DeviceView.cap("€".repeat(600), 1024), Optional.of(cut), Optional.of(cut),
                        Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE), Integer.MAX_VALUE, false);
        check(roundTrip(largest).equals(largest), "The largest view round-trips within 32 KiB");
        DeviceView tooLarge = new DeviceView(POS, RedstoneInput.TYPE.id(), "n", "s", "", AccessMode.CLAIM, Optional.empty(), DeviceView.Registration.NONE, DeviceView.Grants.NONE,
                "{\"x\":\"" + "x".repeat(DeviceView.MAX_STATE_BYTES) + "\"}", Optional.empty(), false, "", Optional.empty(), OWNED, DeviceView.Diagnostics.notRunning(null), 0, false);
        try {
            DeviceView.STREAM_CODEC.encode(Unpooled.buffer(), tooLarge);
            throw new AssertionError("A view whose state exceeds " + DeviceView.MAX_STATE_BYTES + " bytes must not encode");
        } catch (EncoderException expected) {
            // The cap holds.
        }
        expectFailure(() -> DeviceView.of(POS, link, DeviceView.Registration.NONE, false, json(tooLarge.state()), "", null, DeviceView.Grants.NONE, OWNED,
                DeviceView.Diagnostics.notRunning(null)), "A view of a state over the cap");
        expectFailure(() -> new DeviceView(POS, RedstoneInput.TYPE.id(), "n", "s", "", AccessMode.CLAIM, Optional.empty(), DeviceView.Registration.NONE, DeviceView.Grants.NONE,
                "[1]", Optional.empty(), false, "", Optional.empty(), OWNED, DeviceView.Diagnostics.notRunning(null), 0, false), "A state that is no object");
        expectFailure(() -> new DeviceView(POS, RedstoneInput.TYPE.id(), "n", "s", "", AccessMode.CLAIM, Optional.empty(), DeviceView.Registration.NONE, DeviceView.Grants.NONE,
                "{}", Optional.empty(), false, "", Optional.empty(), OWNED, DeviceView.Diagnostics.notRunning(null), -1, false), "A negative item count");
        for (DeviceView.Grants beyondUse : List.of(new DeviceView.Grants(true, true, false, false), new DeviceView.Grants(false, false, true, false),
                new DeviceView.Grants(false, false, false, true))) {
            expectFailure(() -> new DeviceView(POS, RedstoneInput.TYPE.id(), "n", "", "", AccessMode.CLAIM, Optional.empty(), DeviceView.Registration.NONE, beyondUse,
                    "{}", Optional.empty(), false, "", Optional.empty(), OWNED, DeviceView.Diagnostics.notRunning(null), 0, true), "A controls-only view granting " + beyondUse);
        }
    }

    private static <T> JsonElement encodeDefault(Field<T> field) {
        return field.encode(field.defaultValue());
    }

    private static List<String> settingKeys(List<Setting<?>> settings) {
        return settings.stream().map(Setting::key).toList();
    }

    private static List<String> keys(List<LinkSetting<?>> settings) {
        return settings.stream().map(LinkSetting::key).toList();
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("mineqtt_check", path);
    }

    /**
     * M3-8: the Smart Lamp's twin adapter (M3 plan D5) over the captured light states (cap §9.1 to §9.5), folded from
     * statestream topics as the twin folds them and applied as the twin applies them ({@link DeviceType#twinCommand}, then
     * the lamp's merge): on at brightness 0 shows at 1, an hs light shows its rgb_color, an RGBWW light without kelvin
     * topics its colour, off keeps the colour (null colours are no black), unavailable holds whatever stale attributes
     * say, and a switch-like entity lights the lamp white. Its ports stay frozen ({@link #frozenPorts}).
     */
    private static void lampTwin() {
        TwinAdapter twin = SmartLamp.TYPE.twin().orElseThrow();
        check(twin == SmartLamp.TWIN && twin.primary().equals("light") && twin.sourceDomains().equals(List.of("light", "switch", "input_boolean", "fan", "binary_sensor"))
                && twin.controllable() && twin.targets().isEmpty() && twin.shapes().isEmpty(),
                "The lamp mirrors lights and switch-like entities and forwards to them (M3-9), with no targets");
        lampForward(twin);
        check(twin.mirroredKeys().containsAll(List.of("light", "state", "brightness", "color", "color_temp", "color_temp_kelvin")) && !twin.mirroredKeys().contains("effect"),
                "The source owns the light's keys; the effect stays the lamp's: " + twin.mirroredKeys());
        check(twin.sourceEnd("light.kitchen").equals(new Endpoint.HaEntityRef("light.kitchen", "light"))
                && twin.sourceEnd("binary_sensor.door").equals(new Endpoint.HaEntityRef("binary_sensor.door", "state")), "A light feeds its composite, anything else its state");
        check(twin.leaves("light.kitchen", Binding.Settings.DEFAULT).equals(List.of("state", "last_updated", "brightness", "rgb_color", "color_temp_kelvin", "color_mode")),
                "A light subscribes exactly D1 #8's leaves, never +: " + twin.leaves("light.kitchen", Binding.Settings.DEFAULT));
        check(twin.leaves("fan.attic", Binding.Settings.DEFAULT).equals(List.of("state", "last_updated")) && twin.leaves("person.steve", Binding.Settings.DEFAULT).isEmpty()
                && twin.leaves("sensor.outdoor", Binding.Settings.DEFAULT).isEmpty(), "A fan its state; nothing for an entity the lamp cannot mirror");

        ValueCodecs.LightState red = new ValueCodecs.LightState(true, 128, ValueCodecs.Rgb.ofPacked(0xFF0000), null);
        check(lamp("light.l91", red, "state", "on", "color_mode", "\"brightness\"", "brightness", "0")
                .equals(new ValueCodecs.LightState(true, 1, ValueCodecs.Rgb.ofPacked(0xFF0000), null)), "Cap §9.1: on at brightness 0 shows at brightness 1, the colour kept");
        check(lamp("light.l92", red, "state", "on", "color_mode", "\"hs\"", "brightness", "200", "rgb_color", "[255, 120, 10]", "color_temp_kelvin", "null")
                .equals(new ValueCodecs.LightState(true, 200, ValueCodecs.Rgb.ofPacked(0xFF780A), null)), "Cap §9.2: an hs light shows its rgb_color");
        check(lamp("light.l93", red, "state", "on", "color_mode", "\"rgbww\"", "brightness", "180", "rgb_color", "[93, 65, 40]")
                .equals(new ValueCodecs.LightState(true, 180, ValueCodecs.Rgb.ofPacked(0x5D4128), null)), "Cap §9.3: an RGBWW light without kelvin topics");
        ValueCodecs.LightState warm = lamp("light.l93k", red, "state", "on", "color_mode", "\"color_temp\"", "brightness", "255", "color_temp_kelvin", "2700",
                "rgb_color", "[255, 167, 87]");
        check(warm.on() && warm.brightness() == 255 && Integer.valueOf(2700).equals(warm.kelvin()), "A color_temp light gives its temperature: " + warm);
        check(lamp("light.l94", red, "state", "off", "brightness", "null", "rgb_color", "null", "color_mode", "null").equals(red.placed()),
                "Cap §9.4: off keeps brightness and colour; null colours are no black");
        check(lamp("light.l95", red, "state", "unavailable", "brightness", "255", "rgb_color", "[1, 2, 3]").equals(red),
                "Cap §9.5: unavailable holds, whatever the stale attributes say");
        check(lamp("switch.porch", red, "state", "on").equals(new ValueCodecs.LightState(true, 255, ValueCodecs.Rgb.WHITE, null))
                && lamp("binary_sensor.door", red, "state", "off").equals(red.placed()) && lamp("fan.attic", red, "state", "unknown").equals(red),
                "A switch-like entity lights the lamp white at full brightness and turns it off; unknown holds");
        Binding off = Binding.of(twin.sourceEnd("light.l96")).withSettings(Binding.Settings.DEFAULT.withUnavailable(Binding.Unavailable.OFF));
        EntityDocuments documents = new EntityDocuments("ha_statestream", EntityDocuments.Limits.DEFAULT);
        documents.accept("ha_statestream/light/l96/state", "unavailable".getBytes(StandardCharsets.UTF_8), false, 0);
        check(SmartLamp.TYPE.twinCommand(documents.document("light.l96").orElseThrow(), off).orElseThrow().equals(json("{\"state\":\"OFF\"}")),
                "twin.unavailable off turns the lamp off");
        check(SmartLamp.TYPE.twinCommand(documents.document("light.l96").orElseThrow(), off.withRefused("not allowed")).isEmpty(), "A refused binding reads nothing");
    }

    /**
     * M3-12: the Redstone Output's twin adapter (M3 plan D5, D7) read from entities folded as the twin folds them: on and
     * off as 15 and 0 (inverted 0 and 15), transitional states holding (cap §9.8 {@code unlocking}), numbers over the
     * entity's own range or {@code twin.min}/{@code twin.max} ({@code 42.5} of 0..100 is 6), a light's brightness through
     * {@code twin.path}, a climate's current temperature, and an option list's index; and what it asks back, mapped the
     * same way ({@code set_value}, {@code set_percentage}, a brightness, {@code select_option}).
     */
    private static void outputTwin() {
        TwinAdapter twin = RedstoneOutput.TYPE.twin().orElseThrow();
        check(twin == RedstoneOutput.TWIN && twin.primary().equals("power") && twin.mirroredKeys().equals(Set.of("power", "state"))
                        && twin.controllable() && twin.targets().isEmpty()
                        && twin.shapes().equals(Set.of(TwinAdapter.Shape.INVERT, TwinAdapter.Shape.RANGE, TwinAdapter.Shape.PATH)),
                "The output mirrors into its level and switch, forwards, and has the invert, range and path rows; its mode is its own");
        check(twin.sourceDomains().equals(RedstoneOutput.TWIN_DOMAINS) && !twin.sourceDomains().contains("person") && twin.sourceDomains().contains("binary_sensor"),
                "The output mirrors on/off, numeric and option entities: " + twin.sourceDomains());
        Binding.Settings brightness = Binding.Settings.DEFAULT.withPath(ValuePath.parse("attributes.brightness"));
        check(twin.leaves("switch.porch", Binding.Settings.DEFAULT).equals(List.of("state", "last_updated"))
                        && twin.leaves("input_number.level", Binding.Settings.DEFAULT).equals(List.of("state", "last_updated", "min", "max", "step"))
                        && twin.leaves("light.porch", brightness).equals(List.of("state", "last_updated", "brightness"))
                        && twin.leaves("climate.hall", Binding.Settings.DEFAULT).equals(List.of("state", "last_updated", "current_temperature"))
                        && twin.leaves("input_select.mode", Binding.Settings.DEFAULT).equals(List.of("state", "last_updated", "options")),
                "Each source subscribes its state, its timestamp and exactly the attributes it reads (D1 #8)");

        Binding.Settings inverted = Binding.Settings.DEFAULT.withInvert(true);
        check(outputLevel("switch.porch", Binding.Settings.DEFAULT, "state", "on") == 15 && outputLevel("switch.porch", Binding.Settings.DEFAULT, "state", "off") == 0
                        && outputLevel("switch.porch", inverted, "state", "on") == 0 && outputLevel("binary_sensor.door", inverted, "state", "off") == 15,
                "On is 15 and off 0; twin.invert swaps them");
        check(outputLevel("lock.front", Binding.Settings.DEFAULT, "state", "unlocked") == 15 && outputLevel("lock.front", Binding.Settings.DEFAULT, "state", "unlocking") == -1
                        && outputLevel("cover.garage", Binding.Settings.DEFAULT, "state", "opening") == -1,
                "Cap §9.8: unlocked is on (D8's lock BOOL); unlocking and opening hold");
        check(outputLevel("input_number.m3_level", Binding.Settings.DEFAULT, "state", "42.5", "min", "0.0", "max", "100.0") == 6
                        && outputLevel("input_number.m3_level", Binding.Settings.DEFAULT, "state", "75", "min", "0.0", "max", "100.0") == 11,
                "An input_number over its own range: 42.5 of 0..100 is 6, 75 is 11");
        Binding.Settings range = Binding.Settings.DEFAULT.withMin(new BigDecimal("0")).withMax(new BigDecimal("40"));
        check(outputLevel("sensor.outdoor", range, "state", "21.4") == 8 && outputLevel("sensor.outdoor", Binding.Settings.DEFAULT, "state", "7") == 7
                        && outputLevel("sensor.outdoor", range, "state", "sunny") == -1 && outputLevel("sensor.outdoor", range, "state", "NaN") == -1,
                "A sensor over twin.min/twin.max, else 0..15; text and NaN hold");
        check(outputLevel("light.porch", brightness, "state", "on", "brightness", "128") == 8 && outputLevel("fan.attic", Binding.Settings.DEFAULT.withPath(ValuePath.parse("attributes.percentage")), "state", "on", "percentage", "100") == 15,
                "twin.path reads a light's brightness over 0..255 and a fan's percentage over 0..100");
        check(outputLevel("light.porch", brightness, "state", "off", "brightness", "null") == 0
                        && outputLevel("light.porch", brightness.withInvert(true), "state", "off", "brightness", "null") == 15
                        && outputLevel("light.porch", brightness, "state", "on", "brightness", "null") == -1,
                "Cap §9.4: an off light's null brightness is level 0 (inverted 15), never the level from before; null while on holds");
        check(outputLevel("climate.hall", range, "state", "heat", "current_temperature", "20") == 8, "A climate's current temperature over the range");
        check(outputLevel("input_select.m3_mode", Binding.Settings.DEFAULT, "state", "Turbo Mode", "options", "[\"Off\", \"Eco\", \"Turbo Mode\"]") == 2,
                "An option list emits the index of its option (D7)");
        check(outputLevel("switch.porch", Binding.Settings.DEFAULT, "state", "unavailable") == -1
                        && outputLevel("switch.porch", Binding.Settings.DEFAULT.withUnavailable(Binding.Unavailable.OFF), "state", "unavailable") == 0,
                "Unavailable holds; with twin.unavailable off the output goes to 0");

        Binding.Settings defaults = Binding.Settings.DEFAULT;
        check(outputCall("switch.porch", defaults, power(0), HaCalls.Mirror.live("on")).equals("switch.turn_off {}")
                        && outputCall("switch.porch", inverted, power(15), HaCalls.Mirror.live("on")).equals("switch.turn_off {}")
                        && outputCall("switch.porch", defaults, state("TOGGLE"), HaCalls.Mirror.retained("on")).equals("switch.toggle {}")
                        && outputCall("cover.garage", defaults, power(15), HaCalls.Mirror.live("closed")).equals("cover.open_cover {}"),
                "Switching: a level above 0 is on, swapped when inverted; a retained toggle is <domain>.toggle; a cover opens");
        check(outputRefusal("switch.porch", defaults, power(15), HaCalls.Mirror.live("on")).equals("skip")
                        && outputRefusal("binary_sensor.door", defaults, power(15), HaCalls.Mirror.live("off")).equals(HaCalls.Reason.READ_ONLY.name()),
                "LIVE dedupe; a binary_sensor never forwards (it has no in-port)");
        HaCalls.Mirror level = mirror("input_number.m3_level", "state", "42.5", "min", "0.0", "max", "100.0", "step", "0.5");
        check(outputCall("input_number.m3_level", defaults, power(6), level).equals("input_number.set_value {\"value\":40}")
                        && outputCall("input_number.m3_level", defaults, state("ON"), level).equals("input_number.set_value {\"value\":100}")
                        && outputCall("input_number.m3_level", defaults, state("TOGGLE"), level).equals("input_number.set_value {\"value\":0}")
                        && outputCall("input_number.m3_level", inverted, power(15), level).equals("input_number.set_value {\"value\":0}"),
                "Level mode: set_value of the value the level stands for in the entity's range; ON is the top, a toggle the other end");
        check(outputRefusal("input_number.m3_level", defaults, power(6), HaCalls.Mirror.NONE).equals(HaCalls.Reason.NOT_LIVE.name())
                        && outputCall("input_number.m3_level", range, power(6), HaCalls.Mirror.NONE).equals("input_number.set_value {\"value\":16}"),
                "Without the entity's document its own range is unknown; twin.min and twin.max make it known");
        check(outputCall("light.porch", brightness, power(8), mirror("light.porch", "state", "on", "brightness", "50")).equals("light.turn_on {\"brightness\":136}")
                        && outputCall("light.porch", brightness, power(0), mirror("light.porch", "state", "on", "brightness", "50")).equals("light.turn_off {}")
                        && outputCall("fan.attic", Binding.Settings.DEFAULT.withPath(ValuePath.parse("attributes.percentage")), power(15), mirror("fan.attic", "state", "on"))
                        .equals("fan.set_percentage {\"percentage\":100}"),
                "An analog light sets its brightness, 0 turns it off; a fan its percentage");
        check(outputCall("light.porch", brightness, state("TOGGLE"), mirror("light.porch", "state", "off", "brightness", "null")).equals("light.turn_on {\"brightness\":255}")
                        && outputCall("light.porch", brightness, state("TOGGLE"), mirror("light.porch", "state", "on", "brightness", "128")).equals("light.turn_off {}"),
                "A toggle of an off light (null brightness) turns it on fully, of an on one off");
        HaCalls.Mirror modes = mirror("input_select.m3_mode", "state", "Off", "options", "[\"Off\", \"Eco\", \"Turbo Mode\"]");
        check(outputCall("input_select.m3_mode", defaults, power(1), modes).equals("input_select.select_option {\"option\":\"Eco\"}")
                        && outputCall("input_select.m3_mode", defaults, power(15), modes).equals("input_select.select_option {\"option\":\"Turbo Mode\"}")
                        && outputRefusal("input_select.m3_mode", defaults, state("TOGGLE"), modes).equals(HaCalls.Reason.BAD_VALUE.name()),
                "An option list selects the option at the level's index (past the end the last); it cannot be toggled");
        check(outputRefusal("sensor.outdoor", range, power(3), mirror("sensor.outdoor", "state", "4")).equals(HaCalls.Reason.READ_ONLY.name())
                        && outputRefusal("climate.hall", range, power(3), mirror("climate.hall", "state", "heat", "current_temperature", "20")).equals(HaCalls.Reason.READ_ONLY.name()),
                "A sensor and a climate's current temperature cannot be set back");
        check(twin.forward(power(3), HaCalls.Mirror.live("on"), Binding.of(twin.sourceEnd("switch.porch"))).isEmpty(), "Without control nothing is asked");
    }

    /**
     * M3-12: the Smart Plug's twin adapter (M3 plan D5, D11): on and off from a switch, an input_boolean, a light or a fan;
     * a right-click's toggle is the explicit opposite of a LIVE state, {@code toggle} of a retained one, and refused while
     * nothing is known.
     */
    private static void plugTwin() {
        TwinAdapter twin = SmartPlug.TYPE.twin().orElseThrow();
        check(twin == SmartPlug.TWIN && twin.primary().equals("power") && twin.mirroredKeys().equals(Set.of("power", "state"))
                        && twin.sourceDomains().equals(List.of("switch", "input_boolean", "light", "fan")) && twin.controllable() && twin.shapes().isEmpty(),
                "The plug mirrors on/off entities into its power and switch, and forwards, without rows");
        check(plugPower("switch.heater", "on") == 15 && plugPower("light.desk", "off") == 0 && plugPower("fan.attic", "unknown") == -1,
                "On is 15, off 0, unknown holds");
        Binding bound = Binding.of(twin.sourceEnd("switch.heater")).withControl(true);
        check(twin.calls(state("TOGGLE"), HaCalls.Mirror.live("on"), bound).toString().equals("[switch.turn_off {}]")
                        && twin.calls(state("TOGGLE"), HaCalls.Mirror.retained("on"), bound).toString().equals("[switch.toggle {}]")
                        && twin.calls(power(15), HaCalls.Mirror.live("off"), bound).toString().equals("[switch.turn_on {}]"),
                "A right-click toggles from a LIVE state explicitly, from a retained one with toggle; a level above 0 is on");
        check(twin.forward(state("TOGGLE"), HaCalls.Mirror.NONE, bound).getFirst() instanceof HaCalls.Refused refused && refused.reason() == HaCalls.Reason.NOT_LIVE
                        && twin.forward(state("ON"), HaCalls.Mirror.live("on"), bound).getFirst() instanceof HaCalls.Skip,
                "A toggle with nothing known is refused locally; ON while LIVE on is not sent");
        check(twin.calls(state("TOGGLE"), HaCalls.Mirror.live("off"), Binding.of(twin.sourceEnd("light.desk")).withControl(true)).toString().equals("[light.turn_on {}]"),
                "A plug mirroring a light switches it");
    }

    /**
     * M3-14, D5, D16: the Redstone Input and the Wall Switch as Home Assistant targets. Both adapters have targets only (no
     * source, primary or mirrored key, never controllable); the Input's {@code powered} follows the edge rules (rising:
     * turn_on, open_cover, scene/script turn_on, press; falling: turn_off, close_cover, nothing for scenes, scripts and
     * buttons; LIVE dedupe), the Wall Switch's {@code left}/{@code right} a press (activate, press, or D11's toggle; double
     * presses and holds unbound). A target sits on an out-port, never on an action.
     */
    private static void targetTwins() {
        TwinAdapter input = RedstoneInput.TYPE.twin().orElseThrow();
        check(input == RedstoneInput.TWIN && input.primary() == null && input.mirroredKeys().isEmpty() && !input.hasSource() && !input.controllable()
                        && input.targets().equals(Map.of("target", "powered")) && input.targetDomains().equals(RedstoneInput.TARGET_DOMAINS),
                "The input has one target on powered and mirrors nothing");
        check(inputCall("input_boolean.m3_flag", true, HaCalls.Mirror.NONE).equals("input_boolean.turn_on {}")
                        && inputCall("input_boolean.m3_flag", false, HaCalls.Mirror.NONE).equals("input_boolean.turn_off {}")
                        && inputCall("cover.garage", true, HaCalls.Mirror.NONE).equals("cover.open_cover {}")
                        && inputCall("cover.garage", false, HaCalls.Mirror.live("open")).equals("cover.close_cover {}")
                        && inputCall("scene.m3_scene", true, HaCalls.Mirror.NONE).equals("scene.turn_on {}")
                        && inputCall("script.m3_script", true, HaCalls.Mirror.NONE).equals("script.turn_on {}")
                        && inputCall("button.m3_bell", true, HaCalls.Mirror.NONE).equals("button.press {}"),
                "A rising edge switches on, opens, activates or presses; a falling edge switches off or closes");
        check(inputOutcome("scene.m3_scene", new JsonPrimitive(false), HaCalls.Mirror.NONE) instanceof HaCalls.Skip
                        && inputOutcome("input_button.m3_bell", new JsonPrimitive(false), HaCalls.Mirror.NONE) instanceof HaCalls.Skip
                        && inputOutcome("input_boolean.m3_flag", new JsonPrimitive(true), HaCalls.Mirror.live("on")) instanceof HaCalls.Skip,
                "A falling edge asks nothing of a scene or a button; an edge the LIVE target already shows asks nothing (dedupe)");
        check(inputOutcome("input_boolean.m3_flag", new JsonPrimitive("maybe"), HaCalls.Mirror.NONE) instanceof HaCalls.Refused bad && bad.reason() == HaCalls.Reason.BAD_VALUE
                        && input.targetCall("signal", TwinAdapter.targetEnd("switch.porch"), new JsonPrimitive(true), HaCalls.Mirror.NONE) instanceof HaCalls.Refused port
                        && port.reason() == HaCalls.Reason.NO_SUCH_PORT
                        && input.targetCall("powered", TwinAdapter.targetEnd("lock.front"), new JsonPrimitive(true), HaCalls.Mirror.NONE) instanceof HaCalls.Refused,
                "Not an edge, an out-port that is no target and a domain the input does not switch are refused");
        check(input.targetProblem("target", "lock.front").isPresent() && input.targetProblem("target", "sensor.outdoor").isPresent()
                        && input.targetProblem("target", "light.m3_lamp").isEmpty() && input.sourceProblem("light.m3_lamp").isPresent(),
                "The input switches lights, switches, scenes, scripts and buttons, never locks or sensors, and mirrors nothing");

        TwinAdapter wall = WallSwitch.TYPE.twin().orElseThrow();
        check(wall == WallSwitch.TWIN && wall.primary() == null && wall.mirroredKeys().isEmpty() && !wall.hasSource()
                        && wall.targets().equals(Map.of("left", "left", "right", "right")) && wall.targetDomains().equals(WallSwitch.TARGET_DOMAINS),
                "Each button has a target on its bus-only event, never on its press_left/press_right action (D16)");
        JsonObject press = WallSwitch.buttonEvent(WallSwitch.Gesture.PRESS, WallSwitch.Button.LEFT);
        check(wallCall("scene.m3_scene", press, HaCalls.Mirror.NONE).equals("scene.turn_on {}") && wallCall("script.m3_script", press, HaCalls.Mirror.NONE).equals("script.turn_on {}")
                        && wallCall("input_button.m3_bell", press, HaCalls.Mirror.NONE).equals("input_button.press {}")
                        && wallCall("light.m3_lamp", press, HaCalls.Mirror.live("off")).equals("light.turn_on {}")
                        && wallCall("switch.m3_fan", press, HaCalls.Mirror.retained("on")).equals("switch.toggle {}"),
                "A press activates a scene or script, presses a button, toggles a switch-like entity as a right-click does (D11)");
        check(wall.targetCall("left", TwinAdapter.targetEnd("switch.m3_fan"), press, HaCalls.Mirror.NONE) instanceof HaCalls.Refused unknown && unknown.reason() == HaCalls.Reason.NOT_LIVE,
                "A toggle of an entity nothing is known of is refused");
        for (WallSwitch.Gesture gesture : List.of(WallSwitch.Gesture.DOUBLE_PRESS, WallSwitch.Gesture.HOLD)) {
            check(wall.targetCall("right", TwinAdapter.targetEnd("scene.m3_scene"), WallSwitch.buttonEvent(gesture, WallSwitch.Button.RIGHT), HaCalls.Mirror.NONE)
                    instanceof HaCalls.Skip, "A " + gesture + " switches nothing in M3");
        }
        check(wall.targetCall("left", TwinAdapter.targetEnd("scene.m3_scene"), new JsonPrimitive("PRESS"), HaCalls.Mirror.NONE) instanceof HaCalls.Refused
                        && wall.targetProblem("left", "cover.garage").isPresent() && wall.targetProblem("middle", "scene.x").isPresent(),
                "A value that is no button event, a cover and an unknown button are refused");

        TwinRules.Rights all = new TwinRules.Rights(true, true, EntityGlobs.ALL, Optional.empty());
        TwinRules.Edited left = TwinRules.edit(wall, null, new DeviceConfig.TwinTarget("left", "scene.m3_scene"), all, all, false);
        check(left.refusal() == null && left.binding().targets().equals(Map.of("left", new Endpoint.HaEntityRef("scene.m3_scene", "activate"))) && !left.binding().bound(),
                "twin.left binds the left event to the scene's activate in-port; the switch is not mirrored: " + left);
        Binding both = TwinRules.edit(wall, left.binding(), new DeviceConfig.TwinTarget("right", "script.m3_script"), all, all, false).binding();
        check(both.targets().keySet().equals(Set.of("left", "right")), "Left and right are bound separately");
        TwinRules.Edited oneLeft = TwinRules.edit(wall, both, new DeviceConfig.TwinTarget("left", ""), TwinRules.Rights.NONE, all, false);
        check(oneLeft.refusal() == null && oneLeft.binding().targets().keySet().equals(Set.of("right")), "Removing one target keeps the other");
        TwinRules.Edited none = TwinRules.edit(wall, oneLeft.binding(), new DeviceConfig.TwinTarget("right", ""), TwinRules.Rights.NONE, all, false);
        check(none.refusal() == null && none.binding() == null, "Removing the last target of a device without a source unbinds it");
        check(TwinRules.edit(wall, null, new DeviceConfig.TwinSource("light.m3_lamp"), all, all, false).refusal() != null, "A Wall Switch mirrors nothing");
        check(TwinRules.landingPending(both, wall, json("{\"press_left\":\"PRESS\"}")).equals(json("{\"press_left\":\"PRESS\"}"))
                        && !TwinRules.seedSkipped(both, wall, json("{\"press_left\":\"PRESS\"}")),
                "Targets alone divert no command: a queued or seeded press lands as always");
        failsToBuild(DeviceType.builder(id("target_on_action")).model("T").action(Action.press("press_left"))
                        .twin(TwinAdapter.targetsOnly().target("left", "press_left").targetDomains("scene").build()),
                "no out-port", "A target on an action (an in-port) refused at type build (D16)");
        boolean mirrorsRefused = false;
        try {
            TwinAdapter.targetsOnly().target("left", "left").targetDomains("scene").mirrors("state").build();
        } catch (IllegalStateException expected) {
            mirrorsRefused = expected.getMessage().contains("mirrors something without a primary port");
        }
        check(mirrorsRefused, "A targets-only adapter that mirrors a key is refused");
    }

    private static String inputCall(String entity, boolean rising, HaCalls.Mirror mirror) {
        return switch (inputOutcome(entity, new JsonPrimitive(rising), mirror)) {
            case HaCalls.Send send -> send.call().toString();
            case HaCalls.Outcome other -> "no call: " + other;
        };
    }

    private static HaCalls.Outcome inputOutcome(String entity, JsonElement value, HaCalls.Mirror mirror) {
        return RedstoneInput.TWIN.targetCall("powered", TwinAdapter.targetEnd(entity), value, mirror);
    }

    private static String wallCall(String entity, JsonObject event, HaCalls.Mirror mirror) {
        return switch (WallSwitch.TWIN.targetCall("left", TwinAdapter.targetEnd(entity), event, mirror)) {
            case HaCalls.Send send -> send.call().toString();
            case HaCalls.Outcome other -> "no call: " + other;
        };
    }

    // The level an output shows after the twin applies an entity folded from these leaves; -1 when it holds.
    private static int outputLevel(String entity, Binding.Settings settings, String... leaves) {
        Binding binding = Binding.of(RedstoneOutput.TWIN.sourceEnd(entity)).withSettings(settings);
        return RedstoneOutput.TYPE.twinCommand(document(entity, leaves), binding).map(command -> command.get("power").getAsInt()).orElse(-1);
    }

    private static int plugPower(String entity, String state) {
        Binding binding = Binding.of(SmartPlug.TWIN.sourceEnd(entity));
        return SmartPlug.TYPE.twinCommand(document(entity, "state", state), binding).map(command -> command.get("power").getAsInt()).orElse(-1);
    }

    // The one call a command to an output bound with control asks for, as "action data".
    private static String outputCall(String entity, Binding.Settings settings, JsonObject command, HaCalls.Mirror mirror) {
        List<Call> calls = RedstoneOutput.TWIN.calls(command, mirror, Binding.of(RedstoneOutput.TWIN.sourceEnd(entity)).withSettings(settings).withControl(true));
        check(calls.size() == 1, "One call for " + command + " to " + entity + ": " + calls);
        return calls.getFirst().toString();
    }

    // Why a command to an output bound with control asks for nothing: the refusal's reason, or "skip".
    private static String outputRefusal(String entity, Binding.Settings settings, JsonObject command, HaCalls.Mirror mirror) {
        List<HaCalls.Outcome> outcomes = RedstoneOutput.TWIN.forward(command, mirror, Binding.of(RedstoneOutput.TWIN.sourceEnd(entity)).withSettings(settings).withControl(true));
        check(outcomes.size() == 1, "One outcome for " + command + " to " + entity + ": " + outcomes);
        return switch (outcomes.getFirst()) {
            case HaCalls.Refused refused -> refused.reason().name();
            case HaCalls.Skip ignored -> "skip";
            case HaCalls.Send send -> "sent " + send.call();
        };
    }

    // What the twin knows of an entity folded from these leaves, LIVE.
    private static HaCalls.Mirror mirror(String entity, String... leaves) {
        EntityDocument document = document(entity, leaves);
        return new HaCalls.Mirror(HaCalls.Mirror.Freshness.LIVE, document.state().wire(), document.toJson());
    }

    private static EntityDocument document(String entity, String... leaves) {
        EntityDocuments documents = new EntityDocuments("ha_statestream", EntityDocuments.Limits.DEFAULT);
        String topic = "ha_statestream/" + entity.replace('.', '/') + "/";
        for (int i = 0; i < leaves.length; i += 2) {
            documents.accept(topic + leaves[i], leaves[i + 1].getBytes(StandardCharsets.UTF_8), false, i);
        }
        return documents.document(entity).orElseThrow();
    }

    private static JsonObject power(int level) {
        JsonObject command = new JsonObject();
        command.addProperty("power", level);
        return command;
    }

    private static JsonObject state(String value) {
        JsonObject command = new JsonObject();
        command.addProperty("state", value);
        return command;
    }

    /**
     * M3-15: the Display Panel (M3 plan D18). Its declaration (settings, the 255 of a Home Assistant text, read-only
     * mirroring of any readable domain), the comparator rule for unbound and mirrored panels, its message codec, Raw
     * Link grammar, saved state, unloaded preview and what clients draw.
     */
    private static void displayPanel() {
        DeviceType type = DisplayPanel.TYPE;
        check(settingKeys(type.settings()).equals(List.of("color", "glow", "size")) && type.unloadedPolicy() == UnloadedPolicy.QUEUE && !type.groupable()
                && type.use().isEmpty() && type.actions().isEmpty() && type.events().isEmpty(),
                "A panel's settings are colour, glow and size; it queues while unloaded, joins no group and opens its screen on a right-click");
        check(DisplayPanel.MESSAGE.kind().equals(PortType.text(255)) && DisplayPanel.MESSAGE.access() == Access.READ_WRITE
                && DisplayPanel.LEVEL.access() == Access.READ && DisplayPanel.COLOR.defaultValue() == DyeColor.CYAN && DisplayPanel.GLOW.defaultValue()
                && DisplayPanel.SIZE.defaultValue() == DisplayPanel.Size.AUTO, "message is text of at most 255, level read-only; cyan, glowing, fitted by default");
        check(type.entities().stream().map(HaEntity::key).toList().equals(List.of("message", "level", "location"))
                && type.entities().getFirst() instanceof HaEntity.Text text && text.maxLength() == 255, "Its own Home Assistant text, the level and the location");

        // The message: sanitised, line breaks kept, capped at 255 code points.
        check(DisplayPanel.MESSAGE.parse(new JsonPrimitive("§aWasher\tdone\r\nnow")).orElseThrow().equals("Washer done\nnow"),
                "Formatting codes go, a tab is a space, a line break stays one");
        check(DisplayPanel.MESSAGE.parse(new JsonPrimitive("😀".repeat(300))).orElseThrow().codePointCount(0, 510) == 255
                && DisplayPanel.MESSAGE.parse(new JsonPrimitive(7)).orElseThrow().equals("7") && DisplayPanel.MESSAGE.parse(json("{\"a\":1}")).isEmpty(),
                "255 code points at most; a number is its text; an object is no message");

        // The comparator rule: a gauge for numbers, on/off for words, presence for text.
        Map<String, Integer> unbound = new LinkedHashMap<>();
        unbound.put("", 0);
        unbound.put("  ", 0);
        unbound.put("Unavailable", 0);
        unbound.put("unknown", 0);
        unbound.put("7", 7);
        unbound.put("0", 0);
        unbound.put("1", 1);
        unbound.put("7.4", 7);
        unbound.put("7.5", 8);
        unbound.put("-3", 0);
        unbound.put("99", 15);
        unbound.put("on", 15);
        unbound.put("OFF", 0);
        unbound.put("open", 15);
        unbound.put("closed", 0);
        unbound.put("true", 15);
        unbound.put("Washer done!", 15);
        unbound.put("21.4 °C", 15);
        unbound.put("NaN", 15);
        unbound.forEach((message, level) -> check(DisplayPanel.level(message) == level, "Unbound '" + message + "' gives " + level + ", not " + DisplayPanel.level(message)));
        JsonObject number = json("{\"state\":\"42.5\",\"attributes\":{\"min\":0,\"max\":100}}");
        check(DisplayPanel.comparator(new JsonPrimitive("42.5"), number, null, null) == 6
                && DisplayPanel.comparator(new JsonPrimitive("42.5"), number, new BigDecimal("40"), new BigDecimal("45")) == 8
                && DisplayPanel.comparator(new JsonPrimitive("42.5"), number, new BigDecimal("50"), new BigDecimal("40")) == 11
                && DisplayPanel.comparator(new JsonPrimitive(12.5), JsonNull.INSTANCE, null, null) == 13,
                "A number maps over the entity's range, over twin.min/twin.max first (reversed descends), else 0..15");
        JsonObject select = json("{\"state\":\"Turbo\",\"attributes\":{\"options\":[\"Off\",\"Eco\",\"Turbo\"]}}");
        check(DisplayPanel.comparator(new JsonPrimitive("Turbo"), select, null, null) == 2 && DisplayPanel.comparator(new JsonPrimitive("Off"), select, null, null) == 0
                && DisplayPanel.comparator(new JsonPrimitive("Boost"), select, null, null) == 15, "An option gives its index; Off is a word, so 0; other text 15");
        check(DisplayPanel.comparator(new JsonPrimitive(true), JsonNull.INSTANCE, null, null) == 15 && DisplayPanel.comparator(JsonNull.INSTANCE, JsonNull.INSTANCE, null, null) == 0
                && DisplayPanel.comparator(json("{\"x\":1}"), JsonNull.INSTANCE, null, null) == 0, "A boolean is on or off; no value or an object is 0");

        // Saved state, commands and the twin's extras.
        DisplayPanel.Panel panel = new DisplayPanel.Panel("Washer done!", DyeColor.RED, false, DisplayPanel.Size.SMALL, 15);
        Tag saved = DisplayPanel.Panel.CODEC.encodeStart(NbtOps.INSTANCE, panel).getOrThrow();
        check(DisplayPanel.Panel.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow().equals(panel), "The saved state round-trips");
        check(DisplayPanel.Panel.CODEC.parse(NbtOps.INSTANCE, new CompoundTag()).getOrThrow().equals(DisplayPanel.Panel.DEFAULT), "Missing values keep their defaults");
        CompoundTag hostile = new CompoundTag();
        hostile.putString("message", "x".repeat(5000));
        hostile.putInt("level", 99);
        hostile.putString("color", "chartreuse");
        DisplayPanel.Panel bounded = DisplayPanel.Panel.CODEC.parse(NbtOps.INSTANCE, hostile).getOrThrow();
        check(bounded.message().length() == 255 && bounded.level() == 15 && bounded.color() == DyeColor.CYAN, "Saved data is bounded: " + bounded.level());
        DisplayPanel.Panel shown = DisplayPanel.Panel.DEFAULT.apply(command("{\"message\":\"7\"}"), false).orElseThrow();
        check(shown.message().equals("7") && shown.level() == 7, "A message sets the level it gives");
        check(DisplayPanel.Panel.DEFAULT.apply(command("{\"message\":\"7\",\"twin\":{\"level\":3}}"), false).orElseThrow().level() == 7
                && DisplayPanel.Panel.DEFAULT.apply(command("{\"message\":\"7\",\"twin\":{\"level\":3}}"), true).orElseThrow().level() == 3
                && DisplayPanel.Panel.DEFAULT.apply(command("{\"message\":\"7\",\"twin\":{\"level\":\"x\"}}"), true).orElseThrow().level() == 7,
                "Only the panel's own twin apply brings its level; a malformed one is ignored");
        DisplayPanel.Panel styled = shown.apply(command("{\"color\":\"LIME\",\"glow\":\"off\",\"size\":\"large\"}"), false).orElseThrow();
        check(styled.equals(new DisplayPanel.Panel("7", DyeColor.LIME, false, DisplayPanel.Size.LARGE, 7)), "Settings keep the message and its level");
        check(shown.apply(command("{\"level\":3}"), false).isEmpty() && shown.apply(command("{\"state\":\"ON\"}"), false).isEmpty(), "The level is read-only; other keys are no panel command");
        JsonObject previewed = type.preview(json("{\"message\":\"\",\"level\":0,\"location\":{\"dimension\":\"minecraft:overworld\",\"x\":1,\"y\":2,\"z\":3}}"),
                json("{\"message\":\"12\"}"));
        check(previewed.get("message").getAsString().equals("12") && previewed.get("level").getAsInt() == 12 && previewed.has("location"),
                "An unloaded panel publishes the queued message with its level: " + previewed);

        // The Raw Link: text in, its retained value shown, no custom payloads.
        RawLinkSpec spec = type.rawLink().orElseThrow();
        check(spec.direction() == RawLinkSpec.Direction.IN && spec.acceptRetained() && !spec.customPayloads(), "A panel follows a topic's retained text");
        check(spec.grammar().command("  Hello\nworld  ", ValueCodecs.CustomPayloads.NONE).orElseThrow().equals(json("{\"message\":\"Hello\\nworld\"}"))
                && spec.grammar().command(" ", ValueCodecs.CustomPayloads.NONE).orElseThrow().equals(json("{\"message\":\"\"}"))
                && spec.grammar().command("", ValueCodecs.CustomPayloads.NONE).isEmpty()
                && spec.grammar().command("€".repeat(400), ValueCodecs.CustomPayloads.NONE).orElseThrow().get("message").getAsString().length() == 255,
                "Raw text is the message (blank clears it, an empty payload commands nothing), capped at 255");
        check(keys(type.linkSettings()).equals(List.of("name", "expose", "access", "raw.channel", "raw.private", "raw.topic",
                "twin.entity", "twin.device", "twin.preset", "twin.path", "twin.min", "twin.max", "twin.decimals", "twin.unavailable")), "Panel link settings: " + keys(type.linkSettings()));

        // What clients draw: finished lines, the title only while mirrored, a point in time as itself.
        DisplayPanel.Render plain = DisplayPanel.Render.of(new DisplayPanel.Panel("The front door is open", DyeColor.CYAN, true, DisplayPanel.Size.AUTO, 15), null, false);
        check(plain.title() == null && plain.lines().equals(List.of("The front", "door is open")) && plain.layout() == art.rehra.mineqtt.twin.core.DisplayText.Size.MEDIUM
                && plain.at().isEmpty(), "An unbound panel draws its message fitted: " + plain);
        DisplayPanel.Render titled = DisplayPanel.Render.of(new DisplayPanel.Panel("12.5 °C", DyeColor.CYAN, true, DisplayPanel.Size.AUTO, 13), "Outdoor", false);
        check("Outdoor".equals(titled.title()) && titled.lines().equals(List.of("12.5 °C")), "A mirrored panel draws its name over the value");
        DisplayPanel.Render time = DisplayPanel.Render.of(new DisplayPanel.Panel("2026-10-11T07:30:00+00:00", DyeColor.CYAN, true, DisplayPanel.Size.LARGE, 15), "Alarm", true);
        check(time.at().equals(java.util.OptionalLong.of(java.time.Instant.parse("2026-10-11T07:30:00Z").toEpochMilli())) && time.lines().isEmpty()
                && "Alarm".equals(time.title()) && time.size() == DisplayPanel.Size.LARGE && time.waiting(), "A point in time travels as epoch millis");
        check(DisplayPanel.timestamp("07:30").isEmpty() && DisplayPanel.timestamp("tomorrow").isEmpty() && DisplayPanel.timestamp("a\n2026-10-11T07:30:00+00:00").isEmpty(),
                "A time of day or text is no point in time");
        // A message anyone may set (screen, /set, Raw Link, HA text) with a year epoch milliseconds cannot hold renders as text.
        DisplayPanel.Render extreme = DisplayPanel.Render.of(new DisplayPanel.Panel("+999999999-01-01T00:00:00Z", DyeColor.CYAN, true, DisplayPanel.Size.AUTO, 15), null, false);
        check(DisplayPanel.timestamp("+999999999-01-01T00:00:00Z").isEmpty() && DisplayPanel.timestamp("-999999999-01-01T00:00:00Z").isEmpty()
                        && extreme.at().isEmpty() && !extreme.lines().isEmpty(), "A year outside 0..9999 is shown as text, never converted: " + extreme);
        // M3-16: how a client words a point in time against its own clock.
        long at = java.time.Instant.parse("2026-10-11T07:30:00Z").toEpochMilli();
        java.time.ZoneId utc = java.time.ZoneOffset.UTC;
        long minute = 60_000;
        check(DisplayPanel.TimeShown.of(at, at - 30_000, utc).kind() == DisplayPanel.TimeShown.Kind.NOW
                && DisplayPanel.TimeShown.of(at, at + 59_000, utc).kind() == DisplayPanel.TimeShown.Kind.NOW, "Within a minute is now");
        DisplayPanel.TimeShown soon = DisplayPanel.TimeShown.of(at, at - (134 * minute + 30_000), utc);
        check(soon.kind() == DisplayPanel.TimeShown.Kind.IN && soon.hours() == 2 && soon.minutes() == 14, "In 2 h 14 min: " + soon);
        DisplayPanel.TimeShown past = DisplayPanel.TimeShown.of(at, at + 5 * minute, utc);
        check(past.kind() == DisplayPanel.TimeShown.Kind.AGO && past.hours() == 0 && past.minutes() == 5, "5 min ago: " + past);
        DisplayPanel.TimeShown later = DisplayPanel.TimeShown.of(at, at - 13 * 60 * minute, utc);
        check(later.kind() == DisplayPanel.TimeShown.Kind.CLOCK && later.clock().equals("07:30"), "Beyond 12 h within a day is the clock time");
        DisplayPanel.TimeShown far = DisplayPanel.TimeShown.of(at, at + 3 * 24 * 60 * minute, utc);
        check(far.kind() == DisplayPanel.TimeShown.Kind.DATE && far.date().equals("10-11 07:30"), "Further away the date and time: " + far.date());
        check(DisplayPanel.TimeShown.of(at, at, java.time.ZoneId.of("Europe/Berlin")).clock().equals("09:30"), "On the client's own clock");
        CompoundTag render = RenderTag.panel(titled);
        check(!render.toString().contains("sensor.") && RenderTag.panel(titled).size() <= 6, "The render data names no entity: " + render);
        check(ValueCodecs.utf8(RenderTag.updateTag(RenderTag.panel(DisplayPanel.Render.of(new DisplayPanel.Panel("Ü".repeat(255), DyeColor.CYAN, true,
                DisplayPanel.Size.SMALL, 15), "N".repeat(64), true))).toString()).length <= 400, "The largest render data stays about 300 bytes");

        panelTwin();
    }

    /** M3-15: the panel mirrors any readable entity read-only: its value as text with its unit, the level from its range or options. */
    private static void panelTwin() {
        TwinAdapter twin = DisplayPanel.TYPE.twin().orElseThrow();
        check(twin == DisplayPanel.TWIN && twin.primary().equals("message") && twin.sourceDomains().equals(art.rehra.mineqtt.twin.core.HaDomains.readableDomains())
                && !twin.controllable() && twin.targets().isEmpty() && twin.hasExtras()
                && twin.shapes().equals(Set.of(TwinAdapter.Shape.PATH, TwinAdapter.Shape.RANGE, TwinAdapter.Shape.DECIMALS)),
                "The panel mirrors every readable domain read-only, with path, range and decimals rows");
        check(!twin.sourceDomains().contains("person") && !twin.sourceDomains().contains("camera"), "Never a private domain");
        check(twin.leaves("input_number.m3_level", Binding.Settings.DEFAULT).equals(List.of("state", "last_updated", "min", "max", "unit_of_measurement"))
                && twin.leaves("sensor.m3_temperature", Binding.Settings.DEFAULT).equals(List.of("state", "last_updated", "unit_of_measurement"))
                && twin.leaves("input_select.m3_mode", Binding.Settings.DEFAULT).equals(List.of("state", "last_updated", "options"))
                && twin.leaves("climate.hall", Binding.Settings.DEFAULT).equals(List.of("state", "last_updated", "current_temperature")),
                "The value, its unit, range and options, as the domain allows: " + twin.leaves("input_number.m3_level", Binding.Settings.DEFAULT));
        check(DisplayPanel.defaultPath("climate").toString().equals(art.rehra.mineqtt.twin.core.ValuePath.keys("attributes", "current_temperature").toString())
                && DisplayPanel.defaultPath("sensor").toString().equals(art.rehra.mineqtt.twin.core.ValuePath.keys("state").toString())
                && DisplayPanel.defaultPath("scene").toString().equals(art.rehra.mineqtt.twin.core.ValuePath.keys("state").toString()),
                "A climate shows its current temperature, a sensor or a scene its state");

        check(panel("sensor.m3_temperature", Binding.Settings.DEFAULT, "state", "12.5", "unit_of_measurement", "\"°C\"")
                .equals(json("{\"message\":\"12.5 °C\",\"twin\":{\"level\":13}}")), "A sensor shows its value with its unit; 12.5 over 0..15 is 13");
        check(panel("input_number.m3_level", Binding.Settings.DEFAULT, "state", "42.5", "min", "0.0", "max", "100.0", "unit_of_measurement", "\"%\"")
                .equals(json("{\"message\":\"42.5%\",\"twin\":{\"level\":6}}")), "An input_number maps over its own range");
        check(panel("input_number.m3_level", Binding.Settings.DEFAULT.withMin(new BigDecimal("40")).withMax(new BigDecimal("50")).withDecimals(0),
                "state", "42.5", "min", "0.0", "max", "100.0", "unit_of_measurement", "\"%\"").equals(json("{\"message\":\"43%\",\"twin\":{\"level\":4}}")),
                "twin.min, twin.max and twin.decimals");
        check(panel("input_select.m3_mode", Binding.Settings.DEFAULT, "state", "Eco", "options", "[\"Off\", \"Eco\", \"Turbo\"]")
                .equals(json("{\"message\":\"Eco\",\"twin\":{\"level\":1}}")), "An option shows as itself and gives its index");
        check(panel("binary_sensor.door", Binding.Settings.DEFAULT, "state", "on").equals(json("{\"message\":\"on\",\"twin\":{\"level\":15}}"))
                && panel("binary_sensor.door", Binding.Settings.DEFAULT, "state", "off").equals(json("{\"message\":\"off\",\"twin\":{\"level\":0}}")),
                "A binary sensor is on or off");
        check(panel("climate.hall", Binding.Settings.DEFAULT, "state", "heat", "current_temperature", "21.5")
                .equals(json("{\"message\":\"21.5\",\"twin\":{\"level\":15}}")), "A climate shows its current temperature");
        check(panel("climate.hall", Binding.Settings.DEFAULT.withPath(art.rehra.mineqtt.twin.core.ValuePath.parse("state")), "state", "heat", "current_temperature", "21.5")
                .equals(json("{\"message\":\"heat\",\"twin\":{\"level\":15}}")), "twin.path picks another value");
        Binding.Settings brightnessPath = Binding.Settings.DEFAULT.withPath(art.rehra.mineqtt.twin.core.ValuePath.parse("attributes.brightness"));
        check(panel("light.porch", brightnessPath, "state", "on", "brightness", "128").equals(json("{\"message\":\"128\",\"twin\":{\"level\":15}}"))
                        && panel("light.porch", brightnessPath, "state", "off", "brightness", "null").equals(json("{\"message\":\"off\",\"twin\":{\"level\":0}}")),
                "Cap §9.4: a panel on an off light's null brightness shows off at level 0, never the brightness from before");
        check(panel("sensor.next_alarm", Binding.Settings.DEFAULT, "state", "2026-10-11T07:30:00+00:00")
                .equals(json("{\"message\":\"2026-10-11T07:30:00+00:00\",\"twin\":{\"level\":15}}")), "A timestamp stays a point in time for the client");
        check(panel("sensor.m3_temperature", Binding.Settings.DEFAULT, "state", "unavailable", "unit_of_measurement", "\"°C\"").isJsonNull(),
                "Unavailable holds: nothing applies (the health gives the level 0)");
        check(panel("sensor.m3_temperature", Binding.Settings.DEFAULT.withUnavailable(Binding.Unavailable.OFF), "state", "unavailable").equals(json("{\"message\":\"\"}")),
                "twin.unavailable off clears the message, without extras");
        check(panel("sensor.m3_temperature", Binding.Settings.DEFAULT, "state", "NaN").isJsonNull(), "NaN is never shown");
        // Applied as the panel applies it.
        DisplayPanel.Panel mirrored = DisplayPanel.Panel.DEFAULT.apply(new CommandReader(panel("input_number.m3_level", Binding.Settings.DEFAULT, "state", "42.5",
                "min", "0.0", "max", "100.0", "unit_of_measurement", "\"%\"").getAsJsonObject()), true).orElseThrow();
        check(mirrored.message().equals("42.5%") && mirrored.level() == 6, "The twin apply shows 42.5% at level 6");
        // A refused binding and another entity read nothing, extras neither.
        EntityDocuments documents = new EntityDocuments("ha_statestream", EntityDocuments.Limits.DEFAULT);
        documents.accept("ha_statestream/sensor/x/state", "5".getBytes(StandardCharsets.UTF_8), false, 0);
        Binding binding = Binding.of(twin.sourceEnd("sensor.x"));
        check(twin.extras(documents.document("sensor.x").orElseThrow(), binding.withRefused("not allowed")).isEmpty()
                && twin.extras(documents.document("sensor.x").orElseThrow(), Binding.of(twin.sourceEnd("sensor.y"))).isEmpty()
                && DisplayPanel.TYPE.twinCommand(documents.document("sensor.x").orElseThrow(), binding.withRefused("not allowed")).isEmpty(),
                "A refused binding or another entity's document gives neither values nor extras");
        Field<String> text = Field.text("text", Access.READ_WRITE);
        TwinAdapter textTwin = TwinAdapter.builder("text").source((domain, settings) -> Map.of("text",
                new art.rehra.mineqtt.twin.core.PortRead(art.rehra.mineqtt.twin.core.ValuePath.keys("state"), new art.rehra.mineqtt.twin.core.ValueMap.Text(-1, null, 255))),
                "sensor").build();
        DeviceType.builder(Identifier.fromNamespaceAndPath("mineqtt", "test_extras")).model("Test").field(text).entity(Ha.text("text", text)).twin(textTwin).build();
        failsToBuild(DeviceType.builder(Identifier.fromNamespaceAndPath("mineqtt", "test_extras")).model("Test")
                        .field(text).field(Field.text("twin", Access.READ)).entity(Ha.text("text", text)).twin(textTwin),
                "port keyed twin", "A twin type with a port keyed twin");
    }

    // The command a panel bound to `entity` gets from a document folded from these statestream leaves; JsonNull for none.
    private static JsonElement panel(String entity, Binding.Settings settings, String... leaves) {
        EntityDocuments documents = new EntityDocuments("ha_statestream", EntityDocuments.Limits.DEFAULT);
        String topic = "ha_statestream/" + entity.replace('.', '/') + "/";
        for (int i = 0; i < leaves.length; i += 2) {
            documents.accept(topic + leaves[i], leaves[i + 1].getBytes(StandardCharsets.UTF_8), false, i);
        }
        Binding binding = Binding.of(DisplayPanel.TWIN.sourceEnd(entity)).withSettings(settings);
        return DisplayPanel.TYPE.twinCommand(documents.document(entity).orElseThrow(), binding).map(command -> (JsonElement) command).orElse(JsonNull.INSTANCE);
    }

    // The light a lamp in state `before` shows after the twin applies an entity folded from these statestream leaves.
    private static ValueCodecs.LightState lamp(String entity, ValueCodecs.LightState before, String... leaves) {
        EntityDocuments documents = new EntityDocuments("ha_statestream", EntityDocuments.Limits.DEFAULT);
        String topic = "ha_statestream/" + entity.replace('.', '/') + "/";
        for (int i = 0; i < leaves.length; i += 2) {
            documents.accept(topic + leaves[i], leaves[i + 1].getBytes(StandardCharsets.UTF_8), false, i);
        }
        Binding binding = Binding.of(SmartLamp.TWIN.sourceEnd(entity));
        return SmartLamp.TYPE.twinCommand(documents.document(entity).orElseThrow(), binding).map(before::merge).orElse(before);
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    /**
     * The Wall Switch (M2-22): the gestures a hand's uses make, the events and Raw Link payloads they publish, the
     * commands its actions send, and its Raw Link of events, which is never retained.
     */
    private static void wallSwitch() {
        WallSwitch.Button left = WallSwitch.Button.LEFT;
        WallSwitch.Button right = WallSwitch.Button.RIGHT;
        // One click: a press once the double press window is over, nothing before.
        WallSwitch.Gestures gestures = new WallSwitch.Gestures();
        check(gestures.use(left, 100).isEmpty() && gestures.down(left, 100) && !gestures.down(right, 100), "A click holds its button down at once");
        check(ticks(gestures, 101, 108).isEmpty(), "A press waits for a second click for 8 ticks");
        check(gestures.tick(109).equals(Map.of(left, WallSwitch.Gesture.PRESS)) && gestures.idle(), "One click is a press after 8 ticks");
        check(!gestures.down(left, 109), "The button is up again");
        // Two clicks within 8 ticks: a double press.
        gestures.use(right, 200);
        check(ticks(gestures, 201, 206).isEmpty() && gestures.use(right, 207).isEmpty(), "A second click comes within the window");
        check(ticks(gestures, 208, 215).isEmpty() && gestures.tick(216).equals(Map.of(right, WallSwitch.Gesture.DOUBLE_PRESS)), "Two clicks are a double press");
        // A click held a fifth of a second repeats once: it counts as two.
        gestures.use(left, 300);
        gestures.use(left, 304);
        check(gestures.tick(313).equals(Map.of(left, WallSwitch.Gesture.DOUBLE_PRESS)), "A click repeated once is a double press");
        // Held: the use key repeats every 4 ticks; the hold fires once, at 10 ticks, and nothing when it is let go.
        check(gestures.use(left, 400).isEmpty() && gestures.use(left, 404).isEmpty() && gestures.use(left, 408).isEmpty(), "Holding is no gesture yet");
        check(gestures.use(left, 412).equals(Optional.of(WallSwitch.Gesture.HOLD)), "Held for 10 ticks is a hold");
        check(gestures.use(left, 416).isEmpty() && gestures.use(left, 420).isEmpty() && gestures.down(left, 420), "A hold fires once while held");
        check(ticks(gestures, 421, 440).isEmpty() && gestures.idle(), "Letting go of a hold fires nothing");
        // Clicks with breaks are no hold, even over 10 ticks.
        gestures.use(right, 500);
        gestures.use(right, 507);
        check(gestures.use(right, 514).isEmpty() && gestures.tick(523).equals(Map.of(right, WallSwitch.Gesture.DOUBLE_PRESS)), "Three clicks with breaks are a double press, no hold");
        // The two buttons are told apart on their own.
        gestures.use(left, 600);
        gestures.use(right, 603);
        check(gestures.tick(609).equals(Map.of(left, WallSwitch.Gesture.PRESS)) && gestures.tick(612).equals(Map.of(right, WallSwitch.Gesture.PRESS)),
                "A click on each button is a press of each");
        // A click after the window, before a tick told the press: the press comes first, then a new gesture starts.
        gestures.use(left, 700);
        check(gestures.use(left, 709).equals(Optional.of(WallSwitch.Gesture.PRESS)) && !gestures.idle(), "A late click tells the press before it");
        check(gestures.tick(718).equals(Map.of(left, WallSwitch.Gesture.PRESS)), "and is a press itself");
        gestures.use(left, 800);
        gestures.clear();
        check(gestures.idle() && gestures.tick(900).isEmpty(), "Clearing forgets a gesture under way");

        // Events and Raw Link payloads.
        check(WallSwitch.event(WallSwitch.Gesture.DOUBLE_PRESS, right).toString().equals("{\"event_type\":\"double_press\",\"button\":\"right\"}"),
                "A gesture's event names its button");
        for (WallSwitch.Gesture gesture : WallSwitch.Gesture.values()) {
            for (WallSwitch.Button button : WallSwitch.Button.values()) {
                check(WallSwitch.TYPE.eventProblem(WallSwitch.event(gesture, button)).isEmpty(), "Every gesture's event is declared: " + gesture + " " + button);
                check(WallSwitch.rawPayload(gesture, button).equals(gesture.eventType() + "_" + button.getSerializedName()), "The Raw Link carries the event's name");
            }
        }
        check(WallSwitch.rawPayload(WallSwitch.Gesture.HOLD, left).equals("hold_left"), "A hold of the left button is hold_left");
        // M3-14, D16: each gesture is also its button's bus-only event; Home Assistant's event entity and triggers, the
        // action topic and the Raw Link never carry it.
        check(WallSwitch.buttonEvent(WallSwitch.Gesture.HOLD, right).toString().equals("{\"event_type\":\"right\",\"gesture\":\"hold\"}"),
                "A gesture's per-button event names its gesture");
        for (WallSwitch.Gesture gesture : WallSwitch.Gesture.values()) {
            for (WallSwitch.Button button : WallSwitch.Button.values()) {
                check(WallSwitch.TYPE.eventProblem(WallSwitch.buttonEvent(gesture, button)).isEmpty(), "Every per-button event is declared: " + gesture + " " + button);
            }
        }
        for (HaEntity<?> entity : WallSwitch.TYPE.entities()) {
            if (entity instanceof HaEntity.Event event) check(!event.eventTypes().contains("left") && !event.eventTypes().contains("right"), "The event entity knows no per-button event");
            if (entity instanceof HaEntity.DeviceTrigger trigger) check(!WallSwitch.TYPE.event(trigger.event()).orElseThrow().busOnly(), "No device trigger fires on a bus-only event");
        }

        // The actions' commands, as Home Assistant, a wire, the screen and the command twin send them.
        check(WallSwitch.presses(new CommandReader(WallSwitch.PRESS_LEFT.command(WallSwitch.PRESS_LEFT.parse("").orElseThrow())))
                .equals(Optional.of(List.of(left))), "press_left with PRESS presses the left button");
        check(WallSwitch.presses(command("{\"press_right\":\"press\",\"press_left\":\"\"}")).orElseThrow().size() == 2, "Both buttons at once, any case");
        for (String refused : List.of("{\"press_left\":\"ON\"}", "{\"press_middle\":\"PRESS\"}", "{\"state\":\"TOGGLE\"}", "{}",
                "{\"press_left\":{\"x\":1}}", "{\"press_left\":\"PRESS\",\"power\":3}")) {
            check(WallSwitch.presses(command(refused)).isEmpty(), "Not a press: " + refused);
        }
        Port wired = WallSwitch.TYPE.port(Port.Direction.IN, "press_left").orElseThrow();
        check(Coerce.coerce(RedstoneInput.pulse(15), RedstoneInput.TYPE.port(Port.Direction.OUT, "pulse").orElseThrow().kind(), wired.kind(), wired.toggles())
                .flatMap(value -> WallSwitch.presses(new CommandReader(WallSwitch.TYPE.inPortCommand("press_left", value)))).equals(Optional.of(List.of(left))),
                "An event wired to press_left presses the left button");

        // A Raw Link of events: never retained, no retain setting, no custom payloads.
        RawLinkSpec spec = WallSwitch.TYPE.rawLink().orElseThrow();
        check(spec.direction() == RawLinkSpec.Direction.OUT && !spec.level() && !spec.customPayloads() && !spec.retainByDefault(), "The switch publishes events");
        RawLink retained = new RawLink("home/hall/switch", RawLinkSpec.Direction.OUT, null, null, 0, true, null, false, null, 1, null, RawLink.Edges.BOTH);
        check(!retained.effectiveRetain(spec) && !RawRetain.retains(retained, spec) && RawRetain.destroyed(retained, spec).isEmpty(),
                "An event link is never retained, whatever its override, and leaves nothing behind");
        check(keys(WallSwitch.TYPE.linkSettings()).equals(List.of("name", "expose", "access", "raw.channel", "raw.private", "raw.topic", "raw.qos",
                        "twin.left", "twin.right", "twin.resume")),
                "A Raw Link of events has QoS but no retain and no payloads");
        boolean refused = false;
        try {
            RawLinkSpec.events().retainByDefault(true);
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check(refused, "An output of events cannot retain by default");
    }

    private static Map<WallSwitch.Button, WallSwitch.Gesture> ticks(WallSwitch.Gestures gestures, long from, long to) {
        Map<WallSwitch.Button, WallSwitch.Gesture> told = new LinkedHashMap<>();
        for (long tick = from; tick <= to; tick++) told.putAll(gestures.tick(tick));
        return told;
    }

    private static CommandReader command(String json) {
        return new CommandReader(JsonParser.parseString(json).getAsJsonObject());
    }

    // The Climate Sensor's readings, settings and saved state (M2-19).
    private static void climate() {
        ClimateSensor.Environment plains = new ClimateSensor.Environment("minecraft:plains", 0.8f, ClimateSensor.Precipitation.RAIN, 1, 0, 15, 0,
                true, false, 0, 0);
        ClimateSensor.Reading noon = ClimateSensor.measure(plains);
        check(noon.equals(new ClimateSensor.Reading(21.5, 60.0, 100_000, "minecraft:plains")), "Plains at a clear noon on open ground: " + noon);
        ClimateSensor.Reading night = ClimateSensor.measure(new ClimateSensor.Environment("minecraft:plains", 0.8f, ClimateSensor.Precipitation.RAIN, 1,
                ClimateSensor.MAX_SKY_DARKEN, 15, 0, true, false, 0, 0));
        check(night.temperature() < noon.temperature() && night.humidity() > noon.humidity() && night.illuminance() == 22,
                "The night is colder, moister and darker: " + night);
        ClimateSensor.Reading desert = ClimateSensor.measure(new ClimateSensor.Environment("minecraft:desert", 2.0f, ClimateSensor.Precipitation.NONE, 1, 0,
                15, 0, true, false, 0, 0));
        ClimateSensor.Reading jungle = ClimateSensor.measure(new ClimateSensor.Environment("minecraft:jungle", 0.95f, ClimateSensor.Precipitation.RAIN, 1, 0,
                15, 0, true, false, 0, 0));
        ClimateSensor.Reading snowy = ClimateSensor.measure(new ClimateSensor.Environment("minecraft:snowy_plains", 0f, ClimateSensor.Precipitation.SNOW, 1, 0,
                15, 0, true, false, 0, 0));
        check(desert.temperature() > jungle.temperature() && jungle.temperature() > snowy.temperature() && desert.humidity() < snowy.humidity()
                && snowy.humidity() < jungle.humidity(), "A desert is hotter and drier than a jungle, which is warmer than snowy plains");
        ClimateSensor.Reading lava = ClimateSensor.measure(new ClimateSensor.Environment("minecraft:plains", 0.8f, ClimateSensor.Precipitation.RAIN, 1, 0,
                15, 0, true, false, 40, 0));
        check(lava.temperature() == noon.temperature() + 30 && lava.humidity() == noon.humidity() - 30, "Heat sources add at most 30 °C and take at most 30 %");
        ClimateSensor.Reading cave = ClimateSensor.measure(new ClimateSensor.Environment("minecraft:dripstone_caves", 0.8f, ClimateSensor.Precipitation.RAIN,
                -100, 0, 0, 0, false, false, 0, 0));
        check(cave.illuminance() == 0 && cave.temperature() > noon.temperature() && cave.humidity() > noon.humidity(),
                "A dark cave far below the sea level is warmer, moister and has no light: " + cave);
        check(ClimateSensor.measure(new ClimateSensor.Environment("", 0.8f, ClimateSensor.Precipitation.RAIN, 1, 0, 0, 15, false, false, 0, 0))
                .illuminance() == 1000, "A torch's block light 15 is 1000 lx");
        ClimateSensor.Reading extreme = ClimateSensor.measure(new ClimateSensor.Environment("", 10f, ClimateSensor.Precipitation.NONE, -1000, 0, 15, 15,
                true, false, 99, 0));
        check(extreme.temperature() == ClimateSensor.MAX_TEMPERATURE && extreme.humidity() == 0 && extreme.illuminance() == ClimateSensor.MAX_ILLUMINANCE,
                "Readings are clamped: " + extreme);

        // The Raw Link payload ignores the server's locale (v1 wrote 21,5 on a German one).
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            ClimateSensor.Reading reading = new ClimateSensor.Reading(21.5, 47.0, 1234, "minecraft:plains");
            check(reading.raw(ClimateSensor.RawValue.TEMPERATURE).equals("21.5") && reading.raw(ClimateSensor.RawValue.HUMIDITY).equals("47.0")
                    && reading.raw(ClimateSensor.RawValue.ILLUMINANCE).equals("1234"), "Raw payloads in Locale.ROOT notation");
            JsonObject state = new ClimateSensor.Climate(10, ClimateSensor.RawValue.HUMIDITY, reading, 5).write(new StateWriter()).json();
            check(state.toString().equals("{\"temperature\":21.5,\"humidity\":47.0,\"illuminance\":1234,\"biome\":\"minecraft:plains\","
                    + "\"interval\":10,\"raw_value\":\"humidity\"}"), "The state JSON: " + state);
        } finally {
            Locale.setDefault(before);
        }
        check(ClimateSensor.TEMPERATURE.parse(new JsonPrimitive(21.46)).orElseThrow() == 21.5 && ClimateSensor.TEMPERATURE.parse(new JsonPrimitive("999")).orElseThrow() == 120
                && ClimateSensor.TEMPERATURE.parse(new JsonPrimitive("warm")).isEmpty(), "A decimal field rounds to its decimals, clamps and refuses garbage");

        ClimateSensor.Climate climate = new ClimateSensor.Climate(30, ClimateSensor.RawValue.ILLUMINANCE, new ClimateSensor.Reading(-3.5, 88.5, 40, "minecraft:taiga"), 1234);
        Tag saved = ClimateSensor.Climate.CODEC.encodeStart(NbtOps.INSTANCE, climate).getOrThrow();
        check(ClimateSensor.Climate.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow().equals(climate), "The saved state round-trips");
        check(ClimateSensor.Climate.CODEC.parse(NbtOps.INSTANCE, new CompoundTag()).getOrThrow().equals(ClimateSensor.Climate.DEFAULT), "Missing values keep their defaults");
        check(climate.due(1234 + 600) && !climate.due(1234 + 599) && climate.due(1000), "A reading is due after the interval, or when the last one lies in the future");
        check(climate.apply(new CommandReader(JsonParser.parseString("{\"interval\":5000}").getAsJsonObject())).orElseThrow().interval() == ClimateSensor.MAX_INTERVAL
                        && climate.apply(new CommandReader(JsonParser.parseString("{\"raw_value\":\"HUMIDITY\"}").getAsJsonObject())).orElseThrow().rawValue()
                        == ClimateSensor.RawValue.HUMIDITY
                        && climate.apply(new CommandReader(JsonParser.parseString("{\"temperature\":5}").getAsJsonObject())).isEmpty(),
                "Commands set the interval (clamped by Home Assistant's lenient path) and the Raw Link's reading; readings are not commands");
        check(ClimateSensor.Climate.ofV1(200, 23.4, null, 77).equals(new ClimateSensor.Climate(10, ClimateSensor.RawValue.TEMPERATURE,
                new ClimateSensor.Reading(23.4, 50.0, 0, ""), 77)), "A v1 temperature sensor: 200 ticks are 10 s, it publishes its temperature");
        check(ClimateSensor.Climate.ofV1(30, null, 61.0, 0).equals(new ClimateSensor.Climate(2, ClimateSensor.RawValue.HUMIDITY,
                new ClimateSensor.Reading(20.0, 61.0, 0, ""), 0)), "A v1 humidity sensor: 30 ticks round up to 2 s, it keeps publishing its humidity");
        check(ClimateSensor.Climate.ofV1(5, null, null, 0).interval() == ClimateSensor.MIN_INTERVAL, "v1's minimum of a second");
        check(climate.placed().equals(new ClimateSensor.Climate(30, ClimateSensor.RawValue.ILLUMINANCE, ClimateSensor.Reading.DEFAULT, 0)),
                "A placed copy keeps its settings and takes its own reading (M2 plan D14)");
    }

    private static void failsToBuild(DeviceType.Builder builder, String reason, String message) {
        try {
            builder.build();
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains(reason), message + " fails for the wrong reason: " + expected.getMessage());
            return;
        }
        throw new AssertionError(message + " must not build");
    }

    private static void expectFailure(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message + " must be refused");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

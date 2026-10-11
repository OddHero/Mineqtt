package art.rehra.mineqtt.ha;

import art.rehra.mineqtt.device.Access;
import art.rehra.mineqtt.device.Action;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceGroup;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.EventSpec;
import art.rehra.mineqtt.device.Field;
import art.rehra.mineqtt.device.TwinTestTypes;
import art.rehra.mineqtt.twin.TwinAdapter;
import art.rehra.mineqtt.twin.core.Binding;
import art.rehra.mineqtt.device.types.ClimateSensor;
import art.rehra.mineqtt.device.types.DisplayPanel;
import art.rehra.mineqtt.device.types.PresenceSensor;
import art.rehra.mineqtt.device.types.RedstoneInput;
import art.rehra.mineqtt.device.types.RedstoneOutput;
import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.device.types.SmartPlug;
import art.rehra.mineqtt.device.types.WallSwitch;
import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.runtime.TopicScheme;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Discovery payloads (design §4), run by Gradle's verifyDiscoveryPayloads task. The bridge and every device type are
 * compared with their golden files; the Smart Lamp must give exactly §4.1's example, and a type with every entity kind
 * must generate the documented templates and topics.
 */
public final class DiscoveryGoldenTest {
    static final UUID WORLD = UUID.fromString("5b0e9a7c-3d2f-41e8-a6c4-b1d09f8e7a63");
    static final DiscoveryPublisher.Identity IDENTITY = new DiscoveryPublisher.Identity("world", WORLD, "2.0.0");
    static final TopicScheme TOPICS = new TopicScheme("mineqtt", "world_7f3a");
    private static final UUID LAMP_ID = UUID.fromString("3f9c2d1e-8a7b-4c6d-9e0f-112233445566");
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    enum Mode implements StringRepresentable {
        SWITCH, LEVEL;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    static void main(String[] args) throws IOException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        bridge();
        lampAsDesigned();
        smartLamp();
        redstone();
        presence();
        smartPlug();
        climate();
        wallSwitch();
        displayPanel();
        everyEntityKind();
        mirrored();
        System.out.println("Discovery payload checks passed.");
    }

    /**
     * M3 plan D6: a bound device keeps its device, identifiers and availability, but of its entities only the disabled
     * {@code binding} diagnostic sensor; every entity it declares stays as a platform-only stub, which removes it in Home
     * Assistant, in every reduced message. Unbinding gives the full message again, byte for byte, with the same
     * unique_ids. A refused binding is still bound; a device with targets only is discovered in full.
     */
    private static void mirrored() {
        DeviceType type = TwinTestTypes.TYPE;
        DeviceRecord record = new DeviceRecord(LAMP_ID, type.id(), GlobalPos.of(Level.OVERWORLD, new BlockPos(3, 64, 3)), "Porch Mirror", "porch_mirror");
        record.setLegacyUniqueId("home_porch");
        JsonObject full = DiscoveryPublisher.deviceConfig(type, record, TOPICS, IDENTITY);
        record.setTwin(TwinTestTypes.bound("switch.porch", true));
        JsonObject reduced = DiscoveryPublisher.deviceConfig(type, record, TOPICS, IDENTITY);
        for (String key : List.of("dev", "o", "avty", "avty_mode", "qos")) {
            check(reduced.get(key).equals(full.get(key)), "A bound device keeps its " + key + ": " + reduced.get(key));
        }
        JsonObject components = reduced.getAsJsonObject("cmps");
        Set<String> declared = full.getAsJsonObject("cmps").keySet();
        check(components.keySet().containsAll(declared) && components.keySet().size() == declared.size() + 1 && components.has(TwinAdapter.BINDING_ENTITY),
                "Every declared entity and the binding sensor: " + components.keySet());
        for (String key : declared) {
            JsonObject stub = components.getAsJsonObject(key);
            check(stub.keySet().equals(Set.of("p")) && stub.get("p").equals(full.getAsJsonObject("cmps").getAsJsonObject(key).get("p")),
                    "A declared entity is a platform-only stub, which removes it: " + key + " " + stub);
        }
        JsonObject sensor = components.getAsJsonObject(TwinAdapter.BINDING_ENTITY);
        expect(sensor, "p", "sensor");
        expect(sensor, "unique_id", "mineqtt_3f9c2d1e8a7b4c6d9e0f112233445566_binding");
        expect(sensor, "state_topic", "mineqtt/world_7f3a/porch_mirror");
        expect(sensor, "json_attributes_topic", "mineqtt/world_7f3a/porch_mirror");
        expect(sensor, "entity_category", "diagnostic");
        check(!sensor.get("enabled_by_default").getAsBoolean() && sensor.get("value_template").getAsString().contains("value_json.binding.entity")
                && !sensor.has("command_topic"), "The sensor is disabled by default, reads the binding's entity and commands nothing: " + sensor);
        check(!reduced.toString().contains("switch.porch"), "The discovery never names the bound entity; the state carries it");
        check(!components.has("output") || !components.getAsJsonObject("output").has("unique_id"), "No command entity survives");

        record.setTwin(record.twin().withRefused("switch.porch is not in twin.allowedEntities"));
        check(DiscoveryPublisher.deviceConfig(type, record, TOPICS, IDENTITY).equals(reduced), "A refused binding is still bound: the same reduced message");
        record.setTwin(new Binding(null, Map.of(TwinTestTypes.PRESSED, TwinAdapter.targetEnd("scene.movie")), false, Binding.Settings.DEFAULT, null));
        check(DiscoveryPublisher.deviceConfig(type, record, TOPICS, IDENTITY).equals(full), "Targets alone mirror nothing: the full message");
        record.setTwin(null);
        JsonObject unbound = DiscoveryPublisher.deviceConfig(type, record, TOPICS, IDENTITY);
        check(unbound.equals(full) && DiscoveryPublisher.hash(unbound).equals(DiscoveryPublisher.hash(full)) && !DiscoveryPublisher.hash(reduced).equals(DiscoveryPublisher.hash(full)),
                "Unbinding republishes the full message with the same unique_ids; binding changes the hash, so a refresh publishes it");
        check(full.getAsJsonObject("cmps").getAsJsonObject("output").get("unique_id").getAsString().equals("home_porch"),
                "...the primary entity keeping its v1 unique_id");
        cleanupMessages(type, record, full, reduced, sensor);
    }

    /**
     * M3-7b: the messages of a cleanup. The first is the full message plus the binding sensor, whichever way the device is
     * bound, so Home Assistant discovers every component; the removal is the reduced message while bound, and every
     * component as a stub once unbound, the binding sensor included (left out, the sensor would stay as an orphan).
     */
    private static void cleanupMessages(DeviceType type, DeviceRecord record, JsonObject full, JsonObject reduced, JsonObject sensor) {
        JsonObject discoverUnbound = DiscoveryPublisher.discoverConfig(type, record, TOPICS, IDENTITY);
        record.setTwin(TwinTestTypes.bound("switch.porch", true));
        JsonObject discoverBound = DiscoveryPublisher.discoverConfig(type, record, TOPICS, IDENTITY);
        check(discoverBound.equals(discoverUnbound), "The first message of a cleanup does not depend on the binding");
        for (String key : List.of("dev", "o", "avty", "avty_mode", "qos")) {
            check(discoverBound.get(key).equals(full.get(key)), "The first message keeps the device's " + key);
        }
        JsonObject everything = discoverBound.getAsJsonObject("cmps");
        Set<String> declared = full.getAsJsonObject("cmps").keySet();
        check(everything.keySet().size() == declared.size() + 1 && everything.keySet().containsAll(declared)
                && everything.getAsJsonObject(TwinAdapter.BINDING_ENTITY).equals(sensor), "...with every declared entity and the binding sensor: " + everything.keySet());
        for (String key : declared) {
            check(everything.get(key).equals(full.getAsJsonObject("cmps").get(key)), "...each declared entity in full: " + key);
        }
        check(!discoverBound.toString().contains("switch.porch"), "...and never the bound entity");

        check(DiscoveryPublisher.removalConfig(type, record, TOPICS, IDENTITY).equals(reduced), "Bound, the removal is the reduced message");
        record.setTwin(null);
        JsonObject removal = DiscoveryPublisher.removalConfig(type, record, TOPICS, IDENTITY);
        JsonObject components = removal.getAsJsonObject("cmps");
        check(components.getAsJsonObject(TwinAdapter.BINDING_ENTITY).keySet().equals(Set.of("p"))
                && components.getAsJsonObject(TwinAdapter.BINDING_ENTITY).get("p").getAsString().equals("sensor"), "Unbound, the removal stubs the binding sensor: " + components);
        check(components.keySet().size() == declared.size() + 1 && components.entrySet().stream().allMatch(entry -> entry.getValue().getAsJsonObject().keySet().equals(Set.of("p"))),
                "...and every declared entity, which the full message after it discovers again: " + components);
        for (String key : List.of("dev", "o", "avty", "avty_mode", "qos")) {
            check(removal.get(key).equals(full.get(key)), "The removal keeps the device's " + key);
        }
        check(DiscoveryPublisher.deviceConfig(type, record, TOPICS, IDENTITY).equals(full), "The steady message after it is the full message alone");
    }

    private static void bridge() throws IOException {
        JsonObject generated = BridgeDevice.discovery(TOPICS, IDENTITY);
        assertGolden("bridge", generated);
        check(BridgeDevice.objectId(WORLD).equals("mineqtt_bridge_5b0e9a7c3d2f41e8a6c4b1d09f8e7a63"), "The bridge is named after the world UUID");
    }

    /** The Smart Lamp gives §4.1's example message, with the device type as its model id. */
    private static void lampAsDesigned() {
        DeviceRecord record = new DeviceRecord(LAMP_ID, SmartLamp.TYPE.id(), GlobalPos.of(Level.OVERWORLD, new BlockPos(120, 64, -35)), "Kitchen Lamp", "kitchen_lamp");
        JsonObject generated = DiscoveryPublisher.deviceConfig(SmartLamp.TYPE, record, TOPICS, IDENTITY);
        assertSame("§4.1 Smart Lamp", JsonParser.parseString(DESIGN_LAMP).getAsJsonObject(), generated);
        check(!generated.getAsJsonObject("dev").has("sa"), "No suggested_area without a room, never the dimension");
    }

    /** A new lamp, a migrated v1 lamp and a legacy group against their golden files. */
    private static void smartLamp() throws IOException {
        DeviceRecord lamp = new DeviceRecord(LAMP_ID, SmartLamp.TYPE.id(), GlobalPos.of(Level.OVERWORLD, new BlockPos(120, 64, -35)), "Kitchen Lamp", "kitchen_lamp");
        assertGolden("lamp", DiscoveryPublisher.deviceConfig(SmartLamp.TYPE, lamp, TOPICS, IDENTITY));

        DeviceRecord legacy = new DeviceRecord(LAMP_ID, SmartLamp.TYPE.id(), GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO), "Kitchen", "kitchen");
        legacy.setLegacyUniqueId("home_kitchen");
        JsonObject legacyConfig = DiscoveryPublisher.deviceConfig(SmartLamp.TYPE, legacy, TOPICS, IDENTITY);
        assertGolden("lamp_legacy", legacyConfig);
        JsonObject components = legacyConfig.getAsJsonObject("cmps");
        check(components.getAsJsonObject("light").get("unique_id").getAsString().equals("home_kitchen"),
                "A migrated v1 lamp's light keeps the v1 unique_id");
        check(components.getAsJsonObject("light_level").get("unique_id").getAsString().equals("mineqtt_3f9c2d1e8a7b4c6d9e0f112233445566_light_level"),
                "Only the primary entity takes the v1 unique_id");
        check(ids(legacyConfig).equals(List.of("mineqtt_3f9c2d1e8a7b4c6d9e0f112233445566", "home_kitchen")),
                "A migrated v1 lamp names v1's device identifier after its own, got " + ids(legacyConfig));
        check(ids(DiscoveryPublisher.deviceConfig(SmartLamp.TYPE, lamp, TOPICS, IDENTITY)).equals(List.of("mineqtt_3f9c2d1e8a7b4c6d9e0f112233445566")),
                "A lamp without a v1 unique_id has only its own identifier");

        // M3-8: a lamp bound to a Home Assistant light keeps one disabled diagnostic entity; its light and sensors are
        // platform-only stubs, which remove them in Home Assistant (M3 plan D6). The entity id is never in the discovery.
        DeviceRecord mirrored = new DeviceRecord(LAMP_ID, SmartLamp.TYPE.id(), GlobalPos.of(Level.OVERWORLD, new BlockPos(120, 64, -35)), "Kitchen Lamp", "kitchen_lamp");
        mirrored.setTwin(Binding.of(SmartLamp.TWIN.sourceEnd("light.kitchen_ceiling")));
        JsonObject mirroredConfig = DiscoveryPublisher.deviceConfig(SmartLamp.TYPE, mirrored, TOPICS, IDENTITY);
        assertGolden("lamp_mirrored", mirroredConfig);
        check(!mirroredConfig.toString().contains("kitchen_ceiling"), "A mirrored lamp's discovery never names its entity");
        mirrored.setTwin(null);
        check(DiscoveryPublisher.deviceConfig(SmartLamp.TYPE, mirrored, TOPICS, IDENTITY).equals(DiscoveryPublisher.deviceConfig(SmartLamp.TYPE, lamp, TOPICS, IDENTITY)),
                "Unbinding the lamp gives its full golden message again");

        DeviceGroup group = new DeviceGroup(UUID.fromString("00000000-0000-4000-8000-0000000000aa"), "Hallway", "hallway");
        group.setLegacyUniqueId("home_hallway");
        group.setLegacyTopic("home/hallway");
        JsonObject config = DiscoveryPublisher.groupConfig(SmartLamp.TYPE, group, TOPICS, IDENTITY);
        assertGolden("legacy_group", config);
        JsonObject groupComponents = config.getAsJsonObject("cmps");
        check(groupComponents.keySet().equals(Set.of("light")), "A group has only the member type's command entities, got " + groupComponents.keySet());
        JsonObject light = groupComponents.getAsJsonObject("light");
        check(light.get("unique_id").getAsString().equals("home_hallway"), "A legacy group's light keeps the v1 unique_id");
        check(light.get("state_topic").getAsString().equals("mineqtt/world_7f3a/group/hallway")
                && light.get("command_topic").getAsString().equals("mineqtt/world_7f3a/group/hallway/set"), "A group uses its group topics");
        JsonArray avty = config.getAsJsonArray("avty");
        check(avty.size() == 1 && avty.get(0).getAsJsonObject().get("t").getAsString().equals("mineqtt/world_7f3a/bridge/state"),
                "A group has only the bridge's availability, got " + avty);
        check(ids(config).equals(List.of("mineqtt_000000000000400080000000000000aa", "home_hallway")),
                "A legacy group is a device of its own, named after its UUID and then v1's device identifier, got " + ids(config));
        DeviceGroup plain = new DeviceGroup(UUID.fromString("00000000-0000-4000-8000-0000000000bb"), "Porch", "porch");
        check(ids(DiscoveryPublisher.groupConfig(SmartLamp.TYPE, plain, TOPICS, IDENTITY)).equals(List.of("mineqtt_000000000000400080000000000000bb")),
                "A group without a v1 unique_id has only its own identifier");
        check(config.getAsJsonObject("dev").get("mdl").getAsString().equals("Smart Lamp group"), "The group's model names the member type");
    }

    /** The Redstone Output and Input (§4.4) against their golden files, and an output group. */
    private static void redstone() throws IOException {
        UUID outputId = UUID.fromString("7a1e0c55-2b3d-4e6f-8091-a2b3c4d5e6f7");
        DeviceRecord output = new DeviceRecord(outputId, RedstoneOutput.TYPE.id(), GlobalPos.of(Level.OVERWORLD, new BlockPos(4, 64, 2)), "Garage Door", "garage_door");
        JsonObject outputConfig = DiscoveryPublisher.deviceConfig(RedstoneOutput.TYPE, output, TOPICS, IDENTITY);
        assertGolden("output", outputConfig);
        JsonObject outputSwitch = outputConfig.getAsJsonObject("cmps").getAsJsonObject("output");
        check(outputSwitch.get("name").isJsonNull() && outputSwitch.get("unique_id").getAsString().equals("mineqtt_7a1e0c552b3d4e6f8091a2b3c4d5e6f7_output"),
                "The output's switch is its primary entity");
        // M3-12: an output bound to the real door contact keeps only the disabled binding sensor (M3 plan D6).
        output.setTwin(Binding.of(RedstoneOutput.TWIN.sourceEnd("binary_sensor.front_door")));
        JsonObject outputMirrored = DiscoveryPublisher.deviceConfig(RedstoneOutput.TYPE, output, TOPICS, IDENTITY);
        assertGolden("output_mirrored", outputMirrored);
        check(!outputMirrored.toString().contains("front_door"), "A mirrored output's discovery never names its entity");
        output.setTwin(null);
        check(DiscoveryPublisher.deviceConfig(RedstoneOutput.TYPE, output, TOPICS, IDENTITY).equals(outputConfig), "Unbinding the output gives its full golden message again");

        UUID inputId = UUID.fromString("0c4d8e2f-6a1b-4c3d-9e5f-708192a3b4c5");
        DeviceRecord input = new DeviceRecord(inputId, RedstoneInput.TYPE.id(), GlobalPos.of(Level.OVERWORLD, new BlockPos(6, 64, 2)), "Doorbell", "doorbell");
        JsonObject inputConfig = DiscoveryPublisher.deviceConfig(RedstoneInput.TYPE, input, TOPICS, IDENTITY);
        assertGolden("input", inputConfig);
        check(inputConfig.getAsJsonObject("cmps").entrySet().stream().noneMatch(entry -> entry.getValue().getAsJsonObject().has("command_topic")),
                "An input takes no commands");

        JsonObject group = DiscoveryPublisher.groupConfig(RedstoneOutput.TYPE, new DeviceGroup(UUID.randomUUID(), "Gates", "gates"), TOPICS, IDENTITY);
        check(group.getAsJsonObject("cmps").keySet().equals(Set.of("output", "power_level", "mode", "power_on_behaviour")),
                "An output group has the output's command entities: " + group.getAsJsonObject("cmps").keySet());
    }

    /**
     * The Smart Plug (§4.4, M2-18) against its golden file: an outlet switch as its primary entity, its power-on
     * behaviour, and a plug group with the plug's command entities.
     */
    private static void smartPlug() throws IOException {
        UUID plugId = UUID.fromString("9b2c4d6e-8f1a-4b3c-8d5e-6f708192a3b4");
        DeviceRecord plug = new DeviceRecord(plugId, SmartPlug.TYPE.id(), GlobalPos.of(Level.OVERWORLD, new BlockPos(10, 64, 2)), "Coffee Maker", "coffee_maker");
        JsonObject config = DiscoveryPublisher.deviceConfig(SmartPlug.TYPE, plug, TOPICS, IDENTITY);
        assertGolden("plug", config);
        JsonObject outlet = config.getAsJsonObject("cmps").getAsJsonObject("plug");
        check(outlet.get("name").isJsonNull() && outlet.get("device_class").getAsString().equals("outlet"), "The plug's outlet switch is its primary entity");
        check(config.getAsJsonObject("cmps").keySet().equals(Set.of("plug", "power_on_behaviour", "location")), "Plug entities: " + config.getAsJsonObject("cmps").keySet());
        // M3-12: a plug mirroring the real coffee machine's switch (M3 plan D6).
        plug.setTwin(Binding.of(SmartPlug.TWIN.sourceEnd("switch.coffee_machine")).withControl(true));
        JsonObject plugMirrored = DiscoveryPublisher.deviceConfig(SmartPlug.TYPE, plug, TOPICS, IDENTITY);
        assertGolden("plug_mirrored", plugMirrored);
        check(!plugMirrored.toString().contains("coffee_machine"), "A mirrored plug's discovery never names its entity");
        plug.setTwin(null);
        check(DiscoveryPublisher.deviceConfig(SmartPlug.TYPE, plug, TOPICS, IDENTITY).equals(config), "Unbinding the plug gives its full golden message again");

        JsonObject group = DiscoveryPublisher.groupConfig(SmartPlug.TYPE, new DeviceGroup(UUID.randomUUID(), "Kitchen Plugs", "kitchen_plugs"), TOPICS, IDENTITY);
        check(group.getAsJsonObject("cmps").keySet().equals(Set.of("plug", "power_on_behaviour"))
                        && group.getAsJsonObject("cmps").getAsJsonObject("plug").get("device_class").getAsString().equals("outlet"),
                "A plug group has the plug's command entities, the outlet among them: " + group.getAsJsonObject("cmps").keySet());
    }

    /**
     * The Display Panel (M3-15) against its golden files: its message as a Home Assistant text of at most 255 characters,
     * the comparator level and the location as disabled diagnostics; bound, the reduced message without the entity.
     */
    private static void displayPanel() throws IOException {
        UUID panelId = UUID.fromString("d15e0a7e-1b2c-4d3e-8f40-5a6b7c8d9e0f");
        DeviceRecord panel = new DeviceRecord(panelId, DisplayPanel.TYPE.id(), GlobalPos.of(Level.OVERWORLD, new BlockPos(12, 64, 2)), "Laundry Panel", "laundry_panel");
        JsonObject config = DiscoveryPublisher.deviceConfig(DisplayPanel.TYPE, panel, TOPICS, IDENTITY);
        assertGolden("display_panel", config);
        JsonObject components = config.getAsJsonObject("cmps");
        check(components.keySet().equals(Set.of("message", "level", "location")), "Panel entities: " + components.keySet());
        JsonObject message = components.getAsJsonObject("message");
        check(message.get("p").getAsString().equals("text") && message.get("max").getAsInt() == 255 && message.get("name").isJsonNull()
                        && message.get("command_topic").getAsString().equals("mineqtt/world_7f3a/laundry_panel/set/message"),
                "The message is the panel's primary text entity of at most 255 characters, set on its own topic");
        panel.setTwin(Binding.of(DisplayPanel.TWIN.sourceEnd("sensor.washer_status")));
        JsonObject mirrored = DiscoveryPublisher.deviceConfig(DisplayPanel.TYPE, panel, TOPICS, IDENTITY);
        assertGolden("display_panel_mirrored", mirrored);
        check(!mirrored.toString().contains("washer_status"), "A mirrored panel's discovery never names its entity");
    }

    /** The Presence Sensor (§4.4) against its golden file: occupancy, count and its three settings. */
    private static void presence() throws IOException {
        UUID sensorId = UUID.fromString("5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f70812");
        DeviceRecord sensor = new DeviceRecord(sensorId, PresenceSensor.TYPE.id(), GlobalPos.of(Level.OVERWORLD, new BlockPos(8, 64, 2)), "Hallway Sensor", "hallway_sensor");
        JsonObject config = DiscoveryPublisher.deviceConfig(PresenceSensor.TYPE, sensor, TOPICS, IDENTITY);
        assertGolden("presence", config);
        JsonObject components = config.getAsJsonObject("cmps");
        check(components.keySet().equals(Set.of("occupancy", "count", "detect", "range", "clear_delay", "location")), "Presence entities: " + components.keySet());
        check(components.getAsJsonObject("detect").getAsJsonArray("options").toString().equals("[\"players\",\"mobs\",\"all\"]"), "detect selects players, mobs or all");
        check(components.getAsJsonObject("range").get("command_topic").getAsString().equals("mineqtt/world_7f3a/hallway_sensor/set/range")
                && components.getAsJsonObject("clear_delay").get("entity_category").getAsString().equals("config"), "The settings are config entities with commands");
    }

    /**
     * The Climate Sensor (§4.4, M2-19) against its golden file: temperature, humidity and illuminance with their device
     * classes and units, its interval, and the biome as a diagnostic. The Raw Link's reading is no Home Assistant entity.
     */
    private static void climate() throws IOException {
        UUID sensorId = UUID.fromString("c1a7e000-4d2b-4f3a-9e8d-7c6b5a493821");
        DeviceRecord sensor = new DeviceRecord(sensorId, ClimateSensor.TYPE.id(), GlobalPos.of(Level.OVERWORLD, new BlockPos(6, 64, 2)), "Greenhouse Climate",
                "greenhouse_climate");
        JsonObject config = DiscoveryPublisher.deviceConfig(ClimateSensor.TYPE, sensor, TOPICS, IDENTITY);
        assertGolden("climate", config);
        JsonObject components = config.getAsJsonObject("cmps");
        check(components.keySet().equals(Set.of("temperature", "humidity", "illuminance", "interval", "biome", "location")), "Climate entities: " + components.keySet());
        check(components.getAsJsonObject("temperature").get("unit_of_measurement").getAsString().equals("°C")
                && components.getAsJsonObject("humidity").get("device_class").getAsString().equals("humidity")
                && components.getAsJsonObject("illuminance").get("unit_of_measurement").getAsString().equals("lx"), "Device classes and units");
    }

    /**
     * The Wall Switch (§4.4, M2-22) against its golden file: its event entity as the primary entity, and a device trigger
     * per gesture and button whose template reads the event's {@code button}.
     */
    private static void wallSwitch() throws IOException {
        UUID switchId = UUID.fromString("a11c5e7d-2b4f-4a6e-8c1d-0f9e8d7c6b5a");
        DeviceRecord record = new DeviceRecord(switchId, WallSwitch.TYPE.id(), GlobalPos.of(Level.OVERWORLD, new BlockPos(4, 64, 2)), "Hall Switch", "hall_switch");
        JsonObject config = DiscoveryPublisher.deviceConfig(WallSwitch.TYPE, record, TOPICS, IDENTITY);
        assertGolden("wall_switch", config);
        JsonObject components = config.getAsJsonObject("cmps");
        check(components.keySet().equals(Set.of("action", "button_short_press_left", "button_short_press_right", "button_double_press_left",
                "button_double_press_right", "button_long_press_left", "button_long_press_right", "location")), "Wall Switch entities: " + components.keySet());
        JsonObject action = components.getAsJsonObject("action");
        check(action.get("name").isJsonNull() && action.get("device_class").getAsString().equals("button")
                && action.getAsJsonArray("event_types").toString().equals("[\"press\",\"double_press\",\"hold\"]"), "The event entity is the primary entity");
        JsonObject hold = components.getAsJsonObject("button_long_press_right");
        expect(hold, "topic", "mineqtt/world_7f3a/hall_switch/action");
        expect(hold, "payload", "hold_right");
        expect(hold, "value_template", "{{ value_json.event_type }}_{{ value_json.button }}");
        check(DiscoveryPublisher.groupConfig(WallSwitch.TYPE, new DeviceGroup(UUID.randomUUID(), "G", "g"), TOPICS, IDENTITY).getAsJsonObject("cmps").isEmpty(),
                "A Wall Switch has no command entity a group could carry");
    }

    /** Every entity kind: the generated templates, command topics and categories. */
    private static void everyEntityKind() {
        Field<Integer> power = Field.intRange("power", 0, 15, Access.READ_WRITE);
        Field<Mode> mode = Field.enumOf("mode", Mode.class, Access.READ_WRITE);
        Field<Boolean> powered = Field.bool("powered", Access.READ);
        Field<String> label = Field.text("label", Access.READ_WRITE);
        Field<Integer> items = Field.intRange("items", 0, 64, Access.READ);
        DeviceType type = DeviceType.builder(Identifier.fromNamespaceAndPath("mineqtt_check", "everything"))
                .model("Everything")
                .field(power).field(mode).field(powered).field(label).field(items).field(CommonFields.LOCATION)
                .entity(Ha.switchOf("output", power).on(15).off(0).name(null))
                .entity(Ha.number("power_level", power).range(0, 15).slider().unit("level"))
                .entity(Ha.select("mode", mode).config())
                .entity(Ha.binarySensor("powered", powered).deviceClass("power"))
                .entity(Ha.sensor("items", items).stateClass("measurement"))
                .entity(Ha.sensor("location", CommonFields.LOCATION).diagnostic().disabledByDefault())
                .entity(Ha.event("pulse", "pulse"))
                .entity(Ha.button("trigger_now"))
                .entity(Ha.text("label", label))
                .entity(Ha.notify("message"))
                .entity(Ha.deviceTrigger("button_short_press", "left", "press", "button"))
                // Every command and event entity has its declaration (M2-4): no discovery key depends on them.
                .action(Action.state()).action(Action.press("trigger_now")).action(new Action("message", PortType.text(255), false))
                .event(EventSpec.of("pulse")).event(EventSpec.of("press").attribute("button", PortType.enumOf(List.of("left"))))
                .build();
        DeviceRecord record = new DeviceRecord(LAMP_ID, type.id(), GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO), "All", "all");
        JsonObject config = DiscoveryPublisher.deviceConfig(type, record, TOPICS, IDENTITY);
        JsonObject components = config.getAsJsonObject("cmps");
        String state = "mineqtt/world_7f3a/all";

        JsonObject output = components.getAsJsonObject("output");
        expect(output, "p", "switch");
        check(output.get("name").isJsonNull(), "The primary entity is named after the device");
        expect(output, "value_template", "{{ 'OFF' if value_json.power == 0 else 'ON' }}");
        expect(output, "command_topic", state + "/set");
        expect(output, "payload_on", "{\"state\":\"ON\"}");
        expect(output, "state_on", "ON");
        check(!output.get("retain").getAsBoolean(), "Commands are never retained");

        JsonObject number = components.getAsJsonObject("power_level");
        expect(number, "command_topic", state + "/set/power");
        expect(number, "value_template", "{{ value_json.power }}");
        expect(number, "mode", "slider");
        expect(number, "unit_of_measurement", "level");
        check(number.get("max").getAsInt() == 15 && number.get("name").getAsString().equals("Power level"), "Number range and default name");

        JsonObject select = components.getAsJsonObject("mode");
        expect(select, "command_topic", state + "/set/mode");
        expect(select, "entity_category", "config");
        check(select.getAsJsonArray("options").toString().equals("[\"switch\",\"level\"]"), "Select options are the serialized names");

        expect(components.getAsJsonObject("powered"), "value_template", "{{ 'ON' if value_json.powered else 'OFF' }}");
        check(!components.getAsJsonObject("powered").has("retain"), "A sensor commands nothing");
        expect(components.getAsJsonObject("items"), "value_template", "{{ value_json['items'] }}");
        expect(components.getAsJsonObject("location"), "value_template", "{{ value_json.location.dimension }} {{ value_json.location.pos | join(',') }}");
        expect(components.getAsJsonObject("location"), "entity_category", "diagnostic");
        check(!components.getAsJsonObject("location").get("enabled_by_default").getAsBoolean(), "Disabled by default");

        JsonObject event = components.getAsJsonObject("pulse");
        expect(event, "state_topic", state + "/action");
        check(event.getAsJsonArray("event_types").toString().equals("[\"pulse\"]"), "Event types");

        expect(components.getAsJsonObject("trigger_now"), "command_topic", state + "/set/trigger_now");
        expect(components.getAsJsonObject("trigger_now"), "payload_press", "PRESS");
        expect(components.getAsJsonObject("label"), "command_topic", state + "/set/label");
        check(components.getAsJsonObject("label").get("max").getAsInt() == 255, "Text length");
        expect(components.getAsJsonObject("message"), "command_topic", state + "/set/message");

        JsonObject trigger = components.getAsJsonObject("button_short_press_left");
        expect(trigger, "p", "device_automation");
        expect(trigger, "automation_type", "trigger");
        expect(trigger, "topic", state + "/action");
        expect(trigger, "type", "button_short_press");
        expect(trigger, "subtype", "left");
        expect(trigger, "payload", "press_left");
        expect(trigger, "value_template", "{{ value_json.event_type }}_{{ value_json.button }}");
        check(!trigger.has("unique_id") && !trigger.has("name"), "A device trigger is no entity");

        JsonArray avty = config.getAsJsonArray("avty");
        check(avty.size() == 2 && avty.get(1).getAsJsonObject().get("t").getAsString().equals(state + "/availability"), "Bridge and device availability");
        expect(config, "avty_mode", "all");
        check(config.getAsJsonObject("dev").get("via_device").getAsString().equals(IDENTITY.bridgeObjectId()), "Every device is behind the bridge");

        JsonObject group = DiscoveryPublisher.groupConfig(type, new DeviceGroup(UUID.randomUUID(), "G", "g"), TOPICS, IDENTITY).getAsJsonObject("cmps");
        check(group.keySet().equals(Set.of("output", "power_level", "mode", "trigger_now", "label", "message")),
                "A group gets only command entities, no sensors, events or triggers: " + group.keySet());
    }

    // §4.1, with the device type as the model id.
    private static final String DESIGN_LAMP = """
            {
              "dev": {
                "ids": ["mineqtt_3f9c2d1e8a7b4c6d9e0f112233445566"],
                "name": "Kitchen Lamp",
                "mf": "MineQTT",
                "mdl": "Smart Lamp",
                "mdl_id": "mineqtt:smart_lamp",
                "sw": "2.0.0",
                "via_device": "mineqtt_bridge_5b0e9a7c3d2f41e8a6c4b1d09f8e7a63"
              },
              "o": { "name": "MineQTT", "sw": "2.0.0", "url": "https://github.com/OddHero/Mineqtt" },
              "avty": [
                { "t": "mineqtt/world_7f3a/bridge/state" },
                { "t": "mineqtt/world_7f3a/kitchen_lamp/availability" }
              ],
              "avty_mode": "all",
              "qos": 1,
              "cmps": {
                "light": {
                  "p": "light",
                  "unique_id": "mineqtt_3f9c2d1e8a7b4c6d9e0f112233445566_light",
                  "name": null,
                  "schema": "json",
                  "state_topic": "mineqtt/world_7f3a/kitchen_lamp",
                  "command_topic": "mineqtt/world_7f3a/kitchen_lamp/set",
                  "brightness": true,
                  "brightness_scale": 255,
                  "supported_color_modes": ["rgb", "color_temp"],
                  "color_temp_kelvin": true,
                  "min_kelvin": 2000,
                  "max_kelvin": 6500,
                  "effect": true,
                  "effect_list": ["off", "pulse", "candle"],
                  "flash": false,
                  "retain": false
                },
                "light_level": {
                  "p": "sensor",
                  "unique_id": "mineqtt_3f9c2d1e8a7b4c6d9e0f112233445566_light_level",
                  "name": "Emitted light level",
                  "state_topic": "mineqtt/world_7f3a/kitchen_lamp",
                  "value_template": "{{ value_json.light_level }}",
                  "state_class": "measurement",
                  "entity_category": "diagnostic",
                  "enabled_by_default": false
                },
                "location": {
                  "p": "sensor",
                  "unique_id": "mineqtt_3f9c2d1e8a7b4c6d9e0f112233445566_location",
                  "name": "Location",
                  "state_topic": "mineqtt/world_7f3a/kitchen_lamp",
                  "value_template": "{{ value_json.location.dimension }} {{ value_json.location.pos | join(',') }}",
                  "entity_category": "diagnostic",
                  "enabled_by_default": false
                }
              }
            }""";

    private static void assertGolden(String name, JsonObject generated) throws IOException {
        try (InputStream in = DiscoveryGoldenTest.class.getResourceAsStream("/golden/" + name + ".json")) {
            check(in != null, "Missing golden file golden/" + name + ".json");
            JsonElement golden = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            assertSame("golden/" + name + ".json", golden, generated);
        }
    }

    private static void assertSame(String what, JsonElement expected, JsonElement generated) {
        // Gson's objects compare as maps, so key order does not matter; the message shows both sorted.
        if (!expected.equals(generated)) {
            throw new AssertionError(what + " differs.\nExpected:\n" + PRETTY.toJson(canonical(expected))
                    + "\nGenerated:\n" + PRETTY.toJson(canonical(generated)));
        }
    }

    private static JsonElement canonical(JsonElement element) {
        if (element.isJsonObject()) {
            Map<String, JsonElement> sorted = new TreeMap<>();
            element.getAsJsonObject().entrySet().forEach(entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));
            JsonObject object = new JsonObject();
            sorted.forEach(object::add);
            return object;
        }
        if (element.isJsonArray()) {
            JsonArray array = new JsonArray();
            element.getAsJsonArray().forEach(item -> array.add(canonical(item)));
            return array;
        }
        return element;
    }

    static List<String> ids(JsonObject config) {
        return config.getAsJsonObject("dev").getAsJsonArray("ids").asList().stream().map(JsonElement::getAsString).toList();
    }

    private static void expect(JsonObject object, String key, String value) {
        check(object.has(key) && object.get(key).getAsString().equals(value), "Expected " + key + "=" + value + " in " + object);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}

package art.rehra.mineqtt.legacy;

import art.rehra.mineqtt.blocks.entities.MotionSensorBlockEntity;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceGroup;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceRegistry;
import art.rehra.mineqtt.device.DeviceTypes;
import art.rehra.mineqtt.device.LegacyAdoptionCheck;
import art.rehra.mineqtt.device.LinkAdoptionCheck;
import art.rehra.mineqtt.device.RawLinkSpec;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.UnloadedPolicy;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.device.ValueCodecs.LightState;
import art.rehra.mineqtt.device.types.PresenceSensor;
import art.rehra.mineqtt.device.types.PresenceSensor.Presence;
import art.rehra.mineqtt.device.types.RedstoneInput;
import art.rehra.mineqtt.device.types.RedstoneOutput;
import art.rehra.mineqtt.device.types.RedstoneOutput.Output;
import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.mqtt.BlockStatePersistence;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The v1 migration (design §9), run by Gradle's verifyLegacyNbtMigration task. The v1 data is built exactly as v1's
 * block entities wrote it (the {@code main} branch's {@code saveAdditional}), then read back:
 * <ul>
 *     <li>the snapshot: the combined topic as v1's {@code getCombinedTopic()} made it, owner, private mode;</li>
 *     <li>the RGB keys: {@code Target*} 0–15 times 17, {@code Brightness}, {@code Lit};</li>
 *     <li>the v2 output: the Smart Lamp's state JSON and NBT, the adopted Raw Link with its grandfather flag and echo,
 *     placed copies that lose both (through the vanilla item-data merge), and v1 lamps without an owner;</li>
 *     <li>the Redstone Output and Input: state JSON, commands and previews, and the adopted links of an emitter with
 *     custom payload slots and of a publisher in private mode, also from placed items;</li>
 *     <li>the Presence Sensor: state JSON, settings, occupancy with its clear delay, v1's area and spawn-egg filters,
 *     and the adopted link of a v1 motion sensor with eggs in its filter slots 2 and 3, also from a placed item;</li>
 *     <li>the per-topic states of {@code mineqtt_topic_states.json} as records and legacy groups, and the attach-time
 *     decisions on them: which record a lamp takes over, which v1 light it keeps, which legacy group it joins.</li>
 * </ul>
 */
public final class LegacyNbtV1Test {
    // The built-in registries, which the item codecs need; set once Minecraft is bootstrapped.
    private static HolderLookup.Provider REGISTRIES;

    static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        // Item stacks need their items' default components, which a server binds when it loads its data packs; the
        // common ones are enough for the items the slots hold here.
        for (Item item : List.of(Items.PAPER, Items.STONE, Items.REDSTONE_LAMP, Items.ZOMBIE_SPAWN_EGG, Items.PIG_SPAWN_EGG)) {
            item.builtInRegistryHolder().bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
        }
        DeviceTypes.register(SmartLamp.TYPE);
        DeviceTypes.register(RedstoneOutput.TYPE);
        DeviceTypes.register(RedstoneInput.TYPE);
        DeviceTypes.register(PresenceSensor.TYPE);
        combinedTopics();
        lampKeys();
        smartLampState();
        redstoneOutputState();
        redstoneInputState();
        presenceState();
        presenceFilters();
        adoption();
        positionKeys();
        topicNames();
        topicStates();
        System.out.println("Legacy NBT migration checks passed.");
    }

    /** The topic every v1 device computed from its slots, owner and private mode. */
    private static void combinedTopics() {
        expectTopic(paper("home/kitchen"), ItemStack.EMPTY, "Steve", false, "home/kitchen");
        expectTopic(paper("home"), new ItemStack(Items.STONE), "Steve", false, "home/minecraft/stone");
        expectTopic(new ItemStack(Items.REDSTONE_LAMP), paper("hall"), "", false, "minecraft/redstone_lamp/hall");
        expectTopic(paper("lamp"), ItemStack.EMPTY, "Steve", true, "Steve/lamp");
        expectTopic(ItemStack.EMPTY, ItemStack.EMPTY, "Steve", true, "Steve");
        expectTopic(paper("lamp"), ItemStack.EMPTY, "", true, "lamp");
        expectTopic(ItemStack.EMPTY, paper("sub"), "Steve", false, "");

        ValueInput emitter = input(v1Device(paper("garage/door"), ItemStack.EMPTY, paper("OPEN"), paper("CLOSE"), "Alex", false));
        LegacyNbtV1.Snapshot snapshot = LegacyNbtV1.read(emitter);
        check(snapshot.combinedTopic().equals("garage/door") && snapshot.ownerName().equals("Alex") && !snapshot.privateMode(), "Emitter snapshot: " + snapshot);
        check(new ValueCodecs.CustomPayloads("OPEN", "CLOSE").equals(snapshot.custom()), "Slots 2 and 3 are the custom payloads: " + snapshot.custom());
        check(LegacyNbtV1.legacyUniqueId("home/Küche lamp#1").equals("home_K_che_lamp_1"), "The v1 unique_id keeps only [a-zA-Z0-9_-]");
    }

    private static void expectTopic(ItemStack base, ItemStack sub, String owner, boolean privateMode, String expected) {
        LegacyNbtV1.Snapshot snapshot = LegacyNbtV1.read(input(v1Device(base, sub, ItemStack.EMPTY, ItemStack.EMPTY, owner, privateMode)));
        check(snapshot.combinedTopic().equals(expected), "Combined topic '" + snapshot.combinedTopic() + "', v1 made '" + expected + "'");
        check(LegacyNbtV1.combinedTopic(base, sub, privateMode, owner).equals(expected), "The slots' topic rules are v1's");
    }

    /** {@code Target*} times 17, {@code Brightness}, {@code Lit}; v1's plain colours only set the displayed colour. */
    private static void lampKeys() {
        LightState json = LegacyNbtV1.lampState(input(v1Lamp(paper("home/kitchen"), 15, 8, 0, 12, 6, 0, 200, true)));
        check(json.equals(new LightState(true, 200, new ValueCodecs.Rgb(255, 136, 0), null)), "Target colour, brightness and lit: " + json);

        LightState plain = LegacyNbtV1.lampState(input(v1Lamp(paper("home/kitchen"), 0, 0, 0, 10, 0, 5, 255, true)));
        check(plain.color().equals(new ValueCodecs.Rgb(170, 0, 85)), "Without a target colour the displayed one counts: " + plain);

        LightState defaults = LegacyNbtV1.lampState(input(new CompoundTag()));
        check(defaults.equals(new LightState(false, 255, new ValueCodecs.Rgb(0, 0, 0), null)), "v1's defaults: " + defaults);

        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, REGISTRIES);
        LegacyNbtV1.writeLamp(output, json);
        CompoundTag written = output.buildResult();
        check(written.getIntOr("TargetGreen", -1) == 8 && written.getIntOr("Green", -1) == 6 && written.getByteOr("Lit", (byte) 0) == 1,
                "Written back with v1's keys: " + written);
        check(LegacyNbtV1.lampState(input(written)).equals(json), "A lamp saved before its adoption reads back the same");
    }

    /** The v2 Smart Lamp: state JSON, NBT round trip, light level, preview of a queued command and the v1 echo. */
    private static void smartLampState() {
        LightState red = new LightState(true, 200, new ValueCodecs.Rgb(255, 120, 0), null);
        JsonObject state = SmartLamp.state(red, GlobalPos.of(Level.OVERWORLD, new BlockPos(120, 64, -35)));
        check(state.equals(JsonParser.parseString("""
                {"state":"ON","brightness":200,"color_mode":"rgb","color":{"r":255,"g":120,"b":0},"light_level":12,
                 "location":{"dimension":"minecraft:overworld","pos":[120,64,-35]}}""")), "§3.3 lamp state: " + state);
        LightState warm = red.merge(JsonParser.parseString("{\"color_temp_kelvin\":2700}").getAsJsonObject());
        JsonObject warmState = SmartLamp.command(warm);
        check(warmState.get("color_mode").getAsString().equals("color_temp") && warmState.get("color_temp").getAsInt() == 2700
                && warmState.get("color_temp_kelvin").getAsInt() == 2700, "Kelvin as Home Assistant reads it and as documented: " + warmState);

        for (LightState light : List.of(red, warm, LightState.DEFAULT)) {
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, REGISTRIES);
            output.store("state", SmartLamp.LIGHT.codec(), light);
            Optional<LightState> read = input(output.buildResult()).read("state", SmartLamp.LIGHT.codec());
            check(read.equals(Optional.of(light)), "The lamp state round-trips through NBT: " + light + " -> " + read);
        }

        check(SmartLamp.lightLevel(red) == 12, "Brightness 200 gives light level 12");
        check(SmartLamp.lightLevel(new LightState(true, 128, ValueCodecs.Rgb.WHITE, null)) == 8, "Brightness 128 gives light level 8");
        check(SmartLamp.lightLevel(new LightState(true, 1, ValueCodecs.Rgb.WHITE, null)) == 1, "A lamp that is on gives some light");
        check(SmartLamp.lightLevel(new LightState(false, 255, ValueCodecs.Rgb.WHITE, null)) == 0, "A lamp that is off gives none");

        JsonObject toggled = SmartLamp.TYPE.preview(state, JsonParser.parseString("{\"state\":\"TOGGLE\"}").getAsJsonObject());
        check(toggled.get("state").getAsString().equals("OFF") && toggled.get("light_level").getAsInt() == 0
                && toggled.get("brightness").getAsInt() == 200 && toggled.has("location"), "An unloaded lamp previews a toggle: " + toggled);
        JsonObject tempered = SmartLamp.TYPE.preview(state, JsonParser.parseString("{\"color_temp\":4000}").getAsJsonObject());
        check(tempered.get("color_temp_kelvin").getAsInt() == 4000, "A preview switches to color_temp mode: " + tempered);
        JsonObject back = SmartLamp.TYPE.preview(tempered, JsonParser.parseString("{\"color\":{\"r\":0,\"g\":0,\"b\":255}}").getAsJsonObject());
        check(!back.has("color_temp") && !back.has("color_temp_kelvin") && back.get("color_mode").getAsString().equals("rgb"),
                "A preview back to rgb drops the temperature: " + back);
        check(SmartLamp.TYPE.writableState(state).keySet().equals(Set.of("state", "brightness", "color_mode", "color")),
                "A group shares the light's keys only: " + SmartLamp.TYPE.writableState(state));

        JsonObject echo = SmartLamp.TYPE.rawLink().orElseThrow().echo(state);
        check(echo.equals(JsonParser.parseString("{\"state\":\"ON\",\"brightness\":200,\"color\":{\"r\":255,\"g\":120,\"b\":0}}")),
                "The v1 echo has v1's keys only: " + echo);
        check(!SmartLamp.TYPE.rawLink().orElseThrow().acceptRetained(), "A lamp never takes a retained raw value (v1: the world file wins)");
    }

    private static void adoption() {
        CompoundTag lamp = v1Lamp(paper("home/kitchen"), 15, 0, 0, 15, 0, 0, 255, true);
        LinkAdoptionCheck.adoptedOnChunkLoad(REGISTRIES, lamp, "home/kitchen");
        LinkAdoptionCheck.adoptedWithoutOwner(REGISTRIES, lamp, "home/kitchen");
        LinkAdoptionCheck.placedFromItem(REGISTRIES, lamp, "home/kitchen", new LightState(true, 255, new ValueCodecs.Rgb(255, 0, 0), null));
        LinkAdoptionCheck.withoutTopic(REGISTRIES, v1Lamp(ItemStack.EMPTY, 0, 0, 0, 0, 0, 0, 255, false));

        CompoundTag emitter = v1Emitter(v1Device(paper("garage/door"), ItemStack.EMPTY, paper("OPEN"), paper("CLOSE"), "Steve", false));
        LinkAdoptionCheck.emitterAdopted(REGISTRIES, emitter, "garage/door", "OPEN", "CLOSE");
        CompoundTag publisher = v1Device(paper("garage"), paper("bell"), paper("RING"), ItemStack.EMPTY, "Steve", true);
        LinkAdoptionCheck.publisherAdopted(REGISTRIES, publisher, "Steve/garage/bell", "RING");
        LinkAdoptionCheck.redstonePlacedFromItem(REGISTRIES, emitter, publisher);
        CompoundTag motion = v1Motion(v1Device(paper("home/hall"), ItemStack.EMPTY, spawnEgg(Items.ZOMBIE_SPAWN_EGG, EntityTypes.ZOMBIE),
                spawnEgg(Items.PIG_SPAWN_EGG, EntityTypes.PIG), "Steve", false), true, 2);
        LinkAdoptionCheck.motionAdopted(REGISTRIES, motion, "home/hall",
                new ValueCodecs.CustomPayloads("minecraft/zombie_spawn_egg", "minecraft/pig_spawn_egg"));
        LinkAdoptionCheck.motionPlacedFromItem(REGISTRIES, motion);
        LinkAdoptionCheck.slotsFollowed(REGISTRIES, motion, emitter, "OPEN", "CLOSE");
        LegacyAdoptionCheck.run();
    }

    /**
     * The Redstone Output (§3.3): its state JSON, switching (ON restores the last level), TOGGLE, switch mode, its
     * power-on behaviour, the preview of a queued command, and its state in NBT.
     */
    private static void redstoneOutputState() {
        Output off = Output.DEFAULT;
        GlobalPos pos = GlobalPos.of(Level.OVERWORLD, new BlockPos(4, 64, 2));
        JsonObject state = outputState(off.apply(command("{\"power\":15}")).orElseThrow(), pos);
        check(state.equals(JsonParser.parseString("""
                {"state":"ON","power":15,"mode":"level","power_on_behaviour":"previous","last_level":15,
                 "location":{"dimension":"minecraft:overworld","pos":[4,64,2]}}""")), "§3.3 output state: " + state);

        Output seven = off.apply(command("{\"power\":7}")).orElseThrow();
        check(seven.power() == 7 && seven.lastLevel() == 7 && seven.on(), "A level: " + seven);
        Output switchedOff = seven.apply(command("{\"state\":\"OFF\"}")).orElseThrow();
        check(switchedOff.power() == 0 && switchedOff.lastLevel() == 7, "OFF keeps the level ON restores: " + switchedOff);
        check(switchedOff.apply(command("{\"state\":\"ON\"}")).orElseThrow().power() == 7, "ON restores the last level above 0");
        check(off.apply(command("{\"state\":\"ON\"}")).orElseThrow().power() == 15, "An output that never had a level turns on at 15");
        check(switchedOff.apply(command("{\"state\":\"TOGGLE\"}")).orElseThrow().power() == 7
                && seven.apply(command("{\"state\":\"TOGGLE\"}")).orElseThrow().power() == 0, "TOGGLE switches between off and the last level");
        check(switchedOff.apply(command("{\"state\":\"ON\",\"power\":4}")).orElseThrow().power() == 4, "ON with a level turns on at that level");
        check(seven.apply(command("{\"state\":true}")).orElseThrow().power() == 7 && seven.apply(command("{\"state\":\"0\"}")).orElseThrow().power() == 0,
                "Boolean spellings of the state");
        check(seven.apply(command("{\"power\":99}")).orElseThrow().power() == 15 && seven.apply(command("{\"power\":\"-3\"}")).orElseThrow().power() == 0,
                "Levels are clamped");
        check(seven.apply(command("{\"brightness\":7}")).isEmpty() && seven.apply(command("{\"state\":\"maybe\"}")).isEmpty(),
                "A command for something else is no output command");

        Output digital = seven.apply(command("{\"mode\":\"switch\"}")).orElseThrow();
        check(digital.mode() == RedstoneOutput.Mode.SWITCH && digital.power() == 15, "Switch mode turns any level into full power: " + digital);
        check(digital.apply(command("{\"power\":3}")).orElseThrow().power() == 15, "and keeps doing so");
        check(digital.apply(command("{\"mode\":\"LEVEL\",\"power\":3}")).orElseThrow().power() == 3, "Level mode, read case-insensitively");

        Output powerOnOff = seven.apply(command("{\"power_on_behaviour\":\"off\"}")).orElseThrow();
        check(powerOnOff.poweredOn().power() == 0 && powerOnOff.poweredOn().lastLevel() == 7, "Power-on off starts at 0");
        Output powerOnOn = switchedOff.apply(command("{\"power_on_behaviour\":\"on\"}")).orElseThrow();
        check(powerOnOn.poweredOn().power() == 7, "Power-on on starts at the last level");
        check(seven.poweredOn().equals(seven), "Power-on previous keeps the level, as v1 emitters did");

        JsonObject unloaded = outputState(switchedOff, pos);
        JsonObject switchedOn = RedstoneOutput.TYPE.preview(unloaded, JsonParser.parseString("{\"state\":\"ON\"}").getAsJsonObject());
        check(switchedOn.equals(outputState(seven, pos)), "An unloaded output previews ON at its last level, keys in order: " + switchedOn);
        JsonObject toggled = RedstoneOutput.TYPE.preview(outputState(seven, pos), JsonParser.parseString("{\"state\":\"TOGGLE\"}").getAsJsonObject());
        check(toggled.get("power").getAsInt() == 0 && toggled.get("state").getAsString().equals("OFF") && toggled.has("location"), "and a toggle: " + toggled);
        check(RedstoneOutput.TYPE.preview(unloaded, JsonParser.parseString("{\"color\":\"red\"}").getAsJsonObject()).equals(unloaded),
                "A preview ignores what is no output command");
        check(RedstoneOutput.TYPE.writableState(state).keySet().equals(Set.of("power", "mode", "power_on_behaviour")), "A group shares the writable keys only");
        redstoneOutputPowerOn(pos, seven, switchedOff);
        redstoneOutputGroupToggle(state);
        check(RedstoneOutput.TYPE.rawLink().orElseThrow().grammar() == ValueCodecs.REDSTONE, "The raw grammar is v1's redstone grammar");

        for (Output output : List.of(off, seven, switchedOff, digital, powerOnOn)) {
            TagValueOutput nbt = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, REGISTRIES);
            nbt.store("state", Output.CODEC, output);
            Optional<Output> read = input(nbt.buildResult()).read("state", Output.CODEC);
            check(read.equals(Optional.of(output)), "The output round-trips through NBT: " + output + " -> " + read);
        }
        CompoundTag garbage = new CompoundTag();
        CompoundTag garbageState = new CompoundTag();
        garbageState.putString("mode", "sideways");
        garbageState.putInt("power", 4);
        garbage.put("state", garbageState);
        check(input(garbage).read("state", Output.CODEC).equals(Optional.of(Output.DEFAULT.apply(command("{\"power\":4}")).orElseThrow())),
                "An unreadable value keeps its default, the rest is read");
    }

    /**
     * An unloaded output starts with its server too (§7.2): its record is powered on at server start, and a command
     * queued afterwards is diffed against that, so it survives the block entity's own power-on at attach.
     */
    private static void redstoneOutputPowerOn(GlobalPos pos, Output seven, Output switchedOff) {
        JsonObject offAtSeven = outputState(seven.apply(command("{\"power_on_behaviour\":\"off\"}")).orElseThrow(), pos);
        JsonObject started = RedstoneOutput.TYPE.poweredOn(offAtSeven);
        check(started.get("power").getAsInt() == 0 && started.get("state").getAsString().equals("OFF") && started.get("last_level").getAsInt() == 7
                        && started.get("location").equals(offAtSeven.get("location")) && offAtSeven.get("power").getAsInt() == 7,
                "Power-on off: the record starts at 0 with its last level and location; the stopped state is not changed: " + started);
        JsonObject turnedOn = RedstoneOutput.TYPE.preview(started, json("{\"state\":\"ON\"}"));
        JsonObject queuedOn = RedstoneOutput.TYPE.difference(started, turnedOn);
        check(queuedOn.equals(json("{\"power\":7}")), "An ON queued after the power-on is kept: " + queuedOn);
        Output attached = Output.of(offAtSeven).poweredOn().apply(new CommandReader(queuedOn)).orElseThrow();
        check(attached.power() == 7, "and turns the output on after the block entity's own power-on: " + attached);

        JsonObject onAtZero = outputState(switchedOff.apply(command("{\"power_on_behaviour\":\"on\"}")).orElseThrow(), pos);
        JsonObject startedOn = RedstoneOutput.TYPE.poweredOn(onAtZero);
        check(startedOn.get("power").getAsInt() == 7 && startedOn.get("state").getAsString().equals("ON"), "Power-on on: the record starts at its last level: " + startedOn);
        JsonObject turnedOff = RedstoneOutput.TYPE.preview(startedOn, json("{\"state\":\"OFF\"}"));
        check(RedstoneOutput.TYPE.difference(startedOn, turnedOff).equals(json("{\"power\":0}")), "An OFF queued after it is kept");
        JsonObject previous = outputState(seven, pos);
        check(RedstoneOutput.TYPE.poweredOn(previous).equals(previous), "Power-on previous keeps the record");
    }

    /**
     * A group TOGGLE is resolved against the group's state (§6.6) so every member gets the same target, also for the
     * output, whose group state has no {@code state} key: its power decides, as the Home Assistant switch reads it.
     */
    private static void redstoneOutputGroupToggle(JsonObject memberState) {
        JsonObject groupOn = RedstoneOutput.TYPE.writableState(memberState);
        JsonObject toggle = json("{\"state\":\"TOGGLE\"}");
        check(RedstoneOutput.TYPE.resolveToggles(toggle, groupOn).equals(json("{\"state\":\"OFF\"}")), "An output group that is on toggles off");
        JsonObject groupOff = groupOn.deepCopy();
        groupOff.addProperty("power", 0);
        check(RedstoneOutput.TYPE.resolveToggles(toggle, groupOff).equals(json("{\"state\":\"ON\"}")), "An output group that is off toggles on");
        check(RedstoneOutput.TYPE.resolveToggles(json("{\"state\":\"toggle\",\"mode\":\"switch\"}"), groupOff).equals(json("{\"state\":\"ON\",\"mode\":\"switch\"}")),
                "The rest of the command stays");
        check(RedstoneOutput.TYPE.resolveToggles(json("{\"power\":4}"), groupOn).equals(json("{\"power\":4}")), "A command without a toggle is unchanged");
        check(SmartLamp.TYPE.resolveToggles(toggle, json("{\"state\":\"OFF\",\"brightness\":120}")).equals(json("{\"state\":\"ON\"}")),
                "A lamp group toggles on its state key");
    }

    /** The Redstone Input (§3.3): state JSON, the pulse event and v1's raw payloads. */
    private static void redstoneInputState() {
        JsonObject state = inputState(11, GlobalPos.of(Level.OVERWORLD, new BlockPos(6, 64, 2)));
        check(state.equals(JsonParser.parseString("{\"powered\":true,\"signal\":11,\"location\":{\"dimension\":\"minecraft:overworld\",\"pos\":[6,64,2]}}")),
                "§3.3 input state: " + state);
        check(inputState(0, GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO)).get("powered").getAsBoolean() == false, "No signal, not powered");
        check(RedstoneInput.pulse(11).equals(JsonParser.parseString("{\"event_type\":\"pulse\",\"signal\":11}")), "§3.3 pulse event");
        check(RedstoneInput.rawPayload(true, ValueCodecs.CustomPayloads.NONE).equals("true")
                && RedstoneInput.rawPayload(false, ValueCodecs.CustomPayloads.NONE).equals("false"), "v1's payloads");
        ValueCodecs.CustomPayloads custom = new ValueCodecs.CustomPayloads("OPEN", "CLOSE");
        check(RedstoneInput.rawPayload(true, custom).equals("OPEN") && RedstoneInput.rawPayload(false, custom).equals("CLOSE"), "Custom payloads win");
        check(RedstoneInput.TYPE.unloadedPolicy() == UnloadedPolicy.UNAVAILABLE && !RedstoneInput.TYPE.groupable()
                && RedstoneInput.TYPE.rawLink().orElseThrow().direction() == RawLinkSpec.Direction.OUT, "An input is a sensor with a Raw Link output");
    }

    /**
     * The Presence Sensor (§3.3): its state JSON, settings commands, occupancy with its clear delay, its NBT, v1's
     * motion sensor as a sensor of mobs, and its area, which is v1's box at the default range.
     */
    private static void presenceState() {
        GlobalPos pos = GlobalPos.of(Level.OVERWORLD, new BlockPos(8, 64, 2));
        Presence two = Presence.DEFAULT.scanned(2, 1000);
        JsonObject state = presenceState(two, pos);
        check(state.equals(JsonParser.parseString("""
                {"occupancy":true,"count":2,"detect":"players","range":5,"clear_delay":10,
                 "location":{"dimension":"minecraft:overworld","pos":[8,64,2]}}""")), "§3.3 presence state: " + state);
        check(!state.has("last_seen"), "The time of the last detection is no state");

        check(two.apply(command("{\"detect\":\"mobs\"}")).orElseThrow().detect() == PresenceSensor.Detect.MOBS, "detect is set");
        check(two.apply(command("{\"detect\":\"ALL\",\"range\":3,\"clear_delay\":30}")).orElseThrow()
                .equals(new Presence(PresenceSensor.Detect.ALL, 3, 30, 2, true, 1000)), "All settings at once, the readings stay");
        check(two.apply(command("{\"range\":99}")).orElseThrow().range() == PresenceSensor.MAX_RANGE
                && two.apply(command("{\"range\":\"0\"}")).orElseThrow().range() == PresenceSensor.MIN_RANGE, "The range is clamped to 1-8");
        check(two.apply(command("{\"clear_delay\":-5}")).orElseThrow().clearDelay() == 0
                && two.apply(command("{\"clear_delay\":99999}")).orElseThrow().clearDelay() == PresenceSensor.MAX_CLEAR_DELAY, "The clear delay is clamped");
        check(two.apply(command("{\"detect\":\"sideways\"}")).isEmpty() && two.apply(command("{\"count\":5,\"occupancy\":false}")).isEmpty()
                && two.apply(command("{\"state\":\"ON\"}")).isEmpty(), "Readings and unknown values are no commands");

        Presence held = two.scanned(0, 1000 + 199);
        check(held.occupancy() && held.count() == 0 && held.lastSeen() == 1000, "Occupancy holds for the clear delay, the count does not: " + held);
        check(!held.scanned(0, 1000 + 200).occupancy(), "and clears after it");
        check(held.scanned(1, 1150).equals(new Presence(PresenceSensor.Detect.PLAYERS, 5, 10, 1, true, 1150)), "A new detection restarts the delay");
        Presence instant = two.apply(command("{\"clear_delay\":0}")).orElseThrow();
        check(!instant.scanned(0, 1001).occupancy(), "Without a clear delay occupancy follows the count");
        check(!new Presence(PresenceSensor.Detect.PLAYERS, 5, 10, 1, true, 50_000).scanned(0, 1000).occupancy(),
                "A last detection in the future (data from another world) has expired");
        check(!Presence.DEFAULT.scanned(0, 5).occupancy(), "Nothing seen, not occupied");

        Presence v1 = Presence.ofV1(-1, false);
        check(v1.detect() == PresenceSensor.Detect.MOBS && v1.range() == PresenceSensor.DEFAULT_RANGE && v1.count() == 0 && !v1.occupancy(),
                "A v1 motion sensor counts mobs in v1's box; its count before the first scan is 0: " + v1);
        check(!Presence.ofV1(3, true).scanned(0, 24000).occupancy(), "v1 had no clear delay: its motion ends at the first empty scan");
        check(Presence.DEFAULT.detect() == PresenceSensor.Detect.PLAYERS, "New sensors count players");

        for (Presence presence : List.of(Presence.DEFAULT, two, held, v1, instant)) {
            TagValueOutput nbt = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, REGISTRIES);
            nbt.store("state", Presence.CODEC, presence);
            Optional<Presence> read = input(nbt.buildResult()).read("state", Presence.CODEC);
            check(read.equals(Optional.of(presence)), "The sensor round-trips through NBT: " + presence + " -> " + read);
        }
        CompoundTag garbage = new CompoundTag();
        CompoundTag garbageState = new CompoundTag();
        garbageState.putString("detect", "ghosts");
        garbageState.putInt("range", 3);
        garbage.put("state", garbageState);
        check(input(garbage).read("state", Presence.CODEC).equals(Optional.of(new Presence(PresenceSensor.Detect.PLAYERS, 3, 10, 0, false, 0))),
                "An unreadable value keeps its default, the rest is read");

        BlockPos sensor = new BlockPos(10, 64, -3);
        for (Direction facing : Direction.values()) {
            AABB v1Box = new AABB(sensor).inflate(2.0).move(facing.getStepX() * 2.5, facing.getStepY() * 2.5, facing.getStepZ() * 2.5);
            check(PresenceSensor.area(sensor, facing, PresenceSensor.DEFAULT_RANGE).equals(v1Box), "The default range is v1's box facing " + facing);
        }
        check(PresenceSensor.area(sensor, Direction.EAST, 1).equals(new AABB(10.5, 64, -3, 11.5, 65, -2)), "Range 1: one block from the centre out");
        check(PresenceSensor.area(sensor, Direction.UP, 8).equals(new AABB(6.5, 64.5, -6.5, 14.5, 72.5, 1.5)), "Range 8: an 8 block cube from the centre up");
        check(PresenceSensor.TYPE.unloadedPolicy() == UnloadedPolicy.UNAVAILABLE && !PresenceSensor.TYPE.groupable()
                && PresenceSensor.TYPE.minInterval().toSeconds() == 1, "A presence sensor is a sensor: unavailable while unloaded, 1 s interval");
        RawLinkSpec raw = PresenceSensor.TYPE.rawLink().orElseThrow();
        check(raw.direction() == RawLinkSpec.Direction.OUT && !raw.customPayloads() && !raw.echoes(), "Its Raw Link publishes the count, without custom payloads");
        check(RedstoneInput.TYPE.rawLink().orElseThrow().customPayloads() && RedstoneOutput.TYPE.rawLink().orElseThrow().customPayloads(),
                "Redstone links keep theirs");
    }

    /** Spawn-egg filters choose the mobs a sensor counts; without eggs it counts every mob. */
    private static void presenceFilters() {
        ItemStack zombie = spawnEgg(Items.ZOMBIE_SPAWN_EGG, EntityTypes.ZOMBIE);
        check(MotionSensorBlockEntity.matchesFilters(List.of(ItemStack.EMPTY, ItemStack.EMPTY), EntityTypes.PIG), "No filter, every mob");
        check(MotionSensorBlockEntity.matchesFilters(List.of(ItemStack.EMPTY, zombie), EntityTypes.ZOMBIE)
                && !MotionSensorBlockEntity.matchesFilters(List.of(ItemStack.EMPTY, zombie), EntityTypes.PIG), "An egg counts its mob only");
        check(MotionSensorBlockEntity.matchesFilters(List.of(zombie, spawnEgg(Items.PIG_SPAWN_EGG, EntityTypes.PIG)), EntityTypes.PIG), "Several eggs, any of them");
        check(MotionSensorBlockEntity.matchesFilters(List.of(paper("pig")), EntityTypes.PIG)
                && !MotionSensorBlockEntity.matchesFilters(List.of(paper("pig"), zombie), EntityTypes.PIG), "Other items filter nothing");
        check(MotionSensorBlockEntity.FIRST_FILTER == 2 && MotionSensorBlockEntity.NUM_FILTERS == 5,
                "The sensor reads the slots v1's filter tab showed, 2 to 6");
    }

    // The state JSON the motion sensor's writeState publishes.
    private static JsonObject presenceState(Presence presence, GlobalPos pos) {
        return presence.write(new StateWriter()).put(CommonFields.LOCATION, pos).json();
    }

    // The state JSON the emitter's writeState publishes.
    private static JsonObject outputState(Output output, GlobalPos pos) {
        return output.write(new StateWriter()).put(CommonFields.LOCATION, pos).json();
    }

    // The state JSON the publisher's writeState publishes.
    private static JsonObject inputState(int signal, GlobalPos pos) {
        return RedstoneInput.write(new StateWriter(), signal).put(CommonFields.LOCATION, pos).json();
    }

    private static JsonObject json(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static CommandReader command(String json) {
        return new CommandReader(JsonParser.parseString(json).getAsJsonObject());
    }

    private static void positionKeys() {
        check(BlockStatePersistence.parseBlockPositionKey("minecraft:overworld:10:64:-3").equals(Optional.of(kitchen())), "v1 position key");
        ResourceKey<Level> custom = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("somemod", "dims/deep"));
        check(BlockStatePersistence.parseBlockPositionKey("somemod:dims/deep:-1:0:7").equals(Optional.of(GlobalPos.of(custom, new BlockPos(-1, 0, 7)))),
                "A modded dimension");
        for (String invalid : List.of("1:2:3", "minecraft:overworld:a:2:3", "minecraft:overworld:1:2", "", "Not A Dimension!:1:2:3")) {
            check(BlockStatePersistence.parseBlockPositionKey(invalid).isEmpty(), "Not a position: '" + invalid + "'");
        }
    }

    private static void topicNames() {
        check(LegacyNbtV1.topicName("home/kitchen_lamp").equals("Kitchen Lamp"), "Last level, title-cased");
        check(LegacyNbtV1.topicName("home/hall/").equals("Hall"), "A trailing slash is skipped");
        check(LegacyNbtV1.topicName("a/b-c d").equals("B C D"), "Separators become spaces");
        check(LegacyNbtV1.topicName("///").isEmpty() && LegacyNbtV1.topicName("x/__").isEmpty(), "Nothing to name");
    }

    /** §9.2: one lamp on a topic is a record of its own, several are a legacy group. */
    private static void topicStates() {
        DeviceRegistry registry = new DeviceRegistry("world_7f3a", UUID.randomUUID());
        GlobalPos taken = GlobalPos.of(Level.OVERWORLD, new BlockPos(99, 64, 99));
        registry.add(new DeviceRecord(UUID.randomUUID(), SmartLamp.TYPE.id(), taken, "Existing", "existing"));

        BlockStatePersistence.PersistedData data = new BlockStatePersistence.PersistedData();
        data.topicStates.put("home/kitchen", state(15, 0, 0, 128, true, "minecraft:overworld:10:64:-3"));
        data.topicStates.put("home/hall", state(0, 0, 15, 255, false, "minecraft:overworld:0:64:0", "minecraft:overworld:4:64:0",
                "minecraft:overworld:4:64:0", "garbage", "minecraft:overworld:99:64:99"));
        data.topicStates.put("a/b", state(1, 1, 1, 10, true, "minecraft:overworld:20:64:0"));
        data.topicStates.put("a_b", state(1, 1, 1, 10, true, "minecraft:overworld:21:64:0"));
        data.topicStates.put("home/empty", state(1, 1, 1, 10, true));
        data.topicStates.put(" ", state(1, 1, 1, 10, true, "minecraft:overworld:30:64:0"));
        List<String> problems = new ArrayList<>();
        int[] imported = LegacyTopicStateImport.importInto(registry, data, problems::add);

        check(imported[0] == 3 && imported[1] == 1, "Three single lamps and one group, got " + imported[0] + " and " + imported[1]);
        check(registry.legacyImportDone(), "The import runs once");
        check(problems.size() == 4, "A duplicate unique_id, an invalid and a taken position, and the nameless topic are reported: " + problems);

        DeviceRecord kitchen = registry.at(kitchen());
        LightState kitchenLight = new LightState(true, 128, new ValueCodecs.Rgb(255, 0, 0), null);
        check(kitchen != null && kitchen.type().equals(SmartLamp.TYPE.id()) && kitchen.name().equals("Kitchen") && kitchen.slug().equals("kitchen"),
                "A single lamp is named after its topic");
        check("home_kitchen".equals(kitchen.legacyUniqueId()) && Boolean.TRUE.equals(kitchen.expose()) && kitchen.group() == null,
                "It keeps the v1 unique_id and stays exposed (grandfathered)");
        check("home/kitchen".equals(kitchen.legacyTopic()), "It knows the v1 topic its lamp must have to take it over");
        check(SmartLamp.command(kitchenLight).equals(kitchen.pending()), "The topic's state is queued for its lamp: " + kitchen.pending());
        check(SmartLamp.state(kitchenLight, kitchen()).equals(kitchen.lastState()), "and published while it is unloaded: " + kitchen.lastState());

        DeviceGroup hall = registry.groups().stream().filter(group -> "home/hall".equals(group.legacyTopic())).findFirst().orElseThrow();
        LightState hallLight = new LightState(false, 255, new ValueCodecs.Rgb(0, 0, 255), null);
        check(hall.name().equals("Hall") && hall.slug().equals("hall") && "home_hall".equals(hall.legacyUniqueId()), "The legacy group of the topic");
        check(SmartLamp.TYPE.id().equals(hall.memberType()) && !hall.exposeMembers() && SmartLamp.command(hallLight).equals(hall.state()),
                "It holds lamps, hides them and has the topic's state");
        Set<GlobalPos> memberPositions = hall.members().stream().map(registry::record).map(DeviceRecord::pos).collect(Collectors.toSet());
        check(memberPositions.equals(Set.of(GlobalPos.of(Level.OVERWORLD, new BlockPos(0, 64, 0)), GlobalPos.of(Level.OVERWORLD, new BlockPos(4, 64, 0)))),
                "One member per distinct valid free position: " + memberPositions);
        for (UUID member : hall.members()) {
            DeviceRecord record = registry.record(member);
            check(record.legacyUniqueId() == null && record.expose() == null && SmartLamp.command(hallLight).equals(record.pending()),
                    "A member has no v1 light of its own and gets the topic's state");
        }

        DeviceRecord ab = registry.at(GlobalPos.of(Level.OVERWORLD, new BlockPos(20, 64, 0)));
        DeviceRecord aUnderscoreB = registry.at(GlobalPos.of(Level.OVERWORLD, new BlockPos(21, 64, 0)));
        check("a_b".equals(ab.legacyUniqueId()) && aUnderscoreB.legacyUniqueId() == null && !ab.slug().equals(aUnderscoreB.slug()),
                "Two topics with one v1 unique_id: the first keeps it");
        check(registry.at(GlobalPos.of(Level.OVERWORLD, new BlockPos(30, 64, 0))) == null, "A topic without a name is skipped");

        DeviceRegistry reloaded = DeviceRegistry.decode(registry.encode(), problem -> {
            throw new AssertionError(problem);
        });
        DeviceGroup reloadedHall = reloaded.group(hall.id());
        check(reloadedHall != null && "home/hall".equals(reloadedHall.legacyTopic()) && reloadedHall.members().equals(hall.members()),
                "The legacy group survives the registry file");
        check(reloaded.legacyImportDone(), "So does the import flag");

        DeviceRegistry again = new DeviceRegistry("world_7f3a", UUID.randomUUID());
        LegacyTopicStateImport.importInto(again, new BlockStatePersistence.PersistedData(), problem -> {
            throw new AssertionError(problem);
        });
        check(again.legacyImportDone() && again.records().isEmpty(), "A world without the file is done at once");
    }

    private static BlockStatePersistence.TopicState state(int red, int green, int blue, int brightness, boolean lit, String... positions) {
        BlockStatePersistence.TopicState state = new BlockStatePersistence.TopicState(red, green, blue, brightness, lit);
        state.blockPositions = new ArrayList<>(List.of(positions));
        return state;
    }

    // ---- v1 data, as the main branch's saveAdditional wrote it ----

    /** BaseMqttBlockEntity: topic, lastTabId, ownerName, privateMode and the four slots. */
    private static CompoundTag v1Device(ItemStack base, ItemStack sub, ItemStack on, ItemStack off, String owner, boolean privateMode) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, REGISTRIES);
        String topic = LegacyNbtV1.combinedTopic(base, sub, privateMode, owner);
        output.putString("topic", topic);
        output.putString("lastTabId", "settings");
        output.putString("ownerName", owner);
        output.putBoolean("privateMode", privateMode);
        ContainerHelper.saveAllItems(output, NonNullList.of(ItemStack.EMPTY, base, sub, on, off));
        return output.buildResult();
    }

    /**
     * RedstoneEmitterBlockEntity, an MqttSubscriberBlockEntity: the device keys and the subscribed topic; its level is the
     * block's. A publisher (PublisherBlockEntity) wrote the device keys alone.
     */
    private static CompoundTag v1Emitter(CompoundTag device) {
        CompoundTag emitter = device.copy();
        emitter.putString("currentSubscribedTopic", device.getStringOr("topic", ""));
        return emitter;
    }

    /**
     * MotionSensorBlockEntity, an MqttPublisherBlockEntity with nine slots: the device keys and the five filter slots
     * its tab showed (2 to 6, of which v1 read only 4 to 6), then MotionDetected, LastMotionTime (wall-clock) and
     * LastCount.
     */
    private static CompoundTag v1Motion(CompoundTag device, boolean motion, int lastCount) {
        CompoundTag sensor = device.copy();
        NonNullList<ItemStack> items = NonNullList.withSize(9, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input(device), items);
        items.set(6, spawnEgg(Items.ZOMBIE_SPAWN_EGG, EntityTypes.ZOMBIE));
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, REGISTRIES);
        ContainerHelper.saveAllItems(output, items);
        sensor.merge(output.buildResult());
        sensor.putByte("MotionDetected", (byte) (motion ? 1 : 0));
        sensor.putLong("LastMotionTime", 1_759_917_600_000L);
        sensor.putInt("LastCount", lastCount);
        return sensor;
    }

    /** RgbLedBlockEntity: two slots, the displayed and the target colour (0–15), Lit and Brightness. */
    private static CompoundTag v1Lamp(ItemStack base, int targetRed, int targetGreen, int targetBlue, int red, int green, int blue, int brightness, boolean lit) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, REGISTRIES);
        String topic = LegacyNbtV1.combinedTopic(base, ItemStack.EMPTY, false, "Steve");
        output.putString("topic", topic);
        output.putString("lastTabId", "rgb_status");
        output.putString("ownerName", "Steve");
        output.putBoolean("privateMode", false);
        ContainerHelper.saveAllItems(output, NonNullList.of(ItemStack.EMPTY, base, ItemStack.EMPTY));
        output.putString("currentSubscribedTopic", topic);
        output.putInt("Red", red);
        output.putInt("Green", green);
        output.putInt("Blue", blue);
        output.putByte("Lit", (byte) (lit ? 1 : 0));
        output.putInt("TargetRed", targetRed);
        output.putInt("TargetGreen", targetGreen);
        output.putInt("TargetBlue", targetBlue);
        output.putInt("Brightness", brightness);
        return output.buildResult();
    }

    private static GlobalPos kitchen() {
        return GlobalPos.of(Level.OVERWORLD, new BlockPos(10, 64, -3));
    }

    private static ItemStack paper(String name) {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    // A spawn egg as a server has it: its mob is the default entity data of the item.
    private static ItemStack spawnEgg(Item egg, EntityType<?> type) {
        ItemStack stack = new ItemStack(egg);
        stack.set(DataComponents.ENTITY_DATA, TypedEntityData.of(type, new CompoundTag()));
        return stack;
    }

    private static ValueInput input(CompoundTag tag) {
        return TagValueInput.create(ProblemReporter.DISCARDING, REGISTRIES, tag);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

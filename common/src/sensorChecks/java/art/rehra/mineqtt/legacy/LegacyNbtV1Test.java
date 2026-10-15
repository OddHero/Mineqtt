package art.rehra.mineqtt.legacy;

import art.rehra.mineqtt.compat.BlockEntityStorage;
import art.rehra.mineqtt.compat.ContainerItems;
import art.rehra.mineqtt.CheckItems;
import art.rehra.mineqtt.blocks.HumiditySensorBlock;
import art.rehra.mineqtt.blocks.StaticPublisherBlock;
import art.rehra.mineqtt.blocks.entities.PublisherBlockEntity;
import art.rehra.mineqtt.blocks.entities.RgbLedBlockEntity;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceGroup;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceRegistry;
import art.rehra.mineqtt.device.DeviceTypes;
import art.rehra.mineqtt.device.LegacyAdoptionCheck;
import art.rehra.mineqtt.device.LinkAdoptionCheck;
import art.rehra.mineqtt.device.RawLinkSpec;
import art.rehra.mineqtt.device.RenderTag;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.TwinTestTypes;
import art.rehra.mineqtt.device.UnloadedPolicy;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.device.ValueCodecs.LightState;
import art.rehra.mineqtt.device.types.BlockObserver;
import art.rehra.mineqtt.device.types.BlockPlacer;
import art.rehra.mineqtt.device.types.ClimateSensor;
import art.rehra.mineqtt.device.types.DisplayPanel;
import art.rehra.mineqtt.device.types.PresenceSensor;
import art.rehra.mineqtt.device.types.PresenceSensor.Presence;
import art.rehra.mineqtt.device.types.RedstoneInput;
import art.rehra.mineqtt.device.types.RedstoneOutput;
import art.rehra.mineqtt.device.types.RedstoneOutput.Output;
import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.device.types.SmartPlug;
import art.rehra.mineqtt.device.types.WallSwitch;
import art.rehra.mineqtt.device.types.SmartPlug.Plug;
import art.rehra.mineqtt.device.types.Timers;
import art.rehra.mineqtt.device.timer.core.TimerKind;
import art.rehra.mineqtt.device.timer.core.TimerState;
import art.rehra.mineqtt.mqtt.BlockStatePersistence;
import art.rehra.mineqtt.twin.BindingCodecs;
import art.rehra.mineqtt.twin.TwinAdapter;
import art.rehra.mineqtt.twin.core.Binding;
import art.rehra.mineqtt.twin.core.TwinHealth;
import art.rehra.mineqtt.twin.core.ValuePath;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
//? if >=1.21.9 {
import net.minecraft.world.item.component.TypedEntityData;
//?}
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *     <li>the Smart Plug (M2-18): state JSON, commands, previews, power-on and group toggles, and the adopted link of a v1
 *     plug and of one the M2 legacy layer saved with its owner's UUID, also from a placed item;</li>
 *     <li>the Presence Sensor: state JSON, settings, occupancy with its clear delay, v1's area and spawn-egg filters,
 *     and the adopted link of a v1 motion sensor with eggs in its filter slots 2 and 3, also from a placed item;</li>
 *     <li>the per-topic states of {@code mineqtt_topic_states.json} as records and legacy groups, and the attach-time
 *     decisions on them: which record a lamp takes over, which v1 light it keeps, which legacy group it joins;</li>
 *     <li>the render-only update tag (M2-7): its key set, the dispatch of v2, render and v1 data, and the lamp's light
 *     through it;</li>
 *     <li>devices without containers (M2-12): v1 data written back as read until its adoption, M1 data captured from an
 *     M1 world migrated to version 3 (legacy items, private mode, last tab, the sensor's egg filter) idempotently, the mob
 *     filter, and placed copies that reset what they did and carry no legacy items;</li>
 *     <li>the decommissioned Dynamic Publisher and Topic Relay (M2-21): their v1 slots as legacy items, alone in their
 *     saved data.</li>
 * </ul>
 */
public final class LegacyNbtV1Test {
    // The built-in registries, which the item codecs need; set once Minecraft is bootstrapped.
    private static HolderLookup.Provider REGISTRIES;
    // The UUID the M2 legacy layer resolved a legacy block's owner name to (F11).
    private static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        // Item stacks need their items' default components, which a server binds when it loads its data packs; the
        // common ones are enough for the items the slots hold here.
        CheckItems.bindCommonComponents(Items.PAPER, Items.STONE, Items.DIRT, Items.REDSTONE_LAMP);
        // A spawn egg's mob is its default entity data, as the captured M1 eggs have no components of their own.
        bindEgg(Items.ZOMBIE_SPAWN_EGG, EntityTypes.ZOMBIE);
        bindEgg(Items.PIG_SPAWN_EGG, EntityTypes.PIG);
        DeviceTypes.register(SmartLamp.TYPE);
        DeviceTypes.register(RedstoneOutput.TYPE);
        DeviceTypes.register(RedstoneInput.TYPE);
        DeviceTypes.register(PresenceSensor.TYPE);
        DeviceTypes.register(SmartPlug.TYPE);
        DeviceTypes.register(ClimateSensor.TYPE);
        DeviceTypes.register(WallSwitch.TYPE);
        // M3-15: every 0.6.0 world check below runs with the Display Panel registered, which no 0.6.0 world holds.
        DeviceTypes.register(DisplayPanel.TYPE);
        DeviceTypes.register(BlockObserver.TYPE);
        DeviceTypes.register(BlockPlacer.TYPE);
        combinedTopics();
        lampKeys();
        smartLampState();
        redstoneOutputState();
        smartPlugState();
        redstoneInputState();
        presenceState();
        presenceFilters();
        adoption();
        m1Data();
        twinKey();
        positionKeys();
        topicNames();
        topicStates();
        renderTags();
        panelTags();
        timerKeys();
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
                {"state":"ON","brightness":200,"color_mode":"rgb","color":{"r":255,"g":120,"b":0},"effect":"off","light_level":12,
                 "location":{"dimension":"minecraft:overworld","pos":[120,64,-35]}}""")), "§3.3 lamp state: " + state);
        LightState warm = red.merge(JsonParser.parseString("{\"color_temp_kelvin\":2700}").getAsJsonObject());
        JsonObject warmState = SmartLamp.command(warm);
        check(warmState.get("color_mode").getAsString().equals("color_temp") && warmState.get("color_temp").getAsInt() == 2700
                && warmState.get("color_temp_kelvin").getAsInt() == 2700, "Kelvin as Home Assistant reads it and as documented: " + warmState);

        LightState pulsing = warm.merge(JsonParser.parseString("{\"effect\":\"pulse\"}").getAsJsonObject());
        for (LightState light : List.of(red, warm, pulsing, LightState.DEFAULT)) {
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, REGISTRIES);
            output.store("state", SmartLamp.LIGHT.codec(), light);
            Optional<LightState> read = input(output.buildResult()).read("state", SmartLamp.LIGHT.codec());
            check(read.equals(Optional.of(light)), "The lamp state round-trips through NBT: " + light + " -> " + read);
        }

        // A lamp saved before effects existed (M1, M2 up to M2-25) has none.
        CompoundTag m1;
        try {
            m1 = TagParser.parseCompoundFully("{state:{state:\"ON\",brightness:200,color_mode:\"rgb\",color:{r:255,g:120,b:0}}}");
        } catch (CommandSyntaxException e) {
            throw new AssertionError(e);
        }
        check(input(m1).read("state", SmartLamp.LIGHT.codec()).equals(Optional.of(red)), "A lamp saved without an effect loads without one");

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
        JsonObject faded = SmartLamp.TYPE.preview(state, JsonParser.parseString("{\"state\":\"OFF\",\"transition\":5}").getAsJsonObject());
        check(faded.get("state").getAsString().equals("OFF") && faded.get("light_level").getAsInt() == 0 && !faded.has("transition"),
                "A queued preview ignores the transition: the target at once, and no transition in the state: " + faded);
        JsonObject flickering = SmartLamp.TYPE.preview(state, JsonParser.parseString("{\"effect\":\"Candle\"}").getAsJsonObject());
        check(flickering.get("effect").getAsString().equals("candle") && flickering.get("brightness").getAsInt() == 200,
                "A preview takes an effect: " + flickering);
        check(SmartLamp.TYPE.writableState(state).keySet().equals(Set.of("state", "brightness", "color_mode", "color", "effect")),
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
        CompoundTag plug = v1Plug(v1Device(paper("kitchen/coffee"), ItemStack.EMPTY, paper("BREW"), paper("STOP"), "Steve", false), null);
        LinkAdoptionCheck.plugAdopted(REGISTRIES, plug, "kitchen/coffee", "BREW", "STOP", null);
        CompoundTag legacyLayerPlug = v1Plug(v1Device(paper("kitchen"), paper("kettle"), ItemStack.EMPTY, ItemStack.EMPTY, "Steve", false), STEVE);
        LinkAdoptionCheck.plugAdopted(REGISTRIES, legacyLayerPlug, "kitchen/kettle", null, null, STEVE);
        LinkAdoptionCheck.plugPlacedFromItem(REGISTRIES, legacyLayerPlug);
        CompoundTag motion = v1Motion(v1Device(paper("home/hall"), ItemStack.EMPTY, spawnEgg(Items.ZOMBIE_SPAWN_EGG, EntityTypes.ZOMBIE),
                spawnEgg(Items.PIG_SPAWN_EGG, EntityTypes.PIG), "Steve", false), true, 2);
        LinkAdoptionCheck.motionAdopted(REGISTRIES, motion, "home/hall",
                new ValueCodecs.CustomPayloads("minecraft/zombie_spawn_egg", "minecraft/pig_spawn_egg"),
                List.of(Identifier.withDefaultNamespace("zombie"), Identifier.withDefaultNamespace("pig")));
        LinkAdoptionCheck.motionPlacedFromItem(REGISTRIES, motion);
        // M4-9: a v1 Block Sensor becomes a Block Observer whose v1 topic is its unretained Raw Link output.
        CompoundTag sensor = v1Device(paper("garden"), paper("gate"), ItemStack.EMPTY, ItemStack.EMPTY, "Steve", false);
        sensor.putString("BlockSensorDataMode", "properties");
        LinkAdoptionCheck.sensorAdopted(REGISTRIES, sensor, "garden/gate", BlockObserver.Payload.PROPERTIES);
        CompoundTag fullSensor = v1Device(paper("garden"), ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, "Steve", false);
        fullSensor.putString("BlockSensorDataMode", "full_data");
        LinkAdoptionCheck.sensorAdopted(REGISTRIES, fullSensor, "garden", BlockObserver.Payload.FULL);
        // M4-10: a v1 Block Emitter becomes a Block Placer whose listen topic is its Raw Link input, which never applies a
        // retained message; its third slot was never a payload.
        CompoundTag blockEmitter = v1Emitter(v1Device(paper("builds"), paper("wall"), paper("OPEN"), ItemStack.EMPTY, "Steve", false));
        LinkAdoptionCheck.placerAdopted(REGISTRIES, blockEmitter, "builds/wall");
        // M4-1: a timer-like noV1Topic type keeps no Raw Link from its v1 slots (M4 plan D2), the anvil name it gets.
        CompoundTag timer = v1Device(paper("kitchen"), paper("alarm"), ItemStack.EMPTY, ItemStack.EMPTY, "Steve", false);
        timer.putString("CustomName", "Kitchen Alarm");
        LinkAdoptionCheck.noV1TopicAdopted(REGISTRIES, timer, "kitchen/alarm");
        climateSensors();
        staticPublishers();
        decommissioned();
        lightRemotes();
        LegacyAdoptionCheck.run();
    }

    /**
     * The Climate Sensor (M2-19): a v1 temperature sensor adopted in place, and a v1 humidity sensor converted by the pure
     * converter into Climate Sensor data that keeps publishing its humidity on its v1 topic, with its owner UUID (saved
     * by the M2 legacy layer) and its anvil name.
     */
    private static void climateSensors() {
        CompoundTag temperature = v1Climate(v1Device(paper("greenhouse"), paper("temp"), ItemStack.EMPTY, ItemStack.EMPTY, "Steve", false),
                "Temperature", 23.4, 100, 4_000L);
        LinkAdoptionCheck.climateAdopted(REGISTRIES, temperature, "greenhouse/temp",
                new ClimateSensor.Climate(5, ClimateSensor.RawValue.TEMPERATURE, new ClimateSensor.Reading(23.4, 50.0, 0, ""), 4_000L), null, "");

        CompoundTag humidity = v1Climate(v1Device(paper("greenhouse"), paper("humidity"), paper("on"), ItemStack.EMPTY, "Steve", true),
                "Humidity", 61.5, 200, 8_000L);
        humidity.store("ownerUuid", UUIDUtil.CODEC, STEVE);
        humidity.store("CustomName", ComponentSerialization.CODEC, Component.literal("Greenhouse Air"));
        CompoundTag converted = HumiditySensorBlock.toClimateSensor(humidity);
        check(converted.equals(humidity) && converted != humidity, "A v1 humidity sensor's data is the Climate Sensor's v1 data as it stands, copied");
        check(HumiditySensorBlock.toClimateSensor(humidity).equals(converted), "The converter is pure");
        LinkAdoptionCheck.climateAdopted(REGISTRIES, converted, "Steve/greenhouse/humidity",
                new ClimateSensor.Climate(10, ClimateSensor.RawValue.HUMIDITY, new ClimateSensor.Reading(20.0, 61.5, 0, ""), 8_000L), STEVE, "Greenhouse Air");
        CompoundTag both = humidity.copy();
        both.putDouble("Temperature", 30.0);
        check(!HumiditySensorBlock.toClimateSensor(both).contains("Temperature"), "A converted humidity sensor never reads as a temperature sensor");
        check(!LegacyNbtV1.hasClimateKeys(input(new CompoundTag())) && LegacyNbtV1.hasClimateKeys(input(converted)) && LegacyNbtV1.hasClimateKeys(input(temperature)),
                "v1 sensors are told by their own keys");
    }

    /**
     * The Static Publisher (M2-20): v1 data as v1 saved it, converted by the pure converter into Redstone Input data that
     * keeps publishing its message on rising edges only, non-retained; once with the owner UUID the M2 legacy layer saved
     * and an anvil name, once with an empty message, which v1 published as {@code true}.
     */
    private static void staticPublishers() {
        CompoundTag bell = v1Static(v1Device(paper("home"), paper("doorbell"), paper("stray"), ItemStack.EMPTY, "Steve", false), "DING", true);
        bell.store("ownerUuid", UUIDUtil.CODEC, STEVE);
        bell.store("CustomName", ComponentSerialization.CODEC, Component.literal("Front Door"));
        CompoundTag converted = StaticPublisherBlock.toRedstoneInput(bell);
        check(!converted.contains("wasPowered") && !converted.contains("lastPublishStatus") && converted.getStringOr("staticMessage", "").equals("DING"),
                "The converter keeps the message and drops v1's edge record and status line: " + converted);
        CompoundTag expected = bell.copy();
        expected.remove("wasPowered");
        expected.remove("lastPublishStatus");
        check(converted.equals(expected) && bell.contains("wasPowered") && StaticPublisherBlock.toRedstoneInput(bell).equals(converted),
                "Everything else stays, and the converter is pure");
        LinkAdoptionCheck.staticPublisherAdopted(REGISTRIES, converted, "home/doorbell", "DING", "DING", STEVE, "Front Door");

        CompoundTag plain = StaticPublisherBlock.toRedstoneInput(v1Static(v1Device(paper("alarm"), ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
                "Steve", true), "", false));
        LinkAdoptionCheck.staticPublisherAdopted(REGISTRIES, plain, "Steve/alarm", null, "true", null, "");
    }

    /**
     * v1 Light Remotes, which became Wall Switches (M2-22): their topic slots (two, of the four every v1 device saved) and
     * owner, a private one with its owner prefix. What their topic commanded is {@code LegacyAdoptionCheck}'s.
     */
    private static void lightRemotes() {
        LinkAdoptionCheck.remoteAdopted(REGISTRIES, v1Device(paper("home"), paper("kitchen"), ItemStack.EMPTY, ItemStack.EMPTY, "Steve", false), "home/kitchen");
        LinkAdoptionCheck.remoteAdopted(REGISTRIES, v1Device(paper("lamp"), ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, "Steve", true), "Steve/lamp");
    }

    /**
     * The decommissioned Dynamic Publisher and Topic Relay (M2-21): their v1 data as v1 saved it keeps only the items of
     * its slots, each in its slot, which the block entity saves as {@code legacyItems} alone and reads back the same.
     */
    private static void decommissioned() {
        CompoundTag relay = v1Relay(v1Device(paper("home/out"), paper("relay"), paper("stray"), ItemStack.EMPTY, "Steve", false),
                "home/in", "last message");
        relay.store("CustomName", ComponentSerialization.CODEC, Component.literal("Hall Relay"));
        retiredItems(relay, List.of(0, 1, 2), List.of("home/out", "relay", "stray"));

        CompoundTag dynamic = v1Dynamic(v1Device(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, "Alex", true),
                ItemStack.EMPTY, paper("server/players"), 2);
        retiredItems(dynamic, List.of(1), List.of("server/players"));

        CompoundTag empty = v1Dynamic(v1Device(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, "", false),
                ItemStack.EMPTY, ItemStack.EMPTY, 0);
        DecommissionedBlockEntity nothing = retired(empty);
        check(nothing.legacyItems().isEmpty(), "A retired block whose slots were empty keeps nothing");
        check(nothing.saveCustomOnly(REGISTRIES).isEmpty(), "and saves nothing");
    }

    /**
     * The items of retired v1 data, by slot and paper name, through the block entity's own load and save: saved, none of
     * the v1 keys is left besides the items as legacyItems, which read back the same.
     */
    private static void retiredItems(CompoundTag v1, List<Integer> slots, List<String> names) {
        DecommissionedBlockEntity loaded = retired(v1);
        List<ItemStackWithSlot> items = loaded.legacyItems();
        check(items.stream().map(ItemStackWithSlot::slot).toList().equals(slots)
                        && items.stream().map(item -> item.stack().getHoverName().getString()).toList().equals(names)
                        && items.stream().allMatch(item -> item.stack().is(Items.PAPER) && item.stack().getCount() == 1),
                "The v1 slots become legacy items in their slots, empty ones left out: " + items);
        CompoundTag saved = loaded.saveCustomOnly(REGISTRIES);
        check(saved.keySet().equals(Set.of(DeviceLink.LEGACY_ITEMS)), "Saved, only legacyItems is left of the v1 keys " + v1.keySet() + ": " + saved.keySet());
        List<ItemStackWithSlot> again = retired(saved).legacyItems();
        boolean same = again.size() == items.size();
        for (int i = 0; same && i < items.size(); i++) {
            same = again.get(i).slot() == items.get(i).slot() && ItemStack.matches(again.get(i).stack(), items.get(i).stack());
        }
        check(same, "Loaded again, the same items in the same slots: " + again);
    }

    // A retired block's entity loaded from saved data. Mod block entity types are registered by the loaders, so it borrows
    // a vanilla type, as LinkAdoptionCheck's block entities do.
    private static DecommissionedBlockEntity retired(CompoundTag data) {
        DecommissionedBlockEntity blockEntity = new DecommissionedBlockEntity(BlockEntityTypes.DAYLIGHT_DETECTOR, BlockPos.ZERO,
                Blocks.DAYLIGHT_DETECTOR.defaultBlockState());
        BlockEntityStorage.loadCustomOnly(blockEntity, input(data));
        return blockEntity;
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
     * The Smart Plug (M2-18): its state JSON, switching (ON, OFF, TOGGLE, a level, boolean spellings), its power-on
     * behaviour, the preview and difference of a queued command, a group toggle, and its state in NBT.
     */
    private static void smartPlugState() {
        GlobalPos pos = GlobalPos.of(Level.OVERWORLD, new BlockPos(10, 64, 2));
        Plug off = Plug.DEFAULT;
        Plug on = off.apply(command("{\"state\":\"ON\"}")).orElseThrow();
        JsonObject state = plugState(on, pos);
        check(state.equals(json("""
                {"state":"ON","power":15,"power_on_behaviour":"previous","location":{"dimension":"minecraft:overworld","pos":[10,64,2]}}""")),
                "Plug state: " + state);
        check(plugState(off, pos).get("power").getAsInt() == 0 && plugState(off, pos).get("state").getAsString().equals("OFF"), "Off is power 0");
        check(on.apply(command("{\"state\":\"TOGGLE\"}")).orElseThrow().equals(off) && off.apply(command("{\"state\":\"toggle\"}")).orElseThrow().equals(on),
                "TOGGLE switches it");
        check(off.apply(command("{\"power\":7}")).orElseThrow().equals(on) && on.apply(command("{\"power\":0}")).orElseThrow().equals(off)
                && off.apply(command("{\"power\":99}")).orElseThrow().power() == SmartPlug.ON_POWER, "Any level above 0 is full power, 0 is off");
        check(on.apply(command("{\"state\":\"OFF\",\"power\":15}")).orElseThrow().equals(off), "The state wins over a level");
        check(off.apply(command("{\"state\":true}")).orElseThrow().equals(on) && on.apply(command("{\"state\":\"0\"}")).orElseThrow().equals(off),
                "Boolean spellings of the state");
        check(on.apply(command("{\"mode\":\"level\"}")).isEmpty() && on.apply(command("{\"brightness\":7}")).isEmpty()
                && on.apply(command("{\"state\":\"maybe\"}")).isEmpty(), "A command for something else, the Output's mode among them, is no plug command");

        Plug startsOff = on.apply(command("{\"power_on_behaviour\":\"off\"}")).orElseThrow();
        check(startsOff.on() && !startsOff.poweredOn().on(), "Power-on off starts off");
        Plug startsOn = off.apply(command("{\"power_on_behaviour\":\"on\"}")).orElseThrow();
        check(startsOn.poweredOn().on() && on.poweredOn().equals(on) && off.poweredOn().equals(off), "Power-on on starts on, previous keeps the state");
        check(startsOn.placed().equals(new Plug(false, RedstoneOutput.PowerOn.ON)), "A placed copy is off and keeps its power-on behaviour");
        check(Plug.ofPower(15).equals(on) && Plug.ofPower(3).on() && Plug.ofPower(0).equals(off), "A v1 plug is on while its block is powered");

        JsonObject unloaded = plugState(off, pos);
        JsonObject switchedOn = SmartPlug.TYPE.preview(unloaded, json("{\"state\":\"ON\"}"));
        check(switchedOn.equals(plugState(on, pos)), "An unloaded plug previews ON, keys in order: " + switchedOn);
        check(SmartPlug.TYPE.difference(unloaded, switchedOn).equals(json("{\"power\":15}")), "A queued ON is kept as the level it sets");
        check(SmartPlug.TYPE.preview(plugState(on, pos), json("{\"state\":\"TOGGLE\"}")).equals(unloaded), "and a toggle");
        check(SmartPlug.TYPE.preview(unloaded, json("{\"color\":\"red\"}")).equals(unloaded), "A preview ignores what is no plug command");
        check(SmartPlug.TYPE.writableState(state).keySet().equals(Set.of("power", "power_on_behaviour")), "A group shares the writable keys only");
        JsonObject started = SmartPlug.TYPE.poweredOn(plugState(startsOff, pos));
        check(started.get("power").getAsInt() == 0 && started.get("state").getAsString().equals("OFF") && started.has("location"),
                "An unloaded plug with power-on off starts off: " + started);
        check(SmartPlug.TYPE.poweredOn(state).equals(state), "Power-on previous keeps the record");
        JsonObject toggle = json("{\"state\":\"TOGGLE\"}");
        check(SmartPlug.TYPE.resolveToggles(toggle, SmartPlug.TYPE.writableState(state)).equals(json("{\"state\":\"OFF\"}"))
                && SmartPlug.TYPE.resolveToggles(toggle, SmartPlug.TYPE.writableState(unloaded)).equals(json("{\"state\":\"ON\"}")),
                "A plug group toggles on its power, as its outlet switch reads it");

        for (Plug plug : List.of(off, on, startsOff, startsOn)) {
            TagValueOutput nbt = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, REGISTRIES);
            nbt.store("state", Plug.CODEC, plug);
            Optional<Plug> read = input(nbt.buildResult()).read("state", Plug.CODEC);
            check(read.equals(Optional.of(plug)), "The plug round-trips through NBT: " + plug + " -> " + read);
        }
        CompoundTag garbage = new CompoundTag();
        CompoundTag garbageState = new CompoundTag();
        garbageState.putString("power_on_behaviour", "sometimes");
        garbageState.putInt("power", 15);
        garbage.put("state", garbageState);
        check(input(garbage).read("state", Plug.CODEC).equals(Optional.of(on)), "An unreadable value keeps its default, the rest is read");
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
                {"occupancy":true,"count":2,"detect":"players","range":5,"clear_delay":10,"filter":"",
                 "location":{"dimension":"minecraft:overworld","pos":[8,64,2]}}""")), "§3.3 presence state: " + state);
        check(!state.has("last_seen"), "The time of the last detection is no state");

        check(two.apply(command("{\"detect\":\"mobs\"}")).orElseThrow().detect() == PresenceSensor.Detect.MOBS, "detect is set");
        check(two.apply(command("{\"detect\":\"ALL\",\"range\":3,\"clear_delay\":30}")).orElseThrow()
                .equals(new Presence(PresenceSensor.Detect.ALL, 3, 30, List.of(), 2, true, 1000)), "All settings at once, the readings stay");
        check(two.apply(command("{\"filter\":\"minecraft:zombie,minecraft:pig\"}")).orElseThrow().filter()
                .equals(List.of(Identifier.withDefaultNamespace("zombie"), Identifier.withDefaultNamespace("pig"))), "filter is set");
        check(two.apply(command("{\"range\":99}")).orElseThrow().range() == PresenceSensor.MAX_RANGE
                && two.apply(command("{\"range\":\"0\"}")).orElseThrow().range() == PresenceSensor.MIN_RANGE, "The range is clamped to 1-8");
        check(two.apply(command("{\"clear_delay\":-5}")).orElseThrow().clearDelay() == 0
                && two.apply(command("{\"clear_delay\":99999}")).orElseThrow().clearDelay() == PresenceSensor.MAX_CLEAR_DELAY, "The clear delay is clamped");
        check(two.apply(command("{\"detect\":\"sideways\"}")).isEmpty() && two.apply(command("{\"count\":5,\"occupancy\":false}")).isEmpty()
                && two.apply(command("{\"state\":\"ON\"}")).isEmpty(), "Readings and unknown values are no commands");

        Presence held = two.scanned(0, 1000 + 199);
        check(held.occupancy() && held.count() == 0 && held.lastSeen() == 1000, "Occupancy holds for the clear delay, the count does not: " + held);
        check(!held.scanned(0, 1000 + 200).occupancy(), "and clears after it");
        check(held.scanned(1, 1150).equals(new Presence(PresenceSensor.Detect.PLAYERS, 5, 10, List.of(), 1, true, 1150)), "A new detection restarts the delay");
        Presence instant = two.apply(command("{\"clear_delay\":0}")).orElseThrow();
        check(!instant.scanned(0, 1001).occupancy(), "Without a clear delay occupancy follows the count");
        check(!new Presence(PresenceSensor.Detect.PLAYERS, 5, 10, List.of(), 1, true, 50_000).scanned(0, 1000).occupancy(),
                "A last detection in the future (data from another world) has expired");
        check(!Presence.DEFAULT.scanned(0, 5).occupancy(), "Nothing seen, not occupied");

        Presence v1 = Presence.ofV1(-1, false);
        check(v1.detect() == PresenceSensor.Detect.MOBS && v1.range() == PresenceSensor.DEFAULT_RANGE && v1.count() == 0 && !v1.occupancy(),
                "A v1 motion sensor counts mobs in v1's box; its count before the first scan is 0: " + v1);
        check(!Presence.ofV1(3, true).scanned(0, 24000).occupancy(), "v1 had no clear delay: its motion ends at the first empty scan");
        check(Presence.DEFAULT.detect() == PresenceSensor.Detect.PLAYERS, "New sensors count players");

        Presence filtered = two.withFilter(List.of(Identifier.withDefaultNamespace("zombie")));
        Presence modded = two.withFilter(List.of(Identifier.withDefaultNamespace("zombie"), Identifier.fromNamespaceAndPath("othermod", "wisp")));
        for (Presence presence : List.of(Presence.DEFAULT, two, held, v1, instant, filtered, modded)) {
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
        garbageState.putInt("filter", 7);
        check(input(garbage).read("state", Presence.CODEC).equals(Optional.of(new Presence(PresenceSensor.Detect.PLAYERS, 3, 10, List.of(), 0, false, 0))),
                "An unreadable value keeps its default, the rest is read");
        // A removed mod's mob, a hand-written token that is no id and the player, whom detect counts.
        CompoundTag unknown = new CompoundTag();
        CompoundTag unknownState = new CompoundTag();
        unknownState.putString("detect", "mobs");
        unknownState.putString("filter", "zombie, othermod:wisp Zombie! player zombie");
        unknown.put("state", unknownState);
        Presence kept = input(unknown).read("state", Presence.CODEC).orElseThrow();
        check(kept.filter().equals(List.of(Identifier.withDefaultNamespace("zombie"), Identifier.fromNamespaceAndPath("othermod", "wisp"))),
                "A saved filter keeps the ids of mobs not registered now and drops only what is no id or the player: " + kept.filter());
        check(kept.counts(EntityTypes.ZOMBIE) && !kept.counts(EntityTypes.PIG), "and still counts only its mobs");
        TagValueOutput keptNbt = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, REGISTRIES);
        keptNbt.store("state", Presence.CODEC, kept);
        check(keptNbt.buildResult().getCompound("state").orElseThrow().getStringOr("filter", "").equals("zombie, othermod:wisp"),
                "and saves them again, so a reinstalled mod's mob counts once more");

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

    /**
     * The mob filter (M2-12): the ids it takes and refuses, its text, which mobs it counts, and the filter v1's and M1's
     * spawn eggs in the filter slots (2 to 6) give: every egg's mob once, other items and other slots nothing.
     */
    private static void presenceFilters() {
        Identifier zombieId = Identifier.withDefaultNamespace("zombie");
        Identifier pigId = Identifier.withDefaultNamespace("pig");
        check(PresenceSensor.parseFilter(" zombie,  minecraft:pig zombie ").result().equals(Optional.of(List.of(zombieId, pigId))),
                "Commas or spaces, minecraft: by default, each mob once");
        check(PresenceSensor.parseFilter("").result().equals(Optional.of(List.of())), "Empty: no filter");
        check(PresenceSensor.parseFilter("minecraft:ghost").error().isPresent() && PresenceSensor.parseFilter("Zombie!").error().isPresent()
                && PresenceSensor.parseFilter("player").error().isPresent(), "Unknown mobs, no ids and players are refused");
        String nine = "zombie pig cow sheep chicken horse wolf cat fox";
        check(PresenceSensor.parseFilter(nine).error().isPresent(), "At most " + PresenceSensor.MAX_FILTER + " mobs");
        Identifier modded = Identifier.fromNamespaceAndPath("othermod", "wisp");
        check(PresenceSensor.filterText(List.of(zombieId, pigId, modded)).equals("zombie, pig, othermod:wisp"), "The text of a filter: vanilla mobs without namespace");
        check(PresenceSensor.parseFilter(PresenceSensor.filterText(List.of(zombieId, pigId))).result().equals(Optional.of(List.of(zombieId, pigId))),
                "and reads back as the same filter");
        check(PresenceSensor.FILTER.encode(List.of(zombieId)).getAsString().equals("zombie")
                && PresenceSensor.TYPE.setting("filter").orElseThrow().parse(new JsonPrimitive("cow")).result()
                .equals(Optional.of(List.of(Identifier.withDefaultNamespace("cow")))), "The setting reads and writes the text");

        Presence any = Presence.DEFAULT;
        Presence zombies = any.withFilter(List.of(zombieId));
        check(any.counts(EntityTypes.PIG) && zombies.counts(EntityTypes.ZOMBIE) && !zombies.counts(EntityTypes.PIG), "A filter counts its mobs only");

        List<ItemStackWithSlot> slots = List.of(new ItemStackWithSlot(0, paper("pig")), new ItemStackWithSlot(2, spawnEgg(Items.ZOMBIE_SPAWN_EGG, EntityTypes.ZOMBIE)),
                new ItemStackWithSlot(3, paper("cow")), new ItemStackWithSlot(4, spawnEgg(Items.PIG_SPAWN_EGG, EntityTypes.PIG)),
                new ItemStackWithSlot(6, spawnEgg(Items.ZOMBIE_SPAWN_EGG, EntityTypes.ZOMBIE)), new ItemStackWithSlot(7, spawnEgg(Items.PIG_SPAWN_EGG, EntityTypes.PIG)));
        check(LegacyNbtV1.eggFilter(slots).equals(List.of(zombieId, pigId)), "The eggs of slots 2 to 6 give the filter: " + LegacyNbtV1.eggFilter(slots));
        check(LegacyNbtV1.eggFilter(List.of(new ItemStackWithSlot(2, paper("pig")))).isEmpty(), "Without an egg every mob counts, as before");
        check(LegacyNbtV1.eggFilter(List.of(new ItemStackWithSlot(2, spawnEgg(Items.ZOMBIE_SPAWN_EGG, EntityTypes.PLAYER)))).isEmpty(),
                "A hand-made egg of the player filters nothing, as detect counts players");
    }

    /**
     * M1 data captured with {@code data get block} from an M1 world ({@code e2e/tools/buildm1.sh}, resources {@code m1/})
     * before M2-12 changed the code: migrated without loss, idempotent, and a placed copy resets what it did.
     */
    private static void m1Data() {
        Map<String, CompoundTag> m1 = new HashMap<>();
        for (String name : List.of("input", "output", "lamp", "presence", "private_output", "adopted_output")) {
            m1.put(name, fixture("/m1/" + name + ".snbt"));
        }
        LinkAdoptionCheck.m1Migrated(REGISTRIES, m1);
        LinkAdoptionCheck.placedCopies(REGISTRIES, m1.get("output"), m1.get("presence"));
    }

    /**
     * M3 plan D14: the {@code twin} key of the {@code mineqtt} compound. Data of 0.6.0 (compound version 3, no key) loads
     * unbound and saves exactly as it was, with the version still 3; a binding round-trips on a type with a twin adapter;
     * a {@code twin} key on a type without one, or one that breaks the binding rules, reads as no binding and is not
     * saved again.
     */
    private static void twinKey() {
        CompoundTag data = new CompoundTag();
        data.putInt("v", DeviceLink.VERSION);
        data.store("id", UUIDUtil.CODEC, UUID.fromString("00000000-0000-0000-0000-0000000000d1"));
        data.putString("type", TwinTestTypes.TYPE.id().toString());
        data.putString("name", "Porch Mirror");
        data.putString("slug", "porch_mirror");
        data.putString("access", "claim");
        CompoundTag saved = linkSave(TwinTestTypes.TYPE, data);
        check(DeviceLink.VERSION == 3 && saved.equals(data), "0.6.0 link data loads unbound and saves byte-identical: " + saved);
        check(linkLoad(TwinTestTypes.TYPE, data).twin() == null, "...as no binding");

        Binding binding = TwinTestTypes.bound("switch.porch", true).withTarget(TwinTestTypes.PRESSED, TwinAdapter.targetEnd("scene.movie"));
        CompoundTag bound = data.copy();
        bound.store(DeviceLink.TWIN_KEY, BindingCodecs.CODEC, binding);
        check(binding.equals(linkLoad(TwinTestTypes.TYPE, bound).twin()), "A binding round-trips through the compound");
        check(linkSave(TwinTestTypes.TYPE, bound).equals(bound), "...and saves as it was read");

        // M3 plan D5: the Climate Sensor senses the Minecraft world and never mirrors anything.
        CompoundTag climate = bound.copy();
        climate.putString("type", ClimateSensor.TYPE.id().toString());
        check(ClimateSensor.TYPE.twin().isEmpty() && linkLoad(ClimateSensor.TYPE, climate).twin() == null
                        && !linkSave(ClimateSensor.TYPE, climate).contains(DeviceLink.TWIN_KEY),
                "A type without a twin adapter ignores a twin key and drops it on save");
        // M3-12: an analog Redstone Output's rows and a Smart Plug's control round-trip as well.
        CompoundTag output = data.copy();
        output.putString("type", RedstoneOutput.TYPE.id().toString());
        Binding analog = new Binding(RedstoneOutput.TWIN.sourceEnd("light.porch"), Map.of(), true,
                Binding.Settings.DEFAULT.withInvert(true).withMin(new BigDecimal("12.5")).withMax(new BigDecimal("200"))
                        .withPath(ValuePath.parse("attributes.brightness")).withUnavailable(Binding.Unavailable.OFF), null);
        output.store(DeviceLink.TWIN_KEY, BindingCodecs.CODEC, analog);
        check(analog.equals(linkLoad(RedstoneOutput.TYPE, output).twin()) && linkSave(RedstoneOutput.TYPE, output).equals(output),
                "An analog Redstone Output's binding round-trips through the compound (M3-12)");
        CompoundTag plug = data.copy();
        plug.putString("type", SmartPlug.TYPE.id().toString());
        Binding plugged = new Binding(SmartPlug.TWIN.sourceEnd("switch.heater"), Map.of(), true, Binding.Settings.DEFAULT, null);
        plug.store(DeviceLink.TWIN_KEY, BindingCodecs.CODEC, plugged);
        check(plugged.equals(linkLoad(SmartPlug.TYPE, plug).twin()) && linkSave(SmartPlug.TYPE, plug).equals(plug),
                "A Smart Plug's binding round-trips through the compound (M3-12)");
        CompoundTag lamp = data.copy();
        lamp.putString("type", SmartLamp.TYPE.id().toString());
        Binding mirrored = new Binding(SmartLamp.TWIN.sourceEnd("light.kitchen"), Map.of(), false, Binding.Settings.DEFAULT, null);
        lamp.store(DeviceLink.TWIN_KEY, BindingCodecs.CODEC, mirrored);
        check(mirrored.equals(linkLoad(SmartLamp.TYPE, lamp).twin()) && linkSave(SmartLamp.TYPE, lamp).equals(lamp),
                "A Smart Lamp's binding round-trips through the compound (M3-8)");
        CompoundTag broken = data.copy();
        CompoundTag twin = new CompoundTag();
        twin.putBoolean("control", true);
        broken.put(DeviceLink.TWIN_KEY, twin);
        check(linkLoad(TwinTestTypes.TYPE, broken).twin() == null && linkSave(TwinTestTypes.TYPE, broken).equals(data),
                "A twin key that breaks the binding rules reads as no binding");
    }

    private static DeviceLink linkLoad(art.rehra.mineqtt.device.DeviceType type, CompoundTag data) {
        CompoundTag tag = new CompoundTag();
        tag.put(DeviceLink.KEY, data.copy());
        DeviceLink link = new DeviceLink(type);
        link.load(TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, tag), false);
        return link;
    }

    private static CompoundTag linkSave(art.rehra.mineqtt.device.DeviceType type, CompoundTag data) {
        TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        linkLoad(type, data).save(output);
        return output.buildResult().getCompound(DeviceLink.KEY).orElseThrow();
    }

    private static CompoundTag fixture(String path) {
        try (InputStream in = LegacyNbtV1Test.class.getResourceAsStream(path)) {
            if (in == null) throw new AssertionError("Missing fixture " + path);
            return TagParser.parseCompoundFully(new String(in.readAllBytes(), StandardCharsets.UTF_8).strip());
        } catch (IOException | CommandSyntaxException e) {
            throw new AssertionError("Unreadable fixture " + path + ": " + e.getMessage(), e);
        }
    }

    // The state JSON the motion sensor's writeState publishes.
    private static JsonObject presenceState(Presence presence, GlobalPos pos) {
        return presence.write(new StateWriter()).put(CommonFields.LOCATION, pos).json();
    }

    // The state JSON the emitter's writeState publishes.
    private static JsonObject outputState(Output output, GlobalPos pos) {
        return output.write(new StateWriter()).put(CommonFields.LOCATION, pos).json();
    }

    // The state JSON the plug's writeState publishes.
    private static JsonObject plugState(Plug plug, GlobalPos pos) {
        return plug.write(new StateWriter()).put(CommonFields.LOCATION, pos).json();
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

    /**
     * The render-only update tag (M2-7): a device's clients get exactly one {@code mineqtt_render} compound, built by a
     * pure helper, with no owner, topic, Raw Link or slots; loading tells v2, render and v1 data apart, so a render update
     * never reaches {@code DeviceLink.load} and its v1 migrator.
     */
    private static void renderTags() {
        LightState red = new LightState(true, 128, new ValueCodecs.Rgb(255, 0, 0), null);
        LightState blue = red.merge(JsonParser.parseString("{\"color\":{\"r\":0,\"g\":0,\"b\":255}}").getAsJsonObject());
        CompoundTag lamp = RenderTag.updateTag(RenderTag.lamp(red));
        CompoundTag nothing = RenderTag.updateTag(new CompoundTag());
        check(lamp.keySet().equals(Set.of(RenderTag.KEY)) && nothing.keySet().equals(Set.of(RenderTag.KEY)),
                "An update tag holds the render compound alone: " + lamp + ", " + nothing);
        check(lamp.getCompoundOrEmpty(RenderTag.KEY).keySet().equals(Set.of("rgb", "brightness", "on")), "A lamp renders {rgb, brightness, on}: " + lamp);
        check(nothing.getCompoundOrEmpty(RenderTag.KEY).isEmpty(), "The other devices render nothing: " + nothing);
        // M2-25: the effect, which the client animates, only while the lamp has one.
        LightState candle = red.merge(JsonParser.parseString("{\"effect\":\"candle\"}").getAsJsonObject());
        CompoundTag candleTag = RenderTag.updateTag(RenderTag.lamp(candle));
        check(candleTag.getCompoundOrEmpty(RenderTag.KEY).keySet().equals(Set.of("rgb", "brightness", "on", "effect"))
                && candleTag.getCompoundOrEmpty(RenderTag.KEY).getStringOr("effect", "").equals("candle"), "A lamp with an effect renders it too: " + candleTag);
        check(RenderTag.lamp(input(candleTag)).equals(candle) && RenderTag.lamp(input(lamp)).effect() == ValueCodecs.LightEffect.NONE,
                "The client reads the effect back, and none from a tag without one");

        // The dispatch table, then real data of each kind.
        check(RenderTag.dispatch(true, false) == RenderTag.Data.DEVICE && RenderTag.dispatch(true, true) == RenderTag.Data.DEVICE
                && RenderTag.dispatch(false, true) == RenderTag.Data.RENDER && RenderTag.dispatch(false, false) == RenderTag.Data.V1,
                "A mineqtt compound is v2 data, a render compound alone a render update, neither v1 data");
        check(RenderTag.dispatch(input(lamp)) == RenderTag.Data.RENDER && RenderTag.dispatch(input(nothing)) == RenderTag.Data.RENDER,
                "A render-only tag dispatches to the render update, so it never adopts, also when it is empty");
        TagValueOutput saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, REGISTRIES);
        RgbLedBlockEntity.saveLamp(new DeviceLink(SmartLamp.TYPE), red, saved);
        CompoundTag v2 = saved.buildResult();
        check(RenderTag.dispatch(input(v2)) == RenderTag.Data.DEVICE, "A saved v2 lamp is v2 data: " + v2);
        CompoundTag v2WithRender = v2.copy();
        v2WithRender.merge(lamp);
        check(RenderTag.dispatch(input(v2WithRender)) == RenderTag.Data.DEVICE, "v2 data stays v2 data next to a render compound");
        TagValueOutput input = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, REGISTRIES);
        PublisherBlockEntity.saveInput(new DeviceLink(RedstoneInput.TYPE), 15, input);
        check(RenderTag.dispatch(input(input.buildResult())) == RenderTag.Data.DEVICE,
                "An input's compound without an id is left to DeviceLink.load, which tells a placed v1 item by it");
        check(RenderTag.dispatch(input(v1Lamp(paper("home/kitchen"), 15, 0, 0, 15, 0, 0, 200, true))) == RenderTag.Data.V1
                        && RenderTag.dispatch(input(v1Emitter(v1Device(paper("garage"), ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, "Steve", false)))) == RenderTag.Data.V1
                        && RenderTag.dispatch(input(new CompoundTag())) == RenderTag.Data.V1,
                "v1 data and an empty tag are v1 data");

        // What the client draws: red to blue at the same brightness re-tints without another light level.
        LightState shownRed = RenderTag.lamp(input(lamp));
        LightState shownBlue = RenderTag.lamp(input(RenderTag.updateTag(RenderTag.lamp(blue))));
        check(shownRed.equals(red) && shownBlue.equals(blue), "The lamp's light survives its render update: " + shownRed + ", " + shownBlue);
        check(SmartLamp.lightLevel(shownRed) == SmartLamp.lightLevel(shownBlue) && shownRed.color().packed() != shownBlue.color().packed(),
                "Red to blue keeps the light level, so only the render update re-tints the lamp");
        LightState warm = red.merge(JsonParser.parseString("{\"color_temp_kelvin\":2700}").getAsJsonObject());
        LightState shownWarm = RenderTag.lamp(input(RenderTag.updateTag(RenderTag.lamp(warm))));
        check(shownWarm.color().equals(warm.color()) && shownWarm.kelvin() == null && shownWarm.brightness() == warm.brightness(),
                "A colour temperature renders as the colour it shows: " + shownWarm);
        check(RenderTag.lamp(input(nothing)).equals(new LightState(false, 255, ValueCodecs.Rgb.WHITE, null)), "A lamp missing render keys keeps the defaults");
    }

    /**
     * M3-15: what clients get of a Display Panel (M3 plan D18, D13 #7): its finished lines and the size they fit, or a
     * point in time with its size setting; a title only while it mirrors, a waiting flag only while it waits; colour and
     * glow. A client bounds what it reads as the server bounds what it sends. A saved panel is v2 data, its update tag a
     * render update; its link and state compound round-trip.
     */
    /**
     * M4-3 (plan D4): each v1 timer is told by its first own key, another block's v1 data is no timer's, and a timer's
     * keys written back while it waits for adoption read as the same settings and counters.
     */
    private static void timerKeys() {
        for (String key : List.of("intervalValue", "triggerModeIndex", "alarmTime", "cronExpression")) {
            CompoundTag tag = new CompoundTag();
            if (key.equals("intervalValue")) tag.putLong(key, 5L);
            else if (key.equals("cronExpression")) tag.putString(key, "");
            else tag.putInt(key, 0);
            check(LegacyNbtV1.hasTimerKeys(input(tag)), "A v1 timer is told by " + key);
        }
        check(!LegacyNbtV1.hasTimerKeys(input(v1Lamp(paper("home/kitchen"), 15, 0, 0, 15, 0, 0, 255, true)))
                && !LegacyNbtV1.hasTimerKeys(input(new CompoundTag())), "A lamp's v1 data and an empty compound are no timer's");
        Map<TimerKind, Timers.Settings> settings = Map.of(
                TimerKind.INTERVAL, Timers.Settings.DEFAULT.withInterval(90, Timers.Unit.MINUTES),
                TimerKind.GAME_TIME, Timers.Settings.DEFAULT.withTriggerAt(Timers.TriggerAt.SUNSET).withTimeTicks(4321),
                TimerKind.ALARM, Timers.Settings.DEFAULT.withTimeTicks(18000),
                TimerKind.CRON, Timers.Settings.DEFAULT.withCron("0 0 30 7 * * 1-5"));
        Map<TimerKind, TimerState> counters = Map.of(TimerKind.INTERVAL, new TimerState(41, -1, -1, 9), TimerKind.GAME_TIME, new TimerState(0, 8, -1, 9),
                TimerKind.ALARM, new TimerState(0, 8, -1, 9), TimerKind.CRON, new TimerState(0, -1, 77, 0));
        for (TimerKind kind : TimerKind.values()) {
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, REGISTRIES);
            LegacyNbtV1.writeTimer(output, kind, settings.get(kind), counters.get(kind));
            LegacyNbtV1.Timer read = LegacyNbtV1.timerState(input(output.buildResult()), kind);
            check(read.settings().equals(settings.get(kind)) && read.counters().equals(counters.get(kind)), kind + ": its v1 keys read back as written: " + read);
        }
        TagValueOutput disabled = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, REGISTRIES);
        LegacyNbtV1.writeTimer(disabled, TimerKind.ALARM, Timers.Settings.DEFAULT.withEnabled(false), TimerState.NEW);
        check(disabled.buildResult().getIntOr("alarmTime", 0) == -1, "A disabled alarm clock is one without an alarm, as v1 knew it");
        CompoundTag noon = new CompoundTag();
        noon.putInt("triggerModeIndex", 1);
        check(LegacyNbtV1.timerState(input(noon), TimerKind.GAME_TIME).settings().equals(Timers.Settings.DEFAULT.withTriggerAt(Timers.TriggerAt.NOON).withTimeTicks(6000)),
                "A v1 Daylight Timer without a custom time had noon's, as v1 read it");
    }

    private static void panelTags() {
        DisplayPanel.Panel washer = new DisplayPanel.Panel("Washer done!", DyeColor.LIME, true, DisplayPanel.Size.AUTO, 15);
        CompoundTag plain = RenderTag.updateTag(RenderTag.panel(DisplayPanel.Render.of(washer, null, false)));
        check(plain.getCompoundOrEmpty(RenderTag.KEY).keySet().equals(Set.of("lines", "layout", "color", "glow")), "An unbound panel renders its lines: " + plain);
        check(RenderTag.panel(input(plain)).equals(DisplayPanel.Render.of(washer, null, false)), "and reads them back");
        DisplayPanel.Render mirrored = DisplayPanel.Render.of(washer, "Laundry", true);
        CompoundTag titled = RenderTag.updateTag(RenderTag.panel(mirrored), TwinHealth.Hint.UNAVAILABLE);
        check(titled.getCompoundOrEmpty(RenderTag.KEY).keySet().equals(Set.of("title", "lines", "layout", "color", "glow", "waiting", "twin"))
                && RenderTag.panel(input(titled)).equals(mirrored) && RenderTag.twinHint(input(titled)) == TwinHealth.Hint.UNAVAILABLE,
                "A mirrored panel adds its title, waiting while it waits, and the twin hint: " + titled);
        DisplayPanel.Render alarm = DisplayPanel.Render.of(new DisplayPanel.Panel("2026-10-11T07:30:00+00:00", DyeColor.CYAN, false, DisplayPanel.Size.MEDIUM, 15), null, false);
        CompoundTag time = RenderTag.updateTag(RenderTag.panel(alarm));
        check(time.getCompoundOrEmpty(RenderTag.KEY).keySet().equals(Set.of("at", "size", "color", "glow")) && RenderTag.panel(input(time)).equals(alarm),
                "A point in time renders as epoch millis with the size setting: " + time);
        check(!plain.toString().contains("owner") && !titled.toString().contains("entity") && RenderTag.dispatch(input(plain)) == RenderTag.Data.RENDER,
                "No owner and no entity reach a client; the update tag is a render update");

        CompoundTag hostile = new CompoundTag();
        CompoundTag render = new CompoundTag();
        net.minecraft.nbt.ListTag lines = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < 10; i++) lines.add(net.minecraft.nbt.StringTag.valueOf("x".repeat(100)));
        render.put("lines", lines);
        render.putString("title", "t".repeat(100));
        render.putByte("layout", (byte) 0);
        render.putByte("size", (byte) 99);
        render.putByte("color", (byte) 99);
        hostile.put(RenderTag.KEY, render);
        DisplayPanel.Render bounded = RenderTag.panel(input(hostile));
        check(bounded.lines().size() == 3 && bounded.lines().stream().allMatch(line -> line.length() == 32) && bounded.title().length() == 32
                        && bounded.layout() == art.rehra.mineqtt.twin.core.DisplayText.Size.LARGE && bounded.size() == DisplayPanel.Size.AUTO && bounded.glow(),
                "A client reads at most 4 lines of 32 characters with the title, and a size it knows: " + bounded);
        CompoundTag empty = RenderTag.updateTag(new CompoundTag());
        DisplayPanel.Render nothing = RenderTag.panel(input(empty));
        check(nothing.lines().isEmpty() && nothing.title() == null && nothing.at().isEmpty() && nothing.color() == DisplayPanel.DEFAULT_COLOR && nothing.glow() && !nothing.waiting(),
                "A panel missing render keys draws nothing in the defaults");

        // The saved panel: v2 data in the mineqtt compound, the state round-trips through the link.
        DeviceLink link = new DeviceLink(DisplayPanel.TYPE);
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, REGISTRIES);
        link.save(output).store("state", DisplayPanel.Panel.CODEC, washer);
        CompoundTag saved = output.buildResult();
        check(RenderTag.dispatch(input(saved)) == RenderTag.Data.DEVICE, "A saved panel is v2 data: " + saved);
        DeviceLink loaded = new DeviceLink(DisplayPanel.TYPE);
        check(loaded.load(input(saved), false).flatMap(data -> data.read("state", DisplayPanel.Panel.CODEC)).orElseThrow().equals(washer),
                "Its message, look and level load again");
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
        ContainerItems.save(output, NonNullList.of(ItemStack.EMPTY, base, sub, on, off));
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
     * SmartPlugBlockEntity, an MqttSubscriberBlockEntity like the emitter: the device keys and the subscribed topic; it
     * saved no power, which only its block had. The M2 legacy layer saved the owner's UUID beside them since F11.
     */
    private static CompoundTag v1Plug(CompoundTag device, @Nullable UUID ownerUuid) {
        CompoundTag plug = v1Emitter(device);
        if (ownerUuid != null) plug.store("ownerUuid", UUIDUtil.CODEC, ownerUuid);
        return plug;
    }

    /**
     * MotionSensorBlockEntity, an MqttPublisherBlockEntity with nine slots: the device keys and the five filter slots
     * its tab showed (2 to 6, of which v1 read only 4 to 6), then MotionDetected, LastMotionTime (wall-clock) and
     * LastCount.
     */
    private static CompoundTag v1Motion(CompoundTag device, boolean motion, int lastCount) {
        CompoundTag sensor = device.copy();
        NonNullList<ItemStack> items = NonNullList.withSize(9, ItemStack.EMPTY);
        ContainerItems.load(input(device), items);
        items.set(6, spawnEgg(Items.ZOMBIE_SPAWN_EGG, EntityTypes.ZOMBIE));
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, REGISTRIES);
        ContainerItems.save(output, items);
        sensor.merge(output.buildResult());
        sensor.putByte("MotionDetected", (byte) (motion ? 1 : 0));
        sensor.putLong("LastMotionTime", 1_759_917_600_000L);
        sensor.putInt("LastCount", lastCount);
        return sensor;
    }

    /**
     * TemperatureSensorBlockEntity or HumiditySensorBlockEntity, MqttPublisherBlockEntities: the device keys and the
     * reading ({@code Temperature} or {@code Humidity}), the game time of the last publish and the interval in ticks.
     */
    private static CompoundTag v1Climate(CompoundTag device, String readingKey, double reading, int intervalTicks, long lastPublish) {
        CompoundTag sensor = device.copy();
        sensor.putDouble(readingKey, reading);
        sensor.putLong("LastPublishTime", lastPublish);
        sensor.putInt("UpdateIntervalTicks", intervalTicks);
        return sensor;
    }

    /**
     * StaticPublisherBlockEntity: the device keys with three slots (topic base and sub, and a third that no screen
     * showed), its message, the state of its last edge and its screen's status line.
     */
    private static CompoundTag v1Static(CompoundTag device, String message, boolean powered) {
        CompoundTag publisher = device.copy();
        publisher.putString("staticMessage", message);
        publisher.putBoolean("wasPowered", powered);
        publisher.putString("lastPublishStatus", powered ? "Published to " + device.getStringOr("topic", "") : "");
        return publisher;
    }

    /**
     * TopicRelayBlockEntity, an MqttSubscriberBlockEntity with four slots (the target topic's base and sub): the device
     * keys, the subscribed target topic, and its source topic, cached message, last edge, status line and subscribed
     * source.
     */
    private static CompoundTag v1Relay(CompoundTag device, String source, String cached) {
        CompoundTag relay = v1Emitter(device);
        relay.putString("sourceTopic", source);
        relay.putString("cachedMessage", cached);
        relay.putBoolean("wasPowered", false);
        relay.putBoolean("hasReceivedMessage", !cached.isEmpty());
        relay.putString("lastRelayStatus", "Received from " + source);
        relay.putString("currentSourceSubscribedTopic", source);
        return relay;
    }

    /**
     * DynamicPublisherBlockEntity, an MqttPublisherBlockEntity with two slots (topic base and sub): the device keys with
     * those slots, the data type's ordinal, its last edge and its status line.
     */
    private static CompoundTag v1Dynamic(CompoundTag device, ItemStack base, ItemStack sub, int dataType) {
        CompoundTag publisher = device.copy();
        TagValueOutput slots = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, REGISTRIES);
        ContainerItems.save(slots, NonNullList.of(ItemStack.EMPTY, base, sub));
        publisher.merge(slots.buildResult());
        publisher.putInt("dataType", dataType);
        publisher.putBoolean("wasPowered", false);
        publisher.putString("lastPublishStatus", "");
        return publisher;
    }

    /** RgbLedBlockEntity: two slots, the displayed and the target colour (0–15), Lit and Brightness. */
    private static CompoundTag v1Lamp(ItemStack base, int targetRed, int targetGreen, int targetBlue, int red, int green, int blue, int brightness, boolean lit) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, REGISTRIES);
        String topic = LegacyNbtV1.combinedTopic(base, ItemStack.EMPTY, false, "Steve");
        output.putString("topic", topic);
        output.putString("lastTabId", "rgb_status");
        output.putString("ownerName", "Steve");
        output.putBoolean("privateMode", false);
        ContainerItems.save(output, NonNullList.of(ItemStack.EMPTY, base, ItemStack.EMPTY));
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

    // Before 26.1 a spawn egg knows its mob without components.
    private static void bindEgg(Item egg, EntityType<?> type) {
        //? if >=26.1 {
        egg.builtInRegistryHolder().bindComponents(DataComponentMap.builder().addAll(DataComponents.COMMON_ITEM_COMPONENTS)
                .set(DataComponents.ENTITY_DATA, TypedEntityData.of(type, new CompoundTag())).build());
        //?}
    }

    // A spawn egg as a server has it: its mob is the default entity data of the item.
    private static ItemStack spawnEgg(Item egg, EntityType<?> type) {
        ItemStack stack = new ItemStack(egg);
        //? if >=1.21.9 {
        stack.set(DataComponents.ENTITY_DATA, TypedEntityData.of(type, new CompoundTag()));
        //?} else {
        /*CompoundTag data = new CompoundTag();
        data.putString("id", EntityType.getKey(type).toString());
        stack.set(DataComponents.ENTITY_DATA, net.minecraft.world.item.component.CustomData.of(data));
        *///?}
        return stack;
    }

    private static ValueInput input(CompoundTag tag) {
        return TagValueInput.create(ProblemReporter.DISCARDING, REGISTRIES, tag);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.blocks.entities.BlockEmitterBlockEntity;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.PlacerRules;
import art.rehra.mineqtt.device.RawLink;
import art.rehra.mineqtt.device.RawLinkSpec;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.BlockObserver;
import art.rehra.mineqtt.device.types.BlockPlacer;
import art.rehra.mineqtt.device.types.ContainerObserver;
import art.rehra.mineqtt.ha.HaEntity;
import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import art.rehra.mineqtt.mqtt.core.OfflinePolicy;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The world devices of M4 (plan D12). The Block Observer: set facing a chest, it reports the chest, and its comparator
 * level follows the chest's fill; its Raw Link's full payload carries no block entity data until the server allows it
 * ({@code blockObserver.exportBlockEntityData}, here without a claim mod, so the same claim), and then the chest's items;
 * a redstone wire flickering in front gives at most two {@code changed} events a second, the rest throttled; and a v1
 * Block Sensor set with its v1 data becomes a Block Observer whose v1 topic is its unretained, grandfathered Raw Link
 * with the data mode as its payload. The Block Placer: with the block placer switched on it places white wool from the
 * chest behind it and clears it back; TNT and block entity data are refused with their reason, a retained message
 * builds nothing, ten messages at once act four times, and switched off it answers disabled; a v1 Block Emitter becomes a
 * Block Placer listening on its v1 topic. The Container Observer: placed against a chest, it reports the chest empty, then
 * its items by id (a renamed stack's name never), in its state and in the state it publishes, and no container once the
 * chest is gone.
 */
public final class WorldDevicesGameTest implements FabricClientGameTest {
    private static final int Y = GameTestSession.GROUND_Y + 1;
    private static final BlockPos STAND = new BlockPos(0, Y, 0);
    private static final BlockPos OBSERVER = new BlockPos(3, Y, 0);
    private static final BlockPos TARGET = OBSERVER.east();
    private static final BlockPos LEGACY = new BlockPos(3, Y, 4);
    private static final BlockPos LEGACY_TARGET = LEGACY.east();
    private static final String CHANNEL = "world_devices_" + GameTestSession.BRIDGE_ID + "_observer";
    private static final String RAW_TOPIC = "mineqtt/shared/" + CHANNEL;
    private static final String LEGACY_TOPIC = "world_devices_" + GameTestSession.BRIDGE_ID + "/legacy_sensor";
    private static final BlockPos PLACER = new BlockPos(3, Y, 8);
    private static final BlockPos PLACER_TARGET = PLACER.west();
    private static final BlockPos PLACER_CHEST = PLACER.east();
    private static final BlockPos LEGACY_EMITTER = new BlockPos(3, Y, 12);
    private static final String LEGACY_EMITTER_TOPIC = "world_devices_" + GameTestSession.BRIDGE_ID + "/legacy_emitter";
    private static final BlockPos CONTAINER_OBSERVER = new BlockPos(3, Y, 16);
    private static final BlockPos OBSERVED_CHEST = CONTAINER_OBSERVER.east();
    private static final int ANSWER_TICKS = 60;
    private static final int SETTLE_TICKS = 10;
    // A placer looks at its container every 100 ticks; a little more for the state to reach the runtime.
    private static final int CHOICES_TICKS = 130;
    private static final int FLICKERS = 20;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "world_devices", WorldDevicesGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        String observer = observer(session);
        Optional<BrokerConfig> broker = session.broker();
        if (broker.isEmpty()) {
            session.skip("the observer's Raw Link payloads reach the broker", "no broker (" + GameTestSession.BROKER_ENV + " is not set)");
        } else {
            observerPayloads(session, observer, broker.get());
        }
        flicker(session, observer);
        migratedSensor(session, broker);
        placer(session, broker);
        migratedEmitter(session);
        containerObserver(session);
    }

    /** Set facing a chest, the observer reports it; filling the chest raises its comparator level. */
    private static String observer(GameTestSession session) {
        setblock(session, TARGET, "minecraft:chest[facing=west]");
        setblock(session, OBSERVER, "mineqtt:block_sensor_block[facing=east]");
        session.awaitServer(s -> s.overworld().getBlockEntity(OBSERVER) instanceof DeviceHost host && host.deviceLink().attached());
        // The console placed it; the player claims it, so its Raw Link resolves for an owner.
        session.check(session.onServer(s -> {
            BlockEntity blockEntity = s.overworld().getBlockEntity(OBSERVER);
            return ((DeviceHost) blockEntity).deviceLink().claim(blockEntity, session.playerId(), session.playerName());
        }), "the player claims the observer");
        session.check(BlockObserver.TYPE.equals(session.device(OBSERVER, DeviceLink::type)), "a placed Block Sensor block is a Block Observer device");
        session.check(session.await(() -> state(session, OBSERVER).get("block").getAsString().equals("minecraft:chest"), ANSWER_TICKS),
                "it reports the chest in front (" + state(session, OBSERVER) + ")");
        JsonObject state = state(session, OBSERVER);
        session.check(state.get("signal").getAsInt() == 0 && state.getAsJsonObject("properties").get("facing").getAsString().equals("west")
                        && state.get("payload").getAsString().equals("full") && state.get("change_on").getAsString().equals("state"),
                "an empty chest gives level 0; its properties are attributes; the defaults are full and state (" + state + ")");
        for (int slot = 0; slot < 9; slot++) {
            session.consoleCommand("item replace block " + TARGET.getX() + " " + TARGET.getY() + " " + TARGET.getZ() + " container." + slot + " with minecraft:stone 64");
        }
        session.check(session.await(() -> state(session, OBSERVER).get("signal").getAsInt() > 0, ANSWER_TICKS),
                "filling the chest raises the comparator level the observer reports (" + state(session, OBSERVER) + ")");
        session.check(!state(session, OBSERVER).toString().contains("minecraft:stone"), "the chest's items are never part of the observer's state");
        session.aim(OBSERVER);
        session.screenshot("world_devices_observer");
        return session.device(OBSERVER, DeviceLink::slug);
    }

    /**
     * The full payload has no block entity data by default; once the server allows it (no claim mod: the same claim) the
     * next sample carries the chest's items, and taking the flag back leaves them out again.
     */
    private static void observerPayloads(GameTestSession session, String slug, BrokerConfig broker) {
        session.clearAtClose(RAW_TOPIC);
        session.consoleCommand("mineqtt devices raw " + slug + " channel " + CHANNEL);
        RawLink raw = session.device(OBSERVER, DeviceLink::raw);
        session.check(raw != null && RAW_TOPIC.equals(raw.topic()) && raw.active(), "its Raw Link channel resolves to " + RAW_TOPIC + " (" + raw + ")");
        boolean export = session.onServer(s -> MineQTTConfig.blockObserverExportBlockEntityData);
        try (BrokerProbe probe = BrokerProbe.connect(broker); BrokerProbe.Listener listener = probe.listen(RAW_TOPIC)) {
            // A payload setting sends the payload of the block in front at once.
            session.consoleCommand("mineqtt devices setting " + slug + " payload block_id");
            session.check(next(session, listener, "minecraft:chest"::equals).isPresent(), "payload block_id sends the chest's id");
            session.consoleCommand("mineqtt devices setting " + slug + " payload full");
            Optional<String> full = next(session, listener, text -> text.startsWith("{"));
            JsonObject fullJson = full.map(text -> JsonParser.parseString(text).getAsJsonObject()).orElse(new JsonObject());
            session.check(fullJson.has("block_entity") && fullJson.get("block_entity").isJsonNull() && fullJson.get("id").getAsString().equals("minecraft:chest"),
                    "the full payload names the chest without its block entity data (" + full.orElse("nothing") + ")");

            session.onServer(s -> MineQTTConfig.blockObserverExportBlockEntityData = true);
            Optional<String> exported = next(session, listener, text -> text.contains("\"block_entity\":{"));
            session.check(exported.filter(text -> text.contains("minecraft:stone")).isPresent(),
                    "with blockObserver.exportBlockEntityData and no claim mod, the next sample carries the chest's items (" + exported.orElse("nothing") + ")");
            session.onServer(s -> MineQTTConfig.blockObserverExportBlockEntityData = false);
            session.check(next(session, listener, text -> text.contains("\"block_entity\":null")).isPresent(), "without the flag they are left out again");
            session.check(probe.retained(RAW_TOPIC).isEmpty(), "nothing is retained on its Raw Link topic, as v1 sent it");
        } finally {
            session.onServer(s -> MineQTTConfig.blockObserverExportBlockEntityData = export);
        }
    }

    /** A wire flickering in front for two seconds: every change is counted, at most two changed events a second published. */
    private static void flicker(GameTestSession session, String slug) {
        setblock(session, TARGET, "minecraft:redstone_wire");
        session.check(session.await(() -> state(session, OBSERVER).get("block").getAsString().equals("minecraft:redstone_wire"), ANSWER_TICKS),
                "the observer reports the wire that replaced the chest");
        UUID id = id(session, slug);
        long before = throttled(session, id);
        BlockPos power = TARGET.south();
        long start = System.currentTimeMillis();
        for (int flicker = 0; flicker < FLICKERS; flicker++) {
            setblock(session, power, flicker % 2 == 0 ? "minecraft:redstone_block" : "minecraft:air");
            session.context().waitTicks(2);
        }
        double seconds = (System.currentTimeMillis() - start) / 1000.0;
        long held = throttled(session, id) - before;
        long published = FLICKERS - held;
        // The throttle runs on the wall clock: at most one published per 500 ms, plus the first.
        session.check(held > 0 && published <= Math.ceil(seconds * 2) + 1, "a flickering wire's changed events are throttled to two a second: " + published
                + " of " + FLICKERS + " published in " + seconds + " s (" + session.onRuntime(runtime -> runtime.devices().trafficSummary(id)) + ")");
        session.check(session.await(() -> state(session, OBSERVER).getAsJsonObject("properties").get("power").getAsString().equals("0"), ANSWER_TICKS),
                "its state settles on the wire's last power, 0");
        setblock(session, TARGET, "minecraft:air");
    }

    /**
     * A v1 Block Sensor with its data mode, topic paper and owner becomes a Block Observer: its topic is its grandfathered
     * Raw Link output, not retained, its mode its payload, and it sends the display name of the block in front there.
     */
    private static void migratedSensor(GameTestSession session, Optional<BrokerConfig> broker) {
        String player = session.playerName();
        setblock(session, LEGACY_TARGET, "minecraft:stone");
        setblock(session, LEGACY, "mineqtt:block_sensor_block[facing=east]{BlockSensorDataMode:\"name\",ownerName:\"" + player + "\",privateMode:0b,topic:\""
                + LEGACY_TOPIC + "\",Items:[{Slot:0b,id:\"minecraft:paper\",count:1,components:{\"minecraft:custom_name\":\"" + LEGACY_TOPIC + "\"}}]}");
        session.awaitServer(s -> s.overworld().getBlockEntity(LEGACY) instanceof DeviceHost host && host.deviceLink().attached());
        RawLink raw = session.device(LEGACY, DeviceLink::raw);
        session.check(raw != null && raw.topic().equals(LEGACY_TOPIC) && raw.grandfathered() != null && raw.active()
                        && !raw.effectiveRetain(BlockObserver.TYPE.rawLink().orElseThrow()),
                "its v1 topic is its grandfathered, unretained Raw Link (" + raw + ")");
        session.check(player.equals(session.device(LEGACY, DeviceLink::ownerName)) && Integer.valueOf(1).equals(session.device(LEGACY, link -> link.legacyItems().size())),
                "it keeps its owner and slot paper");
        session.check(session.await(() -> state(session, LEGACY).get("payload").getAsString().equals("name")
                && state(session, LEGACY).get("block").getAsString().equals("minecraft:stone"), ANSWER_TICKS),
                "its data mode is its payload, and it reports the stone (" + state(session, LEGACY) + ")");
        if (broker.isEmpty()) {
            session.skip("the migrated observer sends the display name on its v1 topic", "no broker (" + GameTestSession.BROKER_ENV + " is not set)");
            return;
        }
        session.clearAtClose(LEGACY_TOPIC);
        try (BrokerProbe probe = BrokerProbe.connect(broker.get()); BrokerProbe.Listener listener = probe.listen(LEGACY_TOPIC)) {
            setblock(session, LEGACY_TARGET, "minecraft:dirt");
            session.check(next(session, listener, "Dirt"::equals).isPresent(), "a change in front sends the display name on its v1 topic");
            session.check(probe.retained(LEGACY_TOPIC).isEmpty(), "it sends without retain, as v1 did");
        }
    }

    /**
     * The Block Placer with the block placer switched on: white wool set by its setting is placed from the chest behind it
     * and cleared back into it; the chest's blocks are its choices, Home Assistant's select offers them, and stone from the
     * chest is placed though the tag lacks it; TNT and block entity data are refused with their reason in last_result, the latter counted
     * as a dropped message; a retained message waiting on its topic builds nothing; ten messages at once act four times;
     * switched off, it answers disabled.
     */
    private static void placer(GameTestSession session, Optional<BrokerConfig> broker) {
        boolean enabled = session.onServer(s -> MineQTTConfig.blockPlacerEnabled);
        boolean consume = session.onServer(s -> MineQTTConfig.blockPlacerConsumeItems);
        boolean unlocks = session.onServer(s -> MineQTTConfig.blockPlacerContainerUnlocks);
        session.onServer(s -> {
            MineQTTConfig.blockPlacerEnabled = true;
            MineQTTConfig.blockPlacerConsumeItems = true;
            MineQTTConfig.blockPlacerContainerUnlocks = true;
            return null;
        });
        try {
            setblock(session, PLACER_CHEST, "minecraft:chest[facing=north]");
            session.consoleCommand("item replace block " + PLACER_CHEST.getX() + " " + PLACER_CHEST.getY() + " " + PLACER_CHEST.getZ()
                    + " container.0 with minecraft:white_wool 2");
            setblock(session, PLACER, "mineqtt:block_emitter_block[facing=west]");
            session.awaitServer(s -> s.overworld().getBlockEntity(PLACER) instanceof DeviceHost host && host.deviceLink().attached());
            session.check(session.onServer(s -> {
                BlockEntity blockEntity = s.overworld().getBlockEntity(PLACER);
                return ((DeviceHost) blockEntity).deviceLink().claim(blockEntity, session.playerId(), session.playerName());
            }), "the player claims the placer");
            session.check(BlockPlacer.TYPE.equals(session.device(PLACER, DeviceLink::type)), "a placed Block Emitter block is a Block Placer device");
            String slug = session.device(PLACER, DeviceLink::slug);
            UUID id = id(session, slug);

            session.consoleCommand("mineqtt devices setting " + slug + " block minecraft:white_wool");
            session.consoleCommand("mineqtt devices action " + slug + " place");
            session.check(session.await(() -> block(session, PLACER_TARGET).equals("minecraft:white_wool"), ANSWER_TICKS), "place puts white wool in front");
            JsonObject placed = state(session, PLACER);
            session.check(placed.get("placed").getAsBoolean() && placed.get("last_result").getAsString().equals("placed minecraft:white_wool")
                    && woolInChest(session) == 1, "from the chest behind it, and says so (" + placed + ", " + woolInChest(session) + " left)");
            session.screenshot("world_devices_placer");
            session.consoleCommand("mineqtt devices action " + slug + " clear");
            session.check(session.await(() -> block(session, PLACER_TARGET).equals("minecraft:air") && woolInChest(session) == 2, ANSWER_TICKS),
                    "clear removes it and gives the wool back (" + woolInChest(session) + " in the chest)");
            session.check(!state(session, PLACER).get("placed").getAsBoolean(), "it has placed nothing now");

            // The container behind decides (blockPlacer.containerUnlocks, on by default): its blocks are the choices Home
            // Assistant's select offers, and stone, which is not on #mineqtt:placeable, is placed from it.
            session.check(choices(session).equals(List.of("minecraft:white_wool")), "the chest's wool is its choice (" + state(session, PLACER) + ")");
            session.consoleCommand("item replace block " + PLACER_CHEST.getX() + " " + PLACER_CHEST.getY() + " " + PLACER_CHEST.getZ()
                    + " container.1 with minecraft:stone 1");
            List<String> stocked = List.of("minecraft:stone", "minecraft:white_wool");
            session.check(session.await(() -> choices(session).equals(stocked), CHOICES_TICKS), "stone put into the chest joins them (" + choices(session) + ")");
            session.check(session.await(() -> offered(session, id).equals(stocked), ANSWER_TICKS),
                    "and Home Assistant's select offers them (" + offered(session, id) + ")");
            session.consoleCommand("mineqtt devices setting " + slug + " block minecraft:stone");
            session.consoleCommand("mineqtt devices action " + slug + " place");
            session.check(session.await(() -> block(session, PLACER_TARGET).equals("minecraft:stone"), ANSWER_TICKS),
                    "stone from the chest is placed though the tag lacks it (" + state(session, PLACER) + ")");
            session.check(choices(session).equals(List.of("minecraft:stone", "minecraft:white_wool")), "the block it is set to stays a choice with the chest's stone gone");
            session.consoleCommand("mineqtt devices action " + slug + " clear");
            session.check(session.await(() -> block(session, PLACER_TARGET).equals("minecraft:air"), ANSWER_TICKS), "and cleared back into the chest");
            session.consoleCommand("mineqtt devices setting " + slug + " block minecraft:white_wool");

            session.consoleCommand("mineqtt devices setting " + slug + " block minecraft:tnt");
            JsonObject tnt = state(session, PLACER);
            session.check(tnt.get("block").getAsString().equals("minecraft:white_wool") && tnt.get("last_result").getAsString().contains("TNT"),
                    "TNT is refused, with the reason in last_result (" + tnt + ")");

            String channel = "world_devices_" + GameTestSession.BRIDGE_ID + "_placer";
            String topic = "mineqtt/shared/" + channel;
            if (broker.isPresent()) {
                // A build retained on the topic before the placer listens never builds (D12).
                session.clearAtClose(topic);
                try (BrokerProbe probe = BrokerProbe.connect(broker.get())) {
                    probe.publish(topic, "minecraft:white_wool", true);
                }
            }
            session.consoleCommand("mineqtt devices raw " + slug + " channel " + channel);
            session.check(topic.equals(session.device(PLACER, link -> link.raw() == null ? null : link.raw().topic())), "its Raw Link listens on " + topic);
            session.context().waitTicks(SETTLE_TICKS);
            session.check(block(session, PLACER_TARGET).equals("minecraft:air"),
                    broker.isPresent() ? "the build retained on its topic built nothing" : "nothing is built without a message");

            long droppedBefore = dropped(session.onRuntime(runtime -> runtime.devices().trafficSummary(id)));
            local(session, topic, "{\"id\":\"minecraft:chest\",\"block_entity\":{}}");
            session.check(session.await(() -> state(session, PLACER).get("last_result").getAsString().equals(PlacerRules.NO_NBT), ANSWER_TICKS)
                    && block(session, PLACER_TARGET).equals("minecraft:air"), "block entity data is refused: " + state(session, PLACER));
            session.check(dropped(session.onRuntime(runtime -> runtime.devices().trafficSummary(id))) == droppedBefore + 1,
                    "and counted as a dropped message (" + session.onRuntime(runtime -> runtime.devices().trafficSummary(id)) + ")");

            // Ten messages in one tick, once the four actions a second are back: place, clear, place, clear, then too fast.
            session.context().waitTicks(2 * SETTLE_TICKS + 5);
            long beforeBurst = dropped(session.onRuntime(runtime -> runtime.devices().trafficSummary(id)));
            session.onRuntime(runtime -> {
                for (int message = 0; message < 10; message++) local(runtime, topic, message % 2 == 0 ? "place" : "clear");
                return null;
            });
            session.context().waitTicks(SETTLE_TICKS);
            long refused = dropped(session.onRuntime(runtime -> runtime.devices().trafficSummary(id))) - beforeBurst;
            JsonObject burst = state(session, PLACER);
            session.check(refused == 6 && burst.get("last_result").getAsString().startsWith("too fast") && block(session, PLACER_TARGET).equals("minecraft:air")
                    && woolInChest(session) == 2, "ten messages at once act four times, six are refused (" + refused + ", " + burst + ")");

            session.onServer(s -> MineQTTConfig.blockPlacerEnabled = false);
            session.check(session.await(() -> state(session, PLACER).get("last_result").getAsString().equals(BlockEmitterBlockEntity.DISABLED), ANSWER_TICKS),
                    "switched off, its last result says so");
            session.consoleCommand("mineqtt devices action " + slug + " place");
            session.context().waitTicks(SETTLE_TICKS);
            session.check(block(session, PLACER_TARGET).equals("minecraft:air")
                    && state(session, PLACER).get("last_result").getAsString().equals(BlockEmitterBlockEntity.DISABLED), "and every action is answered with disabled");
        } finally {
            session.onServer(s -> {
                MineQTTConfig.blockPlacerEnabled = enabled;
                MineQTTConfig.blockPlacerConsumeItems = consume;
                MineQTTConfig.blockPlacerContainerUnlocks = unlocks;
                return null;
            });
        }
        for (BlockPos pos : List.of(PLACER_TARGET, PLACER, PLACER_CHEST)) setblock(session, pos, "minecraft:air");
    }

    /** A v1 Block Emitter with its listen topic and owner becomes a Block Placer whose topic is its grandfathered Raw Link input. */
    private static void migratedEmitter(GameTestSession session) {
        String player = session.playerName();
        setblock(session, LEGACY_EMITTER, "mineqtt:block_emitter_block[facing=west]{currentSubscribedTopic:\"" + LEGACY_EMITTER_TOPIC + "\",ownerName:\""
                + player + "\",privateMode:0b,topic:\"" + LEGACY_EMITTER_TOPIC + "\",lastStatus:\"Ready\",Items:[{Slot:0b,id:\"minecraft:paper\",count:1,"
                + "components:{\"minecraft:custom_name\":\"" + LEGACY_EMITTER_TOPIC + "\"}}]}");
        session.awaitServer(s -> s.overworld().getBlockEntity(LEGACY_EMITTER) instanceof DeviceHost host && host.deviceLink().attached());
        RawLink raw = session.device(LEGACY_EMITTER, DeviceLink::raw);
        session.check(raw != null && raw.topic().equals(LEGACY_EMITTER_TOPIC) && raw.direction() == RawLinkSpec.Direction.IN && raw.grandfathered() != null,
                "its v1 topic is its grandfathered Raw Link input (" + raw + ")");
        session.check(BlockPlacer.TYPE.equals(session.device(LEGACY_EMITTER, DeviceLink::type)) && state(session, LEGACY_EMITTER).get("block").getAsString().isEmpty()
                        && Integer.valueOf(1).equals(session.device(LEGACY_EMITTER, link -> link.legacyItems().size())),
                "it is a Block Placer set to no block, its slot paper kept (" + state(session, LEGACY_EMITTER) + ")");
        setblock(session, LEGACY_EMITTER, "minecraft:air");
    }

    /**
     * Placed against a chest, the Container Observer reports it empty; filled, its items by id with their counts, the totals
     * and the fill, in its state and in the state it publishes; a renamed stack counts as its item and its name stays on the
     * server. With the chest gone it reports no container.
     */
    private static void containerObserver(GameTestSession session) {
        setblock(session, OBSERVED_CHEST, "minecraft:chest[facing=west]");
        setblock(session, CONTAINER_OBSERVER, "mineqtt:container_observer[facing=east]");
        session.awaitServer(s -> s.overworld().getBlockEntity(CONTAINER_OBSERVER) instanceof DeviceHost host && host.deviceLink().attached());
        session.check(session.onServer(s -> {
            BlockEntity blockEntity = s.overworld().getBlockEntity(CONTAINER_OBSERVER);
            return ((DeviceHost) blockEntity).deviceLink().claim(blockEntity, session.playerId(), session.playerName());
        }), "the player claims the Container Observer");
        session.check(ContainerObserver.TYPE.equals(session.device(CONTAINER_OBSERVER, DeviceLink::type)), "a placed container_observer block is a Container Observer device");
        session.check(session.await(() -> state(session, CONTAINER_OBSERVER).get("status").getAsString().equals("ok"), ANSWER_TICKS),
                "it reads the chest (" + state(session, CONTAINER_OBSERVER) + ")");
        JsonObject empty = state(session, CONTAINER_OBSERVER);
        session.check(empty.get("container").getAsString().equals("minecraft:chest") && empty.get("empty").getAsBoolean() && empty.get("total").getAsInt() == 0
                        && empty.get("slots_total").getAsInt() == 27 && empty.getAsJsonObject("items").isEmpty() && empty.get("fill").getAsDouble() == 0,
                "an empty chest of 27 slots (" + empty + ")");

        session.onServer(s -> {
            Container chest = (Container) s.overworld().getBlockEntity(OBSERVED_CHEST);
            chest.setItem(0, new ItemStack(Items.STONE, 64));
            chest.setItem(4, new ItemStack(Items.DIAMOND, 3));
            ItemStack secret = new ItemStack(Items.PAPER);
            secret.set(DataComponents.CUSTOM_NAME, Component.literal("Secret Plans"));
            chest.setItem(26, secret);
            chest.setChanged();
            return null;
        });
        session.check(session.await(() -> state(session, CONTAINER_OBSERVER).get("total").getAsInt() == 68, ANSWER_TICKS),
                "filled, it counts the items (" + state(session, CONTAINER_OBSERVER) + ")");
        JsonObject filled = state(session, CONTAINER_OBSERVER);
        JsonObject items = filled.getAsJsonObject("items");
        session.check(items.size() == 3 && items.get("minecraft:stone").getAsInt() == 64 && items.get("minecraft:diamond").getAsInt() == 3
                        && items.get("minecraft:paper").getAsInt() == 1 && filled.get("slots_used").getAsInt() == 3 && !filled.get("empty").getAsBoolean()
                        && !filled.get("full").getAsBoolean() && filled.get("fill").getAsDouble() > 0, "its items by id, its slots and fill (" + filled + ")");
        session.check(!filled.toString().contains("Secret"), "a renamed stack counts as its item; its name never reaches the state");
        UUID id = id(session, session.device(CONTAINER_OBSERVER, DeviceLink::slug));
        session.check(session.await(() -> {
            JsonObject published = session.onRuntime(runtime -> {
                DeviceRecord record = runtime.registry().record(id);
                return record == null ? null : record.lastState();
            });
            return published != null && published.has("total") && published.get("total").getAsInt() == 68;
        }, ANSWER_TICKS), "the state it published carries the same counts");
        // Its back, the face players see, from beside it: it stands beyond reach of the stand.
        session.teleport(CONTAINER_OBSERVER.west(2));
        session.aim(CONTAINER_OBSERVER);
        session.screenshot("world_devices_container_observer");

        setblock(session, OBSERVED_CHEST, "minecraft:air");
        session.check(session.await(() -> state(session, CONTAINER_OBSERVER).get("status").getAsString().equals("no_container"), ANSWER_TICKS),
                "with the chest gone it reports no container (" + state(session, CONTAINER_OBSERVER) + ")");
        JsonObject gone = state(session, CONTAINER_OBSERVER);
        session.check(gone.getAsJsonObject("items").isEmpty() && gone.get("total").getAsInt() == 0 && gone.get("container").getAsString().isEmpty(),
                "and nothing of what the chest held (" + gone + ")");
        setblock(session, CONTAINER_OBSERVER, "minecraft:air");
        session.teleport(STAND);
    }

    // A message on a topic through the runtime's own MQTT service, which delivers it to this server's subscribers.
    private static void local(GameTestSession session, String topic, String payload) {
        session.onRuntime(runtime -> {
            local(runtime, topic, payload);
            return null;
        });
    }

    private static void local(MineqttRuntime runtime, String topic, String payload) {
        runtime.mqtt().publish(new Outbound(topic, payload.getBytes(StandardCharsets.UTF_8), 0, false, OfflinePolicy.DROP, Outbound.Delivery.EDGE,
                UUID.randomUUID()));
    }

    // The dropped count of a traffic summary: "3 in (1 dropped), 2 out".
    private static long dropped(String summary) {
        int open = summary.indexOf('(');
        return open < 0 ? 0 : Long.parseLong(summary.substring(open + 1, summary.indexOf(' ', open)));
    }

    // The placer's choices in its state.
    private static List<String> choices(GameTestSession session) {
        List<String> choices = new ArrayList<>();
        JsonObject state = state(session, PLACER);
        if (state.get("choices") instanceof JsonArray array) array.forEach(entry -> choices.add(entry.getAsString()));
        return choices;
    }

    // The options the placer's discovery offers Home Assistant now: its select over the state the runtime last published.
    private static List<String> offered(GameTestSession session, UUID id) {
        return session.onRuntime(runtime -> {
            DeviceRecord record = runtime.registry().record(id);
            return BlockPlacer.TYPE.entities().stream().filter(entity -> entity instanceof HaEntity.Select).map(entity -> (HaEntity.Select) entity)
                    .findFirst().orElseThrow().currentOptions(record == null ? null : record.lastState());
        });
    }

    private static int woolInChest(GameTestSession session) {
        return session.onServer(s -> s.overworld().getBlockEntity(PLACER_CHEST) instanceof Container chest ? chest.countItem(Items.WOOL.white()) : -1);
    }

    private static String block(GameTestSession session, BlockPos pos) {
        return session.onServer(s -> BuiltInRegistries.BLOCK.getKey(s.overworld().getBlockState(pos).getBlock()).toString());
    }

    private static UUID id(GameTestSession session, String slug) {
        return session.onRuntime(runtime -> runtime.registry().records().stream().filter(record -> record.slug().equals(slug))
                .map(DeviceRecord::id).findFirst().orElseThrow());
    }

    // The next live message on the listener's topic that matches, within the answer time.
    private static Optional<String> next(GameTestSession session, BrokerProbe.Listener listener, Predicate<String> matches) {
        for (int tick = 0; tick < ANSWER_TICKS; tick += 5) {
            Optional<String> message;
            while ((message = listener.nextLive()).isPresent()) {
                if (matches.test(message.get())) return message;
            }
            session.context().waitTicks(5);
        }
        return Optional.empty();
    }

    private static long throttled(GameTestSession session, UUID id) {
        String summary = session.onRuntime(runtime -> runtime.devices().trafficSummary(id));
        int at = summary.indexOf(" events throttled");
        return at < 0 ? 0 : Long.parseLong(summary.substring(summary.lastIndexOf(' ', at - 1) + 1, at));
    }

    private static void setblock(GameTestSession session, BlockPos pos, String block) {
        session.consoleCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block);
    }

    private static JsonObject state(GameTestSession session, BlockPos pos) {
        return session.onServer(s -> {
            StateWriter state = new StateWriter();
            if (s.overworld().getBlockEntity(pos) instanceof DeviceHost host) host.writeState(state);
            return state.json();
        });
    }
}

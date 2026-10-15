package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.compat.ItemBlockEntityData;
import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.blocks.TemperatureSensorBlock;
import art.rehra.mineqtt.blocks.entities.MineqttBlockEntityTypes;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.RawLink;
import art.rehra.mineqtt.device.RawTopics;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.items.MineqttItems;
import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import art.rehra.mineqtt.ui.device.ControlsTab;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The Climate Sensor and the humidity sensor's conversion (M2-19). The player places a Climate Sensor, which reads the
 * temperature, humidity, illuminance and biome of its spot at once; glowstone beside it raises the illuminance at the
 * next reading, and its screen has the interval and the Raw Link's reading on its Controls tab. A v1 humidity sensor set
 * facing east with its v1 data becomes a Climate Sensor facing east that publishes the humidity on its v1 topic without
 * retain, keeps its slots, owner and anvil name; a humidity sensor item placed before any inventory tick converted it,
 * with forged data naming another owner and their topic, becomes a Climate Sensor placed by the player (design §8.6);
 * humidity sensor items turn into Climate Sensors in the player's inventory, keeping their name; and a crafter turns a
 * humidity sensor from its grid into a Climate Sensor through the 1:1 recipe, as items in containers convert.
 */
public final class ClimateGameTest implements FabricClientGameTest {
    private static final int Y = GameTestSession.GROUND_Y + 1;
    private static final BlockPos STAND = new BlockPos(0, Y, 0);
    private static final BlockPos SENSOR_GROUND = new BlockPos(2, GameTestSession.GROUND_Y, 1);
    private static final BlockPos LEGACY = new BlockPos(-2, Y, 2);
    private static final BlockPos LEGACY_ITEM_GROUND = new BlockPos(2, GameTestSession.GROUND_Y, -2);
    private static final BlockPos CRAFTER = new BlockPos(0, Y, 4);
    private static final String LEGACY_TOPIC = "climate_" + GameTestSession.BRIDGE_ID + "/humidity";
    private static final String FORGED_TOPIC = "zigbee2mqtt/climate_gametest/set";
    private static final UUID VICTIM = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final int ANSWER_TICKS = 60;
    private static final int BROKER_ATTEMPTS = 20;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "climate", ClimateGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        placed(session);
        converted(session);
        placedLegacy(session);
        items(session);
        crafter(session);
    }

    /** A placed sensor reads its spot at once; more light raises its illuminance at the next reading; its screen. */
    private static void placed(GameTestSession session) {
        BlockPos sensor = session.place(MineqttBlocks.TEMPERATURE_SENSOR_BLOCK.get(), SENSOR_GROUND);
        session.awaitServer(s -> s.overworld().getBlockEntity(sensor) instanceof DeviceHost host && host.deviceLink().attached());
        session.check(session.onServer(s -> s.overworld().getBlockState(sensor).getValue(TemperatureSensorBlock.FACING)) == Direction.UP,
                "a sensor placed on the ground faces up");
        JsonObject state = state(session, sensor);
        session.check(state.get("biome").getAsString().startsWith("minecraft:") && state.has("temperature") && state.has("humidity")
                && state.get("interval").getAsInt() == 10 && state.get("raw_value").getAsString().equals("temperature"),
                "a placed sensor has read its spot and its defaults (" + state + ")");
        int before = state.get("illuminance").getAsInt();
        String slug = session.device(sensor, DeviceLink::slug);
        session.consoleCommand("mineqtt devices setting " + slug + " interval 1");
        session.consoleCommand("setblock " + (sensor.getX() + 1) + " " + sensor.getY() + " " + sensor.getZ() + " minecraft:glowstone");
        session.check(session.await(() -> state(session, sensor).get("illuminance").getAsInt() > before, ANSWER_TICKS),
                "glowstone beside it raises the illuminance at the next reading (" + before + " lx to " + state(session, sensor).get("illuminance") + " lx)");
        session.aim(sensor);
        session.screenshot("climate_placed");

        session.openBlock(sensor);
        session.check(session.tabIds().contains(ControlsTab.ID), "its screen has a Controls tab");
        session.clickTab(ControlsTab.ID);
        session.check(session.screenView().map(view -> view.state().contains("\"interval\":1")).orElse(false), "its view shows the interval of 1 s");
        session.screenshot("climate_controls");
        session.closeScreen();
    }

    /**
     * A v1 humidity sensor facing east, with v1's keys, a paper topic, its owner and an anvil name, becomes a Climate
     * Sensor facing east on the next tick and keeps publishing its humidity on its v1 topic.
     */
    private static void converted(GameTestSession session) {
        String player = session.playerName();
        // The wall it hangs on: facing east, it is mounted on the block to its west.
        session.consoleCommand("setblock " + (LEGACY.getX() - 1) + " " + LEGACY.getY() + " " + LEGACY.getZ() + " minecraft:stone");
        session.consoleCommand("setblock " + LEGACY.getX() + " " + LEGACY.getY() + " " + LEGACY.getZ() + " mineqtt:humidity_sensor_block[facing=east]"
                + "{Humidity:61.5d,UpdateIntervalTicks:40,LastPublishTime:0L,ownerName:\"" + player + "\",privateMode:0b,topic:\"" + LEGACY_TOPIC + "\","
                + "CustomName:\"Greenhouse Air\",Items:[{Slot:0b,id:\"minecraft:paper\",count:1,components:{\"minecraft:custom_name\":\"" + LEGACY_TOPIC + "\"}}]}");
        session.check(session.await(() -> session.onServer(s -> s.overworld().getBlockState(LEGACY).is(MineqttBlocks.TEMPERATURE_SENSOR_BLOCK.get())
                && s.overworld().getBlockEntity(LEGACY) instanceof DeviceHost host && host.deviceLink().attached()), ANSWER_TICKS),
                "the humidity sensor became an attached Climate Sensor");
        session.check(session.onServer(s -> s.overworld().getBlockState(LEGACY).getValue(TemperatureSensorBlock.FACING)) == Direction.EAST,
                "it keeps its facing");
        RawLink raw = session.device(LEGACY, DeviceLink::raw);
        session.check(raw != null && raw.topic().equals(LEGACY_TOPIC) && raw.grandfathered() != null && raw.active(),
                "its v1 topic is its grandfathered Raw Link (" + raw + ")");
        session.check("Greenhouse Air".equals(session.device(LEGACY, DeviceLink::name)) && player.equals(session.device(LEGACY, DeviceLink::ownerName))
                        && Integer.valueOf(1).equals(session.device(LEGACY, link -> link.legacyItems().size())),
                "it keeps its anvil name, owner and slot paper");
        JsonObject state = state(session, LEGACY);
        session.check(state.get("raw_value").getAsString().equals("humidity") && state.get("interval").getAsInt() == 2,
                "it publishes the humidity, every 2 s as its 40 ticks (" + state + ")");
        session.aim(LEGACY);
        session.screenshot("climate_converted");

        Optional<BrokerConfig> broker = session.broker();
        if (broker.isEmpty()) {
            session.skip("the converted sensor publishes its humidity on its v1 topic", "no broker (" + GameTestSession.BROKER_ENV + " is not set)");
            return;
        }
        // A retained copy would be a regression; it must not outlive the suite either.
        session.clearAtClose(LEGACY_TOPIC);
        try (BrokerProbe probe = BrokerProbe.connect(broker.get())) {
            // The next reading, as v1 sent it: live, and the humidity, which differs from the temperature here.
            Optional<String> payload = Optional.empty();
            try (BrokerProbe.Listener listener = probe.listen(LEGACY_TOPIC)) {
                for (int attempt = 0; attempt < BROKER_ATTEMPTS && payload.isEmpty(); attempt++) {
                    session.context().waitTicks(10);
                    payload = listener.nextLive();
                }
            }
            JsonObject reading = state(session, LEGACY);
            String humidity = reading.get("humidity").getAsBigDecimal().setScale(1).toPlainString();
            String temperature = reading.get("temperature").getAsBigDecimal().setScale(1).toPlainString();
            session.check(payload.filter(humidity::equals).isPresent() && !humidity.equals(temperature),
                    "it publishes its humidity on its v1 topic (" + payload.orElse("nothing") + ", humidity " + humidity + ", temperature " + temperature + ")");
            session.check(probe.retained(LEGACY_TOPIC).isEmpty(), "it publishes without retain, as v1 did");
        }
    }

    /**
     * A humidity sensor item placed before an inventory tick converted it (by a block placer, or from a creative client's
     * set-slot and use packets in one tick) is untrusted like any placed item: its forged owner and topic do not survive.
     * The stack never enters an inventory here, so no inventory tick can convert it first. The owner is forged in the
     * block entity data, the topic paper in the container component, which replaces the data's {@code Items} on placement.
     */
    private static void placedLegacy(GameTestSession session) {
        BlockPos placed = LEGACY_ITEM_GROUND.above();
        String data = "{Humidity:61.5d,UpdateIntervalTicks:40,LastPublishTime:0L,ownerName:\"Victim\",ownerUuid:" + intArray(VICTIM) + ",privateMode:0b}";
        boolean used = session.onServer(s -> {
            ItemStack paper = new ItemStack(Items.PAPER);
            paper.set(DataComponents.CUSTOM_NAME, Component.literal(FORGED_TOPIC));
            ItemStack stack = new ItemStack(MineqttItems.HumiditySensorBlock.get());
            ItemBlockEntityData.set(stack, MineqttBlockEntityTypes.HUMIDITY_SENSOR_BLOCK.get(), parse(data));
            stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(paper)));
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(LEGACY_ITEM_GROUND).add(0, 0.5, 0), Direction.UP, LEGACY_ITEM_GROUND, false);
            return stack.useOn(new UseOnContext(s.overworld(), session.player(s), InteractionHand.MAIN_HAND, stack, hit)).consumesAction();
        });
        session.check(used && session.onServer(s -> s.overworld().getBlockState(placed).is(MineqttBlocks.TEMPERATURE_SENSOR_BLOCK.get())),
                "the placed humidity sensor item became a Climate Sensor at once");
        session.awaitServer(s -> s.overworld().getBlockEntity(placed) instanceof DeviceHost host && host.deviceLink().attached());
        session.check(Boolean.TRUE.equals(session.device(placed, link -> link.id() != null && session.playerId().equals(link.owner())
                        && session.playerName().equals(link.ownerName()) && link.legacyItems().isEmpty())),
                "the placer owns it, not the forged owner, and it has no legacy items (owner " + session.device(placed, DeviceLink::ownerName) + ")");
        RawLink raw = session.device(placed, DeviceLink::raw);
        session.check(raw != null && raw.grandfathered() == null && raw.resolved() && RawTopics.shared(FORGED_TOPIC).equals(raw.topic()),
                "its forged topic is not grandfathered but moved to the shared namespace (" + raw + ")");
    }

    /** Humidity sensor items in the player's inventory become Climate Sensors at the next inventory tick. */
    private static void items(GameTestSession session) {
        session.consoleCommand("give " + session.playerName() + " mineqtt:humidity_sensor_block[minecraft:custom_name=\"Kept\"] 2");
        session.check(session.await(() -> session.onServer(s -> {
            Inventory inventory = session.player(s).getInventory();
            return inventory.countItem(MineqttItems.HumiditySensorBlock.get()) == 0 && inventory.countItem(MineqttItems.TemperatureSensorBlock.get()) == 2;
        }), ANSWER_TICKS), "two humidity sensor items became two Climate Sensors");
        session.check(session.onServer(s -> {
            Inventory inventory = session.player(s).getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.is(MineqttItems.TemperatureSensorBlock.get())) return Component.literal("Kept").equals(stack.get(DataComponents.CUSTOM_NAME));
            }
            return false;
        }), "they keep their name");
    }

    /** A crafter, which no inventory tick reaches, turns a humidity sensor into a Climate Sensor through the 1:1 recipe. */
    private static void crafter(GameTestSession session) {
        session.consoleCommand("setblock " + CRAFTER.getX() + " " + CRAFTER.getY() + " " + CRAFTER.getZ() + " minecraft:crafter[orientation=up_north]"
                + "{Items:[{Slot:0b,id:\"mineqtt:humidity_sensor_block\",count:1}]}");
        session.consoleCommand("setblock " + (CRAFTER.getX() + 1) + " " + CRAFTER.getY() + " " + CRAFTER.getZ() + " minecraft:redstone_block");
        AABB above = new AABB(CRAFTER.above()).inflate(2);
        session.check(session.await(() -> session.onServer(s -> !s.overworld().getEntitiesOfClass(ItemEntity.class, above,
                        item -> item.getItem().is(MineqttItems.TemperatureSensorBlock.get())).isEmpty()), ANSWER_TICKS),
                "the crafter made a Climate Sensor from the humidity sensor");
    }

    private static CompoundTag parse(String snbt) {
        try {
            return TagParser.parseCompoundFully(snbt);
        } catch (CommandSyntaxException e) {
            throw new IllegalArgumentException("Not SNBT: " + snbt, e);
        }
    }

    /** A UUID as an SNBT int array, the way block entity data stores it. */
    private static String intArray(UUID id) {
        return Arrays.toString(UUIDUtil.uuidToIntArray(id)).replace("[", "[I;").replace(" ", "");
    }

    private static JsonObject state(GameTestSession session, BlockPos pos) {
        return session.onServer(s -> {
            StateWriter state = new StateWriter();
            if (s.overworld().getBlockEntity(pos) instanceof DeviceHost host) host.writeState(state);
            return state.json();
        });
    }
}

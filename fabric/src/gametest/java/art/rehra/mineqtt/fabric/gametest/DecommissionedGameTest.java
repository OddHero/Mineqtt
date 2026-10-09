package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.items.MineqttItems;
import art.rehra.mineqtt.legacy.DecommissionedBlockEntity;
import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The decommissioned Dynamic Publisher and Topic Relay (M2-21, design §9.5). A v1 Topic Relay and a v1 Dynamic
 * Publisher, set with v1's keys and papers in their slots, load as inert blocks that keep only those papers as legacy
 * items; with a broker, a message on the relay's source topic and a redstone pulse publish nothing anywhere. A
 * right-click explains in chat what replaced the block and opens no screen; the item's tooltip says it is retired. Broken
 * with a pickaxe by a survival player, the relay drops one Network Chip and its papers, never itself. A placed item whose
 * block entity data names items gets none of them, so breaking it gives the chip alone.
 */
public final class DecommissionedGameTest implements FabricClientGameTest {
    private static final int Y = GameTestSession.GROUND_Y + 1;
    private static final BlockPos STAND = new BlockPos(0, Y, 0);
    private static final BlockPos RELAY = new BlockPos(1, Y, 3);
    private static final BlockPos POWER = new BlockPos(2, Y, 3);
    private static final BlockPos DYNAMIC = new BlockPos(3, Y, 3);
    private static final BlockPos PLACED_GROUND = new BlockPos(-2, Y - 1, 3);
    private static final String SOURCE = "decom_gametest/in";
    private static final String TARGET = "decom_gametest/out/relay";
    private static final String DYNAMIC_TOPIC = "decom_gametest/out/dynamic";
    private static final String TOOLTIP = "Retired in MineQTT 2 – break to recover parts";
    private static final String RELAY_REPLACEMENT = "Route topics in Home Assistant, Node-RED or a broker bridge";
    private static final int ANSWER_TICKS = 60;
    private static final int TELEPORT_RESEND_TICKS = 25;
    // Long enough for a publish to make the round trip through the broker, and for one that should not come.
    private static final int SETTLE_TICKS = 40;
    // InventoryScreen's panel and the first hotbar slot's middle in it.
    private static final int INVENTORY_W = 176;
    private static final int INVENTORY_H = 166;
    private static final int HOTBAR_SLOT_X = 16;
    private static final int HOTBAR_SLOT_Y = 150;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "decommissioned", DecommissionedGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        // The server sends a teleport again after 20 ticks until it sees the confirmation, and with it the rotation of
        // the teleport (straight ahead), which would undo the first aim of this suite, coming only a few ticks later.
        session.context().waitTicks(TELEPORT_RESEND_TICKS);
        Optional<BrokerConfig> broker = session.broker();
        loaded(session);
        if (broker.isEmpty()) {
            session.skip("a message on the source topic and a pulse publish nothing", "no broker (" + GameTestSession.BROKER_ENV + " is not set)");
        } else {
            try (BrokerProbe probe = BrokerProbe.connect(broker.get()); BrokerProbe.Listener listener = probe.listen("decom_gametest/out/#")) {
                inert(session, probe, listener);
            }
        }
        use(session);
        tooltip(session);
        broken(session);
        placedCopy(session);
    }

    /** v1 data loads into the inert block entity, which keeps the slot papers alone. */
    private static void loaded(GameTestSession session) {
        String player = session.playerName();
        session.consoleCommand("setblock " + RELAY.getX() + " " + RELAY.getY() + " " + RELAY.getZ() + " mineqtt:topic_relay[powered=false]"
                + "{sourceTopic:\"" + SOURCE + "\",cachedMessage:\"cached\",wasPowered:0b,hasReceivedMessage:1b,lastRelayStatus:\"Received from " + SOURCE + "\","
                + "currentSourceSubscribedTopic:\"" + SOURCE + "\",currentSubscribedTopic:\"" + TARGET + "\",topic:\"" + TARGET + "\",lastTabId:\"topic_relay\","
                + "ownerName:\"" + player + "\",privateMode:0b,CustomName:\"Hall Relay\",Items:[" + paper(0, TARGET) + "," + paper(2, "stray") + "]}");
        session.consoleCommand("setblock " + DYNAMIC.getX() + " " + DYNAMIC.getY() + " " + DYNAMIC.getZ() + " mineqtt:dynamic_publisher[powered=true]"
                + "{dataType:2,wasPowered:1b,lastPublishStatus:\"Published '1' to " + DYNAMIC_TOPIC + "\",topic:\"" + DYNAMIC_TOPIC + "\","
                + "lastTabId:\"dynamic_publisher\",ownerName:\"" + player + "\",privateMode:0b,Items:[" + paper(0, DYNAMIC_TOPIC) + "]}");
        session.check(session.onServer(s -> legacyItems(s, RELAY).stream().map(item -> item.slot() + ":" + item.stack().getHoverName().getString()).toList()
                .equals(List.of("0:" + TARGET, "2:stray"))), "the relay keeps its two slot papers in their slots as legacy items");
        session.check(session.onServer(s -> legacyItems(s, DYNAMIC).size() == 1), "the Dynamic Publisher keeps its slot paper");
        // Vanilla adds the block entity's own (empty) components to every save.
        CompoundTag saved = session.onServer(s -> s.overworld().getBlockEntity(RELAY).saveWithoutMetadata(s.registryAccess()));
        session.check(saved.keySet().equals(Set.of(DeviceLink.LEGACY_ITEMS, "components")) && saved.getCompoundOrEmpty("components").isEmpty(),
                "saved, nothing but its legacy items is left of the v1 data (" + saved + ")");
        session.check(session.onServer(s -> s.overworld().getBlockState(DYNAMIC).is(MineqttBlocks.DYNAMIC_PUBLISHER_BLOCK.get())
                && s.overworld().getBlockState(DYNAMIC).getValue(BlockStateProperties.POWERED)),
                "the Dynamic Publisher keeps its saved powered state");
        session.aim(RELAY);
        session.screenshot("decommissioned_placed");
    }

    /** A message on the relay's source and a pulse next to both blocks publish nothing on any topic. */
    private static void inert(GameTestSession session, BrokerProbe probe, BrokerProbe.Listener listener) {
        probe.publish(SOURCE, "hello", false);
        List<String> received = new ArrayList<>();
        settle(session, listener, received);
        for (int pulse = 1; pulse <= 2; pulse++) {
            setPower(session, "minecraft:redstone_block");
            settle(session, listener, received);
            setPower(session, "minecraft:air");
            settle(session, listener, received);
        }
        session.check(received.isEmpty(), "neither the relay nor the Dynamic Publisher published anything (" + received + ")");
        session.check(session.onServer(s -> !s.overworld().getBlockState(RELAY).getValue(BlockStateProperties.POWERED)),
                "the relay ignores redstone: its powered state stays as saved");
    }

    /** A right-click explains the retirement and its replacement in chat, and opens no screen. */
    private static void use(GameTestSession session) {
        session.hold(Items.AIR);
        session.aim(RELAY);
        int mark = ChatLog.mark();
        session.pressUse();
        Optional<ChatLog.Line> line = session.awaitChat(mark, l -> l.text().startsWith("Topic Relay was retired in MineQTT 2"), ANSWER_TICKS);
        session.check(line.map(l -> !l.actionBar() && l.text().contains(RELAY_REPLACEMENT) && l.text().contains("Network Chip")).orElse(false),
                "a right-click tells in chat that it is retired, what replaced it and what breaking it gives (" + line + ")");
        session.check(session.onClient(mc -> mc.gui.screen() == null), "no screen opens");
        session.screenshot("decommissioned_use");
    }

    /** The item's tooltip says it is retired and what replaced it; screenshot of it in the inventory. */
    private static void tooltip(GameTestSession session) {
        session.consoleCommand("gamemode survival " + session.playerName());
        session.consoleCommand("item replace entity " + session.playerName() + " hotbar.0 with mineqtt:topic_relay");
        session.context().waitFor(mc -> mc.player.getInventory().getItem(0).is(MineqttItems.TopicRelayBlock.get()));
        List<String> lines = session.onClient(mc -> mc.player.getInventory().getItem(0)
                .getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.NORMAL).stream().map(Component::getString).toList());
        session.check(lines.contains(TOOLTIP) && lines.contains(RELAY_REPLACEMENT), "the item's tooltip says it is retired and what replaced it (" + lines + ")");

        session.context().getInput().pressKey(options -> options.keyInventory);
        session.context().waitForScreen(InventoryScreen.class);
        double[] cursor = session.onClient(mc -> {
            Window window = mc.getWindow();
            double guiX = (window.getGuiScaledWidth() - INVENTORY_W) / 2 + HOTBAR_SLOT_X;
            double guiY = (window.getGuiScaledHeight() - INVENTORY_H) / 2 + HOTBAR_SLOT_Y;
            return new double[]{guiX * window.getScreenWidth() / window.getGuiScaledWidth(), guiY * window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        session.context().runOnClient(mc -> mc.gui.toastManager().clear());
        session.context().getInput().setCursorPos(cursor[0], cursor[1]);
        session.context().waitTicks(2);
        session.context().takeScreenshot("decommissioned_tooltip");
        session.closeScreen();
    }

    /** Broken with a pickaxe in survival: one Network Chip and the two papers, not the block. */
    private static void broken(GameTestSession session) {
        session.hold(Items.STONE_PICKAXE);
        session.check(session.onServer(s -> session.player(s).gameMode.destroyBlock(RELAY)), "the player breaks the relay");
        session.check(session.await(() -> dropped(session, RELAY, MineqttItems.NetworkChip.get()) == 1 && dropped(session, RELAY, Items.PAPER) == 2, ANSWER_TICKS),
                "it drops one Network Chip and its two papers (" + dropped(session, RELAY, MineqttItems.NetworkChip.get()) + " chips, "
                        + dropped(session, RELAY, Items.PAPER) + " papers)");
        session.check(dropped(session, RELAY, MineqttItems.TopicRelayBlock.get()) == 0, "and never itself");
        session.consoleCommand("kill @e[type=item]");
    }

    /** A placed item takes none of the items its block entity data names; broken, it gives the chip alone. */
    private static void placedCopy(GameTestSession session) {
        session.consoleCommand("item replace entity " + session.playerName() + " weapon.mainhand with mineqtt:topic_relay[minecraft:block_entity_data="
                + "{id:\"mineqtt:topic_relay\",legacyItems:[{Slot:0b,id:\"minecraft:diamond\",count:5}]}]");
        session.context().waitFor(mc -> mc.player.getMainHandItem().is(MineqttItems.TopicRelayBlock.get()));
        BlockPos placed = session.placeHeld(MineqttBlocks.TOPIC_RELAY_BLOCK.get(), PLACED_GROUND);
        session.check(session.onServer(s -> legacyItems(s, placed).isEmpty()), "a placed copy keeps none of the items its data names");
        session.consoleCommand("setblock " + placed.getX() + " " + placed.getY() + " " + placed.getZ() + " minecraft:air destroy");
        session.check(session.await(() -> dropped(session, placed, MineqttItems.NetworkChip.get()) == 1, ANSWER_TICKS)
                && dropped(session, placed, Items.DIAMOND) == 0, "broken, it gives the chip and no diamond");
    }

    private static List<ItemStackWithSlot> legacyItems(MinecraftServer server, BlockPos pos) {
        BlockEntity blockEntity = server.overworld().getBlockEntity(pos);
        if (!(blockEntity instanceof DecommissionedBlockEntity retired)) throw new AssertionError("No decommissioned block entity at " + pos + ": " + blockEntity);
        return retired.legacyItems();
    }

    private static int dropped(GameTestSession session, BlockPos pos, Item item) {
        return session.onServer(s -> s.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2), entity -> entity.getItem().is(item))
                .stream().mapToInt(entity -> entity.getItem().getCount()).sum());
    }

    private static String paper(int slot, String name) {
        return "{Slot:" + slot + "b,id:\"minecraft:paper\",count:1,components:{\"minecraft:custom_name\":\"" + name + "\"}}";
    }

    private static void setPower(GameTestSession session, String block) {
        session.consoleCommand("setblock " + POWER.getX() + " " + POWER.getY() + " " + POWER.getZ() + " " + block);
    }

    // Collects what arrives live while the game ticks.
    private static void settle(GameTestSession session, BrokerProbe.Listener listener, List<String> received) {
        for (int tick = 0; tick < SETTLE_TICKS; tick += 5) {
            session.context().waitTicks(5);
            Optional<String> message;
            while ((message = listener.nextLive()).isPresent()) received.add(message.get());
        }
    }
}

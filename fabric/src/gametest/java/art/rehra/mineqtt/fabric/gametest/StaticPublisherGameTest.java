package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.blocks.RedstonePublisherBlock;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.RawLink;
import art.rehra.mineqtt.items.MineqttItems;
import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import art.rehra.mineqtt.ui.device.AdvancedTab;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The Static Publisher's conversion into a Redstone Input (M2-20, M2 plan D11). A v1 Static Publisher, saved powered,
 * with v1's keys, a paper topic, its message, its owner and an anvil name, becomes a Redstone Input on the next tick
 * that keeps its topic, owner, name and slot paper, and whose Raw Link sends the message on rising edges only and
 * explicitly not retained; its Advanced tab says so. On the broker each pulse publishes the message once, live: the
 * falling edge its first signal read finds publishes nothing, nor does any later falling edge, and nothing is retained.
 * Static Publisher items turn into Redstone Inputs in the player's inventory, keeping their name, and a crafter turns
 * one from its grid into a Redstone Input through the 1:1 recipe.
 */
public final class StaticPublisherGameTest implements FabricClientGameTest {
    private static final int Y = GameTestSession.GROUND_Y + 1;
    private static final BlockPos STAND = new BlockPos(0, Y, 0);
    private static final BlockPos LEGACY = new BlockPos(2, Y, 2);
    private static final BlockPos POWER = LEGACY.east();
    private static final BlockPos CRAFTER = new BlockPos(-2, Y, 4);
    private static final String TOPIC = "static_" + GameTestSession.BRIDGE_ID + "/doorbell";
    private static final String MESSAGE = "DING";
    private static final int ANSWER_TICKS = 60;
    // Long enough for a publish to make the round trip through the broker, and for one that should not come.
    private static final int SETTLE_TICKS = 30;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "static_publisher", StaticPublisherGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        Optional<BrokerConfig> broker = session.broker();
        if (broker.isEmpty()) {
            converted(session);
            session.skip("each pulse publishes the message once, non-retained", "no broker (" + GameTestSession.BROKER_ENV + " is not set)");
        } else {
            // A retained copy would be a regression; it must not outlive the suite either.
            session.clearAtClose(TOPIC);
            try (BrokerProbe probe = BrokerProbe.connect(broker.get()); BrokerProbe.Listener listener = probe.listen(TOPIC)) {
                converted(session);
                pulses(session, probe, listener);
            }
        }
        items(session);
        crafter(session);
    }

    /** A v1 Static Publisher saved powered becomes a Redstone Input with its link, owner, name and paper; its screen. */
    private static void converted(GameTestSession session) {
        String player = session.playerName();
        session.consoleCommand("setblock " + LEGACY.getX() + " " + LEGACY.getY() + " " + LEGACY.getZ() + " mineqtt:static_publisher[powered=true]"
                + "{staticMessage:\"" + MESSAGE + "\",wasPowered:1b,lastPublishStatus:\"Published to " + TOPIC + "\",ownerName:\"" + player + "\","
                + "privateMode:0b,topic:\"" + TOPIC + "\",lastTabId:\"static_publisher\",CustomName:\"Front Door\","
                + "Items:[{Slot:0b,id:\"minecraft:paper\",count:1,components:{\"minecraft:custom_name\":\"" + TOPIC + "\"}}]}");
        session.check(session.await(() -> session.onServer(s -> s.overworld().getBlockState(LEGACY).is(MineqttBlocks.PUBLISHER_BLOCK.get())
                && s.overworld().getBlockEntity(LEGACY) instanceof DeviceHost host && host.deviceLink().attached()), ANSWER_TICKS),
                "the Static Publisher became an attached Redstone Input");
        // Nothing powers it: the first signal read after the attach is a falling edge, which turns the block off.
        session.check(session.await(() -> !session.onServer(s -> s.overworld().getBlockState(LEGACY).getValue(RedstonePublisherBlock.POWERED)), ANSWER_TICKS),
                "its first signal read finds no signal and switches the saved powered state off");
        RawLink raw = session.device(LEGACY, DeviceLink::raw);
        session.check(raw != null && raw.topic().equals(TOPIC) && raw.grandfathered() != null && raw.active() && MESSAGE.equals(raw.on()) && raw.off() == null,
                "its v1 topic is its grandfathered Raw Link with the message as the on payload (" + raw + ")");
        session.check(raw != null && raw.edges() == RawLink.Edges.RISING && Boolean.FALSE.equals(raw.retain()),
                "the link sends rising edges only, explicitly not retained (" + raw + ")");
        session.check("Front Door".equals(session.device(LEGACY, DeviceLink::name)) && player.equals(session.device(LEGACY, DeviceLink::ownerName))
                        && Integer.valueOf(1).equals(session.device(LEGACY, link -> link.legacyItems().size())),
                "it keeps its anvil name, owner and slot paper");
        session.aim(LEGACY);
        session.screenshot("static_publisher_converted");

        session.openBlock(LEGACY);
        session.check(session.clickTab(AdvancedTab.ID), "its screen has an Advanced tab");
        Optional<DeviceView.Raw> shown = session.screenView().flatMap(DeviceView::raw);
        session.check(shown.map(link -> link.edges() == RawLink.Edges.RISING && !link.retains() && link.on().equals(Optional.of(MESSAGE))).orElse(false),
                "its Advanced tab shows the rising-edge link with the message, not retained (" + shown + ")");
        session.screenshot("static_publisher_advanced");
        session.closeScreen();
    }

    /** Two pulses publish the message twice, live; no falling edge publishes anything, and nothing is retained. */
    private static void pulses(GameTestSession session, BrokerProbe probe, BrokerProbe.Listener listener) {
        List<String> received = new ArrayList<>();
        settle(session, listener, received);
        session.check(received.isEmpty(), "the conversion and its falling edge publish nothing (" + received + ")");
        for (int pulse = 1; pulse <= 2; pulse++) {
            setPower(session, "minecraft:redstone_block");
            session.check(session.await(() -> session.onServer(s -> s.overworld().getBlockState(LEGACY).getValue(RedstonePublisherBlock.POWERED)), ANSWER_TICKS),
                    "pulse " + pulse + " powers the input");
            settle(session, listener, received);
            setPower(session, "minecraft:air");
            session.check(session.await(() -> !session.onServer(s -> s.overworld().getBlockState(LEGACY).getValue(RedstonePublisherBlock.POWERED)), ANSWER_TICKS),
                    "pulse " + pulse + " ends");
            settle(session, listener, received);
            session.check(received.equals(Collections.nCopies(pulse, MESSAGE)),
                    "after pulse " + pulse + " the message came " + pulse + " time(s), and nothing on a falling edge (" + received + ")");
        }
        session.check(probe.retained(TOPIC).isEmpty(), "nothing is retained on the topic, as v1 published it");
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

    /** Static Publisher items in the player's inventory become Redstone Inputs at the next inventory tick. */
    private static void items(GameTestSession session) {
        int inputs = session.onServer(s -> session.player(s).getInventory().countItem(MineqttItems.RedstonePublisherBlock.get()));
        session.consoleCommand("give " + session.playerName() + " mineqtt:static_publisher[minecraft:custom_name=\"Bell\"] 2");
        session.check(session.await(() -> session.onServer(s -> {
            Inventory inventory = session.player(s).getInventory();
            return inventory.countItem(MineqttItems.StaticPublisherBlock.get()) == 0 && inventory.countItem(MineqttItems.RedstonePublisherBlock.get()) == inputs + 2;
        }), ANSWER_TICKS), "two Static Publisher items became two Redstone Inputs");
        session.check(session.onServer(s -> {
            Inventory inventory = session.player(s).getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.is(MineqttItems.RedstonePublisherBlock.get()) && Component.literal("Bell").equals(stack.get(DataComponents.CUSTOM_NAME))) return true;
            }
            return false;
        }), "they keep their name");
    }

    /** A crafter, which no inventory tick reaches, turns a Static Publisher into a Redstone Input through the 1:1 recipe. */
    private static void crafter(GameTestSession session) {
        session.consoleCommand("setblock " + CRAFTER.getX() + " " + CRAFTER.getY() + " " + CRAFTER.getZ() + " minecraft:crafter[orientation=up_north]"
                + "{Items:[{Slot:0b,id:\"mineqtt:static_publisher\",count:1}]}");
        session.consoleCommand("setblock " + (CRAFTER.getX() - 1) + " " + CRAFTER.getY() + " " + CRAFTER.getZ() + " minecraft:redstone_block");
        AABB above = new AABB(CRAFTER.above()).inflate(2);
        session.check(session.await(() -> session.onServer(s -> !s.overworld().getEntitiesOfClass(ItemEntity.class, above,
                        item -> item.getItem().is(MineqttItems.RedstonePublisherBlock.get())).isEmpty()), ANSWER_TICKS),
                "the crafter made a Redstone Input from the Static Publisher");
    }
}

package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.RawLink;
import art.rehra.mineqtt.device.RawTopics;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.items.MineqttItems;
import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.runtime.TopicScheme;
import art.rehra.mineqtt.ui.CyberdeckScreen;
import art.rehra.mineqtt.ui.framework.tabs.CyberdeckExplorerTab;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * E2E checklist item 12 (design §8.4, §8.6): a non-op creative player copies another player's private lamp, whose Raw
 * Link is a grandfathered v1 topic outside the allowlist, with Ctrl + pick block and places the copy. The copy becomes a
 * new device owned by the placer, its Raw Link never reaches the other player's topic: it is resolved for the placer into
 * the shared namespace, and the placer is told (M2 plan D1). The player cannot run {@code /mineqtt}, and the Cyberdeck
 * explorer shows neither Home Assistant topics nor the other player's private topics (needs {@value GameTestSession#BROKER_ENV}).
 */
public final class PlacementSecurityGameTest implements FabricClientGameTest {
    private static final String RAW_TOPIC = "zigbee2mqtt/x/set";
    private static final UUID VICTIM = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String VICTIM_NAME = "Victim";
    private static final BlockPos STAND = new BlockPos(0, GameTestSession.GROUND_Y + 1, 0);
    private static final BlockPos SOURCE = new BlockPos(2, GameTestSession.GROUND_Y + 1, 0);
    private static final BlockPos GROUND = new BlockPos(0, GameTestSession.GROUND_Y, 2);
    private static final String DISCOVERY_PROBE = "homeassistant/light/mineqtt_" + GameTestSession.BRIDGE_ID + "/config";
    private static final String STATE_PROBE = "homeassistant/light/mineqtt_" + GameTestSession.BRIDGE_ID + "/state";
    private static final String SHARED_PROBE = "mineqtt/shared/" + GameTestSession.BRIDGE_ID + "_probe";
    private static final int CHAT_TIMEOUT_TICKS = 40;
    private static final int LISTEN_TIMEOUT_TICKS = 200;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "placement-security", PlacementSecurityGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.consoleCommand("gamemode creative " + session.playerName());
        session.teleport(STAND);
        session.check(session.onServer(s -> session.player(s).hasInfiniteMaterials()), "the player is in creative mode");

        int mark = ChatLog.mark();
        session.playerCommand("mineqtt status");
        String unknown = session.onClient(mc -> Component.translatable("command.unknown.command").getString());
        session.check(session.awaitChat(mark, line -> line.text().contains(unknown), CHAT_TIMEOUT_TICKS).isPresent(),
                "a non-op player cannot run /mineqtt (chat: " + ChatLog.since(mark) + ")");

        BlockPos placed = copyAndPlace(session);
        if (placed != null) {
            explorerScope(session, placed);
        }
    }

    /** Copies the other player's lamp with Ctrl + pick block and places it; returns where, or null when that failed. */
    private static @Nullable BlockPos copyAndPlace(GameTestSession session) {
        UUID sourceId = UUID.randomUUID();
        session.consoleCommand("setblock " + SOURCE.getX() + " " + SOURCE.getY() + " " + SOURCE.getZ()
                + " mineqtt:rgb_led_block{mineqtt:{v:2,id:" + intArray(sourceId) + ",type:\"mineqtt:smart_lamp\",owner:"
                + intArray(VICTIM) + ",ownerName:\"" + VICTIM_NAME + "\",access:\"private\",raw:{topic:\"" + RAW_TOPIC
                + "\",dir:\"in\",grandfathered:{topic:\"" + RAW_TOPIC + "\",owner:" + intArray(VICTIM) + "}},"
                + "state:{state:\"ON\",brightness:200,color:{r:255,g:0,b:0}}},Items:[{Slot:1b,id:\"minecraft:stone\",count:3}]}");
        session.awaitServer(s -> s.overworld().getBlockEntity(SOURCE) instanceof DeviceHost host && host.deviceLink().attached());

        // In creative, Ctrl + pick block copies the block entity data into the item.
        session.aim(SOURCE);
        TestInput input = session.context().getInput();
        input.holdControl();
        input.pressKey(options -> options.keyPickItem);
        input.releaseControl();
        session.context().waitFor(mc -> mc.player.getMainHandItem().is(MineqttItems.RgbLedBlock.get()));
        String copied = session.onServer(s -> {
            ItemStack stack = session.player(s).getMainHandItem();
            var data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
            return data == null ? "" : data.copyTagWithoutId().toString();
        });
        session.check(copied.contains(RAW_TOPIC) && copied.contains(VICTIM_NAME),
                "Ctrl + pick block copies the lamp with its raw topic and the other owner");

        int placedMark = ChatLog.mark();
        BlockPos placed = session.placeHeld(MineqttBlocks.RGB_LED_BLOCK.get(), GROUND);
        Placed copy = session.device(placed, link -> new Placed(link.id(), link.owner(), link.ownerName(), link.raw()));
        if (copy == null) {
            session.check(false, "the copied lamp is a device");
            return null;
        }
        session.check(copy.id() != null && !copy.id().equals(sourceId), "the copy is a new device (" + copy.id() + ")");
        session.check(session.playerId().equals(copy.owner()) && session.playerName().equals(copy.ownerName()),
                "the placer owns the copy (owner " + copy.ownerName() + ")");
        // M2-12: the source's old slots held stone, which its copied data carries; none of it is the copy's.
        session.check(Boolean.TRUE.equals(session.device(SOURCE, link -> link.legacyItems().size() == 1))
                        && Boolean.TRUE.equals(session.device(placed, link -> link.legacyItems().isEmpty())),
                "the source keeps its legacy stone, the copy gets none it could eject");
        // M2 plan D14: a placed copy keeps its colour and brightness, and is off.
        JsonObject light = session.onServer(s -> {
            StateWriter state = new StateWriter();
            ((DeviceHost) s.overworld().getBlockEntity(placed)).writeState(state);
            return state.json();
        });
        session.check(light.get("state").getAsString().equals("OFF") && light.get("brightness").getAsInt() == 200
                && light.getAsJsonObject("color").get("r").getAsInt() == 255, "the copy is off in the source's colour and brightness (" + light + ")");
        // The placer may not use the other player's topic, so the copy's M1 link is resolved for them like a new one.
        RawLink raw = copy.raw();
        String shared = RawTopics.shared(RAW_TOPIC);
        session.check(raw != null && shared.equals(raw.topic()) && raw.active() && raw.grandfathered() == null && raw.resolved(),
                "the copy's Raw Link moved to the shared namespace, not grandfathered (" + raw + ")");
        session.check(session.awaitChat(placedMark, line -> !line.actionBar() && line.text().contains(RAW_TOPIC) && line.text().contains(shared),
                CHAT_TIMEOUT_TICKS).isPresent(), "the placer is told where the copy's Raw Link moved (chat: " + ChatLog.since(placedMark) + ")");
        session.check(session.awaitChat(placedMark, line -> line.actionBar() && line.text().contains(shared), CHAT_TIMEOUT_TICKS).isPresent(),
                "the owner's action bar shows the move too (messages: " + ChatLog.since(placedMark) + ")");

        session.openBlock(placed);
        String status = session.screenStatus();
        session.check(status != null && status.contains("Raw " + shared) && !status.contains("grandfathered"),
                "the copy's screen shows its shared Raw Link (" + status + ")");
        session.screenshot("placement_copied_lamp");
        session.closeScreen();
        return placed;
    }

    /** The explorer of a non-op player sees its own and shared topics, never Home Assistant's or another player's private ones. */
    private static void explorerScope(GameTestSession session, BlockPos placed) {
        Optional<BrokerConfig> broker = session.broker();
        if (broker.isEmpty()) {
            session.skip("Cyberdeck explorer scope", GameTestSession.BROKER_ENV + " is not set");
            return;
        }
        TopicScheme topics = session.onRuntime(MineqttRuntime::topics);
        String victimDevice = topics.device(session.device(SOURCE, DeviceLink::slug));
        String ownDevice = topics.device(session.device(placed, DeviceLink::slug));
        String victimPrivate = topics.root() + "/raw/players/" + VICTIM + "/probe";

        try (BrokerProbe probe = BrokerProbe.connect(broker.get())) {
            try {
                // Retained before listening, so the explorer receives it on subscribe; the rest arrives live.
                probe.publish(DISCOVERY_PROBE, "{\"name\":\"MineQTT game test probe\"}", true);
                session.hold(MineqttItems.Cyberdeck.get());
                session.useHeldItem();
                session.check(session.clickTab(CyberdeckExplorerTab.ID), "the Cyberdeck opens its explorer tab");
                session.clickButton("Listen: OFF");
                session.context().waitTicks(40);
                probe.publish(STATE_PROBE, "ON", false);
                probe.publish(victimPrivate, "victim-private", false);
                probe.publish(victimDevice + "/set", "{\"state\":\"ON\",\"brightness\":17}", false);
                probe.publish(ownDevice + "/set", "{\"state\":\"ON\",\"brightness\":33}", false);
                probe.publish(SHARED_PROBE, "visible-control", false);
                // The shared probe is published last; once it arrives, the earlier probes had their chance.
                if (session.awaitClient(mc -> CyberdeckScreen.discoveredTopics().containsKey(SHARED_PROBE), LISTEN_TIMEOUT_TICKS)) {
                    session.context().waitTicks(20);
                }
            } finally {
                probe.publish(DISCOVERY_PROBE, "", true);
            }
        }
        Map<String, String> seen = session.onClient(mc -> new TreeMap<>(CyberdeckScreen.discoveredTopics()));
        session.info("the explorer saw " + seen);
        session.screenshot("placement_explorer");
        session.check(seen.containsKey(SHARED_PROBE), "control: the shared raw topic reaches the explorer");
        session.check(seen.containsKey(ownDevice), "control: the player's own device state reaches the explorer");
        session.check(seen.keySet().stream().noneMatch(topic -> topic.startsWith("homeassistant/")),
                "no homeassistant/# message reaches the explorer, retained or live");
        session.check(seen.keySet().stream().noneMatch(topic -> topic.equals(victimDevice) || topic.startsWith(victimDevice + "/")),
                "the other player's private device topics stay hidden");
        session.check(!seen.containsKey(victimPrivate), "the other player's private raw topic stays hidden");

        session.clickButton("Listen: ON");
        session.closeScreen();
    }

    private record Placed(UUID id, UUID owner, String ownerName, RawLink raw) {
    }

    /** A UUID as an SNBT int array, the way block entity data stores it. */
    private static String intArray(UUID id) {
        return Arrays.toString(UUIDUtil.uuidToIntArray(id)).replace("[", "[I;").replace(" ", "");
    }
}

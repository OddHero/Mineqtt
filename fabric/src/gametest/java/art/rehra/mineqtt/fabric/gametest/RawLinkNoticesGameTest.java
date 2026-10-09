package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.blocks.RedstoneEmitterBlock;
import art.rehra.mineqtt.blocks.entities.DeviceBlockEntity;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.config.StaticDefaults;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.RawLink;
import art.rehra.mineqtt.device.RawTopics;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.ui.device.AdvancedTab;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Raw Link refusals and remaps reach the player (M2 plan D1, F2). The non-op player places a Redstone Input and, on its
 * Advanced tab, gives it stone's channel: they are told in chat that the item resolves to the shared namespace, and
 * the open screen's status line follows. A config reload that drops {@code mineqtt/shared/#} from the allowlist refuses
 * the link at once: a chat line to the player who just edited it, the owner's action bar, and the refusal on the open
 * screen. Restoring the allowlist opens the link again, still without a restart. A Redstone Output beside it shows that
 * the reload closes and reopens a Raw Link input's subscription, that only an edit (not opening the screen) makes the
 * player the editor, and that a reload leaving the ACL as it was tells nothing again. The output has no container, so
 * Fabric's item storage, sided or not, finds nothing on it (M2-12).
 */
public final class RawLinkNoticesGameTest implements FabricClientGameTest {
    private static final String LITERAL = "minecraft/stone";
    private static final BlockPos STAND = new BlockPos(0, GameTestSession.GROUND_Y + 1, 0);
    private static final BlockPos GROUND = new BlockPos(0, GameTestSession.GROUND_Y, 2);
    private static final String OUTPUT_LITERAL = "minecraft/dirt";
    private static final BlockPos OUTPUT_GROUND = new BlockPos(-3, GameTestSession.GROUND_Y, 2);
    private static final int NOTICE_TIMEOUT_TICKS = 40;
    // Ample for a loopback message or a notice that should not come.
    private static final int DELIVERY_TICKS = 20;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "raw-link-notices", RawLinkNoticesGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        BlockPos output = placeOutput(session);
        String outputTopic = RawTopics.shared(OUTPUT_LITERAL);

        BlockPos input = session.place(MineqttBlocks.PUBLISHER_BLOCK.get(), GROUND);
        session.awaitServer(s -> s.overworld().getBlockEntity(input) instanceof DeviceHost host && host.deviceLink().attached());
        session.openBlock(input);
        // A device's screen opens on its General tab (M2-8); its Raw Link is edited on the Advanced tab (M2-10, M2-12).
        session.check(session.clickTab(AdvancedTab.ID), "the input's Advanced tab opens");
        String shared = RawTopics.shared(LITERAL);
        // The input publishes its level retained there (M2 plan D2).
        session.clearAtClose(shared);

        int editMark = ChatLog.mark();
        setChannel(session, LITERAL);
        session.awaitServer(s -> raw(s.overworld().getBlockEntity(input)) != null);
        RawLink placed = session.device(input, link -> link.raw());
        session.check(placed != null && shared.equals(placed.topic()) && placed.active() && LITERAL.equals(placed.channel()),
                "stone's channel resolves to the shared namespace (" + placed + ")");
        session.check(session.awaitChat(editMark, line -> !line.actionBar() && line.text().contains(LITERAL) && line.text().contains(shared),
                NOTICE_TIMEOUT_TICKS).isPresent(), "the editor is told where the item topic went (chat: " + ChatLog.since(editMark) + ")");
        session.check(session.awaitClient(mc -> statusShows(mc, "Raw " + shared), NOTICE_TIMEOUT_TICKS),
                "the open screen's status line follows the edit (" + session.screenStatus() + ")");
        session.screenshot("raw_link_shared");

        int refusedMark = ChatLog.mark();
        reloadRawAllow(session, List.of(StaticDefaults.BASE_PLACEHOLDER + "/" + StaticDefaults.BRIDGE_ID_PLACEHOLDER + "/raw/#"));
        session.check(session.awaitChat(refusedMark, line -> !line.actionBar() && line.text().contains("refused") && line.text().contains(LITERAL),
                NOTICE_TIMEOUT_TICKS).isPresent(), "the editor is told in chat that the reload refuses the link (chat: " + ChatLog.since(refusedMark) + ")");
        session.check(session.awaitChat(refusedMark, line -> line.actionBar() && line.text().contains("refused"), NOTICE_TIMEOUT_TICKS).isPresent(),
                "the owner's action bar shows the refusal (messages: " + ChatLog.since(refusedMark) + ")");
        session.check(session.awaitClient(mc -> statusShows(mc, "refused: ") && menu(mc).rawProblem() != null, NOTICE_TIMEOUT_TICKS),
                "the open screen shows the refusal without reopening (" + session.screenStatus() + " / " + session.screenRawProblem() + ")");
        RawLink refused = session.device(input, link -> link.raw());
        session.check(refused != null && !refused.active(), "the reload disabled the link (" + refused + ")");
        session.check(refusedChatLines(refusedMark, LITERAL) == 1, "the refusal is told once (chat: " + ChatLog.since(refusedMark) + ")");
        session.check(refusedChatLines(refusedMark, OUTPUT_LITERAL) == 1,
                "the output's refusal is told once too (chat: " + ChatLog.since(refusedMark) + ")");
        publish(session, outputTopic, "true");
        session.context().waitTicks(DELIVERY_TICKS);
        session.check(power(session, output) == 0, "the refused output no longer follows its topic");
        session.screenshot("raw_link_refused");

        // The same ACL saved again (another key changed) judges nothing again, so nobody is told twice.
        int unchangedMark = ChatLog.mark();
        reloadRawAllow(session, List.of(StaticDefaults.BASE_PLACEHOLDER + "/" + StaticDefaults.BRIDGE_ID_PLACEHOLDER + "/raw/#"));
        session.context().waitTicks(DELIVERY_TICKS);
        session.check(ChatLog.since(unchangedMark).stream().noneMatch(line -> line.text().contains("refused")),
                "a reload that leaves the ACL as it was tells nothing again (messages: " + ChatLog.since(unchangedMark) + ")");
        // A changed ACL judges both links again; their refusals stay as they were, so they are not told twice.
        int sameReasonMark = ChatLog.mark();
        reloadRawAllow(session, List.of(StaticDefaults.BASE_PLACEHOLDER + "/" + StaticDefaults.BRIDGE_ID_PLACEHOLDER + "/raw/#", "home/#"));
        session.context().waitTicks(DELIVERY_TICKS);
        session.check(refusedChatLines(sameReasonMark, LITERAL) == 0 && refusedChatLines(sameReasonMark, OUTPUT_LITERAL) == 0,
                "a reload that refuses the links for the same reason tells nothing again (messages: " + ChatLog.since(sameReasonMark) + ")");

        reloadRawAllow(session, StaticDefaults.DEFAULT_ACL_RAW_ALLOW);
        session.check(session.awaitClient(mc -> statusShows(mc, "Raw " + shared) && menu(mc).rawProblem() == null, NOTICE_TIMEOUT_TICKS),
                "restoring the allowlist opens the link again on the open screen (" + session.screenStatus() + ")");
        RawLink reopened = session.device(input, link -> link.raw());
        session.check(reopened != null && reopened.active() && shared.equals(reopened.topic()), "the link works again (" + reopened + ")");
        publish(session, outputTopic, "true");
        session.check(session.awaitClient(mc -> mc.level.getBlockState(output).getValue(RedstoneEmitterBlock.POWER) > 0, NOTICE_TIMEOUT_TICKS),
                "restoring the allowlist subscribes the output again: a message on its topic powers it");
        session.closeScreen();
    }

    /**
     * Places a Redstone Output (a Raw Link input) and links it to dirt's channel on its Advanced tab; opening the screen
     * alone does not make the player its editor, the edit does. Away from the Redstone Input, so neither powers the other.
     */
    private static BlockPos placeOutput(GameTestSession session) {
        BlockPos output = session.place(MineqttBlocks.REDSTONE_EMITTER_BLOCK.get(), OUTPUT_GROUND);
        session.awaitServer(s -> s.overworld().getBlockEntity(output) instanceof DeviceHost host && host.deviceLink().attached());
        session.openBlock(output);
        session.check(session.clickTab(AdvancedTab.ID), "the output's Advanced tab opens");
        session.check(editor(session, output) == null, "opening the screen does not make the player the editor");
        setChannel(session, OUTPUT_LITERAL);
        session.awaitServer(s -> raw(s.overworld().getBlockEntity(output)) != null);
        RawLink linked = session.device(output, link -> link.raw());
        session.check(linked != null && linked.active() && RawTopics.shared(OUTPUT_LITERAL).equals(linked.topic()),
                "dirt's channel resolves to the shared namespace (" + linked + ")");
        session.check(session.playerId().equals(editor(session, output)), "the edit that linked it makes the player the editor");
        noItemStorage(session, output);
        publish(session, RawTopics.shared(OUTPUT_LITERAL), "true");
        session.check(session.awaitClient(mc -> mc.level.getBlockState(output).getValue(RedstoneEmitterBlock.POWER) > 0, NOTICE_TIMEOUT_TICKS),
                "a message on the output's topic powers it");
        publish(session, RawTopics.shared(OUTPUT_LITERAL), "false");
        session.check(session.awaitClient(mc -> mc.level.getBlockState(output).getValue(RedstoneEmitterBlock.POWER) == 0, NOTICE_TIMEOUT_TICKS),
                "and turns it off again");
        session.closeScreen();
        return output;
    }

    /** Types a channel into the open Advanced tab's Raw Link field and sends it with Enter, as a player does. */
    private static void setChannel(GameTestSession session, String channel) {
        session.typeInto(widget -> widget instanceof EditBox && widget.getMessage().getString().equals("Raw Link"), "the Raw Link field", channel);
        session.context().getInput().pressKey(InputConstants.KEY_RETURN);
    }

    /**
     * The output has no container since M2-12, so Fabric's item storage finds none on it, queried without a side or from
     * two sides: hoppers and pipes have nothing to take.
     */
    private static void noItemStorage(GameTestSession session, BlockPos pos) {
        for (Direction side : new Direction[]{null, Direction.UP, Direction.NORTH}) {
            boolean none = session.onServer(s -> ItemStorage.SIDED.find(s.overworld(), pos, side) == null);
            session.check(none, "Fabric's item storage from side " + side + " finds none on the output");
        }
        session.check(session.onServer(s -> !(s.overworld().getBlockEntity(pos) instanceof Container)), "the output's block entity is no container");
    }

    /** Publishes as another client would: a foreign origin reaches the subscriptions of this server through the loopback. */
    private static void publish(GameTestSession session, String topic, String payload) {
        session.onRuntime(runtime -> {
            runtime.mqtt().publish(Outbound.raw(topic, payload.getBytes(StandardCharsets.UTF_8), 0, UUID.randomUUID()));
            return null;
        });
    }

    private static int power(GameTestSession session, BlockPos pos) {
        return session.onServer(s -> s.overworld().getBlockState(pos).getValue(RedstoneEmitterBlock.POWER));
    }

    private static @Nullable UUID editor(GameTestSession session, BlockPos pos) {
        return session.onServer(s -> s.overworld().getBlockEntity(pos) instanceof DeviceBlockEntity device ? device.recentEditor() : null);
    }

    private static long refusedChatLines(int mark, String channel) {
        return ChatLog.since(mark).stream().filter(line -> !line.actionBar() && line.text().contains("refused") && line.text().contains(channel)).count();
    }

    /** Sets {@code acl.rawAllow} and reloads the config on the server thread, as a config screen or file reload does. */
    private static void reloadRawAllow(GameTestSession session, List<String> rawAllow) {
        session.onServer(s -> {
            MineQTTConfig.aclRawAllow = rawAllow;
            MineqttRuntime.configChanged();
            return null;
        });
    }

    // On the client thread: whether the open screen's status line contains the text.
    private static boolean statusShows(Minecraft mc, String text) {
        TabbedMqttMenu menu = menu(mc);
        return menu != null && menu.deviceStatus() != null && menu.deviceStatus().contains(text);
    }

    private static @Nullable TabbedMqttMenu menu(Minecraft mc) {
        return mc.gui.screen() instanceof TabbedMqttScreen screen ? screen.getMenu() : null;
    }

    private static @Nullable RawLink raw(@Nullable BlockEntity blockEntity) {
        return blockEntity instanceof DeviceHost host ? host.deviceLink().raw() : null;
    }
}

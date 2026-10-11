package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.blocks.RedstoneEmitterBlock;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.RawLink;
import art.rehra.mineqtt.device.RawTopics;
import art.rehra.mineqtt.device.types.RedstoneOutput;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceActionPayload;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceConfigPayload;
import art.rehra.mineqtt.security.AccessMode;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The device config protocol from a real client (M2-6): the non-op player places a Redstone Output, opens its screen and
 * sends the payloads the generated screens will send. The view comes with the screen and says what the player may
 * change; a setting, a name, an access mode, a Raw Link channel and an allowlisted typed topic apply, and every open
 * screen gets the new view; a value out of its bounds, exposing without the right, a typed topic outside the allowlist
 * and an undeclared action are refused with a chat line and change nothing; a declared action switches the output.
 * Without the screen open, the guard drops the payloads silently. On a device of another owner the player configures but
 * may not change the access mode.
 */
public final class DeviceConfigGameTest implements FabricClientGameTest {
    private static final BlockPos STAND = new BlockPos(0, GameTestSession.GROUND_Y + 1, 0);
    private static final BlockPos GROUND = new BlockPos(2, GameTestSession.GROUND_Y, 0);
    private static final BlockPos OTHERS = new BlockPos(-2, GameTestSession.GROUND_Y + 1, 0);
    private static final UUID OTHER_OWNER = UUID.fromString("5c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f");
    private static final String CHANNEL = "device_config_" + GameTestSession.BRIDGE_ID;
    private static final int ANSWER_TICKS = 40;
    // Ample for a dropped payload to have done something if it had not been dropped.
    private static final int DROP_TICKS = 20;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "device-config", DeviceConfigGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        BlockPos output = session.place(MineqttBlocks.REDSTONE_EMITTER_BLOCK.get(), GROUND);
        session.awaitServer(s -> s.overworld().getBlockEntity(output) instanceof DeviceHost host && host.deviceLink().attached());
        session.openBlock(output);

        // The view comes with the screen's open data (M2-7), built for this player: the owner, who may not expose.
        Optional<DeviceView> opened = session.screenView();
        session.check(opened.isPresent(), "the device view comes with the opened screen");
        DeviceView view = opened.orElseThrow();
        session.check(view.type().equals(RedstoneOutput.TYPE.id()) && view.grants().equals(new DeviceView.Grants(true, true, false, true)),
                "the view is the output's, and the owner may use, configure and set access but not expose (" + view.type() + ", " + view.grants() + ")");
        session.check(view.status().equals(session.screenStatus()), "the view carries the screen's status line");
        session.screenshot("device_config_view");

        // A setting of the type reaches the device as its command; the screen gets the new view.
        session.send(new DeviceConfigPayload(output, "mode", StringTag.valueOf("switch")));
        session.check(awaitView(session, v -> "switch".equals(v.stateJson().get("mode").getAsString())), "mode switch is applied and the view shows it");
        session.check("switch".equals(session.onRuntime(runtime -> record(runtime.registry().records(), output).lastState().get("mode").getAsString())),
                "the device publishes mode switch");

        refused(session, new DeviceConfigPayload(output, "power_on_behaviour", StringTag.valueOf("sometimes")), "an unknown power-on behaviour");
        refused(session, new DeviceConfigPayload(output, "expose", StringTag.valueOf("on")), "exposing without mineqtt:ha.expose");
        session.check(session.device(output, link -> link.expose()) == null, "the output's exposure is unchanged");

        // The name and the access mode, the owner's to set.
        session.send(new DeviceConfigPayload(output, "name", StringTag.valueOf("Garage Door")));
        session.check(awaitView(session, v -> v.name().equals("Garage Door") && v.slug().equals("garage_door")), "the output is renamed and the view shows its slug");
        session.send(new DeviceConfigPayload(output, "access", StringTag.valueOf("private")));
        session.check(awaitView(session, v -> v.access() == AccessMode.PRIVATE), "the owner makes the output private");

        // The Raw Link: a channel resolves into the shared namespace; a typed topic is used as typed when the ACL allows it.
        session.send(new DeviceConfigPayload(output, "raw.channel", StringTag.valueOf(CHANNEL)));
        session.check(awaitView(session, v -> v.raw().flatMap(DeviceView.Raw::channel).filter(CHANNEL::equals).isPresent()), "the channel is set");
        RawLink link = session.device(output, deviceLink -> deviceLink.raw());
        session.check(link != null && link.topic().equals(RawTopics.shared(CHANNEL)) && link.active(), "the channel resolves to its shared form (" + link + ")");
        refused(session, new DeviceConfigPayload(output, "raw.topic", StringTag.valueOf("zigbee2mqtt/gametest/set")), "a typed topic outside the allowlist");
        session.check(RawTopics.shared(CHANNEL).equals(session.device(output, deviceLink -> deviceLink.raw().topic())), "the refused topic leaves the link as it was");
        String typed = RawTopics.shared("device_config_typed_" + GameTestSession.BRIDGE_ID);
        session.send(new DeviceConfigPayload(output, "raw.topic", StringTag.valueOf(typed)));
        session.check(awaitView(session, v -> v.raw().filter(raw -> raw.topic().equals(typed) && raw.channel().isEmpty()).isPresent()),
                "an allowlisted typed topic is used as typed, without a channel");
        refused(session, new DeviceConfigPayload(output, "raw.retain", ByteTag.valueOf(true)), "retain on a Raw Link input");

        // A declared action switches the output; an undeclared one is refused.
        session.send(new DeviceActionPayload(output, "state", "ON"));
        session.check(awaitPower(session, output, 15), "the state action switches the output on");
        refused(session, new DeviceActionPayload(output, "pulse", ""), "an action the output does not declare");
        session.send(new DeviceActionPayload(output, "state", "TOGGLE"));
        session.check(awaitPower(session, output, 0), "TOGGLE switches it off again");
        session.closeScreen();

        // Without its screen open, a payload is dropped by the guard: nothing changes and nobody is told.
        int mark = ChatLog.mark();
        session.send(new DeviceConfigPayload(output, "mode", StringTag.valueOf("level")));
        session.send(new DeviceActionPayload(output, "state", "ON"));
        session.context().waitTicks(DROP_TICKS);
        session.check(session.onServer(s -> s.overworld().getBlockState(output).getValue(RedstoneEmitterBlock.POWER) == 0)
                        && "switch".equals(session.onRuntime(runtime -> record(runtime.registry().records(), output).lastState().get("mode").getAsString())),
                "payloads without the screen open change nothing");
        session.check(ChatLog.since(mark).isEmpty(), "and tell nothing (" + ChatLog.since(mark) + ")");

        // A device of another owner with claim access: without a claim mod the player configures it, but its access mode
        // is the owner's.
        session.consoleCommand("setblock " + OTHERS.getX() + " " + OTHERS.getY() + " " + OTHERS.getZ() + " mineqtt:rgb_led_block{mineqtt:{v:2,id:"
                + intArray(UUID.randomUUID()) + ",type:\"mineqtt:smart_lamp\",owner:" + intArray(OTHER_OWNER) + ",ownerName:\"Someone\",access:\"claim\"}}");
        session.awaitServer(s -> s.overworld().getBlockEntity(OTHERS) instanceof DeviceHost host && host.deviceLink().attached());
        session.openBlock(OTHERS);
        session.check(awaitView(session, v -> v.grants().equals(new DeviceView.Grants(true, true, false, false))),
                "another owner's claim lamp may be used and configured, its access not changed");
        refused(session, new DeviceConfigPayload(OTHERS, "access", StringTag.valueOf("public")), "an access change by someone who is not the owner");
        session.check(session.device(OTHERS, deviceLink -> deviceLink.access()) == AccessMode.CLAIM, "the lamp stays claim");
        session.send(new DeviceActionPayload(OTHERS, "state", "ON"));
        session.check(awaitView(session, v -> "ON".equals(v.stateJson().get("state").getAsString())), "the player switches the other owner's lamp on");
        session.closeScreen();
    }

    // Sends a payload that must be refused: the player is told in chat, and the view does not change.
    private static void refused(GameTestSession session, CustomPacketPayload payload, String what) {
        int mark = ChatLog.mark();
        session.send(payload);
        Optional<ChatLog.Line> line = session.awaitChat(mark, chat -> !chat.actionBar() && chat.text().startsWith("Not changed: "), ANSWER_TICKS);
        session.check(line.isPresent(), what + " is refused with a reason (" + line.map(ChatLog.Line::text).orElse("no chat line") + ")");
    }

    private static boolean awaitView(GameTestSession session, Predicate<DeviceView> matches) {
        return session.awaitClient(mc -> {
            DeviceView view = view(mc);
            return view != null && matches.test(view);
        }, ANSWER_TICKS);
    }

    // The open screen's view, read on the client thread.
    private static @Nullable DeviceView view(Minecraft mc) {
        return mc.gui.screen() instanceof TabbedMqttScreen screen ? screen.getMenu().deviceView().orElse(null) : null;
    }

    // Waits until the client sees the output emit that power.
    private static boolean awaitPower(GameTestSession session, BlockPos output, int power) {
        return session.awaitClient(mc -> mc.level.getBlockState(output).getValue(RedstoneEmitterBlock.POWER) == power, ANSWER_TICKS);
    }

    private static DeviceRecord record(Iterable<DeviceRecord> records, BlockPos pos) {
        for (DeviceRecord record : records) {
            if (record.pos().pos().equals(pos)) return record;
        }
        throw new AssertionError("No device at " + pos);
    }

    /** A UUID as an SNBT int array, the way block entity data stores it. */
    private static String intArray(UUID id) {
        return Arrays.toString(UUIDUtil.uuidToIntArray(id)).replace("[", "[I;").replace(" ", "");
    }
}

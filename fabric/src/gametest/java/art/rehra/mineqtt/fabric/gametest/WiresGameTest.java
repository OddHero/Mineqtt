package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.blocks.RedstoneEmitterBlock;
import art.rehra.mineqtt.blocks.RgbLedBlock;
import art.rehra.mineqtt.blocks.entities.BlockEmitterBlockEntity;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.CommandSource;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.flow.core.Mapping;
import art.rehra.mineqtt.flow.core.MessageBus;
import art.rehra.mineqtt.flow.core.Root;
import art.rehra.mineqtt.flow.core.Wire;
import art.rehra.mineqtt.legacy.LegacySubscriptionFacade;
import art.rehra.mineqtt.mqtt.core.OfflinePolicy;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LeverBlock;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Local bindings (M2-15): the console wires a Redstone Input to a Redstone Output and to a Smart Lamp with
 * {@code /mineqtt wires add}, and the non-op player flips a lever on the input by hand. Both follow at once, through the
 * server's message bus and not the broker, and flipping the lever back turns them off. An incompatible pair and a
 * self-wire are refused. Two outputs wired into each other, one way inverted, make a loop without a delay: the inverted
 * wire tells its target the opposite of its source's off as it is made (M2-16), which starts the loop, and the bus cuts
 * it after 16 wires. The roots the runtime opens (M3 plan D10) are read off the bus's counters: the lever and the
 * console are the world's, a Home Assistant command on a chain of outputs is HA through every hop, and a broker message
 * that a v1 Block Emitter turns into a redstone block powers the input as RAW, and a Home Assistant command queued for an
 * output in an unloaded chunk lands as STARTUP when the chunk loads again (M3-9). Breaking the input takes its wires along.
 */
public final class WiresGameTest implements FabricClientGameTest {
    private static final int Y = GameTestSession.GROUND_Y + 1;
    private static final BlockPos STAND = new BlockPos(0, Y, 0);
    private static final BlockPos INPUT = new BlockPos(3, Y, 0);
    private static final BlockPos LEVER = INPUT.above();
    private static final BlockPos OUTPUT = new BlockPos(3, Y, 3);
    private static final BlockPos LAMP = new BlockPos(3, Y, -3);
    private static final BlockPos LOOP_A = new BlockPos(-3, Y, 3);
    private static final BlockPos LOOP_B = new BlockPos(-3, Y, 5);
    private static final BlockPos CHAIN_FIRST = new BlockPos(-3, Y, -3);
    private static final BlockPos CHAIN_MIDDLE = new BlockPos(-3, Y, -5);
    private static final BlockPos CHAIN_LAST = new BlockPos(-3, Y, -7);
    // Far beyond any view distance, so its chunk unloads without its forceload ticket.
    private static final BlockPos FAR_OUTPUT = new BlockPos(4096, Y, 4096);
    private static final BlockPos NEAR_OUTPUT = new BlockPos(-3, Y, 9);
    private static final int ANSWER_TICKS = 40;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "wires", WiresGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        setblock(session, INPUT, "mineqtt:redstone_publisher_block");
        setblock(session, LEVER, "minecraft:lever[face=floor,facing=north]");
        setblock(session, OUTPUT, "mineqtt:redstone_emitter_block");
        setblock(session, LAMP, "mineqtt:rgb_led_block");
        for (BlockPos pos : List.of(INPUT, OUTPUT, LAMP)) {
            session.awaitServer(s -> s.overworld().getBlockEntity(pos) instanceof DeviceHost host && host.deviceLink().attached());
        }
        String input = slug(session, INPUT);
        String output = slug(session, OUTPUT);
        String lamp = slug(session, LAMP);

        // Without a mapping, the default: powered follows into a switch state.
        session.consoleCommand("mineqtt wires add " + input + ".powered " + output + ".state");
        session.consoleCommand("mineqtt wires add " + input + ".powered " + lamp + ".state");
        List<Wire> wires = session.onRuntime(runtime -> List.copyOf(runtime.registry().wires()));
        session.check(wires.size() == 2 && wires.stream().allMatch(wire -> wire.mapping() == Mapping.FOLLOW && wire.enabled()),
                "the console wires the input to the output and the lamp, following (" + wires.size() + " wires)");
        session.consoleCommand("mineqtt wires add " + input + ".pulse " + output + ".power");
        session.consoleCommand("mineqtt wires add " + input + ".powered " + input + ".powered");
        session.check(session.onRuntime(runtime -> runtime.registry().wires().size()) == 2, "an incompatible pair and a self-wire are refused");

        // The player flips the lever by hand: the input's change reaches both targets in the same tick's drain.
        session.aim(LEVER);
        session.pressUse();
        session.check(session.await(() -> leverOn(session) && power(session, OUTPUT) == 15 && lampLevel(session) > 0, ANSWER_TICKS),
                "the lever powers the input, and the output and the lamp switch on (output " + power(session, OUTPUT) + ", lamp " + lampLevel(session) + ")");
        session.screenshot("wires_lever_on");
        session.pressUse();
        session.check(session.await(() -> !leverOn(session) && power(session, OUTPUT) == 0 && lampLevel(session) == 0, ANSWER_TICKS),
                "flipping it back switches both off");
        DeviceRuntime.FlowCounters flows = counters(session);
        session.check(flows.delivered() == 4 && flows.dropped().isEmpty(), "four deliveries, nothing dropped (" + flows.summary() + ")");

        // A loop without a delay: A's state into B, B's inverted back into A, so every delivery changes its target. The bus
        // cuts it after 16 wires.
        setblock(session, LOOP_A, "mineqtt:redstone_emitter_block");
        setblock(session, LOOP_B, "mineqtt:redstone_emitter_block");
        for (BlockPos pos : List.of(LOOP_A, LOOP_B)) {
            session.awaitServer(s -> s.overworld().getBlockEntity(pos) instanceof DeviceHost host && host.deviceLink().attached());
        }
        String loopA = slug(session, LOOP_A);
        String loopB = slug(session, LOOP_B);
        session.consoleCommand("mineqtt wires add " + loopA + ".state " + loopB + ".state");
        // Made, the inverted wire switches A on, as B is off: the loop starts without a command.
        session.consoleCommand("mineqtt wires add " + loopB + ".state " + loopA + ".state invert");
        session.check(session.await(() -> counters(session).dropped().getOrDefault(MessageBus.Drop.HOPS, 0L) == 1, ANSWER_TICKS),
                "the loop is cut once (" + counters(session).summary() + ")");
        long delivered = counters(session).delivered();
        session.check(delivered == 4 + MessageBus.HOP_TTL, "after 16 deliveries (" + (delivered - 4) + ")");
        session.context().waitTicks(20);
        session.check(counters(session).delivered() == delivered, "and stays cut: nothing more is delivered");
        flows = counters(session);
        session.check(flows.emitted(Root.WORLD) == flows.emitted() && flows.emitted() > 0,
                "a hand on a lever and the console's wires are the world's (M3 plan D10): " + flows.summary());

        haRoot(session);
        legacyRoot(session, input);
        attachRoot(session);

        // Breaking the input takes its wires along; the loop's stay.
        setblock(session, INPUT, "minecraft:air");
        session.check(session.await(() -> session.onRuntime(runtime -> runtime.registry().wires().size()) == 2, ANSWER_TICKS),
                "breaking the input removes its two wires");
    }

    /**
     * A Home Assistant {@code /set} command through {@link DeviceRuntime#command}, the runtime's real command extent, on
     * the first of three outputs wired in a chain: both of its messages are HA, also the one the middle output emits while
     * the first is delivered to it (M3 plan D10). None becomes the world's.
     */
    private static void haRoot(GameTestSession session) {
        for (BlockPos pos : List.of(CHAIN_FIRST, CHAIN_MIDDLE, CHAIN_LAST)) {
            setblock(session, pos, "mineqtt:redstone_emitter_block");
            session.awaitServer(s -> s.overworld().getBlockEntity(pos) instanceof DeviceHost host && host.deviceLink().attached());
        }
        String first = slug(session, CHAIN_FIRST);
        session.consoleCommand("mineqtt wires add " + first + ".state " + slug(session, CHAIN_MIDDLE) + ".state");
        session.consoleCommand("mineqtt wires add " + slug(session, CHAIN_MIDDLE) + ".state " + slug(session, CHAIN_LAST) + ".state");
        DeviceRuntime.FlowCounters before = counters(session);
        JsonObject command = new JsonObject();
        command.addProperty("state", "ON");
        DeviceRuntime.Delivery delivery = session.onRuntime(runtime -> runtime.devices().command(id(runtime, first), command, CommandSource.HA, null));
        session.check(delivery.route() == DeviceRuntime.Route.APPLY, "Home Assistant switches " + first + " on (" + delivery + ")");
        session.check(session.await(() -> power(session, CHAIN_LAST) == 15, ANSWER_TICKS),
                "the chain follows it to its last output (" + counters(session).summary() + ")");
        DeviceRuntime.FlowCounters after = counters(session);
        session.check(after.emitted(Root.HA) - before.emitted(Root.HA) == 2 && after.emitted() - before.emitted() == 2
                        && after.emitted(Root.WORLD) == before.emitted(Root.WORLD),
                "both messages of the chain are HA, the second one emitted during the first one's delivery: " + after.summary());
        for (BlockPos pos : List.of(CHAIN_FIRST, CHAIN_MIDDLE, CHAIN_LAST)) setblock(session, pos, "minecraft:air");
        session.check(session.await(() -> session.onRuntime(runtime -> runtime.registry().wires().size()) == 4, ANSWER_TICKS),
                "breaking the chain removes its wires");
    }

    /**
     * A broker message to a v1 Block Emitter places a redstone block next to the Redstone Input, which emits in the same
     * tick's neighbour update: RAW, not the world's (M3 plan D10), so nobody on the broker switches a real device through
     * it. The block placer is on for this check only.
     */
    private static void legacyRoot(GameTestSession session, String input) {
        BlockPos target = INPUT.east();
        BlockPos emitter = target.east();
        String topic = "mineqtt/shared/gametest/" + UUID.randomUUID() + "/emitter";
        boolean placer = session.onServer(s -> MineQTTConfig.blockPlacerEnabled);
        session.onServer(s -> MineQTTConfig.blockPlacerEnabled = true);
        try {
            setblock(session, emitter, "mineqtt:block_emitter_block[facing=west]");
            session.check(session.onServer(s -> {
                if (!(s.overworld().getBlockEntity(emitter) instanceof BlockEmitterBlockEntity block)) return false;
                LegacySubscriptionFacade.subscribe(topic, block);
                return true;
            }), "a Block Emitter listens on " + topic);
            DeviceRuntime.FlowCounters before = counters(session);
            session.onRuntime(runtime -> {
                runtime.mqtt().publish(new Outbound(topic, "minecraft:redstone_block".getBytes(StandardCharsets.UTF_8), 0, false,
                        OfflinePolicy.DROP, Outbound.Delivery.EDGE, UUID.randomUUID()));
                return null;
            });
            session.check(session.await(() -> power(session, OUTPUT) == 15 && lampLevel(session) > 0, ANSWER_TICKS),
                    "the message places a redstone block, which powers " + input + " and so the output and the lamp (output "
                            + power(session, OUTPUT) + ")");
            DeviceRuntime.FlowCounters after = counters(session);
            session.check(after.emitted(Root.RAW) - before.emitted(Root.RAW) == after.emitted() - before.emitted()
                            && after.emitted(Root.RAW) > before.emitted(Root.RAW),
                    "what the broker message powered is RAW, not the world's: " + after.summary());
        } finally {
            session.onServer(s -> {
                if (s.overworld().getBlockEntity(emitter) instanceof BlockEmitterBlockEntity block) LegacySubscriptionFacade.unsubscribe(topic, block);
                MineQTTConfig.blockPlacerEnabled = placer;
                return null;
            });
        }
        setblock(session, emitter, "minecraft:air");
        setblock(session, target, "minecraft:air");
        session.check(session.await(() -> power(session, OUTPUT) == 0, ANSWER_TICKS), "removing the redstone block switches the output off");
    }

    /**
     * A chunk load never switches a real device (M3 plan D10, M3-9): a Home Assistant {@code /set} queued for an output in
     * an unloaded chunk lands when the chunk loads again, and what the output then emits over its wire to a second output
     * is {@link Root#STARTUP}, not the world's, so it could never cause a request to Home Assistant. The far chunk is
     * loaded and unloaded with {@code /forceload}.
     */
    private static void attachRoot(GameTestSession session) {
        BlockPos far = FAR_OUTPUT;
        String chunk = far.getX() + " " + far.getZ();
        session.consoleCommand("forceload add " + chunk);
        setblock(session, far, "mineqtt:redstone_emitter_block");
        setblock(session, NEAR_OUTPUT, "mineqtt:redstone_emitter_block");
        for (BlockPos pos : List.of(far, NEAR_OUTPUT)) {
            session.awaitServer(s -> s.overworld().getBlockEntity(pos) instanceof DeviceHost host && host.deviceLink().attached());
        }
        String farSlug = slug(session, far);
        String nearSlug = slug(session, NEAR_OUTPUT);
        session.consoleCommand("mineqtt wires add " + farSlug + ".state " + nearSlug + ".state");
        session.consoleCommand("forceload remove " + chunk);
        // A game test's server never has time left in a tick, and the chunk map unloads chunks only with time left (or
        // 2000 waiting), so each wait gives it one pass of the chunk source with time left, as an idle server would.
        session.check(session.await(() -> session.onServer(s -> {
                    s.overworld().getChunkSource().tick(() -> true, false);
                    return !MineqttRuntime.of(s).devices().isAttached(id(MineqttRuntime.of(s), farSlug));
                }), 30 * 20),
                farSlug + " unloads with its chunk");
        JsonObject command = new JsonObject();
        command.addProperty("state", "ON");
        DeviceRuntime.Delivery queued = session.onRuntime(runtime -> runtime.devices().command(id(runtime, farSlug), command, CommandSource.HA, null));
        session.check(queued.route() == DeviceRuntime.Route.QUEUE, "Home Assistant's /set waits in " + farSlug + "'s record (" + queued + ")");
        DeviceRuntime.FlowCounters before = counters(session);
        session.consoleCommand("forceload add " + chunk);
        session.check(session.await(() -> power(session, NEAR_OUTPUT) == 15, 30 * 20),
                "loading the chunk lands the queued command, and " + nearSlug + " follows over the wire (" + counters(session).summary() + ")");
        DeviceRuntime.FlowCounters after = counters(session);
        long startup = after.emitted(Root.STARTUP) - before.emitted(Root.STARTUP);
        // The bus counts messages on ports with wires: the far output's state, which switched the near one.
        session.check(startup >= 1 && after.emitted() - before.emitted() == startup && after.emitted(Root.WORLD) == before.emitted(Root.WORLD),
                "what the attach emitted over its wire is STARTUP, never the world's: " + after.summary());
        int wires = session.onRuntime(runtime -> runtime.registry().wires().size());
        setblock(session, far, "minecraft:air");
        setblock(session, NEAR_OUTPUT, "minecraft:air");
        session.consoleCommand("forceload remove " + chunk);
        session.check(session.await(() -> session.onRuntime(runtime -> runtime.registry().wires().size()) == wires - 1, ANSWER_TICKS),
                "breaking both outputs removes their wire");
    }

    private static UUID id(MineqttRuntime runtime, String slug) {
        return runtime.registry().records().stream().filter(record -> record.slug().equals(slug)).map(DeviceRecord::id).findFirst()
                .orElseThrow(() -> new AssertionError("No device " + slug));
    }

    private static void setblock(GameTestSession session, BlockPos pos, String block) {
        session.consoleCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block);
    }

    private static String slug(GameTestSession session, BlockPos pos) {
        return session.onRuntime(runtime -> runtime.registry().records().stream().filter(record -> record.pos().pos().equals(pos))
                .map(DeviceRecord::slug).findFirst().orElseThrow(() -> new AssertionError("No device at " + pos)));
    }

    private static boolean leverOn(GameTestSession session) {
        return session.onServer(s -> s.overworld().getBlockState(LEVER).getValue(LeverBlock.POWERED));
    }

    private static int power(GameTestSession session, BlockPos pos) {
        return session.onServer(s -> s.overworld().getBlockState(pos).getValue(RedstoneEmitterBlock.POWER));
    }

    private static int lampLevel(GameTestSession session) {
        return session.onServer(s -> s.overworld().getBlockState(LAMP).getValue(RgbLedBlock.LIGHT_LEVEL));
    }

    private static DeviceRuntime.FlowCounters counters(GameTestSession session) {
        return session.onRuntime(runtime -> runtime.devices().flowCounters());
    }
}

package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.blocks.RedstoneEmitterBlock;
import art.rehra.mineqtt.blocks.RgbLedBlock;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.flow.core.Mapping;
import art.rehra.mineqtt.flow.core.MessageBus;
import art.rehra.mineqtt.flow.core.Wire;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LeverBlock;

import java.util.List;

/**
 * Local bindings (M2-15): the console wires a Redstone Input to a Redstone Output and to a Smart Lamp with
 * {@code /mineqtt wires add}, and the non-op player flips a lever on the input by hand. Both follow at once, through the
 * server's message bus and not the broker, and flipping the lever back turns them off. An incompatible pair and a
 * self-wire are refused. Two outputs wired into each other, one way inverted, make a loop without a delay: the inverted
 * wire tells its target the opposite of its source's off as it is made (M2-16), which starts the loop, and the bus cuts
 * it after 16 wires. Breaking the input takes its wires along.
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

        // Breaking the input takes its wires along; the loop's stay.
        setblock(session, INPUT, "minecraft:air");
        session.check(session.await(() -> session.onRuntime(runtime -> runtime.registry().wires().size()) == 2, ANSWER_TICKS),
                "breaking the input removes its two wires");
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

package art.rehra.mineqtt.device;

import art.rehra.mineqtt.device.types.Timers;
import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.flow.core.FlowMessage;
import art.rehra.mineqtt.flow.core.MessageBus;
import art.rehra.mineqtt.flow.core.Scope;
import art.rehra.mineqtt.flow.core.Wire;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A timer chain through the registry's wiring, which only this package reaches, for {@code verifyRuntimeWiring}
 * (M4 plan D15). It needs the device types registered.
 */
public final class TimerWiringProbe {
    private TimerWiringProbe() {
    }

    /**
     * M4-4: two timers wired to fire each other by their default wires, {@code fired→trigger}, through a real message bus
     * over the registry. Each delivery is a press on the target's Trigger now, which fires it at once; the bus cuts the
     * loop after {@link MessageBus#HOP_TTL} wires, so a timer chain never runs away.
     */
    public static void chainIsCut() {
        DeviceRegistry registry = new DeviceRegistry("chain_0001", UUID.randomUUID());
        DeviceRecord first = new DeviceRecord(UUID.randomUUID(), Timers.INTERVAL_TIMER.id(), GlobalPos.of(Level.OVERWORLD, new BlockPos(0, -60, 0)), "First", "first");
        DeviceRecord second = new DeviceRecord(UUID.randomUUID(), Timers.CRON_TIMER.id(), GlobalPos.of(Level.OVERWORLD, new BlockPos(2, -60, 0)), "Second", "second");
        registry.add(first);
        registry.add(second);
        DefaultWiring.Pair pair = DefaultWiring.of(Timers.INTERVAL_TIMER, Timers.CRON_TIMER).orElseThrow();
        for (DeviceRecord[] ends : List.of(new DeviceRecord[]{first, second}, new DeviceRecord[]{second, first})) {
            Endpoint from = Endpoint.port(ends[0].id(), pair.from());
            Endpoint to = Endpoint.port(ends[1].id(), pair.to());
            check(RegistryWiring.problem(registry, from, to, pair.mapping()).isEmpty(), "The default wire between two timers may be made: " + pair);
            registry.addWire(new Wire(UUID.randomUUID(), from, to, pair.mapping(), Scope.WIRELESS, UUID.randomUUID(), true));
        }
        List<String> presses = new ArrayList<>();
        List<UUID> cut = new ArrayList<>();
        MessageBus[] bus = new MessageBus[1];
        bus[0] = new MessageBus(new RegistryWiring(registry), (wire, message) -> {
            RegistryWiring.Command command = RegistryWiring.command(registry, wire.to(), message.payload()).orElseThrow();
            presses.add(command.command().toString());
            // The timer fires at once; its event carries the delivered message's hops on.
            bus[0].emit(FlowMessage.Kind.EVENT, Endpoint.port(command.target(), DefaultWiring.FIRED), DefaultWiring.FIRED, Timers.fired(Timers.Source.MANUAL), 0);
            return MessageBus.Outcome.TAKEN;
        }, new MessageBus.Listener() {
            @Override
            public void loopCut(Wire wire, FlowMessage message) {
                cut.add(wire.id());
            }

            @Override
            public void failed(Wire wire, FlowMessage message, RuntimeException e) {
                throw new AssertionError("No delivery throws here", e);
            }
        // A frozen clock: the 2 ms drain budget must not cut a drain short on a busy machine (a parallel build).
        }, () -> 0L);
        bus[0].emit(FlowMessage.Kind.EVENT, Endpoint.port(first.id(), DefaultWiring.FIRED), DefaultWiring.FIRED, Timers.fired(Timers.Source.SCHEDULE), 0);
        // A drain has a budget, as a server tick does; the chain goes on over the next ones until the bus cuts it.
        for (int tick = 0; tick < 100 && bus[0].hasQueued(); tick++) bus[0].drain();
        check(presses.size() == MessageBus.HOP_TTL && presses.stream().allMatch("{\"trigger\":\"PRESS\"}"::equals),
                "Each hop presses the next timer's Trigger now, " + MessageBus.HOP_TTL + " in all: " + presses);
        check(cut.size() == 1 && bus[0].dropped(MessageBus.Drop.HOPS) == 1 && !bus[0].hasQueued(), "and the loop is cut there, once: " + cut.size() + " cuts, " + bus[0].dropped(MessageBus.Drop.HOPS) + " dropped, queued " + bus[0].hasQueued());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

package art.rehra.mineqtt.runtime;

import art.rehra.mineqtt.device.DeviceRuntime;
import dev.architectury.event.events.common.ChunkEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.function.Consumer;

/** Drives {@link MineqttRuntime} from the server lifecycle. Server-side only: a client never connects to MQTT. */
public final class ServerHooks {
    private ServerHooks() {
    }

    /**
     * Registers the lifecycle and tick listeners. Call it before any other MineQTT listener on the same events, so
     * the runtime exists for theirs and the tick's messages are delivered before they run.
     */
    public static void register() {
        LifecycleEvent.SERVER_STARTED.register(MineqttRuntime::start);
        TickEvent.SERVER_PRE.register(server -> ifRunning(server, MineqttRuntime::tickStart));
        TickEvent.SERVER_POST.register(server -> ifRunning(server, MineqttRuntime::tickEnd));
        LifecycleEvent.SERVER_LEVEL_SAVE.register(level -> ifRunning(level.getServer(), runtime -> runtime.levelSaved(level)));
        ChunkEvent.LOAD.register((chunk, level, newChunk) -> {
            if (level instanceof ServerLevel serverLevel) ifRunning(serverLevel.getServer(), runtime -> runtime.devices().chunkLoaded(serverLevel, chunk.getPos()));
        });
        // A datapack reload may change a tag behind a runtime select's options (M4 plan D12: #mineqtt:placeable, the Block
        // Placer's blocks): discovery republishes the devices whose options changed. A client's copy of the tags is no
        // concern of the runtime.
        LifecycleEvent.TAGS_UPDATED.register((registries, client) -> {
            if (!client) MineqttRuntime.current().ifPresent(runtime -> runtime.discovery().refreshDynamicOptions());
        });
        LifecycleEvent.SERVER_STOPPING.register(server -> ifRunning(server, MineqttRuntime::stop));
        LifecycleEvent.SERVER_STOPPED.register(server -> {
            ifRunning(server, MineqttRuntime::stopped);
            DeviceRuntime.serverStopped(server);
        });
    }

    private static void ifRunning(MinecraftServer server, Consumer<MineqttRuntime> action) {
        MineqttRuntime runtime = MineqttRuntime.find(server);
        if (runtime != null) action.accept(runtime);
    }
}

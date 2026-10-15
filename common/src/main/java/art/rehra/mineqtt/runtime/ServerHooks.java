package art.rehra.mineqtt.runtime;

import art.rehra.mineqtt.device.DeviceRuntime;
//? if >=26.1 {
import dev.architectury.event.events.common.ChunkEvent;
//?}
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

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
        // Architectury has these two events from 26.1 on; before, the loaders' own events call chunkLoaded and tagsUpdated.
        //? if >=26.1 {
        ChunkEvent.LOAD.register((chunk, level, newChunk) -> {
            if (level instanceof ServerLevel serverLevel) chunkLoaded(serverLevel, chunk.getPos());
        });
        LifecycleEvent.TAGS_UPDATED.register((registries, client) -> tagsUpdated(client));
        //?}
        LifecycleEvent.SERVER_STOPPING.register(server -> ifRunning(server, MineqttRuntime::stop));
        LifecycleEvent.SERVER_STOPPED.register(server -> {
            ifRunning(server, MineqttRuntime::stopped);
            DeviceRuntime.serverStopped(server);
        });
    }

    /** A chunk of a server level reached FULL. */
    public static void chunkLoaded(ServerLevel level, ChunkPos chunk) {
        ifRunning(level.getServer(), runtime -> runtime.devices().chunkLoaded(level, chunk));
    }

    /**
     * Tags were loaded. A datapack reload may change a tag behind a runtime select's options (M4 plan D12:
     * #mineqtt:placeable, the Block Placer's blocks): discovery republishes the devices whose options changed. A client's
     * copy of the tags is no concern of the runtime.
     */
    public static void tagsUpdated(boolean client) {
        if (!client) MineqttRuntime.current().ifPresent(runtime -> runtime.discovery().refreshDynamicOptions());
    }

    private static void ifRunning(MinecraftServer server, Consumer<MineqttRuntime> action) {
        MineqttRuntime runtime = MineqttRuntime.find(server);
        if (runtime != null) action.accept(runtime);
    }
}

package art.rehra.mineqtt.neoforge;

//? if <26.1 {
/*import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.runtime.ServerHooks;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
*///?}

/**
 * The chunk load and tags updated events of NeoForge's game bus for {@link art.rehra.mineqtt.runtime.ServerHooks}, before
 * 26.1: Architectury has them as its own events from 26.1 on.
 */
//? if >=1.21.6 && <26.1 {
/*@EventBusSubscriber(modid = MineQTT.MOD_ID)
*///?} else if <1.21.6 {
/*@EventBusSubscriber(modid = MineQTT.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
*///?}
public final class NeoForgeServerEvents {
    private NeoForgeServerEvents() {
    }
    //? if <26.1 {

    /*@SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) ServerHooks.chunkLoaded(level, event.getChunk().getPos());
    }

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        ServerHooks.tagsUpdated(event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED);
    }
    *///?}
}

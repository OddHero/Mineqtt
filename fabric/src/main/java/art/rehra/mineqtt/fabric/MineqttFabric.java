package art.rehra.mineqtt.fabric;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.entities.MineqttBlockEntityTypes;
import art.rehra.mineqtt.fabric.config.FabricConfigHandler;
import art.rehra.mineqtt.fabric.integrations.FabricModLoaderUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class MineqttFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // Proceed with mild caution.

        // Set up the mod loader utils for Fabric
        MineQTT.setModLoaderUtils(new FabricModLoaderUtils());

        // Set up the config handler for Fabric
        MineQTT.setConfigHandler(new FabricConfigHandler());

        // Run our common setup.
        MineQTT.init();

        closeItemStorage();
    }

    /**
     * The slots stay closed to automation (M2 plan D7): {@code BaseMqttBlockEntity} has no faces, but Fabric's
     * {@code ItemStorage.SIDED} container fallback wraps a container whole for a side-less ({@code null}) query. A
     * provider for the block entity type wins over that fallback, so every query, sided or not, gets an empty storage.
     * Hoppers and droppers use the vanilla container and are unaffected. The types resolve here because
     * {@code MineQTT.init()} registered them, and Fabric registers eagerly. Devices have no container (M2-12), so no
     * fallback wraps them.
     */
    private static void closeItemStorage() {
        ItemStorage.SIDED.registerForBlockEntities((blockEntity, side) -> Storage.empty(),
                MineqttBlockEntityTypes.slottedTypes().toArray(BlockEntityType<?>[]::new));
    }
}

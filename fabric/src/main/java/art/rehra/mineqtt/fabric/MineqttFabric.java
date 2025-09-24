package art.rehra.mineqtt.fabric;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.fabric.blocks.entities.MineqttBlockEntityTypesFabric;
import art.rehra.mineqtt.fabric.config.FabricConfigHandler;
import net.fabricmc.api.ModInitializer;

public final class MineqttFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // Proceed with mild caution.

        // Set up the config handler for Fabric
        MineQTT.setConfigHandler(new FabricConfigHandler());

        // Run our common setup.
        MineQTT.init();

        MineqttBlockEntityTypesFabric.initBlockEntityTypes();
    }
}

package art.rehra.mineqtt.neoforge;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.neoforge.config.NeoForgeConfigHandler;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(MineQTT.MOD_ID)
public final class MineqttNeoForge {
    public MineqttNeoForge(ModContainer container) {
        // Set up the config handler for NeoForge (now using JSON like Fabric)
        MineQTT.setConfigHandler(new NeoForgeConfigHandler());

        // For now, we'll skip the config screen integration for NeoForge
        // We'll focus on getting the basic functionality working first

        // Run our common setup
        MineQTT.init();
    }
}

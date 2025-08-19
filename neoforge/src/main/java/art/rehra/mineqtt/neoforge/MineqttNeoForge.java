package art.rehra.mineqtt.neoforge;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.neoforge.config.NeoForgeConfigHandler;
import art.rehra.mineqtt.neoforge.config.NeoForgeConfigScreenIntegration;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@Mod(MineQTT.MOD_ID)
@EventBusSubscriber(modid = MineQTT.MOD_ID)
public final class MineqttNeoForge {
    private static NeoForgeConfigHandler configHandler;

    public MineqttNeoForge(ModContainer container) {
        // Set up the config handler for NeoForge using native config system
        configHandler = new NeoForgeConfigHandler();
        MineQTT.setConfigHandler(configHandler);

        // Register the config with NeoForge
        container.registerConfig(ModConfig.Type.COMMON, NeoForgeConfigHandler.SPEC, "mineqtt.toml");

        // Register config screen with NeoForge's mod menu
        NeoForgeConfigScreenIntegration.register(container);

        // Run our common setup
        MineQTT.init();
    }

    @SubscribeEvent
    public static void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getModId().equals(MineQTT.MOD_ID)) {
            MineQTT.LOGGER.info("Loading MineQTT config...");
            if (configHandler != null) {
                configHandler.loadConfig();
                MineQTT.initializeMqttClient();
            }
        }
    }

    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getModId().equals(MineQTT.MOD_ID)) {
            MineQTT.LOGGER.info("Reloading MineQTT config...");
            if (configHandler != null) {
                configHandler.loadConfig();
                MineQTT.initializeMqttClient();
            }
        }
    }
}

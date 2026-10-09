package art.rehra.mineqtt.neoforge;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.neoforge.config.NeoForgeConfigHandler;
import art.rehra.mineqtt.neoforge.integrations.NeoForgeModLoaderUtils;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLPaths;

@Mod(MineQTT.MOD_ID)
@EventBusSubscriber(modid = MineQTT.MOD_ID)
public final class MineqttNeoForge {
    private static NeoForgeConfigHandler configHandler;

    public MineqttNeoForge(ModContainer container) {
        // Set up the mod loader utils for NeoForge
        MineQTT.setModLoaderUtils(new NeoForgeModLoaderUtils());

        // Set up the config handler for NeoForge using native config system
        configHandler = new NeoForgeConfigHandler();
        MineQTT.setConfigHandler(configHandler);

        // A v1 file must be rewritten before NeoForge loads it, which would drop the v1 keys
        NeoForgeConfigHandler.migrateV1File(FMLPaths.CONFIGDIR.get().resolve(NeoForgeConfigHandler.FILE_NAME));

        // Register the config with NeoForge
        container.registerConfig(ModConfig.Type.COMMON, NeoForgeConfigHandler.SPEC, NeoForgeConfigHandler.FILE_NAME);

        // Run our common setup
        MineQTT.init();
    }

    @SubscribeEvent
    public static void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getModId().equals(MineQTT.MOD_ID)) {
            MineQTT.LOGGER.info("Loading MineQTT config...");
            if (configHandler != null) {
                configHandler.loadConfig();
                MineqttRuntime.configChanged();
            }
        }
    }

    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getModId().equals(MineQTT.MOD_ID)) {
            MineQTT.LOGGER.info("Reloading MineQTT config...");
            if (configHandler != null) {
                // Saves (commands, the config screen) reload too; the runtime reconnects only for changed connection settings.
                configHandler.loadConfig();
                MineqttRuntime.configChanged();
            }
        }
    }
}

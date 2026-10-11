package art.rehra.mineqtt;

import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.blocks.entities.MineqttBlockEntityTypes;
import art.rehra.mineqtt.commands.MineQTTCommands;
import art.rehra.mineqtt.config.ConfigHandler;
import art.rehra.mineqtt.device.DeviceTypes;
import art.rehra.mineqtt.integrations.IModLoaderUtils;
import art.rehra.mineqtt.integrations.PermissionManager;
import art.rehra.mineqtt.items.ConfiguratorSessions;
import art.rehra.mineqtt.items.MineqttItems;
import art.rehra.mineqtt.legacy.LegacySubscriptionFacade;
import art.rehra.mineqtt.mqtt.CyberdeckSessionManager;
import art.rehra.mineqtt.network.MineqttNetworking;
import art.rehra.mineqtt.registry.MineqttDataComponents;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.runtime.ServerHooks;
import art.rehra.mineqtt.security.C2SGuard;
import art.rehra.mineqtt.tabs.MineQTTTabs;
import art.rehra.mineqtt.ui.MineqttMenuTypes;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import art.rehra.mineqtt.twin.BindingPresets;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MineQTT {
    public static final String MOD_ID = "mineqtt";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static ConfigHandler configHandler;
    public static PermissionManager permissionManager;

    public static IModLoaderUtils modLoaderUtils;

    public static void init() {
        LOGGER.info("Initializing MineQTT...");

        if (modLoaderUtils == null) {
            throw new IllegalStateException("IModLoaderUtils not set. Please set it before calling init().");
        }

        if (configHandler == null) {
            throw new IllegalStateException("ConfigHandler not set. Please set it before calling init().");
        }

        // Initialize PermissionManager
        permissionManager = new PermissionManager();
        // Load configuration into MineQTTConfig
        configHandler.loadConfig();

        LOGGER.info("MineQTT initialized successfully");

        MineQTTTabs.init();
        MineqttDataComponents.init();
        MineqttBlocks.init();
        // After the blocks: Fabric registers eagerly, and every block entity type names its block.
        MineqttBlockEntityTypes.init();
        MineqttItems.init();

        MineqttMenuTypes.init();
        MineqttNetworking.init();
        DeviceTypes.registerBuiltIn();
        // MQTT device presets come from datapacks, reloaded with them (M3-17).
        BindingPresets.register();

        // First, so the runtime exists for the listeners below and delivers each tick's messages before they run.
        ServerHooks.register();

        LifecycleEvent.SERVER_STARTED.register(server -> LegacySubscriptionFacade.attach(MineqttRuntime.of(server)));

        TickEvent.SERVER_PRE.register(CyberdeckSessionManager::tick);

        // Not the runtime's: block screens open without MQTT too.
        TickEvent.PLAYER_POST.register(player -> {
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) TabbedMqttMenu.abilitiesChanged(serverPlayer);
        });

        dev.architectury.event.events.common.PlayerEvent.PLAYER_JOIN.register(player -> {
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                CyberdeckSessionManager.onPlayerLoggedIn(serverPlayer);
                MineqttRuntime.current().filter(runtime -> runtime.server() == serverPlayer.level().getServer())
                        .ifPresent(runtime -> {
                            runtime.devices().playerJoined(serverPlayer);
                            runtime.devices().deliverNotices(serverPlayer);
                        });
            }
        });

        dev.architectury.event.events.common.PlayerEvent.PLAYER_QUIT.register(player -> {
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                CyberdeckSessionManager.onPlayerLoggedOut(serverPlayer);
                ConfiguratorSessions.forget(serverPlayer.getUUID());
                C2SGuard.forget(serverPlayer.getUUID());
                MineqttRuntime.current().filter(runtime -> runtime.server() == serverPlayer.level().getServer())
                        .ifPresent(runtime -> runtime.devices().playerLeft(serverPlayer.getUUID()));
            }
        });

        CommandRegistrationEvent.EVENT.register((dispatcher, registryAccess, selection) -> {
            MineQTTCommands.register(dispatcher);
        });

        LifecycleEvent.SERVER_STOPPING.register(server -> {
            // Close the legacy subscriptions; the next session's blocks register anew
            LegacySubscriptionFacade.detach();
            CyberdeckSessionManager.onServerStopping();
            ConfiguratorSessions.clear();
            C2SGuard.clear();
        });
    }

    public static void setModLoaderUtils(IModLoaderUtils utils) {
        modLoaderUtils = utils;
    }

    public static void setConfigHandler(ConfigHandler handler) {
        configHandler = handler;
    }

    public static ConfigHandler getConfigHandler() {
        return configHandler;
    }
}

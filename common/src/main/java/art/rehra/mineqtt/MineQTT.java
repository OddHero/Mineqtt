package art.rehra.mineqtt;

import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.config.ConfigHandler;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.integrations.IModLoaderUtils;
import art.rehra.mineqtt.integrations.PermissionManager;
import art.rehra.mineqtt.items.MineqttItems;
import art.rehra.mineqtt.mqtt.SubscriptionManager;
import art.rehra.mineqtt.mqtt.BlockStateManager;
import art.rehra.mineqtt.mqtt.homeassistant.HomeAssistantDiscoveryManager;
import art.rehra.mineqtt.tabs.MineQTTTabs;
import art.rehra.mineqtt.ui.MineqttMenuTypes;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.lifecycle.MqttClientAutoReconnect;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public class MineQTT {
    public static final String MOD_ID = "mineqtt";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static ConfigHandler configHandler;
    public static PermissionManager permissionManager;
    public static Mqtt3AsyncClient mqttClient;

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
        MineqttBlocks.init();
        MineqttItems.init();

        MineqttMenuTypes.init();


        LifecycleEvent.SERVER_STARTING.register(server -> {
            LOGGER.info("Server starting - initializing MQTT client");
            SubscriptionManager.init();
            BlockStateManager.init();
            HomeAssistantDiscoveryManager.init();
            initializeMqttClient();
        });

        LifecycleEvent.SERVER_LEVEL_LOAD.register(level -> {
            // Load persisted block states when overworld loads (once per world)
            if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
                // Save to world folder: saves/WorldName/data/mineqtt/
                Path worldSaveDir = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("data").resolve("mineqtt");
                BlockStateManager.loadPersistedData(worldSaveDir);
                LOGGER.info("Loaded MQTT block state data for world: " + level.getServer().getWorldData().getLevelName());
            }
        });

        LifecycleEvent.SERVER_LEVEL_SAVE.register(level -> {
            // Save persisted block states when overworld saves
            if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
                BlockStateManager.savePersistedData();
            }
        });

        TickEvent.SERVER_PRE.register(SubscriptionManager::onServerPreTick);

        LifecycleEvent.SERVER_STOPPING.register(server -> {
            LOGGER.info("Server stopping - saving block state data and disconnecting MQTT client");

            // Save block state data before shutdown (final save)
            BlockStateManager.savePersistedData();

            if (mqttClient != null && mqttClient.getState().isConnected()) {
                try {
                    // First publish the offline status
                    mqttClient.publishWith()
                            .topic(MineQTTConfig.getTopicPath(MineQTTConfig.statusTopic))
                            .payload("Offline".getBytes())
                            .qos(MqttQos.AT_LEAST_ONCE)
                            .retain(true)
                            .send()
                            .whenComplete((result, throwable) -> {
                                // Then disconnect the client
                                mqttClient.disconnect().whenComplete((disconnectResult, disconnectThrowable) -> {
                                    if (disconnectThrowable != null) {
                                        LOGGER.warn("Error during MQTT disconnect: {}", disconnectThrowable.getMessage());
                                    } else {
                                        LOGGER.info("MQTT client disconnected successfully");
                                    }
                                });
                            });
                } catch (Exception e) {
                    LOGGER.warn("Error during MQTT shutdown: {}", e.getMessage());
                    // Force disconnect if publish fails
                    mqttClient.disconnect();
                }
            }
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

    // New: public wrapper to allow platforms to reinitialize the MQTT client
    public static void initializeMqttClient() {
        if (MineQTTConfig.brokerUrl == null || MineQTTConfig.brokerUrl.isEmpty()) {
            LOGGER.warn("MQTT broker URL not configured, skipping MQTT initialization");
            return;
        }

        try {
            LOGGER.info("MQTT client configuration:");
            LOGGER.info("   Broker URL: {}", MineQTTConfig.brokerUrl);
            LOGGER.info("   Client ID: {}", MineQTTConfig.clientId);
            LOGGER.info("   Auto Reconnect: {}", MineQTTConfig.autoReconnect);

            mqttClient = MqttClient.builder()
                    .serverHost(MineQTTConfig.brokerUrl)
                    .identifier(MineQTTConfig.clientId)
                    .useMqttVersion3()
                    .executorConfig()
                    .nettyThreads(2)
                    .applyExecutorConfig()
                    .automaticReconnect(MineQTTConfig.autoReconnect
                            ? MqttClientAutoReconnect.builder().initialDelay(MineQTTConfig.connectionTimeout, TimeUnit.SECONDS).build()
                            : null)
                    .willPublish().topic(MineQTTConfig.getTopicPath(MineQTTConfig.statusTopic)).payload("Offline".getBytes()).qos(MqttQos.AT_LEAST_ONCE).retain(true).applyWillPublish()
                    .simpleAuth().username(MineQTTConfig.username)
                    .password(MineQTTConfig.password.getBytes())
                    .applySimpleAuth()
                    .addConnectedListener(
                            conn -> {
                                LOGGER.info("MQTT client connected to broker: {}", MineQTTConfig.brokerUrl);
                                // Publish "Online" status message upon successful connection
                                mqttClient.publishWith().topic(MineQTTConfig.getTopicPath(MineQTTConfig.statusTopic))
                                        .payload("Online".getBytes())
                                        .qos(MqttQos.AT_LEAST_ONCE)
                                        .retain(true)
                                        .send();
                            })
                    .addDisconnectedListener(
                            disconn -> LOGGER.warn("MQTT client disconnected from broker.", disconn.getCause()))
                    .buildAsync();

            mqttClient.connect().whenComplete((connAck, throwable) -> {
                if (throwable != null) {
                    LOGGER.error("Failed to connect to MQTT broker", throwable);
                } else {
                    LOGGER.info("Connected to MQTT broker successfully");
                }
            });
        } catch (Exception e) {
            LOGGER.error("Failed to connect to MQTT broker", e);
        }
    }
}

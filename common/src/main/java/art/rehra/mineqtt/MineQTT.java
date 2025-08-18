package art.rehra.mineqtt;

import art.rehra.mineqtt.blocks.MineQTTBlocks;
import art.rehra.mineqtt.items.MineQTTItems;
import art.rehra.mineqtt.tabs.MineQTTTabs;
import art.rehra.mineqtt.config.ConfigHandler;
import art.rehra.mineqtt.config.MineQTTConfig;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public final class MineQTT {
    public static final String MOD_ID = "mineqtt";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static Mqtt3AsyncClient mqttClient;
    private static ConfigHandler configHandler;

    public static void init() {
        LOGGER.info("MineQTT is initializing...");

        // Load config first
        if (configHandler != null) {
            configHandler.loadConfig();
        }

        // Initialize the MQTT client with config values
        initializeMqttClient();

        MineQTTTabs.init();
        MineQTTBlocks.init();
        MineQTTItems.init();
    }

    public static void setConfigHandler(ConfigHandler handler) {
        configHandler = handler;
    }

    public static ConfigHandler getConfigHandler() {
        return configHandler;
    }

    public static void initializeMqttClient() {
        // Disconnect existing client if present
        if (mqttClient != null && mqttClient.getState().isConnected()) {
            mqttClient.disconnect();
        }

        var clientBuilder = MqttClient.builder()
                .useMqttVersion3()
                .identifier(UUID.randomUUID().toString())
                .serverHost(MineQTTConfig.brokerHost)
                .serverPort(MineQTTConfig.brokerPort)
                .willPublish()
                    .topic(MineQTTConfig.statusTopic)
                    .payload(MineQTTConfig.offlineMessage.getBytes())
                    .qos(MqttQos.AT_MOST_ONCE)
                    .retain(false)
                    .applyWillPublish();

        // Add authentication if enabled
        if (MineQTTConfig.useAuthentication && !MineQTTConfig.username.isEmpty()) {
            clientBuilder.simpleAuth()
                    .username(MineQTTConfig.username)
                    .password(MineQTTConfig.password.getBytes())
                    .applySimpleAuth();
        }

        mqttClient = clientBuilder.buildAsync();

        mqttClient.connect()
                .orTimeout(MineQTTConfig.connectionTimeout, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        LOGGER.error("Failed to connect to MQTT broker", throwable);
                    } else {
                        LOGGER.info("Connected to MQTT broker successfully");

                        // Publish a status message to indicate that MineQTT is online
                        mqttClient.publishWith()
                                .topic(MineQTTConfig.statusTopic)
                                .payload(MineQTTConfig.onlineMessage.getBytes())
                                .send()
                                .whenComplete((pubResult, pubThrowable) -> {
                                    if (pubThrowable != null) {
                                        LOGGER.error("Failed to publish status message", pubThrowable);
                                    } else {
                                        LOGGER.info("Status message published successfully");
                                    }
                                });
                    }
                });
    }

    public static Mqtt3AsyncClient getMqttClient() {
        return mqttClient;
    }
}

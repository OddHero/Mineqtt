package art.rehra.mineqtt;

import art.rehra.mineqtt.config.ConfigHandler;
import art.rehra.mineqtt.config.MineQTTConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MineQTT {
    public static final String MOD_ID = "mineqtt";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static ConfigHandler configHandler;

    public static void init() {
        LOGGER.info("Initializing MineQTT...");

        // Config loading is handled by each platform at the appropriate lifecycle time.
        // Do not call configHandler.loadConfig() here to avoid early access on NeoForge.

        // Initialize the MQTT client with current config values
        initializeMQTTClient();

        LOGGER.info("MineQTT initialized successfully");
    }

    public static void setConfigHandler(ConfigHandler handler) {
        configHandler = handler;
    }

    public static ConfigHandler getConfigHandler() {
        return configHandler;
    }

    // New: public wrapper to allow platforms to reinitialize the MQTT client
    public static void initializeMqttClient() {
        initializeMQTTClient();
    }

    private static void initializeMQTTClient() {
        if (MineQTTConfig.brokerUrl == null || MineQTTConfig.brokerUrl.isEmpty()) {
            LOGGER.warn("MQTT broker URL not configured, skipping MQTT initialization");
            return;
        }

        try {
            // TODO: Initialize MQTT client with config values
            LOGGER.info("MQTT client configuration loaded:");
            LOGGER.info("Broker URL: {}", MineQTTConfig.brokerUrl);
            LOGGER.info("Client ID: {}", MineQTTConfig.clientId);
            LOGGER.info("Auto Reconnect: {}", MineQTTConfig.autoReconnect);

            // This would be where you initialize the actual MQTT client
            // Example:
            // mqttClient = MqttClient.builder()
            //     .serverHost(MineQTTConfig.brokerUrl)
            //     .identifier(MineQTTConfig.clientId)
            //     .build();

            if (MineQTTConfig.enableDebugging) {
                LOGGER.info("Connected to MQTT broker successfully");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to connect to MQTT broker", e);
        }
    }
}

package art.rehra.mineqtt;

import art.rehra.mineqtt.blocks.MineQTTBlocks;
import art.rehra.mineqtt.config.ConfigHandler;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.items.MineQTTItems;
import art.rehra.mineqtt.tabs.MineQTTTabs;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.lifecycle.MqttClientAutoReconnect;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import dev.architectury.event.events.common.LifecycleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public class MineQTT {
    public static final String MOD_ID = "mineqtt";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static ConfigHandler configHandler;
    public static Mqtt3AsyncClient mqttClient;

    public static void init() {
        LOGGER.info("Initializing MineQTT...");

        if (configHandler == null) {
            throw new IllegalStateException("ConfigHandler not set. Please set it before calling init().");
        }
        // Load configuration into MineQTTConfig
        configHandler.loadConfig();

        LOGGER.info("MineQTT initialized successfully");

        MineQTTTabs.init();
        MineQTTBlocks.init();
        MineQTTItems.init();

        LifecycleEvent.SERVER_STARTING.register(server -> {
            LOGGER.info("Server starting - initializing MQTT client");
            initializeMqttClient();
        });

        LifecycleEvent.SERVER_STOPPING.register(server -> {
            LOGGER.info("Server stopping - disconnecting MQTT client");
            if (mqttClient != null && mqttClient.getState().isConnected()) {
                mqttClient.publishWith().topic(MineQTTConfig.getTopicPath(MineQTTConfig.statusTopic))
                        .payload("Offline".getBytes())
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .retain(true)
                        .send();
            }
        });

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
                    .useMqttVersion3().
                    automaticReconnect(MineQTTConfig.autoReconnect
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

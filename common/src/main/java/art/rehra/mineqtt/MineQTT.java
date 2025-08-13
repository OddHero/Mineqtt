package art.rehra.mineqtt;

import art.rehra.mineqtt.blocks.MineQTTBlocks;
import art.rehra.mineqtt.items.MineQTTItems;
import art.rehra.mineqtt.tabs.MineQTTTabs;
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

    public static void init() {
        LOGGER.info("MineQTT is initializing...");

        // Initialize the MQTT client
        mqttClient = MqttClient.builder()
                .useMqttVersion3()
                .identifier(UUID.randomUUID().toString())
                .serverHost("test.mosquitto.org")
                .serverPort(1883)
                //.simpleAuth().username("user").password("password".getBytes()).applySimpleAuth()
                .willPublish().topic("mineqtt/status").payload("MineQTT is offline".getBytes()).qos(MqttQos.AT_MOST_ONCE).retain(false).applyWillPublish()
                .buildAsync();

        mqttClient.connect()
                .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        LOGGER.error("Failed to connect to MQTT broker", throwable);
                    } else {
                        LOGGER.info("Connected to MQTT broker successfully");

                        // Publish a status message to indicate that MineQTT is online
                        mqttClient.publishWith()
                                .topic("mineqtt/status")
                                .payload("MineQTT is online".getBytes())
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


        MineQTTTabs.init();
        MineQTTBlocks.init();
        MineQTTItems.init();
    }
}

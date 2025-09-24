package art.rehra.mineqtt.mqtt;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.entities.SubscriberBlockEntity;
import net.minecraft.server.MinecraftServer;

import java.util.*;

public class SubscriptionManager {

    // Map topic -> set of ICallbackTarget subscribers
    private static final Map<String, Set<ICallbackTarget>> topicSubscribers = new HashMap<>();

    // Tracks which topics are currently subscribed at the MQTT client level
    private static final Set<String> activeTopics = new HashSet<>();

    // Map topic -> last received message (non-retained)
    private static final Map<String, String> lastMessages = new HashMap<>();

    // Map topic -> retained message
    private static final Map<String, String> retainedMessages = new HashMap<>();

    // Initialization method to be called from main Mod file
    public static void init() {
        topicSubscribers.clear();
        activeTopics.clear();
        lastMessages.clear();
        retainedMessages.clear();
    }

    // Subscribe a ICallbackTarget to a topic
    public static void subscribe(String topic, ICallbackTarget callbackTarget) {
        topicSubscribers.computeIfAbsent(topic, k -> new HashSet<>()).add(callbackTarget);

        // Only subscribe at MQTT level if not already done
        if (activeTopics.add(topic)) {
            subscribeToMqttTopic(topic);
        }

        // If we have a retained message for this topic, send it immediately
        String retained = retainedMessages.get(topic);
        if (retained != null) {
            callbackTarget.onMessageReceived(topic, retained);
        }
    }

    // Unsubscribe a ICallbackTarget from a topic
    public static void unsubscribe(String topic, ICallbackTarget callbackTarget) {
        Set<ICallbackTarget> subscribers = topicSubscribers.get(topic);
        if (subscribers != null) {
            subscribers.remove(callbackTarget);
            if (subscribers.isEmpty()) {
                topicSubscribers.remove(topic);
                // Unsubscribe at MQTT level when last subscriber is gone
                unsubscribeFromMqttTopic(topic);
                activeTopics.remove(topic);
            }
        }
    }

    // Internal: Subscribe to MQTT topic
    private static void subscribeToMqttTopic(String topic) {
        MineQTT.LOGGER.info("Subscribing to MQTT topic: " + topic);
        if (MineQTT.mqttClient != null && MineQTT.mqttClient.getState().isConnected()) {
            MineQTT.mqttClient.subscribeWith()
                    .topicFilter(topic)
                    .callback(publish -> {
                        String message = new String(publish.getPayloadAsBytes());
                        MineQTT.LOGGER.info("Received message on topic " + publish.getTopic() + ": " + message);
                        if (publish.isRetain()) {
                            retainedMessages.put(topic, message);
                        } else {
                            lastMessages.put(topic, message);
                        }
                    })
                    .send();
        } else {
            MineQTT.LOGGER.warn("MQTT client not connected, cannot subscribe to topic: " + topic);
        }
    }

    // Internal: Unsubscribe from MQTT topic
    private static void unsubscribeFromMqttTopic(String topic) {
        MineQTT.LOGGER.info("Unsubscribing from MQTT topic: " + topic);
        if (MineQTT.mqttClient != null && MineQTT.mqttClient.getState().isConnected()) {
            MineQTT.mqttClient.unsubscribeWith()
                    .topicFilter(topic)
                    .send();
        } else {
            MineQTT.LOGGER.warn("MQTT client not connected, cannot unsubscribe from topic: " + topic);
        }
    }

    public static void onServerPreTick(MinecraftServer server) {
        for (Map.Entry<String, Set<ICallbackTarget>> entry : topicSubscribers.entrySet()) {
            String topic = entry.getKey();
            String message = lastMessages.get(topic);
            if (message == null) continue;
            for (ICallbackTarget target : entry.getValue()) {
                var blockEntity = server.getLevel(server.overworld().dimension()).getBlockEntity(target.getPosition());
                if (blockEntity instanceof SubscriberBlockEntity subscriber) {
                    subscriber.onMessageReceived(topic, message);
                }
            }
            // Remove the message after delivery (unless retained)
            lastMessages.remove(topic);
        }
    }
}

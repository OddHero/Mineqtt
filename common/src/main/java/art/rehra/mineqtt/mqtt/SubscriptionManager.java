package art.rehra.mineqtt.mqtt;

import art.rehra.mineqtt.MineQTT;
import net.minecraft.server.MinecraftServer;

import java.util.*;

public class SubscriptionManager {

    // Map topic -> set of ICallbackTarget subscribers
    private static final Map<String, Set<ICallbackTarget>> topicSubscribers = Collections.synchronizedMap(new HashMap<>());

    // Tracks which topics are currently subscribed at the MQTT client level
    private static final Set<String> activeTopics = Collections.synchronizedSet(new HashSet<>());

    // Map topic -> last received message (pending delivery)
    private static final Map<String, String> lastMessages = Collections.synchronizedMap(new HashMap<>());

    // Map topic -> cached last message (for immediate delivery to new subscribers)
    private static final Map<String, String> cachedMessages = Collections.synchronizedMap(new HashMap<>());

    // Set of topics currently subscribed at MQTT level
    private static final Set<String> subscribedMqttTopics = Collections.synchronizedSet(new HashSet<>());

    // Clear block subscribers but keep activeTopics and cachedMessages
    public static void clearSubscribers() {
        topicSubscribers.clear();
        synchronized (lastMessages) {
            lastMessages.clear();
        }
        subscribedMqttTopics.clear();
    }

    // Full reset (e.g. for testing)
    public static void init() {
        clearSubscribers();
        activeTopics.clear();
        cachedMessages.clear();
    }


    // Subscribe a ICallbackTarget to a topic
    public static void subscribe(String topic, ICallbackTarget callbackTarget) {
        Set<ICallbackTarget> subscribers = topicSubscribers.computeIfAbsent(topic, k -> Collections.synchronizedSet(new HashSet<>()));
        subscribers.add(callbackTarget);

        // If we have a cached message, deliver it immediately to the new subscriber
        String cachedMessage = cachedMessages.get(topic);
        if (cachedMessage != null) {
            callbackTarget.onMessageReceived(topic, cachedMessage);
        }

        // Only subscribe at MQTT level if not already done
        if (activeTopics.add(topic)) {
            subscribeToMqttTopic(topic);
        }
    }

    // Unsubscribe a ICallbackTarget from a topic
    public static void unsubscribe(String topic, ICallbackTarget callbackTarget) {
        Set<ICallbackTarget> subscribers = topicSubscribers.get(topic);
        if (subscribers != null) {
            subscribers.remove(callbackTarget);
            if (subscribers.isEmpty()) {
                // Remove topic if no more subscribers
                // Use a lock or synchronized check if needed, but here simple remove is usually enough
                // if we don't care about race with computeIfAbsent as much
                topicSubscribers.remove(topic, subscribers); 
                
                // Unsubscribe at MQTT level when last subscriber is gone
                unsubscribeFromMqttTopic(topic);
                activeTopics.remove(topic);
            }
        }
    }

    // Internal: Subscribe to MQTT topic
    private static void subscribeToMqttTopic(String topic) {
        if (MineQTT.mqttClient != null && MineQTT.mqttClient.getState().isConnected()) {
            MineQTT.LOGGER.info("Subscribing to MQTT topic: " + topic);
            MineQTT.mqttClient.subscribeWith()
                    .topicFilter(topic)
                    .callback(publish -> {
                        String receivedTopic = publish.getTopic().toString();
                        String message = new String(publish.getPayloadAsBytes());
                        MineQTT.LOGGER.info("Received message on topic " + receivedTopic + ": " + message + " (retained: " + publish.isRetain() + ")");

                        // Add to pending delivery queue
                        synchronized (lastMessages) {
                            lastMessages.put(receivedTopic, message);
                        }

                        // Cache message for future subscribers
                        cachedMessages.put(receivedTopic, message);
                    })
                    .send()
                    .whenComplete((subAck, throwable) -> {
                        if (throwable == null) {
                            subscribedMqttTopics.add(topic);
                        } else {
                            MineQTT.LOGGER.error("Failed to subscribe to topic: " + topic, throwable);
                        }
                    });
        } else {
            MineQTT.LOGGER.warn("MQTT client not connected, subscription for topic " + topic + " will be handled on connection");
        }
    }

    /**
     * Resubscribe to all active topics.
     * Called when MQTT client connects or reconnects.
     */
    public static void resubscribeAll() {
        Set<String> topicsToResubscribe;
        synchronized (activeTopics) {
            MineQTT.LOGGER.info("Resubscribing to all active topics: " + activeTopics.size());
            topicsToResubscribe = new HashSet<>(activeTopics);
        }
        subscribedMqttTopics.clear();
        for (String topic : topicsToResubscribe) {
            subscribeToMqttTopic(topic);
        }
    }

    // Internal: Unsubscribe from MQTT topic
    private static void unsubscribeFromMqttTopic(String topic) {
        MineQTT.LOGGER.info("Unsubscribing from MQTT topic: " + topic);
        subscribedMqttTopics.remove(topic);
        if (MineQTT.mqttClient != null && MineQTT.mqttClient.getState().isConnected()) {
            MineQTT.mqttClient.unsubscribeWith()
                    .topicFilter(topic)
                    .send();
        } else {
            MineQTT.LOGGER.warn("MQTT client not connected, cannot unsubscribe from topic: " + topic);
        }
    }

    public static void onServerPreTick(MinecraftServer server) {
        if (server == null) return;

        // Create a copy of the entries to avoid ConcurrentModificationException
        Map<String, String> messagesToProcess;
        synchronized (lastMessages) {
            if (lastMessages.isEmpty()) return;
            messagesToProcess = new HashMap<>(lastMessages);
            // Clear all processed messages after copying
            lastMessages.clear();
        }

        for (Map.Entry<String, String> messageEntry : messagesToProcess.entrySet()) {
            String topic = messageEntry.getKey();
            String message = messageEntry.getValue();

            Set<ICallbackTarget> subscribers = topicSubscribers.get(topic);
            if (subscribers == null) continue;

            // Create a copy of subscribers to avoid ConcurrentModificationException
            Set<ICallbackTarget> subscribersCopy;
            synchronized (subscribers) {
                subscribersCopy = new HashSet<>(subscribers);
            }

            for (ICallbackTarget target : subscribersCopy) {
                if (target == null || target.getPosition() == null) continue;

                // Get the dimension for this target
                var level = server.getLevel(target.getDimension());
                if (level == null) continue;

                // Verify the block entity still exists and call the callback
                var blockEntity = level.getBlockEntity(target.getPosition());
                if (blockEntity instanceof ICallbackTarget) {
                    target.onMessageReceived(topic, message);
                } else {
                    // Clean up stale subscriber if block entity is gone
                    subscribers.remove(target);
                }
            }
        }
    }
}

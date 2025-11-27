package art.rehra.mineqtt.mqtt;

import art.rehra.mineqtt.MineQTT;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.*;

public class SubscriptionManager {

    // Map topic -> set of ICallbackTarget subscribers
    private static final Map<String, Set<ICallbackTarget>> topicSubscribers = new HashMap<>();

    // Tracks which topics are currently subscribed at the MQTT client level
    private static final Set<String> activeTopics = new HashSet<>();

    // Map topic -> last received message (pending delivery)
    private static final Map<String, String> lastMessages = new HashMap<>();

    // Map topic -> cached last message (for immediate delivery to new subscribers)
    private static final Map<String, String> cachedMessages = new HashMap<>();

    // Persisted data loaded at startup
    private static SubscriptionPersistence.PersistedData persistedData;

    // Save directory for persistence
    private static Path saveDirectory;

    // Initialization method to be called from main Mod file
    public static void init() {
        topicSubscribers.clear();
        activeTopics.clear();
        lastMessages.clear();
        cachedMessages.clear();
        persistedData = null;
    }

    /**
     * Load persisted subscription data from disk.
     * Called when server starts.
     */
    public static void loadPersistedData(Path worldSaveDir) {
        saveDirectory = worldSaveDir;
        persistedData = SubscriptionPersistence.load(worldSaveDir);

        // Restore cached messages
        if (persistedData != null && persistedData.lastMessages != null) {
            cachedMessages.putAll(persistedData.lastMessages);
            MineQTT.LOGGER.info("Restored " + cachedMessages.size() + " cached messages from persistence");
        }
    }

    /**
     * Save subscription data to disk.
     * Called when server stops or world unloads.
     */
    public static void savePersistedData() {
        if (saveDirectory != null) {
            SubscriptionPersistence.save(saveDirectory, topicSubscribers, cachedMessages);
        }
    }


    // Subscribe a ICallbackTarget to a topic
    public static void subscribe(String topic, ICallbackTarget callbackTarget) {
        topicSubscribers.computeIfAbsent(topic, k -> new HashSet<>()).add(callbackTarget);

        // Deliver cached message immediately if available
        String cachedMessage = cachedMessages.get(topic);
        if (cachedMessage != null) {
            callbackTarget.onMessageReceived(topic, cachedMessage);
            MineQTT.LOGGER.info("Delivered cached message to subscriber for topic: " + topic);
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
                        String receivedTopic = publish.getTopic().toString();
                        String message = new String(publish.getPayloadAsBytes());
                        MineQTT.LOGGER.info("Received message on topic " + receivedTopic + ": " + message + " (retained: " + publish.isRetain() + ")");

                        // Add to pending delivery queue
                        lastMessages.put(receivedTopic, message);

                        // Cache message for future subscribers
                        cachedMessages.put(receivedTopic, message);
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
        if (server == null) return;

        // Create a copy of the entries to avoid ConcurrentModificationException
        Map<String, String> messagesToProcess = new HashMap<>(lastMessages);

        for (Map.Entry<String, String> messageEntry : messagesToProcess.entrySet()) {
            String topic = messageEntry.getKey();
            String message = messageEntry.getValue();

            Set<ICallbackTarget> subscribers = topicSubscribers.get(topic);
            if (subscribers == null) continue;

            // Create a copy of subscribers to avoid ConcurrentModificationException
            Set<ICallbackTarget> subscribersCopy = new HashSet<>(subscribers);

            for (ICallbackTarget target : subscribersCopy) {
                if (target == null || target.getPosition() == null) continue;

                // Get the dimension for this target
                var level = server.getLevel(target.getDimension());
                if (level == null) continue;

                // Verify the block entity still exists and call the callback
                var blockEntity = level.getBlockEntity(target.getPosition());
                if (blockEntity instanceof ICallbackTarget) {
                    target.onMessageReceived(topic, message);
                }
            }
        }

        // Clear all processed messages after delivery
        lastMessages.clear();
    }
}

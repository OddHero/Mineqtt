package art.rehra.mineqtt.mqtt.homeassistant;

import art.rehra.mineqtt.MineQTT;
import com.hivemq.client.mqtt.datatypes.MqttQos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Home Assistant MQTT discovery for all MineQTT devices.
 * Handles automatic device registration and cleanup when devices are added/removed.
 *
 * Multiple blocks can share the same topic and appear as one device in HA.
 * Discovery messages are only removed when ALL blocks using a topic are removed.
 */
public class HomeAssistantDiscoveryManager {

    // Map: topic -> set of block positions using this topic
    private static final Map<String, Set<String>> topicToBlockPositions = new ConcurrentHashMap<>();

    // Map: topic -> discovery configuration for this device
    private static final Map<String, HomeAssistantDevice> registeredDevices = new ConcurrentHashMap<>();

    // Discovery prefix (default: homeassistant)
    private static String discoveryPrefix = "homeassistant";

    /**
     * Initialize the discovery manager.
     */
    public static void init() {
        topicToBlockPositions.clear();
        registeredDevices.clear();
        MineQTT.LOGGER.info("Home Assistant Discovery Manager initialized");
    }

    /**
     * Set the discovery prefix (default: homeassistant).
     */
    public static void setDiscoveryPrefix(String prefix) {
        discoveryPrefix = prefix;
    }

    /**
     * Register a device for Home Assistant discovery.
     * If this is the first block using this topic, publish discovery.
     * If other blocks already use this topic, just track it.
     *
     * @param topic The MQTT topic this device uses
     * @param blockPosition Unique identifier for the block (e.g., "dimension:x:y:z")
     * @param device The device configuration
     */
    public static void registerDevice(String topic, String blockPosition, HomeAssistantDevice device) {
        if (topic == null || topic.isEmpty() || blockPosition == null) {
            return;
        }

        // Get or create the set of blocks for this topic
        Set<String> blockPositions = topicToBlockPositions.computeIfAbsent(topic, k -> ConcurrentHashMap.newKeySet());
        boolean isFirstBlock = blockPositions.isEmpty();

        blockPositions.add(blockPosition);
        registeredDevices.put(topic, device);

        // Only publish discovery if this is the first block for this topic
        if (isFirstBlock) {
            MineQTT.LOGGER.info("First block for topic '" + topic + "' - publishing discovery");
            publishDiscovery(topic, device);
        } else {
            MineQTT.LOGGER.info("Block " + blockPosition + " joined existing device on topic: " + topic + " (total: " + blockPositions.size() + " blocks)");
        }
    }

    /**
     * Unregister a device/block.
     * If this is the last block using this topic, remove from Home Assistant.
     *
     * @param topic The MQTT topic
     * @param blockPosition Unique identifier for the block
     */
    public static void unregisterDevice(String topic, String blockPosition) {
        if (topic == null || topic.isEmpty() || blockPosition == null) {
            return;
        }

        Set<String> blockPositions = topicToBlockPositions.get(topic);
        if (blockPositions == null) {
            MineQTT.LOGGER.debug("Attempted to unregister block " + blockPosition + " from topic '" + topic + "' but no blocks registered for this topic");
            return;
        }

        blockPositions.remove(blockPosition);
        MineQTT.LOGGER.info("Unregistered block " + blockPosition + " from topic '" + topic + "' (remaining: " + blockPositions.size() + " blocks)");

        // If this was the last block using this topic, remove from HA
        if (blockPositions.isEmpty()) {
            topicToBlockPositions.remove(topic);
            HomeAssistantDevice device = registeredDevices.remove(topic);

            if (device != null) {
                MineQTT.LOGGER.info("Last block removed for topic '" + topic + "' - removing from Home Assistant");
                removeDiscovery(topic, device);
            }
        }
    }

    /**
     * Update a device's topic (e.g., when player changes the topic in GUI).
     * Unregisters from old topic and registers to new topic.
     *
     * @param oldTopic Previous topic (can be null/empty)
     * @param newTopic New topic (can be null/empty)
     * @param blockPosition Unique identifier for the block
     * @param device The device configuration (uses newTopic)
     */
    public static void updateDeviceTopic(String oldTopic, String newTopic, String blockPosition, HomeAssistantDevice device) {
        // Unregister from old topic if it exists
        if (oldTopic != null && !oldTopic.isEmpty()) {
            unregisterDevice(oldTopic, blockPosition);
        }

        // Register to new topic if it exists
        if (newTopic != null && !newTopic.isEmpty()) {
            registerDevice(newTopic, blockPosition, device);
        }
    }

    /**
     * Publish Home Assistant discovery message.
     */
    private static void publishDiscovery(String topic, HomeAssistantDevice device) {
        if (MineQTT.mqttClient == null || !MineQTT.mqttClient.getState().isConnected()) {
            MineQTT.LOGGER.warn("Cannot publish discovery - MQTT not connected");
            return;
        }

        String discoveryTopic = device.getDiscoveryTopic(discoveryPrefix);
        String payload = device.toJson();

        try {
            MineQTT.mqttClient.publishWith()
                .topic(discoveryTopic)
                .payload(payload.getBytes())
                .retain(true)
                .qos(MqttQos.AT_LEAST_ONCE)
                .send();

            MineQTT.LOGGER.info("Published Home Assistant discovery: " + discoveryTopic);
        } catch (Exception e) {
            MineQTT.LOGGER.error("Failed to publish discovery for topic: " + topic, e);
        }
    }

    /**
     * Remove Home Assistant discovery message.
     */
    private static void removeDiscovery(String topic, HomeAssistantDevice device) {
        if (MineQTT.mqttClient == null || !MineQTT.mqttClient.getState().isConnected()) {
            MineQTT.LOGGER.warn("Cannot remove discovery - MQTT not connected");
            return;
        }

        String discoveryTopic = device.getDiscoveryTopic(discoveryPrefix);

        try {
            // Publish empty retained message to remove
            MineQTT.mqttClient.publishWith()
                .topic(discoveryTopic)
                .payload(new byte[0])
                .retain(true)
                .qos(MqttQos.AT_LEAST_ONCE)
                .send()
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        MineQTT.LOGGER.error("Failed to remove discovery for topic: " + topic, throwable);
                    } else {
                        MineQTT.LOGGER.info("Successfully removed Home Assistant discovery: " + discoveryTopic + " (last block removed)");
                    }
                });

        } catch (Exception e) {
            MineQTT.LOGGER.error("Failed to remove discovery for topic: " + topic, e);
        }
    }

    /**
     * Get the number of blocks currently using a topic.
     */
    public static int getBlockCount(String topic) {
        Set<String> blocks = topicToBlockPositions.get(topic);
        return blocks != null ? blocks.size() : 0;
    }

    /**
     * Check if a topic is currently registered.
     */
    public static boolean isTopicRegistered(String topic) {
        return registeredDevices.containsKey(topic);
    }

    /**
     * Get all currently registered topics.
     */
    public static Set<String> getRegisteredTopics() {
        return new HashSet<>(registeredDevices.keySet());
    }

    /**
     * Extract the suggested area name from a block position ID.
     * Block position format: "minecraft:overworld:[100, 64, -200]"
     *
     * @param blockPosition Block position identifier
     * @return Suggested area name (e.g., "Overworld", "Nether", "End")
     */
    public static String getSuggestedAreaFromBlockPosition(String blockPosition) {
        if (blockPosition == null || blockPosition.isEmpty()) {
            return "Minecraft";
        }

        // Extract dimension from block position (format: "dimension:coordinates")
        String dimension;
        if (blockPosition.contains(":")) {
            dimension = blockPosition.substring(0, blockPosition.indexOf(":"));
        } else {
            return "Minecraft";
        }

        // Map dimension IDs to friendly area names
        if (dimension.contains("overworld")) {
            return "Overworld";
        } else if (dimension.contains("nether") || dimension.contains("the_nether")) {
            return "Nether";
        } else if (dimension.contains("end") || dimension.contains("the_end")) {
            return "End";
        } else {
            // For custom dimensions, try to extract the name
            String[] parts = dimension.split("[:/]");
            if (parts.length > 0) {
                String lastPart = parts[parts.length - 1];
                // Capitalize first letter
                return lastPart.substring(0, 1).toUpperCase() + lastPart.substring(1);
            }
            return "Minecraft";
        }
    }
}


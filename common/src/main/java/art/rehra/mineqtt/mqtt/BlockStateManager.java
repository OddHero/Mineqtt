package art.rehra.mineqtt.mqtt;

import art.rehra.mineqtt.MineQTT;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Manages the state of MQTT topics (not individual blocks).
 * All blocks on the same topic share the same state.
 */
public class BlockStateManager {

    // Map: topic -> state (ONE state per topic, shared by all blocks)
    private static final Map<String, BlockStatePersistence.TopicState> topicStates = new HashMap<>();

    // Map: topic -> set of block positions using this topic
    private static final Map<String, Set<String>> topicBlocks = new HashMap<>();

    private static Path saveDirectory;

    public static void init() {
        topicStates.clear();
        topicBlocks.clear();
    }

    /**
     * Load persisted topic states.
     */
    public static void loadPersistedData(Path worldSaveDir) {
        saveDirectory = worldSaveDir;
        BlockStatePersistence.PersistedData data = BlockStatePersistence.load(worldSaveDir);

        if (data != null && data.topicStates != null) {
            topicStates.putAll(data.topicStates);

            // Rebuild topicBlocks from loaded data
            for (Map.Entry<String, BlockStatePersistence.TopicState> entry : topicStates.entrySet()) {
                String topic = entry.getKey();
                BlockStatePersistence.TopicState state = entry.getValue();
                if (state.blockPositions != null) {
                    topicBlocks.put(topic, new HashSet<>(state.blockPositions));
                }
            }

            MineQTT.LOGGER.info("Restored " + topicStates.size() + " topic states from persistence");
        }
    }

    /**
     * Save topic states to disk.
     */
    public static void savePersistedData() {
        if (saveDirectory != null) {
            // Update block position lists in states before saving
            for (Map.Entry<String, Set<String>> entry : topicBlocks.entrySet()) {
                String topic = entry.getKey();
                BlockStatePersistence.TopicState state = topicStates.get(topic);
                if (state != null) {
                    state.blockPositions = new java.util.ArrayList<>(entry.getValue());
                }
            }

            BlockStatePersistence.save(saveDirectory, topicStates);
        }
    }

    /**
     * Update topic state. All blocks on this topic share this state.
     */
    public static void updateTopicState(String topic, int targetRed, int targetGreen, int targetBlue,
                                       int brightness, boolean lit) {
        BlockStatePersistence.TopicState state = new BlockStatePersistence.TopicState(
            targetRed, targetGreen, targetBlue, brightness, lit
        );
        topicStates.put(topic, state);
    }

    /**
     * Register a block as using a topic.
     */
    public static void registerBlock(String topic, ResourceKey<Level> dimension, BlockPos pos) {
        String blockKey = BlockStatePersistence.makeBlockPositionKey(dimension, pos);
        topicBlocks.computeIfAbsent(topic, k -> new HashSet<>()).add(blockKey);
    }

    /**
     * Unregister a block from a topic.
     */
    public static void unregisterBlock(String topic, ResourceKey<Level> dimension, BlockPos pos) {
        String blockKey = BlockStatePersistence.makeBlockPositionKey(dimension, pos);
        Set<String> blocks = topicBlocks.get(topic);
        if (blocks != null) {
            blocks.remove(blockKey);
            if (blocks.isEmpty()) {
                topicBlocks.remove(topic);
                topicStates.remove(topic);
            }
        }
    }

    /**
     * Get the state for a topic.
     */
    public static BlockStatePersistence.TopicState getTopicState(String topic) {
        return topicStates.get(topic);
    }
}


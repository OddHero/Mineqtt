package art.rehra.mineqtt.mqtt;

import art.rehra.mineqtt.MineQTT;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages the state of all MQTT-controlled blocks.
 * Persists complete block states (not just last message).
 */
public class BlockStateManager {

    private static final Map<String, BlockStatePersistence.BlockState> blockStates = new HashMap<>();
    private static Path saveDirectory;

    public static void init() {
        blockStates.clear();
    }

    /**
     * Load persisted block states.
     */
    public static void loadPersistedData(Path worldSaveDir) {
        saveDirectory = worldSaveDir;
        BlockStatePersistence.PersistedData data = BlockStatePersistence.load(worldSaveDir);

        if (data != null && data.blockStates != null) {
            blockStates.putAll(data.blockStates);
            MineQTT.LOGGER.info("Restored " + blockStates.size() + " block states from persistence");
        }
    }

    /**
     * Save block states to disk.
     */
    public static void savePersistedData() {
        if (saveDirectory != null) {
            BlockStatePersistence.save(saveDirectory, blockStates);
        }
    }

    /**
     * Update or create a block state.
     */
    public static void updateBlockState(ResourceKey<Level> dimension, BlockPos pos,
                                       String topic, int red, int green, int blue,
                                       int targetRed, int targetGreen, int targetBlue,
                                       int brightness, boolean lit) {
        String key = BlockStatePersistence.makeBlockPositionKey(dimension, pos);
        BlockStatePersistence.BlockState state = new BlockStatePersistence.BlockState(
            topic, red, green, blue, targetRed, targetGreen, targetBlue, brightness, lit
        );
        blockStates.put(key, state);
    }

    /**
     * Get a block's saved state.
     */
    public static BlockStatePersistence.BlockState getBlockState(ResourceKey<Level> dimension, BlockPos pos) {
        String key = BlockStatePersistence.makeBlockPositionKey(dimension, pos);
        return blockStates.get(key);
    }

    /**
     * Get any saved state for a topic (from any block using that topic).
     * Used when a new block subscribes to get the current state.
     */
    public static BlockStatePersistence.BlockState getStateForTopic(String topic) {
        // Find any block state that uses this topic
        for (BlockStatePersistence.BlockState state : blockStates.values()) {
            if (state.topic != null && state.topic.equals(topic)) {
                return state;
            }
        }
        return null;
    }

    /**
     * Remove a block state (when block is destroyed).
     */
    public static void removeBlockState(ResourceKey<Level> dimension, BlockPos pos) {
        String key = BlockStatePersistence.makeBlockPositionKey(dimension, pos);
        blockStates.remove(key);
    }
}


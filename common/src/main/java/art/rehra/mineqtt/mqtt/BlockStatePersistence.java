package art.rehra.mineqtt.mqtt;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Persists MQTT topic states (not individual block states).
 * All blocks on the same topic share the same state.
 */
public class BlockStatePersistence {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Represents the state of a topic (shared by all blocks).
     */
    public static class TopicState {
        public int targetRed;
        public int targetGreen;
        public int targetBlue;
        public int brightness;
        public boolean lit;
        public List<String> blockPositions = new ArrayList<>();

        public TopicState() {}

        public TopicState(int targetRed, int targetGreen, int targetBlue, int brightness, boolean lit) {
            this.targetRed = targetRed;
            this.targetGreen = targetGreen;
            this.targetBlue = targetBlue;
            this.brightness = brightness;
            this.lit = lit;
        }
    }

    public static class PersistedData {
        // Map: topic -> state (shared by all blocks on that topic)
        public Map<String, TopicState> topicStates = new HashMap<>();

        public PersistedData() {}
    }

    /**
     * Save topic states to disk.
     */
    public static void save(Path saveDir, Map<String, TopicState> topicStates) {
        try {
            Files.createDirectories(saveDir);
            Path dataFile = saveDir.resolve("mineqtt_topic_states.json");

            PersistedData data = new PersistedData();
            data.topicStates.putAll(topicStates);

            // Write to file
            try (Writer writer = new FileWriter(dataFile.toFile())) {
                GSON.toJson(data, writer);
            }

            art.rehra.mineqtt.MineQTT.LOGGER.info("Saved topic states: " + data.topicStates.size() + " topics");
        } catch (IOException e) {
            art.rehra.mineqtt.MineQTT.LOGGER.error("Failed to save topic states", e);
        }
    }

    /**
     * Load topic states from disk.
     */
    public static PersistedData load(Path saveDir) {
        Path dataFile = saveDir.resolve("mineqtt_topic_states.json");

        if (!Files.exists(dataFile)) {
            art.rehra.mineqtt.MineQTT.LOGGER.info("No saved topic states found");
            return new PersistedData();
        }

        try (Reader reader = new FileReader(dataFile.toFile())) {
            Type type = new TypeToken<PersistedData>(){}.getType();
            PersistedData data = GSON.fromJson(reader, type);

            if (data == null) {
                data = new PersistedData();
            }

            art.rehra.mineqtt.MineQTT.LOGGER.info("Loaded topic states: " + data.topicStates.size() + " topics");
            return data;
        } catch (IOException e) {
            art.rehra.mineqtt.MineQTT.LOGGER.error("Failed to load topic states", e);
            return new PersistedData();
        }
    }

    /**
     * Generate block position string for tracking.
     */
    public static String makeBlockPositionKey(ResourceKey<Level> dimension, BlockPos pos) {
        return dimension.location() + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
    }
}


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
 * Persists block entity states (not just last messages).
 * Each block position stores its complete state including color, brightness, etc.
 */
public class BlockStatePersistence {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Represents the complete state of a block entity.
     */
    public static class BlockState {
        public String topic;
        public int red;
        public int green;
        public int blue;
        public int targetRed;
        public int targetGreen;
        public int targetBlue;
        public int brightness;
        public boolean lit;

        public BlockState() {}

        public BlockState(String topic, int red, int green, int blue,
                         int targetRed, int targetGreen, int targetBlue,
                         int brightness, boolean lit) {
            this.topic = topic;
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.targetRed = targetRed;
            this.targetGreen = targetGreen;
            this.targetBlue = targetBlue;
            this.brightness = brightness;
            this.lit = lit;
        }
    }

    public static class PersistedData {
        // Map: blockPosition -> state
        public Map<String, BlockState> blockStates = new HashMap<>();

        public PersistedData() {}
    }

    /**
     * Save block states to disk.
     */
    public static void save(Path saveDir, Map<String, BlockState> blockStates) {
        try {
            Files.createDirectories(saveDir);
            Path dataFile = saveDir.resolve("mineqtt_block_states.json");

            PersistedData data = new PersistedData();
            data.blockStates.putAll(blockStates);

            // Write to file
            try (Writer writer = new FileWriter(dataFile.toFile())) {
                GSON.toJson(data, writer);
            }

            art.rehra.mineqtt.MineQTT.LOGGER.info("Saved block states: " + data.blockStates.size() + " blocks");
        } catch (IOException e) {
            art.rehra.mineqtt.MineQTT.LOGGER.error("Failed to save block states", e);
        }
    }

    /**
     * Load block states from disk.
     */
    public static PersistedData load(Path saveDir) {
        Path dataFile = saveDir.resolve("mineqtt_block_states.json");

        if (!Files.exists(dataFile)) {
            art.rehra.mineqtt.MineQTT.LOGGER.info("No saved block states found");
            return new PersistedData();
        }

        try (Reader reader = new FileReader(dataFile.toFile())) {
            Type type = new TypeToken<PersistedData>(){}.getType();
            PersistedData data = GSON.fromJson(reader, type);

            if (data == null) {
                data = new PersistedData();
            }

            art.rehra.mineqtt.MineQTT.LOGGER.info("Loaded block states: " + data.blockStates.size() + " blocks");
            return data;
        } catch (IOException e) {
            art.rehra.mineqtt.MineQTT.LOGGER.error("Failed to load block states", e);
            return new PersistedData();
        }
    }

    /**
     * Generate block position string for persistence key.
     */
    public static String makeBlockPositionKey(ResourceKey<Level> dimension, BlockPos pos) {
        return dimension.location() + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
    }
}


package art.rehra.mineqtt.mqtt;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Reads the per-topic RGB LED states MineQTT 1 kept in {@code mineqtt_topic_states.json}: one state per topic, shared
 * by all lamps on it. MineQTT 2 imports the file once and never writes it (design §9.2).
 */
public class BlockStatePersistence {

    public static final String FILE_NAME = "mineqtt_topic_states.json";
    private static final Gson GSON = new Gson();

    /**
     * Represents the state of a topic (shared by all blocks). Colours are 0-15 per channel, brightness 0-255.
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
     * Load topic states from disk. v1 wrote the file in the platform charset, so it is read in it too.
     *
     * @return the states, empty ones when there is no file; empty when the file cannot be read
     */
    public static Optional<PersistedData> load(Path saveDir) {
        Path dataFile = saveDir.resolve(FILE_NAME);

        if (!Files.exists(dataFile)) {
            return Optional.of(new PersistedData());
        }

        try (Reader reader = new FileReader(dataFile.toFile())) {
            PersistedData data = GSON.fromJson(reader, PersistedData.class);
            if (data == null) data = new PersistedData();
            if (data.topicStates == null) data.topicStates = new HashMap<>();
            art.rehra.mineqtt.MineQTT.LOGGER.info("Loaded v1 topic states: " + data.topicStates.size() + " topics");
            return Optional.of(data);
        } catch (IOException | JsonParseException e) {
            art.rehra.mineqtt.MineQTT.LOGGER.error("Failed to load the v1 topic states from " + dataFile, e);
            return Optional.empty();
        }
    }

    /**
     * Parses a block position as v1 wrote it, {@code <dimension>:<x>:<y>:<z>} (the dimension id has a colon itself).
     */
    public static Optional<GlobalPos> parseBlockPositionKey(String key) {
        int z = key.lastIndexOf(':');
        int y = z <= 0 ? -1 : key.lastIndexOf(':', z - 1);
        int x = y <= 0 ? -1 : key.lastIndexOf(':', y - 1);
        if (x <= 0) return Optional.empty();
        Identifier dimension = Identifier.tryParse(key.substring(0, x));
        if (dimension == null) return Optional.empty();
        try {
            BlockPos pos = new BlockPos(Integer.parseInt(key.substring(x + 1, y)), Integer.parseInt(key.substring(y + 1, z)),
                    Integer.parseInt(key.substring(z + 1)));
            return Optional.of(GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dimension), pos));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}

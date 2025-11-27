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
 * Persists MQTT subscription data to disk, including:
 * - Which blocks are subscribed to which topics
 * - The last message received on each topic
 *
 * Data is saved on server shutdown and restored on startup.
 */
public class SubscriptionPersistence {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static class PersistedData {
        // Map: topic -> last message
        public Map<String, String> lastMessages = new HashMap<>();

        // Map: topic -> list of block positions (as strings)
        public Map<String, List<String>> topicSubscribers = new HashMap<>();

        public PersistedData() {}
    }

    /**
     * Save subscription data to disk.
     *
     * @param saveDir Directory to save the data file
     * @param topicSubscribers Current subscribers (topic -> set of ICallbackTarget)
     * @param lastMessages Last messages per topic
     */
    public static void save(Path saveDir, Map<String, Set<ICallbackTarget>> topicSubscribers, Map<String, String> lastMessages) {
        try {
            Files.createDirectories(saveDir);
            Path dataFile = saveDir.resolve("mineqtt_subscriptions.json");

            PersistedData data = new PersistedData();

            // Save last messages for topics that still have subscribers
            for (Map.Entry<String, Set<ICallbackTarget>> entry : topicSubscribers.entrySet()) {
                String topic = entry.getKey();
                Set<ICallbackTarget> subscribers = entry.getValue();

                if (subscribers != null && !subscribers.isEmpty()) {
                    // Save last message for this topic
                    String message = lastMessages.get(topic);
                    if (message != null) {
                        data.lastMessages.put(topic, message);
                    }

                    // Save subscriber positions
                    List<String> positions = new ArrayList<>();
                    for (ICallbackTarget target : subscribers) {
                        if (target != null && target.getPosition() != null && target.getDimension() != null) {
                            String posString = serializeBlockPosition(target.getDimension(), target.getPosition());
                            positions.add(posString);
                        }
                    }
                    if (!positions.isEmpty()) {
                        data.topicSubscribers.put(topic, positions);
                    }
                }
            }

            // Write to file
            try (Writer writer = new FileWriter(dataFile.toFile())) {
                GSON.toJson(data, writer);
            }

            art.rehra.mineqtt.MineQTT.LOGGER.info("Saved MQTT subscription data: " + data.topicSubscribers.size() + " topics, " + data.lastMessages.size() + " messages");
        } catch (IOException e) {
            art.rehra.mineqtt.MineQTT.LOGGER.error("Failed to save MQTT subscription data", e);
        }
    }

    /**
     * Load subscription data from disk.
     *
     * @param saveDir Directory containing the data file
     * @return Loaded data, or empty data if file doesn't exist
     */
    public static PersistedData load(Path saveDir) {
        Path dataFile = saveDir.resolve("mineqtt_subscriptions.json");

        if (!Files.exists(dataFile)) {
            art.rehra.mineqtt.MineQTT.LOGGER.info("No saved MQTT subscription data found");
            return new PersistedData();
        }

        try (Reader reader = new FileReader(dataFile.toFile())) {
            Type type = new TypeToken<PersistedData>(){}.getType();
            PersistedData data = GSON.fromJson(reader, type);

            if (data == null) {
                data = new PersistedData();
            }

            art.rehra.mineqtt.MineQTT.LOGGER.info("Loaded MQTT subscription data: " + data.topicSubscribers.size() + " topics, " + data.lastMessages.size() + " messages");
            return data;
        } catch (IOException e) {
            art.rehra.mineqtt.MineQTT.LOGGER.error("Failed to load MQTT subscription data", e);
            return new PersistedData();
        }
    }

    /**
     * Serialize a block position to a string.
     * Format: "dimension:x:y:z"
     */
    private static String serializeBlockPosition(ResourceKey<Level> dimension, BlockPos pos) {
        return dimension.location() + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
    }

    /**
     * Check if a persisted block position matches a callback target.
     */
    public static boolean positionMatches(String persistedPosition, ICallbackTarget target) {
        if (target == null || target.getPosition() == null || target.getDimension() == null) {
            return false;
        }

        String currentPosition = serializeBlockPosition(target.getDimension(), target.getPosition());
        return persistedPosition.equals(currentPosition);
    }
}


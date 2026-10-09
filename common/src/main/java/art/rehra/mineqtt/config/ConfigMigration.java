package art.rehra.mineqtt.config;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Migrates a v1 config (no {@code configVersion}, but v1-only keys) to schema v2 (design §9.6). Both loaders read their v1 file into a
 * {@link V1} and let {@link #applyV1} fill {@link MineQTTConfig}; each then writes the v2 file in its own format.
 */
public final class ConfigMigration {
    /** v1's built-in broker. Treated as an unset broker, so no migrated server keeps talking to a public broker. */
    static final String V1_PUBLIC_BROKER = "test.mosquitto.org";
    /** The v1 base topic default of the loader config files; the in-code default was {@code minecraft-<clientId>}. */
    static final String V1_BASE_TOPIC = "minecraft";
    static final String V1_STATUS_TOPIC = "status";

    private ConfigMigration() {
    }

    /**
     * The v1 keys that carry over. A null component was absent from the v1 file. {@code autoReconnect},
     * {@code connectionTimeout}, the zombie goal and item portal keys (their features are gone) and the other v1 keys
     * have no v2 counterpart and are not read.
     */
    public record V1(String brokerUrl, String clientId, String username, String password, Integer keepAlive,
                     String baseTopic, String statusTopic) {
    }

    /**
     * Copies a v1 file to {@code backup} before it is rewritten as v2. An existing backup is kept, because it holds
     * the original of an earlier migration.
     *
     * @return false when an earlier backup was kept
     * @throws IOException when the copy fails; the v1 file must then stay as it is
     */
    public static boolean backUpV1(Path file, Path backup) throws IOException {
        try {
            Files.copy(file, backup);
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        }
    }

    /**
     * Resets {@link MineQTTConfig} to the v2 defaults and applies the v1 values.
     *
     * @return log lines describing what changed; they never contain the password or the username
     */
    public static List<String> applyV1(V1 v1) {
        MineQTTConfig.resetToDefaults();
        List<String> notes = new ArrayList<>();

        String brokerUrl = v1.brokerUrl() == null ? "" : v1.brokerUrl().strip();
        if (brokerUrl.toLowerCase(Locale.ROOT).equals(V1_PUBLIC_BROKER)) {
            notes.add("brokerUrl: the old public default " + V1_PUBLIC_BROKER + " is not carried over, MQTT stays disabled until "
                    + "broker is set (set broker to " + V1_PUBLIC_BROKER + " explicitly to keep using it)");
        } else {
            MineQTTConfig.broker = brokerUrl;
            if (!brokerUrl.isEmpty()) {
                notes.add(ConfigValidation.loggableBroker(brokerUrl).map(uri -> "brokerUrl -> broker: " + uri)
                        .orElse("brokerUrl -> broker: kept as written, but it is not a valid broker URI"));
            }
        }

        if (v1.username() != null) MineQTTConfig.username = v1.username();
        if (v1.password() != null) MineQTTConfig.password = v1.password();
        if (v1.keepAlive() != null && ConfigValidation.isValidKeepAlive(v1.keepAlive())) {
            MineQTTConfig.keepAliveSec = v1.keepAlive();
        }

        String v1Base = v1.baseTopic() == null ? V1_BASE_TOPIC : v1.baseTopic().strip();
        boolean defaultBase = v1Base.isEmpty() || v1Base.equals(V1_BASE_TOPIC)
                || (v1.clientId() != null && v1Base.equals(V1_BASE_TOPIC + "-" + v1.clientId()));
        if (!defaultBase) {
            if (ConfigValidation.isValidConfigTopic(v1Base)) {
                MineQTTConfig.baseTopic = v1Base;
                notes.add("baseTopic: the customised value '" + v1Base + "' is kept");
            } else {
                notes.add("baseTopic: '" + v1Base + "' is not a valid v2 base topic, using '" + StaticDefaults.DEFAULT_BASE_TOPIC + "'");
            }
        }
        String v1Status = v1.statusTopic() == null || v1.statusTopic().isBlank() ? V1_STATUS_TOPIC : v1.statusTopic().strip();
        String v1StatusTopic = v1Base + "/" + v1Status;
        if (ConfigValidation.isValidConfigTopic(v1StatusTopic)) MineQTTConfig.legacyV1StatusTopic = v1StatusTopic;

        if (v1.clientId() != null) notes.add("clientId is no longer used: the client id is now mineqtt_<bridgeId>");
        return notes;
    }
}

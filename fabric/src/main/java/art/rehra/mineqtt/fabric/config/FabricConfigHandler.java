package art.rehra.mineqtt.fabric.config;

import art.rehra.mineqtt.config.ConfigHandler;
import art.rehra.mineqtt.config.ConfigMigration;
import art.rehra.mineqtt.config.ConfigValidation;
import art.rehra.mineqtt.config.JsonConfigFormat;
import art.rehra.mineqtt.config.MineQTTConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FabricConfigHandler implements ConfigHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("MineQTT-Config");
    private static final String CONFIG_FILE_NAME = "mineqtt.json";
    private static final String V1_BACKUP_SUFFIX = ".v1.bak";
    private final Path configPath;
    private final Gson gson;
    /** The file could not be loaded or backed up; saving would overwrite what the user has to repair. */
    private boolean fileProtected;

    public FabricConfigHandler() {
        this.configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
        this.gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    }

    @Override
    public void loadConfig() {
        // The file is left untouched while it cannot be loaded, so the user can repair it; nothing is saved until they do.
        fileProtected = true;
        if (!Files.exists(configPath)) {
            LOGGER.info("Configuration file not found, creating default configuration");
            MineQTTConfig.resetToDefaults();
            fileProtected = false;
            saveConfig();
            return;
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(Files.readString(configPath));
        } catch (Exception e) {
            LOGGER.error("Failed to read configuration {}, using defaults until it is fixed: {}", configPath, e.getMessage());
            MineQTTConfig.resetToDefaults();
            return;
        }
        if (!root.isJsonObject()) {
            LOGGER.error("Configuration {} is not a JSON object, using defaults until it is fixed", configPath);
            MineQTTConfig.resetToDefaults();
            return;
        }

        JsonConfigFormat.Result result = JsonConfigFormat.read(root.getAsJsonObject());
        if (result.migrated()) {
            Path backup = configPath.resolveSibling(CONFIG_FILE_NAME + V1_BACKUP_SUFFIX);
            try {
                if (ConfigMigration.backUpV1(configPath, backup)) {
                    LOGGER.info("Migrating v1 configuration {} to v2, the original is kept as {}", configPath, backup);
                } else {
                    LOGGER.warn("Migrating v1 configuration {} to v2 without a new backup, {} from an earlier migration is kept", configPath, backup);
                }
            } catch (IOException e) {
                LOGGER.error("Failed to back up v1 configuration {}, it is not migrated or saved this time", configPath, e);
                result.messages().forEach(message -> LOGGER.warn("Config: {}", message));
                return;
            }
        }
        fileProtected = false;
        result.messages().forEach(message -> LOGGER.warn("Config: {}", message));
        if (result.migrated()) saveConfig();
        LOGGER.info("Configuration loaded from {}", configPath);
    }

    @Override
    public void saveConfig() {
        if (fileProtected) {
            LOGGER.error("Configuration {} could not be loaded and is not overwritten; changes are not saved until it is fixed and the game restarted", configPath);
            return;
        }
        try {
            String jsonContent = gson.toJson(JsonConfigFormat.write());
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, jsonContent);
            LOGGER.info("Configuration saved to {}", configPath);
        } catch (IOException e) {
            LOGGER.error("Failed to save configuration", e);
        }
    }

    @Override
    public boolean isConfigValid() {
        return ConfigValidation.isValidBroker(MineQTTConfig.broker);
    }

    @Override
    public void resetToDefaults() {
        MineQTTConfig.resetToDefaults();
        saveConfig();
    }

    @Override
    public Path configPath() {
        return configPath;
    }
}

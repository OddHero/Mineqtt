package art.rehra.mineqtt.fabric.config;

import art.rehra.mineqtt.config.ConfigHandler;
import art.rehra.mineqtt.config.MineQTTConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class FabricConfigHandler implements ConfigHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("MineQTT-Config");
    private static final String CONFIG_FILE_NAME = "mineqtt.json";
    private final Path configPath;
    private final Gson gson;

    public FabricConfigHandler() {
        this.configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    @Override
    public void loadConfig() {
        if (Files.exists(configPath)) {
            try {
                String jsonContent = Files.readString(configPath);
                ConfigData configData = gson.fromJson(jsonContent, ConfigData.class);
                if (configData != null) {
                    applyConfigData(configData);
                    LOGGER.info("Configuration loaded from {}", configPath);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load configuration, using defaults", e);
                resetToDefaults();
            }
        } else {
            LOGGER.info("Configuration file not found, creating default configuration");
            saveConfig();
        }
    }

    @Override
    public void saveConfig() {
        try {
            ConfigData configData = createConfigData();
            String jsonContent = gson.toJson(configData);
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, jsonContent);
            LOGGER.info("Configuration saved to {}", configPath);
        } catch (IOException e) {
            LOGGER.error("Failed to save configuration", e);
        }
    }

    @Override
    public boolean isConfigValid() {
        return MineQTTConfig.brokerUrl != null && !MineQTTConfig.brokerUrl.isEmpty()
                && MineQTTConfig.clientId != null && !MineQTTConfig.clientId.isEmpty();
    }

    @Override
    public void resetToDefaults() {
        MineQTTConfig.resetToDefaults();
        saveConfig();
    }

    private ConfigData createConfigData() {
        ConfigData data = new ConfigData();
        // MQTT Connection Settings
        data.brokerUrl = MineQTTConfig.brokerUrl;
        data.clientId = MineQTTConfig.clientId;
        data.username = MineQTTConfig.username;
        data.password = MineQTTConfig.password;
        data.autoReconnect = MineQTTConfig.autoReconnect;
        data.connectionTimeout = MineQTTConfig.connectionTimeout;
        data.keepAlive = MineQTTConfig.keepAlive;
        data.allowItemNetherPortalTeleport = MineQTTConfig.allowItemNetherPortalTeleport;

        return data;
    }

    private void applyConfigData(ConfigData data) {
        // MQTT Connection Settings
        MineQTTConfig.brokerUrl = data.brokerUrl;
        MineQTTConfig.clientId = data.clientId;
        MineQTTConfig.username = data.username;
        MineQTTConfig.password = data.password;
        MineQTTConfig.autoReconnect = data.autoReconnect;
        MineQTTConfig.connectionTimeout = data.connectionTimeout;
        MineQTTConfig.keepAlive = data.keepAlive;
        MineQTTConfig.allowItemNetherPortalTeleport = data.allowItemNetherPortalTeleport;
    }

    private static class ConfigData {
        // MQTT Connection Settings
        public String brokerUrl = "tcp://localhost:1883";
        public String clientId = "minecraft-client" + UUID.randomUUID();
        public String username = "";
        public String password = "";
        public boolean autoReconnect = true;
        public long connectionTimeout = 30;
        public int keepAlive = 60;
        public boolean allowItemNetherPortalTeleport = true;

        // MQTT Topics
        public String baseTopic = "minecraft";
        public String statusTopic = "status";
    }
}

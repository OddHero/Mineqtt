package art.rehra.mineqtt.neoforge.config;

import art.rehra.mineqtt.config.ConfigHandler;
import art.rehra.mineqtt.config.MineQTTConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class NeoForgeConfigHandler implements ConfigHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("MineQTT-Config");
    private static final String CONFIG_FILE_NAME = "mineqtt.json";
    private final Path configPath;
    private final Gson gson;

    public NeoForgeConfigHandler() {
        this.configPath = FMLPaths.CONFIGDIR.get().resolve(CONFIG_FILE_NAME);
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
                resetConfig();
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
    public void resetConfig() {
        MineQTTConfig.resetToDefaults();
        saveConfig();
        LOGGER.info("Configuration reset to defaults");
    }

    private ConfigData createConfigData() {
        ConfigData configData = new ConfigData();

        // Connection settings
        configData.brokerHost = MineQTTConfig.brokerHost;
        configData.brokerPort = MineQTTConfig.brokerPort;
        configData.username = MineQTTConfig.username;
        configData.password = MineQTTConfig.password;
        configData.useAuthentication = MineQTTConfig.useAuthentication;
        configData.connectionTimeout = MineQTTConfig.connectionTimeout;
        configData.autoReconnect = MineQTTConfig.autoReconnect;

        // Topics
        configData.statusTopic = MineQTTConfig.statusTopic;
        configData.playerEventsTopic = MineQTTConfig.playerEventsTopic;
        configData.chatTopic = MineQTTConfig.chatTopic;
        configData.blockEventsTopic = MineQTTConfig.blockEventsTopic;

        // Publishing settings
        configData.publishPlayerJoin = MineQTTConfig.publishPlayerJoin;
        configData.publishPlayerLeave = MineQTTConfig.publishPlayerLeave;
        configData.publishChatMessages = MineQTTConfig.publishChatMessages;
        configData.publishBlockBreak = MineQTTConfig.publishBlockBreak;
        configData.publishBlockPlace = MineQTTConfig.publishBlockPlace;

        // Message settings
        configData.onlineMessage = MineQTTConfig.onlineMessage;
        configData.offlineMessage = MineQTTConfig.offlineMessage;

        // Debug settings
        configData.enableDebugLogging = MineQTTConfig.enableDebugLogging;
        configData.showMqttStatusInChat = MineQTTConfig.showMqttStatusInChat;

        return configData;
    }

    private void applyConfigData(ConfigData configData) {
        // Connection settings
        MineQTTConfig.brokerHost = configData.brokerHost;
        MineQTTConfig.brokerPort = configData.brokerPort;
        MineQTTConfig.username = configData.username;
        MineQTTConfig.password = configData.password;
        MineQTTConfig.useAuthentication = configData.useAuthentication;
        MineQTTConfig.connectionTimeout = configData.connectionTimeout;
        MineQTTConfig.autoReconnect = configData.autoReconnect;

        // Topics
        MineQTTConfig.statusTopic = configData.statusTopic;
        MineQTTConfig.playerEventsTopic = configData.playerEventsTopic;
        MineQTTConfig.chatTopic = configData.chatTopic;
        MineQTTConfig.blockEventsTopic = configData.blockEventsTopic;

        // Publishing settings
        MineQTTConfig.publishPlayerJoin = configData.publishPlayerJoin;
        MineQTTConfig.publishPlayerLeave = configData.publishPlayerLeave;
        MineQTTConfig.publishChatMessages = configData.publishChatMessages;
        MineQTTConfig.publishBlockBreak = configData.publishBlockBreak;
        MineQTTConfig.publishBlockPlace = configData.publishBlockPlace;

        // Message settings
        MineQTTConfig.onlineMessage = configData.onlineMessage;
        MineQTTConfig.offlineMessage = configData.offlineMessage;

        // Debug settings
        MineQTTConfig.enableDebugLogging = configData.enableDebugLogging;
        MineQTTConfig.showMqttStatusInChat = configData.showMqttStatusInChat;
    }

    private static class ConfigData {
        // Connection settings
        public String brokerHost = "test.mosquitto.org";
        public int brokerPort = 1883;
        public String username = "";
        public String password = "";
        public boolean useAuthentication = false;
        public int connectionTimeout = 10;
        public boolean autoReconnect = true;

        // Topics
        public String statusTopic = "mineqtt/status";
        public String playerEventsTopic = "mineqtt/player/events";
        public String chatTopic = "mineqtt/chat";
        public String blockEventsTopic = "mineqtt/blocks";

        // Publishing settings
        public boolean publishPlayerJoin = true;
        public boolean publishPlayerLeave = true;
        public boolean publishChatMessages = false;
        public boolean publishBlockBreak = false;
        public boolean publishBlockPlace = false;

        // Message settings
        public String onlineMessage = "MineQTT is online";
        public String offlineMessage = "MineQTT is offline";

        // Debug settings
        public boolean enableDebugLogging = false;
        public boolean showMqttStatusInChat = false;
    }
}

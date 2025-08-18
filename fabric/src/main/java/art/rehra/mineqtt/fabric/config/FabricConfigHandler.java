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
    }

    private ConfigData createConfigData() {
        ConfigData data = new ConfigData();
        data.brokerHost = MineQTTConfig.brokerHost;
        data.brokerPort = MineQTTConfig.brokerPort;
        data.username = MineQTTConfig.username;
        data.password = MineQTTConfig.password;
        data.useAuthentication = MineQTTConfig.useAuthentication;
        data.connectionTimeout = MineQTTConfig.connectionTimeout;
        data.autoReconnect = MineQTTConfig.autoReconnect;

        data.statusTopic = MineQTTConfig.statusTopic;
        data.playerEventsTopic = MineQTTConfig.playerEventsTopic;
        data.chatTopic = MineQTTConfig.chatTopic;
        data.blockEventsTopic = MineQTTConfig.blockEventsTopic;

        data.publishPlayerJoin = MineQTTConfig.publishPlayerJoin;
        data.publishPlayerLeave = MineQTTConfig.publishPlayerLeave;
        data.publishChatMessages = MineQTTConfig.publishChatMessages;
        data.publishBlockBreak = MineQTTConfig.publishBlockBreak;
        data.publishBlockPlace = MineQTTConfig.publishBlockPlace;

        data.onlineMessage = MineQTTConfig.onlineMessage;
        data.offlineMessage = MineQTTConfig.offlineMessage;

        data.enableDebugLogging = MineQTTConfig.enableDebugLogging;
        data.showMqttStatusInChat = MineQTTConfig.showMqttStatusInChat;

        return data;
    }

    private void applyConfigData(ConfigData data) {
        MineQTTConfig.brokerHost = data.brokerHost;
        MineQTTConfig.brokerPort = data.brokerPort;
        MineQTTConfig.username = data.username;
        MineQTTConfig.password = data.password;
        MineQTTConfig.useAuthentication = data.useAuthentication;
        MineQTTConfig.connectionTimeout = data.connectionTimeout;
        MineQTTConfig.autoReconnect = data.autoReconnect;

        MineQTTConfig.statusTopic = data.statusTopic;
        MineQTTConfig.playerEventsTopic = data.playerEventsTopic;
        MineQTTConfig.chatTopic = data.chatTopic;
        MineQTTConfig.blockEventsTopic = data.blockEventsTopic;

        MineQTTConfig.publishPlayerJoin = data.publishPlayerJoin;
        MineQTTConfig.publishPlayerLeave = data.publishPlayerLeave;
        MineQTTConfig.publishChatMessages = data.publishChatMessages;
        MineQTTConfig.publishBlockBreak = data.publishBlockBreak;
        MineQTTConfig.publishBlockPlace = data.publishBlockPlace;

        MineQTTConfig.onlineMessage = data.onlineMessage;
        MineQTTConfig.offlineMessage = data.offlineMessage;

        MineQTTConfig.enableDebugLogging = data.enableDebugLogging;
        MineQTTConfig.showMqttStatusInChat = data.showMqttStatusInChat;
    }

    private static class ConfigData {
        public String brokerHost = "test.mosquitto.org";
        public int brokerPort = 1883;
        public String username = "";
        public String password = "";
        public boolean useAuthentication = false;
        public int connectionTimeout = 10;
        public boolean autoReconnect = true;

        public String statusTopic = "mineqtt/status";
        public String playerEventsTopic = "mineqtt/player/events";
        public String chatTopic = "mineqtt/chat";
        public String blockEventsTopic = "mineqtt/blocks";

        public boolean publishPlayerJoin = true;
        public boolean publishPlayerLeave = true;
        public boolean publishChatMessages = false;
        public boolean publishBlockBreak = false;
        public boolean publishBlockPlace = false;

        public String onlineMessage = "MineQTT is online";
        public String offlineMessage = "MineQTT is offline";

        public boolean enableDebugLogging = false;
        public boolean showMqttStatusInChat = false;
    }
}

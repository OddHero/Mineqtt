package art.rehra.mineqtt.neoforge.config;

import art.rehra.mineqtt.config.ConfigHandler;
import art.rehra.mineqtt.config.MineQTTConfig;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

public class NeoForgeConfigHandler implements ConfigHandler {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // MQTT Connection Settings
    public static final ModConfigSpec.ConfigValue<String> BROKER_URL;
    public static final ModConfigSpec.ConfigValue<String> CLIENT_ID;
    public static final ModConfigSpec.ConfigValue<String> USERNAME;
    public static final ModConfigSpec.ConfigValue<String> PASSWORD;
    public static final ModConfigSpec.BooleanValue AUTO_RECONNECT;
    public static final ModConfigSpec.IntValue CONNECTION_TIMEOUT;
    public static final ModConfigSpec.IntValue KEEP_ALIVE;

    // MQTT Topics
    public static final ModConfigSpec.ConfigValue<String> PLAYER_JOIN_TOPIC;
    public static final ModConfigSpec.ConfigValue<String> PLAYER_LEAVE_TOPIC;
    public static final ModConfigSpec.ConfigValue<String> CHAT_TOPIC;
    public static final ModConfigSpec.ConfigValue<String> BLOCK_BREAK_TOPIC;
    public static final ModConfigSpec.ConfigValue<String> BLOCK_PLACE_TOPIC;

    // Feature Toggles
    public static final ModConfigSpec.BooleanValue ENABLE_PLAYER_EVENTS;
    public static final ModConfigSpec.BooleanValue ENABLE_CHAT_EVENTS;
    public static final ModConfigSpec.BooleanValue ENABLE_BLOCK_EVENTS;
    public static final ModConfigSpec.BooleanValue ENABLE_DEBUGGING;

    // Message Settings
    public static final ModConfigSpec.BooleanValue INCLUDE_COORDINATES;
    public static final ModConfigSpec.BooleanValue INCLUDE_TIMESTAMP;
    public static final ModConfigSpec.ConfigValue<String> MESSAGE_FORMAT;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.comment("MQTT Connection Settings").push("connection");

        BROKER_URL = BUILDER
                .comment("MQTT broker URL (e.g., tcp://localhost:1883)")
                .define("brokerUrl", "tcp://localhost:1883");

        CLIENT_ID = BUILDER
                .comment("MQTT client identifier")
                .define("clientId", "minecraft-client");

        USERNAME = BUILDER
                .comment("MQTT username (leave empty if not required)")
                .define("username", "");

        PASSWORD = BUILDER
                .comment("MQTT password (leave empty if not required)")
                .define("password", "");

        AUTO_RECONNECT = BUILDER
                .comment("Automatically reconnect to MQTT broker if connection is lost")
                .define("autoReconnect", true);

        CONNECTION_TIMEOUT = BUILDER
                .comment("Connection timeout in seconds")
                .defineInRange("connectionTimeout", 30, 1, 300);

        KEEP_ALIVE = BUILDER
                .comment("Keep alive interval in seconds")
                .defineInRange("keepAlive", 60, 1, 3600);

        BUILDER.pop();

        BUILDER.comment("MQTT Topics").push("topics");

        PLAYER_JOIN_TOPIC = BUILDER
                .comment("Topic for player join events")
                .define("playerJoinTopic", "minecraft/players/join");

        PLAYER_LEAVE_TOPIC = BUILDER
                .comment("Topic for player leave events")
                .define("playerLeaveTopic", "minecraft/players/leave");

        CHAT_TOPIC = BUILDER
                .comment("Topic for chat messages")
                .define("chatTopic", "minecraft/chat");

        BLOCK_BREAK_TOPIC = BUILDER
                .comment("Topic for block break events")
                .define("blockBreakTopic", "minecraft/blocks/break");

        BLOCK_PLACE_TOPIC = BUILDER
                .comment("Topic for block place events")
                .define("blockPlaceTopic", "minecraft/blocks/place");

        BUILDER.pop();

        BUILDER.comment("Feature Toggles").push("features");

        ENABLE_PLAYER_EVENTS = BUILDER
                .comment("Enable player join/leave events")
                .define("enablePlayerEvents", true);

        ENABLE_CHAT_EVENTS = BUILDER
                .comment("Enable chat message events")
                .define("enableChatEvents", true);

        ENABLE_BLOCK_EVENTS = BUILDER
                .comment("Enable block break/place events")
                .define("enableBlockEvents", false);

        ENABLE_DEBUGGING = BUILDER
                .comment("Enable debug logging")
                .define("enableDebugging", false);

        BUILDER.pop();

        BUILDER.comment("Message Settings").push("messages");

        INCLUDE_COORDINATES = BUILDER
                .comment("Include player coordinates in events")
                .define("includeCoordinates", true);

        INCLUDE_TIMESTAMP = BUILDER
                .comment("Include timestamp in events")
                .define("includeTimestamp", true);

        MESSAGE_FORMAT = BUILDER
                .comment("Message format (json or text)")
                .define("messageFormat", "json");

        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    @Override
    public void loadConfig() {
        MineQTTConfig.brokerUrl = BROKER_URL.get();
        MineQTTConfig.clientId = CLIENT_ID.get();
        MineQTTConfig.username = USERNAME.get();
        MineQTTConfig.password = PASSWORD.get();
        MineQTTConfig.autoReconnect = AUTO_RECONNECT.get();
        MineQTTConfig.connectionTimeout = CONNECTION_TIMEOUT.get();
        MineQTTConfig.keepAlive = KEEP_ALIVE.get();

        MineQTTConfig.playerJoinTopic = PLAYER_JOIN_TOPIC.get();
        MineQTTConfig.playerLeaveTopic = PLAYER_LEAVE_TOPIC.get();
        MineQTTConfig.chatTopic = CHAT_TOPIC.get();
        MineQTTConfig.blockBreakTopic = BLOCK_BREAK_TOPIC.get();
        MineQTTConfig.blockPlaceTopic = BLOCK_PLACE_TOPIC.get();

        MineQTTConfig.enablePlayerEvents = ENABLE_PLAYER_EVENTS.get();
        MineQTTConfig.enableChatEvents = ENABLE_CHAT_EVENTS.get();
        MineQTTConfig.enableBlockEvents = ENABLE_BLOCK_EVENTS.get();
        MineQTTConfig.enableDebugging = ENABLE_DEBUGGING.get();

        MineQTTConfig.includeCoordinates = INCLUDE_COORDINATES.get();
        MineQTTConfig.includeTimestamp = INCLUDE_TIMESTAMP.get();
        MineQTTConfig.messageFormat = MESSAGE_FORMAT.get();
    }

    @Override
    public void saveConfig() {
        // NeoForge handles saving automatically when values change
        BROKER_URL.set(MineQTTConfig.brokerUrl);
        CLIENT_ID.set(MineQTTConfig.clientId);
        USERNAME.set(MineQTTConfig.username);
        PASSWORD.set(MineQTTConfig.password);
        AUTO_RECONNECT.set(MineQTTConfig.autoReconnect);
        CONNECTION_TIMEOUT.set(MineQTTConfig.connectionTimeout);
        KEEP_ALIVE.set(MineQTTConfig.keepAlive);

        PLAYER_JOIN_TOPIC.set(MineQTTConfig.playerJoinTopic);
        PLAYER_LEAVE_TOPIC.set(MineQTTConfig.playerLeaveTopic);
        CHAT_TOPIC.set(MineQTTConfig.chatTopic);
        BLOCK_BREAK_TOPIC.set(MineQTTConfig.blockBreakTopic);
        BLOCK_PLACE_TOPIC.set(MineQTTConfig.blockPlaceTopic);

        ENABLE_PLAYER_EVENTS.set(MineQTTConfig.enablePlayerEvents);
        ENABLE_CHAT_EVENTS.set(MineQTTConfig.enableChatEvents);
        ENABLE_BLOCK_EVENTS.set(MineQTTConfig.enableBlockEvents);
        ENABLE_DEBUGGING.set(MineQTTConfig.enableDebugging);

        INCLUDE_COORDINATES.set(MineQTTConfig.includeCoordinates);
        INCLUDE_TIMESTAMP.set(MineQTTConfig.includeTimestamp);
        MESSAGE_FORMAT.set(MineQTTConfig.messageFormat);
    }

    @Override
    public boolean isConfigValid() {
        return BROKER_URL.get() != null && !BROKER_URL.get().isEmpty() &&
               CLIENT_ID.get() != null && !CLIENT_ID.get().isEmpty();
    }

    @Override
    public void resetToDefaults() {
        MineQTTConfig.resetToDefaults();
        saveConfig();
    }
}

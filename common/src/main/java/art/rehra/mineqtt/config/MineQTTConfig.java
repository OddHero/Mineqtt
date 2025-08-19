package art.rehra.mineqtt.config;

public class MineQTTConfig {
    // MQTT Connection Settings
    public static String brokerUrl = "tcp://localhost:1883";
    public static String clientId = "minecraft-client";
    public static String username = "";
    public static String password = "";
    public static boolean autoReconnect = true;
    public static int connectionTimeout = 30;
    public static int keepAlive = 60;

    // MQTT Topics
    public static String playerJoinTopic = "minecraft/players/join";
    public static String playerLeaveTopic = "minecraft/players/leave";
    public static String chatTopic = "minecraft/chat";
    public static String blockBreakTopic = "minecraft/blocks/break";
    public static String blockPlaceTopic = "minecraft/blocks/place";

    // Feature Toggles
    public static boolean enablePlayerEvents = true;
    public static boolean enableChatEvents = true;
    public static boolean enableBlockEvents = false;
    public static boolean enableDebugging = false;

    // Message Settings
    public static boolean includeCoordinates = true;
    public static boolean includeTimestamp = true;
    public static String messageFormat = "json";

    // Platform-specific config saving/loading will be handled by each platform
    public static void resetToDefaults() {
        brokerUrl = "tcp://localhost:1883";
        clientId = "minecraft-client";
        username = "";
        password = "";
        autoReconnect = true;
        connectionTimeout = 30;
        keepAlive = 60;

        playerJoinTopic = "minecraft/players/join";
        playerLeaveTopic = "minecraft/players/leave";
        chatTopic = "minecraft/chat";
        blockBreakTopic = "minecraft/blocks/break";
        blockPlaceTopic = "minecraft/blocks/place";

        enablePlayerEvents = true;
        enableChatEvents = true;
        enableBlockEvents = false;
        enableDebugging = false;

        includeCoordinates = true;
        includeTimestamp = true;
        messageFormat = "json";
    }
}

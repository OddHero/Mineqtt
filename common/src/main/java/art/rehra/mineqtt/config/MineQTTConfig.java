package art.rehra.mineqtt.config;

public class MineQTTConfig {
    // MQTT Connection Settings
    public static String brokerHost = "test.mosquitto.org";
    public static int brokerPort = 1883;
    public static String username = "";
    public static String password = "";
    public static boolean useAuthentication = false;
    public static int connectionTimeout = 10;
    public static boolean autoReconnect = true;

    // MQTT Topics
    public static String statusTopic = "mineqtt/status";
    public static String playerEventsTopic = "mineqtt/player/events";
    public static String chatTopic = "mineqtt/chat";
    public static String blockEventsTopic = "mineqtt/blocks";

    // Publishing Settings
    public static boolean publishPlayerJoin = true;
    public static boolean publishPlayerLeave = true;
    public static boolean publishChatMessages = false;
    public static boolean publishBlockBreak = false;
    public static boolean publishBlockPlace = false;

    // Message Settings
    public static String onlineMessage = "MineQTT is online";
    public static String offlineMessage = "MineQTT is offline";

    // Debug Settings
    public static boolean enableDebugLogging = false;
    public static boolean showMqttStatusInChat = false;

    // Platform-specific config saving/loading will be handled by each platform
    public static void resetToDefaults() {
        brokerHost = "test.mosquitto.org";
        brokerPort = 1883;
        username = "";
        password = "";
        useAuthentication = false;
        connectionTimeout = 10;
        autoReconnect = true;

        statusTopic = "mineqtt/status";
        playerEventsTopic = "mineqtt/player/events";
        chatTopic = "mineqtt/chat";
        blockEventsTopic = "mineqtt/blocks";

        publishPlayerJoin = true;
        publishPlayerLeave = true;
        publishChatMessages = false;
        publishBlockBreak = false;
        publishBlockPlace = false;

        onlineMessage = "MineQTT is online";
        offlineMessage = "MineQTT is offline";

        enableDebugLogging = false;
        showMqttStatusInChat = false;
    }
}

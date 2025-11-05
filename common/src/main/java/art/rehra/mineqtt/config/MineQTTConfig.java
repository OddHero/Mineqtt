package art.rehra.mineqtt.config;

public class MineQTTConfig {
    // MQTT Connection Settings
    public static String brokerUrl;
    public static String clientId;
    public static String username;
    public static String password;
    public static boolean autoReconnect;
    public static long connectionTimeout;
    public static int keepAlive;

    // MQTT Topics
    public static String baseTopic;
    public static String statusTopic;

    static {
        resetToDefaults();
    }

    // Platform-specific config saving/loading will be handled by each platform
    public static void resetToDefaults() {
        brokerUrl = StaticDefaults.DEFAULT_BROKER_URL;
        clientId = StaticDefaults.DEFAULT_CLIENT_ID;
        username = StaticDefaults.DEFAULT_USERNAME;
        password = StaticDefaults.DEFAULT_PASSWORD;
        autoReconnect = StaticDefaults.DEFAULT_AUTO_RECONNECT;
        connectionTimeout = StaticDefaults.DEFAULT_CONNECTION_TIMEOUT;
        keepAlive = StaticDefaults.DEFAULT_KEEP_ALIVE;
        baseTopic = StaticDefaults.DEFAULT_TOPIC_BASE;
        statusTopic = StaticDefaults.DEFAULT_TOPIC_STATUS;
    }

    public static String getTopicPath(String subTopic) {
        return baseTopic + "/" + subTopic;
    }
}

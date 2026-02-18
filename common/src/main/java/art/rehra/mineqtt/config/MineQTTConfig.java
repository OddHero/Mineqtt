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
    public static boolean allowItemNetherPortalTeleport;

    // MQTT Topics
    public static String baseTopic;
    public static String statusTopic;
    public static int goalX;
    public static int goalY;
    public static int goalZ;
    public static boolean zombieGoalEnabled;

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
        allowItemNetherPortalTeleport = StaticDefaults.DEFAULT_ALLOW_ITEM_NETHER_PORTAL_TELEPORT;
        baseTopic = StaticDefaults.DEFAULT_TOPIC_BASE;
        statusTopic = StaticDefaults.DEFAULT_TOPIC_STATUS;
        goalX = StaticDefaults.DEFAULT_GOAL_X;
        goalY = StaticDefaults.DEFAULT_GOAL_Y;
        goalZ = StaticDefaults.DEFAULT_GOAL_Z;
        zombieGoalEnabled = StaticDefaults.DEFAULT_ZOMBIE_GOAL_ENABLED;
    }

    public static String getTopicPath(String subTopic) {
        return baseTopic + "/" + subTopic;
    }
}

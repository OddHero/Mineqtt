package art.rehra.mineqtt.config;

public class MineQTTConfig {
    // MQTT Connection Settings
    public static String brokerUrl = "tcp://localhost:1883";
    public static String clientId = "minecraft-client";
    public static String username = "";
    public static String password = "";
    public static boolean autoReconnect = true;
    public static long connectionTimeout = 30;
    public static int keepAlive = 60;

    // MQTT Topics
    public static String baseTopic = "minecraft";
    public static String statusTopic = "status";


    // Platform-specific config saving/loading will be handled by each platform
    public static void resetToDefaults() {
        brokerUrl = "tcp://localhost:1883";
        clientId = "minecraft-client";
        username = "";
        password = "";
        autoReconnect = true;
        connectionTimeout = 30;
        keepAlive = 60;
        baseTopic = "minecraft";
        statusTopic = "status";
    }

    public static String getTopicPath(String subTopic) {
        return baseTopic + "/" + subTopic;
    }
}

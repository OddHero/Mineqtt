package art.rehra.mineqtt.config;

public final class StaticDefaults {

    // Mod
    public static final String MOD_ID = "mineqtt";
    public static final String MOD_NAME = "MineQTT";

    // MQTT Connection Settings
    public static final String DEFAULT_BROKER_URL = "test.mosquitto.org";
    public static final String DEFAULT_CLIENT_ID = java.util.UUID.randomUUID().toString();
    public static final String DEFAULT_USERNAME = "";
    public static final String DEFAULT_PASSWORD = "";
    public static final boolean DEFAULT_AUTO_RECONNECT = true;
    public static final long DEFAULT_CONNECTION_TIMEOUT = 30L; // seconds
    public static final int DEFAULT_KEEP_ALIVE = 60; // seconds

    // MQTT Topics
    public static final String DEFAULT_TOPIC_BASE = "minecraft-" + DEFAULT_CLIENT_ID; // Unique base topic per client
    public static final String DEFAULT_TOPIC_STATUS = "status"; // For online/offline status
    public static final String DEFAULT_TOPIC_COMMAND_RUN = "command/run"; // For sending commands to Minecraft
    public static final String DEFAULT_TOPIC_COMMAND_RESPONSE = "command/response"; // For sending command responses
    public static final String DEFAULT_TOPIC_CHAT = "chat"; // For chat messages
    public static final String DEFAULT_TOPIC_CHAT_SAY = "chat/say"; // For receiving chat messages over MQTT

    public static final String DEFAULT_TOPIC_PLAYER = "player"; // For player-related messages
    public static final String DEFAULT_TOPIC_PLAYER_JOIN = "player/join"; // For player join
    public static final String DEFAULT_TOPIC_PLAYER_LEAVE = "player/leave"; // For player leave
    public static final String DEFAULT_TOPIC_PLAYER_DEATH = "player/death"; // For player

}

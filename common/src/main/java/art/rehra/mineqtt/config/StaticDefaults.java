package art.rehra.mineqtt.config;

import java.net.InetAddress;
import java.net.UnknownHostException;

public final class StaticDefaults {

    // Mod
    public static final String MOD_ID = "mineqtt";
    public static final String MOD_NAME = "MineQTT";

    // MQTT Connection Settings
    public static final String DEFAULT_BROKER_URL = "test.mosquitto.org";
    public static final String DEFAULT_CLIENT_ID = generateClientId();
    public static final String DEFAULT_USERNAME = "";
    public static final String DEFAULT_PASSWORD = "";
    public static final boolean DEFAULT_AUTO_RECONNECT = true;
    public static final long DEFAULT_CONNECTION_TIMEOUT = 30L; // seconds
    public static final int DEFAULT_KEEP_ALIVE = 60; // seconds
    public static final boolean DEFAULT_ALLOW_ITEM_NETHER_PORTAL_TELEPORT = true;

    // MQTT Topics
    public static final String DEFAULT_TOPIC_BASE = "minecraft-" + DEFAULT_CLIENT_ID; // Unique base topic per client
    public static final String DEFAULT_TOPIC_STATUS = "status"; // For online/offline status
    public static final String DEFAULT_TOPIC_COMMAND_RUN = "command/run"; // For sending commands to Minecraft
    public static final String DEFAULT_TOPIC_COMMAND_RESPONSE = "command/response"; // For sending command responses
    public static final String DEFAULT_TOPIC_CHAT = "chat"; // For chat messages
    public static final String DEFAULT_TOPIC_CHAT_SAY = "chat/say"; // For receiving chat messages over MQTT

    public static final String DEFAULT_TOPIC_PLAYER = "player"; // For player-related messages

    /**
     * Generate a deterministic client ID based on hostname.
     * This prevents creating ghost topics on every dev client restart.
     */
    private static String generateClientId() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            // Clean hostname to be MQTT-compatible
            hostname = hostname.replaceAll("[^a-zA-Z0-9-_]", "-");
            return hostname;
        } catch (UnknownHostException e) {
            // Fallback to system property if hostname unavailable
            String username = System.getProperty("user.name", "unknown");
            username = username.replaceAll("[^a-zA-Z0-9-_]", "-");
            return username;
        }
    }
    public static final String DEFAULT_TOPIC_PLAYER_JOIN = "player/join"; // For player join
    public static final String DEFAULT_TOPIC_PLAYER_LEAVE = "player/leave"; // For player leave
    public static final String DEFAULT_TOPIC_PLAYER_DEATH = "player/death"; // For player

}

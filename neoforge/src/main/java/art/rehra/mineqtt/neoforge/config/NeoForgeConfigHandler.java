package art.rehra.mineqtt.neoforge.config;

import art.rehra.mineqtt.config.ConfigHandler;
import art.rehra.mineqtt.config.MineQTTConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.UUID;

public class NeoForgeConfigHandler implements ConfigHandler {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // MQTT Connection Settings
    public static final ModConfigSpec.ConfigValue<String> BROKER_URL;
    public static final ModConfigSpec.ConfigValue<String> CLIENT_ID;
    public static final ModConfigSpec.ConfigValue<String> USERNAME;
    public static final ModConfigSpec.ConfigValue<String> PASSWORD;
    public static final ModConfigSpec.BooleanValue AUTO_RECONNECT;
    public static final ModConfigSpec.LongValue CONNECTION_TIMEOUT;
    public static final ModConfigSpec.IntValue KEEP_ALIVE;
    public static final ModConfigSpec.BooleanValue ALLOW_ITEM_NETHER_PORTAL_TELEPORT;

    public static final ModConfigSpec.ConfigValue<String> BASE_TOPIC;
    public static final ModConfigSpec.ConfigValue<String> STATUS_TOPIC;

    public static final ModConfigSpec.IntValue GOAL_X;
    public static final ModConfigSpec.IntValue GOAL_Y;
    public static final ModConfigSpec.IntValue GOAL_Z;
    public static final ModConfigSpec.BooleanValue ZOMBIE_GOAL_ENABLED;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.comment("MQTT Connection Settings").push("connection");

        BROKER_URL = BUILDER
                .comment("MQTT broker URL (e.g., tcp://localhost:1883)")
                .define("brokerUrl", "tcp://localhost:1883");

        CLIENT_ID = BUILDER
                .comment("MQTT client identifier")
                .define("clientId", "minecraft-client" + UUID.randomUUID());

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
                .defineInRange("connectionTimeout", 30L, 1, 300);

        KEEP_ALIVE = BUILDER
                .comment("Keep alive interval in seconds")
                .defineInRange("keepAlive", 60, 1, 3600);

        ALLOW_ITEM_NETHER_PORTAL_TELEPORT = BUILDER
                .comment("Allow items to teleport through nether portals")
                .define("allowItemNetherPortalTeleport", true);

        BUILDER.pop();

        BUILDER.comment("MQTT Topics").push("topics");

        BASE_TOPIC = BUILDER
                .comment("MQTT base topic")
                .define("baseTopic", "minecraft");

        STATUS_TOPIC = BUILDER
                .comment("MQTT status topic")
                .define("statusTopic", "status");

        BUILDER.pop();

        BUILDER.comment("Goal Coordinates").push("goal");

        GOAL_X = BUILDER
                .comment("X coordinate of the global goal")
                .defineInRange("goalX", 7, -30000000, 30000000);

        GOAL_Y = BUILDER
                .comment("Y coordinate of the global goal")
                .defineInRange("goalY", -35, -2048, 2048);

        GOAL_Z = BUILDER
                .comment("Z coordinate of the global goal")
                .defineInRange("goalZ", 4, -30000000, 30000000);

        ZOMBIE_GOAL_ENABLED = BUILDER
                .comment("Whether the global zombie goal is enabled")
                .define("zombieGoalEnabled", false);

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
        MineQTTConfig.allowItemNetherPortalTeleport = ALLOW_ITEM_NETHER_PORTAL_TELEPORT.get();
        MineQTTConfig.baseTopic = BASE_TOPIC.get();
        MineQTTConfig.statusTopic = STATUS_TOPIC.get();
        MineQTTConfig.goalX = GOAL_X.get();
        MineQTTConfig.goalY = GOAL_Y.get();
        MineQTTConfig.goalZ = GOAL_Z.get();
        MineQTTConfig.zombieGoalEnabled = ZOMBIE_GOAL_ENABLED.get();
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
        ALLOW_ITEM_NETHER_PORTAL_TELEPORT.set(MineQTTConfig.allowItemNetherPortalTeleport);
        BASE_TOPIC.set(MineQTTConfig.baseTopic);
        STATUS_TOPIC.set(MineQTTConfig.statusTopic);
        GOAL_X.set(MineQTTConfig.goalX);
        GOAL_Y.set(MineQTTConfig.goalY);
        GOAL_Z.set(MineQTTConfig.goalZ);
        ZOMBIE_GOAL_ENABLED.set(MineQTTConfig.zombieGoalEnabled);
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

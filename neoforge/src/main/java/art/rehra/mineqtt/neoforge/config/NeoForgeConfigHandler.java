package art.rehra.mineqtt.neoforge.config;

import art.rehra.mineqtt.config.ConfigHandler;
import art.rehra.mineqtt.config.MineQTTConfig;
import net.neoforged.fml.config.ModConfig;
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

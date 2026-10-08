package art.rehra.mineqtt.config;

import art.rehra.mineqtt.mqtt.core.BrokerSettings;
import art.rehra.mineqtt.mqtt.core.ProtocolVersion;

import java.util.List;

/** The only source of config defaults. Both loader config handlers and {@link MineQTTConfig} read them from here. */
public final class StaticDefaults {

    // Mod
    public static final String MOD_ID = "mineqtt";
    public static final String MOD_NAME = "MineQTT";

    /** The config schema written by this version. v1 files have no {@code configVersion} key. */
    public static final int CONFIG_VERSION = 2;

    // MQTT connection. An empty broker disables MQTT.
    public static final String DEFAULT_BROKER = "";
    public static final ProtocolVersion DEFAULT_PROTOCOL = ProtocolVersion.MQTT_5;
    public static final int DEFAULT_KEEP_ALIVE_SEC = BrokerSettings.DEFAULT_KEEP_ALIVE_SEC;
    public static final String DEFAULT_USERNAME = "";
    public static final String DEFAULT_PASSWORD = "";
    /** Environment variable that overrides the configured password, so it need not be stored in the config file. */
    public static final String PASSWORD_ENV_VAR = "MINEQTT_PASSWORD";
    public static final String DEFAULT_TLS_CA_FILE = "";
    public static final boolean DEFAULT_TLS_INSECURE_SKIP_VERIFY = false;

    // Topics. An empty bridge id is generated once per world.
    public static final String DEFAULT_BRIDGE_ID = "";
    public static final String DEFAULT_BASE_TOPIC = "mineqtt";
    public static final String DEFAULT_DISCOVERY_PREFIX = "homeassistant";
    public static final String DEFAULT_HA_BIRTH_TOPIC = "homeassistant/status";
    public static final HaExpose DEFAULT_HA_EXPOSE = HaExpose.AUTO;

    // Topic ACL. Raw allowlist entries may use these placeholders; they are resolved per world.
    public static final String BASE_PLACEHOLDER = "<base>";
    public static final String BRIDGE_ID_PLACEHOLDER = "<bridgeId>";
    public static final List<String> DEFAULT_ACL_RAW_ALLOW =
            List.of("mineqtt/shared/#", BASE_PLACEHOLDER + "/" + BRIDGE_ID_PLACEHOLDER + "/raw/#");
    public static final AutoBoolean DEFAULT_ACL_OPS_BYPASS_ALLOWLIST = AutoBoolean.AUTO;
    public static final boolean DEFAULT_ACL_EXPLORER_BROKER_WIDE = false;

    public static final boolean DEFAULT_BLOCK_PLACER_ENABLED = false;
    /** 0 means unlimited. */
    public static final int DEFAULT_LIMITS_MAX_DEVICES_PER_PLAYER = 0;

    /** The v1 status topic recorded by the config migration; empty when there was no v1 config. */
    public static final String DEFAULT_LEGACY_V1_STATUS_TOPIC = "";

    // Leftover experiments, kept until they are removed together with their features.
    public static final boolean DEFAULT_ALLOW_ITEM_NETHER_PORTAL_TELEPORT = true;
    public static final int DEFAULT_GOAL_X = 7;
    public static final int DEFAULT_GOAL_Y = -35;
    public static final int DEFAULT_GOAL_Z = 4;
    public static final boolean DEFAULT_ZOMBIE_GOAL_ENABLED = false;

    private StaticDefaults() {
    }
}

package art.rehra.mineqtt.config;

import art.rehra.mineqtt.mqtt.core.BrokerSettings;
import art.rehra.mineqtt.mqtt.core.ProtocolVersion;
import art.rehra.mineqtt.twin.core.EntityDocuments;
import art.rehra.mineqtt.twin.core.HaDomains;

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
    /** The wires one player may own (M2 plan D9); 0 means unlimited. Wires made from the console count for nobody. */
    public static final int DEFAULT_LIMITS_MAX_WIRES_PER_PLAYER = 256;

    // Home Assistant twin (M3 plan D13). Never "homeassistant" (the discovery prefix) and never under the base topic.
    public static final String DEFAULT_TWIN_STATESTREAM_BASE = "ha_statestream";
    /**
     * The one {@code twin.allowedEntities} entry that is no glob: every entity on integrated servers, none on dedicated
     * servers (the twin is off until the admin lists entities). Globs are lowercase, so it cannot be mistaken for one.
     */
    public static final String AUTO_ENTRY = "AUTO";
    public static final List<String> DEFAULT_TWIN_ALLOWED_ENTITIES = List.of(AUTO_ENTRY);
    /** The control blueprint's {@code allowed_domains} default (D12, D15). */
    public static final List<String> DEFAULT_TWIN_HA_ACTION_ALLOWLIST = HaDomains.DEFAULT_CONTROL_DOMAINS;
    /** Bridge-wide Home Assistant requests per second (D10), within 1 and {@value #MAX_TWIN_REQUESTS_PER_SECOND}. */
    public static final int DEFAULT_TWIN_MAX_REQUESTS_PER_SECOND = 20;
    public static final int MAX_TWIN_REQUESTS_PER_SECOND = 1000;
    /** Read the catalog blueprint's topics (D3, D4). */
    public static final boolean DEFAULT_TWIN_CATALOG = true;
    /** Entities the twin keeps at most (D4), within 1 and {@value #MAX_TWIN_CATALOG_MAX}. */
    public static final int DEFAULT_TWIN_CATALOG_MAX = EntityDocuments.Limits.DEFAULT.maxEntities();
    public static final int MAX_TWIN_CATALOG_MAX = 10_000;

    /** Off: an empty dedicated server pauses as {@code pause-when-empty-seconds} says, and MQTT is pumped between ticks. */
    public static final boolean DEFAULT_SERVER_KEEP_TICKING_WHEN_EMPTY = false;

    /** The v1 status topic recorded by the config migration; empty when there was no v1 config. */
    public static final String DEFAULT_LEGACY_V1_STATUS_TOPIC = "";

    private StaticDefaults() {
    }
}

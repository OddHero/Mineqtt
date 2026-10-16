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

    // World telemetry and players (M4 plan D6 to D10, D16). Nothing about a player is published without their consent.
    /** false keeps only the Milestone 1 telemetry keys (players_online and the drop counters). */
    public static final boolean DEFAULT_TELEMETRY_ENABLED = true;
    /** At most this often a change other than players, day or weather is published, within the two bounds below. */
    public static final int DEFAULT_TELEMETRY_INTERVAL_SECONDS = 10;
    public static final int MIN_TELEMETRY_INTERVAL_SECONDS = 5;
    public static final int MAX_TELEMETRY_INTERVAL_SECONDS = 300;
    /** The server's upper bound on what is published about a player; each player's consent still applies. */
    public static final TelemetryPlayers DEFAULT_TELEMETRY_PLAYERS = TelemetryPlayers.PRESENCE;
    /** An admin veto only: false means counts only, no names anywhere. It never widens naming beyond consent (D9). */
    public static final boolean DEFAULT_TELEMETRY_PUBLISH_PLAYER_NAMES = true;
    /** Home Assistant's Broadcast text, shown as system chat (D8). */
    public static final boolean DEFAULT_TELEMETRY_ALLOW_BROADCAST = false;
    /** Presence devices at most; beyond it nobody new gets one (D10). */
    public static final int DEFAULT_TELEMETRY_MAX_PRESENCE_DEVICES = 64;
    public static final int MIN_TELEMETRY_MAX_PRESENCE_DEVICES = 1;
    public static final int MAX_TELEMETRY_MAX_PRESENCE_DEVICES = 1000;
    /** Days a player may stay unseen before their presence device and name are forgotten; 0 means never. */
    public static final int DEFAULT_TELEMETRY_FORGET_AFTER_DAYS = 90;
    public static final int MAX_TELEMETRY_FORGET_AFTER_DAYS = 3650;

    // Block Placer (D12): blockPlacer.enabled above stays the master switch.
    /** Who may configure a Block Placer besides operators: AUTO means everyone on integrated, nobody on dedicated servers. */
    public static final AutoBoolean DEFAULT_BLOCK_PLACER_PLAYERS_MAY_CONFIGURE = AutoBoolean.AUTO;
    /** A placed block costs its item from the container behind the placer. */
    public static final boolean DEFAULT_BLOCK_PLACER_CONSUME_ITEMS = true;
    /**
     * While items are consumed, any block in the container behind a placer may be placed, not only those of
     * {@code #mineqtt:placeable}; the code's hard denies still hold.
     */
    public static final boolean DEFAULT_BLOCK_PLACER_CONTAINER_UNLOCKS = true;

    // Block Observer (D12)
    /** Block-entity data in the observer's full payload; the observer and its target must also share one claim. */
    public static final boolean DEFAULT_BLOCK_OBSERVER_EXPORT_BLOCK_ENTITY_DATA = false;

    // Rooms (D11)
    /** The longest side of a room, in blocks, within the two bounds below. */
    public static final int DEFAULT_ROOMS_MAX_SIZE = 128;
    public static final int MIN_ROOMS_MAX_SIZE = 8;
    public static final int MAX_ROOMS_MAX_SIZE = 512;
    /** Rooms in one world, whoever made them. */
    public static final int MAX_ROOMS_PER_WORLD = 512;
    /** The rooms one player may own; 0 means unlimited (the world's cap still applies). Operators are not limited. */
    public static final int DEFAULT_LIMITS_MAX_ROOMS_PER_PLAYER = 16;

    /** Off: an empty dedicated server pauses as {@code pause-when-empty-seconds} says, and MQTT is pumped between ticks. */
    public static final boolean DEFAULT_SERVER_KEEP_TICKING_WHEN_EMPTY = false;

    /** The v1 status topic recorded by the config migration; empty when there was no v1 config. */
    public static final String DEFAULT_LEGACY_V1_STATUS_TOPIC = "";

    private StaticDefaults() {
    }
}

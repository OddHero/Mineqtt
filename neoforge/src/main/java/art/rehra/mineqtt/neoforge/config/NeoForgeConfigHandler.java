package art.rehra.mineqtt.neoforge.config;

import art.rehra.mineqtt.config.AutoBoolean;
import art.rehra.mineqtt.config.ConfigHandler;
import art.rehra.mineqtt.config.ConfigMigration;
import art.rehra.mineqtt.config.ConfigValidation;
import art.rehra.mineqtt.config.HaExpose;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.config.StaticDefaults;
import art.rehra.mineqtt.config.TelemetryPlayers;
import art.rehra.mineqtt.mqtt.core.ProtocolVersion;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.EnumGetMethod;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.io.ParsingException;
import com.electronwill.nightconfig.core.io.WritingMode;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class NeoForgeConfigHandler implements ConfigHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("MineQTT-Config");
    public static final String FILE_NAME = "mineqtt.toml";
    private static final String V1_BACKUP_SUFFIX = ".v1.bak";
    /** Tables only a v1 file has. [goal], which Milestone 1's v2 files have too, is not read any more. */
    private static final List<String> V1_ONLY_TABLES = List.of("connection", "topics");
    // Stands for a telemetry.players value NightConfig could not read; it is no setting, so it narrows to OFF.
    private static final Object UNREADABLE = new Object();
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue CONFIG_VERSION;

    // MQTT Connection Settings
    public static final ModConfigSpec.ConfigValue<String> BROKER;
    public static final ModConfigSpec.ConfigValue<String> PROTOCOL;
    public static final ModConfigSpec.IntValue KEEP_ALIVE_SEC;
    public static final ModConfigSpec.ConfigValue<String> USERNAME;
    public static final ModConfigSpec.ConfigValue<String> PASSWORD;

    // Topics
    public static final ModConfigSpec.ConfigValue<String> BRIDGE_ID;
    public static final ModConfigSpec.ConfigValue<String> BASE_TOPIC;
    public static final ModConfigSpec.ConfigValue<String> DISCOVERY_PREFIX;
    public static final ModConfigSpec.ConfigValue<String> HA_BIRTH_TOPIC;

    public static final ModConfigSpec.ConfigValue<String> TLS_CA_FILE;
    public static final ModConfigSpec.BooleanValue TLS_INSECURE_SKIP_VERIFY;

    public static final ModConfigSpec.EnumValue<HaExpose> HA_EXPOSE;

    public static final ModConfigSpec.ConfigValue<List<? extends String>> ACL_RAW_ALLOW;
    public static final ModConfigSpec.EnumValue<AutoBoolean> ACL_OPS_BYPASS_ALLOWLIST;
    public static final ModConfigSpec.BooleanValue ACL_EXPLORER_BROKER_WIDE;

    public static final ModConfigSpec.BooleanValue BLOCK_PLACER_ENABLED;
    public static final ModConfigSpec.EnumValue<AutoBoolean> BLOCK_PLACER_PLAYERS_MAY_CONFIGURE;
    public static final ModConfigSpec.BooleanValue BLOCK_PLACER_CONSUME_ITEMS;
    public static final ModConfigSpec.BooleanValue BLOCK_PLACER_CONTAINER_UNLOCKS;

    public static final ModConfigSpec.BooleanValue BLOCK_OBSERVER_EXPORT_BLOCK_ENTITY_DATA;

    public static final ModConfigSpec.IntValue LIMITS_MAX_DEVICES_PER_PLAYER;
    public static final ModConfigSpec.IntValue LIMITS_MAX_WIRES_PER_PLAYER;
    public static final ModConfigSpec.IntValue LIMITS_MAX_ROOMS_PER_PLAYER;

    public static final ModConfigSpec.IntValue ROOMS_MAX_SIZE;

    public static final ModConfigSpec.ConfigValue<String> TWIN_STATESTREAM_BASE;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> TWIN_ALLOWED_ENTITIES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> TWIN_HA_ACTION_ALLOWLIST;
    public static final ModConfigSpec.IntValue TWIN_MAX_REQUESTS_PER_SECOND;
    public static final ModConfigSpec.BooleanValue TWIN_CATALOG;
    public static final ModConfigSpec.IntValue TWIN_CATALOG_MAX;

    public static final ModConfigSpec.BooleanValue TELEMETRY_ENABLED;
    public static final ModConfigSpec.IntValue TELEMETRY_INTERVAL_SECONDS;
    public static final ModConfigSpec.EnumValue<TelemetryPlayers> TELEMETRY_PLAYERS;
    // No BooleanValue: NeoForge would correct an invalid value to true, the widest; see isPresent.
    public static final ModConfigSpec.ConfigValue<Boolean> TELEMETRY_PUBLISH_PLAYER_NAMES;
    public static final ModConfigSpec.BooleanValue TELEMETRY_ALLOW_BROADCAST;
    public static final ModConfigSpec.IntValue TELEMETRY_MAX_PRESENCE_DEVICES;
    public static final ModConfigSpec.IntValue TELEMETRY_FORGET_AFTER_DAYS;

    public static final ModConfigSpec.BooleanValue SERVER_KEEP_TICKING_WHEN_EMPTY;

    public static final ModConfigSpec.ConfigValue<String> LEGACY_V1_STATUS_TOPIC;

    public static final ModConfigSpec SPEC;

    static {
        // Top-level keys come first: TOML puts everything after a [section] header into that section.
        CONFIG_VERSION = BUILDER
                .comment("Config schema version, set by MineQTT. Do not change it.")
                .translation("config.mineqtt.config_version")
                .defineInRange("configVersion", StaticDefaults.CONFIG_VERSION, 1, Integer.MAX_VALUE);

        BROKER = BUILDER
                .comment("MQTT broker URI: host, host:port, tcp://, mqtt://, ssl://, mqtts://, ws:// or wss://. Empty disables MQTT.")
                .translation("config.mineqtt.broker")
                .define("broker", StaticDefaults.DEFAULT_BROKER);

        PROTOCOL = BUILDER
                .comment("MQTT protocol version: 5 or 3.1.1 (for brokers without MQTT 5 support)")
                .translation("config.mineqtt.protocol")
                .define("protocol", StaticDefaults.DEFAULT_PROTOCOL.configValue(), ConfigValidation::isValidProtocol);

        KEEP_ALIVE_SEC = BUILDER
                .comment("MQTT keep alive in seconds, 0 disables it")
                .translation("config.mineqtt.keep_alive_sec")
                .defineInRange("keepAliveSec", StaticDefaults.DEFAULT_KEEP_ALIVE_SEC, 0, 65_535);

        USERNAME = BUILDER
                .comment("MQTT username (leave empty if not required)")
                .translation("config.mineqtt.username")
                .define("username", StaticDefaults.DEFAULT_USERNAME);

        PASSWORD = BUILDER
                .comment("MQTT password (leave empty if not required). The environment variable "
                        + StaticDefaults.PASSWORD_ENV_VAR + " takes precedence, so the password need not be stored here.")
                .translation("config.mineqtt.password")
                .define("password", StaticDefaults.DEFAULT_PASSWORD);

        BRIDGE_ID = BUILDER
                .comment("Bridge id: the topic level of this server under the base topic, and the MQTT client id mineqtt_<bridgeId>.",
                        "Empty generates one per world. Otherwise 1-64 of a-z, 0-9, _ and -.")
                .translation("config.mineqtt.bridge_id")
                .define("bridgeId", StaticDefaults.DEFAULT_BRIDGE_ID, ConfigValidation::isValidBridgeId);

        BASE_TOPIC = BUILDER
                .comment("MQTT base topic of every MineQTT device topic")
                .translation("config.mineqtt.base_topic")
                .define("baseTopic", StaticDefaults.DEFAULT_BASE_TOPIC, ConfigValidation::isValidConfigTopic);

        DISCOVERY_PREFIX = BUILDER
                .comment("Home Assistant MQTT discovery prefix")
                .translation("config.mineqtt.discovery_prefix")
                .define("discoveryPrefix", StaticDefaults.DEFAULT_DISCOVERY_PREFIX, ConfigValidation::isValidConfigTopic);

        HA_BIRTH_TOPIC = BUILDER
                .comment("Home Assistant birth topic; 'online' there makes MineQTT republish discovery and state")
                .translation("config.mineqtt.ha_birth_topic")
                .define("haBirthTopic", StaticDefaults.DEFAULT_HA_BIRTH_TOPIC, ConfigValidation::isValidConfigTopic);

        BUILDER.comment("TLS for ssl://, mqtts:// and wss:// brokers").translation("config.mineqtt.section.tls").push("tls");

        TLS_CA_FILE = BUILDER
                .comment("PEM file with the CA certificates to trust instead of the Java truststore. Empty uses the Java truststore.")
                .translation("config.mineqtt.tls_ca_file")
                .define("caFile", StaticDefaults.DEFAULT_TLS_CA_FILE);

        TLS_INSECURE_SKIP_VERIFY = BUILDER
                .comment("Accept any server certificate and host name. Only for testing; logged as a warning on every connect.")
                .translation("config.mineqtt.tls_insecure_skip_verify")
                .define("insecureSkipVerify", StaticDefaults.DEFAULT_TLS_INSECURE_SKIP_VERIFY);

        BUILDER.pop();

        BUILDER.comment("Home Assistant").translation("config.mineqtt.section.ha").push("ha");

        HA_EXPOSE = BUILDER
                .comment("Which devices Home Assistant discovers: ALL, OPT_IN (only devices a permitted player exposes), NONE,",
                        "or AUTO (ALL on integrated servers, OPT_IN on dedicated servers)")
                .translation("config.mineqtt.ha_expose")
                .defineEnum("expose", StaticDefaults.DEFAULT_HA_EXPOSE);

        BUILDER.pop();

        BUILDER.comment("Topic access control for players").translation("config.mineqtt.section.acl").push("acl");

        ACL_RAW_ALLOW = BUILDER
                .comment("Topic filters players may publish raw messages to and link raw topics under.",
                        StaticDefaults.BASE_PLACEHOLDER + " and " + StaticDefaults.BRIDGE_ID_PLACEHOLDER + " stand for the base topic and the bridge id.")
                .translation("config.mineqtt.acl_raw_allow")
                .defineListAllowEmpty("rawAllow", StaticDefaults.DEFAULT_ACL_RAW_ALLOW, () -> "", ConfigValidation::isValidRawAllowFilter);

        ACL_OPS_BYPASS_ALLOWLIST = BUILDER
                .comment("Whether operators may use topics outside rawAllow: TRUE, FALSE, or AUTO (true on integrated, false on dedicated servers)")
                .translation("config.mineqtt.acl_ops_bypass_allowlist")
                .defineEnum("opsBypassAllowlist", StaticDefaults.DEFAULT_ACL_OPS_BYPASS_ALLOWLIST);

        ACL_EXPLORER_BROKER_WIDE = BUILDER
                .comment("Let players with the mineqtt.admin permission listen on the whole broker in the topic explorer")
                .translation("config.mineqtt.acl_explorer_broker_wide")
                .define("explorerBrokerWide", StaticDefaults.DEFAULT_ACL_EXPLORER_BROKER_WIDE);

        BUILDER.pop();

        BUILDER.comment("Block Placer (the block emitter)").translation("config.mineqtt.section.block_placer").push("blockPlacer");

        BLOCK_PLACER_ENABLED = BUILDER
                .comment("Allow the Block Placer to place blocks from MQTT messages")
                .translation("config.mineqtt.block_placer_enabled")
                .define("enabled", StaticDefaults.DEFAULT_BLOCK_PLACER_ENABLED);

        BLOCK_PLACER_PLAYERS_MAY_CONFIGURE = BUILDER
                .comment("Whether players who may configure a Block Placer may set its block and raw link; operators always may.",
                        "TRUE, FALSE, or AUTO (true on integrated, false on dedicated servers)")
                .translation("config.mineqtt.block_placer_players_may_configure")
                .defineEnum("playersMayConfigure", StaticDefaults.DEFAULT_BLOCK_PLACER_PLAYERS_MAY_CONFIGURE);

        BLOCK_PLACER_CONSUME_ITEMS = BUILDER
                .comment("A placed block costs its item from the container behind the Block Placer; clearing returns it")
                .translation("config.mineqtt.block_placer_consume_items")
                .define("consumeItems", StaticDefaults.DEFAULT_BLOCK_PLACER_CONSUME_ITEMS);

        BLOCK_PLACER_CONTAINER_UNLOCKS = BUILDER
                .comment("While the Block Placer uses items, any block in the container behind it may be placed, not only the",
                        "#mineqtt:placeable ones; blocks with block entity data, TNT, fluids, fire, portals, unbreakable and two-part",
                        "blocks never are")
                .translation("config.mineqtt.block_placer_container_unlocks")
                .define("containerUnlocks", StaticDefaults.DEFAULT_BLOCK_PLACER_CONTAINER_UNLOCKS);

        BUILDER.pop();

        BUILDER.comment("Block Observer (the block sensor)").translation("config.mineqtt.section.block_observer").push("blockObserver");

        BLOCK_OBSERVER_EXPORT_BLOCK_ENTITY_DATA = BUILDER
                .comment("Include the observed block's block-entity data (chest or sign contents) in the observer's full raw payload.",
                        "Only when the observer and its target share one claim, or no claim mod is installed; never in Home Assistant.")
                .translation("config.mineqtt.block_observer_export_block_entity_data")
                .define("exportBlockEntityData", StaticDefaults.DEFAULT_BLOCK_OBSERVER_EXPORT_BLOCK_ENTITY_DATA);

        BUILDER.pop();

        BUILDER.comment("Limits").translation("config.mineqtt.section.limits").push("limits");

        LIMITS_MAX_DEVICES_PER_PLAYER = BUILDER
                .comment("Maximum number of MQTT devices a player may own, 0 for unlimited")
                .translation("config.mineqtt.limits_max_devices_per_player")
                .defineInRange("maxDevicesPerPlayer", StaticDefaults.DEFAULT_LIMITS_MAX_DEVICES_PER_PLAYER, 0, Integer.MAX_VALUE);

        LIMITS_MAX_WIRES_PER_PLAYER = BUILDER
                .comment("Maximum number of wires (Configurator links) a player may own, 0 for unlimited; wires made from the console count for nobody")
                .translation("config.mineqtt.limits_max_wires_per_player")
                .defineInRange("maxWiresPerPlayer", StaticDefaults.DEFAULT_LIMITS_MAX_WIRES_PER_PLAYER, 0, Integer.MAX_VALUE);

        LIMITS_MAX_ROOMS_PER_PLAYER = BUILDER
                .comment("Maximum number of rooms a player may own, 0 for unlimited; operators are not limited, and a world holds at most "
                        + StaticDefaults.MAX_ROOMS_PER_WORLD + " rooms")
                .translation("config.mineqtt.limits_max_rooms_per_player")
                .defineInRange("maxRoomsPerPlayer", StaticDefaults.DEFAULT_LIMITS_MAX_ROOMS_PER_PLAYER, 0, StaticDefaults.MAX_ROOMS_PER_WORLD);

        BUILDER.pop();

        BUILDER.comment("Rooms: boxes that name the Home Assistant area of the devices inside").translation("config.mineqtt.section.rooms").push("rooms");

        ROOMS_MAX_SIZE = BUILDER
                .comment("The longest side of a room, in blocks")
                .translation("config.mineqtt.rooms_max_size")
                .defineInRange("maxSize", StaticDefaults.DEFAULT_ROOMS_MAX_SIZE, StaticDefaults.MIN_ROOMS_MAX_SIZE, StaticDefaults.MAX_ROOMS_MAX_SIZE);

        BUILDER.pop();

        BUILDER.comment("Home Assistant twin: blocks that mirror and switch Home Assistant entities").translation("config.mineqtt.section.twin").push("twin");

        TWIN_STATESTREAM_BASE = BUILDER
                .comment("The base_topic of Home Assistant's mqtt_statestream. Players may not publish under it, and only mineqtt.admin may listen there.",
                        "Must not overlap the discovery prefix, the base topic or mineqtt/shared. Empty: the twin reads nothing.")
                .translation("config.mineqtt.twin_statestream_base")
                .define("statestreamBase", StaticDefaults.DEFAULT_TWIN_STATESTREAM_BASE, NeoForgeConfigHandler::isValidStatestreamBase);

        TWIN_ALLOWED_ENTITIES = BUILDER
                .comment("Home Assistant entities the twin may read and bind, as globs over the entity id (* any run, ? one character),",
                        "e.g. light.*, sensor.outdoor_temperature. Operators do not bypass it. Empty turns the twin off.",
                        StaticDefaults.AUTO_ENTRY + " alone: every entity on integrated servers, none on dedicated servers.")
                .translation("config.mineqtt.twin_allowed_entities")
                .defineListAllowEmpty("allowedEntities", StaticDefaults.DEFAULT_TWIN_ALLOWED_ENTITIES, () -> "", NeoForgeConfigHandler::isString);

        TWIN_HA_ACTION_ALLOWLIST = BUILDER
                .comment("Home Assistant domains Minecraft may control. The control blueprint's allowed_domains and its labels gate them again in Home Assistant.")
                .translation("config.mineqtt.twin_ha_action_allowlist")
                .defineListAllowEmpty("haActionAllowlist", StaticDefaults.DEFAULT_TWIN_HA_ACTION_ALLOWLIST, () -> "", NeoForgeConfigHandler::isString);

        TWIN_MAX_REQUESTS_PER_SECOND = BUILDER
                .comment("Home Assistant requests per second for the whole server")
                .translation("config.mineqtt.twin_max_requests_per_second")
                .defineInRange("maxRequestsPerSecond", StaticDefaults.DEFAULT_TWIN_MAX_REQUESTS_PER_SECOND, 1, StaticDefaults.MAX_TWIN_REQUESTS_PER_SECOND);

        TWIN_CATALOG = BUILDER
                .comment("Read the entity catalog the MineQTT catalog blueprint publishes under the statestream base")
                .translation("config.mineqtt.twin_catalog")
                .define("catalog", StaticDefaults.DEFAULT_TWIN_CATALOG);

        TWIN_CATALOG_MAX = BUILDER
                .comment("Home Assistant entities the twin keeps at most")
                .translation("config.mineqtt.twin_catalog_max")
                .defineInRange("catalogMax", StaticDefaults.DEFAULT_TWIN_CATALOG_MAX, 1, StaticDefaults.MAX_TWIN_CATALOG_MAX);

        BUILDER.pop();

        BUILDER.comment("World telemetry on the bridge device and what is published about players").translation("config.mineqtt.section.telemetry").push("telemetry");

        TELEMETRY_ENABLED = BUILDER
                .comment("Publish the world's time, weather, day and server load on the bridge device; off keeps only the player count and drop counters")
                .translation("config.mineqtt.telemetry_enabled")
                .define("enabled", StaticDefaults.DEFAULT_TELEMETRY_ENABLED);

        TELEMETRY_INTERVAL_SECONDS = BUILDER
                .comment("At most this often, in seconds, a telemetry change other than players, day or weather is published")
                .translation("config.mineqtt.telemetry_interval_seconds")
                .defineInRange("intervalSeconds", StaticDefaults.DEFAULT_TELEMETRY_INTERVAL_SECONDS, StaticDefaults.MIN_TELEMETRY_INTERVAL_SECONDS,
                        StaticDefaults.MAX_TELEMETRY_INTERVAL_SECONDS);

        // These two only ever narrow what is published about players, so NeoForge keeps an invalid value as written
        // instead of correcting it to the default, and loadConfig narrows it (ConfigValidation): see isPresent.
        TELEMETRY_PLAYERS = BUILDER
                .comment("The most this server publishes about a player who consents: OFF (counts only), PRESENCE (name and a presence device)",
                        "or FULL (also health, dimension and position). Nothing is published about a player without their consent.",
                        "Any other value counts as OFF until it is fixed.")
                .translation("config.mineqtt.telemetry_players")
                .defineEnum("players", StaticDefaults.DEFAULT_TELEMETRY_PLAYERS, EnumGetMethod.NAME_IGNORECASE, NeoForgeConfigHandler::isPresent);

        TELEMETRY_PUBLISH_PLAYER_NAMES = BUILDER
                .comment("false publishes counts only: no player names and no presence devices, whatever players consent to.",
                        "Any value other than true or false counts as false until it is fixed.")
                .translation("config.mineqtt.telemetry_publish_player_names")
                .define("publishPlayerNames", () -> StaticDefaults.DEFAULT_TELEMETRY_PUBLISH_PLAYER_NAMES, NeoForgeConfigHandler::isPresent);

        TELEMETRY_ALLOW_BROADCAST = BUILDER
                .comment("Let Home Assistant (anyone who may write the bridge's request topic) show a line of text in chat, prefixed [MQTT]")
                .translation("config.mineqtt.telemetry_allow_broadcast")
                .define("allowBroadcast", StaticDefaults.DEFAULT_TELEMETRY_ALLOW_BROADCAST);

        TELEMETRY_MAX_PRESENCE_DEVICES = BUILDER
                .comment("Presence devices at most; beyond it no new player gets one")
                .translation("config.mineqtt.telemetry_max_presence_devices")
                .defineInRange("maxPresenceDevices", StaticDefaults.DEFAULT_TELEMETRY_MAX_PRESENCE_DEVICES, StaticDefaults.MIN_TELEMETRY_MAX_PRESENCE_DEVICES,
                        StaticDefaults.MAX_TELEMETRY_MAX_PRESENCE_DEVICES);

        TELEMETRY_FORGET_AFTER_DAYS = BUILDER
                .comment("Days a player may stay away before their presence device and name are forgotten, 0 for never; their consent is kept")
                .translation("config.mineqtt.telemetry_forget_after_days")
                .defineInRange("forgetAfterDays", StaticDefaults.DEFAULT_TELEMETRY_FORGET_AFTER_DAYS, 0, StaticDefaults.MAX_TELEMETRY_FORGET_AFTER_DAYS);

        BUILDER.pop();

        BUILDER.comment("Dedicated server").translation("config.mineqtt.section.server").push("server");

        SERVER_KEEP_TICKING_WHEN_EMPTY = BUILDER
                .comment("Keep ticking with nobody online, overriding pause-when-empty-seconds in server.properties, so repeaters,",
                        "pistons and light keep running. Off, MQTT is still handled while the server is paused.")
                .translation("config.mineqtt.server_keep_ticking_when_empty")
                .define("keepTickingWhenEmpty", StaticDefaults.DEFAULT_SERVER_KEEP_TICKING_WHEN_EMPTY);

        BUILDER.pop();

        BUILDER.comment("Migration records, maintained by MineQTT").translation("config.mineqtt.section.legacy").push("legacy");

        LEGACY_V1_STATUS_TOPIC = BUILDER
                .comment("The status topic of the v1 config; its retained Offline message is cleared once")
                .translation("config.mineqtt.legacy_v1_status_topic")
                .define("v1StatusTopic", StaticDefaults.DEFAULT_LEGACY_V1_STATUS_TOPIC);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    /**
     * Rewrites a v1 {@value #FILE_NAME} (no {@code configVersion}, but a v1-only table) as v2 and keeps the original
     * as {@value #FILE_NAME}{@value #V1_BACKUP_SUFFIX}, unless a backup from an earlier migration already exists. Must run before the config is registered: NeoForge drops every
     * key the spec does not define while loading, which would lose the v1 values.
     */
    public static void migrateV1File(Path file) {
        if (!Files.isRegularFile(file)) return;
        CommentedConfig v1File;
        try (Reader reader = Files.newBufferedReader(file)) {
            v1File = new TomlParser().parse(reader);
        } catch (IOException | ParsingException e) {
            // NeoForge reports the broken file itself and recreates it from the defaults.
            LOGGER.warn("Could not read {} to check for a v1 configuration: {}", file, e.getMessage());
            return;
        }
        // Without a v1 table it is a hand-written v2 file; NeoForge adds the missing configVersion itself.
        if (v1File.contains("configVersion") || V1_ONLY_TABLES.stream().noneMatch(v1File::contains)) return;

        List<String> notes = ConfigMigration.applyV1(readV1(v1File));
        List<String> problems = MineQTTConfig.sanitize();
        Path backup = file.resolveSibling(FILE_NAME + V1_BACKUP_SUFFIX);
        boolean backedUp;
        try {
            backedUp = ConfigMigration.backUpV1(file, backup);
        } catch (IOException e) {
            LOGGER.error("Failed to back up v1 configuration {}, it is not migrated", file, e);
            return;
        }
        CommentedConfig v2File = TomlFormat.newConfig(LinkedHashMap::new);
        writeValues(new ValueSink() {
            @Override
            public <T> void put(ModConfigSpec.ConfigValue<T> key, T value) {
                v2File.set(key.getPath(), value);
            }
        });
        // Adds the comments, so NeoForge finds the file correct and does not rewrite or back it up again.
        SPEC.correct(v2File);
        new TomlWriter().write(v2File, file, WritingMode.REPLACE_ATOMIC);
        if (backedUp) {
            LOGGER.info("Migrated v1 configuration {} to v2, the original is kept as {}", file, backup);
        } else {
            LOGGER.warn("Migrated v1 configuration {} to v2 without a new backup, {} from an earlier migration is kept", file, backup);
        }
        notes.forEach(note -> LOGGER.warn("Config: {}", note));
        problems.forEach(problem -> LOGGER.warn("Config: {}", problem));
    }

    @Override
    public void loadConfig() {
        // NeoForge loads COMMON configs after mod construction. Keep the shared
        // defaults until ModConfigEvent.Loading applies the loaded values.
        if (!SPEC.isLoaded()) return;
        MineQTTConfig.configVersion = CONFIG_VERSION.get();
        MineQTTConfig.broker = BROKER.get();
        MineQTTConfig.protocol = ProtocolVersion.parse(PROTOCOL.get());
        MineQTTConfig.keepAliveSec = KEEP_ALIVE_SEC.get();
        MineQTTConfig.username = USERNAME.get();
        MineQTTConfig.password = PASSWORD.get();
        MineQTTConfig.bridgeId = BRIDGE_ID.get();
        MineQTTConfig.baseTopic = BASE_TOPIC.get();
        MineQTTConfig.discoveryPrefix = DISCOVERY_PREFIX.get();
        MineQTTConfig.haBirthTopic = HA_BIRTH_TOPIC.get();
        MineQTTConfig.tlsCaFile = TLS_CA_FILE.get();
        MineQTTConfig.tlsInsecureSkipVerify = TLS_INSECURE_SKIP_VERIFY.get();
        MineQTTConfig.haExpose = HA_EXPOSE.get();
        MineQTTConfig.aclRawAllow = List.copyOf(ACL_RAW_ALLOW.get());
        MineQTTConfig.aclOpsBypassAllowlist = ACL_OPS_BYPASS_ALLOWLIST.get();
        MineQTTConfig.aclExplorerBrokerWide = ACL_EXPLORER_BROKER_WIDE.get();
        MineQTTConfig.blockPlacerEnabled = BLOCK_PLACER_ENABLED.get();
        MineQTTConfig.blockPlacerPlayersMayConfigure = BLOCK_PLACER_PLAYERS_MAY_CONFIGURE.get();
        MineQTTConfig.blockPlacerConsumeItems = BLOCK_PLACER_CONSUME_ITEMS.get();
        MineQTTConfig.blockPlacerContainerUnlocks = BLOCK_PLACER_CONTAINER_UNLOCKS.get();
        MineQTTConfig.blockObserverExportBlockEntityData = BLOCK_OBSERVER_EXPORT_BLOCK_ENTITY_DATA.get();
        MineQTTConfig.limitsMaxDevicesPerPlayer = LIMITS_MAX_DEVICES_PER_PLAYER.get();
        MineQTTConfig.limitsMaxWiresPerPlayer = LIMITS_MAX_WIRES_PER_PLAYER.get();
        MineQTTConfig.limitsMaxRoomsPerPlayer = LIMITS_MAX_ROOMS_PER_PLAYER.get();
        MineQTTConfig.roomsMaxSize = ROOMS_MAX_SIZE.get();
        MineQTTConfig.twinStatestreamBase = TWIN_STATESTREAM_BASE.get();
        MineQTTConfig.twinAllowedEntities = List.copyOf(TWIN_ALLOWED_ENTITIES.get());
        MineQTTConfig.twinHaActionAllowlist = List.copyOf(TWIN_HA_ACTION_ALLOWLIST.get());
        MineQTTConfig.twinMaxRequestsPerSecond = TWIN_MAX_REQUESTS_PER_SECOND.get();
        MineQTTConfig.twinCatalog = TWIN_CATALOG.get();
        MineQTTConfig.twinCatalogMax = TWIN_CATALOG_MAX.get();
        MineQTTConfig.telemetryEnabled = TELEMETRY_ENABLED.get();
        MineQTTConfig.telemetryIntervalSeconds = TELEMETRY_INTERVAL_SECONDS.get();
        List<String> narrowed = new ArrayList<>();
        MineQTTConfig.telemetryPlayers = ConfigValidation.telemetryPlayersOrNarrowest(rawTelemetryPlayers(), narrowed);
        MineQTTConfig.telemetryPublishPlayerNames = ConfigValidation.publishPlayerNamesOrNarrowest(
                ((ModConfigSpec.ConfigValue<?>) TELEMETRY_PUBLISH_PLAYER_NAMES).get(), narrowed);
        if (!narrowed.isEmpty()) {
            // Held in memory, so get() and the config screen read the narrow value; the file keeps its text until the next save.
            TELEMETRY_PLAYERS.set(MineQTTConfig.telemetryPlayers);
            TELEMETRY_PUBLISH_PLAYER_NAMES.set(MineQTTConfig.telemetryPublishPlayerNames);
            narrowed.forEach(problem -> LOGGER.warn("Config: {}", problem));
        }
        MineQTTConfig.telemetryAllowBroadcast = TELEMETRY_ALLOW_BROADCAST.get();
        MineQTTConfig.telemetryMaxPresenceDevices = TELEMETRY_MAX_PRESENCE_DEVICES.get();
        MineQTTConfig.telemetryForgetAfterDays = TELEMETRY_FORGET_AFTER_DAYS.get();
        MineQTTConfig.serverKeepTickingWhenEmpty = SERVER_KEEP_TICKING_WHEN_EMPTY.get();
        MineQTTConfig.legacyV1StatusTopic = LEGACY_V1_STATUS_TOPIC.get();
        MineQTTConfig.sanitize().forEach(problem -> LOGGER.warn("Config: {}", problem));
    }

    @Override
    public void saveConfig() {
        if (!SPEC.isLoaded()) {
            LOGGER.warn("Configuration {} is not loaded yet, nothing is saved", configPath());
            return;
        }
        writeValues(ModConfigSpec.ConfigValue::set);
        // Writes the file and fires ModConfigEvent.Reloading, which reads the values back.
        SPEC.save();
    }

    @Override
    public boolean isConfigValid() {
        return ConfigValidation.isValidBroker(MineQTTConfig.broker);
    }

    @Override
    public void resetToDefaults() {
        MineQTTConfig.resetToDefaults();
        saveConfig();
    }

    @Override
    public Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }

    private static ConfigMigration.V1 readV1(UnmodifiableConfig v1File) {
        return new ConfigMigration.V1(v1String(v1File, "connection.brokerUrl"), v1String(v1File, "connection.clientId"),
                v1String(v1File, "connection.username"), v1String(v1File, "connection.password"),
                v1Int(v1File, "connection.keepAlive"), v1String(v1File, "topics.baseTopic"), v1String(v1File, "topics.statusTopic"));
    }

    /**
     * A statestream base NeoForge keeps as written: empty, or a valid topic once one trailing {@code /} is dropped. The
     * overlap with the other topics is judged by {@link MineQTTConfig#sanitize()}, which can see them all.
     */
    private static boolean isValidStatestreamBase(Object value) {
        if (!(value instanceof String base)) return false;
        String normalized = ConfigValidation.normalizeStatestreamBase(base);
        return normalized.isEmpty() || ConfigValidation.isValidConfigTopic(normalized);
    }

    /**
     * The element check of the twin's lists. NeoForge drops invalid elements, but resets a list whose elements are all
     * invalid to its default, and the default of {@code allowedEntities} is {@code AUTO}: every entity on an integrated
     * server. So NeoForge only checks the type, and {@link MineQTTConfig#sanitize()} drops invalid entries, which only
     * ever narrows the list.
     */
    private static boolean isString(Object value) {
        return value instanceof String;
    }

    /**
     * The validator of {@code telemetry.players} and {@code telemetry.publishPlayerNames}: every value present in the file
     * passes, so NeoForge never corrects one to its default (the widest setting) at load or at a reload of the edited
     * file; a missing key is still added with its default. {@link ConfigValidation} judges the value in
     * {@link #loadConfig()} and narrows an invalid one.
     */
    private static boolean isPresent(Object value) {
        return value != null;
    }

    /**
     * {@code telemetry.players} as {@link ConfigValidation#telemetryPlayersOrNarrowest} takes it: the setting NightConfig
     * reads (a name in any case), or {@link #UNREADABLE} for a string that names none or a value of another type, which
     * NightConfig refuses with an exception.
     */
    private static Object rawTelemetryPlayers() {
        try {
            return TELEMETRY_PLAYERS.get();
        } catch (RuntimeException e) {
            return UNREADABLE;
        }
    }

    private static String v1String(UnmodifiableConfig v1File, String path) {
        return v1File.get(path) instanceof String value ? value : null;
    }

    private static Integer v1Int(UnmodifiableConfig v1File, String path) {
        return v1File.get(path) instanceof Number value && value.longValue() == value.intValue() ? value.intValue() : null;
    }

    /** Every spec value from {@link MineQTTConfig}, in spec order. */
    private static void writeValues(ValueSink sink) {
        sink.put(CONFIG_VERSION, StaticDefaults.CONFIG_VERSION);
        sink.put(BROKER, MineQTTConfig.broker);
        sink.put(PROTOCOL, MineQTTConfig.protocol.configValue());
        sink.put(KEEP_ALIVE_SEC, MineQTTConfig.keepAliveSec);
        sink.put(USERNAME, MineQTTConfig.username);
        sink.put(PASSWORD, MineQTTConfig.password);
        sink.put(BRIDGE_ID, MineQTTConfig.bridgeId);
        sink.put(BASE_TOPIC, MineQTTConfig.baseTopic);
        sink.put(DISCOVERY_PREFIX, MineQTTConfig.discoveryPrefix);
        sink.put(HA_BIRTH_TOPIC, MineQTTConfig.haBirthTopic);
        sink.put(TLS_CA_FILE, MineQTTConfig.tlsCaFile);
        sink.put(TLS_INSECURE_SKIP_VERIFY, MineQTTConfig.tlsInsecureSkipVerify);
        sink.put(HA_EXPOSE, MineQTTConfig.haExpose);
        sink.put(ACL_RAW_ALLOW, MineQTTConfig.aclRawAllow);
        sink.put(ACL_OPS_BYPASS_ALLOWLIST, MineQTTConfig.aclOpsBypassAllowlist);
        sink.put(ACL_EXPLORER_BROKER_WIDE, MineQTTConfig.aclExplorerBrokerWide);
        sink.put(BLOCK_PLACER_ENABLED, MineQTTConfig.blockPlacerEnabled);
        sink.put(BLOCK_PLACER_PLAYERS_MAY_CONFIGURE, MineQTTConfig.blockPlacerPlayersMayConfigure);
        sink.put(BLOCK_PLACER_CONSUME_ITEMS, MineQTTConfig.blockPlacerConsumeItems);
        sink.put(BLOCK_PLACER_CONTAINER_UNLOCKS, MineQTTConfig.blockPlacerContainerUnlocks);
        sink.put(BLOCK_OBSERVER_EXPORT_BLOCK_ENTITY_DATA, MineQTTConfig.blockObserverExportBlockEntityData);
        sink.put(LIMITS_MAX_DEVICES_PER_PLAYER, MineQTTConfig.limitsMaxDevicesPerPlayer);
        sink.put(LIMITS_MAX_WIRES_PER_PLAYER, MineQTTConfig.limitsMaxWiresPerPlayer);
        sink.put(LIMITS_MAX_ROOMS_PER_PLAYER, MineQTTConfig.limitsMaxRoomsPerPlayer);
        sink.put(ROOMS_MAX_SIZE, MineQTTConfig.roomsMaxSize);
        sink.put(TWIN_STATESTREAM_BASE, MineQTTConfig.twinStatestreamBase);
        sink.put(TWIN_ALLOWED_ENTITIES, MineQTTConfig.twinAllowedEntities);
        sink.put(TWIN_HA_ACTION_ALLOWLIST, MineQTTConfig.twinHaActionAllowlist);
        sink.put(TWIN_MAX_REQUESTS_PER_SECOND, MineQTTConfig.twinMaxRequestsPerSecond);
        sink.put(TWIN_CATALOG, MineQTTConfig.twinCatalog);
        sink.put(TWIN_CATALOG_MAX, MineQTTConfig.twinCatalogMax);
        sink.put(TELEMETRY_ENABLED, MineQTTConfig.telemetryEnabled);
        sink.put(TELEMETRY_INTERVAL_SECONDS, MineQTTConfig.telemetryIntervalSeconds);
        sink.put(TELEMETRY_PLAYERS, MineQTTConfig.telemetryPlayers);
        sink.put(TELEMETRY_PUBLISH_PLAYER_NAMES, MineQTTConfig.telemetryPublishPlayerNames);
        sink.put(TELEMETRY_ALLOW_BROADCAST, MineQTTConfig.telemetryAllowBroadcast);
        sink.put(TELEMETRY_MAX_PRESENCE_DEVICES, MineQTTConfig.telemetryMaxPresenceDevices);
        sink.put(TELEMETRY_FORGET_AFTER_DAYS, MineQTTConfig.telemetryForgetAfterDays);
        sink.put(SERVER_KEEP_TICKING_WHEN_EMPTY, MineQTTConfig.serverKeepTickingWhenEmpty);
        sink.put(LEGACY_V1_STATUS_TOPIC, MineQTTConfig.legacyV1StatusTopic);
    }

    private interface ValueSink {
        <T> void put(ModConfigSpec.ConfigValue<T> key, T value);
    }
}

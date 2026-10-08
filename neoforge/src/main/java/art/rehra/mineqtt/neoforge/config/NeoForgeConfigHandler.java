package art.rehra.mineqtt.neoforge.config;

import art.rehra.mineqtt.config.AutoBoolean;
import art.rehra.mineqtt.config.ConfigHandler;
import art.rehra.mineqtt.config.ConfigMigration;
import art.rehra.mineqtt.config.ConfigValidation;
import art.rehra.mineqtt.config.HaExpose;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.config.StaticDefaults;
import art.rehra.mineqtt.mqtt.core.ProtocolVersion;
import com.electronwill.nightconfig.core.CommentedConfig;
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
import java.util.LinkedHashMap;
import java.util.List;

public class NeoForgeConfigHandler implements ConfigHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("MineQTT-Config");
    public static final String FILE_NAME = "mineqtt.toml";
    private static final String V1_BACKUP_SUFFIX = ".v1.bak";
    /** Tables only a v1 file has; v2 keeps just [goal] of the v1 tables. */
    private static final List<String> V1_ONLY_TABLES = List.of("connection", "topics");
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
    public static final ModConfigSpec.BooleanValue ALLOW_ITEM_NETHER_PORTAL_TELEPORT;

    public static final ModConfigSpec.ConfigValue<String> TLS_CA_FILE;
    public static final ModConfigSpec.BooleanValue TLS_INSECURE_SKIP_VERIFY;

    public static final ModConfigSpec.EnumValue<HaExpose> HA_EXPOSE;

    public static final ModConfigSpec.ConfigValue<List<? extends String>> ACL_RAW_ALLOW;
    public static final ModConfigSpec.EnumValue<AutoBoolean> ACL_OPS_BYPASS_ALLOWLIST;
    public static final ModConfigSpec.BooleanValue ACL_EXPLORER_BROKER_WIDE;

    public static final ModConfigSpec.BooleanValue BLOCK_PLACER_ENABLED;

    public static final ModConfigSpec.IntValue LIMITS_MAX_DEVICES_PER_PLAYER;

    public static final ModConfigSpec.ConfigValue<String> LEGACY_V1_STATUS_TOPIC;

    public static final ModConfigSpec.IntValue GOAL_X;
    public static final ModConfigSpec.IntValue GOAL_Y;
    public static final ModConfigSpec.IntValue GOAL_Z;
    public static final ModConfigSpec.BooleanValue ZOMBIE_GOAL_ENABLED;

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

        ALLOW_ITEM_NETHER_PORTAL_TELEPORT = BUILDER
                .comment("Allow items to teleport through nether portals")
                .translation("config.mineqtt.allow_item_nether_portal_teleport")
                .define("allowItemNetherPortalTeleport", StaticDefaults.DEFAULT_ALLOW_ITEM_NETHER_PORTAL_TELEPORT);

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

        BUILDER.pop();

        BUILDER.comment("Limits").translation("config.mineqtt.section.limits").push("limits");

        LIMITS_MAX_DEVICES_PER_PLAYER = BUILDER
                .comment("Maximum number of MQTT devices a player may own, 0 for unlimited")
                .translation("config.mineqtt.limits_max_devices_per_player")
                .defineInRange("maxDevicesPerPlayer", StaticDefaults.DEFAULT_LIMITS_MAX_DEVICES_PER_PLAYER, 0, Integer.MAX_VALUE);

        BUILDER.pop();

        BUILDER.comment("Migration records, maintained by MineQTT").translation("config.mineqtt.section.legacy").push("legacy");

        LEGACY_V1_STATUS_TOPIC = BUILDER
                .comment("The status topic of the v1 config; its retained Offline message is cleared once")
                .translation("config.mineqtt.legacy_v1_status_topic")
                .define("v1StatusTopic", StaticDefaults.DEFAULT_LEGACY_V1_STATUS_TOPIC);

        BUILDER.pop();

        BUILDER.comment("Goal Coordinates").translation("config.mineqtt.category.goal").push("goal");

        GOAL_X = BUILDER
                .comment("X coordinate of the global goal")
                .translation("config.mineqtt.goal_x")
                .defineInRange("goalX", StaticDefaults.DEFAULT_GOAL_X, -30000000, 30000000);

        GOAL_Y = BUILDER
                .comment("Y coordinate of the global goal")
                .translation("config.mineqtt.goal_y")
                .defineInRange("goalY", StaticDefaults.DEFAULT_GOAL_Y, -2048, 2048);

        GOAL_Z = BUILDER
                .comment("Z coordinate of the global goal")
                .translation("config.mineqtt.goal_z")
                .defineInRange("goalZ", StaticDefaults.DEFAULT_GOAL_Z, -30000000, 30000000);

        ZOMBIE_GOAL_ENABLED = BUILDER
                .comment("Whether the global zombie goal is enabled")
                .translation("config.mineqtt.zombie_goal_enabled")
                .define("zombieGoalEnabled", StaticDefaults.DEFAULT_ZOMBIE_GOAL_ENABLED);

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
        MineQTTConfig.allowItemNetherPortalTeleport = ALLOW_ITEM_NETHER_PORTAL_TELEPORT.get();
        MineQTTConfig.tlsCaFile = TLS_CA_FILE.get();
        MineQTTConfig.tlsInsecureSkipVerify = TLS_INSECURE_SKIP_VERIFY.get();
        MineQTTConfig.haExpose = HA_EXPOSE.get();
        MineQTTConfig.aclRawAllow = List.copyOf(ACL_RAW_ALLOW.get());
        MineQTTConfig.aclOpsBypassAllowlist = ACL_OPS_BYPASS_ALLOWLIST.get();
        MineQTTConfig.aclExplorerBrokerWide = ACL_EXPLORER_BROKER_WIDE.get();
        MineQTTConfig.blockPlacerEnabled = BLOCK_PLACER_ENABLED.get();
        MineQTTConfig.limitsMaxDevicesPerPlayer = LIMITS_MAX_DEVICES_PER_PLAYER.get();
        MineQTTConfig.legacyV1StatusTopic = LEGACY_V1_STATUS_TOPIC.get();
        MineQTTConfig.goalX = GOAL_X.get();
        MineQTTConfig.goalY = GOAL_Y.get();
        MineQTTConfig.goalZ = GOAL_Z.get();
        MineQTTConfig.zombieGoalEnabled = ZOMBIE_GOAL_ENABLED.get();
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
                v1Int(v1File, "connection.keepAlive"), v1String(v1File, "topics.baseTopic"), v1String(v1File, "topics.statusTopic"),
                v1Bool(v1File, "connection.allowItemNetherPortalTeleport"), v1Int(v1File, "goal.goalX"),
                v1Int(v1File, "goal.goalY"), v1Int(v1File, "goal.goalZ"), v1Bool(v1File, "goal.zombieGoalEnabled"));
    }

    private static String v1String(UnmodifiableConfig v1File, String path) {
        return v1File.get(path) instanceof String value ? value : null;
    }

    private static Integer v1Int(UnmodifiableConfig v1File, String path) {
        return v1File.get(path) instanceof Number value && value.longValue() == value.intValue() ? value.intValue() : null;
    }

    private static Boolean v1Bool(UnmodifiableConfig v1File, String path) {
        return v1File.get(path) instanceof Boolean value ? value : null;
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
        sink.put(ALLOW_ITEM_NETHER_PORTAL_TELEPORT, MineQTTConfig.allowItemNetherPortalTeleport);
        sink.put(TLS_CA_FILE, MineQTTConfig.tlsCaFile);
        sink.put(TLS_INSECURE_SKIP_VERIFY, MineQTTConfig.tlsInsecureSkipVerify);
        sink.put(HA_EXPOSE, MineQTTConfig.haExpose);
        sink.put(ACL_RAW_ALLOW, MineQTTConfig.aclRawAllow);
        sink.put(ACL_OPS_BYPASS_ALLOWLIST, MineQTTConfig.aclOpsBypassAllowlist);
        sink.put(ACL_EXPLORER_BROKER_WIDE, MineQTTConfig.aclExplorerBrokerWide);
        sink.put(BLOCK_PLACER_ENABLED, MineQTTConfig.blockPlacerEnabled);
        sink.put(LIMITS_MAX_DEVICES_PER_PLAYER, MineQTTConfig.limitsMaxDevicesPerPlayer);
        sink.put(LEGACY_V1_STATUS_TOPIC, MineQTTConfig.legacyV1StatusTopic);
        sink.put(GOAL_X, MineQTTConfig.goalX);
        sink.put(GOAL_Y, MineQTTConfig.goalY);
        sink.put(GOAL_Z, MineQTTConfig.goalZ);
        sink.put(ZOMBIE_GOAL_ENABLED, MineQTTConfig.zombieGoalEnabled);
    }

    private interface ValueSink {
        <T> void put(ModConfigSpec.ConfigValue<T> key, T value);
    }
}

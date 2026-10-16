package art.rehra.mineqtt.config;

import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import art.rehra.mineqtt.mqtt.core.BrokerSettings;
import art.rehra.mineqtt.mqtt.core.ProtocolVersion;
import art.rehra.mineqtt.twin.core.EntityGlobs;
import art.rehra.mineqtt.twin.core.HaDomains;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The loaded configuration (schema v2, design §9.6). Each loader's {@link ConfigHandler} fills these fields from its
 * own file format; every default comes from {@link StaticDefaults}.
 */
public class MineQTTConfig {
    public static int configVersion;

    // MQTT connection
    public static String broker;
    public static ProtocolVersion protocol;
    public static int keepAliveSec;
    public static String username;
    public static String password;
    public static String tlsCaFile;
    public static boolean tlsInsecureSkipVerify;

    // Topics and Home Assistant
    public static String bridgeId;
    public static String baseTopic;
    public static String discoveryPrefix;
    public static String haBirthTopic;
    public static HaExpose haExpose;

    // Topic ACL, block placer and limits
    public static List<String> aclRawAllow;
    public static AutoBoolean aclOpsBypassAllowlist;
    public static boolean aclExplorerBrokerWide;
    public static boolean blockPlacerEnabled;
    public static int limitsMaxDevicesPerPlayer;
    public static int limitsMaxWiresPerPlayer;

    // World telemetry, players, the Block Placer and Observer, rooms (M4 plan D16)
    public static boolean telemetryEnabled;
    public static int telemetryIntervalSeconds;
    public static TelemetryPlayers telemetryPlayers;
    public static boolean telemetryPublishPlayerNames;
    public static boolean telemetryAllowBroadcast;
    public static int telemetryMaxPresenceDevices;
    public static int telemetryForgetAfterDays;
    public static AutoBoolean blockPlacerPlayersMayConfigure;
    public static boolean blockPlacerConsumeItems;
    public static boolean blockPlacerContainerUnlocks;
    public static boolean blockObserverExportBlockEntityData;
    public static boolean entityObserverExportEntityData;
    public static int roomsMaxSize;
    public static int limitsMaxRoomsPerPlayer;

    // Home Assistant twin (M3 plan D13)
    /** Normalized by {@link #sanitize()} (no trailing {@code /}); empty means no statestream base. */
    public static String twinStatestreamBase;
    /** Globs, or the single entry {@link StaticDefaults#AUTO_ENTRY}; {@link #twinAllowedEntities(boolean)} resolves them. */
    public static List<String> twinAllowedEntities;
    public static List<String> twinHaActionAllowlist;
    public static int twinMaxRequestsPerSecond;
    public static boolean twinCatalog;
    public static int twinCatalogMax;

    // Dedicated server
    public static boolean serverKeepTickingWhenEmpty;

    // Migration record
    public static String legacyV1StatusTopic;

    static {
        resetToDefaults();
    }

    // Platform-specific config saving/loading will be handled by each platform
    public static void resetToDefaults() {
        configVersion = StaticDefaults.CONFIG_VERSION;
        broker = StaticDefaults.DEFAULT_BROKER;
        protocol = StaticDefaults.DEFAULT_PROTOCOL;
        keepAliveSec = StaticDefaults.DEFAULT_KEEP_ALIVE_SEC;
        username = StaticDefaults.DEFAULT_USERNAME;
        password = StaticDefaults.DEFAULT_PASSWORD;
        tlsCaFile = StaticDefaults.DEFAULT_TLS_CA_FILE;
        tlsInsecureSkipVerify = StaticDefaults.DEFAULT_TLS_INSECURE_SKIP_VERIFY;
        bridgeId = StaticDefaults.DEFAULT_BRIDGE_ID;
        baseTopic = StaticDefaults.DEFAULT_BASE_TOPIC;
        discoveryPrefix = StaticDefaults.DEFAULT_DISCOVERY_PREFIX;
        haBirthTopic = StaticDefaults.DEFAULT_HA_BIRTH_TOPIC;
        haExpose = StaticDefaults.DEFAULT_HA_EXPOSE;
        aclRawAllow = StaticDefaults.DEFAULT_ACL_RAW_ALLOW;
        aclOpsBypassAllowlist = StaticDefaults.DEFAULT_ACL_OPS_BYPASS_ALLOWLIST;
        aclExplorerBrokerWide = StaticDefaults.DEFAULT_ACL_EXPLORER_BROKER_WIDE;
        blockPlacerEnabled = StaticDefaults.DEFAULT_BLOCK_PLACER_ENABLED;
        limitsMaxDevicesPerPlayer = StaticDefaults.DEFAULT_LIMITS_MAX_DEVICES_PER_PLAYER;
        limitsMaxWiresPerPlayer = StaticDefaults.DEFAULT_LIMITS_MAX_WIRES_PER_PLAYER;
        telemetryEnabled = StaticDefaults.DEFAULT_TELEMETRY_ENABLED;
        telemetryIntervalSeconds = StaticDefaults.DEFAULT_TELEMETRY_INTERVAL_SECONDS;
        telemetryPlayers = StaticDefaults.DEFAULT_TELEMETRY_PLAYERS;
        telemetryPublishPlayerNames = StaticDefaults.DEFAULT_TELEMETRY_PUBLISH_PLAYER_NAMES;
        telemetryAllowBroadcast = StaticDefaults.DEFAULT_TELEMETRY_ALLOW_BROADCAST;
        telemetryMaxPresenceDevices = StaticDefaults.DEFAULT_TELEMETRY_MAX_PRESENCE_DEVICES;
        telemetryForgetAfterDays = StaticDefaults.DEFAULT_TELEMETRY_FORGET_AFTER_DAYS;
        blockPlacerPlayersMayConfigure = StaticDefaults.DEFAULT_BLOCK_PLACER_PLAYERS_MAY_CONFIGURE;
        blockPlacerConsumeItems = StaticDefaults.DEFAULT_BLOCK_PLACER_CONSUME_ITEMS;
        blockPlacerContainerUnlocks = StaticDefaults.DEFAULT_BLOCK_PLACER_CONTAINER_UNLOCKS;
        blockObserverExportBlockEntityData = StaticDefaults.DEFAULT_BLOCK_OBSERVER_EXPORT_BLOCK_ENTITY_DATA;
        entityObserverExportEntityData = StaticDefaults.DEFAULT_ENTITY_OBSERVER_EXPORT_ENTITY_DATA;
        roomsMaxSize = StaticDefaults.DEFAULT_ROOMS_MAX_SIZE;
        limitsMaxRoomsPerPlayer = StaticDefaults.DEFAULT_LIMITS_MAX_ROOMS_PER_PLAYER;
        twinStatestreamBase = StaticDefaults.DEFAULT_TWIN_STATESTREAM_BASE;
        twinAllowedEntities = StaticDefaults.DEFAULT_TWIN_ALLOWED_ENTITIES;
        twinHaActionAllowlist = StaticDefaults.DEFAULT_TWIN_HA_ACTION_ALLOWLIST;
        twinMaxRequestsPerSecond = StaticDefaults.DEFAULT_TWIN_MAX_REQUESTS_PER_SECOND;
        twinCatalog = StaticDefaults.DEFAULT_TWIN_CATALOG;
        twinCatalogMax = StaticDefaults.DEFAULT_TWIN_CATALOG_MAX;
        serverKeepTickingWhenEmpty = StaticDefaults.DEFAULT_SERVER_KEEP_TICKING_WHEN_EMPTY;
        legacyV1StatusTopic = StaticDefaults.DEFAULT_LEGACY_V1_STATUS_TOPIC;
    }

    /**
     * Replaces values that cannot be used by their defaults and reports every problem as a log line. A malformed
     * broker URI is kept, so the user can fix the typo, and MQTT stays disabled until then. Lines never contain the
     * password or the username.
     */
    public static List<String> sanitize() {
        List<String> problems = new ArrayList<>();
        if (broker == null) broker = StaticDefaults.DEFAULT_BROKER;
        broker = broker.strip();
        ConfigValidation.brokerError(broker).ifPresent(error -> problems.add("broker: " + error + "; MQTT stays disabled until it is fixed"));
        if (protocol == null) protocol = StaticDefaults.DEFAULT_PROTOCOL;
        if (!ConfigValidation.isValidKeepAlive(keepAliveSec)) {
            problems.add("keepAliveSec: " + keepAliveSec + " is outside 0-65535, using " + StaticDefaults.DEFAULT_KEEP_ALIVE_SEC);
            keepAliveSec = StaticDefaults.DEFAULT_KEEP_ALIVE_SEC;
        }
        if (username == null) username = StaticDefaults.DEFAULT_USERNAME;
        if (password == null) password = StaticDefaults.DEFAULT_PASSWORD;
        if (protocol == ProtocolVersion.MQTT_3_1_1 && username.isBlank() && effectivePassword() != null) {
            problems.add("password: MQTT 3.1.1 cannot send a password without a username; set username too");
        }
        if (tlsCaFile == null) tlsCaFile = StaticDefaults.DEFAULT_TLS_CA_FILE;
        tlsCaFile = tlsCaFile.strip();
        if (!tlsCaFile.isEmpty() && !isReadableFile(tlsCaFile)) {
            problems.add("tls.caFile: " + tlsCaFile + " is not a readable file; TLS connections will fail");
        }
        if (!ConfigValidation.isValidBridgeId(bridgeId)) {
            problems.add("bridgeId: '" + bridgeId + "' must be 1-64 of a-z, 0-9, _ and -; a bridge id is generated per world instead");
            bridgeId = StaticDefaults.DEFAULT_BRIDGE_ID;
        }
        baseTopic = validTopic("baseTopic", baseTopic, StaticDefaults.DEFAULT_BASE_TOPIC, problems);
        Optional<String> baseNamespaceError = ConfigValidation.baseTopicNamespaceError(baseTopic);
        if (baseNamespaceError.isPresent()) {
            problems.add("baseTopic: " + baseNamespaceError.get() + ", using '" + StaticDefaults.DEFAULT_BASE_TOPIC + "'");
            baseTopic = StaticDefaults.DEFAULT_BASE_TOPIC;
        }
        Optional<String> namespaceError = ConfigValidation.bridgeNamespaceError(bridgeId, baseTopic);
        if (namespaceError.isPresent()) {
            problems.add("bridgeId: " + namespaceError.get() + "; a bridge id is generated per world instead");
            bridgeId = StaticDefaults.DEFAULT_BRIDGE_ID;
        }
        discoveryPrefix = validTopic("discoveryPrefix", discoveryPrefix, StaticDefaults.DEFAULT_DISCOVERY_PREFIX, problems);
        haBirthTopic = validTopic("haBirthTopic", haBirthTopic, StaticDefaults.DEFAULT_HA_BIRTH_TOPIC, problems);
        if (haExpose == null) haExpose = StaticDefaults.DEFAULT_HA_EXPOSE;
        if (aclRawAllow == null) aclRawAllow = StaticDefaults.DEFAULT_ACL_RAW_ALLOW;
        List<String> rawAllow = new ArrayList<>();
        for (String filter : aclRawAllow) {
            if (ConfigValidation.isValidRawAllowFilter(filter)) {
                rawAllow.add(filter);
            } else {
                problems.add("acl.rawAllow: '" + filter + "' is not a valid topic filter and is ignored");
            }
        }
        aclRawAllow = List.copyOf(rawAllow);
        if (aclOpsBypassAllowlist == null) aclOpsBypassAllowlist = StaticDefaults.DEFAULT_ACL_OPS_BYPASS_ALLOWLIST;
        if (limitsMaxDevicesPerPlayer < 0) {
            problems.add("limits.maxDevicesPerPlayer: " + limitsMaxDevicesPerPlayer + " is negative, using 0 (unlimited)");
            limitsMaxDevicesPerPlayer = StaticDefaults.DEFAULT_LIMITS_MAX_DEVICES_PER_PLAYER;
        }
        if (limitsMaxWiresPerPlayer < 0) {
            problems.add("limits.maxWiresPerPlayer: " + limitsMaxWiresPerPlayer + " is negative, using " + StaticDefaults.DEFAULT_LIMITS_MAX_WIRES_PER_PLAYER);
            limitsMaxWiresPerPlayer = StaticDefaults.DEFAULT_LIMITS_MAX_WIRES_PER_PLAYER;
        }
        sanitizeTwin(problems);
        sanitizeWorld(problems);
        if (legacyV1StatusTopic == null) legacyV1StatusTopic = StaticDefaults.DEFAULT_LEGACY_V1_STATUS_TOPIC;
        return problems;
    }

    /**
     * The {@code twin.*} keys (M3 plan D13). Invalid list entries are dropped, which only ever narrows what the twin may
     * read or control. Runs after the discovery prefix and the base topic are sanitized, because the statestream base
     * must not overlap them.
     */
    private static void sanitizeTwin(List<String> problems) {
        String base = ConfigValidation.normalizeStatestreamBase(twinStatestreamBase == null ? StaticDefaults.DEFAULT_TWIN_STATESTREAM_BASE : twinStatestreamBase);
        Optional<String> baseError = ConfigValidation.statestreamBaseError(base, discoveryPrefix, baseTopic);
        if (baseError.isPresent()) {
            String fallback = StaticDefaults.DEFAULT_TWIN_STATESTREAM_BASE;
            if (ConfigValidation.statestreamBaseError(fallback, discoveryPrefix, baseTopic).isPresent()) {
                // Only a discovery prefix or base topic inside ha_statestream gets here; the twin then reads nothing.
                problems.add("twin.statestreamBase: " + baseError.get() + ", and so does the default '" + fallback
                        + "'; the twin reads nothing until twin.statestreamBase is set");
                fallback = "";
            } else {
                problems.add("twin.statestreamBase: " + baseError.get() + ", using '" + fallback + "'");
            }
            base = fallback;
        }
        twinStatestreamBase = base;

        if (twinAllowedEntities == null) twinAllowedEntities = StaticDefaults.DEFAULT_TWIN_ALLOWED_ENTITIES;
        List<String> globs = new ArrayList<>();
        boolean auto = false;
        for (String entry : twinAllowedEntities) {
            Optional<String> error = ConfigValidation.allowedEntityError(entry);
            if (error.isPresent()) {
                problems.add("twin.allowedEntities: '" + entry + "' is ignored, " + error.get());
            } else if (ConfigValidation.isAutoEntry(entry)) {
                auto = true;
            } else if (!globs.contains(entry.strip())) {
                globs.add(entry.strip());
            }
        }
        if (auto && !globs.isEmpty()) {
            problems.add("twin.allowedEntities: " + StaticDefaults.AUTO_ENTRY + " stands alone and is ignored next to the globs " + globs);
        }
        twinAllowedEntities = auto && globs.isEmpty() ? StaticDefaults.DEFAULT_TWIN_ALLOWED_ENTITIES : List.copyOf(globs);

        if (twinHaActionAllowlist == null) twinHaActionAllowlist = StaticDefaults.DEFAULT_TWIN_HA_ACTION_ALLOWLIST;
        List<String> domains = new ArrayList<>();
        for (String domain : twinHaActionAllowlist) {
            if (!ConfigValidation.isValidHaActionDomain(domain)) {
                problems.add("twin.haActionAllowlist: '" + domain + "' is ignored, it is not a domain Minecraft can control (one of "
                        + HaDomains.controllableDomains() + ")");
            } else if (!domains.contains(domain.strip())) {
                domains.add(domain.strip());
            }
        }
        twinHaActionAllowlist = List.copyOf(domains);

        if (!ConfigValidation.isValidTwinMaxRequestsPerSecond(twinMaxRequestsPerSecond)) {
            problems.add("twin.maxRequestsPerSecond: " + twinMaxRequestsPerSecond + " is outside 1-" + StaticDefaults.MAX_TWIN_REQUESTS_PER_SECOND
                    + ", using " + StaticDefaults.DEFAULT_TWIN_MAX_REQUESTS_PER_SECOND);
            twinMaxRequestsPerSecond = StaticDefaults.DEFAULT_TWIN_MAX_REQUESTS_PER_SECOND;
        }
        if (!ConfigValidation.isValidTwinCatalogMax(twinCatalogMax)) {
            problems.add("twin.catalogMax: " + twinCatalogMax + " is outside 1-" + StaticDefaults.MAX_TWIN_CATALOG_MAX
                    + ", using " + StaticDefaults.DEFAULT_TWIN_CATALOG_MAX);
            twinCatalogMax = StaticDefaults.DEFAULT_TWIN_CATALOG_MAX;
        }
    }

    /**
     * The {@code telemetry.*}, {@code blockPlacer.*}, {@code blockObserver.*} and {@code rooms.*} keys and
     * {@code limits.maxRoomsPerPlayer} (M4 plan D16). A number out of its range falls back to its default.
     */
    private static void sanitizeWorld(List<String> problems) {
        if (!ConfigValidation.isValidTelemetryIntervalSeconds(telemetryIntervalSeconds)) {
            problems.add("telemetry.intervalSeconds: " + telemetryIntervalSeconds + " is outside " + StaticDefaults.MIN_TELEMETRY_INTERVAL_SECONDS
                    + "-" + StaticDefaults.MAX_TELEMETRY_INTERVAL_SECONDS + ", using " + StaticDefaults.DEFAULT_TELEMETRY_INTERVAL_SECONDS);
            telemetryIntervalSeconds = StaticDefaults.DEFAULT_TELEMETRY_INTERVAL_SECONDS;
        }
        if (telemetryPlayers == null) telemetryPlayers = StaticDefaults.DEFAULT_TELEMETRY_PLAYERS;
        if (!ConfigValidation.isValidTelemetryMaxPresenceDevices(telemetryMaxPresenceDevices)) {
            problems.add("telemetry.maxPresenceDevices: " + telemetryMaxPresenceDevices + " is outside " + StaticDefaults.MIN_TELEMETRY_MAX_PRESENCE_DEVICES
                    + "-" + StaticDefaults.MAX_TELEMETRY_MAX_PRESENCE_DEVICES + ", using " + StaticDefaults.DEFAULT_TELEMETRY_MAX_PRESENCE_DEVICES);
            telemetryMaxPresenceDevices = StaticDefaults.DEFAULT_TELEMETRY_MAX_PRESENCE_DEVICES;
        }
        if (!ConfigValidation.isValidTelemetryForgetAfterDays(telemetryForgetAfterDays)) {
            problems.add("telemetry.forgetAfterDays: " + telemetryForgetAfterDays + " is outside 0-" + StaticDefaults.MAX_TELEMETRY_FORGET_AFTER_DAYS
                    + " (0 = never), using " + StaticDefaults.DEFAULT_TELEMETRY_FORGET_AFTER_DAYS);
            telemetryForgetAfterDays = StaticDefaults.DEFAULT_TELEMETRY_FORGET_AFTER_DAYS;
        }
        if (blockPlacerPlayersMayConfigure == null) blockPlacerPlayersMayConfigure = StaticDefaults.DEFAULT_BLOCK_PLACER_PLAYERS_MAY_CONFIGURE;
        if (!ConfigValidation.isValidRoomsMaxSize(roomsMaxSize)) {
            problems.add("rooms.maxSize: " + roomsMaxSize + " is outside " + StaticDefaults.MIN_ROOMS_MAX_SIZE + "-" + StaticDefaults.MAX_ROOMS_MAX_SIZE
                    + ", using " + StaticDefaults.DEFAULT_ROOMS_MAX_SIZE);
            roomsMaxSize = StaticDefaults.DEFAULT_ROOMS_MAX_SIZE;
        }
        if (!ConfigValidation.isValidLimitsMaxRoomsPerPlayer(limitsMaxRoomsPerPlayer)) {
            problems.add("limits.maxRoomsPerPlayer: " + limitsMaxRoomsPerPlayer + " is outside 0-" + StaticDefaults.MAX_ROOMS_PER_WORLD
                    + " (0 = unlimited), using " + StaticDefaults.DEFAULT_LIMITS_MAX_ROOMS_PER_PLAYER);
            limitsMaxRoomsPerPlayer = StaticDefaults.DEFAULT_LIMITS_MAX_ROOMS_PER_PLAYER;
        }
    }

    /**
     * The effective {@code blockPlacer.playersMayConfigure} (M4 plan D12): AUTO means every player who may configure
     * the block on integrated servers, operators only on dedicated servers.
     */
    public static boolean blockPlacerPlayersMayConfigure(boolean dedicatedServer) {
        return blockPlacerPlayersMayConfigure.resolve(!dedicatedServer);
    }

    /**
     * The effective {@code twin.allowedEntities} (M3 plan D13): {@link StaticDefaults#AUTO_ENTRY} means every entity on
     * integrated servers and none on dedicated servers. Nobody bypasses it, operators and the console included, so it
     * depends on the server type alone. An entry {@link #sanitize()} has not judged yet (a screen saving right now)
     * counts only when it is a valid glob.
     */
    public static EntityGlobs twinAllowedEntities(boolean dedicatedServer) {
        List<String> entries = twinAllowedEntities;
        if (entries.size() == 1 && ConfigValidation.isAutoEntry(entries.getFirst())) return dedicatedServer ? EntityGlobs.NONE : EntityGlobs.ALL;
        return EntityGlobs.of(entries.stream().filter(entry -> EntityGlobs.problem(entry.strip()).isEmpty()).toList());
    }

    /**
     * Why the twin is off, as a sentence that names the config key to set, or empty when it may read entities: no
     * statestream base, or no allowed entities (the default on dedicated servers).
     */
    public static Optional<String> twinOffReason(boolean dedicatedServer) {
        if (twinStatestreamBase.isEmpty()) return Optional.of("no statestream base: set twin.statestreamBase");
        if (twinAllowedEntities(dedicatedServer).isEmpty()) return Optional.of("no allowed entities: set twin.allowedEntities");
        return Optional.empty();
    }

    /**
     * The twin line of {@code /mineqtt status} and the startup log (M3 plan D13): off with the key to set, or what the
     * twin reads and may control.
     */
    public static String twinSummary(boolean dedicatedServer) {
        Optional<String> off = twinOffReason(dedicatedServer);
        if (off.isPresent()) return "off, " + off.get();
        EntityGlobs allowed = twinAllowedEntities(dedicatedServer);
        String entities = allowed.equals(EntityGlobs.ALL) ? "all entities" : allowed.patterns().size() == 1 ? "entities matching " + allowed.patterns().getFirst()
                : allowed.patterns().size() + " entity patterns";
        String auto = twinAllowedEntities.size() == 1 && ConfigValidation.isAutoEntry(twinAllowedEntities.getFirst()) ? " (" + StaticDefaults.AUTO_ENTRY + ")" : "";
        return "reads " + entities + auto + " under " + twinStatestreamBase + ", catalog " + (twinCatalog ? "on (at most " + twinCatalogMax + " entities)" : "off")
                + "; controls " + (twinHaActionAllowlist.isEmpty() ? "no domains" : twinHaActionAllowlist.size() + " domains")
                + ", at most " + twinMaxRequestsPerSecond + " requests/s";
    }

    /** The password to send: {@value StaticDefaults#PASSWORD_ENV_VAR} wins over the config value. Null when neither is set. */
    public static char[] effectivePassword() {
        return effectivePassword(System.getenv(StaticDefaults.PASSWORD_ENV_VAR));
    }

    static char[] effectivePassword(String environmentValue) {
        if (environmentValue != null && !environmentValue.isEmpty()) return environmentValue.toCharArray();
        return password == null || password.isEmpty() ? null : password.toCharArray();
    }

    /** Whether {@value StaticDefaults#PASSWORD_ENV_VAR} is set, which makes the config password unused. */
    public static boolean passwordFromEnvironment() {
        String value = System.getenv(StaticDefaults.PASSWORD_ENV_VAR);
        return value != null && !value.isEmpty();
    }

    /** Everything about the broker connection except the URI. */
    public static BrokerSettings brokerSettings(String clientId) {
        Path caFile = tlsCaFile.isEmpty() ? null : Path.of(tlsCaFile);
        return new BrokerSettings(clientId, username.isBlank() ? null : username, effectivePassword(), caFile,
                tlsInsecureSkipVerify, keepAliveSec, protocol, BrokerSettings.DEFAULT_MAX_INBOUND_BYTES);
    }

    /**
     * The configured broker connection.
     *
     * @return empty when no broker is configured, which means MQTT is disabled
     * @throws IllegalArgumentException when the broker URI is malformed
     */
    public static Optional<BrokerConfig> brokerConfig(String clientId) {
        return BrokerConfig.parse(broker, brokerSettings(clientId));
    }

    /** {@code acl.rawAllow} with the base topic and the world's bridge id filled in. */
    public static List<String> rawAllow(String resolvedBridgeId) {
        return aclRawAllow.stream().map(filter -> resolvePlaceholders(filter, baseTopic, resolvedBridgeId)).toList();
    }

    static String resolvePlaceholders(String filter, String base, String resolvedBridgeId) {
        return filter.replace(StaticDefaults.BASE_PLACEHOLDER, base).replace(StaticDefaults.BRIDGE_ID_PLACEHOLDER, resolvedBridgeId);
    }

    /** The effective {@code ha.expose}: {@link HaExpose#AUTO} means ALL on integrated and OPT_IN on dedicated servers. */
    public static HaExpose haExpose(boolean dedicatedServer) {
        return haExpose.resolve(dedicatedServer);
    }

    /** The effective {@code acl.opsBypassAllowlist}: AUTO means true on integrated and false on dedicated servers. */
    public static boolean opsBypassAllowlist(boolean dedicatedServer) {
        return aclOpsBypassAllowlist.resolve(!dedicatedServer);
    }

    /**
     * The settings a broker connection is built from, including the base topic and bridge id its will is published
     * on. When they change, the client has to reconnect.
     */
    public static ConnectionSettings connectionSettings() {
        return new ConnectionSettings(broker, protocol, keepAliveSec, username, password, tlsCaFile, tlsInsecureSkipVerify, bridgeId, baseTopic);
    }

    private static String validTopic(String key, String value, String fallback, List<String> problems) {
        if (ConfigValidation.isValidConfigTopic(value)) return value;
        problems.add(key + ": '" + value + "' is not a valid topic (no wildcards, no leading $, no empty levels), using '" + fallback + "'");
        return fallback;
    }

    private static boolean isReadableFile(String file) {
        try {
            Path path = Path.of(file);
            return Files.isRegularFile(path) && Files.isReadable(path);
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /**
     * A comparable snapshot of the connection settings; {@link #toString()} masks the credentials, also those a
     * malformed broker URI may contain.
     */
    public record ConnectionSettings(String broker, ProtocolVersion protocol, int keepAliveSec, String username,
                                     String password, String tlsCaFile, boolean tlsInsecureSkipVerify, String bridgeId,
                                     String baseTopic) {
        @Override
        public String toString() {
            String loggedBroker = broker.isBlank() ? "none" : ConfigValidation.loggableBroker(broker).orElse("(invalid)");
            return "ConnectionSettings[broker=" + loggedBroker + ", protocol=" + protocol.configValue() + ", keepAliveSec=" + keepAliveSec
                    + ", username=" + (username.isEmpty() ? "none" : "***") + ", password=" + (password.isEmpty() ? "none" : "***")
                    + ", tlsCaFile=" + tlsCaFile + ", tlsInsecureSkipVerify=" + tlsInsecureSkipVerify + ", bridgeId=" + bridgeId + ", baseTopic=" + baseTopic + "]";
        }
    }
}

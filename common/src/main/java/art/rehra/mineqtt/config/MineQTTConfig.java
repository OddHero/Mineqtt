package art.rehra.mineqtt.config;

import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import art.rehra.mineqtt.mqtt.core.BrokerSettings;
import art.rehra.mineqtt.mqtt.core.ProtocolVersion;

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

    // Migration record
    public static String legacyV1StatusTopic;

    // Leftover experiments
    public static boolean allowItemNetherPortalTeleport;
    public static int goalX;
    public static int goalY;
    public static int goalZ;
    public static boolean zombieGoalEnabled;

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
        legacyV1StatusTopic = StaticDefaults.DEFAULT_LEGACY_V1_STATUS_TOPIC;
        allowItemNetherPortalTeleport = StaticDefaults.DEFAULT_ALLOW_ITEM_NETHER_PORTAL_TELEPORT;
        goalX = StaticDefaults.DEFAULT_GOAL_X;
        goalY = StaticDefaults.DEFAULT_GOAL_Y;
        goalZ = StaticDefaults.DEFAULT_GOAL_Z;
        zombieGoalEnabled = StaticDefaults.DEFAULT_ZOMBIE_GOAL_ENABLED;
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
        if (legacyV1StatusTopic == null) legacyV1StatusTopic = StaticDefaults.DEFAULT_LEGACY_V1_STATUS_TOPIC;
        return problems;
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

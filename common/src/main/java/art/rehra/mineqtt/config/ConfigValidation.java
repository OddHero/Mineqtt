package art.rehra.mineqtt.config;

import art.rehra.mineqtt.device.RawTopics;
import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import art.rehra.mineqtt.mqtt.core.BrokerSettings;
import art.rehra.mineqtt.mqtt.core.ProtocolVersion;
import art.rehra.mineqtt.mqtt.core.TopicFilters;
import art.rehra.mineqtt.twin.core.EntityGlobs;
import art.rehra.mineqtt.twin.core.HaDomains;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Value rules shared by both loaders' config files and screens and by {@link MineQTTConfig#sanitize()}. */
public final class ConfigValidation {
    /** Same charset as device slugs: the bridge id becomes a topic level and part of the MQTT client id. */
    private static final Pattern BRIDGE_ID = Pattern.compile("[a-z0-9_-]{1,64}");
    private static final int MAX_TOPIC_BYTES = 256;
    private static final BrokerSettings PARSE_ONLY = BrokerSettings.defaults("mineqtt_config_check", StaticDefaults.DEFAULT_PROTOCOL);

    private ConfigValidation() {
    }

    /** Why the broker URI cannot be used, or empty when it is valid or blank (blank disables MQTT). */
    public static Optional<String> brokerError(String uri) {
        try {
            BrokerConfig.parse(uri, PARSE_ONLY);
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.of(e.getMessage());
        }
    }

    /**
     * The broker URI in canonical form for logs, or empty when it is blank or malformed. A malformed URI may carry
     * credentials, so it is never logged as written.
     */
    public static Optional<String> loggableBroker(String uri) {
        try {
            return BrokerConfig.parse(uri, PARSE_ONLY).map(BrokerConfig::uri);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static boolean isValidBroker(Object value) {
        return value instanceof String uri && brokerError(uri).isEmpty();
    }

    public static boolean isValidProtocol(Object value) {
        if (!(value instanceof String text)) return false;
        try {
            ProtocolVersion.parse(text);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Empty (generate one per world) or 1-64 of {@code a-z 0-9 _ -}. */
    public static boolean isValidBridgeId(Object value) {
        return value instanceof String id && (id.isEmpty() || BRIDGE_ID.matcher(id).matches());
    }

    /**
     * Why a base topic cannot be used, or empty when it can: every bridge namespace under a base topic in the shared
     * namespace {@code mineqtt/shared}, where item and paper topics of every server meet, would lie in it, so another
     * server's item and paper topics would reach this server's device topics.
     */
    public static Optional<String> baseTopicNamespaceError(String baseTopic) {
        if (!inSharedNamespace(baseTopic)) return Optional.empty();
        return Optional.of("'" + baseTopic + "' lies in the shared namespace " + RawTopics.SHARED_ROOT + " of every server");
    }

    /**
     * Why a bridge id cannot be used under the base topic, or empty when it can: the bridge namespace
     * {@code <baseTopic>/<bridgeId>} must not lie in the shared namespace ({@link #baseTopicNamespaceError}). The bridge
     * id {@code shared} under the base {@code mineqtt} would make it this server's own reserved namespace.
     */
    public static Optional<String> bridgeNamespaceError(String bridgeId, String baseTopic) {
        if (!inSharedNamespace(baseTopic + "/" + bridgeId)) return Optional.empty();
        return Optional.of("'" + bridgeId + "' under the base topic '" + baseTopic + "' lies in the shared namespace " + RawTopics.SHARED_ROOT
                + " of every server");
    }

    private static boolean inSharedNamespace(String topic) {
        return TopicFilters.covers(RawTopics.SHARED_ROOT + "/#", topic);
    }

    /**
     * A topic MineQTT publishes under or subscribes to as configured (base topic, discovery prefix, birth topic):
     * no wildcards, no leading {@code $}, no empty level and at most 256 bytes.
     */
    public static boolean isValidConfigTopic(Object value) {
        if (!(value instanceof String topic) || !TopicFilters.isValidTopic(topic) || topic.startsWith("$")) return false;
        if (topic.getBytes(StandardCharsets.UTF_8).length > MAX_TOPIC_BYTES) return false;
        for (String level : topic.split("/", -1)) {
            if (level.isEmpty()) return false;
        }
        return true;
    }

    /** An {@code acl.rawAllow} entry: a topic filter, possibly using the base and bridge id placeholders. */
    public static boolean isValidRawAllowFilter(Object value) {
        if (!(value instanceof String filter)) return false;
        return TopicFilters.isValidFilter(MineQTTConfig.resolvePlaceholders(filter, StaticDefaults.DEFAULT_BASE_TOPIC, "bridge"));
    }

    public static boolean isValidKeepAlive(int seconds) {
        return seconds >= 0 && seconds <= 65_535;
    }

    /**
     * {@code twin.statestreamBase} as topics are matched against it: surrounding whitespace and one trailing {@code /}
     * dropped, as Home Assistant appends one to its {@code base_topic} ({@code StatestreamTopic.normalizeBase} reads
     * the base the same way). Empty stays empty: no statestream base, the twin reads nothing.
     */
    public static String normalizeStatestreamBase(String base) {
        String stripped = base.strip();
        return stripped.endsWith("/") ? stripped.substring(0, stripped.length() - 1) : stripped;
    }

    /**
     * Why a normalized {@code twin.statestreamBase} cannot be used next to these topics, or empty when it can (M3 plan
     * D13, the F1 pattern). Empty means no statestream base and is valid. The base must be a valid config topic and must
     * not equal or overlap the discovery prefix, MineQTT's base topic (every bridge namespace lies under it) or the
     * shared namespace {@code mineqtt/shared}: the ACL makes the whole statestream namespace read-only, so an overlap
     * would either open those namespaces to the twin's readers or make MineQTT's own topics unwritable.
     */
    public static Optional<String> statestreamBaseError(String base, String discoveryPrefix, String baseTopic) {
        if (base.isEmpty()) return Optional.empty();
        if (!isValidConfigTopic(base)) return Optional.of("'" + base + "' is not a valid topic (no wildcards, no leading $, no empty levels)");
        if (overlap(base, discoveryPrefix)) {
            return Optional.of("'" + base + "' overlaps the Home Assistant discovery prefix '" + discoveryPrefix + "'");
        }
        if (overlap(base, baseTopic)) return Optional.of("'" + base + "' overlaps the MineQTT base topic '" + baseTopic + "'");
        if (overlap(base, RawTopics.SHARED_ROOT)) {
            return Optional.of("'" + base + "' overlaps the shared namespace " + RawTopics.SHARED_ROOT + " of every server");
        }
        return Optional.empty();
    }

    /** Whether one topic lies in the other's subtree (or both are equal). */
    private static boolean overlap(String a, String b) {
        return TopicFilters.covers(a + "/#", b) || TopicFilters.covers(b + "/#", a);
    }

    /** Why an entry cannot stand in {@code twin.allowedEntities}, or empty when it can: {@code AUTO} or a valid glob. */
    public static Optional<String> allowedEntityError(String entry) {
        if (entry != null && isAutoEntry(entry)) return Optional.empty();
        return EntityGlobs.problem(entry == null ? null : entry.strip());
    }

    /** Whether the entry is {@link StaticDefaults#AUTO_ENTRY}, in any case and with surrounding whitespace. */
    public static boolean isAutoEntry(String entry) {
        return entry.strip().equalsIgnoreCase(StaticDefaults.AUTO_ENTRY);
    }

    /** Whether a {@code twin.haActionAllowlist} entry names a domain Minecraft may control at all ({@link HaDomains}). */
    public static boolean isValidHaActionDomain(Object value) {
        return value instanceof String domain && HaDomains.controllable(domain.strip());
    }

    public static boolean isValidTwinMaxRequestsPerSecond(int value) {
        return value >= 1 && value <= StaticDefaults.MAX_TWIN_REQUESTS_PER_SECOND;
    }

    public static boolean isValidTwinCatalogMax(int value) {
        return value >= 1 && value <= StaticDefaults.MAX_TWIN_CATALOG_MAX;
    }

    public static boolean isValidTelemetryIntervalSeconds(int value) {
        return value >= StaticDefaults.MIN_TELEMETRY_INTERVAL_SECONDS && value <= StaticDefaults.MAX_TELEMETRY_INTERVAL_SECONDS;
    }

    public static boolean isValidTelemetryMaxPresenceDevices(int value) {
        return value >= StaticDefaults.MIN_TELEMETRY_MAX_PRESENCE_DEVICES && value <= StaticDefaults.MAX_TELEMETRY_MAX_PRESENCE_DEVICES;
    }

    /** 0 (never forget) up to {@value StaticDefaults#MAX_TELEMETRY_FORGET_AFTER_DAYS} days. */
    public static boolean isValidTelemetryForgetAfterDays(int value) {
        return value >= 0 && value <= StaticDefaults.MAX_TELEMETRY_FORGET_AFTER_DAYS;
    }

    public static boolean isValidRoomsMaxSize(int value) {
        return value >= StaticDefaults.MIN_ROOMS_MAX_SIZE && value <= StaticDefaults.MAX_ROOMS_MAX_SIZE;
    }

    /** 0 (unlimited, the world's cap still applies) up to the world's cap of {@value StaticDefaults#MAX_ROOMS_PER_WORLD}. */
    public static boolean isValidLimitsMaxRoomsPerPlayer(int value) {
        return value >= 0 && value <= StaticDefaults.MAX_ROOMS_PER_WORLD;
    }

    /**
     * {@code telemetry.players} as both loaders read it from their file (M4 plan D9): an absent key takes the default, a
     * name in any case (or the constant, as NeoForge may hold it) is that setting, and anything else falls back to the
     * narrowest setting, {@code OFF}, with a message, never to the default: a typo must not publish more about players.
     *
     * @param raw the value as the file holds it: a string, boolean, number, list, the enum constant, or null when absent
     */
    public static TelemetryPlayers telemetryPlayersOrNarrowest(Object raw, List<String> messages) {
        if (raw == null) return StaticDefaults.DEFAULT_TELEMETRY_PLAYERS;
        if (raw instanceof TelemetryPlayers players) return players;
        if (raw instanceof String text) {
            for (TelemetryPlayers players : TelemetryPlayers.values()) {
                if (players.name().equals(text.strip().toUpperCase(Locale.ROOT))) return players;
            }
        }
        messages.add("telemetry.players: expected one of " + List.of(TelemetryPlayers.values()) + ", using " + TelemetryPlayers.OFF
                + " (nothing is published about players) until it is fixed");
        return TelemetryPlayers.OFF;
    }

    /**
     * {@code telemetry.publishPlayerNames} as both loaders read it from their file (M4 plan D9): an absent key takes the
     * default, a boolean (or {@code "true"}/{@code "false"} in any case, which NeoForge accepts for its booleans) is that
     * value, and anything else falls back to the narrowest value, {@code false} (no names), with a message.
     *
     * @param raw the value as the file holds it, or null when absent
     */
    public static boolean publishPlayerNamesOrNarrowest(Object raw, List<String> messages) {
        if (raw == null) return StaticDefaults.DEFAULT_TELEMETRY_PUBLISH_PLAYER_NAMES;
        if (raw instanceof Boolean value) return value;
        if (raw instanceof String text && (text.strip().equalsIgnoreCase("true") || text.strip().equalsIgnoreCase("false"))) {
            return text.strip().equalsIgnoreCase("true");
        }
        messages.add("telemetry.publishPlayerNames: expected true or false, using false (no player names) until it is fixed");
        return false;
    }
}

package art.rehra.mineqtt.config;

import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import art.rehra.mineqtt.mqtt.core.BrokerSettings;
import art.rehra.mineqtt.mqtt.core.ProtocolVersion;
import art.rehra.mineqtt.mqtt.core.TopicFilters;

import java.nio.charset.StandardCharsets;
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
}

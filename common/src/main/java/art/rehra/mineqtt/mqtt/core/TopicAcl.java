package art.rehra.mineqtt.mqtt.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Decides whether a topic a player entered may be used (design §8.2). Pure: who the player is, whether they are an op
 * and which grandfather flags a device carries are resolved by the caller.
 * <p>
 * Order of the checks for a publish or Raw Link topic: validation, hard deny (applies to ops too), the owner's private
 * subtree, a grandfathered v1 topic, the raw allowlist, the op bypass of the allowlist.
 */
public final class TopicAcl {
    /** The encoded length limit of a player-entered topic. */
    public static final int MAX_TOPIC_BYTES = 256;
    // The reserved levels below <base>/<bridgeId> that are no device slugs.
    private static final String BRIDGE_LEVEL = "bridge";
    private static final String RAW_LEVEL = "raw";
    private static final String GROUP_LEVEL = "group";

    public enum Decision {
        ALLOWED(true, "allowed"),
        ALLOWED_PRIVATE(true, "allowed: the owner's private topic"),
        ALLOWED_GRANDFATHERED(true, "allowed: grandfathered v1 topic"),
        ALLOWED_OP_BYPASS(true, "allowed: operator bypass of the raw allowlist"),
        INVALID(false, "invalid topic: empty, empty level, NUL or longer than 256 bytes"),
        WILDCARD(false, "wildcards are only allowed when listening"),
        DOLLAR(false, "topics starting with $ are reserved for the broker"),
        DENY_DISCOVERY(false, "the Home Assistant discovery prefix is reserved"),
        DENY_BRIDGE(false, "this server's MineQTT namespace is reserved (only its raw/ subtree is open)"),
        DENY_FOREIGN_PRIVATE(false, "another player's private topics are reserved to them"),
        DENY_STATESTREAM(false, "the Home Assistant statestream namespace is read-only"),
        DENY_BROKER_WIDE(false, "listening on the whole broker needs mineqtt.admin and acl.explorerBrokerWide"),
        NOT_ALLOWLISTED(false, "not in the raw topic allowlist (acl.rawAllow)");

        private final boolean allowed;
        private final String message;

        Decision(boolean allowed, String message) {
            this.allowed = allowed;
            this.message = message;
        }

        public boolean allowed() {
            return allowed;
        }

        /** A sentence for the device status line and command feedback. */
        public String message() {
            return message;
        }
    }

    /**
     * The ACL configuration.
     *
     * @param base            the MineQTT base topic, e.g. {@code mineqtt}
     * @param bridgeId        this server's bridge id, one topic level
     * @param discoveryPrefix the Home Assistant discovery prefix, e.g. {@code homeassistant}
     * @param statestreamBase the HA statestream base topic, or null/empty when none is configured
     * @param rawAllow        filters players may publish into and link raw topics under
     */
    public record Rules(String base, String bridgeId, String discoveryPrefix, String statestreamBase,
                        List<String> rawAllow, boolean opsBypassAllowlist, boolean explorerBrokerWide) {
        public Rules {
            requireTopic("base topic", base);
            requireTopic("bridge id", bridgeId);
            if (bridgeId.indexOf('/') >= 0) throw new IllegalArgumentException("Bridge id must be a single topic level: " + bridgeId);
            requireTopic("discovery prefix", discoveryPrefix);
            if (statestreamBase != null && statestreamBase.isEmpty()) statestreamBase = null;
            if (statestreamBase != null) requireTopic("statestream base", statestreamBase);
            rawAllow = List.copyOf(rawAllow);
            for (String filter : rawAllow) {
                if (!TopicFilters.isValidFilter(filter)) throw new IllegalArgumentException("Invalid acl.rawAllow filter: " + filter);
            }
        }

        private static void requireTopic(String what, String topic) {
            if (!TopicFilters.isValidTopic(topic)) throw new IllegalArgumentException("Invalid " + what + ": " + topic);
        }
    }

    /**
     * Who asks. {@code player} is null for the server console, which counts as an op without a private subtree.
     *
     * @param admin holds the {@code mineqtt.admin} permission
     */
    public record Subject(UUID player, boolean op, boolean admin) {
        public static final Subject CONSOLE = new Subject(null, true, true);
    }

    /**
     * A v1 topic adopted by the migrator: exempt from the allowlist for exactly this topic while the owner stays. A
     * null owner is a v1 device without a known owner, which keeps the exemption while it has none.
     */
    public record Grandfather(String topic, UUID owner) {
    }

    private final Rules rules;
    private final String bridgeRoot;
    private final String playersRoot;

    public TopicAcl(Rules rules) {
        this.rules = rules;
        this.bridgeRoot = rules.base() + "/" + rules.bridgeId();
        this.playersRoot = bridgeRoot + "/raw/players";
    }

    public Rules rules() {
        return rules;
    }

    /** The default {@code acl.rawAllow}: the shared namespace and this server's raw subtree, on every server type. */
    public static List<String> defaultRawAllow(String base, String bridgeId) {
        return List.of("mineqtt/shared/#", base + "/" + bridgeId + "/raw/#");
    }

    /** Where a private-mode topic of {@code owner} lives; an empty topic is the root of the owner's subtree. */
    public String privateTopic(UUID owner, String topic) {
        String root = playersRoot + "/" + owner;
        return topic.isEmpty() ? root : root + "/" + topic;
    }

    /** A topic a player publishes to (command, Configurator) without a grandfather flag. */
    public Decision checkPublish(Subject subject, String topic) {
        return checkPublish(subject, topic, null);
    }

    /**
     * A topic a player publishes to, or the topic of a Raw Link in either direction.
     *
     * @param grandfather the device's grandfather flag, or null
     */
    public Decision checkPublish(Subject subject, String topic, Grandfather grandfather) {
        Decision invalid = validate(topic, false);
        if (invalid != null) return invalid;
        Decision denied = hardDenyPublish(subject, topic);
        if (denied != null) return denied;
        if (subject.player() != null && TopicFilters.covers(playersRoot + "/" + subject.player() + "/#", topic)) {
            return Decision.ALLOWED_PRIVATE;
        }
        if (grandfather != null && grandfather.topic().equals(topic) && Objects.equals(grandfather.owner(), subject.player())) {
            return Decision.ALLOWED_GRANDFATHERED;
        }
        return allowlisted(subject, topic);
    }

    /** A filter a player listens on with the Cyberdeck/Configurator explorer. */
    public Decision checkListen(Subject subject, String filter) {
        Decision invalid = validate(filter, true);
        if (invalid != null) return invalid;
        boolean brokerWide = rules.explorerBrokerWide() && subject.admin();
        if (TopicFilters.isWildcard(TopicFilters.levels(filter)[0])) {
            return brokerWide ? Decision.ALLOWED : Decision.DENY_BROKER_WIDE;
        }
        // Wider filters such as <base>/<bridgeId>/# pass here; the explorer hides other players' messages itself.
        if (!brokerWide && intoForeignPrivate(subject, filter)) return Decision.DENY_FOREIGN_PRIVATE;
        if (brokerWide || TopicFilters.covers(bridgeRoot + "/#", filter)) return Decision.ALLOWED;
        return allowlisted(subject, filter);
    }

    /**
     * A filter a block mirrors (statestream, zigbee2mqtt and other foreign state). Nothing filters a mirror's messages
     * afterwards, so any filter that can reach another player's private subtree is denied, wildcards included.
     */
    public Decision checkMirror(Subject subject, String filter) {
        Decision invalid = validate(filter, true);
        if (invalid != null) return invalid;
        if (TopicFilters.isWildcard(TopicFilters.levels(filter)[0])) return Decision.DENY_BROKER_WIDE;
        if (overlaps(rules.discoveryPrefix(), filter)) return Decision.DENY_DISCOVERY;
        if (reachesForeignPrivate(subject, filter)) return Decision.DENY_FOREIGN_PRIVATE;
        return Decision.ALLOWED;
    }

    /**
     * The hard deny alone, for unmigrated v1 blocks (the allowlist does not apply to them, so no world breaks). Only
     * MQTT validity is checked, not the 256-byte limit, because v1 topics were never limited. A block owns no private
     * subtree and nothing filters its messages afterwards, so like a mirror it listens on no filter that can reach a
     * player's private topics. The discovery prefix stays open to listeners, which v1 blocks use for HA's birth topic.
     */
    public Decision checkLegacy(String topicOrFilter, boolean publish) {
        if (publish) {
            if (!TopicFilters.isValidTopic(topicOrFilter)) return TopicFilters.isValidFilter(topicOrFilter) ? Decision.WILDCARD : Decision.INVALID;
            if (topicOrFilter.startsWith("$")) return Decision.DOLLAR;
            Decision denied = hardDenyPublish(Subject.CONSOLE, topicOrFilter);
            return denied == null ? Decision.ALLOWED : denied;
        }
        if (!TopicFilters.isValidFilter(topicOrFilter)) return Decision.INVALID;
        if (topicOrFilter.startsWith("$")) return Decision.DOLLAR;
        if (TopicFilters.isWildcard(TopicFilters.levels(topicOrFilter)[0])) return Decision.DENY_BROKER_WIDE;
        return reachesForeignPrivate(Subject.CONSOLE, topicOrFilter) ? Decision.DENY_FOREIGN_PRIVATE : Decision.ALLOWED;
    }

    /** Whether the explorer shows only the payload size of a message on this topic. */
    public boolean redactPayload(String topic) {
        return TopicFilters.covers(rules.discoveryPrefix() + "/#", topic);
    }

    /**
     * The filters the Cyberdeck/Configurator explorer listens on for a subject (design §8.4): the whole broker for an
     * admin when {@code acl.explorerBrokerWide} is set, else this server's namespace plus every raw allowlist filter
     * the subject may listen on. A filter covered by another one is left out, so no message matches twice; filters that
     * only partly overlap can still both match one message.
     */
    public List<String> listenScope(Subject subject) {
        if (rules.explorerBrokerWide() && subject.admin()) return List.of("#");
        List<String> scope = new ArrayList<>();
        scope.add(bridgeRoot + "/#");
        for (String filter : rules.rawAllow()) {
            if (!checkListen(subject, filter).allowed()) continue;
            if (scope.stream().anyMatch(wider -> TopicFilters.covers(wider, filter))) continue;
            scope.removeIf(narrower -> TopicFilters.covers(filter, narrower));
            scope.add(filter);
        }
        return List.copyOf(scope);
    }

    /**
     * What the explorer may show a subject of a message it received on one of the {@link #listenScope} filters. The
     * scope reaches further than a player may look, so every message is judged on its own (design §8.4).
     */
    public Exposure explorerExposure(Subject subject, String topic) {
        if (TopicFilters.covers(bridgeRoot + "/#", topic)) {
            String[] levels = TopicFilters.levels(topic);
            int first = TopicFilters.levels(bridgeRoot).length;
            if (levels.length == first) return Exposure.SHOW;
            return switch (levels[first]) {
                case BRIDGE_LEVEL -> Exposure.SHOW;
                case RAW_LEVEL -> intoForeignPrivate(subject, topic) ? Exposure.HIDE : Exposure.SHOW;
                case GROUP_LEVEL -> new Exposure(Exposure.Kind.GROUP, levels.length > first + 1 ? levels[first + 1] : "");
                default -> new Exposure(Exposure.Kind.DEVICE, levels[first]);
            };
        }
        return redactPayload(topic) ? Exposure.REDACT : Exposure.SHOW;
    }

    /**
     * The explorer's verdict on one message.
     *
     * @param slug the device or group slug for {@link Kind#DEVICE} and {@link Kind#GROUP}, else empty
     */
    public record Exposure(Kind kind, String slug) {
        public static final Exposure SHOW = new Exposure(Kind.SHOW, "");
        public static final Exposure REDACT = new Exposure(Kind.REDACT, "");
        public static final Exposure HIDE = new Exposure(Kind.HIDE, "");

        public enum Kind {
            /** Show topic and payload. */
            SHOW,
            /** Show the topic and only the payload's size. */
            REDACT,
            /** Do not show the message. */
            HIDE,
            /** A device's topic: shown to players who may configure the device with that slug. */
            DEVICE,
            /** A group's topic: shown to players who may configure groups. */
            GROUP
        }
    }

    private static Decision validate(String topicOrFilter, boolean filterAllowed) {
        if (topicOrFilter == null || topicOrFilter.isEmpty() || topicOrFilter.indexOf('\0') >= 0
                || topicOrFilter.getBytes(StandardCharsets.UTF_8).length > MAX_TOPIC_BYTES) {
            return Decision.INVALID;
        }
        for (String level : TopicFilters.levels(topicOrFilter)) {
            if (level.isEmpty()) return Decision.INVALID;
        }
        if (topicOrFilter.startsWith("$")) return Decision.DOLLAR;
        if (filterAllowed) return TopicFilters.isValidFilter(topicOrFilter) ? null : Decision.INVALID;
        if (TopicFilters.isValidTopic(topicOrFilter)) return null;
        return TopicFilters.isValidFilter(topicOrFilter) ? Decision.WILDCARD : Decision.INVALID;
    }

    private Decision hardDenyPublish(Subject subject, String topic) {
        if (TopicFilters.covers(rules.discoveryPrefix() + "/#", topic)) return Decision.DENY_DISCOVERY;
        if (rules.statestreamBase() != null && TopicFilters.covers(rules.statestreamBase() + "/#", topic)) {
            return Decision.DENY_STATESTREAM;
        }
        if (intoForeignPrivate(subject, topic)) return Decision.DENY_FOREIGN_PRIVATE;
        if (TopicFilters.covers(bridgeRoot + "/#", topic) && !TopicFilters.covers(bridgeRoot + "/raw/#", topic)) {
            return Decision.DENY_BRIDGE;
        }
        return null;
    }

    /**
     * Whether everything matched lies in the private subtree of anyone but the subject, or is its bare root. Wider
     * filters that only partly reach such a subtree pass; see {@link #reachesForeignPrivate} for those.
     */
    private boolean intoForeignPrivate(Subject subject, String topicOrFilter) {
        if (!TopicFilters.covers(playersRoot + "/#", topicOrFilter)) return false;
        String[] levels = TopicFilters.levels(topicOrFilter);
        int ownerLevel = TopicFilters.levels(playersRoot).length;
        return subject.player() == null || levels.length <= ownerLevel || !levels[ownerLevel].equals(subject.player().toString());
    }

    /** Whether the filter can match any topic in the private subtree of anyone but the subject. */
    private boolean reachesForeignPrivate(Subject subject, String filter) {
        String[] filterLevels = TopicFilters.levels(filter);
        int ownerLevel = TopicFilters.levels(playersRoot).length;
        if (!overlaps(playersRoot, filter)) return false;
        // A "#" at or above the owner level reaches every owner.
        for (int i = 0; i < Math.min(ownerLevel, filterLevels.length); i++) {
            if (filterLevels[i].equals("#")) return true;
        }
        // Without one, a filter that ends at the players root matches only that bare topic, which belongs to no one.
        if (filterLevels.length == ownerLevel) return false;
        String owner = filterLevels[ownerLevel];
        return subject.player() == null || TopicFilters.isWildcard(owner) || !owner.equals(subject.player().toString());
    }

    private Decision allowlisted(Subject subject, String topicOrFilter) {
        for (String allowed : rules.rawAllow()) {
            if (TopicFilters.covers(allowed, topicOrFilter)) return Decision.ALLOWED;
        }
        return subject.op() && rules.opsBypassAllowlist() ? Decision.ALLOWED_OP_BYPASS : Decision.NOT_ALLOWLISTED;
    }

    /** Whether the subtree under {@code prefix} and the topics matched by {@code filter} share any topic. */
    private static boolean overlaps(String prefix, String filter) {
        String[] prefixLevels = TopicFilters.levels(prefix);
        String[] filterLevels = TopicFilters.levels(filter);
        for (int i = 0; i < prefixLevels.length; i++) {
            if (i >= filterLevels.length) return false;
            String level = filterLevels[i];
            if (level.equals("#")) return true;
            if (!level.equals("+") && !level.equals(prefixLevels[i])) return false;
        }
        return true;
    }
}

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
 * <p>
 * The hard deny also covers every other bridge's {@code bridge/} namespace, under this server's base topic and under
 * {@value #SHARED_BASE}, the base of the shared namespace and of the control blueprint's default request topic
 * {@code mineqtt/+/bridge/ha_request} (M3 plan D12, D13): nobody in game can forge a Home Assistant request, sync or
 * response of any server, whatever the allowlist says.
 */
public final class TopicAcl {
    /** The encoded length limit of a player-entered topic. */
    public static final int MAX_TOPIC_BYTES = 256;
    /**
     * The base topic of the shared namespace {@code mineqtt/shared}, which every server's default allowlist opens, and
     * MineQTT's default base topic.
     */
    public static final String SHARED_BASE = "mineqtt";
    /**
     * The twin's leaves below a bridge's {@code bridge/} level. Their payloads carry entity ids and player names, so the
     * explorer shows non-admins only their size (M3 plan D13).
     */
    public static final List<String> TWIN_TRAFFIC_LEAVES = List.of("ha_request", "ha_response", "ha_sync");
    // The reserved levels below <base>/<bridgeId> that are no device slugs.
    private static final String BRIDGE_LEVEL = "bridge";
    private static final String RAW_LEVEL = "raw";
    private static final String GROUP_LEVEL = "group";
    private static final String PLAYER_LEVEL = "player";

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
        DENY_FOREIGN_BRIDGE(false, "the bridge/ namespace of every MineQTT server is reserved"),
        DENY_STATESTREAM_LISTEN(false, "listening on the Home Assistant statestream namespace needs mineqtt.admin"),
        DENY_STATESTREAM_LEGACY(false, "v1 topics are not followed on the Home Assistant statestream namespace"),
        DENY_TWIN_TRAFFIC(false, "the twin's Home Assistant requests, responses and syncs of every MineQTT server are reserved"),
        DENY_STATESTREAM_SOURCE(false, "the Home Assistant statestream namespace is mirrored through Home Assistant bindings only (twin.entity)"),
        DENY_DEVICE_TOPICS(false, "this server's device and group topics are shown only to players who may configure them (only its bridge/ and raw/ subtrees are open)"),
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
     * @param statestreamBase the HA statestream base topic, or null/empty when none is configured; one trailing
     *                        {@code /} is dropped
     * @param rawAllow        filters players may publish into and link raw topics under
     */
    public record Rules(String base, String bridgeId, String discoveryPrefix, String statestreamBase,
                        List<String> rawAllow, boolean opsBypassAllowlist, boolean explorerBrokerWide) {
        public Rules {
            requireTopic("base topic", base);
            requireTopic("bridge id", bridgeId);
            if (bridgeId.indexOf('/') >= 0) throw new IllegalArgumentException("Bridge id must be a single topic level: " + bridgeId);
            requireTopic("discovery prefix", discoveryPrefix);
            // One trailing slash is dropped, as Home Assistant and StatestreamTopic.normalizeBase do, so the deny
            // filter covers the namespace the twin reads (base + "/#", not base + "//#").
            if (statestreamBase != null && statestreamBase.endsWith("/")) statestreamBase = statestreamBase.substring(0, statestreamBase.length() - 1);
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

    /**
     * A filter a player listens on with the Cyberdeck/Configurator explorer. Only admins listen on the statestream
     * namespace: it carries the state of every Home Assistant entity the HA admin included, presence among them.
     */
    public Decision checkListen(Subject subject, String filter) {
        Decision invalid = validate(filter, true);
        if (invalid != null) return invalid;
        boolean brokerWide = rules.explorerBrokerWide() && subject.admin();
        if (TopicFilters.isWildcard(TopicFilters.levels(filter)[0])) {
            return brokerWide ? Decision.ALLOWED : Decision.DENY_BROKER_WIDE;
        }
        if (!subject.admin() && rules.statestreamBase() != null && overlaps(rules.statestreamBase(), filter)) {
            return Decision.DENY_STATESTREAM_LISTEN;
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
     * The state topic of an MQTT device preset a device mirrors (M3 plan M3-17), judged for the device's owner: what
     * {@link #checkMirror} denies, one topic and no wildcard, and for every subject, admins included, the statestream
     * namespace ({@link Decision#DENY_STATESTREAM_SOURCE}) and the twin's request, response and sync traffic. A preset
     * such as the Generic topic takes any topic a player names, and what the device mirrors every client in range sees;
     * Home Assistant entities are read only through Home Assistant bindings, which {@code twin.allowedEntities}, the
     * catalog's labels and the privacy domains gate (D2, D13), and the twin traffic carries entity ids and player names.
     * Nor this server's own device and group topics ({@link Decision#DENY_DEVICE_TOPICS}): a bound
     * device's state names its Home Assistant entity (D13 #7), and the explorer shows a device's topics only to players
     * who may configure it, which a mirror's viewers need not.
     */
    public Decision checkTopicSource(Subject subject, String topic) {
        Decision invalid = validate(topic, false);
        if (invalid != null) return invalid;
        Decision mirror = checkMirror(subject, topic);
        if (!mirror.allowed()) return mirror;
        if (rules.statestreamBase() != null && overlaps(rules.statestreamBase(), topic)) return Decision.DENY_STATESTREAM_SOURCE;
        if (reachesDeviceTopics(topic)) return Decision.DENY_DEVICE_TOPICS;
        return reachesTwinTraffic(topic) ? Decision.DENY_TWIN_TRAFFIC : Decision.ALLOWED;
    }

    /**
     * The hard deny alone, for the v1 topic a legacy group follows and echoes its state to (design §6.6; the allowlist
     * does not apply to it, so no v1 world breaks). Only MQTT validity is checked, not the 256-byte limit, because v1
     * topics were never limited. A group has no admin, owns no private subtree, and nothing filters its messages
     * afterwards: any player who sees the group's lamps sees what it receives. So it listens on no filter that can reach
     * a player's private topics, the statestream namespace (M3 plan D13: only admins listen there) or the twin's request,
     * response and sync traffic of any bridge, whose payloads the explorer shows non-admins only as sizes, nor this
     * server's device and group topics (a bound device's state names its entity, and the explorer shows
     * them only to players who may configure the device). The discovery prefix stays open to listeners, as it was to v1
     * blocks, which used it for HA's birth topic. Every other v1 topic is a device's grandfathered Raw Link, judged by
     * {@link #checkPublish(Subject, String, Grandfather)}.
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
        if (rules.statestreamBase() != null && overlaps(rules.statestreamBase(), topicOrFilter)) return Decision.DENY_STATESTREAM_LEGACY;
        if (reachesForeignPrivate(Subject.CONSOLE, topicOrFilter)) return Decision.DENY_FOREIGN_PRIVATE;
        if (reachesDeviceTopics(topicOrFilter)) return Decision.DENY_DEVICE_TOPICS;
        return reachesTwinTraffic(topicOrFilter) ? Decision.DENY_TWIN_TRAFFIC : Decision.ALLOWED;
    }

    /**
     * Whether the topic or filter can match one of this server's device or group topics: anything below
     * {@code <base>/<bridgeId>} but its {@code bridge/} and {@code raw/} subtrees and the bare root, which the explorer
     * shows everyone ({@link #explorerExposure}).
     */
    private boolean reachesDeviceTopics(String filter) {
        if (!overlaps(bridgeRoot, filter)) return false;
        String[] levels = TopicFilters.levels(filter);
        int first = TopicFilters.levels(bridgeRoot).length;
        // A "#" above the first level below the root reaches every device.
        for (int i = 0; i < Math.min(first, levels.length); i++) {
            if (levels[i].equals("#")) return true;
        }
        if (levels.length == first) return false;
        return !levels[first].equals(BRIDGE_LEVEL) && !levels[first].equals(RAW_LEVEL);
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
     * scope reaches further than a player may look, so every message is judged on its own (design §8.4). Non-admins see
     * the twin's request, response and sync traffic of any bridge only as sizes, and no statestream message at all (a
     * filter wider than a multi-level statestream base can still deliver one).
     */
    public Exposure explorerExposure(Subject subject, String topic) {
        if (!subject.admin() && twinTraffic(topic)) return Exposure.REDACT;
        if (!subject.admin() && rules.statestreamBase() != null && TopicFilters.covers(rules.statestreamBase() + "/#", topic)) {
            return Exposure.HIDE;
        }
        if (TopicFilters.covers(bridgeRoot + "/#", topic)) {
            String[] levels = TopicFilters.levels(topic);
            int first = TopicFilters.levels(bridgeRoot).length;
            if (levels.length == first) return Exposure.SHOW;
            return switch (levels[first]) {
                case BRIDGE_LEVEL -> Exposure.SHOW;
                case RAW_LEVEL -> intoForeignPrivate(subject, topic) ? Exposure.HIDE : Exposure.SHOW;
                case GROUP_LEVEL -> new Exposure(Exposure.Kind.GROUP, levels.length > first + 1 ? levels[first + 1] : "");
                case PLAYER_LEVEL -> new Exposure(Exposure.Kind.PLAYER, levels.length > first + 1 ? levels[first + 1] : "");
                default -> new Exposure(Exposure.Kind.DEVICE, levels[first]);
            };
        }
        return redactPayload(topic) ? Exposure.REDACT : Exposure.SHOW;
    }

    /**
     * The explorer's verdict on one message.
     *
     * @param slug the device, group or presence device slug for {@link Kind#DEVICE}, {@link Kind#GROUP} and
     *             {@link Kind#PLAYER}, else empty
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
            GROUP,
            /**
             * A player's presence device, {@code player/<slug>} (M4 plan D10): shown only to admins and to the player whose
             * device it is; the bare {@code player} level belongs to no one.
             */
            PLAYER
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
        if (inBridgeNamespace(topic)) return Decision.DENY_FOREIGN_BRIDGE;
        return null;
    }

    /** Whether the topic lies in any bridge's {@code bridge/} namespace, under this base topic or the shared one. */
    private boolean inBridgeNamespace(String topic) {
        return TopicFilters.covers(rules.base() + "/+/" + BRIDGE_LEVEL + "/#", topic)
                || TopicFilters.covers(SHARED_BASE + "/+/" + BRIDGE_LEVEL + "/#", topic);
    }

    /** Whether the topic is a twin request, response or sync of any bridge, or lies below one. */
    private boolean twinTraffic(String topic) {
        String[] levels = TopicFilters.levels(topic);
        for (String base : List.of(rules.base(), SHARED_BASE)) {
            // <base>/<bridge>/bridge/<leaf>: the leaf is two levels below the base's own.
            int leaf = TopicFilters.levels(base).length + 2;
            if (levels.length > leaf && TopicFilters.covers(base + "/+/" + BRIDGE_LEVEL + "/#", topic)
                    && TWIN_TRAFFIC_LEAVES.contains(levels[leaf])) {
                return true;
            }
        }
        return false;
    }

    /** Whether the filter can match a twin request, response or sync of any bridge, or a topic below one. */
    private boolean reachesTwinTraffic(String filter) {
        for (String base : List.of(rules.base(), SHARED_BASE)) {
            for (String leaf : TWIN_TRAFFIC_LEAVES) {
                if (overlaps(base + "/+/" + BRIDGE_LEVEL + "/" + leaf, filter)) return true;
            }
        }
        return false;
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

    /**
     * Whether the subtree under {@code prefix} and the topics matched by {@code filter} share any topic. A {@code +} in
     * the prefix stands for any one level, so {@code mineqtt/+/bridge} is the subtree of every bridge's level.
     */
    private static boolean overlaps(String prefix, String filter) {
        String[] prefixLevels = TopicFilters.levels(prefix);
        String[] filterLevels = TopicFilters.levels(filter);
        for (int i = 0; i < prefixLevels.length; i++) {
            if (i >= filterLevels.length) return false;
            String level = filterLevels[i];
            if (level.equals("#")) return true;
            if (!level.equals("+") && !prefixLevels[i].equals("+") && !level.equals(prefixLevels[i])) return false;
        }
        return true;
    }
}

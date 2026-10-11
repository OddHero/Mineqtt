package art.rehra.mineqtt.twin.core;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Where a topic under the Home Assistant statestream base belongs (M3 plan D1, D3). Home Assistant's
 * {@code mqtt_statestream} publishes {@code <base>/<domain>/<object_id>/<leaf>}, replacing the entity id's {@code .}
 * with {@code /}, so an entity topic is exactly three levels below the base; the base itself may have several levels.
 * <p>
 * Domains starting with {@code _} are not Home Assistant's: its {@code VALID_DOMAIN} regex refuses a leading
 * underscore. MineQTT's catalog blueprint publishes under {@code <base>/_mineqtt/} for that reason, so
 * {@code <base>/_mineqtt/catalog/<domain>} and {@code <base>/_mineqtt/status} are recognised here and every other
 * {@code _}-domain topic is ignored, as is any topic of another shape.
 * <p>
 * Minecraft-free: the twin core uses only the JDK, Gson and {@code flow.core}.
 */
public sealed interface StatestreamTopic permits StatestreamTopic.Entity, StatestreamTopic.Catalog, StatestreamTopic.Status,
        StatestreamTopic.Ignored {
    /** The first level below the base under which the catalog blueprint publishes. */
    String MINEQTT_LEVEL = "_mineqtt";
    /** The state leaf: a raw string, never JSON. */
    String STATE = "state";
    /** The timestamp leaves: raw ISO-8601 strings, published with {@code publish_timestamps: true}. */
    String LAST_UPDATED = "last_updated";
    String LAST_CHANGED = "last_changed";

    /**
     * A Home Assistant domain as statestream can publish it: lowercase letters, digits and underscores, not starting
     * with an underscore (Home Assistant's {@code VALID_DOMAIN} is stricter still).
     */
    Pattern DOMAIN = Pattern.compile("[a-z0-9][a-z0-9_]*");
    /** An object id as statestream can publish it. */
    Pattern OBJECT_ID = Pattern.compile("[a-z0-9_]+");
    /** An entity id, {@code <domain>.<object_id>}. */
    Pattern ENTITY_ID = Pattern.compile("[a-z0-9][a-z0-9_]*\\.[a-z0-9_]+");

    /** Why a topic is not an entity, catalog or status topic. */
    enum Reason {
        /** Not below the base at all. */
        OUTSIDE_BASE,
        /** Below the base, but not three levels deep (or with an empty leaf). */
        SHAPE,
        /** A domain starting with {@code _} that is not the catalog or its status. */
        RESERVED_DOMAIN,
        /** Three levels, but the domain or object id is not one Home Assistant makes. */
        INVALID_ID
    }

    /** How a leaf is decoded. */
    enum LeafKind {
        /** {@code state}: raw UTF-8; zero length means cleared. */
        STATE,
        /** {@code last_updated}: raw ISO-8601. */
        LAST_UPDATED,
        /** {@code last_changed}: raw ISO-8601. */
        LAST_CHANGED,
        /** Any other leaf: one attribute, strict JSON. */
        ATTRIBUTE;

        public static LeafKind of(String leaf) {
            return switch (leaf) {
                case StatestreamTopic.STATE -> STATE;
                case StatestreamTopic.LAST_UPDATED -> LAST_UPDATED;
                case StatestreamTopic.LAST_CHANGED -> LAST_CHANGED;
                default -> ATTRIBUTE;
            };
        }
    }

    /**
     * Classifies {@code topic} against {@code base} (as {@link #normalizeBase} returns it). Never throws for a topic:
     * anything unexpected is {@link Ignored} with its reason, so the caller can count it.
     */
    static StatestreamTopic parse(String base, String topic) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(topic, "topic");
        if (topic.length() <= base.length() + 1 || !topic.startsWith(base) || topic.charAt(base.length()) != '/') {
            return new Ignored(Reason.OUTSIDE_BASE);
        }
        String[] levels = topic.substring(base.length() + 1).split("/", -1);
        if (levels[0].startsWith("_")) {
            if (levels[0].equals(MINEQTT_LEVEL)) {
                if (levels.length == 2 && levels[1].equals("status")) return new Status();
                if (levels.length == 3 && levels[1].equals("catalog") && DOMAIN.matcher(levels[2]).matches()) {
                    return new Catalog(levels[2]);
                }
            }
            return new Ignored(Reason.RESERVED_DOMAIN);
        }
        if (levels.length != 3 || levels[2].isEmpty()) return new Ignored(Reason.SHAPE);
        if (!DOMAIN.matcher(levels[0]).matches() || !OBJECT_ID.matcher(levels[1]).matches()) return new Ignored(Reason.INVALID_ID);
        return new Entity(levels[0], levels[1], levels[2]);
    }

    /**
     * The base as topics are matched against it. Home Assistant appends a {@code /} to its {@code base_topic} when
     * missing, so {@code ha_statestream/} and {@code ha_statestream} publish alike and one trailing slash is dropped.
     *
     * @throws IllegalArgumentException for an empty base, a wildcard, a NUL, an empty level or a leading {@code /}
     */
    static String normalizeBase(String base) {
        Objects.requireNonNull(base, "base");
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (normalized.isEmpty()) throw new IllegalArgumentException("The statestream base must not be empty");
        if (normalized.indexOf('+') >= 0 || normalized.indexOf('#') >= 0 || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("The statestream base must be a topic without wildcards: " + base);
        }
        for (String level : normalized.split("/", -1)) {
            if (level.isEmpty()) throw new IllegalArgumentException("The statestream base must not have empty levels: " + base);
        }
        return normalized;
    }

    /** Whether {@code entityId} is {@code <domain>.<object_id>} as statestream publishes it. */
    static boolean isEntityId(String entityId) {
        return entityId != null && ENTITY_ID.matcher(entityId).matches();
    }

    /** The topic of one leaf of an entity: what a mirror subscribes to (D1 #8: per needed leaf, never {@code +}). */
    static String topic(String base, String entityId, String leaf) {
        if (!isEntityId(entityId)) throw new IllegalArgumentException("Not an entity id: " + entityId);
        if (leaf.isEmpty() || leaf.indexOf('/') >= 0 || leaf.indexOf('+') >= 0 || leaf.indexOf('#') >= 0) {
            throw new IllegalArgumentException("Not a statestream leaf: " + leaf);
        }
        return base + "/" + entityId.replace('.', '/') + "/" + leaf;
    }

    /** The retained catalog list of one domain. */
    static String catalogTopic(String base, String domain) {
        if (!DOMAIN.matcher(domain).matches()) throw new IllegalArgumentException("Not a domain: " + domain);
        return base + "/" + MINEQTT_LEVEL + "/catalog/" + domain;
    }

    /** The catalog blueprint's retained status. */
    static String statusTopic(String base) {
        return base + "/" + MINEQTT_LEVEL + "/status";
    }

    /** One leaf of one entity. */
    record Entity(String domain, String objectId, String leaf) implements StatestreamTopic {
        public Entity {
            if (!DOMAIN.matcher(domain).matches()) throw new IllegalArgumentException("Not a domain: " + domain);
            if (!OBJECT_ID.matcher(objectId).matches()) throw new IllegalArgumentException("Not an object id: " + objectId);
            if (leaf.isEmpty()) throw new IllegalArgumentException("A leaf must not be empty");
        }

        public String entityId() {
            return domain + "." + objectId;
        }

        public LeafKind kind() {
            return LeafKind.of(leaf);
        }
    }

    /** {@code <base>/_mineqtt/catalog/<domain>}: the catalog list of one domain. */
    record Catalog(String domain) implements StatestreamTopic {
    }

    /** {@code <base>/_mineqtt/status}: the catalog blueprint's status. */
    record Status() implements StatestreamTopic {
    }

    /** Not a topic the twin reads; counted by its reason. */
    record Ignored(Reason reason) implements StatestreamTopic {
    }
}

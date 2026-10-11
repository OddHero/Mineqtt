package art.rehra.mineqtt.flow.core;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One end of a {@link Wire}, or the source of a {@link FlowMessage} (M2 plan §3.2): a port of a node, keyed by UUID and
 * port key, never by position, so it survives renames, moves and unloads. Both ends of a wire are endpoints, so MQTT-in
 * and Home Assistant sources fit without a schema change. {@link HaEntityRef} and {@link TopicRef} are the ends of wires
 * derived from device bindings (M3 plan §3.2, §3.3), which are never persisted: the registry codec still refuses them.
 */
public sealed interface Endpoint permits Endpoint.PortRef, Endpoint.GroupPortRef, Endpoint.TopicRef, Endpoint.HaEntityRef {
    /** A port key; device field and action keys are port ids, so they are checked against it too. */
    Pattern PORT_KEY = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    static PortRef port(UUID node, String port) {
        return new PortRef(node, port);
    }

    static GroupPortRef groupPort(UUID group, String port) {
        return new GroupPortRef(group, port);
    }

    /** Whether {@code key} is a valid port key. */
    static boolean isPortKey(String key) {
        return PORT_KEY.matcher(key).matches();
    }

    /**
     * Home Assistant's entity id as statestream publishes it, {@code <domain>.<object_id>} (M3 plan D1); the same shape
     * as {@code twin.core.StatestreamTopic.ENTITY_ID}, which this Minecraft-free core may not depend on.
     */
    Pattern ENTITY_ID = Pattern.compile("[a-z0-9][a-z0-9_]*\\.[a-z0-9_]+");

    /** Whether this kind of endpoint is reserved for a later milestone; none is now. */
    default boolean reserved() {
        return false;
    }

    /** A short description for logs and command output, e.g. {@code 3f9c…0001.state}. */
    String describe();

    /** A port of a device; logic blocks are devices too. */
    record PortRef(UUID node, String port) implements Endpoint {
        public PortRef {
            Objects.requireNonNull(node, "node");
            checkKey(port);
        }

        @Override
        public String describe() {
            return node + "." + port;
        }
    }

    /** A port of a device group: as a target, it fans out to every member. */
    record GroupPortRef(UUID group, String port) implements Endpoint {
        public GroupPortRef {
            Objects.requireNonNull(group, "group");
            checkKey(port);
        }

        @Override
        public String describe() {
            return "group " + group + "." + port;
        }
    }

    /**
     * A standalone MQTT topic as a source or a sink (M3 plan §3.2): a preset binding's topic (M3-17).
     *
     * @param path where the value sits in the payload, as {@code twin.core.ValuePath} spells it; {@code ""} for the whole
     *             payload
     */
    record TopicRef(String topic, String path) implements Endpoint {
        public TopicRef {
            if (topic.isEmpty()) throw new IllegalArgumentException("A topic endpoint needs a topic");
            Objects.requireNonNull(path, "path");
        }

        @Override
        public String describe() {
            return "topic " + topic + (path.isEmpty() ? "" : " " + path);
        }
    }

    /**
     * A port of a Home Assistant entity (M3 plan §3.2, D8), as a state source or a service-call sink:
     * {@code light.kitchen.brightness}.
     *
     * @param entityId the entity, {@code <domain>.<object_id>} ({@link #ENTITY_ID})
     * @param port     a port key of its domain ({@code twin.core.HaDomains}), or {@code attr_<key>} or {@code value} for
     *                 a generic read
     */
    record HaEntityRef(String entityId, String port) implements Endpoint {
        public HaEntityRef {
            if (!ENTITY_ID.matcher(entityId).matches()) throw new IllegalArgumentException("Not a Home Assistant entity id: " + entityId);
            checkKey(port);
        }

        @Override
        public String describe() {
            return "entity " + entityId + "." + port;
        }
    }

    private static void checkKey(String port) {
        if (!isPortKey(port)) throw new IllegalArgumentException("Invalid port key: " + port);
    }
}

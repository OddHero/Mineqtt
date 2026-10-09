package art.rehra.mineqtt.flow.core;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One end of a {@link Wire}, or the source of a {@link FlowMessage} (M2 plan §3.2): a port of a node, keyed by UUID and
 * port key, never by position, so it survives renames, moves and unloads. Both ends of a wire are endpoints, so MQTT-in
 * and Home Assistant sources fit later without a schema change. {@link TopicRef} and {@link HaEntityRef} are reserved:
 * declared so that every switch over endpoints stays exhaustive when they are built, but nothing makes them yet and the
 * registry codec refuses them.
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

    /** Whether this kind of endpoint is reserved for a later milestone. */
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

    /** Reserved: a standalone MQTT topic as a source or a sink. */
    record TopicRef(String topic) implements Endpoint {
        public TopicRef {
            if (topic.isEmpty()) throw new IllegalArgumentException("A topic endpoint needs a topic");
        }

        @Override
        public boolean reserved() {
            return true;
        }

        @Override
        public String describe() {
            return "topic " + topic;
        }
    }

    /** Reserved: a Home Assistant entity, as a state source or a service-call sink (M3). */
    record HaEntityRef(String entityId) implements Endpoint {
        public HaEntityRef {
            if (entityId.isEmpty()) throw new IllegalArgumentException("A Home Assistant endpoint needs an entity id");
        }

        @Override
        public boolean reserved() {
            return true;
        }

        @Override
        public String describe() {
            return "entity " + entityId;
        }
    }

    private static void checkKey(String port) {
        if (!isPortKey(port)) throw new IllegalArgumentException("Invalid port key: " + port);
    }
}

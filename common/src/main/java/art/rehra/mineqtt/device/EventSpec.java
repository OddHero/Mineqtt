package art.rehra.mineqtt.device;

import art.rehra.mineqtt.flow.core.PortType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * An event a device type declares (M2 plan §3.1, §3.8 #5): a press, a pulse, a timer firing. It is published on the
 * device's {@code action} topic as {@code {"event_type":"<type>", <attributes>}} and is an out-port of kind
 * {@code EVENT}. An event is never encoded as a state toggle, and a device publishes only the events its type declares.
 *
 * @param type       the event type, also the port id; frozen like field keys
 * @param attributes the keys every event of this type carries besides {@code event_type}, with their kinds
 */
public record EventSpec(String type, Map<String, PortType> attributes) {
    public EventSpec {
        // The port kind checks the type's spelling.
        PortType.event(type);
        attributes = Map.copyOf(attributes);
        if (attributes.containsKey(PortType.Event.TYPE_KEY)) throw new IllegalArgumentException("Event " + type + " declares " + PortType.Event.TYPE_KEY + " as an attribute");
    }

    /** An event of the type without attributes. */
    public static EventSpec of(String type) {
        return new EventSpec(type, Map.of());
    }

    /** The event with one more attribute. */
    public EventSpec attribute(String key, PortType kind) {
        Map<String, PortType> more = new LinkedHashMap<>(attributes);
        if (more.put(key, kind) != null) throw new IllegalArgumentException("Event " + type + " declares attribute " + key + " twice");
        return new EventSpec(type, more);
    }

    /** The kind of the event's out-port. */
    public PortType.Event kind() {
        return PortType.event(type);
    }

    /** {@code {"event_type":"<type>"}}, to which the publisher adds the attributes. */
    public JsonObject payload() {
        JsonObject event = new JsonObject();
        event.addProperty(PortType.Event.TYPE_KEY, type);
        return event;
    }

    /**
     * Why an event payload is no event of this type, or empty when it is one: its {@code event_type} is this type, it
     * carries every attribute in its kind, and nothing else.
     */
    public Optional<String> problem(JsonObject event) {
        JsonElement eventType = event.get(PortType.Event.TYPE_KEY);
        if (eventType == null || !eventType.isJsonPrimitive() || !eventType.getAsString().equals(type)) {
            return Optional.of("not a " + type + " event: " + event);
        }
        for (Map.Entry<String, PortType> attribute : attributes.entrySet()) {
            JsonElement value = event.get(attribute.getKey());
            if (value == null) return Optional.of("event " + type + " lacks attribute " + attribute.getKey());
            Optional<String> problem = attribute.getValue().problem(value);
            if (problem.isPresent()) return Optional.of("event " + type + " attribute " + attribute.getKey() + ": " + problem.get());
        }
        for (String key : event.keySet()) {
            if (!key.equals(PortType.Event.TYPE_KEY) && !attributes.containsKey(key)) return Optional.of("event " + type + " has undeclared attribute " + key);
        }
        return Optional.empty();
    }
}

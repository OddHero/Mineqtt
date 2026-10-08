package art.rehra.mineqtt.ha;

import art.rehra.mineqtt.mqtt.core.Inbound;
import art.rehra.mineqtt.mqtt.core.MqttService;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.mqtt.core.SubOptions;
import art.rehra.mineqtt.mqtt.core.Subscription;
import art.rehra.mineqtt.runtime.TopicScheme;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The MineQTT Bridge device (design §4.3): the {@code via_device} of every device, identified by the world UUID so a
 * changed bridge id does not orphan it. It shows the connection, the version, the device count, the players online
 * and the dropped message counters, and has a button that republishes everything.
 * <ul>
 *     <li>{@code <root>/bridge} carries the telemetry, retained, published on change at most every 10 s.</li>
 *     <li>{@code bridge/request/<command>} takes requests (M1: {@code republish}); the answer goes to
 *     {@code bridge/response/<command>}. Retained requests are ignored.</li>
 * </ul>
 * Server thread only.
 */
public final class BridgeDevice {
    public static final String MODEL = "MineQTT Bridge";
    /** The shortest time between two telemetry publishes. */
    static final long TELEMETRY_INTERVAL_MILLIS = 10_000;
    private static final long SAMPLE_INTERVAL_MILLIS = 1_000;
    private static final String REPUBLISH = "republish";
    private static final Pattern COMMAND = Pattern.compile("[a-z0-9_]{1,32}");

    private final MqttService mqtt;
    private final Supplier<TopicScheme> topics;
    private final IntSupplier playersOnline;
    private final LongSupplier clock;
    private final Runnable republish;
    private @Nullable Subscription requests;
    private @Nullable TopicScheme requestTopics;
    private @Nullable JsonObject published;
    private long publishedAt;
    private long sampledAt;
    private boolean stopped;

    /**
     * @param republish runs for a {@code republish} request: discovery, availability and state of everything
     * @param clock     milliseconds
     */
    public BridgeDevice(MqttService mqtt, Supplier<TopicScheme> topics, IntSupplier playersOnline, LongSupplier clock, Runnable republish) {
        this.mqtt = mqtt;
        this.topics = topics;
        this.playersOnline = playersOnline;
        this.clock = clock;
        this.republish = republish;
        topicsChanged();
    }

    /** {@code mineqtt_bridge_<worldUuid32>}. */
    public static String objectId(UUID worldUuid) {
        return "mineqtt_bridge_" + worldUuid.toString().replace("-", "");
    }

    /** The bridge's device discovery message. */
    public static JsonObject discovery(TopicScheme topics, DiscoveryPublisher.Identity identity) {
        String objectId = identity.bridgeObjectId();
        JsonObject device = new JsonObject();
        JsonArray ids = new JsonArray();
        ids.add(objectId);
        device.add("ids", ids);
        device.addProperty("name", identity.worldName());
        device.addProperty("mf", DiscoveryPublisher.MANUFACTURER);
        device.addProperty("mdl", MODEL);
        device.addProperty("sw", identity.version());
        JsonObject config = new JsonObject();
        config.add("dev", device);
        config.add("o", DiscoveryPublisher.origin(identity.version()));
        config.addProperty("qos", 1);

        JsonObject components = new JsonObject();
        // Connectivity reads the bridge state itself: it shows the bridge offline instead of turning unavailable,
        // so it has no availability while every other entity follows the bridge state.
        JsonObject connectivity = component(objectId, "connectivity", "binary_sensor", "Connectivity", topics.bridgeState(), null);
        connectivity.addProperty("device_class", "connectivity");
        connectivity.addProperty("payload_on", "online");
        connectivity.addProperty("payload_off", "offline");
        connectivity.addProperty("entity_category", "diagnostic");
        components.add("connectivity", connectivity);

        JsonObject version = component(objectId, "version", "sensor", "Version", topics.bridgeInfo(), topics.bridgeState());
        version.addProperty("value_template", "{{ value_json.version }}");
        version.addProperty("entity_category", "diagnostic");
        components.add("version", version);

        JsonObject deviceCount = component(objectId, "device_count", "sensor", "Device count", topics.bridgeInfo(), topics.bridgeState());
        deviceCount.addProperty("value_template", "{{ value_json.devices }}");
        deviceCount.addProperty("state_class", "measurement");
        deviceCount.addProperty("entity_category", "diagnostic");
        components.add("device_count", deviceCount);

        JsonObject players = component(objectId, "players_online", "sensor", "Players online", topics.bridgeTelemetry(), topics.bridgeState());
        players.addProperty("value_template", "{{ value_json.players_online }}");
        players.addProperty("state_class", "measurement");
        components.add("players_online", players);

        JsonObject button = component(objectId, REPUBLISH, "button", "Republish", null, topics.bridgeState());
        button.addProperty("command_topic", topics.bridgeRequest(REPUBLISH));
        button.addProperty("payload_press", REPUBLISH);
        button.addProperty("retain", false);
        button.addProperty("entity_category", "config");
        components.add(REPUBLISH, button);

        for (String direction : new String[]{"inbound", "outbound"}) {
            String key = "dropped_" + direction;
            JsonObject dropped = component(objectId, key, "sensor", "Dropped " + direction, topics.bridgeTelemetry(), topics.bridgeState());
            dropped.addProperty("value_template", "{{ value_json." + key + " }}");
            dropped.addProperty("state_class", "total_increasing");
            dropped.addProperty("entity_category", "diagnostic");
            dropped.addProperty("enabled_by_default", false);
            components.add(key, dropped);
        }
        config.add("cmps", components);
        return config;
    }

    private static JsonObject component(String objectId, String key, String platform, String name, @Nullable String stateTopic,
                                        @Nullable String availabilityTopic) {
        JsonObject component = new JsonObject();
        component.addProperty("p", platform);
        component.addProperty("unique_id", objectId + "_" + key);
        component.addProperty("name", name);
        if (stateTopic != null) component.addProperty("state_topic", stateTopic);
        if (availabilityTopic != null) {
            JsonObject entry = new JsonObject();
            entry.addProperty("topic", availabilityTopic);
            JsonArray availability = new JsonArray();
            availability.add(entry);
            component.add("availability", availability);
        }
        return component;
    }

    /** After a reconnect: the bridge id or base topic may have changed, which moves the request filter. */
    public void topicsChanged() {
        if (stopped || topics.get().equals(requestTopics)) return;
        if (requests != null) requests.close();
        requestTopics = topics.get();
        requests = mqtt.subscribe(requestTopics.bridgeRequests(), SubOptions.COMMAND, this::onRequest);
    }

    /** After a connect or for a republish: the telemetry now, also when unchanged. */
    public void publishTelemetry() {
        if (stopped) return;
        JsonObject telemetry = telemetry();
        mqtt.publish(Outbound.state(topics.get().bridgeTelemetry(), telemetry).asRefresh());
        published = telemetry;
        publishedAt = clock.getAsLong();
    }

    /**
     * {@code SERVER_POST}: samples the telemetry once a second and publishes a change once the interval is over. Not
     * while the broker is unreachable, nor before the first connect: every connect publishes it anyway.
     */
    public void tick() {
        long now = clock.getAsLong();
        if (stopped || published == null || !mqtt.state().isConnected() || now - sampledAt < SAMPLE_INTERVAL_MILLIS) return;
        sampledAt = now;
        if (now - publishedAt < TELEMETRY_INTERVAL_MILLIS) return;
        JsonObject telemetry = telemetry();
        if (telemetry.equals(published)) return;
        mqtt.publish(Outbound.state(topics.get().bridgeTelemetry(), telemetry));
        published = telemetry;
        publishedAt = now;
    }

    /** {@code SERVER_STOPPING}. */
    public void stop() {
        stopped = true;
        if (requests != null) requests.close();
        requests = null;
    }

    private JsonObject telemetry() {
        MqttService.Stats stats = mqtt.stats();
        JsonObject telemetry = new JsonObject();
        telemetry.addProperty("players_online", playersOnline.getAsInt());
        telemetry.addProperty("dropped_inbound", stats.droppedInbound());
        telemetry.addProperty("dropped_outbound", stats.droppedOutbound());
        return telemetry;
    }

    private void onRequest(Inbound message) {
        String command = message.topic().substring(message.topic().lastIndexOf('/') + 1);
        // An empty payload is someone clearing a retained request.
        if (stopped || message.payload().length == 0 || !COMMAND.matcher(command).matches()
                || !message.topic().equals(topics.get().bridgeRequest(command))) {
            return;
        }
        JsonObject response = new JsonObject();
        if (command.equals(REPUBLISH)) {
            republish.run();
            response.addProperty("status", "ok");
        } else {
            response.addProperty("status", "error");
            response.addProperty("error", "unknown request " + command);
        }
        mqtt.publish(Outbound.event(topics.get().bridgeResponse(command), response));
    }
}

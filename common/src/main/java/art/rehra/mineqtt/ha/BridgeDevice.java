package art.rehra.mineqtt.ha;

import art.rehra.mineqtt.mqtt.core.Inbound;
import art.rehra.mineqtt.mqtt.core.MqttLog;
import art.rehra.mineqtt.mqtt.core.MqttService;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.mqtt.core.SubOptions;
import art.rehra.mineqtt.mqtt.core.Subscription;
import art.rehra.mineqtt.runtime.TopicScheme;
import art.rehra.mineqtt.telemetry.core.BroadcastText;
import art.rehra.mineqtt.telemetry.core.EventLimiter;
import art.rehra.mineqtt.telemetry.core.PlayerEventMessage;
import art.rehra.mineqtt.telemetry.core.TelemetrySnapshot;
import art.rehra.mineqtt.telemetry.core.TelemetryThrottle;
import art.rehra.mineqtt.telemetry.core.Weather;
import art.rehra.mineqtt.telemetry.core.WorldReading;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The MineQTT Bridge device (design §4.3): the {@code via_device} of every device, identified by the world UUID so a
 * changed bridge id does not orphan it. It shows the connection, the version, the device count, the players online
 * and the dropped message counters, the world's time, day, weather and server load (M4 plan D6), and has a button
 * that republishes everything.
 * <ul>
 *     <li>{@code <root>/bridge} carries the telemetry ({@link TelemetrySnapshot}), retained, sampled once a second and
 *     published as {@link TelemetryThrottle} allows.</li>
 *     <li>{@code bridge/event} carries the player events ({@link PlayerEventMessage}), never retained.</li>
 *     <li>{@code bridge/request/<command>} takes requests (M1: {@code republish}); the answer goes to
 *     {@code bridge/response/<command>}. Retained requests are ignored.</li>
 *     <li>{@code bridge/request/broadcast} (M4 plan D8) shows its text in every player's chat behind a fixed prefix, only
 *     while {@code telemetry.allowBroadcast} is on, cleaned and limited by {@link BroadcastText}; each text shown is
 *     logged with its topic. While it is on, the bridge has a {@code broadcast} text entity that sends to it. Nothing
 *     reaches a command: the text is never parsed, only shown.</li>
 * </ul>
 * Server thread only.
 */
public final class BridgeDevice {
    public static final String MODEL = "MineQTT Bridge";
    private static final String REPUBLISH = "republish";
    /** The broadcast request, and the key of its text entity. */
    public static final String BROADCAST = "broadcast";
    /**
     * The longest text the broadcast entity lets Home Assistant send: Home Assistant refuses a text entity whose
     * {@code max} is over its state length of 255, one less than {@link BroadcastText#MAX_LENGTH}.
     */
    public static final int BROADCAST_ENTITY_MAX = 255;
    private static final Pattern COMMAND = Pattern.compile("[a-z0-9_]{1,32}");

    private final MqttService mqtt;
    private final Supplier<TopicScheme> topics;
    private final Supplier<WorldReading> world;
    private final Supplier<Settings> settings;
    private final LongSupplier clock;
    private final Runnable republish;
    private final Consumer<String> broadcast;
    private final MqttLog log;
    private final EventLimiter events;
    private final BroadcastText.Limiter broadcasts;
    private @Nullable Subscription requests;
    private @Nullable TopicScheme requestTopics;
    private @Nullable TelemetrySnapshot published;
    private long publishedAt;
    private long sampledAt;
    private boolean stopped;

    /**
     * The telemetry config, read at every sample.
     *
     * @param worldKeys       {@code telemetry.enabled}: false publishes only the player count and the drop counters
     * @param intervalSeconds {@code telemetry.intervalSeconds}
     * @param broadcast       {@code telemetry.allowBroadcast}: whether broadcast requests are shown
     */
    public record Settings(boolean worldKeys, int intervalSeconds, boolean broadcast) {
        /** Settings with broadcast text off. */
        public Settings(boolean worldKeys, int intervalSeconds) {
            this(worldKeys, intervalSeconds, false);
        }
    }

    /**
     * @param world     the world's reading for a sample, taken on the server thread
     * @param republish runs for a {@code republish} request: discovery, availability and state of everything
     * @param clock     milliseconds
     * @param broadcast shows a broadcast text, cleaned, in every player's chat behind the fixed prefix
     */
    public BridgeDevice(MqttService mqtt, Supplier<TopicScheme> topics, Supplier<WorldReading> world, Supplier<Settings> settings,
                        LongSupplier clock, Runnable republish, Consumer<String> broadcast, MqttLog log) {
        this.mqtt = mqtt;
        this.topics = topics;
        this.world = world;
        this.settings = settings;
        this.clock = clock;
        this.republish = republish;
        this.broadcast = broadcast;
        this.log = log;
        this.events = new EventLimiter(clock);
        this.broadcasts = new BroadcastText.Limiter(clock);
        topicsChanged();
    }

    /** {@code mineqtt_bridge_<worldUuid32>}. */
    public static String objectId(UUID worldUuid) {
        return "mineqtt_bridge_" + worldUuid.toString().replace("-", "");
    }

    /** The bridge's device discovery message without the broadcast text entity. */
    public static JsonObject discovery(TopicScheme topics, DiscoveryPublisher.Identity identity) {
        return discovery(topics, identity, false);
    }

    /**
     * The bridge's device discovery message.
     *
     * @param broadcast {@code telemetry.allowBroadcast}: the {@code broadcast} text entity is discovered only while it is on
     */
    public static JsonObject discovery(TopicScheme topics, DiscoveryPublisher.Identity identity, boolean broadcast) {
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
        // The names of the players who may be named (M4 plan D9) as its attribute; absent with telemetry.enabled off.
        players.addProperty("json_attributes_topic", topics.bridgeTelemetry());
        players.addProperty("json_attributes_template", "{{ {'players': value_json.players if value_json.players is defined else []} | tojson }}");
        components.add("players_online", players);

        // The world keys (M4 plan D6) are absent with telemetry.enabled off; their entities then read unknown.
        JsonObject tps = component(objectId, "tps", "sensor", "TPS", topics.bridgeTelemetry(), topics.bridgeState());
        tps.addProperty("value_template", worldValue("tps"));
        tps.addProperty("state_class", "measurement");
        tps.addProperty("suggested_display_precision", 1);
        tps.addProperty("entity_category", "diagnostic");
        components.add("tps", tps);

        JsonObject mspt = component(objectId, "mspt", "sensor", "MSPT", topics.bridgeTelemetry(), topics.bridgeState());
        mspt.addProperty("value_template", worldValue("mspt"));
        mspt.addProperty("device_class", "duration");
        mspt.addProperty("unit_of_measurement", "ms");
        mspt.addProperty("state_class", "measurement");
        mspt.addProperty("suggested_display_precision", 1);
        mspt.addProperty("entity_category", "diagnostic");
        mspt.addProperty("enabled_by_default", false);
        components.add("mspt", mspt);

        JsonObject timeOfDay = component(objectId, "time_of_day", "sensor", "Time of day", topics.bridgeTelemetry(), topics.bridgeState());
        timeOfDay.addProperty("value_template", worldValue("time_of_day"));
        components.add("time_of_day", timeOfDay);

        // A measurement, not total_increasing: /time set backwards would read as a meter reset in the statistics.
        JsonObject day = component(objectId, "day", "sensor", "Day", topics.bridgeTelemetry(), topics.bridgeState());
        day.addProperty("value_template", worldValue("day"));
        day.addProperty("state_class", "measurement");
        components.add("day", day);

        JsonObject isDay = component(objectId, "is_day", "binary_sensor", "Is day", topics.bridgeTelemetry(), topics.bridgeState());
        isDay.addProperty("value_template", "{{ ('ON' if value_json.is_day else 'OFF') if value_json.is_day is defined else None }}");
        components.add("is_day", isDay);

        JsonObject weather = component(objectId, "weather", "sensor", "Weather", topics.bridgeTelemetry(), topics.bridgeState());
        weather.addProperty("value_template", worldValue("weather"));
        weather.addProperty("device_class", "enum");
        JsonArray options = new JsonArray();
        for (Weather option : Weather.values()) options.add(option.key());
        weather.add("options", options);
        components.add("weather", weather);

        // M4 plan D7: join and leave of every player (named only with consent), death and advancement with consent only.
        JsonObject playerEvent = component(objectId, "player_event", "event", "Player event", topics.bridgeEvent(), topics.bridgeState());
        JsonArray eventTypes = new JsonArray();
        for (PlayerEventMessage.Type type : PlayerEventMessage.Type.values()) eventTypes.add(type.key());
        playerEvent.add("event_types", eventTypes);
        components.add("player_event", playerEvent);

        if (broadcast) components.add(BROADCAST, broadcastComponent(objectId, topics));

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

    /**
     * The {@code broadcast} text entity (M4 plan D6, D8): it sends to {@code bridge/request/broadcast}, never retained,
     * and has no state topic, so Home Assistant shows the text it sent last (optimistic).
     */
    public static JsonObject broadcastComponent(String objectId, TopicScheme topics) {
        JsonObject text = component(objectId, BROADCAST, "text", "Broadcast", null, topics.bridgeState());
        text.addProperty("command_topic", topics.bridgeRequest(BROADCAST));
        text.addProperty("retain", false);
        text.addProperty("max", BROADCAST_ENTITY_MAX);
        text.addProperty("entity_category", "config");
        return text;
    }

    /** The template of a world key: {@code None} (unknown) while the telemetry leaves the world keys out. */
    private static String worldValue(String key) {
        return "{{ value_json." + key + " if value_json." + key + " is defined else None }}";
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
        TelemetrySnapshot telemetry = telemetry();
        mqtt.publish(Outbound.state(topics.get().bridgeTelemetry(), telemetry.toJson()).asRefresh());
        published = telemetry;
        publishedAt = clock.getAsLong();
    }

    /**
     * {@code SERVER_POST}: samples the telemetry once a second and publishes it when {@link TelemetryThrottle#due} says
     * so. Not while the broker is unreachable, nor before the first connect: every connect publishes it anyway.
     */
    public void tick() {
        long now = clock.getAsLong();
        if (stopped || published == null || !mqtt.state().isConnected() || now - sampledAt < TelemetryThrottle.SAMPLE_MILLIS) return;
        sampledAt = now;
        TelemetrySnapshot telemetry = telemetry();
        if (!TelemetryThrottle.due(published, publishedAt, telemetry, now, settings.get().intervalSeconds())) return;
        mqtt.publish(Outbound.state(topics.get().bridgeTelemetry(), telemetry.toJson()));
        published = telemetry;
        publishedAt = now;
    }

    /**
     * A player event (M4 plan D7) on {@code bridge/event}: not retained, dropped while the broker is unreachable, within
     * the caps of {@link EventLimiter}; an event over the bridge cap counts in the telemetry's {@code dropped_outbound}.
     */
    public void publishPlayerEvent(PlayerEventMessage event) {
        if (stopped || events.admit(event) != EventLimiter.Verdict.PUBLISH) return;
        mqtt.publish(Outbound.event(topics.get().bridgeEvent(), event.toJson()));
    }

    /** The telemetry published last; empty before the first connect. */
    public Optional<TelemetrySnapshot> lastTelemetry() {
        return Optional.ofNullable(published);
    }

    /** {@code SERVER_STOPPING}. */
    public void stop() {
        stopped = true;
        if (requests != null) requests.close();
        requests = null;
    }

    private TelemetrySnapshot telemetry() {
        MqttService.Stats stats = mqtt.stats();
        return TelemetrySnapshot.of(world.get(), settings.get().worldKeys(), stats.droppedInbound(), stats.droppedOutbound() + events.dropped());
    }

    private void onRequest(Inbound message) {
        String command = message.topic().substring(message.topic().lastIndexOf('/') + 1);
        // An empty payload is someone clearing a retained request; a retained request is a stale one.
        if (stopped || message.payload().length == 0 || message.retained() || !COMMAND.matcher(command).matches()
                || !message.topic().equals(topics.get().bridgeRequest(command))) {
            return;
        }
        JsonObject response = new JsonObject();
        if (command.equals(REPUBLISH)) {
            republish.run();
            response.addProperty("status", "ok");
        } else if (command.equals(BROADCAST)) {
            Optional<String> refused = broadcast(message);
            response.addProperty("status", refused.isEmpty() ? "ok" : "error");
            refused.ifPresent(error -> response.addProperty("error", error));
        } else {
            response.addProperty("status", "error");
            response.addProperty("error", "unknown request " + command);
        }
        mqtt.publish(Outbound.event(topics.get().bridgeResponse(command), response));
    }

    // Shows a broadcast request's text, or says why not.
    private Optional<String> broadcast(Inbound message) {
        if (!settings.get().broadcast()) return Optional.of("broadcast disabled");
        return switch (BroadcastText.parse(message.payload())) {
            case BroadcastText.Refused refused -> Optional.of(refused.error());
            case BroadcastText.Accepted accepted -> {
                if (!broadcasts.admit()) yield Optional.of("rate limited");
                log.info("Broadcast text from " + message.topic() + ": " + accepted.text());
                broadcast.accept(accepted.text());
                yield Optional.empty();
            }
        };
    }
}

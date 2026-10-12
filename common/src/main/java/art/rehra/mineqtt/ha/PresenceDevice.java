package art.rehra.mineqtt.ha;

import art.rehra.mineqtt.presence.FullSample;
import art.rehra.mineqtt.runtime.TopicScheme;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Home Assistant device of a player who consents (M4 plan D10): "Player Steve", model {@value #MODEL}, under the
 * bridge, available while the bridge is. It is no registry device: the player's entry in the registry's {@code players}
 * list holds its slug, and {@code presence.PresenceRuntime} publishes its discovery and state.
 * <ul>
 *     <li>Its object id is {@code mineqtt_player_<first 32 hex of sha256(worldUuid ‖ playerUuid)>}, so neither Home
 *     Assistant nor the broker ever sees the Mojang UUID, and the same player on two servers is two unrelated devices.</li>
 *     <li>Its state topic is {@code <base>/<bridge>/player/<slug>}: {@code {"presence":"ON"}} or {@code OFF}.</li>
 *     <li>With full sharing (consent {@code full} on a server in {@code telemetry.players=full}) the state adds
 *     {@code health}, {@code dimension}, {@code x}, {@code y} and {@code z} ({@link FullSample}) while the player is
 *     online, and the device the {@link #FULL_KEYS} sensors.</li>
 * </ul>
 */
public final class PresenceDevice {
    public static final String MODEL = "MineQTT Player";
    /** What every presence device's object id starts with; {@link GhostCleanup} knows the form. */
    public static final String OBJECT_ID_PREFIX = "mineqtt_player_";
    /** The presence key of the state, and its binary sensor's key. */
    public static final String PRESENCE = "presence";
    public static final String ON = "ON";
    public static final String OFF = "OFF";
    /** The components only full sharing adds: health, dimension and position. */
    public static final List<String> FULL_KEYS = List.of("health", "dimension", "position");

    private PresenceDevice() {
    }

    /** {@code mineqtt_player_<first 32 hex of sha256(worldUuid ‖ playerUuid)>}: salted with the world, never reversible. */
    public static String objectId(UUID worldUuid, UUID player) {
        ByteBuffer bytes = ByteBuffer.allocate(32);
        bytes.putLong(worldUuid.getMostSignificantBits()).putLong(worldUuid.getLeastSignificantBits());
        bytes.putLong(player.getMostSignificantBits()).putLong(player.getLeastSignificantBits());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.array());
            return OBJECT_ID_PREFIX + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM has SHA-256", e);
        }
    }

    /** The name Home Assistant shows: {@code Player <name>}. */
    public static String name(String playerName) {
        return "Player " + playerName;
    }

    /**
     * The device discovery message: the presence binary sensor (the device's primary entity, so it has no name of its
     * own), on the player's state topic, available while the bridge is; with full sharing also {@link #fullComponents}.
     *
     * @param objectId   {@link #objectId}
     * @param playerName the player's current name; only Home Assistant's name follows a rename, the slug stays
     * @param slug       the player's topic level under {@code player/}
     * @param full       whether the player shares health, dimension and position
     */
    public static JsonObject discovery(TopicScheme topics, DiscoveryPublisher.Identity identity, String objectId, String playerName, String slug,
                                       boolean full) {
        JsonObject device = new JsonObject();
        JsonArray ids = new JsonArray();
        ids.add(objectId);
        device.add("ids", ids);
        device.addProperty("name", name(playerName));
        device.addProperty("mf", DiscoveryPublisher.MANUFACTURER);
        device.addProperty("mdl", MODEL);
        device.addProperty("sw", identity.version());
        device.addProperty("via_device", identity.bridgeObjectId());
        JsonObject config = new JsonObject();
        config.add("dev", device);
        config.add("o", DiscoveryPublisher.origin(identity.version()));
        JsonArray availability = new JsonArray();
        JsonObject bridge = new JsonObject();
        bridge.addProperty("t", topics.bridgeState());
        availability.add(bridge);
        config.add("avty", availability);
        config.addProperty("qos", 1);

        String state = topics.player(slug);
        JsonObject components = new JsonObject();
        JsonObject presence = new JsonObject();
        presence.addProperty("p", "binary_sensor");
        presence.addProperty("unique_id", objectId + "_" + PRESENCE);
        presence.add("name", JsonNull.INSTANCE);
        presence.addProperty("state_topic", state);
        presence.addProperty("value_template", "{{ value_json." + PRESENCE + " }}");
        presence.addProperty("payload_on", ON);
        presence.addProperty("payload_off", OFF);
        presence.addProperty("device_class", "presence");
        components.add(PRESENCE, presence);
        if (full) fullComponents(objectId, state).forEach(components::add);
        config.add("cmps", components);
        return config;
    }

    /**
     * The sensors of full sharing, by key: {@code health} (a measurement), {@code dimension} and {@code position} (state
     * {@code x, y, z}, with {@code x}, {@code y} and {@code z} as attributes). Each reads unknown while the state carries no
     * reading: the player is offline, or no longer shares them.
     *
     * @param stateTopic the device's {@code player/<slug>} topic
     */
    public static Map<String, JsonObject> fullComponents(String objectId, String stateTopic) {
        Map<String, JsonObject> components = new LinkedHashMap<>();
        JsonObject health = sensor(objectId, "health", "Health", stateTopic, "{{ value_json.health if value_json.health is defined else None }}");
        health.addProperty("state_class", "measurement");
        components.put("health", health);
        components.put("dimension", sensor(objectId, "dimension", "Dimension", stateTopic,
                "{{ value_json.dimension if value_json.dimension is defined else None }}"));
        JsonObject position = sensor(objectId, "position", "Position", stateTopic,
                "{{ (value_json.x ~ ', ' ~ value_json.y ~ ', ' ~ value_json.z) if value_json.x is defined else None }}");
        position.addProperty("json_attributes_topic", stateTopic);
        position.addProperty("json_attributes_template",
                "{{ ({'x': value_json.x, 'y': value_json.y, 'z': value_json.z} if value_json.x is defined else {}) | tojson }}");
        components.put("position", position);
        return components;
    }

    private static JsonObject sensor(String objectId, String key, String name, String stateTopic, String valueTemplate) {
        JsonObject sensor = new JsonObject();
        sensor.addProperty("p", "sensor");
        sensor.addProperty("unique_id", objectId + "_" + key);
        sensor.addProperty("name", name);
        sensor.addProperty("state_topic", stateTopic);
        sensor.addProperty("value_template", valueTemplate);
        return sensor;
    }

    /** The state of a presence device: {@code {"presence":"ON"}} or {@code {"presence":"OFF"}}. */
    public static JsonObject state(boolean online) {
        return state(online, Optional.empty());
    }

    /**
     * The state of a presence device, with a full reading while the player is online and shares it. An offline player's
     * state never carries one: no last-known position is kept.
     */
    public static JsonObject state(boolean online, Optional<FullSample> full) {
        JsonObject state = new JsonObject();
        state.addProperty(PRESENCE, online ? ON : OFF);
        if (online) full.ifPresent(sample -> sample.addTo(state));
        return state;
    }
}

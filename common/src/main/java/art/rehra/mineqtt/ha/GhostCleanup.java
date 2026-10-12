package art.rehra.mineqtt.ha;

import art.rehra.mineqtt.mqtt.core.Inbound;
import art.rehra.mineqtt.mqtt.core.MqttLog;
import art.rehra.mineqtt.mqtt.core.MqttService;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.mqtt.core.SubOptions;
import art.rehra.mineqtt.mqtt.core.Subscription;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Clears the Home Assistant discovery of devices this bridge no longer has (design §4.5): a device whose creation a
 * crash rolled back, or one removed while its clear could not reach the broker, leaves its retained
 * {@code <prefix>/device/mineqtt_<uuid32>/config} behind, and Home Assistant keeps showing it.
 * <p>
 * After every connect MineQTT subscribes to {@code <prefix>/device/+/config} for {@value #WINDOW_MILLIS} ms and
 * {@link #judge judges} each retained message: only a config whose object id is a device id, that names this bridge as
 * its {@code via_device} and whose id is neither a device nor a group of this world's registry is cleared, with an
 * empty retained message. A player's presence device ({@code mineqtt_player_<32hex>}, M4 plan D10) is judged the same
 * way against the players who have one now. The same window also scans the retained presence states under this
 * bridge's own {@code <base>/<bridge>/player/+} ({@link #judgeState}): a state whose slug no player holds now is cleared
 * too, since its topic names the player and a full reading in it holds their health and position. So a lost or
 * restored registry, or a consent withdrawn while the broker could not be reached, never leaves a player's device or
 * state behind. The discovery of other bridges and other integrations is never touched. Live messages are
 * ignored: a ghost is always a stored message, and a live config naming this bridge can only come from another server
 * running a copy of this world. The clears share the discovery rate limit. A connection lost during the scan ends it;
 * the next connect scans again.
 * <p>
 * Minecraft-free (JDK, Gson and {@code mqtt.core} only, which {@code verifyCoreIsolation} enforces), so the broker
 * integration check runs it against a real broker. Server thread only.
 */
public final class GhostCleanup {
    /** How long the scan listens after a connect. */
    public static final long WINDOW_MILLIS = 5_000;
    /** Larger configs are skipped before they are parsed; no MineQTT device config comes near it. */
    public static final int MAX_CONFIG_BYTES = 16 * 1024;
    private static final String OBJECT_ID_PREFIX = "mineqtt_";
    // PresenceDevice.OBJECT_ID_PREFIX, which this Minecraft-free class may not name.
    private static final String PLAYER_ID_PREFIX = "mineqtt_player_";
    private static final int UUID_HEX_DIGITS = 32;
    // Retained messages are what the scan is for. QoS 0, because some brokers (Moquette) drop the retain flag of a
    // stored message they send at QoS 1 or 2; a message lost at QoS 0 only leaves its ghost to the next connect. Not
    // no-local: a broker reading MQTT 5 strictly would withhold the ghosts this client id published in an earlier run.
    // The size cap is judge's, after the topic filter, so a large config of another integration is skipped silently
    // instead of being logged as a dropped message.
    private static final SubOptions SCAN = new SubOptions(0, true, false, Integer.MAX_VALUE, null);

    /** What the scan makes of one message on the discovery filter. */
    public enum Verdict {
        /** Not a device config of the form MineQTT publishes, e.g. another integration's or the bridge's own. */
        FOREIGN,
        /** A live message: no stored config, so no ghost. Clears (empty payloads) are live too. */
        LIVE,
        /** Over {@value #MAX_CONFIG_BYTES} bytes; not parsed. */
        TOO_LARGE,
        /** A device or group of this world's registry, or the presence device of a player who has one. */
        KNOWN,
        /** Not a JSON object. */
        MALFORMED,
        /** Its {@code via_device} is another bridge, or missing. */
        OTHER_BRIDGE,
        /** This bridge's device that the registry does not have: its config is cleared. */
        GHOST
    }

    private final MqttService mqtt;
    private final Supplier<String> prefix;
    private final String bridgeObjectId;
    private final Predicate<UUID> known;
    private final Predicate<String> knownPlayer;
    private final Supplier<String> playerRoot;
    private final Predicate<String> knownSlug;
    private final BooleanSupplier rateLimit;
    private final LongSupplier clock;
    private final MqttLog log;
    // The topics judged ghosts, each with whether it became known since (a device or presence device with its id
    // appeared), waiting for the rate limit.
    private final Map<String, BooleanSupplier> clears = new LinkedHashMap<>();
    // Null when no scan listens; the two are opened and closed together.
    private Subscription subscription;
    private Subscription stateSubscription;
    private String scanPrefix;
    private String scanPlayerRoot;
    private long endsAtMillis;
    private int scanned;
    private int statesScanned;
    private int ghosts;
    private boolean stopped;

    /**
     * @param prefix         the discovery prefix, read at each connect
     * @param bridgeObjectId this bridge's device identifier, which its devices name as {@code via_device}
     * @param known          whether a device or group with this id is in this world's registry
     * @param knownPlayer    whether a player has a presence device with this object id now ({@code mineqtt_player_<32hex>})
     * @param playerRoot     where this bridge's presence states live, {@code <base>/<bridge>/player}, read at each connect
     * @param knownSlug      whether a player has a presence device with this slug now
     * @param rateLimit      takes one message from the discovery rate limit; false when the second's budget is spent
     * @param clock          milliseconds
     */
    public GhostCleanup(MqttService mqtt, Supplier<String> prefix, String bridgeObjectId, Predicate<UUID> known, Predicate<String> knownPlayer,
                        Supplier<String> playerRoot, Predicate<String> knownSlug, BooleanSupplier rateLimit, LongSupplier clock, MqttLog log) {
        this.mqtt = mqtt;
        this.prefix = prefix;
        this.bridgeObjectId = bridgeObjectId;
        this.known = known;
        this.knownPlayer = knownPlayer;
        this.playerRoot = playerRoot;
        this.knownSlug = knownSlug;
        this.rateLimit = rateLimit;
        this.clock = clock;
        this.log = log;
    }

    /**
     * The decision for one message on {@code <prefix>/device/+/config}. The cheap tests (topic, device id, registry,
     * retain flag, size) come before the payload is parsed.
     */
    public static Verdict judge(String prefix, Inbound message, String bridgeObjectId, Predicate<UUID> known, Predicate<String> knownPlayer) {
        UUID id = deviceId(prefix, message.topic());
        String player = id == null ? playerId(prefix, message.topic()) : null;
        if (id == null && player == null) return Verdict.FOREIGN;
        if (id != null ? known.test(id) : knownPlayer.test(player)) return Verdict.KNOWN;
        if (!message.retained()) return Verdict.LIVE;
        if (message.payload().length > MAX_CONFIG_BYTES) return Verdict.TOO_LARGE;
        Optional<JsonElement> json = message.json();
        if (json.isEmpty() || !json.get().isJsonObject()) return Verdict.MALFORMED;
        return bridgeObjectId.equals(viaDevice(json.get().getAsJsonObject())) ? Verdict.GHOST : Verdict.OTHER_BRIDGE;
    }

    /**
     * The decision for one message on {@code <playerRoot>/+}, the presence states of this bridge: a retained state whose
     * slug no player holds now is a ghost. The topic is this bridge's own, so the payload is not read.
     */
    public static Verdict judgeState(String playerRoot, Inbound message, Predicate<String> knownSlug) {
        String slug = playerSlug(playerRoot, message.topic());
        if (slug == null) return Verdict.FOREIGN;
        if (knownSlug.test(slug)) return Verdict.KNOWN;
        if (!message.retained() || message.payload().length == 0) return Verdict.LIVE;
        return Verdict.GHOST;
    }

    /** The slug in {@code <playerRoot>/<slug>} (one topic level); null for any other topic. */
    static String playerSlug(String playerRoot, String topic) {
        String head = playerRoot + "/";
        if (!topic.startsWith(head) || topic.length() == head.length()) return null;
        String slug = topic.substring(head.length());
        return slug.indexOf('/') >= 0 ? null : slug;
    }

    /**
     * The device id in {@code <prefix>/device/mineqtt_<uuid32>/config}, the form {@code DeviceRecord.objectId} writes
     * (32 lowercase hex digits); null for any other topic.
     */
    static UUID deviceId(String prefix, String topic) {
        String hex = hexAfter(prefix + "/device/" + OBJECT_ID_PREFIX, topic);
        return hex == null ? null : new UUID(HexFormat.fromHexDigitsToLong(hex, 0, 16), HexFormat.fromHexDigitsToLong(hex, 16, 32));
    }

    /**
     * The object id in {@code <prefix>/device/mineqtt_player_<32hex>/config}, the form of a presence device (32 lowercase
     * hex digits); null for any other topic.
     */
    static String playerId(String prefix, String topic) {
        String hex = hexAfter(prefix + "/device/" + PLAYER_ID_PREFIX, topic);
        return hex == null ? null : PLAYER_ID_PREFIX + hex;
    }

    // The 32 lowercase hex digits between head and /config, or null when the topic has another form.
    private static String hexAfter(String head, String topic) {
        String tail = "/config";
        if (!topic.startsWith(head) || !topic.endsWith(tail) || topic.length() != head.length() + UUID_HEX_DIGITS + tail.length()) {
            return null;
        }
        String hex = topic.substring(head.length(), head.length() + UUID_HEX_DIGITS);
        for (int i = 0; i < hex.length(); i++) {
            char digit = hex.charAt(i);
            if ((digit < '0' || digit > '9') && (digit < 'a' || digit > 'f')) return null;
        }
        return hex;
    }

    // dev.via_device, or null; Home Assistant also accepts the device block spelled out.
    private static String viaDevice(JsonObject config) {
        JsonElement device = config.has("dev") ? config.get("dev") : config.get("device");
        if (device == null || !device.isJsonObject()) return null;
        JsonElement via = device.getAsJsonObject().get("via_device");
        return via != null && via.isJsonPrimitive() && via.getAsJsonPrimitive().isString() ? via.getAsString() : null;
    }

    /**
     * After every CONNACK: starts a scan, or restarts the one still listening (and follows a changed prefix or player
     * root).
     */
    public void connected() {
        if (stopped) return;
        String current = prefix.get();
        String currentRoot = playerRoot.get();
        if (subscription != null && (!current.equals(scanPrefix) || !currentRoot.equals(scanPlayerRoot))) closeScan();
        if (subscription == null) {
            scanPrefix = current;
            scanPlayerRoot = currentRoot;
            scanned = 0;
            statesScanned = 0;
            ghosts = 0;
            subscription = mqtt.subscribe(scanPrefix + "/device/+/config", SCAN, this::onConfig);
            stateSubscription = mqtt.subscribe(scanPlayerRoot + "/+", SCAN, this::onState);
        }
        endsAtMillis = clock.getAsLong() + WINDOW_MILLIS;
    }

    /**
     * {@code SERVER_POST}: ends the scan when its time is up or the connection is lost, and releases clears within the
     * discovery rate limit while connected.
     */
    public void tick() {
        if (stopped) return;
        if (!mqtt.state().isConnected()) {
            // The next connect scans again and finds whatever was not cleared.
            if (subscription != null) closeScan();
            clears.clear();
            return;
        }
        if (subscription != null && clock.getAsLong() >= endsAtMillis) {
            closeScan();
            String looked = scanned + " discovery configs and " + statesScanned + " presence states looked at";
            if (ghosts > 0) {
                log.info("Home Assistant ghost cleanup: " + looked + ", " + ghosts + (ghosts == 1 ? " ghost" : " ghosts") + " found");
            } else {
                log.debug("Home Assistant ghost cleanup: " + looked + ", no ghosts");
            }
        }
        for (Iterator<Map.Entry<String, BooleanSupplier>> waiting = clears.entrySet().iterator(); waiting.hasNext(); ) {
            Map.Entry<String, BooleanSupplier> ghost = waiting.next();
            // A device (or presence device) with this id or slug appeared since: its own message replaces the ghost.
            if (ghost.getValue().getAsBoolean()) {
                waiting.remove();
                continue;
            }
            if (!rateLimit.getAsBoolean()) return;
            waiting.remove();
            mqtt.publish(Outbound.clear(ghost.getKey()));
        }
    }

    /** Whether the scan is listening or clears wait, so a paused server is pumped until it is done. */
    public boolean pending() {
        return !stopped && (subscription != null || !clears.isEmpty() && mqtt.state().isConnected());
    }

    /** {@code SERVER_STOPPING}. */
    public void stop() {
        stopped = true;
        clears.clear();
        if (subscription != null) closeScan();
    }

    private void onConfig(Inbound message) {
        if (stopped || subscription == null) return;
        scanned++;
        if (judge(scanPrefix, message, bridgeObjectId, known, knownPlayer) != Verdict.GHOST || clears.containsKey(message.topic())) return;
        UUID id = deviceId(scanPrefix, message.topic());
        if (id != null) {
            clears.put(message.topic(), () -> known.test(id));
            log.info("Clearing the Home Assistant discovery " + message.topic() + ": it names this bridge, but no device or group of this world has its id");
        } else {
            String player = playerId(scanPrefix, message.topic());
            clears.put(message.topic(), () -> knownPlayer.test(player));
            log.info("Clearing the Home Assistant discovery " + message.topic() + ": it names this bridge, but no player of this world has that presence device");
        }
        ghosts++;
    }

    private void onState(Inbound message) {
        if (stopped || stateSubscription == null) return;
        statesScanned++;
        if (judgeState(scanPlayerRoot, message, knownSlug) != Verdict.GHOST || clears.containsKey(message.topic())) return;
        String slug = playerSlug(scanPlayerRoot, message.topic());
        clears.put(message.topic(), () -> knownSlug.test(slug));
        log.info("Clearing the retained presence state " + message.topic() + ": no player of this world has that presence device");
        ghosts++;
    }

    private void closeScan() {
        subscription.close();
        subscription = null;
        stateSubscription.close();
        stateSubscription = null;
    }
}

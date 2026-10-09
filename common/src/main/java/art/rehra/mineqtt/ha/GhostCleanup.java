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
 * empty retained message. The discovery of other bridges and other integrations is never touched. Live messages are
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
        /** A device or group of this world's registry. */
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
    private final BooleanSupplier rateLimit;
    private final LongSupplier clock;
    private final MqttLog log;
    // The topics judged ghosts, with their device ids, waiting for the rate limit.
    private final Map<String, UUID> clears = new LinkedHashMap<>();
    // Null when no scan listens.
    private Subscription subscription;
    private String scanPrefix;
    private long endsAtMillis;
    private int scanned;
    private int ghosts;
    private boolean stopped;

    /**
     * @param prefix         the discovery prefix, read at each connect
     * @param bridgeObjectId this bridge's device identifier, which its devices name as {@code via_device}
     * @param known          whether a device or group with this id is in this world's registry
     * @param rateLimit      takes one message from the discovery rate limit; false when the second's budget is spent
     * @param clock          milliseconds
     */
    public GhostCleanup(MqttService mqtt, Supplier<String> prefix, String bridgeObjectId, Predicate<UUID> known,
                        BooleanSupplier rateLimit, LongSupplier clock, MqttLog log) {
        this.mqtt = mqtt;
        this.prefix = prefix;
        this.bridgeObjectId = bridgeObjectId;
        this.known = known;
        this.rateLimit = rateLimit;
        this.clock = clock;
        this.log = log;
    }

    /**
     * The decision for one message on {@code <prefix>/device/+/config}. The cheap tests (topic, device id, registry,
     * retain flag, size) come before the payload is parsed.
     */
    public static Verdict judge(String prefix, Inbound message, String bridgeObjectId, Predicate<UUID> known) {
        UUID id = deviceId(prefix, message.topic());
        if (id == null) return Verdict.FOREIGN;
        if (known.test(id)) return Verdict.KNOWN;
        if (!message.retained()) return Verdict.LIVE;
        if (message.payload().length > MAX_CONFIG_BYTES) return Verdict.TOO_LARGE;
        Optional<JsonElement> json = message.json();
        if (json.isEmpty() || !json.get().isJsonObject()) return Verdict.MALFORMED;
        return bridgeObjectId.equals(viaDevice(json.get().getAsJsonObject())) ? Verdict.GHOST : Verdict.OTHER_BRIDGE;
    }

    /**
     * The device id in {@code <prefix>/device/mineqtt_<uuid32>/config}, the form {@code DeviceRecord.objectId} writes
     * (32 lowercase hex digits); null for any other topic.
     */
    static UUID deviceId(String prefix, String topic) {
        String head = prefix + "/device/" + OBJECT_ID_PREFIX;
        String tail = "/config";
        if (!topic.startsWith(head) || !topic.endsWith(tail) || topic.length() != head.length() + UUID_HEX_DIGITS + tail.length()) {
            return null;
        }
        String hex = topic.substring(head.length(), head.length() + UUID_HEX_DIGITS);
        for (int i = 0; i < hex.length(); i++) {
            char digit = hex.charAt(i);
            if ((digit < '0' || digit > '9') && (digit < 'a' || digit > 'f')) return null;
        }
        return new UUID(HexFormat.fromHexDigitsToLong(hex, 0, 16), HexFormat.fromHexDigitsToLong(hex, 16, 32));
    }

    // dev.via_device, or null; Home Assistant also accepts the device block spelled out.
    private static String viaDevice(JsonObject config) {
        JsonElement device = config.has("dev") ? config.get("dev") : config.get("device");
        if (device == null || !device.isJsonObject()) return null;
        JsonElement via = device.getAsJsonObject().get("via_device");
        return via != null && via.isJsonPrimitive() && via.getAsJsonPrimitive().isString() ? via.getAsString() : null;
    }

    /** After every CONNACK: starts a scan, or restarts the one still listening (and follows a changed prefix). */
    public void connected() {
        if (stopped) return;
        String current = prefix.get();
        if (subscription != null && !current.equals(scanPrefix)) closeScan();
        if (subscription == null) {
            scanPrefix = current;
            scanned = 0;
            ghosts = 0;
            subscription = mqtt.subscribe(scanPrefix + "/device/+/config", SCAN, this::onConfig);
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
            if (ghosts > 0) {
                log.info("Home Assistant ghost cleanup: " + scanned + " discovery configs looked at, " + ghosts
                        + (ghosts == 1 ? " ghost" : " ghosts") + " found");
            } else {
                log.debug("Home Assistant ghost cleanup: " + scanned + " discovery configs looked at, no ghosts");
            }
        }
        for (Iterator<Map.Entry<String, UUID>> waiting = clears.entrySet().iterator(); waiting.hasNext(); ) {
            Map.Entry<String, UUID> ghost = waiting.next();
            // A device with this id appeared since: its own discovery replaces the config.
            if (known.test(ghost.getValue())) {
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
        if (judge(scanPrefix, message, bridgeObjectId, known) != Verdict.GHOST || clears.containsKey(message.topic())) return;
        clears.put(message.topic(), deviceId(scanPrefix, message.topic()));
        ghosts++;
        log.info("Clearing the Home Assistant discovery " + message.topic() + ": it names this bridge, but no device or group of this world has its id");
    }

    private void closeScan() {
        subscription.close();
        subscription = null;
    }
}

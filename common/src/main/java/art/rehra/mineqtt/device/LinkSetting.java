package art.rehra.mineqtt.device;

import art.rehra.mineqtt.flow.core.Coerce;
import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.mqtt.core.TopicFilters;
import art.rehra.mineqtt.security.C2SGuard;
import art.rehra.mineqtt.security.AccessMode;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * A setting of a device's {@link DeviceLink} rather than of its type: its identity, exposure, access and Raw Link. Every
 * device has the same ones, a Raw Link's only when its type has one ({@link #of}). Like a {@link Setting}, a
 * link setting gives the GUI control ({@link #kind()}) and the bounds of what a client may send (design §8.5: a name of
 * at most 64 characters, topics of at most 256 bytes, payloads of at most 1 KiB); applying a value, with the ACL check of
 * a typed topic, is {@link DeviceConfig}'s and the runtime's. Keys contain no field key, so a type can never shadow one
 * ({@link DeviceType.Builder#build()} checks).
 *
 * @param gate who may change it, on top of the C2S guard's checks
 */
public record LinkSetting<T>(String key, PortType kind, Gate gate, Function<JsonElement, DataResult<T>> reader) {
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_TOPIC_BYTES = TopicAcl.MAX_TOPIC_BYTES;
    public static final int MAX_PAYLOAD_BYTES = C2SGuard.MAX_PAYLOAD_BYTES;
    /** The Home Assistant exposure: {@code on}, {@code off}, or {@code default} to follow {@code ha.expose}. */
    public static final List<String> EXPOSE_VALUES = List.of("on", "off", "default");
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)?");

    /**
     * Who may change a link setting, or work a control of the generated screen ({@code DeviceTabs.Control}): a setting of
     * the type needs CONFIGURE, a command to one of its writable fields or an action USE (M2-9).
     */
    public enum Gate {
        /** USE on the device: a command or an action, never a link setting. */
        USE,
        /** CONFIGURE on the device. */
        CONFIGURE,
        /** CONFIGURE and {@code AclContext.mayExpose}, as {@code /mineqtt devices expose} (F7). */
        EXPOSE,
        /** The owner or an operator (M2-11). */
        ACCESS
    }

    /** The device's name; the slug follows it. Not blank once sanitised. */
    public static final LinkSetting<String> NAME = new LinkSetting<>("name", PortType.text(MAX_NAME_LENGTH), Gate.CONFIGURE, json -> {
        String name = ValueCodecs.sanitizeText(json.getAsString()).strip();
        return name.isEmpty() ? DataResult.error(() -> "A device needs a name") : DataResult.success(name);
    });
    /** The explicit exposure; empty follows {@code ha.expose}. */
    public static final LinkSetting<Optional<Boolean>> EXPOSE = new LinkSetting<>("expose", PortType.enumOf(EXPOSE_VALUES), Gate.EXPOSE,
            json -> DataResult.success(switch (json.getAsString().toLowerCase(Locale.ROOT)) {
                case "on" -> Optional.of(true);
                case "off" -> Optional.of(false);
                default -> Optional.empty();
            }));
    public static final LinkSetting<AccessMode> ACCESS = new LinkSetting<>("access",
            PortType.enumOf(Arrays.stream(AccessMode.values()).map(AccessMode::getSerializedName).toList()), Gate.ACCESS,
            json -> AccessMode.CODEC.parse(JsonOps.INSTANCE, new JsonPrimitive(json.getAsString().toLowerCase(Locale.ROOT))));
    /**
     * The Raw Link's channel: an item or paper literal, resolved to a topic for the owner ({@link RawTopics#resolve}).
     * Empty removes the link.
     */
    public static final LinkSetting<String> RAW_CHANNEL = new LinkSetting<>("raw.channel", PortType.text(MAX_TOPIC_BYTES), Gate.CONFIGURE,
            LinkSetting::topic);
    /**
     * A private channel: resolved into the owner's private subtree ({@code <base>/<bridgeId>/raw/players/<owner>/...}),
     * which v1's private mode put the slots' topic into. It needs an owner. Empty removes the link.
     */
    public static final LinkSetting<String> RAW_PRIVATE = new LinkSetting<>("raw.private", PortType.text(MAX_TOPIC_BYTES), Gate.CONFIGURE,
            LinkSetting::topic);
    /** An explicit Raw Link topic, used as typed (the ACL decides, operators' bypass included). Empty removes the link. */
    public static final LinkSetting<String> RAW_TOPIC = new LinkSetting<>("raw.topic", PortType.text(MAX_TOPIC_BYTES), Gate.CONFIGURE,
            LinkSetting::topic);
    /** Whether an output publishes retained, overriding its type's default. */
    public static final LinkSetting<Boolean> RAW_RETAIN = new LinkSetting<>("raw.retain", PortType.BOOL, Gate.CONFIGURE,
            json -> DataResult.success(Coerce.bool(json).orElseThrow()));
    /** The QoS an output publishes with. */
    public static final LinkSetting<Integer> RAW_QOS = new LinkSetting<>("raw.qos", PortType.integer(0, 1), Gate.CONFIGURE,
            json -> DataResult.success(json.getAsInt()));
    /** The custom on payload; empty removes it. */
    public static final LinkSetting<String> RAW_ON = new LinkSetting<>("raw.on", PortType.text(MAX_PAYLOAD_BYTES), Gate.CONFIGURE,
            LinkSetting::payload);
    /** The custom off payload; empty removes it. */
    public static final LinkSetting<String> RAW_OFF = new LinkSetting<>("raw.off", PortType.text(MAX_PAYLOAD_BYTES), Gate.CONFIGURE,
            LinkSetting::payload);
    private static final List<LinkSetting<?>> ALL = List.of(NAME, EXPOSE, ACCESS, RAW_CHANNEL, RAW_PRIVATE, RAW_TOPIC, RAW_RETAIN, RAW_QOS, RAW_ON, RAW_OFF);

    public LinkSetting {
        if (!KEY.matcher(key).matches()) throw new IllegalArgumentException("Invalid link setting key: " + key);
    }

    /**
     * The link settings of a device of {@code type}: name, exposure and access; with a Raw Link its channel, private
     * channel and topic, and for an output its retain and QoS (an input's subscription has neither); custom payloads
     * when its type has them.
     */
    public static List<LinkSetting<?>> of(DeviceType type) {
        List<LinkSetting<?>> settings = new ArrayList<>(List.of(NAME, EXPOSE, ACCESS));
        type.rawLink().ifPresent(spec -> {
            settings.add(RAW_CHANNEL);
            settings.add(RAW_PRIVATE);
            settings.add(RAW_TOPIC);
            if (spec.direction() == RawLinkSpec.Direction.OUT) {
                // An output of events is never retained.
                if (spec.level()) settings.add(RAW_RETAIN);
                settings.add(RAW_QOS);
            }
            if (spec.customPayloads()) {
                settings.add(RAW_ON);
                settings.add(RAW_OFF);
            }
        });
        return List.copyOf(settings);
    }

    /** Whether {@code key} names a link setting of some device. */
    public static boolean isKey(String key) {
        return ALL.stream().anyMatch(setting -> setting.key().equals(key));
    }

    /** A value sent by a client; an error when it does not fit the kind or the bounds of the setting. */
    public DataResult<T> parse(Tag value) {
        return parse(NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, value));
    }

    /** {@link #parse(Tag)} of a value given as JSON, as a command twin reads it ({@link DeviceConfig#commandValue}). */
    public DataResult<T> parse(JsonElement json) {
        Optional<String> problem = kind.problem(json);
        if (problem.isPresent()) return DataResult.error(() -> key + ": " + problem.get());
        return reader.apply(json);
    }

    // A topic or channel of at most MAX_TOPIC_BYTES that names one topic (no wildcard); empty for none.
    private static DataResult<String> topic(JsonElement json) {
        String topic = json.getAsString();
        if (topic.isEmpty()) return DataResult.success(topic);
        if (!C2SGuard.fitsTopic(topic)) return DataResult.error(() -> "A topic has at most " + MAX_TOPIC_BYTES + " bytes");
        return TopicFilters.isValidTopic(topic) ? DataResult.success(topic) : DataResult.error(() -> "Not a topic: " + topic);
    }

    // A payload of at most MAX_PAYLOAD_BYTES; empty for none.
    private static DataResult<String> payload(JsonElement json) {
        String payload = json.getAsString();
        return C2SGuard.fitsPayload(payload) ? DataResult.success(payload)
                : DataResult.error(() -> "A payload has at most " + MAX_PAYLOAD_BYTES + " bytes");
    }
}

package art.rehra.mineqtt.device;

import art.rehra.mineqtt.flow.core.Coerce;
import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.mqtt.core.TopicFilters;
import art.rehra.mineqtt.security.C2SGuard;
import art.rehra.mineqtt.security.AccessMode;
import art.rehra.mineqtt.twin.TwinAdapter;
import art.rehra.mineqtt.twin.core.Binding;
import art.rehra.mineqtt.twin.core.BindingPreset;
import art.rehra.mineqtt.twin.core.ValueMap;
import art.rehra.mineqtt.twin.core.ValuePath;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * A setting of a device's {@link DeviceLink} rather than of its type: its identity, exposure, access, Raw Link and twin
 * binding. Every device has the same ones, a Raw Link's only when its type has one and a binding's only when its type has
 * a twin adapter ({@link #of}). Like a {@link Setting}, a
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
    /** The longest number {@code twin.min} and {@code twin.max} take, as typed. */
    public static final int MAX_DECIMAL_LENGTH = 32;
    /** The Home Assistant exposure: {@code on}, {@code off}, or {@code default} to follow {@code ha.expose}. */
    public static final List<String> EXPOSE_VALUES = List.of("on", "off", "default");
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)?");
    private static final Pattern DECIMAL = Pattern.compile("-?[0-9]{1,15}(\\.[0-9]{1,6})?");

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
        ACCESS,
        /**
         * CONFIGURE, and the runtime's twin gate (M3 plan D13): {@code mineqtt:twin.bind} and an entity in
         * {@code twin.allowedEntities}, which nobody bypasses. Binding or changing a source or a target.
         */
        TWIN,
        /** CONFIGURE, and {@code mineqtt:twin.control} at the runtime (M3 plan D13): turning control on. */
        TWIN_CONTROL
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
    /** The prefix of every twin binding key (M3 plan D19). */
    public static final String TWIN_PREFIX = "twin.";
    /** The values of {@link #TWIN_DECIMALS}: {@code auto} keeps Home Assistant's own formatting. */
    public static final List<String> TWIN_DECIMALS_VALUES = List.of("auto", "0", "1", "2", "3");
    /** The Home Assistant entity a device mirrors; empty unbinds it. */
    public static final LinkSetting<String> TWIN_ENTITY = new LinkSetting<>("twin.entity", PortType.text(Binding.MAX_REFERENCE_LENGTH), Gate.TWIN,
            LinkSetting::entity);
    /**
     * An MQTT device a device mirrors through a preset (M3-17): {@code <preset id> <device name>}, e.g.
     * {@code mineqtt:zigbee2mqtt_light Porch}; empty unbinds it.
     */
    public static final LinkSetting<PresetDevice> TWIN_DEVICE = new LinkSetting<>("twin.device",
            PortType.text(BindingPreset.MAX_ID_LENGTH + 1 + BindingPreset.MAX_DEVICE_BYTES), Gate.TWIN, LinkSetting::presetDevice);
    /** Another preset for the MQTT device a device already mirrors through one (M3-17). */
    public static final LinkSetting<String> TWIN_PRESET = new LinkSetting<>("twin.preset", PortType.text(BindingPreset.MAX_ID_LENGTH), Gate.TWIN,
            LinkSetting::presetId);
    /** Whether using a mirrored device switches its source. */
    public static final LinkSetting<Boolean> TWIN_CONTROL = new LinkSetting<>("twin.control", PortType.BOOL, Gate.TWIN_CONTROL,
            json -> DataResult.success(Coerce.bool(json).orElseThrow()));
    /** What to read instead of the type's default, as a value path; empty for the default. */
    public static final LinkSetting<Optional<ValuePath>> TWIN_PATH = new LinkSetting<>("twin.path", PortType.text(ValuePath.MAX_LENGTH), Gate.TWIN,
            LinkSetting::path);
    /** On reads as off. */
    public static final LinkSetting<Boolean> TWIN_INVERT = new LinkSetting<>("twin.invert", PortType.BOOL, Gate.CONFIGURE,
            json -> DataResult.success(Coerce.bool(json).orElseThrow()));
    /** The source value that is level 0; empty for the entity's own minimum. */
    public static final LinkSetting<Optional<BigDecimal>> TWIN_MIN = new LinkSetting<>("twin.min", PortType.text(MAX_DECIMAL_LENGTH), Gate.CONFIGURE,
            LinkSetting::decimal);
    /** The source value that is level 15; empty for the entity's own maximum. */
    public static final LinkSetting<Optional<BigDecimal>> TWIN_MAX = new LinkSetting<>("twin.max", PortType.text(MAX_DECIMAL_LENGTH), Gate.CONFIGURE,
            LinkSetting::decimal);
    /** The places of a shown number. */
    public static final LinkSetting<Integer> TWIN_DECIMALS = new LinkSetting<>("twin.decimals", PortType.enumOf(TWIN_DECIMALS_VALUES), Gate.CONFIGURE,
            json -> DataResult.success(json.getAsString().equals("auto") ? ValueMap.AUTO_DECIMALS : Integer.parseInt(json.getAsString())));
    /** What the device does while its source has no value: {@code hold} or {@code off}. */
    public static final LinkSetting<Binding.Unavailable> TWIN_UNAVAILABLE = new LinkSetting<>("twin.unavailable",
            PortType.enumOf(Arrays.stream(Binding.Unavailable.values()).map(Binding.Unavailable::key).toList()), Gate.CONFIGURE,
            json -> Binding.Unavailable.of(json.getAsString().toLowerCase(Locale.ROOT)).map(DataResult::success)
                    .orElseGet(() -> DataResult.error(() -> "twin.unavailable is hold or off")));
    /**
     * Resumes the control the circuit breaker paused (M3 plan D10, D19: the Twin tab's Resume): a press, never a stored
     * value; {@code /mineqtt devices bind <device> resume} is its command twin. Only for a type that can be paused: one
     * whose source it controls, or one with targets. Copy-Paste never copies it.
     */
    public static final LinkSetting<Boolean> TWIN_RESUME = new LinkSetting<>("twin.resume", PortType.event("press"), Gate.CONFIGURE,
            json -> DataResult.success(Boolean.TRUE));
    private static final List<LinkSetting<?>> ALL = List.of(NAME, EXPOSE, ACCESS, RAW_CHANNEL, RAW_PRIVATE, RAW_TOPIC, RAW_RETAIN, RAW_QOS, RAW_ON, RAW_OFF,
            TWIN_ENTITY, TWIN_DEVICE, TWIN_PRESET, TWIN_CONTROL, TWIN_PATH, TWIN_INVERT, TWIN_MIN, TWIN_MAX, TWIN_DECIMALS, TWIN_UNAVAILABLE,
            TWIN_RESUME);
    /** The names {@code twin.<name>} of the fixed twin keys, which no target may take. */
    public static final List<String> TWIN_RESERVED_NAMES = ALL.stream().map(LinkSetting::key).filter(key -> key.startsWith(TWIN_PREFIX))
            .map(key -> key.substring(TWIN_PREFIX.length())).toList();

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
        type.twin().ifPresent(twin -> settings.addAll(twin(twin)));
        return List.copyOf(settings);
    }

    /**
     * The binding keys of a type with a twin adapter (M3 plan D19), in the order a paste applies them: the source, the
     * Twin tab rows the adapter uses, what happens while the source is unavailable, then control, which needs the
     * source; then one {@code twin.<name>} per target ({@code twin.target}, {@code twin.left}); last {@code twin.resume} for a
     * type the breaker can pause.
     */
    public static List<LinkSetting<?>> twin(TwinAdapter twin) {
        List<LinkSetting<?>> settings = new ArrayList<>();
        if (twin.hasSource()) {
            settings.add(TWIN_ENTITY);
            settings.add(TWIN_DEVICE);
            settings.add(TWIN_PRESET);
            if (twin.shapes().contains(TwinAdapter.Shape.PATH)) settings.add(TWIN_PATH);
            if (twin.shapes().contains(TwinAdapter.Shape.INVERT)) settings.add(TWIN_INVERT);
            if (twin.shapes().contains(TwinAdapter.Shape.RANGE)) {
                settings.add(TWIN_MIN);
                settings.add(TWIN_MAX);
            }
            if (twin.shapes().contains(TwinAdapter.Shape.DECIMALS)) settings.add(TWIN_DECIMALS);
            settings.add(TWIN_UNAVAILABLE);
            if (twin.controllable()) settings.add(TWIN_CONTROL);
        }
        twin.targets().keySet().forEach(name -> settings.add(target(name)));
        if (pausable(twin)) settings.add(TWIN_RESUME);
        return settings;
    }

    /** Whether the circuit breaker can pause a device of a type with this adapter: it requests (control or targets). */
    public static boolean pausable(TwinAdapter twin) {
        return twin.controllable() || !twin.targets().isEmpty();
    }

    /** The key {@code twin.<name>} of a target: the Home Assistant entity an out-port switches; empty removes it. */
    public static LinkSetting<String> target(String name) {
        return new LinkSetting<>(TWIN_PREFIX + name, PortType.text(Binding.MAX_REFERENCE_LENGTH), Gate.TWIN, LinkSetting::entity);
    }

    /** Whether this is a twin binding key, a target's included. */
    public boolean twin() {
        return key.startsWith(TWIN_PREFIX);
    }

    /** Whether {@code key} names a link setting of some device; every {@code twin.*} key is reserved for bindings. */
    public static boolean isKey(String key) {
        return key.startsWith(TWIN_PREFIX) || ALL.stream().anyMatch(setting -> setting.key().equals(key));
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

    // An entity id as Home Assistant spells it (lowercase, <domain>.<object_id>); empty for none.
    private static DataResult<String> entity(JsonElement json) {
        String entity = json.getAsString().strip();
        if (entity.isEmpty()) return DataResult.success(entity);
        return Endpoint.ENTITY_ID.matcher(entity).matches() ? DataResult.success(entity)
                : DataResult.error(() -> "'" + entity + "' is not a Home Assistant entity id (like light.kitchen)");
    }

    /**
     * What {@link #TWIN_DEVICE} names: a preset and a device; both empty to unbind.
     *
     * @param preset the preset's id ({@link BindingPreset#ID})
     * @param device the device name, which may hold spaces; the preset checks it
     */
    public record PresetDevice(String preset, String device) {
    }

    // "<preset id> <device name>", split at the first space (an id has none, a device name may); empty unbinds.
    private static DataResult<PresetDevice> presetDevice(JsonElement json) {
        String text = json.getAsString().strip();
        if (text.isEmpty()) return DataResult.success(new PresetDevice("", ""));
        int space = text.indexOf(' ');
        if (space < 0) return DataResult.error(() -> "twin.device is '<preset> <device name>', e.g. mineqtt:zigbee2mqtt_light Porch");
        String preset = text.substring(0, space);
        String device = text.substring(space + 1).strip();
        if (!BindingPreset.ID.matcher(preset).matches()) return DataResult.error(() -> "'" + preset + "' is no preset id (like mineqtt:tasmota_power)");
        return DataResult.success(new PresetDevice(preset, device));
    }

    // A preset id, as datapacks name them.
    private static DataResult<String> presetId(JsonElement json) {
        String text = json.getAsString().strip();
        return BindingPreset.ID.matcher(text).matches() ? DataResult.success(text)
                : DataResult.error(() -> "'" + text + "' is no preset id (like mineqtt:tasmota_power)");
    }

    // A value path; empty for the type's default.
    private static DataResult<Optional<ValuePath>> path(JsonElement json) {
        String text = json.getAsString().strip();
        if (text.isEmpty()) return DataResult.success(Optional.empty());
        Optional<String> problem = ValuePath.problem(text);
        return problem.isPresent() ? DataResult.error(() -> "twin.path: " + problem.get()) : DataResult.success(Optional.of(ValuePath.parse(text)));
    }

    // A finite decimal number in Locale.ROOT notation, without an exponent; empty for none.
    private static DataResult<Optional<BigDecimal>> decimal(JsonElement json) {
        String text = json.getAsString().strip();
        if (text.isEmpty()) return DataResult.success(Optional.empty());
        if (!DECIMAL.matcher(text).matches()) return DataResult.error(() -> "'" + text + "' is not a plain number");
        return DataResult.success(Optional.of(new BigDecimal(text)));
    }

    // A payload of at most MAX_PAYLOAD_BYTES; empty for none.
    private static DataResult<String> payload(JsonElement json) {
        String payload = json.getAsString();
        return C2SGuard.fitsPayload(payload) ? DataResult.success(payload)
                : DataResult.error(() -> "A payload has at most " + MAX_PAYLOAD_BYTES + " bytes");
    }
}

package art.rehra.mineqtt.device;

import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.security.AccessMode;
import art.rehra.mineqtt.security.AclContext;
import art.rehra.mineqtt.security.C2SGuard;
import art.rehra.mineqtt.twin.core.Binding;
import art.rehra.mineqtt.twin.core.ValuePath;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.NameAndId;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * What a player may change on a device and how (M2 plan §4.2 M2-6): the one validator behind the screen's
 * {@code DeviceConfigC2S} and {@code DeviceActionC2S} and their command twins ({@code /mineqtt devices setting},
 * {@code action} and {@code raw}). Pure: the server judges who may do what ({@link DeviceView.Grants}) and applies the
 * {@link Change} through {@link DeviceRuntime}.
 * <ul>
 *     <li>A key is one of the type's {@link Setting}s, which need CONFIGURE and reach the device as the command
 *     {@code {"<key>": value}}; one of its other writable fields, whose command needs USE (the Controls tab, M2-9); or
 *     one of its {@link LinkSetting}s with their {@link LinkSetting.Gate}. Any other key is refused, as is a value that
 *     does not fit the kind and bounds of its key (design §8.5).</li>
 *     <li>An action needs USE and must be declared by the type (M2 plan §3.8 #1); its value is read by
 *     {@link Action#parse}.</li>
 *     <li>A typed Raw Link topic is used as typed: the ACL judges it for the player who typed it, the operator bypass
 *     included, and for the device's owner, who publishes to it from then on ({@link #typedTopicProblem}).</li>
 * </ul>
 */
public final class DeviceConfig {
    private DeviceConfig() {
    }

    /** A validated change of one key. */
    public sealed interface Change permits Command, Rename, Expose, Access, RawChannel, RawTopic, RawRetain, RawQos, RawPayload, Twin, TwinResume {
    }

    /**
     * Resumes the Home Assistant control the circuit breaker paused ({@link LinkSetting#TWIN_RESUME}, M3 plan D10). No edit
     * of the binding, so the twin gate does not apply: CONFIGURE, as {@code /mineqtt devices bind <device> resume}.
     */
    public record TwinResume() implements Change {
    }

    /**
     * A change of the device's twin binding (M3 plan D19), applied by {@link TwinRules#edit}: the runtime composes the
     * twin gate ({@code mineqtt:twin.bind}, {@code twin.allowedEntities}, {@code mineqtt:twin.control}) for the editor.
     */
    public sealed interface Twin extends Change permits TwinSource, TwinDevice, TwinPreset, TwinTarget, TwinControl, TwinInvert, TwinMin, TwinMax,
            TwinDecimals, TwinUnavailable, TwinPath {
        /** The setting key the change came from, for replies. */
        String key();
    }

    /** @param entity the entity to mirror; empty unbinds the source */
    public record TwinSource(String entity) implements Twin {
        @Override
        public String key() {
            return LinkSetting.TWIN_ENTITY.key();
        }
    }

    /**
     * An MQTT device to mirror through a preset (M3-17): {@code twin.device <preset id> <device name>}.
     *
     * @param preset the preset's id; empty with an empty device unbinds the source
     * @param device the device name its topics take
     */
    public record TwinDevice(String preset, String device) implements Twin {
        @Override
        public String key() {
            return LinkSetting.TWIN_DEVICE.key();
        }

        /** Whether it unbinds the source instead of naming a device. */
        public boolean unbinds() {
            return preset.isEmpty() && device.isEmpty();
        }
    }

    /** @param preset another preset for the MQTT device the source already names (M3-17) */
    public record TwinPreset(String preset) implements Twin {
        @Override
        public String key() {
            return LinkSetting.TWIN_PRESET.key();
        }
    }

    /**
     * @param name   the adapter's target name ({@code target}, {@code left})
     * @param entity the entity the target switches; empty removes the target
     */
    public record TwinTarget(String name, String entity) implements Twin {
        @Override
        public String key() {
            return LinkSetting.TWIN_PREFIX + name;
        }
    }

    public record TwinControl(boolean control) implements Twin {
        @Override
        public String key() {
            return LinkSetting.TWIN_CONTROL.key();
        }
    }

    public record TwinInvert(boolean invert) implements Twin {
        @Override
        public String key() {
            return LinkSetting.TWIN_INVERT.key();
        }
    }

    /** @param min null for the entity's own minimum */
    public record TwinMin(@Nullable BigDecimal min) implements Twin {
        @Override
        public String key() {
            return LinkSetting.TWIN_MIN.key();
        }
    }

    /** @param max null for the entity's own maximum */
    public record TwinMax(@Nullable BigDecimal max) implements Twin {
        @Override
        public String key() {
            return LinkSetting.TWIN_MAX.key();
        }
    }

    public record TwinDecimals(int decimals) implements Twin {
        @Override
        public String key() {
            return LinkSetting.TWIN_DECIMALS.key();
        }
    }

    public record TwinUnavailable(Binding.Unavailable unavailable) implements Twin {
        @Override
        public String key() {
            return LinkSetting.TWIN_UNAVAILABLE.key();
        }
    }

    /** @param path null for the type's default */
    public record TwinPath(@Nullable ValuePath path) implements Twin {
        @Override
        public String key() {
            return LinkSetting.TWIN_PATH.key();
        }
    }

    /** A setting of the type or a command to one of its writable fields, sent to the device as this command. */
    public record Command(JsonObject command) implements Change {
    }

    public record Rename(String name) implements Change {
    }

    /** @param expose null to follow {@code ha.expose} */
    public record Expose(@Nullable Boolean expose) implements Change {
    }

    public record Access(AccessMode mode) implements Change {
    }

    /**
     * An item or paper literal the Raw Link resolves from ({@link RawTopics#resolve}); empty removes the link.
     *
     * @param privateMode whether it resolves into the owner's private subtree ({@link LinkSetting#RAW_PRIVATE})
     */
    public record RawChannel(String channel, boolean privateMode) implements Change {
    }

    /** A Raw Link topic used as typed; empty removes the link. */
    public record RawTopic(String topic) implements Change {
    }

    public record RawRetain(boolean retain) implements Change {
    }

    public record RawQos(int qos) implements Change {
    }

    /**
     * A custom payload of the Raw Link.
     *
     * @param on      whether it is the on payload, else the off payload
     * @param payload null removes it
     */
    public record RawPayload(boolean on, @Nullable String payload) implements Change {
    }

    /** {@link #parse(DeviceType, String, JsonElement, DeviceView.Grants)} of a value a client sent as NBT. */
    public static DataResult<Change> parse(DeviceType type, String key, Tag value, DeviceView.Grants grants) {
        return parse(type, key, NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, value), grants);
    }

    /**
     * Checks a change of one key: that the type has the key, that the grants allow it and that the value fits.
     *
     * @return the change, or why it is refused
     */
    public static DataResult<Change> parse(DeviceType type, String key, JsonElement value, DeviceView.Grants grants) {
        Optional<Setting<?>> setting = type.setting(key);
        if (setting.isPresent()) {
            if (!grants.configure()) return DataResult.error(() -> refusal(LinkSetting.Gate.CONFIGURE));
            return command(setting.get(), value);
        }
        Optional<Port> command = commandPort(type, key);
        if (command.isPresent()) {
            if (!grants.use()) return DataResult.error(() -> refusal(LinkSetting.Gate.USE));
            return fieldCommand(type, command.get(), value);
        }
        Optional<LinkSetting<?>> linkSetting = type.linkSetting(key);
        if (linkSetting.isEmpty()) return DataResult.error(() -> "A " + type.id() + " device has no setting " + key);
        if (!permits(linkSetting.get().gate(), grants)) return DataResult.error(() -> refusal(linkSetting.get().gate()));
        return linkChange(linkSetting.get(), value);
    }

    /**
     * The writable field of {@code type} that {@code key} names when it is no setting: a player who may use the device
     * sends it a command ({@link #parse}), as Home Assistant's {@code /set} does. The Redstone Output's {@code power} and
     * the Smart Lamp's {@code light}.
     */
    public static Optional<Port> commandPort(DeviceType type, String key) {
        return type.port(Port.Direction.IN, key).filter(port -> port.origin() == Port.Origin.FIELD);
    }

    /**
     * What a player needs before the C2S guard lets {@code DeviceConfigC2S} for {@code key} through: USE for a command to
     * a writable field, CONFIGURE for anything else, an unknown key included, so the guard still drops silently what a
     * player who may only use the device cannot change anyway. {@link #parse} judges the key's own gate after it.
     */
    public static MineqttPermission permission(DeviceType type, String key) {
        return commandPort(type, key).isPresent() ? MineqttPermission.USE : MineqttPermission.CONFIGURE;
    }

    /**
     * Checks an action: the type declares it, the grants allow using the device and the value fits it.
     *
     * @return the command that performs it, or why it is refused
     */
    public static DataResult<JsonObject> action(DeviceType type, String key, String value, DeviceView.Grants grants) {
        Optional<Action> action = type.action(key);
        if (action.isEmpty()) return DataResult.error(() -> "A " + type.id() + " device has no action " + key);
        if (!grants.use()) return DataResult.error(() -> "You may not use this device");
        if (!C2SGuard.fitsPayload(value)) return DataResult.error(() -> "A value has at most " + C2SGuard.MAX_PAYLOAD_BYTES + " bytes");
        return action.get().parse(value).map(parsed -> DataResult.success(action.get().command(parsed)))
                .orElseGet(() -> DataResult.error(() -> "Action " + key + " takes " + action.get().kind().describe() + ", not '" + value + "'"));
    }

    /**
     * Why a typed Raw Link topic may not be used, or empty when it may (design §8.2, M2 plan D1). The player who typed it
     * must be allowed to publish there, an operator's bypass of the allowlist included, so a guest cannot point a host's
     * device at a topic only the host may use. The device's owner must be allowed too, as the device publishes and
     * subscribes for them from then on. No grandfather flag counts: typing a topic drops it.
     *
     * @param editor the player or command source who typed it
     * @param owner  the device's owner as the ACL sees them, their operator bypass included
     */
    public static Optional<String> typedTopicProblem(TopicAcl acl, TopicAcl.Subject editor, TopicAcl.Subject owner, String topic) {
        TopicAcl.Decision forEditor = acl.checkPublish(editor, topic);
        if (!forEditor.allowed()) return Optional.of(forEditor.message());
        TopicAcl.Decision forOwner = acl.checkPublish(owner, topic);
        return forOwner.allowed() ? Optional.empty() : Optional.of("not for the device's owner: " + forOwner.message());
    }

    /**
     * {@link #typedTopicProblem(TopicAcl, TopicAcl.Subject, TopicAcl.Subject, String)} for a device's editor and its owner,
     * who may be offline and is seen as the server's op list sees them ({@link AclContext#owner}).
     *
     * @param owner              the device's owner; null when it has none
     * @param profilePermissions the server's permissions of a player who may be offline
     */
    public static Optional<String> typedTopicProblem(TopicAcl acl, DeviceRuntime.Editor editor, @Nullable UUID owner, String ownerName,
                                                     Function<NameAndId, ? extends PermissionSet> profilePermissions, String topic) {
        return typedTopicProblem(acl, editor.subject(), AclContext.owner(profilePermissions, owner, ownerName), topic);
    }

    /**
     * The JSON of a value typed into a command: the text itself for a text or an enum key, without one pair of quotes
     * around it (M2 plan D6), so {@code ""} is the empty text and {@code 0} the name {@code "0"}; for any other key the
     * text read like a {@code /set/<field>} payload, a number, a boolean or a name.
     */
    public static JsonElement commandValue(DeviceType type, String key, String text) {
        PortType kind = type.setting(key).map(Setting::kind).or(() -> type.linkSetting(key).map(LinkSetting::kind))
                .or(() -> commandPort(type, key).map(Port::kind)).orElse(null);
        // An enum's values are names, also when they look like numbers (twin.decimals 0), so they stay text like a text's.
        return kind instanceof PortType.Text || kind instanceof PortType.Enum ? new JsonPrimitive(ValueCodecs.unquote(text.strip())) : ValueCodecs.plainValue(text);
    }

    /** Whether the grants hold what a link setting's gate asks for. */
    public static boolean permits(LinkSetting.Gate gate, DeviceView.Grants grants) {
        return switch (gate) {
            case USE -> grants.use();
            case CONFIGURE -> grants.configure();
            case EXPOSE -> grants.configure() && grants.expose();
            case ACCESS -> grants.access();
            // The twin rights are server-wide and judged by the runtime for the editor (TwinRules), on top of CONFIGURE.
            case TWIN, TWIN_CONTROL -> grants.configure();
        };
    }

    /** What a player is told who lacks the grant a gate needs. */
    public static String refusal(LinkSetting.Gate gate) {
        return switch (gate) {
            case USE -> "You may not use this device";
            case CONFIGURE -> "You may not configure this device";
            case EXPOSE -> "Exposing devices to Home Assistant needs operator rights or mineqtt:ha.expose";
            case ACCESS -> "Only the owner or an operator may change who has access";
            case TWIN, TWIN_CONTROL -> "You may not configure this device";
        };
    }

    // A setting's value goes to the device as the command {"<key>": value}, in the field's own encoding.
    private static <T> DataResult<Change> command(Setting<T> setting, JsonElement value) {
        return setting.parse(value).map(parsed -> {
            JsonObject command = new JsonObject();
            command.add(setting.key(), setting.field().encode(parsed));
            return new Command(command);
        });
    }

    /**
     * A command to a writable field, checked against its port: {@code {"<key>": value}} as sent, which the strict check
     * has bounded already, or for an inline field (the lamp's light) the object itself, its keys at the top level. A light
     * command holds only what the screen's picker sends ({@link ValueCodecs.LightState#commandProblem}), and it is never
     * decoded and encoded again, which would fill in every key the player did not change.
     */
    private static DataResult<Change> fieldCommand(DeviceType type, Port port, JsonElement value) {
        Optional<String> problem = port.problem(value);
        if (problem.isEmpty() && port.kind() instanceof PortType.Light) problem = ValueCodecs.LightState.commandProblem(value.getAsJsonObject());
        if (problem.isPresent()) {
            String reason = problem.get();
            return DataResult.error(() -> port.key() + ": " + reason);
        }
        boolean inline = type.fields().stream().anyMatch(field -> field.key().equals(port.key()) && field.inline());
        JsonObject command = new JsonObject();
        if (inline) value.getAsJsonObject().entrySet().forEach(entry -> command.add(entry.getKey(), entry.getValue().deepCopy()));
        else command.add(port.key(), value.deepCopy());
        return DataResult.success(new Command(command));
    }

    private static DataResult<Change> linkChange(LinkSetting<?> setting, JsonElement value) {
        if (setting == LinkSetting.NAME) return LinkSetting.NAME.parse(value).map(Rename::new);
        if (setting == LinkSetting.EXPOSE) return LinkSetting.EXPOSE.parse(value).map(expose -> new Expose(expose.orElse(null)));
        if (setting == LinkSetting.ACCESS) return LinkSetting.ACCESS.parse(value).map(Access::new);
        if (setting == LinkSetting.RAW_CHANNEL) return LinkSetting.RAW_CHANNEL.parse(value).map(channel -> new RawChannel(channel, false));
        if (setting == LinkSetting.RAW_PRIVATE) return LinkSetting.RAW_PRIVATE.parse(value).map(channel -> new RawChannel(channel, true));
        if (setting == LinkSetting.RAW_TOPIC) return LinkSetting.RAW_TOPIC.parse(value).map(RawTopic::new);
        if (setting == LinkSetting.RAW_RETAIN) return LinkSetting.RAW_RETAIN.parse(value).map(RawRetain::new);
        if (setting == LinkSetting.RAW_QOS) return LinkSetting.RAW_QOS.parse(value).map(RawQos::new);
        if (setting == LinkSetting.RAW_ON) return LinkSetting.RAW_ON.parse(value).map(payload -> new RawPayload(true, payload.isEmpty() ? null : payload));
        if (setting == LinkSetting.RAW_OFF) return LinkSetting.RAW_OFF.parse(value).map(payload -> new RawPayload(false, payload.isEmpty() ? null : payload));
        if (setting == LinkSetting.TWIN_ENTITY) return LinkSetting.TWIN_ENTITY.parse(value).map(TwinSource::new);
        if (setting == LinkSetting.TWIN_DEVICE) return LinkSetting.TWIN_DEVICE.parse(value).map(device -> new TwinDevice(device.preset(), device.device()));
        if (setting == LinkSetting.TWIN_PRESET) return LinkSetting.TWIN_PRESET.parse(value).map(TwinPreset::new);
        if (setting == LinkSetting.TWIN_CONTROL) return LinkSetting.TWIN_CONTROL.parse(value).map(TwinControl::new);
        if (setting == LinkSetting.TWIN_INVERT) return LinkSetting.TWIN_INVERT.parse(value).map(TwinInvert::new);
        if (setting == LinkSetting.TWIN_MIN) return LinkSetting.TWIN_MIN.parse(value).map(min -> new TwinMin(min.orElse(null)));
        if (setting == LinkSetting.TWIN_MAX) return LinkSetting.TWIN_MAX.parse(value).map(max -> new TwinMax(max.orElse(null)));
        if (setting == LinkSetting.TWIN_DECIMALS) return LinkSetting.TWIN_DECIMALS.parse(value).map(TwinDecimals::new);
        if (setting == LinkSetting.TWIN_UNAVAILABLE) return LinkSetting.TWIN_UNAVAILABLE.parse(value).map(TwinUnavailable::new);
        if (setting == LinkSetting.TWIN_PATH) return LinkSetting.TWIN_PATH.parse(value).map(path -> new TwinPath(path.orElse(null)));
        if (setting == LinkSetting.TWIN_RESUME) return LinkSetting.TWIN_RESUME.parse(value).map(pressed -> new TwinResume());
        // A target's key is made per type ({@link LinkSetting#target}), so it is told by its prefix.
        if (setting.twin()) {
            String name = setting.key().substring(LinkSetting.TWIN_PREFIX.length());
            return LinkSetting.target(name).parse(value).map(entity -> new TwinTarget(name, entity));
        }
        throw new IllegalStateException("No change for link setting " + setting.key());
    }
}

package art.rehra.mineqtt.device;

import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.ha.HaEntity;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Everything about one kind of device, declared once (design §6.4): its state fields, its Home Assistant entities,
 * its settings, actions and events, its Raw Link, whether it can be grouped, and how often its state may be published.
 * Discovery, command parsing, validation and the device's ports (M2 plan §3.1) are derived from it.
 */
public final class DeviceType {
    private final Identifier id;
    private final String model;
    private final UnloadedPolicy unloadedPolicy;
    private final List<Field<?>> fields;
    private final List<HaEntity<?>> entities;
    private final List<Setting<?>> settings;
    private final List<Action> actions;
    private final List<EventSpec> events;
    private final @Nullable String use;
    private final List<Port> ports;
    private final @Nullable RawLinkSpec rawLink;
    private final boolean groupable;
    private final boolean remote;
    private final Duration minInterval;
    private final Map<String, Double> deadbands;
    private final CommandPreview preview;
    private final UnaryOperator<JsonObject> powerOn;

    private DeviceType(Builder builder) {
        this.id = builder.id;
        this.model = builder.model;
        this.unloadedPolicy = builder.unloadedPolicy;
        this.fields = List.copyOf(builder.fields);
        this.entities = List.copyOf(builder.entities);
        this.settings = List.copyOf(builder.settings);
        this.actions = List.copyOf(builder.actions);
        this.events = List.copyOf(builder.events);
        this.use = builder.use;
        this.ports = derivePorts(fields, settings, actions, events);
        this.rawLink = builder.rawLink;
        this.groupable = builder.groupable;
        this.remote = builder.remote;
        this.minInterval = builder.minInterval;
        this.deadbands = Map.copyOf(builder.deadbands);
        this.preview = builder.preview != null ? builder.preview : this::applyFields;
        this.powerOn = builder.powerOn;
    }

    public static Builder builder(Identifier id) {
        return new Builder(id);
    }

    /**
     * How a command changes the state an unloaded {@link UnloadedPolicy#QUEUE} device publishes. The default sets the
     * writable fields the command has; a type whose commands use keys that are no fields (a switch's {@code state},
     * {@code TOGGLE}) supplies its own.
     */
    @FunctionalInterface
    public interface CommandPreview {
        /** @param state a copy the preview may change and return */
        JsonObject apply(JsonObject state, CommandReader command);
    }

    public Identifier id() {
        return id;
    }

    /** The Home Assistant model, e.g. "Smart Lamp"; also the start of default device names. */
    public String model() {
        return model;
    }

    public UnloadedPolicy unloadedPolicy() {
        return unloadedPolicy;
    }

    public List<Field<?>> fields() {
        return fields;
    }

    public List<HaEntity<?>> entities() {
        return entities;
    }

    public List<Setting<?>> settings() {
        return settings;
    }

    public Optional<Setting<?>> setting(String key) {
        return settings.stream().filter(setting -> setting.key().equals(key)).findFirst();
    }

    /** The settings of every device of this type's {@link DeviceLink}: identity, exposure, access, Raw Link. */
    public List<LinkSetting<?>> linkSettings() {
        return LinkSetting.of(this);
    }

    public Optional<LinkSetting<?>> linkSetting(String key) {
        return linkSettings().stream().filter(setting -> setting.key().equals(key)).findFirst();
    }

    public List<Action> actions() {
        return actions;
    }

    public Optional<Action> action(String key) {
        return actions.stream().filter(action -> action.key().equals(key)).findFirst();
    }

    /**
     * The action a plain right-click performs (design §7.4): the lamp's and the plug's switch toggles, a switch is
     * pressed. Empty for a type without a natural use, whose block opens its screen instead.
     */
    public Optional<Action> use() {
        return use == null ? Optional.empty() : action(use);
    }

    public List<EventSpec> events() {
        return events;
    }

    public Optional<EventSpec> event(String type) {
        return events.stream().filter(event -> event.type().equals(type)).findFirst();
    }

    /**
     * The device's ports, derived from the declaration (M2 plan §3.1): out-ports for every readable field, then every
     * event; in-ports for every writable field (a setting's marked as such), then every action.
     */
    public List<Port> ports() {
        return ports;
    }

    public Optional<Port> port(Port.Direction direction, String key) {
        return ports.stream().filter(port -> port.direction() == direction && port.key().equals(key)).findFirst();
    }

    /**
     * The value of every readable field's out-port in a state (M2 plan §3.1), by port key in declaration order: the
     * field's value as the state holds it, for an inline field the object its codec reads back from the top-level keys.
     * A field the state does not hold is left out.
     */
    public Map<String, JsonElement> outputs(JsonObject state) {
        Map<String, JsonElement> outputs = new LinkedHashMap<>();
        for (Field<?> field : fields) {
            if (!field.access().readable()) continue;
            if (field.inline()) {
                reencode(field, state).ifPresent(value -> outputs.put(field.key(), value));
            } else if (state.has(field.key())) {
                outputs.put(field.key(), state.get(field.key()).deepCopy());
            }
        }
        return outputs;
    }

    /**
     * The {@link #outputs} of {@code after} whose value differs from {@code before}'s, which a change of the device emits
     * on its out-ports.
     *
     * @param before the state last recorded; null for a device that has none yet, whose every output is a change
     */
    public Map<String, JsonElement> changedOutputs(@Nullable JsonObject before, JsonObject after) {
        Map<String, JsonElement> previous = before == null ? Map.of() : outputs(before);
        Map<String, JsonElement> changed = outputs(after);
        changed.entrySet().removeIf(output -> output.getValue().equals(previous.get(output.getKey())));
        return changed;
    }

    /**
     * The command a wire sends to the in-port {@code key}: {@code {"<key>": value}}, or for an inline field's port, such
     * as the lamp's {@code light}, the value's own keys at the top level, as the device's state has them.
     */
    public JsonObject inPortCommand(String key, JsonElement value) {
        boolean inline = fields.stream().anyMatch(field -> field.inline() && field.access().writable() && field.key().equals(key));
        if (inline && value.isJsonObject()) return value.getAsJsonObject().deepCopy();
        JsonObject command = new JsonObject();
        command.add(key, value.deepCopy());
        return command;
    }

    /**
     * Why a device of this type may not publish an event, or empty when it may: the event must be one the type
     * declares ({@link EventSpec#problem}), as the M2 plan's flows constraint §3.8 #5 demands.
     */
    public Optional<String> eventProblem(JsonObject event) {
        JsonElement type = event.get(PortType.Event.TYPE_KEY);
        Optional<EventSpec> spec = type != null && type.isJsonPrimitive() ? event(type.getAsString()) : Optional.empty();
        return spec.isPresent() ? spec.get().problem(event) : Optional.of(this.id + " declares no event " + type);
    }

    public Optional<RawLinkSpec> rawLink() {
        return Optional.ofNullable(rawLink);
    }

    public boolean groupable() {
        return groupable;
    }

    /**
     * Whether v1 devices of this type were remotes (the Light Remote, now the Wall Switch): their v1 topic named the lamps
     * they commanded, so it becomes no Raw Link when they are adopted, nor their name; the attach wires them to those
     * lamps instead (M2 plan D15).
     */
    public boolean remote() {
        return remote;
    }

    /** The shortest time between two state publishes; a newer state waits for it, it is never dropped. */
    public Duration minInterval() {
        return minInterval;
    }

    /** Numeric state keys whose changes below the given size are not published. */
    public Map<String, Double> deadbands() {
        return deadbands;
    }

    public String defaultSlug(UUID deviceId) {
        return Slugs.defaultSlug(id.getPath(), deviceId);
    }

    public String defaultName(UUID deviceId) {
        return Slugs.defaultName(model, deviceId);
    }

    /** The state an unloaded device publishes after {@code command}; {@code state} is not changed. */
    public JsonObject preview(JsonObject state, JsonObject command) {
        return preview.apply(state.deepCopy(), new CommandReader(command));
    }

    /**
     * The state a device starts in when its server starts, from the state it had when its server stopped; the device's
     * {@link DeviceHost#powerOn()} does the same to a loaded device. {@code state} is not changed.
     */
    public JsonObject poweredOn(JsonObject state) {
        return powerOn.apply(state.deepCopy());
    }

    /**
     * The command that turns a device in {@code last} into one in {@code target}: the absolute value of every writable
     * field that differs, e.g. what an unloaded device applies when it loads.
     */
    public JsonObject difference(JsonObject last, JsonObject target) {
        JsonObject lastWritable = writableState(last);
        JsonObject difference = new JsonObject();
        writableState(target).entrySet().forEach(entry -> {
            if (!entry.getValue().equals(lastWritable.get(entry.getKey()))) difference.add(entry.getKey(), entry.getValue());
        });
        return difference;
    }

    /**
     * A group command with its toggles resolved against the group's state, so every member gets the same target: a
     * boolean or ON/OFF value is inverted. A switch over another field than {@code state} (the Redstone Output's power)
     * toggles as its Home Assistant switch reads that field: its off value is OFF, anything else ON.
     */
    public JsonObject resolveToggles(JsonObject command, JsonObject state) {
        JsonObject resolved = command.deepCopy();
        for (String key : command.keySet()) {
            JsonElement value = command.get(key);
            if (!value.isJsonPrimitive() || !value.getAsString().equalsIgnoreCase("TOGGLE")) continue;
            JsonElement current = state.get(key);
            if (current == null) {
                if (key.equals(Action.STATE)) switchedOn(state).ifPresent(on -> resolved.addProperty(key, on ? "OFF" : "ON"));
            } else if (current.isJsonPrimitive()) {
                JsonPrimitive primitive = current.getAsJsonPrimitive();
                if (primitive.isBoolean()) {
                    resolved.addProperty(key, !primitive.getAsBoolean());
                } else if (primitive.getAsString().equalsIgnoreCase("ON") || primitive.getAsString().equalsIgnoreCase("OFF")) {
                    resolved.addProperty(key, primitive.getAsString().equalsIgnoreCase("ON") ? "OFF" : "ON");
                }
            }
        }
        return resolved;
    }

    // Whether the state shows the type's switch on; empty without a switch or its field.
    private Optional<Boolean> switchedOn(JsonObject state) {
        for (HaEntity<?> entity : entities) {
            if (entity instanceof HaEntity.Switch<?> switchEntity) return switchedOn(switchEntity, state);
        }
        return Optional.empty();
    }

    private static <T> Optional<Boolean> switchedOn(HaEntity.Switch<T> switchEntity, JsonObject state) {
        JsonElement value = state.get(switchEntity.field().key());
        return value == null ? Optional.empty() : switchEntity.field().parse(value).map(parsed -> !parsed.equals(switchEntity.off()));
    }

    /**
     * The writable fields of a state, e.g. what a group's members share. Inline fields contribute the keys their codec
     * reads back.
     */
    public JsonObject writableState(JsonObject state) {
        JsonObject writable = new JsonObject();
        for (Field<?> field : fields) {
            if (!field.access().writable()) continue;
            if (field.inline()) {
                reencode(field, state).ifPresent(object -> object.getAsJsonObject().entrySet().forEach(e -> writable.add(e.getKey(), e.getValue())));
            } else if (state.has(field.key())) {
                writable.add(field.key(), state.get(field.key()).deepCopy());
            }
        }
        return writable;
    }

    private JsonObject applyFields(JsonObject state, CommandReader command) {
        JsonObject json = command.json();
        for (Field<?> field : fields) {
            if (!field.access().writable() || !field.access().readable()) continue;
            if (field.inline()) {
                reencode(field, json).ifPresent(object -> object.getAsJsonObject().entrySet().forEach(e -> state.add(e.getKey(), e.getValue())));
            } else if (json.has(field.key())) {
                reencode(field, json.get(field.key())).ifPresent(value -> state.add(field.key(), value));
            }
        }
        return state;
    }

    // The value as the field's codec reads and writes it: clamped, typed, without unknown keys.
    private static <T> Optional<JsonElement> reencode(Field<T> field, JsonElement value) {
        return field.parse(value).flatMap(decoded -> field.codec().encodeStart(JsonOps.INSTANCE, decoded).result());
    }

    private static List<Port> derivePorts(List<Field<?>> fields, List<Setting<?>> settings, List<Action> actions, List<EventSpec> events) {
        List<Port> ports = new ArrayList<>();
        for (Field<?> field : fields) {
            if (field.access().readable()) ports.add(new Port(field.key(), Port.Direction.OUT, field.kind(), Port.Origin.FIELD, false));
        }
        for (EventSpec event : events) {
            ports.add(new Port(event.type(), Port.Direction.OUT, event.kind(), Port.Origin.EVENT, false));
        }
        for (Field<?> field : fields) {
            if (!field.access().writable()) continue;
            boolean setting = settings.stream().anyMatch(candidate -> candidate.field().equals(field));
            ports.add(new Port(field.key(), Port.Direction.IN, field.kind(), setting ? Port.Origin.SETTING : Port.Origin.FIELD, false));
        }
        for (Action action : actions) {
            ports.add(new Port(action.key(), Port.Direction.IN, action.kind(), Port.Origin.ACTION, action.toggles()));
        }
        return List.copyOf(ports);
    }

    @Override
    public String toString() {
        return id.toString();
    }

    public static final class Builder {
        private final Identifier id;
        private String model = "";
        private UnloadedPolicy unloadedPolicy = UnloadedPolicy.QUEUE;
        private final List<Field<?>> fields = new ArrayList<>();
        private final List<HaEntity<?>> entities = new ArrayList<>();
        private final List<Setting<?>> settings = new ArrayList<>();
        private final List<Action> actions = new ArrayList<>();
        private final List<EventSpec> events = new ArrayList<>();
        private @Nullable String use;
        private @Nullable RawLinkSpec rawLink;
        private boolean groupable;
        private boolean remote;
        private Duration minInterval = Duration.ZERO;
        private final Map<String, Double> deadbands = new LinkedHashMap<>();
        private @Nullable CommandPreview preview;
        private UnaryOperator<JsonObject> powerOn = UnaryOperator.identity();

        private Builder(Identifier id) {
            this.id = id;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder unloaded(UnloadedPolicy policy) {
            this.unloadedPolicy = policy;
            return this;
        }

        public Builder field(Field<?> field) {
            fields.add(field);
            return this;
        }

        /** An entity; the fields it references must be declared on this type. */
        public Builder entity(HaEntity<?> entity) {
            entities.add(entity);
            return this;
        }

        public Builder setting(Setting<?> setting) {
            settings.add(setting);
            return this;
        }

        /** A command besides the writable fields; a Home Assistant button, switch or light needs its action. */
        public Builder action(Action action) {
            actions.add(action);
            return this;
        }

        /** An event the device publishes; a Home Assistant event entity or device trigger needs its event. */
        public Builder event(EventSpec event) {
            events.add(event);
            return this;
        }

        /**
         * The declared action a plain right-click performs ({@link DeviceType#use()}); it must toggle or fire, as a
         * hand sends no value.
         */
        public Builder use(String actionKey) {
            this.use = actionKey;
            return this;
        }

        public Builder rawLink(RawLinkSpec spec) {
            this.rawLink = spec;
            return this;
        }

        public Builder groupable(boolean groupable) {
            this.groupable = groupable;
            return this;
        }

        /** v1 devices of this type were remotes ({@link DeviceType#remote()}). */
        public Builder remote() {
            this.remote = true;
            return this;
        }

        /** Sensors: 1 s by default in the design; actuators keep 0 so every change is published at once. */
        public Builder minInterval(Duration interval) {
            this.minInterval = interval;
            return this;
        }

        /** Changes of {@code field} smaller than {@code delta} alone are not published (e.g. 0.2 °C). */
        public Builder deadband(Field<? extends Number> field, double delta) {
            deadbands.put(field.key(), delta);
            return this;
        }

        public Builder preview(CommandPreview preview) {
            this.preview = preview;
            return this;
        }

        /** A type with a power-on behaviour: the state it starts in, from a copy of the state it stopped in. */
        public Builder powerOn(UnaryOperator<JsonObject> powerOn) {
            this.powerOn = powerOn;
            return this;
        }

        /**
         * @throws IllegalStateException when the declaration is inconsistent: duplicate keys, an entity or setting
         *                               over an undeclared field, a command entity over a read-only field, a writable
         *                               field nothing can command, a groupable type that is not an actuator, a Home
         *                               Assistant event or device trigger without its declared event, a button or
         *                               notify without its declared action, a switch or light without a {@code state}
         *                               in-port, an action that shadows a writable field, two ports of one key and
         *                               direction, a field named like a link setting, a writable inline field that
         *                               is no light, or a use that is no declared action that toggles or fires
         */
        public DeviceType build() {
            if (model.isBlank()) fail("needs a model name");
            Set<String> keys = new HashSet<>();
            for (Field<?> field : fields) {
                if (!keys.add(field.key())) fail("declares field " + field.key() + " twice");
                if (LinkSetting.isKey(field.key())) fail("declares field " + field.key() + ", the key of a link setting");
                // A player's command to an inline field reaches the device key by key; only a light's keys are checked.
                if (field.inline() && field.access().writable() && !(field.kind() instanceof PortType.Light)) {
                    fail("declares writable inline field " + field.key() + ", which is no light");
                }
            }
            Set<String> actionKeys = new HashSet<>();
            for (Action action : actions) {
                if (!actionKeys.add(action.key())) fail("declares action " + action.key() + " twice");
                if (fields.stream().anyMatch(field -> field.access().writable() && field.key().equals(action.key()))) {
                    fail("declares action " + action.key() + " over the writable field of its key");
                }
            }
            if (use != null) {
                Optional<Action> used = actions.stream().filter(action -> action.key().equals(use)).findFirst();
                if (used.isEmpty()) fail("is used by hand with " + use + ", which is no declared action");
                if (used.get().handValue().isEmpty()) fail("is used by hand with " + use + ", which neither toggles nor fires");
            }
            Set<String> eventTypes = new HashSet<>();
            for (EventSpec event : events) {
                if (!eventTypes.add(event.type())) fail("declares event " + event.type() + " twice");
            }
            Set<Field<?>> commanded = new HashSet<>();
            Set<String> entityKeys = new HashSet<>();
            for (HaEntity<?> entity : entities) {
                if (!entityKeys.add(entity.key())) fail("declares entity " + entity.key() + " twice");
                if (entity instanceof HaEntity.Switch<?> switchEntity
                        && (switchEntity.on() == null || switchEntity.off() == null || switchEntity.on().equals(switchEntity.off()))) {
                    fail("switch " + entity.key() + " needs distinct on and off values");
                }
                for (Field<?> field : entity.fields()) {
                    if (!fields.contains(field)) fail("entity " + entity.key() + " uses undeclared field " + field.key());
                    if (!field.access().readable()) fail("entity " + entity.key() + " shows write-only field " + field.key());
                    if (entity.commands()) {
                        if (!field.access().writable()) fail("entity " + entity.key() + " commands read-only field " + field.key());
                        commanded.add(field);
                    }
                }
                switch (entity) {
                    case HaEntity.Event event -> event.eventTypes().stream().filter(type -> !eventTypes.contains(type)).findFirst()
                            .ifPresent(type -> fail("event entity " + entity.key() + " has event type " + type + ", which is no declared event"));
                    case HaEntity.DeviceTrigger trigger -> {
                        EventSpec event = events.stream().filter(spec -> spec.type().equals(trigger.event())).findFirst().orElse(null);
                        if (event == null) fail("device trigger " + entity.key() + " fires on " + trigger.event() + ", which is no declared event");
                        PortType attribute = event.attributes().get(trigger.attribute());
                        if (attribute == null) fail("device trigger " + entity.key() + " reads attribute " + trigger.attribute() + ", which event " + event.type() + " does not carry");
                        if (attribute.problem(new JsonPrimitive(trigger.subtype())).isPresent()) {
                            fail("device trigger " + entity.key() + " waits for " + trigger.attribute() + " " + trigger.subtype() + ", which event " + event.type() + " never carries");
                        }
                    }
                    case HaEntity.Button _, HaEntity.Notify _ -> {
                        if (!actionKeys.contains(entity.key())) fail(entity.platform() + " " + entity.key() + " commands no declared action");
                    }
                    case HaEntity.Switch<?> _, HaEntity.Light _ -> {
                        // Home Assistant commands both with {"state": ...}, which may be TOGGLE: only the state action toggles.
                        if (!actionKeys.contains(Action.STATE)) fail(entity.platform() + " " + entity.key() + " needs the state action");
                    }
                    default -> {
                    }
                }
            }
            Set<Field<?>> settingFields = new HashSet<>();
            for (Setting<?> setting : settings) {
                if (!fields.contains(setting.field())) fail("setting " + setting.key() + " uses an undeclared field");
                if (!settingFields.add(setting.field())) fail("declares setting " + setting.key() + " twice");
                commanded.add(setting.field());
            }
            for (Field<?> field : fields) {
                if (field.access().writable() && !commanded.contains(field)) fail("has no entity or setting that commands field " + field.key());
            }
            Set<String> portIds = new HashSet<>();
            for (Port port : derivePorts(fields, settings, actions, events)) {
                if (!portIds.add(port.direction() + " " + port.key())) fail("has two " + port.direction() + " ports " + port.key());
            }
            if (groupable && (unloadedPolicy != UnloadedPolicy.QUEUE || commanded.isEmpty())) fail("is groupable but no actuator");
            if (minInterval.isNegative()) fail("has a negative minimum interval");
            for (Map.Entry<String, Double> deadband : deadbands.entrySet()) {
                if (!keys.contains(deadband.getKey())) fail("has a deadband on undeclared field " + deadband.getKey());
                if (!(deadband.getValue() > 0)) fail("has a deadband that is not positive on " + deadband.getKey());
            }
            return new DeviceType(this);
        }

        private void fail(String problem) {
            throw new IllegalStateException("Device type " + id + " " + problem);
        }
    }
}

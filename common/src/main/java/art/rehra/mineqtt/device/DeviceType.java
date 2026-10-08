package art.rehra.mineqtt.device;

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
 * its settings, its Raw Link, whether it can be grouped, and how often its state may be published. Discovery, command
 * parsing and validation are derived from it.
 */
public final class DeviceType {
    // Switches are commanded with this key (z2m style), whatever their field.
    private static final String SWITCH_KEY = "state";

    private final Identifier id;
    private final String model;
    private final UnloadedPolicy unloadedPolicy;
    private final List<Field<?>> fields;
    private final List<HaEntity<?>> entities;
    private final List<Setting<?>> settings;
    private final @Nullable RawLinkSpec rawLink;
    private final boolean groupable;
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
        this.rawLink = builder.rawLink;
        this.groupable = builder.groupable;
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

    public Optional<RawLinkSpec> rawLink() {
        return Optional.ofNullable(rawLink);
    }

    public boolean groupable() {
        return groupable;
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
                if (key.equals(SWITCH_KEY)) switchedOn(state).ifPresent(on -> resolved.addProperty(key, on ? "OFF" : "ON"));
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
        private @Nullable RawLinkSpec rawLink;
        private boolean groupable;
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

        public Builder rawLink(RawLinkSpec spec) {
            this.rawLink = spec;
            return this;
        }

        public Builder groupable(boolean groupable) {
            this.groupable = groupable;
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
         *                               field nothing can command, or a groupable type that is not an actuator
         */
        public DeviceType build() {
            if (model.isBlank()) fail("needs a model name");
            Set<String> keys = new HashSet<>();
            for (Field<?> field : fields) {
                if (!keys.add(field.key())) fail("declares field " + field.key() + " twice");
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
            }
            for (Setting<?> setting : settings) {
                if (!fields.contains(setting.field())) fail("setting " + setting.field().key() + " uses an undeclared field");
                commanded.add(setting.field());
            }
            for (Field<?> field : fields) {
                if (field.access().writable() && !commanded.contains(field)) fail("has no entity or setting that commands field " + field.key());
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

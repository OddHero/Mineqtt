package art.rehra.mineqtt.ha;

import art.rehra.mineqtt.device.Field;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One Home Assistant entity of a device type: a component of the device's discovery message (design §4). Entities are
 * immutable; every modifier returns a copy. They are built with {@link Ha} and reference the type's fields, which the
 * discovery publisher turns into state templates and command payloads.
 *
 * @param <S> the entity's own type, so modifiers keep it
 */
public sealed interface HaEntity<S extends HaEntity<S>> permits HaEntity.Light, HaEntity.Switch, HaEntity.Number,
        HaEntity.BinarySensor, HaEntity.Sensor, HaEntity.Select, HaEntity.Event, HaEntity.Button, HaEntity.Text,
        HaEntity.Notify, HaEntity.DeviceTrigger {

    /** Home Assistant's {@code entity_category}. */
    enum Category {
        NONE,
        /** A setting, e.g. a mode select. */
        CONFIG,
        /** An internal value, e.g. the location. */
        DIAGNOSTIC
    }

    /**
     * What every entity has.
     *
     * @param key              the component key in {@code cmps} and the unique_id suffix
     * @param name             the entity name; null means the device name alone (the device's primary entity)
     * @param enabledByDefault false hides the entity in Home Assistant until a user enables it
     */
    record Meta(String key, @Nullable String name, Category category, boolean enabledByDefault) {
        private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,63}");

        public Meta {
            if (!KEY.matcher(key).matches()) throw new IllegalArgumentException("Invalid entity key: " + key);
        }

        /** A visible, uncategorised entity named after its key: {@code power_level} becomes "Power level". */
        static Meta of(String key) {
            String words = key.replace('_', ' ');
            return new Meta(key, words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1), Category.NONE, true);
        }
    }

    Meta meta();

    S withMeta(Meta meta);

    /** The Home Assistant platform, e.g. {@code light} or {@code binary_sensor}. */
    String platform();

    /** The fields whose values the entity shows; it commands them too when {@link #commands()}. */
    List<Field<?>> fields();

    /** Whether the entity sends commands to the device. */
    boolean commands();

    default String key() {
        return meta().key();
    }

    default S name(@Nullable String name) {
        return withMeta(new Meta(meta().key(), name, meta().category(), meta().enabledByDefault()));
    }

    default S config() {
        return withMeta(new Meta(meta().key(), meta().name(), Category.CONFIG, meta().enabledByDefault()));
    }

    default S diagnostic() {
        return withMeta(new Meta(meta().key(), meta().name(), Category.DIAGNOSTIC, meta().enabledByDefault()));
    }

    default S disabledByDefault() {
        return withMeta(new Meta(meta().key(), meta().name(), meta().category(), false));
    }

    /**
     * A JSON-schema light over a field whose keys sit in the state object ({@code state}, {@code brightness},
     * {@code color}, {@code color_temp_kelvin}).
     *
     * @param colorModes Home Assistant's {@code supported_color_modes}
     */
    record Light(Meta meta, Field<?> field, boolean brightness, List<String> colorModes, int minKelvin,
                 int maxKelvin) implements HaEntity<Light> {
        public Light {
            colorModes = List.copyOf(colorModes);
            if (colorModes.isEmpty()) throw new IllegalArgumentException("A light needs at least one color mode");
            if (minKelvin > maxKelvin) throw new IllegalArgumentException("Empty kelvin range");
        }

        public Light brightness(boolean supported) {
            return new Light(meta, field, supported, colorModes, minKelvin, maxKelvin);
        }

        public Light colorModes(String... modes) {
            return new Light(meta, field, brightness, List.of(modes), minKelvin, maxKelvin);
        }

        public Light kelvin(int min, int max) {
            return new Light(meta, field, brightness, colorModes, min, max);
        }

        @Override
        public Light withMeta(Meta meta) {
            return new Light(meta, field, brightness, colorModes, minKelvin, maxKelvin);
        }

        @Override
        public String platform() {
            return "light";
        }

        @Override
        public List<Field<?>> fields() {
            return List.of(field);
        }

        @Override
        public boolean commands() {
            return true;
        }
    }

    /**
     * A switch over a field: the field's {@code off} value means off, anything else on (a redstone level of 7 is on).
     * Both values must be set (and differ) before the device type is built. Home Assistant commands it with
     * {@code {"state":"ON"}} and {@code {"state":"OFF"}} on {@code /set}, so a type whose field is not {@code state}
     * maps that key in its {@code applyCommand} and its command preview (e.g. ON restores the last level).
     *
     * @param deviceClass e.g. {@code outlet}; null for none
     */
    record Switch<T>(Meta meta, Field<T> field, @Nullable T on, @Nullable T off, @Nullable String deviceClass) implements HaEntity<Switch<T>> {
        public Switch<T> on(T value) {
            return new Switch<>(meta, field, value, off, deviceClass);
        }

        public Switch<T> off(T value) {
            return new Switch<>(meta, field, on, value, deviceClass);
        }

        public Switch<T> deviceClass(String deviceClass) {
            return new Switch<>(meta, field, on, off, deviceClass);
        }

        @Override
        public Switch<T> withMeta(Meta meta) {
            return new Switch<>(meta, field, on, off, deviceClass);
        }

        @Override
        public String platform() {
            return "switch";
        }

        @Override
        public List<Field<?>> fields() {
            return List.of(field);
        }

        @Override
        public boolean commands() {
            return true;
        }
    }

    /** A number input over an integer field. */
    record Number(Meta meta, Field<Integer> field, int min, int max, int step, boolean sliderMode,
                  @Nullable String unit) implements HaEntity<Number> {
        public Number {
            if (min > max || step <= 0) throw new IllegalArgumentException("Number " + meta.key() + " needs min <= max and a positive step");
        }

        public Number range(int min, int max) {
            return new Number(meta, field, min, max, step, sliderMode, unit);
        }

        public Number step(int step) {
            return new Number(meta, field, min, max, step, sliderMode, unit);
        }

        public Number slider() {
            return new Number(meta, field, min, max, step, true, unit);
        }

        public Number unit(String unit) {
            return new Number(meta, field, min, max, step, sliderMode, unit);
        }

        @Override
        public Number withMeta(Meta meta) {
            return new Number(meta, field, min, max, step, sliderMode, unit);
        }

        @Override
        public String platform() {
            return "number";
        }

        @Override
        public List<Field<?>> fields() {
            return List.of(field);
        }

        @Override
        public boolean commands() {
            return true;
        }
    }

    /** An on/off sensor over a boolean field. */
    record BinarySensor(Meta meta, Field<Boolean> field, @Nullable String deviceClass) implements HaEntity<BinarySensor> {
        public BinarySensor deviceClass(String deviceClass) {
            return new BinarySensor(meta, field, deviceClass);
        }

        @Override
        public BinarySensor withMeta(Meta meta) {
            return new BinarySensor(meta, field, deviceClass);
        }

        @Override
        public String platform() {
            return "binary_sensor";
        }

        @Override
        public List<Field<?>> fields() {
            return List.of(field);
        }

        @Override
        public boolean commands() {
            return false;
        }
    }

    /**
     * A sensor showing a field.
     *
     * @param stateClass    e.g. {@code measurement}; null for none
     * @param valueTemplate replaces the generated template, for fields whose value is not a plain scalar
     */
    record Sensor(Meta meta, Field<?> field, @Nullable String unit, @Nullable String deviceClass,
                  @Nullable String stateClass, @Nullable String valueTemplate) implements HaEntity<Sensor> {
        public Sensor unit(String unit) {
            return new Sensor(meta, field, unit, deviceClass, stateClass, valueTemplate);
        }

        public Sensor deviceClass(String deviceClass) {
            return new Sensor(meta, field, unit, deviceClass, stateClass, valueTemplate);
        }

        public Sensor stateClass(String stateClass) {
            return new Sensor(meta, field, unit, deviceClass, stateClass, valueTemplate);
        }

        public Sensor valueTemplate(String valueTemplate) {
            return new Sensor(meta, field, unit, deviceClass, stateClass, valueTemplate);
        }

        @Override
        public Sensor withMeta(Meta meta) {
            return new Sensor(meta, field, unit, deviceClass, stateClass, valueTemplate);
        }

        @Override
        public String platform() {
            return "sensor";
        }

        @Override
        public List<Field<?>> fields() {
            return List.of(field);
        }

        @Override
        public boolean commands() {
            return false;
        }
    }

    /** A select over an enum field; the options are the enum's serialized names. */
    record Select(Meta meta, Field<?> field, List<String> options) implements HaEntity<Select> {
        public Select {
            options = List.copyOf(options);
            if (options.isEmpty()) throw new IllegalArgumentException("Select " + meta.key() + " needs options");
        }

        @Override
        public Select withMeta(Meta meta) {
            return new Select(meta, field, options);
        }

        @Override
        public String platform() {
            return "select";
        }

        @Override
        public List<Field<?>> fields() {
            return List.of(field);
        }

        @Override
        public boolean commands() {
            return true;
        }
    }

    /** Events the device publishes on its {@code action} topic, e.g. {@code press} or {@code pulse}. */
    record Event(Meta meta, List<String> eventTypes, @Nullable String deviceClass) implements HaEntity<Event> {
        public Event {
            eventTypes = List.copyOf(eventTypes);
            if (eventTypes.isEmpty()) throw new IllegalArgumentException("Event " + meta.key() + " needs event types");
        }

        public Event deviceClass(String deviceClass) {
            return new Event(meta, eventTypes, deviceClass);
        }

        @Override
        public Event withMeta(Meta meta) {
            return new Event(meta, eventTypes, deviceClass);
        }

        @Override
        public String platform() {
            return "event";
        }

        @Override
        public List<Field<?>> fields() {
            return List.of();
        }

        @Override
        public boolean commands() {
            return false;
        }
    }

    /** A button; pressing it sends {@code PRESS} to {@code /set/<key>}, a command named by its key. */
    record Button(Meta meta) implements HaEntity<Button> {
        @Override
        public Button withMeta(Meta meta) {
            return new Button(meta);
        }

        @Override
        public String platform() {
            return "button";
        }

        @Override
        public List<Field<?>> fields() {
            return List.of();
        }

        @Override
        public boolean commands() {
            return true;
        }
    }

    /** A text input over a text field. */
    record Text(Meta meta, Field<String> field, int maxLength) implements HaEntity<Text> {
        public Text {
            if (maxLength <= 0) throw new IllegalArgumentException("Text " + meta.key() + " needs a positive length");
        }

        public Text maxLength(int maxLength) {
            return new Text(meta, field, maxLength);
        }

        @Override
        public Text withMeta(Meta meta) {
            return new Text(meta, field, maxLength);
        }

        @Override
        public String platform() {
            return "text";
        }

        @Override
        public List<Field<?>> fields() {
            return List.of(field);
        }

        @Override
        public boolean commands() {
            return true;
        }
    }

    /** A notify target: Home Assistant sends it messages. */
    record Notify(Meta meta) implements HaEntity<Notify> {
        @Override
        public Notify withMeta(Meta meta) {
            return new Notify(meta);
        }

        @Override
        public String platform() {
            return "notify";
        }

        @Override
        public List<Field<?>> fields() {
            return List.of();
        }

        @Override
        public boolean commands() {
            return true;
        }
    }

    /**
     * A {@code device_automation} trigger, e.g. type {@code button_short_press}, subtype {@code left}. It fires on the
     * device's events {@code {"event_type":"<type>","subtype":"<subtype>"}}.
     */
    record DeviceTrigger(Meta meta, String type, String subtype) implements HaEntity<DeviceTrigger> {
        @Override
        public DeviceTrigger withMeta(Meta meta) {
            return new DeviceTrigger(meta, type, subtype);
        }

        @Override
        public String platform() {
            return "device_automation";
        }

        @Override
        public List<Field<?>> fields() {
            return List.of();
        }

        @Override
        public boolean commands() {
            return false;
        }
    }
}

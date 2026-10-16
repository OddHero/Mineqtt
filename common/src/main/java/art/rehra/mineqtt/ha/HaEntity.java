package art.rehra.mineqtt.ha;

import art.rehra.mineqtt.device.Field;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
     * {@code color}, {@code color_temp_kelvin}, {@code effect}). Home Assistant sends such a light a {@code transition}
     * with its commands.
     *
     * @param colorModes Home Assistant's {@code supported_color_modes}
     * @param effects    Home Assistant's {@code effect_list}; empty for a light without effects
     */
    record Light(Meta meta, Field<?> field, boolean brightness, List<String> colorModes, int minKelvin,
                 int maxKelvin, List<String> effects) implements HaEntity<Light> {
        public Light {
            colorModes = List.copyOf(colorModes);
            effects = List.copyOf(effects);
            if (colorModes.isEmpty()) throw new IllegalArgumentException("A light needs at least one color mode");
            if (minKelvin > maxKelvin) throw new IllegalArgumentException("Empty kelvin range");
        }

        public Light brightness(boolean supported) {
            return new Light(meta, field, supported, colorModes, minKelvin, maxKelvin, effects);
        }

        public Light colorModes(String... modes) {
            return new Light(meta, field, brightness, List.of(modes), minKelvin, maxKelvin, effects);
        }

        public Light kelvin(int min, int max) {
            return new Light(meta, field, brightness, colorModes, min, max, effects);
        }

        public Light effects(List<String> names) {
            return new Light(meta, field, brightness, colorModes, minKelvin, maxKelvin, names);
        }

        @Override
        public Light withMeta(Meta meta) {
            return new Light(meta, field, brightness, colorModes, minKelvin, maxKelvin, effects);
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

    /**
     * An on/off sensor over a boolean field.
     *
     * @param valueTemplate replaces the generated template, e.g. to give {@code None} (unknown) while the value means
     *                      nothing; it renders {@code ON} or {@code OFF} otherwise. Null for the generated one
     */
    record BinarySensor(Meta meta, Field<Boolean> field, @Nullable String deviceClass, @Nullable String valueTemplate) implements HaEntity<BinarySensor> {
        /** A binary sensor with the generated template. */
        public BinarySensor(Meta meta, Field<Boolean> field, @Nullable String deviceClass) {
            this(meta, field, deviceClass, null);
        }

        public BinarySensor deviceClass(String deviceClass) {
            return new BinarySensor(meta, field, deviceClass, valueTemplate);
        }

        public BinarySensor valueTemplate(String valueTemplate) {
            return new BinarySensor(meta, field, deviceClass, valueTemplate);
        }

        @Override
        public BinarySensor withMeta(Meta meta) {
            return new BinarySensor(meta, field, deviceClass, valueTemplate);
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
     * @param stateClass     e.g. {@code measurement}; null for none
     * @param valueTemplate  replaces the generated template, for fields whose value is not a plain scalar
     * @param jsonAttributes a field of the same state whose JSON object becomes the entity's attributes (M4 plan §3.2:
     *                       the Block Observer's block properties); null for none
     */
    record Sensor(Meta meta, Field<?> field, @Nullable String unit, @Nullable String deviceClass,
                  @Nullable String stateClass, @Nullable String valueTemplate, @Nullable Field<?> jsonAttributes) implements HaEntity<Sensor> {
        /** A sensor without attributes. */
        public Sensor(Meta meta, Field<?> field, @Nullable String unit, @Nullable String deviceClass, @Nullable String stateClass,
                      @Nullable String valueTemplate) {
            this(meta, field, unit, deviceClass, stateClass, valueTemplate, null);
        }

        public Sensor unit(String unit) {
            return new Sensor(meta, field, unit, deviceClass, stateClass, valueTemplate, jsonAttributes);
        }

        public Sensor deviceClass(String deviceClass) {
            return new Sensor(meta, field, unit, deviceClass, stateClass, valueTemplate, jsonAttributes);
        }

        public Sensor stateClass(String stateClass) {
            return new Sensor(meta, field, unit, deviceClass, stateClass, valueTemplate, jsonAttributes);
        }

        public Sensor valueTemplate(String valueTemplate) {
            return new Sensor(meta, field, unit, deviceClass, stateClass, valueTemplate, jsonAttributes);
        }

        /**
         * The sensor with the JSON object of {@code field} as its attributes ({@code json_attributes_topic} on the state
         * topic). The field must be declared on the type and readable, which {@code DeviceType.Builder#build()} checks.
         */
        public Sensor jsonAttributes(Field<?> field) {
            return new Sensor(meta, this.field, unit, deviceClass, stateClass, valueTemplate, Objects.requireNonNull(field, "field"));
        }

        @Override
        public Sensor withMeta(Meta meta) {
            return new Sensor(meta, field, unit, deviceClass, stateClass, valueTemplate, jsonAttributes);
        }

        @Override
        public String platform() {
            return "sensor";
        }

        /** The shown field, then the attributes' field when it is another one. */
        @Override
        public List<Field<?>> fields() {
            return jsonAttributes == null || jsonAttributes.equals(field) ? List.of(field) : List.of(field, jsonAttributes);
        }

        @Override
        public boolean commands() {
            return false;
        }
    }

    /**
     * A select over a field. Its options are fixed (an enum field's serialized names) or come from a source asked each
     * time the device's discovery is built (M4 plan §3.2: the Block Placer's allow-listed blocks), over a TEXT or ENUM
     * field. Runtime options are sorted, without duplicates or blank entries, at most {@value #MAX_OPTIONS}; they are part
     * of the discovery message, so its hash changes with them. A source that yields none makes the select a
     * platform-only stub in the message rather than publish one Home Assistant refuses; the stub removes it there.
     *
     * @param options       the fixed options; empty for a select with a source
     * @param optionsSource where the runtime options come from; null for fixed options
     * @param valueTemplate replaces the generated template, so the state is always an option (a block id without its
     *                      properties); null for the generated one
     * @param stateOptions  a list field of the device's own state whose entries, when the state has any, are the options
     *                      instead of the source's (a Block Placer offers the blocks in the container behind it); null
     *                      for none. A group, which has no such state, offers the source's.
     */
    record Select(Meta meta, Field<?> field, List<String> options, @Nullable Supplier<List<String>> optionsSource,
                  @Nullable String valueTemplate, @Nullable Field<List<String>> stateOptions) implements HaEntity<Select> {
        /** The most options a select with a source offers. */
        public static final int MAX_OPTIONS = 128;

        public Select {
            options = List.copyOf(options);
            if (optionsSource == null && options.isEmpty()) throw new IllegalArgumentException("Select " + meta.key() + " needs options");
            if (optionsSource != null && !options.isEmpty()) throw new IllegalArgumentException("Select " + meta.key() + " has fixed options and a source");
            if (stateOptions != null && optionsSource == null) throw new IllegalArgumentException("Select " + meta.key() + " takes options from its state but has no source");
        }

        public Select(Meta meta, Field<?> field, List<String> options, @Nullable Supplier<List<String>> optionsSource, @Nullable String valueTemplate) {
            this(meta, field, options, optionsSource, valueTemplate, null);
        }

        /** A select with fixed options. */
        public Select(Meta meta, Field<?> field, List<String> options) {
            this(meta, field, options, null, null);
        }

        /** Whether the options come from a source ({@link #currentOptions()}). */
        public boolean dynamic() {
            return optionsSource != null;
        }

        public Select valueTemplate(String valueTemplate) {
            return new Select(meta, field, options, optionsSource, valueTemplate, stateOptions);
        }

        /** Takes the options from a list field of the device's state while it has entries ({@link #currentOptions(JsonObject)}). */
        public Select optionsFrom(Field<List<String>> stateOptions) {
            return new Select(meta, field, options, optionsSource, valueTemplate, stateOptions);
        }

        /**
         * The options as discovery publishes them now: the fixed ones, or the source's, sorted, distinct, without blank
         * entries and cut to {@value #MAX_OPTIONS}. Empty when the source has none, or fails, which makes the select a
         * platform-only stub in the device's discovery message, so Home Assistant removes it.
         */
        public List<String> currentOptions() {
            return currentOptions(null);
        }

        /**
         * The options for a device whose state is {@code state}: the entries of its {@link #stateOptions} list when it has
         * any, normalised as the source's are, else {@link #currentOptions()}'s.
         *
         * @param state the device's last state; null for a group or a device without one
         */
        public List<String> currentOptions(@Nullable JsonObject state) {
            if (optionsSource == null) return options;
            if (stateOptions != null && state != null && state.get(stateOptions.key()) instanceof JsonArray array && !array.isEmpty()) {
                List<String> fromState = new ArrayList<>();
                for (JsonElement entry : array) {
                    if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) fromState.add(entry.getAsString());
                }
                List<String> normalised = normalise(fromState);
                if (!normalised.isEmpty()) return normalised;
            }
            List<String> offered;
            try {
                offered = optionsSource.get();
            } catch (RuntimeException e) {
                return List.of();
            }
            return normalise(offered);
        }

        // Sorted, distinct, without blank entries, at most MAX_OPTIONS.
        private static List<String> normalise(@Nullable List<String> offered) {
            if (offered == null) return List.of();
            TreeSet<String> sorted = new TreeSet<>();
            for (String option : offered) {
                if (option != null && !option.isBlank()) sorted.add(option);
            }
            return sorted.stream().limit(MAX_OPTIONS).toList();
        }

        @Override
        public Select withMeta(Meta meta) {
            return new Select(meta, field, options, optionsSource, valueTemplate, stateOptions);
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

    /**
     * A text input over a text field.
     *
     * @param pattern a regular expression Home Assistant checks the text against, in its frontend (JavaScript) and its
     *                backend (Python), e.g. {@code HH:MM}; null for none. Only the subset all three engines read alike
     *                is accepted ({@link #patternProblem}).
     */
    record Text(Meta meta, Field<String> field, int maxLength, @Nullable String pattern) implements HaEntity<Text> {
        /** The longest pattern a text accepts. */
        public static final int MAX_PATTERN_LENGTH = 128;
        // The escapes Java, JavaScript and Python read alike: classes, boundaries, control characters and escaped specials.
        private static final String PORTABLE_ESCAPES = "dDwWsSbBnrtf\\.^$|?*+()[]{}-/";

        public Text {
            if (maxLength <= 0) throw new IllegalArgumentException("Text " + meta.key() + " needs a positive length");
            if (pattern != null) {
                Optional<String> problem = patternProblem(pattern);
                if (problem.isPresent()) throw new IllegalArgumentException("Text " + meta.key() + ": " + problem.get());
            }
        }

        /** A text without a pattern. */
        public Text(Meta meta, Field<String> field, int maxLength) {
            this(meta, field, maxLength, null);
        }

        public Text maxLength(int maxLength) {
            return new Text(meta, field, maxLength, pattern);
        }

        /** The text with a pattern Home Assistant checks it against ({@link #patternProblem}). */
        public Text pattern(String regex) {
            return new Text(meta, field, maxLength, Objects.requireNonNull(regex, "regex"));
        }

        /**
         * Why {@code regex} cannot be a text's pattern, or empty when it can: at most {@value #MAX_PATTERN_LENGTH}
         * characters, a valid Java regular expression, and within the subset JavaScript and Python read the same way:
         * escapes only of {@code \d \D \w \W \s \S \b \B \n \r \t \f} and of special characters, groups only
         * {@code (...)}, {@code (?:...)} and lookarounds, no possessive quantifiers, no character class intersection.
         */
        public static Optional<String> patternProblem(String regex) {
            if (regex.isEmpty()) return Optional.of("the pattern is empty");
            if (regex.length() > MAX_PATTERN_LENGTH) return Optional.of("the pattern is longer than " + MAX_PATTERN_LENGTH + " characters");
            try {
                Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                return Optional.of("the pattern is no regular expression: " + e.getDescription());
            }
            boolean inClass = false;
            for (int i = 0; i < regex.length(); i++) {
                char c = regex.charAt(i);
                if (c == '\\') {
                    char escaped = regex.charAt(i + 1);
                    if (PORTABLE_ESCAPES.indexOf(escaped) < 0) return Optional.of("the escape \\" + escaped + " differs between Java, JavaScript and Python");
                    i++;
                } else if (inClass) {
                    if (c == ']') inClass = false;
                    else if (c == '[') return Optional.of("a nested character class differs between Java, JavaScript and Python");
                    else if (c == '&' && i + 1 < regex.length() && regex.charAt(i + 1) == '&') return Optional.of("a class intersection (&&) is Java only");
                } else if (c == '[') {
                    inClass = true;
                    // A ] right after [ or [^ is a literal in Java and Python but closes an empty class in JavaScript.
                    int next = i + 1 < regex.length() && regex.charAt(i + 1) == '^' ? i + 2 : i + 1;
                    if (next < regex.length() && regex.charAt(next) == ']') return Optional.of("a ] at the start of a class differs between Java, JavaScript and Python");
                } else if (c == '(' && i + 1 < regex.length() && regex.charAt(i + 1) == '?') {
                    String group = regex.substring(i + 2, Math.min(regex.length(), i + 4));
                    if (!(group.startsWith(":") || group.startsWith("=") || group.startsWith("!") || group.startsWith("<=") || group.startsWith("<!"))) {
                        return Optional.of("only (...), (?:...) and lookaround groups read alike in Java, JavaScript and Python");
                    }
                } else if (c == '+' && i > 0 && "*+?}".indexOf(regex.charAt(i - 1)) >= 0 && !escaped(regex, i - 1)) {
                    return Optional.of("a possessive quantifier is not JavaScript");
                }
            }
            return Optional.empty();
        }

        // Whether the character at index is escaped: an odd run of backslashes before it (\\+ is a literal backslash, then +).
        private static boolean escaped(String regex, int index) {
            int backslashes = 0;
            for (int i = index - 1; i >= 0 && regex.charAt(i) == '\\'; i--) backslashes++;
            return backslashes % 2 == 1;
        }

        @Override
        public Text withMeta(Meta meta) {
            return new Text(meta, field, maxLength, pattern);
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
     * A {@code device_automation} trigger, e.g. type {@code button_short_press}, subtype {@code left}: Home Assistant
     * names it after both. It fires on the device's declared event {@code event} whose attribute {@code attribute} is
     * the subtype, e.g. {@code {"event_type":"press","button":"left"}}.
     */
    record DeviceTrigger(Meta meta, String type, String subtype, String event, String attribute) implements HaEntity<DeviceTrigger> {
        /** What the trigger's template makes of an event it fires on, and so its payload: {@code press_left}. */
        public String payload() {
            return event + "_" + subtype;
        }

        @Override
        public DeviceTrigger withMeta(Meta meta) {
            return new DeviceTrigger(meta, type, subtype, event, attribute);
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

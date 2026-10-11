package art.rehra.mineqtt.twin.core;

import art.rehra.mineqtt.flow.core.Coerce;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An MQTT device preset (M3 plan M3-17, design §5.2): how a device that speaks MQTT but is no Home Assistant entity
 * (zigbee2mqtt, Tasmota, Shelly, ESPHome, any topic) is mirrored and switched, loaded from a datapack
 * ({@link PresetCodec}). A preset makes the device look like a Home Assistant entity of its {@link #kind()}, so every
 * device type's twin adapter reads and forwards it unchanged:
 * <ul>
 *     <li><b>Reading.</b> The payload on the {@link #stateTopic(String) state topic} becomes an {@link EntityDocument}
 *     ({@link #document}): its state from the value at the {@link #state() state path}, spelled {@code on}/{@code off}
 *     through the preset's spellings ({@link #on()}, {@link #off()}), its attributes renamed to Home Assistant's names
 *     ({@link #attributes()}), plus constants such as a unit.</li>
 *     <li><b>Writing.</b> A forwarded command becomes the same {@link Call} as for a Home Assistant entity of that kind
 *     (built by {@link HaCalls} from typed values, deduped and switched as D11 says), which {@link #render} turns into
 *     the preset's command for that service: a JSON template whose placeholders are replaced by the call's typed values
 *     through Gson (so every string is JSON-escaped and a member whose value the call lacks is left out), or a text
 *     template that takes numbers only. Nothing a player typed reaches a payload, and the topic comes from the preset
 *     and the device name alone.</li>
 * </ul>
 * The device name ({@value #DEVICE}) is the one parameter, used only in topics and checked as part of one
 * ({@link #deviceProblem}). Pure, immutable and Minecraft-free.
 *
 * @param id             {@code <namespace>:<path>}, from the datapack file's location
 * @param name           what players read, e.g. {@code zigbee2mqtt light}
 * @param kind           the Home Assistant domain the device behaves as ({@link HaDomains#readable})
 * @param stateTopic     the state topic with {@value #DEVICE} in it
 * @param state          where the state is in the payload; {@link ValuePath#WHOLE} for the whole payload
 * @param on             payload values that mean on, compared as JSON; empty when the state is read as it is
 * @param off            payload values that mean off
 * @param attributes     Home Assistant attribute name → where it is in the payload
 * @param constants      Home Assistant attribute name → a fixed value, e.g. {@code unit_of_measurement}
 * @param payloadAttributes whether every top-level member of an object payload is an attribute too (sensors)
 * @param commandTopic   the command topic with {@value #DEVICE} in it; null for a read-only preset
 * @param commandRetain  whether commands are published retained
 * @param commands       Home Assistant service of {@link #kind()} → its command template
 * @param ranges         placeholder → the range its value is clamped to, e.g. zigbee2mqtt's brightness 0–254
 */
public record BindingPreset(String id, String name, String kind, String stateTopic, ValuePath state, List<JsonElement> on, List<JsonElement> off,
                            Map<String, ValuePath> attributes, Map<String, JsonElement> constants, boolean payloadAttributes, String commandTopic,
                            boolean commandRetain, Map<String, JsonElement> commands, Map<String, Range> ranges) {
    /** The device name's placeholder in a topic. */
    public static final String DEVICE = "{device}";
    /** A preset id: a namespace and a path, as a datapack file location gives them. */
    public static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    /** The longest preset id. */
    public static final int MAX_ID_LENGTH = 128;
    /** The longest device name, in UTF-8 bytes. */
    public static final int MAX_DEVICE_BYTES = 128;
    /** The longest serialized command template, in UTF-8 bytes. */
    public static final int MAX_TEMPLATE_BYTES = 2048;
    /** The longest command a template renders, in UTF-8 bytes. */
    public static final int MAX_COMMAND_BYTES = 4096;
    /** A placeholder in a template: {@code {brightness}}. */
    public static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");
    /** The longest state a document keeps, as Home Assistant's own states (255 characters). */
    public static final int MAX_STATE_LENGTH = HaDomains.MAX_TEXT;
    // A payload member a sensor's document keeps as an attribute: a key that is one topic level, as a statestream leaf's.
    private static final Pattern ATTRIBUTE_KEY = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_-]{0,63}");

    /**
     * Every placeholder a template may use → the {@link Call} data key it comes from: the data keys themselves, the
     * colour's channels, the colour temperature in mireds and the brightness in percent.
     */
    public static final Map<String, String> PLACEHOLDERS = placeholders();

    /** Placeholders whose value is text (an option), which a text template may not take. */
    public static final Set<String> TEXT_PLACEHOLDERS = Set.of("option");

    /** An inclusive range a placeholder's number is clamped to. */
    public record Range(BigDecimal min, BigDecimal max) {
        public Range {
            Objects.requireNonNull(min, "min");
            Objects.requireNonNull(max, "max");
            if (min.compareTo(max) > 0) throw new IllegalArgumentException("A range's minimum is above its maximum");
        }

        BigDecimal clamp(BigDecimal value) {
            return value.compareTo(min) < 0 ? min : value.compareTo(max) > 0 ? max : value;
        }
    }

    /** What {@link #render} made of a call. */
    public sealed interface Rendered permits Command, Refused {
    }

    /**
     * The command to publish on the {@link #commandTopic(String) command topic}.
     *
     * @param payload the payload's text: JSON for a JSON template, else the text template's
     */
    public record Command(String payload, boolean json) implements Rendered {
        public byte[] bytes() {
            return payload.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Why the preset cannot send the call. */
    public record Refused(String reason) implements Rendered {
    }

    public BindingPreset {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(stateTopic, "stateTopic");
        Objects.requireNonNull(state, "state");
        on = copy(on);
        off = copy(off);
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        Map<String, JsonElement> constantsCopy = new LinkedHashMap<>();
        constants.forEach((key, value) -> constantsCopy.put(key, value.deepCopy()));
        constants = Collections.unmodifiableMap(constantsCopy);
        Map<String, JsonElement> commandsCopy = new LinkedHashMap<>();
        commands.forEach((service, template) -> commandsCopy.put(service, template.deepCopy()));
        commands = Collections.unmodifiableMap(commandsCopy);
        ranges = Collections.unmodifiableMap(new LinkedHashMap<>(ranges));
        if (commandTopic == null && !commands.isEmpty()) throw new IllegalArgumentException("Commands need a command topic");
    }

    private static List<JsonElement> copy(List<JsonElement> values) {
        List<JsonElement> copy = new ArrayList<>();
        values.forEach(value -> copy.add(value.deepCopy()));
        return Collections.unmodifiableList(copy);
    }

    @Override
    public List<JsonElement> on() {
        return copy(on);
    }

    @Override
    public List<JsonElement> off() {
        return copy(off);
    }

    @Override
    public Map<String, JsonElement> commands() {
        Map<String, JsonElement> copy = new LinkedHashMap<>();
        commands.forEach((service, template) -> copy.put(service, template.deepCopy()));
        return Collections.unmodifiableMap(copy);
    }

    /** Whether the preset can switch the device: it has a command topic and at least one command. */
    public boolean controllable() {
        return commandTopic != null && !commands.isEmpty();
    }

    /** Whether the preset sends {@code service} of its kind ({@code turn_on}, {@code toggle}). */
    public boolean sends(String service) {
        return commands.containsKey(service);
    }

    /**
     * Why {@code device} cannot name a device of this preset, or empty when it can: it is not empty, at most
     * {@value #MAX_DEVICE_BYTES} bytes, has no wildcard, NUL, control character, empty topic level or leading {@code $},
     * and the topics it makes are valid topics of at most 256 bytes.
     */
    public Optional<String> deviceProblem(String device) {
        if (device == null || device.isEmpty()) return Optional.of("name the device, as the preset's topic names it");
        if (device.getBytes(StandardCharsets.UTF_8).length > MAX_DEVICE_BYTES) return Optional.of("a device name has at most " + MAX_DEVICE_BYTES + " bytes");
        for (int i = 0; i < device.length(); i++) {
            char c = device.charAt(i);
            if (c == '+' || c == '#') return Optional.of("a device name may not contain the wildcards + or #");
            if (c < 0x20 || c == 0x7f) return Optional.of("a device name may not contain control characters");
        }
        if (device.startsWith("$")) return Optional.of("a device name may not start with $");
        if (device.startsWith("/") || device.endsWith("/") || device.contains("//")) return Optional.of("a device name may not make an empty topic level");
        for (String topic : topics(device)) {
            if (topic.getBytes(StandardCharsets.UTF_8).length > 256) return Optional.of("the topic " + topic + " is longer than 256 bytes");
            if (topic.startsWith("/") || topic.endsWith("/") || topic.contains("//")) return Optional.of("the topic " + topic + " has an empty level");
        }
        return Optional.empty();
    }

    /** The state topic of {@code device}; the caller checked the name ({@link #deviceProblem}). */
    public String stateTopic(String device) {
        return stateTopic.replace(DEVICE, device);
    }

    /** The command topic of {@code device}; empty for a read-only preset. */
    public Optional<String> commandTopic(String device) {
        return commandTopic == null ? Optional.empty() : Optional.of(commandTopic.replace(DEVICE, device));
    }

    private List<String> topics(String device) {
        List<String> topics = new ArrayList<>();
        topics.add(stateTopic(device));
        commandTopic(device).ifPresent(topics::add);
        return topics;
    }

    /**
     * The id of the documents a preset source makes for {@code topic}: an entity id of {@code kind} that no Home
     * Assistant entity has in practice ({@code light.mqtt_<16 hex digits of the topic's SHA-256>}). It only names the
     * document to the adapters; such documents never enter the Home Assistant documents or the read gate.
     */
    public static String documentId(String kind, String topic) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(topic.getBytes(StandardCharsets.UTF_8));
            return kind + ".mqtt_" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
    }

    /**
     * The document a payload on {@code topic} gives (M3 plan D1 #6 holds for it as for an entity): a zero-length payload
     * is {@link StatestreamValue#CLEARED} (the retained state was deleted); a payload that is no UTF-8, larger than
     * {@value ValuePath#MAX_PAYLOAD_BYTES} bytes or has nothing at the state path is {@link StatestreamValue#UNKNOWN};
     * a value matching {@link #on()} or {@link #off()} is {@code on} or {@code off}; any other value its text, at most
     * {@value #MAX_STATE_LENGTH} characters ({@code unavailable} and {@code unknown} as Home Assistant spells them).
     * A live message is {@link EntityDocument.Freshness#LIVE}, a retained one {@link EntityDocument.Freshness#RETAINED}.
     */
    public EntityDocument document(String topic, byte[] payload, boolean retained, long receivedAt) {
        EntityDocument.Freshness freshness = retained ? EntityDocument.Freshness.RETAINED : EntityDocument.Freshness.LIVE;
        String id = documentId(kind, topic);
        if (payload.length == 0) return new EntityDocument(id, StatestreamValue.CLEARED, Map.of(), null, null, receivedAt, freshness, EntityDocument.Origin.STATESTREAM);
        Optional<JsonElement> json = ValuePath.payload(payload);
        if (json.isEmpty()) return new EntityDocument(id, StatestreamValue.UNKNOWN, Map.of(), null, null, receivedAt, freshness, EntityDocument.Origin.STATESTREAM);
        StatestreamValue stateValue = state.select(json.get()).map(this::stateOf).orElse(StatestreamValue.UNKNOWN);
        Map<String, JsonElement> attrs = new LinkedHashMap<>();
        if (payloadAttributes && json.get().isJsonObject()) {
            for (Map.Entry<String, JsonElement> member : json.get().getAsJsonObject().entrySet()) {
                if (attrs.size() >= EntityDocuments.Limits.DEFAULT.maxAttributes()) break;
                if (attributeKey(member.getKey())) attrs.put(member.getKey(), member.getValue());
            }
        }
        attributes.forEach((attribute, path) -> path.select(json.get()).ifPresent(value -> attrs.put(attribute, value)));
        constants.forEach(attrs::put);
        return new EntityDocument(id, stateValue, attrs, null, null, receivedAt, freshness, EntityDocument.Origin.STATESTREAM);
    }

    // A payload member a sensor's document keeps as an attribute: one topic level as a statestream leaf would be, never
    // a reserved leaf or an attribute MineQTT never reads (GPS, pictures).
    private static boolean attributeKey(String key) {
        return ATTRIBUTE_KEY.matcher(key).matches() && !HaDomains.NEVER_ATTRIBUTES.contains(key) && !key.equals(ValuePath.STATE)
                && !key.equals(ValuePath.LAST_CHANGED) && !key.equals(ValuePath.LAST_UPDATED);
    }

    private StatestreamValue stateOf(JsonElement value) {
        if (value.isJsonNull()) return StatestreamValue.UNKNOWN;
        if (matches(on, value)) return new StatestreamValue.Text("on");
        if (matches(off, value)) return new StatestreamValue.Text("off");
        String text = value.isJsonPrimitive() ? value.getAsString() : value.toString();
        if (text.length() > MAX_STATE_LENGTH) text = text.substring(0, MAX_STATE_LENGTH);
        return StatestreamValue.of(text);
    }

    // A spelling matches its JSON value; a text spelling also matches the same text in another case (ON, On, on).
    private static boolean matches(List<JsonElement> spellings, JsonElement value) {
        for (JsonElement spelling : spellings) {
            if (spelling.equals(value)) return true;
            if (spelling.isJsonPrimitive() && spelling.getAsJsonPrimitive().isString() && value.isJsonPrimitive()
                    && value.getAsJsonPrimitive().isString() && spelling.getAsString().equalsIgnoreCase(value.getAsString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The command for {@code call} (D11's typed values only): refused for a call of another domain than the preset's
     * kind, a service the preset has no command for, a text template missing a value or given text, a JSON template left
     * with nothing (every member's value missing, an array missing an element), and a command over
     * {@value #MAX_COMMAND_BYTES} bytes.
     */
    public Rendered render(Call call) {
        if (!call.domain().equals(kind)) return new Refused("the " + name + " preset is no " + call.domain());
        JsonElement template = commands.get(call.service());
        if (template == null) return new Refused("the " + name + " preset cannot " + call.service().replace('_', ' '));
        Map<String, JsonPrimitive> values = values(call.data());
        String payload;
        boolean json;
        if (template.isJsonPrimitive()) {
            Optional<String> text = fillText(template.getAsString(), values);
            if (text.isEmpty()) return new Refused("the " + name + " preset sends " + call.service().replace('_', ' ') + " as text and needs a number for it");
            payload = text.get();
            json = false;
        } else {
            // Every member left out (or an array missing an element) leaves no command: refused here, never sent as {}.
            Optional<JsonElement> filled = fill(template, values);
            if (filled.isEmpty()) {
                return new Refused("the " + name + " preset's " + call.service().replace('_', ' ') + " command needs a value this one does not carry");
            }
            payload = filled.get().toString();
            json = true;
        }
        if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_COMMAND_BYTES) return new Refused("the command is larger than " + MAX_COMMAND_BYTES + " bytes");
        return new Command(payload, json);
    }

    /** The placeholder values a call's data gives, clamped to the preset's ranges. */
    Map<String, JsonPrimitive> values(JsonObject data) {
        Map<String, JsonPrimitive> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
            JsonElement value = entry.getValue();
            if (entry.getKey().equals("rgb_color") && value.isJsonArray() && value.getAsJsonArray().size() == 3) {
                JsonArray rgb = value.getAsJsonArray();
                String[] channels = {"red", "green", "blue"};
                for (int i = 0; i < 3; i++) {
                    if (rgb.get(i).isJsonPrimitive() && rgb.get(i).getAsJsonPrimitive().isNumber()) values.put(channels[i], rgb.get(i).getAsJsonPrimitive());
                }
            } else if (value.isJsonPrimitive()) {
                // Numbers stay numbers, also when the call carried one as text; an option or a text value stays text.
                boolean text = TEXT_PLACEHOLDERS.contains(entry.getKey()) || entry.getKey().equals("value") && !value.getAsJsonPrimitive().isNumber();
                if (text) values.put(entry.getKey(), value.getAsJsonPrimitive());
                else Coerce.decimal(value).ifPresent(number -> values.put(entry.getKey(), number(number)));
            }
        }
        JsonPrimitive kelvin = values.get("color_temp_kelvin");
        if (kelvin != null && kelvin.isNumber() && kelvin.getAsBigDecimal().signum() > 0) {
            values.put("color_temp_mired", new JsonPrimitive(BigDecimal.valueOf(1_000_000).divide(kelvin.getAsBigDecimal(), MathContext.DECIMAL64)
                    .setScale(0, RoundingMode.HALF_UP).intValueExact()));
        }
        JsonPrimitive brightness = values.get("brightness");
        if (brightness != null && brightness.isNumber()) {
            values.put("brightness_pct", new JsonPrimitive(brightness.getAsBigDecimal().multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(255), 0, RoundingMode.HALF_UP).intValue()));
        }
        Map<String, JsonPrimitive> clamped = new LinkedHashMap<>();
        values.forEach((name, value) -> {
            Range range = ranges.get(name);
            clamped.put(name, range != null && value.isNumber() ? number(range.clamp(value.getAsBigDecimal())) : value);
        });
        return clamped;
    }

    private static JsonPrimitive number(BigDecimal value) {
        BigDecimal plain = value.stripTrailingZeros();
        if (plain.scale() <= 0) {
            try {
                return new JsonPrimitive(plain.longValueExact());
            } catch (ArithmeticException e) {
                return new JsonPrimitive(plain);
            }
        }
        return new JsonPrimitive(plain);
    }

    /**
     * A JSON template filled with typed values: a string that is exactly one placeholder becomes its value as JSON (a
     * number stays a number, text is a JSON string); a string with placeholders in it gets their text; a member whose
     * placeholder has no value is left out, an array with such an element too. Empty when nothing is left.
     */
    static Optional<JsonElement> fill(JsonElement template, Map<String, JsonPrimitive> values) {
        if (template.isJsonObject()) {
            JsonObject filled = new JsonObject();
            for (Map.Entry<String, JsonElement> member : template.getAsJsonObject().entrySet()) {
                fill(member.getValue(), values).ifPresent(value -> filled.add(member.getKey(), value));
            }
            return filled.isEmpty() && !template.getAsJsonObject().isEmpty() ? Optional.empty() : Optional.of(filled);
        }
        if (template.isJsonArray()) {
            JsonArray filled = new JsonArray();
            for (JsonElement element : template.getAsJsonArray()) {
                Optional<JsonElement> value = fill(element, values);
                if (value.isEmpty()) return Optional.empty();
                filled.add(value.get());
            }
            return Optional.of(filled);
        }
        if (template.isJsonPrimitive() && template.getAsJsonPrimitive().isString()) {
            String text = template.getAsString();
            Matcher whole = PLACEHOLDER.matcher(text);
            if (whole.matches()) {
                JsonPrimitive value = values.get(whole.group(1));
                return value == null ? Optional.empty() : Optional.of(value.deepCopy());
            }
            return substitute(text, values).map(JsonPrimitive::new);
        }
        return Optional.of(template.deepCopy());
    }

    // A text template's command: every placeholder a number; empty when one has no value or is text.
    private static Optional<String> fillText(String template, Map<String, JsonPrimitive> values) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            JsonPrimitive value = values.get(matcher.group(1));
            if (value == null || !value.isNumber()) return Optional.empty();
        }
        return substitute(template, values);
    }

    // The placeholders replaced by their values' text; empty when one has none.
    private static Optional<String> substitute(String text, Map<String, JsonPrimitive> values) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            JsonPrimitive value = values.get(matcher.group(1));
            if (value == null) return Optional.empty();
            matcher.appendReplacement(out, Matcher.quoteReplacement(text(value)));
        }
        matcher.appendTail(out);
        return Optional.of(out.toString());
    }

    private static String text(JsonPrimitive value) {
        return value.isNumber() ? value.getAsBigDecimal().stripTrailingZeros().toPlainString() : value.getAsString();
    }

    /** The placeholders a template uses, in order, each once. */
    static Set<String> placeholdersOf(JsonElement template) {
        Set<String> found = new LinkedHashSet<>();
        collect(template, found);
        return found;
    }

    private static void collect(JsonElement template, Set<String> found) {
        if (template.isJsonObject()) {
            template.getAsJsonObject().entrySet().forEach(member -> collect(member.getValue(), found));
        } else if (template.isJsonArray()) {
            template.getAsJsonArray().forEach(element -> collect(element, found));
        } else if (template.isJsonPrimitive() && template.getAsJsonPrimitive().isString()) {
            Matcher matcher = PLACEHOLDER.matcher(template.getAsString());
            while (matcher.find()) found.add(matcher.group(1));
        }
    }

    private static Map<String, String> placeholders() {
        Map<String, String> placeholders = new LinkedHashMap<>();
        for (String key : List.of("brightness", "color_temp_kelvin", "transition", "percentage", "position", "value", "option", "temperature")) {
            placeholders.put(key, key);
        }
        placeholders.put("brightness_pct", "brightness");
        placeholders.put("red", "rgb_color");
        placeholders.put("green", "rgb_color");
        placeholders.put("blue", "rgb_color");
        placeholders.put("color_temp_mired", "color_temp_kelvin");
        return Collections.unmodifiableMap(placeholders);
    }

    @Override
    public String toString() {
        return id + " (" + kind + ", " + stateTopic + (commandTopic == null ? "" : " → " + commandTopic) + ")";
    }
}

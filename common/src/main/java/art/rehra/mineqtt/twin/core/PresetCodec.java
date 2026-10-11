package art.rehra.mineqtt.twin.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads MQTT device presets ({@link BindingPreset}) from datapack JSON (M3 plan M3-17,
 * {@code data/<namespace>/mineqtt/binding_presets/<path>.json}, id {@code <namespace>:<path>}). Strict: an unknown
 * key, a value of the wrong type, a topic without {@value BindingPreset#DEVICE} or with a wildcard, a service the kind
 * does not have, an unknown placeholder or one the service's data cannot give, and a template over
 * {@value BindingPreset#MAX_TEMPLATE_BYTES} bytes make the preset a problem line, and it is skipped; the others load.
 * <pre>
 * { "name": "zigbee2mqtt light", "kind": "light",
 *   "state_topic": "zigbee2mqtt/{device}", "state": "state", "on": "ON", "off": "OFF",
 *   "attributes": { "brightness": "brightness", "color": "color" },
 *   "command_topic": "zigbee2mqtt/{device}/set",
 *   "commands": { "turn_on": { "state": "ON", "brightness": "{brightness}" }, "turn_off": { "state": "OFF" } },
 *   "ranges": { "brightness": [0, 254] } }
 * </pre>
 * Pure and Minecraft-free.
 */
public final class PresetCodec {
    /** The most presets loaded; the rest are problem lines. */
    public static final int MAX_PRESETS = 256;
    /** The largest preset file, in UTF-8 bytes. */
    public static final int MAX_FILE_BYTES = 16 * 1024;
    /** The longest preset name. */
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_ATTRIBUTES = 32;
    public static final int MAX_CONSTANTS = 16;
    public static final int MAX_SPELLINGS = 8;

    private static final Set<String> KEYS = Set.of("name", "kind", "state_topic", "state", "on", "off", "attributes", "constants", "payload_attributes",
            "command_topic", "command_retain", "commands", "ranges");
    private static final Pattern ATTRIBUTE = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    // Any brace in a string of a template or topic, to find one that is no placeholder.
    private static final Pattern BRACE = Pattern.compile("[{}]");

    private PresetCodec() {
    }

    /** What {@link #parse} made of one file: the preset, or why it is skipped. */
    public record Parsed(Optional<BindingPreset> preset, List<String> problems) {
        public Parsed {
            problems = List.copyOf(problems);
        }
    }

    /** What {@link #loadAll} loaded: presets by id, in id order, and a line per skipped file. */
    public record Loaded(Map<String, BindingPreset> presets, List<String> problems) {
        public Loaded {
            presets = Collections.unmodifiableMap(new LinkedHashMap<>(presets));
            problems = List.copyOf(problems);
        }

        public static final Loaded EMPTY = new Loaded(Map.of(), List.of());
    }

    /**
     * Every preset of a datapack reload: file id → its text. A file that is not a JSON object, too large, malformed or
     * breaking a rule is skipped with a problem line naming it.
     */
    public static Loaded loadAll(Map<String, String> files) {
        Map<String, BindingPreset> presets = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> file : new TreeMap<>(files).entrySet()) {
            String id = file.getKey();
            if (presets.size() >= MAX_PRESETS) {
                problems.add("binding preset " + id + " skipped: more than " + MAX_PRESETS + " presets");
                continue;
            }
            if (file.getValue().getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
                problems.add("binding preset " + id + " skipped: larger than " + MAX_FILE_BYTES + " bytes");
                continue;
            }
            Optional<JsonElement> json = EntityDocuments.strictJson(file.getValue());
            if (json.isEmpty()) {
                problems.add("binding preset " + id + " skipped: not valid JSON");
                continue;
            }
            Parsed parsed = parse(id, json.get());
            if (parsed.preset().isPresent()) {
                presets.put(id, parsed.preset().get());
            } else {
                problems.add("binding preset " + id + " skipped: " + String.join("; ", parsed.problems()));
            }
        }
        return new Loaded(presets, problems);
    }

    /** One preset; every rule it breaks is a problem, and then it is skipped. */
    public static Parsed parse(String id, JsonElement json) {
        List<String> problems = new ArrayList<>();
        if (id.length() > BindingPreset.MAX_ID_LENGTH || !BindingPreset.ID.matcher(id).matches()) problems.add("'" + id + "' is no preset id");
        if (!json.isJsonObject()) return new Parsed(Optional.empty(), List.of("a preset is a JSON object"));
        JsonObject root = json.getAsJsonObject();
        for (String key : root.keySet()) {
            if (!KEYS.contains(key)) problems.add("unknown key " + key);
        }
        String name = string(root, "name", problems).orElse(id.substring(id.indexOf(':') + 1));
        if (name.isBlank() || name.length() > MAX_NAME_LENGTH || name.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            problems.add("name has 1 to " + MAX_NAME_LENGTH + " printable characters");
        }
        String kind = string(root, "kind", problems).orElse(null);
        if (kind == null) problems.add("kind is missing");
        else if (!HaDomains.readable(kind)) problems.add("kind " + kind + " is no Home Assistant domain MineQTT reads");
        String stateTopic = string(root, "state_topic", problems).orElse(null);
        if (stateTopic == null) problems.add("state_topic is missing");
        else topicProblem("state_topic", stateTopic).ifPresent(problems::add);
        ValuePath state = string(root, "state", problems).map(text -> {
            Optional<String> problem = ValuePath.problem(text);
            problem.ifPresent(p -> problems.add("state: " + p));
            return problem.isEmpty() ? ValuePath.parse(text) : ValuePath.WHOLE;
        }).orElse(ValuePath.WHOLE);
        List<JsonElement> on = spellings(root, "on", problems);
        List<JsonElement> off = spellings(root, "off", problems);
        if (on.isEmpty() != off.isEmpty()) problems.add("on and off come together");
        Map<String, ValuePath> attributes = new LinkedHashMap<>();
        object(root, "attributes", problems).ifPresent(object -> {
            if (object.size() > MAX_ATTRIBUTES) problems.add("at most " + MAX_ATTRIBUTES + " attributes");
            object.entrySet().forEach(entry -> {
                if (!ATTRIBUTE.matcher(entry.getKey()).matches() || HaDomains.NEVER_ATTRIBUTES.contains(entry.getKey())) {
                    problems.add("attribute " + entry.getKey() + " is no attribute name MineQTT reads");
                } else if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                    problems.add("attribute " + entry.getKey() + " is a value path string");
                } else {
                    Optional<String> problem = ValuePath.problem(entry.getValue().getAsString());
                    if (problem.isPresent()) problems.add("attribute " + entry.getKey() + ": " + problem.get());
                    else attributes.put(entry.getKey(), ValuePath.parse(entry.getValue().getAsString()));
                }
            });
        });
        Map<String, JsonElement> constants = new LinkedHashMap<>();
        object(root, "constants", problems).ifPresent(object -> {
            if (object.size() > MAX_CONSTANTS) problems.add("at most " + MAX_CONSTANTS + " constants");
            object.entrySet().forEach(entry -> {
                if (!ATTRIBUTE.matcher(entry.getKey()).matches() || HaDomains.NEVER_ATTRIBUTES.contains(entry.getKey())) {
                    problems.add("constant " + entry.getKey() + " is no attribute name MineQTT reads");
                } else if (!entry.getValue().isJsonPrimitive() || entry.getValue().toString().length() > HaDomains.MAX_TEXT) {
                    problems.add("constant " + entry.getKey() + " is a short string, number or boolean");
                } else {
                    constants.put(entry.getKey(), entry.getValue());
                }
            });
        });
        boolean payloadAttributes = bool(root, "payload_attributes", problems);
        String commandTopic = string(root, "command_topic", problems).orElse(null);
        if (commandTopic != null) topicProblem("command_topic", commandTopic).ifPresent(problems::add);
        boolean commandRetain = bool(root, "command_retain", problems);
        Map<String, BindingPreset.Range> ranges = ranges(root, problems);
        Map<String, JsonElement> commands = new LinkedHashMap<>();
        object(root, "commands", problems).ifPresent(object -> {
            if (kind != null && !HaDomains.controllable(kind)) {
                problems.add("a " + kind + " takes no commands");
                return;
            }
            Map<String, List<String>> services = kind == null ? Map.of() : HaCalls.services().getOrDefault(kind, Map.of());
            object.entrySet().forEach(entry -> {
                String service = entry.getKey();
                List<String> dataKeys = services.get(service);
                if (dataKeys == null) {
                    problems.add("command " + service + " is no service of " + kind + " (" + String.join(", ", services.keySet()) + ")");
                    return;
                }
                templateProblems(service, entry.getValue(), dataKeys).forEach(problems::add);
                commands.put(service, entry.getValue());
            });
        });
        if (commandTopic != null && commands.isEmpty()) problems.add("command_topic needs commands");
        if (commandTopic == null && !commands.isEmpty()) problems.add("commands need a command_topic");
        ranges.keySet().forEach(placeholder -> {
            if (!BindingPreset.PLACEHOLDERS.containsKey(placeholder)) problems.add("range of an unknown placeholder " + placeholder);
        });
        if (!problems.isEmpty() || kind == null || stateTopic == null) return new Parsed(Optional.empty(), problems);
        try {
            return new Parsed(Optional.of(new BindingPreset(id, name, kind, stateTopic, state, on, off, attributes, constants, payloadAttributes, commandTopic,
                    commandRetain, commands, ranges)), List.of());
        } catch (IllegalArgumentException e) {
            return new Parsed(Optional.empty(), List.of(e.getMessage()));
        }
    }

    /**
     * Why a topic template is not one: it must name {@value BindingPreset#DEVICE}, have no other brace, no wildcard and
     * no empty level, and not start with {@code $}.
     */
    static Optional<String> topicProblem(String key, String topic) {
        if (!topic.contains(BindingPreset.DEVICE)) return Optional.of(key + " must contain " + BindingPreset.DEVICE);
        String rest = topic.replace(BindingPreset.DEVICE, "x");
        if (BRACE.matcher(rest).find()) return Optional.of(key + " has a brace that is no " + BindingPreset.DEVICE);
        if (rest.contains("+") || rest.contains("#")) return Optional.of(key + " may not contain a wildcard");
        if (rest.startsWith("$")) return Optional.of(key + " may not start with $");
        if (rest.startsWith("/") || rest.endsWith("/") || rest.contains("//") || rest.indexOf('\0') >= 0) return Optional.of(key + " has an empty level");
        if (rest.getBytes(StandardCharsets.UTF_8).length > 200) return Optional.of(key + " is too long");
        return Optional.empty();
    }

    /**
     * Why a command template is not one: a JSON object, array or string of at most
     * {@value BindingPreset#MAX_TEMPLATE_BYTES} bytes, whose placeholders are all known and given by the service's data
     * keys ({@code turn_off} has no brightness), with no other brace in a string; a text template takes numbers only.
     */
    static List<String> templateProblems(String service, JsonElement template, List<String> dataKeys) {
        List<String> problems = new ArrayList<>();
        boolean text = template.isJsonPrimitive() && template.getAsJsonPrimitive().isString();
        if (!text && !template.isJsonObject() && !template.isJsonArray()) {
            problems.add("command " + service + " is a JSON object, an array or a text");
            return problems;
        }
        if (template.toString().getBytes(StandardCharsets.UTF_8).length > BindingPreset.MAX_TEMPLATE_BYTES) {
            problems.add("command " + service + " is larger than " + BindingPreset.MAX_TEMPLATE_BYTES + " bytes");
        }
        strayBraces(template).ifPresent(stray -> problems.add("command " + service + " has a brace that is no placeholder: " + stray));
        for (String placeholder : BindingPreset.placeholdersOf(template)) {
            String dataKey = BindingPreset.PLACEHOLDERS.get(placeholder);
            if (dataKey == null) problems.add("command " + service + " uses the unknown placeholder {" + placeholder + "}");
            else if (!dataKeys.contains(dataKey)) problems.add("command " + service + " uses {" + placeholder + "}, which " + service + " never has");
            else if (text && BindingPreset.TEXT_PLACEHOLDERS.contains(placeholder)) problems.add("command " + service + " is text and takes no {" + placeholder + "}");
        }
        return problems;
    }

    // A string in a template with a brace outside a placeholder.
    private static Optional<String> strayBraces(JsonElement template) {
        if (template.isJsonObject()) {
            for (Map.Entry<String, JsonElement> member : template.getAsJsonObject().entrySet()) {
                if (BRACE.matcher(member.getKey()).find()) return Optional.of(member.getKey());
                Optional<String> stray = strayBraces(member.getValue());
                if (stray.isPresent()) return stray;
            }
        } else if (template.isJsonArray()) {
            for (JsonElement element : template.getAsJsonArray()) {
                Optional<String> stray = strayBraces(element);
                if (stray.isPresent()) return stray;
            }
        } else if (template.isJsonPrimitive() && template.getAsJsonPrimitive().isString()) {
            String text = template.getAsString();
            Matcher placeholders = BindingPreset.PLACEHOLDER.matcher(text);
            if (BRACE.matcher(placeholders.replaceAll("")).find()) return Optional.of(text);
        }
        return Optional.empty();
    }

    private static Optional<String> string(JsonObject root, String key, List<String> problems) {
        JsonElement value = root.get(key);
        if (value == null) return Optional.empty();
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            problems.add(key + " is a string");
            return Optional.empty();
        }
        return Optional.of(value.getAsString());
    }

    private static boolean bool(JsonObject root, String key, List<String> problems) {
        JsonElement value = root.get(key);
        if (value == null) return false;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            problems.add(key + " is true or false");
            return false;
        }
        return value.getAsBoolean();
    }

    private static Optional<JsonObject> object(JsonObject root, String key, List<String> problems) {
        JsonElement value = root.get(key);
        if (value == null) return Optional.empty();
        if (!value.isJsonObject()) {
            problems.add(key + " is an object");
            return Optional.empty();
        }
        return Optional.of(value.getAsJsonObject());
    }

    // A spelling or a list of them: strings, numbers or booleans.
    private static List<JsonElement> spellings(JsonObject root, String key, List<String> problems) {
        JsonElement value = root.get(key);
        if (value == null) return List.of();
        JsonArray values = value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
        if (!value.isJsonArray()) values.add(value);
        List<JsonElement> spellings = new ArrayList<>();
        for (JsonElement spelling : values) {
            if (!spelling.isJsonPrimitive() || spelling.toString().length() > HaDomains.MAX_TEXT) {
                problems.add(key + " is a string, number or boolean, or a list of them");
                return List.of();
            }
            spellings.add(spelling);
        }
        if (spellings.isEmpty() || spellings.size() > MAX_SPELLINGS) problems.add(key + " has 1 to " + MAX_SPELLINGS + " spellings");
        return spellings;
    }

    private static Map<String, BindingPreset.Range> ranges(JsonObject root, List<String> problems) {
        Map<String, BindingPreset.Range> ranges = new LinkedHashMap<>();
        object(root, "ranges", problems).ifPresent(object -> object.entrySet().forEach(entry -> {
            JsonElement value = entry.getValue();
            if (value.isJsonArray() && value.getAsJsonArray().size() == 2 && number(value.getAsJsonArray().get(0)).isPresent()
                    && number(value.getAsJsonArray().get(1)).isPresent()) {
                BigDecimal min = number(value.getAsJsonArray().get(0)).get(), max = number(value.getAsJsonArray().get(1)).get();
                if (min.compareTo(max) > 0) problems.add("range of " + entry.getKey() + " has its minimum above its maximum");
                else ranges.put(entry.getKey(), new BindingPreset.Range(min, max));
            } else {
                problems.add("range of " + entry.getKey() + " is [minimum, maximum]");
            }
        }));
        return ranges;
    }

    private static Optional<BigDecimal> number(JsonElement value) {
        return value instanceof JsonPrimitive primitive && primitive.isNumber() ? Optional.of(primitive.getAsBigDecimal()) : Optional.empty();
    }
}

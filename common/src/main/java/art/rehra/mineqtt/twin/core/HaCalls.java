package art.rehra.mineqtt.twin.core;

import art.rehra.mineqtt.flow.core.Coerce;
import art.rehra.mineqtt.flow.core.PortType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one translation table from a value at an in-port of a Home Assistant entity ({@link HaDomains}) to a service
 * call (M3 plan D8, D11, D12): which service a port and value become, and the data-key whitelist of every service. The
 * control blueprint's {@code services} line must equal {@link #servicesJson()} ({@code verifyHaCalls}, D15), so
 * Minecraft never builds a call the blueprint refuses, and the blueprint never runs one Minecraft could not build.
 * <p>
 * What the table makes impossible: a call outside the entity's own domain ({@code homeassistant.*}), {@code lock.open},
 * a lock code, {@code flash}, {@code effect}, script variables, and any of {@link #BLOCKED_DATA_KEYS} in the data. Data
 * comes only from typed values (whole numbers, finite numbers, colour lists, sanitised text of at most 255 characters),
 * never from JSON a player wrote; a light command's other keys are ignored, not passed on.
 * <p>
 * Freshness-aware switching (D11) for a bool {@code state} in-port:
 * <ul>
 *     <li>an explicit on or off becomes {@code turn_on}/{@code turn_off} (or {@code open_cover}/{@code close_cover},
 *     {@code unlock}/{@code lock}), and nothing at all when a LIVE mirror already shows it (dedupe);</li>
 *     <li>a {@code TOGGLE} with a LIVE on or off becomes the explicit opposite; with only a RETAINED value, or a LIVE
 *     state that is neither (a cover {@code opening}), {@code <domain>.toggle}, without dedupe; a lock has no toggle
 *     and switches only from a LIVE locked or unlocked;</li>
 *     <li>a {@code TOGGLE} is refused when nothing is known, or the entity is {@code unavailable}, {@code unknown} or
 *     cleared.</li>
 * </ul>
 * Minecraft-free.
 */
public final class HaCalls {
    /** Keys a call's data never carries, whatever a service's whitelist says: targets belong to the request, codes nowhere. */
    public static final Set<String> BLOCKED_DATA_KEYS = Set.of("entity_id", "device_id", "area_id", "floor_id", "label_id", "code");
    /** The lowest and highest colour temperature a call sends, in kelvin. */
    public static final int MIN_KELVIN = 1000;
    public static final int MAX_KELVIN = 40000;
    /** The longest light fade a call asks for, in seconds: Home Assistant's own clamp ({@code VALID_TRANSITION}). */
    public static final int MAX_TRANSITION_SECONDS = 6553;
    private static final Pattern ACTION = Pattern.compile("([a-z0-9_]+)\\.([a-z0-9_]+)");
    private static final String TOGGLE = "toggle";
    // In the order of the control blueprint's services line: domains, services and data keys alike.
    private static final Map<String, Map<String, List<String>>> SERVICES = table(
            "light", services("turn_on", List.of("brightness", "rgb_color", "color_temp_kelvin", "transition"), "turn_off", List.of("transition"),
                    TOGGLE, List.of()),
            "switch", onOffToggle(),
            "input_boolean", onOffToggle(),
            "fan", services("turn_on", List.of("percentage"), "turn_off", List.of(), TOGGLE, List.of(), "set_percentage", List.of("percentage")),
            "cover", services("open_cover", List.of(), "close_cover", List.of(), "stop_cover", List.of(), TOGGLE, List.of(),
                    "set_cover_position", List.of("position")),
            "scene", services("turn_on", List.of("transition")),
            "script", services("turn_on", List.of()),
            "input_button", services("press", List.of()),
            "button", services("press", List.of()),
            "input_number", services("set_value", List.of("value")),
            "number", services("set_value", List.of("value")),
            "input_select", services("select_option", List.of("option")),
            "select", services("select_option", List.of("option")),
            "input_text", services("set_value", List.of("value")),
            "text", services("set_value", List.of("value")),
            "lock", services("lock", List.of(), "unlock", List.of()),
            "climate", services("set_temperature", List.of("temperature")));

    private HaCalls() {
    }

    /**
     * What MineQTT knows of the entity a call is for, as D11 decides with it.
     *
     * @param state    Home Assistant's state string ({@code on}, {@code unavailable}, …); {@code ""} when cleared or when
     *                 nothing is known
     * @param document the entity's document as the twin knows it ({@code {"state", "attributes": {…}, …}}), from which a
     *                 level is mapped back through the entity's own range or options ({@code input_number.set_value});
     *                 empty when nothing is known or the caller has none
     */
    public record Mirror(Freshness freshness, String state, JsonObject document) {
        /** Nothing known: an unloaded mirrored device, or an entity no binding reads yet. */
        public static final Mirror NONE = new Mirror(Freshness.NONE, "");

        public enum Freshness {
            NONE,
            /** Only retained or older data, which may be stale. */
            RETAINED,
            /** A value Home Assistant sent on this connection since its last birth. */
            LIVE
        }

        public Mirror {
            Objects.requireNonNull(freshness, "freshness");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(document, "document");
            if (freshness == Freshness.NONE && !state.isEmpty()) throw new IllegalArgumentException("Nothing known has no state");
            if (freshness == Freshness.NONE && !document.isEmpty()) throw new IllegalArgumentException("Nothing known has no document");
            document = document.deepCopy();
        }

        /** What is known without the entity's document. */
        public Mirror(Freshness freshness, String state) {
            this(freshness, state, new JsonObject());
        }

        /** A copy of the entity's document; empty when none is known. */
        @Override
        public JsonObject document() {
            return document.deepCopy();
        }

        public static Mirror live(String state) {
            return new Mirror(Freshness.LIVE, state);
        }

        public static Mirror retained(String state) {
            return new Mirror(Freshness.RETAINED, state);
        }

        /** Whether the state is a real value: known at all, and not {@code unavailable}, {@code unknown} or cleared. */
        public boolean known() {
            return freshness != Freshness.NONE && !state.isEmpty() && !state.equals("unavailable") && !state.equals("unknown");
        }
    }

    /** What a value at an in-port becomes. */
    public sealed interface Outcome permits Send, Skip, Refused {
        /** The call to send; empty unless this is a {@link Send}. */
        default Optional<Call> sent() {
            return this instanceof Send(Call call) ? Optional.of(call) : Optional.empty();
        }
    }

    /** Send this call. */
    public record Send(Call call) implements Outcome {
        public Send {
            Objects.requireNonNull(call, "call");
        }
    }

    /**
     * Send nothing, by design: the LIVE mirror already shows the value (dedupe), or the value asks for nothing (a
     * falling edge into a scene).
     *
     * @param already for a dedupe, the on/off state the LIVE mirror already shows ({@code true} for on); empty when the
     *                value asks for nothing. A request still waiting for the rate that would leave another state is
     *                stale then ({@code TwinRequests}).
     */
    public record Skip(String reason, Optional<Boolean> already) implements Outcome {
        public Skip {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(already, "already");
        }

        /** A value that asks for nothing. */
        public Skip(String reason) {
            this(reason, Optional.empty());
        }
    }

    /** The value cannot become a call; {@code detail} says why, for the action bar and the log. */
    public record Refused(Reason reason, String detail) implements Outcome {
        public Refused {
            Objects.requireNonNull(reason, "reason");
        }
    }

    public enum Reason {
        /** The twin does not know the domain. */
        UNKNOWN_DOMAIN,
        /** A D2 privacy domain: never read, never written. */
        PRIVATE_DOMAIN,
        /** The domain is read-only ({@code sensor}, {@code binary_sensor}, …). */
        READ_ONLY,
        /** The domain has no such in-port. */
        NO_SUCH_PORT,
        /** The value does not fit the port. */
        BAD_VALUE,
        /** A toggle needs a known state: nothing is known, or a lock's state is not LIVE. */
        NOT_LIVE,
        /** A toggle of an entity that is {@code unavailable}, {@code unknown} or cleared. */
        UNAVAILABLE,
        /** A toggle of a lock that is neither locked nor unlocked ({@code jammed}, {@code locking}, …). */
        UNCLEAR_STATE,
        /** A redstone edge cannot drive this domain. */
        NO_EDGE
    }

    /**
     * The service whitelist, {@code domain → service → data keys}, in the order of the control blueprint's
     * {@code services} line. Read-only.
     */
    public static Map<String, Map<String, List<String>>> services() {
        return SERVICES;
    }

    /** {@link #services()} as the JSON the control blueprint's {@code # mineqtt:services v1} line must equal (D15). */
    public static JsonObject servicesJson() {
        JsonObject json = new JsonObject();
        SERVICES.forEach((domain, services) -> {
            JsonObject domainJson = new JsonObject();
            services.forEach((service, keys) -> {
                JsonArray array = new JsonArray();
                keys.forEach(array::add);
                domainJson.add(service, array);
            });
            json.add(domain, domainJson);
        });
        return json;
    }

    /** The data keys {@code action} may carry; empty when the action is not in the table. */
    public static Optional<List<String>> dataKeys(String action) {
        Matcher matcher = ACTION.matcher(action);
        if (!matcher.matches()) return Optional.empty();
        Map<String, List<String>> services = SERVICES.get(matcher.group(1));
        return services == null ? Optional.empty() : Optional.ofNullable(services.get(matcher.group(2)));
    }

    /** Whether {@code action} is in the table. */
    public static boolean allowed(String action) {
        return dataKeys(action).isPresent();
    }

    /**
     * The call a value at an in-port becomes, or empty when it becomes none (skipped or refused; {@link #resolve} says
     * which and why).
     */
    public static Optional<Call> call(String domain, String inPort, JsonElement value, Mirror mirror) {
        return resolve(domain, inPort, value, mirror).sent();
    }

    /**
     * What a value at the in-port {@code inPort} of an entity of {@code domain} becomes, given what is known of the
     * entity ({@code mirror}).
     */
    public static Outcome resolve(String domain, String inPort, JsonElement value, Mirror mirror) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(mirror, "mirror");
        Optional<Refused> refused = domainRefusal(domain);
        if (refused.isPresent()) return refused.get();
        HaDomains.Domain spec = HaDomains.domain(domain).orElseThrow();
        Optional<HaDomains.Port> port = spec.inPort(inPort);
        if (port.isEmpty()) return new Refused(Reason.NO_SUCH_PORT, domain + " has no " + inPort + " input");
        PortType kind = port.get().kind();
        return switch (kind) {
            case PortType.Bool ignoredBool -> switchState(spec, value, mirror, new JsonObject());
            case PortType.Light ignoredLight -> light(spec, value, mirror);
            case PortType.Event event -> event(spec, event, value);
            case PortType.Number number -> number(spec, inPort, number, value);
            case PortType.Text text -> text(spec, inPort, text, value);
            default -> throw new IllegalStateException("No call for " + domain + "." + inPort + " (" + kind.describe() + ")");
        };
    }

    /**
     * What a redstone edge at a target of {@code domain} becomes (D5): a rising edge switches on ({@code turn_on},
     * {@code open_cover}), activates ({@code scene.turn_on}, {@code script.turn_on}) or presses; a falling edge switches
     * off ({@code turn_off}, {@code close_cover}) and does nothing for a scene, a script or a button. Locks, numbers,
     * selects, texts and climates are no edge targets.
     */
    public static Outcome edge(String domain, boolean rising, Mirror mirror) {
        return switch (domain) {
            case "light", "switch", "input_boolean", "fan", "cover" -> resolve(domain, HaDomains.STATE_LEAF, new JsonPrimitive(rising), mirror);
            case "scene", "script" -> rising ? resolve(domain, "activate", new JsonPrimitive(Coerce.PRESS), mirror)
                    : new Skip("a falling edge does not activate a " + domain);
            case "button", "input_button" -> rising ? resolve(domain, "press", new JsonPrimitive(Coerce.PRESS), mirror)
                    : new Skip("a falling edge does not press a button");
            default -> domainRefusal(domain).<Outcome>map(refusal -> refusal)
                    .orElseGet(() -> new Refused(Reason.NO_EDGE, "a redstone edge cannot drive " + domain + " entities"));
        };
    }

    /**
     * Why {@code data} may not go with {@code action}, or empty when it may: the action is not in the table, a key is
     * not whitelisted for it, or a value is not of the key's type and range.
     */
    static Optional<String> problem(String action, JsonObject data) {
        Optional<List<String>> keys = dataKeys(action);
        if (keys.isEmpty()) return Optional.of("Minecraft may not call " + action);
        String domain = action.substring(0, action.indexOf('.'));
        for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
            String key = entry.getKey();
            if (BLOCKED_DATA_KEYS.contains(key) || !keys.get().contains(key)) return Optional.of(action + " may not carry " + key);
            Optional<String> problem = valueProblem(domain, key, entry.getValue());
            if (problem.isPresent()) return Optional.of(action + " " + key + ": " + problem.get());
        }
        // Home Assistant refuses both colour descriptors in one call.
        if (data.has("rgb_color") && data.has("color_temp_kelvin")) return Optional.of(action + " may not carry a colour and a colour temperature");
        return Optional.empty();
    }

    // Why no entity of `domain` can be called at all: unknown, private or read-only.
    private static Optional<Refused> domainRefusal(String domain) {
        Optional<HaDomains.Domain> declared = HaDomains.domain(domain);
        if (declared.isEmpty()) return Optional.of(new Refused(Reason.UNKNOWN_DOMAIN, "Home Assistant's " + domain + " domain is not supported"));
        if (!declared.get().readable()) return Optional.of(new Refused(Reason.PRIVATE_DOMAIN, domain + " entities are never shared with Minecraft"));
        if (!declared.get().controllable()) return Optional.of(new Refused(Reason.READ_ONLY, domain + " entities cannot be controlled"));
        return Optional.empty();
    }

    // A bool state in-port, and the switch half of a light command; `data` goes with whichever service takes its keys.
    private static Outcome switchState(HaDomains.Domain domain, JsonElement value, Mirror mirror, JsonObject data) {
        HaDomains.BoolStates states = domain.bool().orElseThrow();
        Optional<Boolean> mirrored = mirror.known() ? states.read(mirror.state()) : Optional.empty();
        boolean desired;
        if (isToggle(value)) {
            if (mirror.freshness() == Mirror.Freshness.NONE) {
                return new Refused(Reason.NOT_LIVE, "nothing is known about it yet, so it cannot be toggled");
            }
            if (!mirror.known()) return new Refused(Reason.UNAVAILABLE, "it is " + describe(mirror.state()) + " in Home Assistant");
            if (mirror.freshness() == Mirror.Freshness.LIVE && mirrored.isPresent()) {
                desired = !mirrored.get();
            } else if (SERVICES.get(domain.id()).containsKey(TOGGLE)) {
                return new Send(new Call(domain.id() + "." + TOGGLE));
            } else if (mirror.freshness() == Mirror.Freshness.LIVE) {
                return new Refused(Reason.UNCLEAR_STATE, "it is " + mirror.state() + ", neither " + states.on() + " nor " + states.off());
            } else {
                return new Refused(Reason.NOT_LIVE, "its state may be stale, and a " + domain.id() + " switches only from a live state");
            }
        } else {
            Optional<Boolean> parsed = bool(value, states);
            if (parsed.isEmpty()) return new Refused(Reason.BAD_VALUE, "not on, off or TOGGLE: " + value);
            desired = parsed.get();
            if (mirror.freshness() == Mirror.Freshness.LIVE && mirrored.equals(parsed) && (!desired || data.isEmpty())) {
                return new Skip("it already is " + (desired ? states.on() : states.off()), Optional.of(desired));
            }
        }
        String action = domain.id() + "." + switchService(domain.id(), desired);
        List<String> keys = dataKeys(action).orElseThrow();
        JsonObject callData = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
            if (keys.contains(entry.getKey())) callData.add(entry.getKey(), entry.getValue());
        }
        return new Send(new Call(action, callData));
    }

    // A JSON-schema light command, as the Smart Lamp's light port carries it: state, brightness, color {r,g,b},
    // color_temp_kelvin with color_mode, transition. Its other keys (effect, flash, …) are ignored.
    private static Outcome light(HaDomains.Domain domain, JsonElement value, Mirror mirror) {
        if (!value.isJsonObject()) return new Refused(Reason.BAD_VALUE, "not a light object: " + value);
        JsonObject command = value.getAsJsonObject();
        JsonObject on = new JsonObject();
        JsonObject off = new JsonObject();
        JsonElement brightness = command.get("brightness");
        if (brightness != null) {
            Optional<BigDecimal> number = Coerce.decimal(brightness);
            if (number.isEmpty()) return new Refused(Reason.BAD_VALUE, "brightness is not a number: " + brightness);
            on.addProperty("brightness", clampWhole(number.get(), 0, 255));
        }
        JsonElement color = command.get("color");
        OptionalInt rgb = color == null ? OptionalInt.empty() : Coerce.rgb(color);
        if (color != null && rgb.isEmpty()) return new Refused(Reason.BAD_VALUE, "color is not {r,g,b} with 0-255: " + color);
        JsonElement kelvinValue = command.get("color_temp_kelvin");
        Optional<BigDecimal> kelvin = kelvinValue == null ? Optional.empty() : Coerce.decimal(kelvinValue);
        if (kelvinValue != null && kelvin.isEmpty()) return new Refused(Reason.BAD_VALUE, "color_temp_kelvin is not a number: " + kelvinValue);
        JsonElement mode = command.get("color_mode");
        boolean colorTemp = mode != null && mode.isJsonPrimitive() && mode.getAsString().equals("color_temp");
        // Home Assistant refuses a colour and a colour temperature in one call; the light's colour mode picks one.
        if (kelvin.isPresent() && (colorTemp || rgb.isEmpty())) {
            on.addProperty("color_temp_kelvin", clampWhole(kelvin.get(), MIN_KELVIN, MAX_KELVIN));
        } else if (rgb.isPresent()) {
            JsonArray list = new JsonArray();
            list.add(rgb.getAsInt() >> 16 & 0xFF);
            list.add(rgb.getAsInt() >> 8 & 0xFF);
            list.add(rgb.getAsInt() & 0xFF);
            on.add("rgb_color", list);
        }
        JsonElement transition = command.get("transition");
        if (transition != null) {
            Optional<BigDecimal> seconds = Coerce.decimal(transition);
            if (seconds.isEmpty()) return new Refused(Reason.BAD_VALUE, "transition is not a number: " + transition);
            JsonPrimitive fade = number(seconds.get().max(BigDecimal.ZERO).min(BigDecimal.valueOf(MAX_TRANSITION_SECONDS)));
            on.add("transition", fade);
            off.add("transition", fade);
        }
        JsonElement state = command.get("state");
        // A light command without a state switches the light on, as Home Assistant's JSON schema reads it.
        if (state == null) return switchState(domain, new JsonPrimitive(true), mirror, on);
        if (isToggle(state)) return switchState(domain, state, mirror, on);
        Optional<Boolean> desired = bool(state, domain.bool().orElseThrow());
        if (desired.isEmpty()) return new Refused(Reason.BAD_VALUE, "state is not ON, OFF or TOGGLE: " + state);
        return switchState(domain, new JsonPrimitive(desired.get()), mirror, desired.get() ? on : off);
    }

    // Scenes and scripts activate, buttons press: PRESS (what a wire gives an action), an event object or its type.
    private static Outcome event(HaDomains.Domain domain, PortType.Event kind, JsonElement value) {
        boolean press = value.isJsonPrimitive() && value.getAsString().equalsIgnoreCase(Coerce.PRESS);
        if (!press && kind.problem(value).isPresent()) {
            return new Refused(Reason.BAD_VALUE, "not PRESS or a " + String.join("/", kind.types()) + " event: " + value);
        }
        String service = kind.types().contains("activate") ? "turn_on" : "press";
        return new Send(new Call(domain.id() + "." + service));
    }

    private static Outcome number(HaDomains.Domain domain, String inPort, PortType.Number kind, JsonElement value) {
        Optional<String> problem = kind.problem(value);
        if (problem.isPresent()) return new Refused(Reason.BAD_VALUE, problem.get());
        BigDecimal number = Coerce.decimal(value).orElseThrow();
        String service = switch (inPort) {
            case "percentage" -> "set_percentage";
            case "position" -> "set_cover_position";
            case "temperature" -> "set_temperature";
            case HaDomains.VALUE_PORT -> "set_value";
            default -> throw new IllegalStateException("No service for " + domain.id() + "." + inPort);
        };
        JsonObject data = new JsonObject();
        data.add(inPort, number(number));
        return new Send(new Call(domain.id() + "." + service, data));
    }

    private static Outcome text(HaDomains.Domain domain, String inPort, PortType.Text kind, JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return new Refused(Reason.BAD_VALUE, "not text: " + value);
        String service = switch (inPort) {
            case "option" -> "select_option";
            case HaDomains.VALUE_PORT -> "set_value";
            default -> throw new IllegalStateException("No service for " + domain.id() + "." + inPort);
        };
        JsonObject data = new JsonObject();
        data.addProperty(inPort, Coerce.sanitize(value.getAsString(), kind.maxLength()));
        return new Send(new Call(domain.id() + "." + service, data));
    }

    private static String switchService(String domain, boolean on) {
        return switch (domain) {
            case "cover" -> on ? "open_cover" : "close_cover";
            case "lock" -> on ? "unlock" : "lock";
            default -> on ? "turn_on" : "turn_off";
        };
    }

    // On or off: a JSON boolean, 0 or 1, the Coerce spellings (on, ON, true, …) or the domain's own two states.
    private static Optional<Boolean> bool(JsonElement value, HaDomains.BoolStates states) {
        Optional<Boolean> bool = Coerce.bool(value);
        if (bool.isPresent() || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return bool;
        return states.read(value.getAsString().trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isToggle(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && value.getAsString().equalsIgnoreCase(Coerce.TOGGLE);
    }

    private static String describe(String state) {
        return state.isEmpty() ? "cleared" : state;
    }

    // Rounded to a whole number and clamped (before rounding, so a huge exponent is never expanded).
    private static long clampWhole(BigDecimal value, long min, long max) {
        return value.max(BigDecimal.valueOf(min)).min(BigDecimal.valueOf(max)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    // A whole number as a long when it fits, anything else as a double, so the JSON never carries BigDecimal's exponent form.
    private static JsonPrimitive number(BigDecimal value) {
        boolean whole = value.signum() == 0 || value.stripTrailingZeros().scale() <= 0;
        if (whole && value.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0) return new JsonPrimitive(value.longValueExact());
        return new JsonPrimitive(value.doubleValue());
    }

    // The type and range of a data value: whole numbers, finite numbers, an [r,g,b] list or sanitised text.
    private static Optional<String> valueProblem(String domain, String key, JsonElement value) {
        return switch (key) {
            case "brightness" -> whole(value, 0, 255);
            case "color_temp_kelvin" -> whole(value, MIN_KELVIN, MAX_KELVIN);
            case "percentage", "position" -> whole(value, 0, 100);
            case "transition" -> finiteIn(value, 0, MAX_TRANSITION_SECONDS);
            case "temperature" -> finiteIn(value, -Double.MAX_VALUE, Double.MAX_VALUE);
            case "rgb_color" -> rgbList(value);
            case "option" -> safeText(value);
            case "value" -> domain.equals("input_text") || domain.equals("text") ? safeText(value) : finiteIn(value, -Double.MAX_VALUE, Double.MAX_VALUE);
            default -> Optional.of("no type is declared for it");
        };
    }

    private static Optional<String> whole(JsonElement value, long min, long max) {
        Optional<String> problem = finiteIn(value, min, max);
        if (problem.isPresent()) return problem;
        return Coerce.decimal(value).orElseThrow().stripTrailingZeros().scale() > 0 ? Optional.of("not a whole number: " + value) : Optional.empty();
    }

    private static Optional<String> finiteIn(JsonElement value, double min, double max) {
        Optional<BigDecimal> number = Coerce.decimal(value);
        if (number.isEmpty()) return Optional.of("not a finite number: " + value);
        if (number.get().compareTo(BigDecimal.valueOf(min)) < 0 || number.get().compareTo(BigDecimal.valueOf(max)) > 0) {
            return Optional.of(value + " is out of range");
        }
        return Optional.empty();
    }

    private static Optional<String> rgbList(JsonElement value) {
        if (!value.isJsonArray() || value.getAsJsonArray().size() != 3) return Optional.of("not an [r, g, b] list: " + value);
        for (JsonElement channel : value.getAsJsonArray()) {
            Optional<String> problem = whole(channel, 0, 255);
            if (problem.isPresent()) return problem;
        }
        return Optional.empty();
    }

    private static Optional<String> safeText(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return Optional.of("not text: " + value);
        String text = value.getAsString();
        return Coerce.sanitize(text, HaDomains.MAX_TEXT).equals(text) ? Optional.empty()
                : Optional.of("text with control characters, formatting codes or over " + HaDomains.MAX_TEXT + " characters");
    }

    private static Map<String, List<String>> onOffToggle() {
        return services("turn_on", List.of(), "turn_off", List.of(), TOGGLE, List.of());
    }

    // One domain's services, in order: service, data keys, service, data keys, …; no service may whitelist a blocked key.
    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> services(Object... pairs) {
        Map<String, List<String>> services = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            List<String> keys = List.copyOf((List<String>) pairs[i + 1]);
            for (String key : keys) {
                if (BLOCKED_DATA_KEYS.contains(key)) throw new IllegalStateException(pairs[i] + " whitelists " + key);
            }
            if (services.put((String) pairs[i], keys) != null) throw new IllegalStateException("Service declared twice: " + pairs[i]);
        }
        return Collections.unmodifiableMap(services);
    }

    // The table, in order: domain, services, domain, services, …
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, List<String>>> table(Object... pairs) {
        Map<String, Map<String, List<String>>> table = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (table.put((String) pairs[i], (Map<String, List<String>>) pairs[i + 1]) != null) {
                throw new IllegalStateException("Domain declared twice: " + pairs[i]);
            }
        }
        return Collections.unmodifiableMap(table);
    }
}

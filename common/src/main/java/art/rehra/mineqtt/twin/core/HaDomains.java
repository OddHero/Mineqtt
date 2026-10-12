package art.rehra.mineqtt.twin.core;

import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.flow.core.PortSpec;
import art.rehra.mineqtt.flow.core.PortType;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Home Assistant entities as typed flow nodes (M3 plan D8): per domain, the out-ports a binding may read and the in-ports
 * a binding may write, each a {@link PortSpec} with a {@code flow.core} {@link PortType}; the attributes MineQTT may
 * read at all; and whether the domain is readable or controllable. Declared data, Minecraft-free.
 * <p>
 * What is not declared here does not exist for the twin: an entity of an unknown domain can be neither read nor
 * written. The privacy domains of D2 ({@link #PRIVATE_DOMAINS}: GPS, occupancy, cameras, alarm panels) are declared
 * explicitly as never readable and never controllable, so they cannot be bound even when an admin includes them in
 * {@code mqtt_statestream}.
 * <p>
 * An out-port names the statestream leaves it reads ({@link Port#leaves}): {@code state} or attributes, never {@code +}
 * (D1 #8). Besides the typed ports, any readable entity has the generic read ports of §3.2: {@link #VALUE_PORT}, the raw
 * state as text, and {@code attr_<key>} for an attribute on the domain's whitelist ({@link #readPort}).
 * <p>
 * The service calls behind the in-ports are {@link HaCalls}. {@link #DEFAULT_CONTROL_DOMAINS} is the control
 * blueprint's {@code allowed_domains} default and the {@code twin.haActionAllowlist} default (D12, D15).
 */
public final class HaDomains {
    /** The statestream leaf of an entity's state. */
    public static final String STATE_LEAF = "state";
    /** The generic read port of any readable entity: its state as text (§3.2). */
    public static final String VALUE_PORT = "value";
    /** A sensor's state as a number, beside {@link #VALUE_PORT}, which reads it as text whatever it is (D8). */
    public static final String NUMBER_PORT = "number";
    /**
     * The statestream leaves of an entity's timestamps (D1 #8, D7): readable for every readable domain, since they carry
     * no attribute, only when the state last changed and when HA last wrote the entity.
     */
    public static final List<String> TIMESTAMP_LEAVES = List.of(StatestreamTopic.LAST_CHANGED, StatestreamTopic.LAST_UPDATED);
    /** The generic read port of an attribute is this prefix plus the attribute's key (§3.2). */
    public static final String ATTRIBUTE_PORT_PREFIX = "attr_";
    /** The longest text a port carries or a call sends: Home Assistant's {@code text} and {@code input_text} maximum. */
    public static final int MAX_TEXT = 255;
    /** Never readable, never controllable, even when included in {@code mqtt_statestream} (D2). */
    public static final List<String> PRIVATE_DOMAINS = List.of("person", "device_tracker", "zone", "camera", "alarm_control_panel");
    /**
     * Attributes no domain may whitelist: positions, pictures with access tokens, codes and who used them. A port or the
     * catalog never carries them (D3, D13 #7).
     */
    public static final Set<String> NEVER_ATTRIBUTES = Set.of("latitude", "longitude", "gps_accuracy", "altitude", "entity_picture",
            "access_token", "token", "code", "code_format", "changed_by", "user_id", "ip_address");
    /** Whitelisted for every readable domain. */
    public static final List<String> COMMON_ATTRIBUTES = List.of("friendly_name", "device_class");
    /**
     * The domains Minecraft may control by default: the control blueprint's {@code allowed_domains} default and the
     * {@code twin.haActionAllowlist} default. {@code lock} and {@code climate} are opt-in (D12).
     */
    public static final List<String> DEFAULT_CONTROL_DOMAINS = List.of("light", "switch", "input_boolean", "fan", "cover", "scene",
            "script", "input_button", "button", "input_number", "number", "input_select", "select", "input_text", "text");

    // Home Assistant numbers have no declared range; an entity's own range comes from its min/max attributes.
    private static final PortType.Number ANY_NUMBER = PortType.number(-Double.MAX_VALUE, Double.MAX_VALUE, false, null);
    private static final PortType.Number ANY_INTEGER = PortType.number(Long.MIN_VALUE, Long.MAX_VALUE, true, null);
    private static final PortType.Number BRIGHTNESS = PortType.integer(0, 255);
    private static final PortType.Number PERCENT = PortType.integer(0, 100);
    private static final PortType.Text TEXT = PortType.text(MAX_TEXT);
    private static final BoolStates ON_OFF = new BoolStates("on", "off");
    private static final Map<String, Domain> DOMAINS = declare();

    private HaDomains() {
    }

    public enum Direction {
        /** Read from Home Assistant: a source of the twin. */
        OUT,
        /** Written to Home Assistant through a service call: a sink of the twin. */
        IN
    }

    /**
     * A port of a Home Assistant entity.
     *
     * @param key     the port key ({@link Endpoint#isPortKey})
     * @param toggles whether an event wired to this in-port toggles it (a switch state)
     * @param leaves  for an out-port, the statestream leaves it reads, the first being its value; empty for an in-port
     */
    public record Port(String key, Direction direction, PortType kind, boolean toggles, List<String> leaves) implements PortSpec {
        public Port {
            if (!Endpoint.isPortKey(key)) throw new IllegalArgumentException("Invalid port key: " + key);
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(kind, "kind");
            leaves = List.copyOf(leaves);
            if (direction == Direction.OUT && leaves.isEmpty()) throw new IllegalArgumentException("Out-port " + key + " reads no leaf");
            if (direction == Direction.IN && !leaves.isEmpty()) throw new IllegalArgumentException("In-port " + key + " reads leaves");
            if (toggles && (direction != Direction.IN || !(kind instanceof PortType.Bool))) {
                throw new IllegalArgumentException("Only a bool in-port toggles: " + key);
            }
        }

        static Port out(String key, PortType kind, String... leaves) {
            return new Port(key, Direction.OUT, kind, false, List.of(leaves));
        }

        static Port in(String key, PortType kind) {
            return new Port(key, Direction.IN, kind, false, List.of());
        }

        static Port switchState() {
            return new Port(STATE_LEAF, Direction.IN, PortType.BOOL, true, List.of());
        }

        /** {@code state (bool)}, for command output. */
        public String describe() {
            return key + " (" + kind.describe() + ")";
        }
    }

    /**
     * The two states of a domain whose {@code state} port is a bool, as Home Assistant writes them: {@code on}/{@code off},
     * {@code open}/{@code closed}, {@code unlocked}/{@code locked}. Any other state (e.g. {@code opening},
     * {@code jammed}) is neither.
     */
    public record BoolStates(String on, String off) {
        /** True for {@link #on}, false for {@link #off}, empty for any other state. */
        public Optional<Boolean> read(String state) {
            if (on.equals(state)) return Optional.of(true);
            if (off.equals(state)) return Optional.of(false);
            return Optional.empty();
        }
    }

    /**
     * One Home Assistant domain as the twin sees it.
     *
     * @param attributes the attributes MineQTT may read, the {@link #COMMON_ATTRIBUTES} included; empty when unreadable
     * @param readable   whether an entity of this domain may be read (mirrored, shown, listed) at all
     * @param bool       the states of the {@code state} port when it is a bool; empty otherwise
     */
    public record Domain(String id, List<Port> outPorts, List<Port> inPorts, List<String> attributes, boolean readable,
                         Optional<BoolStates> bool) {
        public Domain {
            outPorts = List.copyOf(outPorts);
            inPorts = List.copyOf(inPorts);
            attributes = List.copyOf(attributes);
            Objects.requireNonNull(bool, "bool");
            checkPorts(id, outPorts, Direction.OUT);
            checkPorts(id, inPorts, Direction.IN);
            for (String attribute : attributes) {
                if (NEVER_ATTRIBUTES.contains(attribute)) throw new IllegalArgumentException(id + " whitelists " + attribute);
            }
            boolean boolState = outPorts.stream().anyMatch(port -> port.key().equals(STATE_LEAF) && port.kind() instanceof PortType.Bool);
            if (bool.isPresent() != boolState) throw new IllegalArgumentException(id + " needs its two states exactly when its state is a bool");
            if (!readable && !(outPorts.isEmpty() && inPorts.isEmpty() && attributes.isEmpty())) {
                throw new IllegalArgumentException("An unreadable domain has no ports or attributes: " + id);
            }
            for (Port port : outPorts) {
                for (String leaf : port.leaves()) {
                    if (!leaf.equals(STATE_LEAF) && !attributes.contains(leaf)) {
                        throw new IllegalArgumentException(id + "." + port.key() + " reads " + leaf + ", which is not whitelisted");
                    }
                }
            }
        }

        /** Whether an entity of this domain may be written through a service call at all. */
        public boolean controllable() {
            return !inPorts.isEmpty();
        }

        public Optional<Port> outPort(String key) {
            return outPorts.stream().filter(port -> port.key().equals(key)).findFirst();
        }

        public Optional<Port> inPort(String key) {
            return inPorts.stream().filter(port -> port.key().equals(key)).findFirst();
        }

        /**
         * Whether a binding may subscribe to the statestream leaf {@code leaf}: the state, its {@link #TIMESTAMP_LEAVES}, or
         * a whitelisted attribute.
         */
        public boolean readableLeaf(String leaf) {
            return readable && (leaf.equals(STATE_LEAF) || TIMESTAMP_LEAVES.contains(leaf) || attributes.contains(leaf));
        }

        /**
         * The out-port a binding reads by {@code key}: a typed port of the table, else the generic {@link #VALUE_PORT}
         * (the state as text) or {@code attr_<key>} (a whitelisted attribute as JSON). Empty for an unreadable domain
         * and for any other key.
         */
        public Optional<Port> readPort(String key) {
            if (!readable) return Optional.empty();
            Optional<Port> typed = outPort(key);
            if (typed.isPresent()) return typed;
            if (key.equals(VALUE_PORT)) return Optional.of(Port.out(VALUE_PORT, TEXT, STATE_LEAF));
            if (key.startsWith(ATTRIBUTE_PORT_PREFIX) && Endpoint.isPortKey(key)) {
                String attribute = key.substring(ATTRIBUTE_PORT_PREFIX.length());
                if (attributes.contains(attribute)) return Optional.of(Port.out(key, PortType.JSON, attribute));
            }
            return Optional.empty();
        }

        private static void checkPorts(String id, List<Port> ports, Direction direction) {
            Set<String> keys = new LinkedHashSet<>();
            for (Port port : ports) {
                if (port.direction() != direction) throw new IllegalArgumentException(id + "." + port.key() + " is not an " + direction + "-port");
                if (!keys.add(port.key())) throw new IllegalArgumentException("Duplicate port " + id + "." + port.key());
            }
        }
    }

    /** The domain {@code id}; empty when the twin does not know it. */
    public static Optional<Domain> domain(String id) {
        return Optional.ofNullable(DOMAINS.get(id));
    }

    /** Every declared domain, readable or not, in the order of the D8 table. */
    public static Collection<Domain> all() {
        return DOMAINS.values();
    }

    /**
     * The domains whose entities change only when they are triggered: a scene's state is the time it
     * was last activated, a script's runs, a button's last press. Home Assistant gives such an entity a new
     * {@code last_updated} at every start without statestream publishing it, so missing live values say nothing about
     * the {@code mqtt_statestream} include, and no drift is inferred for them ({@link #infersDrift}).
     */
    public static final List<String> TRIGGER_DOMAINS = List.of("scene", "script", "button", "input_button");

    /**
     * Whether the D3 drift warning may be inferred for an entity of {@code domain} from catalog moves without live
     * statestream values: every domain but the {@link #TRIGGER_DOMAINS}.
     */
    public static boolean infersDrift(String domain) {
        return !TRIGGER_DOMAINS.contains(domain);
    }

    /** Whether an entity of {@code domain} may be read: declared, and not one of the {@link #PRIVATE_DOMAINS}. */
    public static boolean readable(String domain) {
        Domain declared = DOMAINS.get(domain);
        return declared != null && declared.readable();
    }

    /** Whether an entity of {@code domain} may be written through a service call. */
    public static boolean controllable(String domain) {
        Domain declared = DOMAINS.get(domain);
        return declared != null && declared.controllable();
    }

    /** The readable domains, in table order: the catalog blueprint publishes one list per domain (D3). */
    public static List<String> readableDomains() {
        return DOMAINS.values().stream().filter(Domain::readable).map(Domain::id).toList();
    }

    /**
     * The controllable domains, in table order: {@link #DEFAULT_CONTROL_DOMAINS} plus the opt-in ones, the options of the
     * control blueprint's {@code allowed_domains} selector.
     */
    public static List<String> controllableDomains() {
        return DOMAINS.values().stream().filter(Domain::controllable).map(Domain::id).toList();
    }

    /**
     * The attribute whitelist of every readable domain as JSON, {@code {"light": ["friendly_name", …], …}}: the catalog
     * blueprint's {@code # mineqtt:attributes v1} line must equal it (D15).
     */
    public static JsonObject attributesJson() {
        JsonObject json = new JsonObject();
        for (Domain domain : DOMAINS.values()) {
            if (!domain.readable()) continue;
            JsonArray attributes = new JsonArray();
            domain.attributes().forEach(attributes::add);
            json.add(domain.id(), attributes);
        }
        return json;
    }

    private static Map<String, Domain> declare() {
        Map<String, Domain> domains = new LinkedHashMap<>();
        new Builder("light").bool(ON_OFF)
                .attributes("brightness", "rgb_color", "color_temp_kelvin", "color_mode", "supported_color_modes",
                        "min_color_temp_kelvin", "max_color_temp_kelvin")
                .out(Port.out(STATE_LEAF, PortType.BOOL, STATE_LEAF))
                .out(Port.out("brightness", BRIGHTNESS, "brightness"))
                .out(Port.out("color", PortType.COLOR, "rgb_color"))
                .out(Port.out("light", PortType.LIGHT, STATE_LEAF, "brightness", "rgb_color", "color_temp_kelvin", "color_mode"))
                .in(Port.switchState())
                .in(Port.in("light", PortType.LIGHT))
                .into(domains);
        for (String id : List.of("switch", "input_boolean")) {
            new Builder(id).bool(ON_OFF).out(Port.out(STATE_LEAF, PortType.BOOL, STATE_LEAF)).in(Port.switchState()).into(domains);
        }
        new Builder("fan").bool(ON_OFF).attributes("percentage", "percentage_step", "preset_mode")
                .out(Port.out(STATE_LEAF, PortType.BOOL, STATE_LEAF))
                .out(Port.out("percentage", PERCENT, "percentage"))
                .in(Port.switchState())
                .in(Port.in("percentage", PERCENT))
                .into(domains);
        new Builder("cover").bool(new BoolStates("open", "closed")).attributes("current_position", "current_tilt_position")
                .out(Port.out(STATE_LEAF, PortType.BOOL, STATE_LEAF))
                .out(Port.out("position", PERCENT, "current_position"))
                .in(Port.switchState())
                .in(Port.in("position", PERCENT))
                .into(domains);
        new Builder("binary_sensor").bool(ON_OFF).out(Port.out(STATE_LEAF, PortType.BOOL, STATE_LEAF)).into(domains);
        // A sensor's state is a number or text (a weather condition, an enum, a timestamp): value reads it as text, any
        // state; number reads it as a number, which a wire to a level or number port needs (text never coerces to one).
        new Builder("sensor").attributes("unit_of_measurement", "state_class")
                .out(Port.out(VALUE_PORT, TEXT, STATE_LEAF, "unit_of_measurement"))
                .out(Port.out(NUMBER_PORT, ANY_NUMBER, STATE_LEAF, "unit_of_measurement"))
                .into(domains);
        new Builder("counter").attributes("minimum", "maximum", "step", "initial")
                .out(Port.out(VALUE_PORT, ANY_INTEGER, STATE_LEAF))
                .into(domains);
        for (String id : List.of("input_number", "number")) {
            new Builder(id).attributes("min", "max", "step", "mode", "unit_of_measurement")
                    .out(Port.out(VALUE_PORT, ANY_NUMBER, STATE_LEAF, "min", "max"))
                    .in(Port.in(VALUE_PORT, ANY_NUMBER))
                    .into(domains);
        }
        // The options are the entity's own (its options attribute), so the port is text and the call checks nothing more.
        for (String id : List.of("input_select", "select")) {
            new Builder(id).attributes("options")
                    .out(Port.out("option", TEXT, STATE_LEAF, "options"))
                    .in(Port.in("option", TEXT))
                    .into(domains);
        }
        for (String id : List.of("input_text", "text")) {
            new Builder(id).attributes("min", "max", "mode", "pattern")
                    .out(Port.out(VALUE_PORT, TEXT, STATE_LEAF))
                    .in(Port.in(VALUE_PORT, TEXT))
                    .into(domains);
        }
        new Builder("scene").in(Port.in("activate", PortType.event("activate"))).into(domains);
        new Builder("script").attributes("last_triggered").in(Port.in("activate", PortType.event("activate"))).into(domains);
        for (String id : List.of("button", "input_button")) {
            new Builder(id).in(Port.in("press", PortType.event("press"))).into(domains);
        }
        // Lock and unlock only: never open, never a code (D8, D12). On is unlocked, as D7's spellings and Home Assistant's
        // lock binary sensors read it, so a value read through ValueMap means the same written back through HaCalls.
        new Builder("lock").bool(new BoolStates("unlocked", "locked"))
                .out(Port.out(STATE_LEAF, PortType.BOOL, STATE_LEAF))
                .in(Port.switchState())
                .into(domains);
        new Builder("climate").attributes("current_temperature", "temperature", "target_temp_low", "target_temp_high", "min_temp",
                        "max_temp", "target_temp_step", "current_humidity", "hvac_action")
                .out(Port.out("current_temperature", ANY_NUMBER, "current_temperature"))
                .out(Port.out("temperature", ANY_NUMBER, "temperature"))
                .in(Port.in("temperature", ANY_NUMBER))
                .into(domains);
        for (String id : PRIVATE_DOMAINS) {
            domains.put(id, new Domain(id, List.of(), List.of(), List.of(), false, Optional.empty()));
        }
        return Collections.unmodifiableMap(domains);
    }

    // Collects one readable domain; the common attributes come first.
    private static final class Builder {
        private final String id;
        private final List<String> attributes = new ArrayList<>(COMMON_ATTRIBUTES);
        private final List<Port> outPorts = new ArrayList<>();
        private final List<Port> inPorts = new ArrayList<>();
        private BoolStates bool;

        Builder(String id) {
            this.id = id;
        }

        Builder bool(BoolStates states) {
            bool = states;
            return this;
        }

        Builder attributes(String... keys) {
            attributes.addAll(List.of(keys));
            return this;
        }

        Builder out(Port port) {
            outPorts.add(port);
            return this;
        }

        Builder in(Port port) {
            inPorts.add(port);
            return this;
        }

        void into(Map<String, Domain> domains) {
            if (domains.put(id, new Domain(id, outPorts, inPorts, attributes, true, Optional.ofNullable(bool))) != null) {
                throw new IllegalStateException("Domain declared twice: " + id);
            }
        }
    }
}

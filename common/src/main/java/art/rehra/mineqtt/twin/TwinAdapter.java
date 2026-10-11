package art.rehra.mineqtt.twin;

import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.twin.core.Binding;
import art.rehra.mineqtt.twin.core.Call;
import art.rehra.mineqtt.twin.core.EntityDocument;
import art.rehra.mineqtt.twin.core.HaCalls;
import art.rehra.mineqtt.twin.core.HaDomains;
import art.rehra.mineqtt.twin.core.PortRead;
import art.rehra.mineqtt.twin.core.StatestreamTopic;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * How a device type takes part in the twin (M3 plan §3.1, D5): declared once on the type
 * ({@code DeviceType.Builder#twin}), which checks it against the type's ports when the type is built.
 * <ul>
 *     <li>The <b>primary port</b> is the writable in-port a source fills (a lamp's {@code light}); the
 *     {@link #mirroredKeys() mirrored keys} are the command keys the source owns, so a command touching one is routed as
 *     a command to the mirror (forwarded or refused), any other (a setting) applies as always.</li>
 *     <li>The <b>source domains</b> are the Home Assistant domains a source may be, in the picker's preference order, and
 *     {@link Reads} gives the {@link PortRead}s of each: which in-port each value fills.</li>
 *     <li>{@link Forward} turns a forwarded command into service calls; an adapter without one is read-only.</li>
 *     <li>The <b>targets</b> are the out-ports (fields or events) that may switch an entity, each under the setting name
 *     players use ({@code twin.<name>}), and the controllable domains a target may be.</li>
 * </ul>
 * {@link #read} and {@link #forward} are pure: reading never yields a call, so incoming state cannot cause a request
 * (D10). Minecraft-free.
 */
public final class TwinAdapter {
    /** The key of the one entity a bound device keeps in Home Assistant (D6); a twin type may not declare an entity of this key. */
    public static final String BINDING_ENTITY = "binding";
    /**
     * The command key under which a twin apply carries the adapter's {@link Extras}; a twin type declares no field, action
     * or event of this key, and a device honours it only from its own twin apply ({@code CommandSource.TWIN}).
     */
    public static final String EXTRAS_KEY = "twin";

    /** The rows of the Twin tab a type uses besides the source and control (D19). */
    public enum Shape {
        /** {@code twin.invert}: on reads as off. */
        INVERT,
        /** {@code twin.min} and {@code twin.max}: the range a number maps from. */
        RANGE,
        /** {@code twin.decimals}: the places of a shown number. */
        DECIMALS,
        /** {@code twin.path}: what to read instead of the default. */
        PATH
    }

    /** The {@link PortRead}s for a source of {@code domain}: in-port key → read, in order; empty when the domain is no source. */
    @FunctionalInterface
    public interface Reads {
        Map<String, PortRead> reads(String domain, Binding.Settings settings);
    }

    /**
     * What a forwarded command asks of its source (D11): one {@link HaCalls.Outcome} per service call it would make,
     * built from typed values through {@link HaCalls} only, never from the command's own JSON. A {@link HaCalls.Skip}
     * (the LIVE mirror already shows it) and a {@link HaCalls.Refused} (nothing known to toggle, a read-only domain) say
     * why nothing is sent.
     */
    @FunctionalInterface
    public interface Forward {
        List<HaCalls.Outcome> forward(JsonObject command, String domain, HaCalls.Mirror mirror, Binding binding);
    }

    /**
     * What a value leaving a target's out-port asks of the entity the target switches (M3 plan §3.4 step 3, D5): the
     * Redstone Input's edge rules, the Wall Switch's press. Built through {@link HaCalls} only; a {@link HaCalls.Skip}
     * (a falling edge into a scene, the LIVE target already showing it) or a {@link HaCalls.Refused} says why nothing is
     * sent.
     */
    @FunctionalInterface
    public interface Target {
        /**
         * @param domain the target entity's domain
         * @param end    the target end: the entity and its in-port ({@link #targetEnd})
         * @param value  what the out-port carried
         * @param mirror what the twin knows of the target entity now
         */
        HaCalls.Outcome call(String domain, Endpoint.HaEntityRef end, JsonElement value, HaCalls.Mirror mirror);

        /** The value as it is, at the end's in-port. */
        Target AS_IS = (domain, end, value, mirror) -> HaCalls.resolve(domain, end.port(), value, mirror);
    }

    /**
     * Read-only values a source gives a device besides its in-port values, such as the Display Panel's comparator level,
     * which needs the source's range or options from its document (M3 plan D18). They ride in the twin apply's command
     * under {@link #EXTRAS_KEY}. Pure; never the entity id (D13 #7).
     */
    @FunctionalInterface
    public interface Extras {
        /**
         * @param document the source's document as {@link EntityDocument#toJson()} gives it
         * @param domain   the source's domain
         */
        JsonObject extras(JsonObject document, String domain, Binding binding);
    }

    private final String primary;
    private final List<String> sourceDomains;
    private final Set<String> mirroredKeys;
    private final Reads reads;
    private final Forward forward;
    private final Map<String, String> targets;
    // out-port -> how a value leaving it becomes a call
    private final Map<String, Target> targetCalls;
    private final List<String> targetDomains;
    private final Map<String, JsonElement> off;
    private final Set<Shape> shapes;
    private final Function<String, String> sourcePort;
    private final Function<String, List<String>> extraLeaves;
    private final Extras extras;
    private final List<String> attributeLeaves;

    private TwinAdapter(Builder builder) {
        this.primary = builder.primary;
        this.sourceDomains = List.copyOf(builder.sourceDomains);
        Set<String> keys = new LinkedHashSet<>();
        if (builder.primary != null) keys.add(builder.primary);
        keys.addAll(builder.mirroredKeys);
        this.mirroredKeys = Collections.unmodifiableSet(keys);
        this.reads = builder.reads;
        this.forward = builder.forward;
        this.targets = Collections.unmodifiableMap(new LinkedHashMap<>(builder.targets));
        this.targetCalls = Map.copyOf(builder.targetCalls);
        this.targetDomains = List.copyOf(builder.targetDomains);
        Map<String, JsonElement> offCopy = new LinkedHashMap<>();
        builder.off.forEach((port, value) -> offCopy.put(port, value.deepCopy()));
        this.off = Collections.unmodifiableMap(offCopy);
        this.shapes = builder.shapes.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(builder.shapes));
        this.sourcePort = builder.sourcePort;
        this.extraLeaves = builder.extraLeaves;
        this.extras = builder.extras;
        this.attributeLeaves = List.copyOf(builder.attributeLeaves);
    }

    /**
     * The source end of a binding to {@code entityId} (M3 plan §3.2): the entity's out-port the source wire carries, as
     * the adapter names it for the domain (the first out-port of the domain by default, else the generic {@code value}).
     *
     * @throws IllegalArgumentException for an id that is no entity id
     */
    public Endpoint.HaEntityRef sourceEnd(String entityId) {
        String port = sourcePort.apply(domainOf(entityId));
        return new Endpoint.HaEntityRef(entityId, port);
    }

    /**
     * The target end of a binding to {@code entityId}: the entity's first in-port (its {@code state}, {@code activate} or
     * {@code press}), which the service-call table translates.
     *
     * @throws IllegalArgumentException for an id that is no entity id, or an entity of a domain without in-ports
     */
    public static Endpoint.HaEntityRef targetEnd(String entityId) {
        HaDomains.Domain domain = HaDomains.domain(domainOf(entityId)).filter(HaDomains.Domain::controllable)
                .orElseThrow(() -> new IllegalArgumentException(entityId + " cannot be controlled"));
        return new Endpoint.HaEntityRef(entityId, domain.inPorts().getFirst().key());
    }

    // The first out-port of a readable domain, else the generic state port.
    private static String defaultSourcePort(String domain) {
        return HaDomains.domain(domain).filter(known -> !known.outPorts().isEmpty()).map(known -> known.outPorts().getFirst().key())
                .orElse(HaDomains.VALUE_PORT);
    }

    /** An adapter whose sources fill {@code primary}. */
    public static Builder builder(String primary) {
        return new Builder(Objects.requireNonNull(primary, "primary"));
    }

    /**
     * An adapter of targets only (M3 plan D5: the Redstone Input, the Wall Switch): no source, so no primary port and no
     * mirrored key, and a device of its type is never mirrored; its out-ports switch entities.
     */
    public static Builder targetsOnly() {
        return new Builder(null);
    }

    /** The in-port a source fills; null for an adapter of targets only. */
    public String primary() {
        return primary;
    }

    /** The command keys a source owns: the primary port first. */
    public Set<String> mirroredKeys() {
        return mirroredKeys;
    }

    /** The domains a source may be, in preference order; empty for an adapter of targets only. */
    public List<String> sourceDomains() {
        return sourceDomains;
    }

    /** Whether a device of this type can mirror a source at all. */
    public boolean hasSource() {
        return !sourceDomains.isEmpty();
    }

    /** Whether a mirrored device forwards commands to its source (control); a read-only adapter never does. */
    public boolean controllable() {
        return forward != null;
    }

    /** Target setting name → device out-port key, e.g. {@code target → powered}. */
    public Map<String, String> targets() {
        return targets;
    }

    /** The out-port a target setting name switches from. */
    public Optional<String> targetPort(String name) {
        return Optional.ofNullable(targets.get(name));
    }

    /** The controllable domains a target may be. */
    public List<String> targetDomains() {
        return targetDomains;
    }

    /** The in-port values a bound device takes while its source has no value and {@code twin.unavailable} is {@code off}. */
    public Map<String, JsonElement> off() {
        return off;
    }

    /** Whether a twin apply of this type carries {@link Extras} under {@link #EXTRAS_KEY}. */
    public boolean hasExtras() {
        return extras != null;
    }

    /** The Twin tab rows this type uses besides source and control. */
    public Set<Shape> shapes() {
        return shapes;
    }

    /** The reads of a source of {@code domain} with these settings; empty for a domain that is no source of this type. */
    public Map<String, PortRead> reads(String domain, Binding.Settings settings) {
        if (!sourceDomains.contains(domain)) return Map.of();
        Map<String, PortRead> declared = reads.reads(domain, settings);
        return declared == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(declared));
    }

    /**
     * The reads of a binding's source: its domain's for a Home Assistant entity, its kind's for an MQTT device preset
     * (M3-17), none without one.
     */
    public Map<String, PortRead> reads(Binding binding) {
        return binding.sourceDomain().map(domain -> reads(domain, binding.settings())).orElse(Map.of());
    }

    /**
     * The statestream leaves a binding to {@code entityId} subscribes to (M3 plan D1 #8, never {@code +}): the state, its
     * {@code last_updated} (freshness and the catalog merge, D3), the leaves of the source port
     * ({@link HaDomains.Port#leaves}, e.g. a light's brightness and colours), the leaf of each read's path, the
     * {@code twin.path} one included, the attributes the type also reads ({@link Builder#leaves}, e.g. a unit), then what
     * the adapter also needs to write back ({@link Builder#extraLeaves}, an {@code input_number}'s {@code step}). Only
     * leaves the domain allows, each once, in that order; empty for an entity that is no source of this type.
     */
    public List<String> leaves(String entityId, Binding.Settings settings) {
        if (sourceProblem(entityId).isPresent()) return List.of();
        String domainId = domainOf(entityId);
        HaDomains.Domain domain = HaDomains.domain(domainId).orElseThrow();
        Set<String> leaves = new LinkedHashSet<>();
        leaves.add(HaDomains.STATE_LEAF);
        leaves.add(StatestreamTopic.LAST_UPDATED);
        domain.readPort(sourcePort.apply(domainId)).ifPresent(port -> leaves.addAll(port.leaves()));
        reads(domainId, settings).values().forEach(read -> read.path().statestreamLeaf().ifPresent(leaves::add));
        if (settings.path() != null) settings.path().statestreamLeaf().ifPresent(leaves::add);
        leaves.addAll(attributeLeaves);
        leaves.addAll(extraLeaves.apply(domainId));
        return leaves.stream().filter(domain::readableLeaf).toList();
    }

    /** Why {@code entityId} cannot be this type's source, or empty when it can. */
    public Optional<String> sourceProblem(String entityId) {
        if (!hasSource()) return Optional.of("this device cannot mirror anything");
        if (!Endpoint.ENTITY_ID.matcher(entityId).matches()) return Optional.of("'" + entityId + "' is not a Home Assistant entity id");
        String domain = domainOf(entityId);
        if (!HaDomains.readable(domain)) return Optional.of(domain + " entities cannot be read by MineQTT");
        if (!sourceDomains.contains(domain)) return Optional.of("this device cannot mirror " + domain + " entities (it mirrors " + String.join(", ", sourceDomains) + ")");
        return Optional.empty();
    }

    /**
     * Why an MQTT device preset of {@code kind} cannot be this type's source (M3-17), or empty when it can: a preset
     * makes its device behave as an entity of its kind, so the kind must be one of the type's source domains.
     */
    public Optional<String> kindProblem(String kind) {
        if (!hasSource()) return Optional.of("this device cannot mirror anything");
        if (!HaDomains.readable(kind)) return Optional.of(kind + " devices cannot be read by MineQTT");
        if (!sourceDomains.contains(kind)) return Optional.of("this device cannot mirror a " + kind + " (it mirrors " + String.join(", ", sourceDomains) + ")");
        return Optional.empty();
    }

    /** Why {@code entityId} cannot be the target of the target setting {@code name}, or empty when it can. */
    public Optional<String> targetProblem(String name, String entityId) {
        if (!targets.containsKey(name)) return Optional.of("this device has no target " + name);
        if (!Endpoint.ENTITY_ID.matcher(entityId).matches()) return Optional.of("'" + entityId + "' is not a Home Assistant entity id");
        String domain = domainOf(entityId);
        if (!HaDomains.controllable(domain)) return Optional.of(domain + " entities cannot be controlled by MineQTT");
        if (!targetDomains.contains(domain)) return Optional.of("this device cannot switch " + domain + " entities");
        return Optional.empty();
    }

    /**
     * Why a binding does not fit this adapter, or empty when it does: its source and targets must be ones this type
     * takes, and control needs an adapter that forwards. A topic source must name an MQTT device preset whose kind the
     * type mirrors (M3-17; whether the preset is loaded and its topic allowed is the runtime's to judge); targets are
     * Home Assistant entities only.
     */
    public Optional<String> problem(Binding binding) {
        Optional<String> source = binding.sourceEntity().flatMap(this::sourceProblem);
        if (source.isPresent()) return source;
        if (binding.source() instanceof Endpoint.TopicRef) {
            Optional<Binding.PresetRef> preset = binding.preset();
            if (preset.isEmpty()) return Optional.of("an MQTT topic is mirrored through a device preset (twin.device)");
            Optional<String> kind = kindProblem(preset.get().kind());
            if (kind.isPresent()) return kind;
        }
        if (binding.control() && !controllable()) return Optional.of("this device is read-only towards its source");
        for (Map.Entry<String, Endpoint> target : binding.targets().entrySet()) {
            String name = targets.entrySet().stream().filter(entry -> entry.getValue().equals(target.getKey())).map(Map.Entry::getKey).findFirst().orElse(null);
            if (name == null) return Optional.of("this device has no target on " + target.getKey());
            if (!(target.getValue() instanceof Endpoint.HaEntityRef entity)) return Optional.of("this device cannot switch an MQTT topic yet");
            Optional<String> problem = targetProblem(name, entity.entityId());
            if (problem.isPresent()) return problem;
        }
        return Optional.empty();
    }

    /** Whether a command touches what the source owns, so it is routed as a command to the mirror. */
    public boolean touchesMirror(JsonObject command) {
        for (String key : command.keySet()) {
            if (mirroredKeys.contains(key)) return true;
        }
        return false;
    }

    /**
     * The in-port values a document of the binding's source gives: in-port key → value, empty for no change. Nothing for a
     * binding that does not work, a document of another entity, or (with {@code twin.unavailable} {@code hold}) a source
     * without a value. Reading never yields a call.
     */
    public Map<String, JsonElement> read(EntityDocument document, Binding binding) {
        if (!binding.bound() || !binding.active() || !binding.sourceDocument().map(document.entityId()::equals).orElse(false)) return Map.of();
        if (!document.deliverable()) {
            if (binding.settings().unavailable() != Binding.Unavailable.OFF) return Map.of();
            Map<String, JsonElement> values = new LinkedHashMap<>();
            off.forEach((port, value) -> values.put(port, value.deepCopy()));
            return values;
        }
        JsonObject json = document.toJson();
        Map<String, JsonElement> values = new LinkedHashMap<>();
        reads(document.domain(), binding.settings()).forEach((port, read) -> read.apply(json).ifPresent(value -> values.put(port, value)));
        return values;
    }

    /**
     * The {@link Extras} a document of the binding's source gives: empty whenever {@link #read} would read nothing from it
     * for being another entity's, a refused binding's or a source without a value, and for an adapter without extras.
     */
    public JsonObject extras(EntityDocument document, Binding binding) {
        if (extras == null || !binding.bound() || !binding.active() || !binding.sourceDocument().map(document.entityId()::equals).orElse(false)
                || !document.deliverable()) {
            return new JsonObject();
        }
        JsonObject values = extras.extras(document.toJson(), document.domain(), binding);
        return values == null ? new JsonObject() : values.deepCopy();
    }

    /**
     * What a forwarded command asks of its source, one outcome per service call: nothing unless the binding works, has
     * control and mirrors a Home Assistant entity or an MQTT device preset (whose kind stands for the domain, M3-17).
     * Every call sent is of the source's own domain (D12: no cross-domain call); a call of another domain is dropped.
     *
     * @param mirror what the source shows now ({@link HaCalls.Mirror#NONE} when nothing is known)
     */
    public List<HaCalls.Outcome> forward(JsonObject command, HaCalls.Mirror mirror, Binding binding) {
        if (forward == null || !binding.controls() || binding.sourceDomain().isEmpty() || !touchesMirror(command)) return List.of();
        String domain = binding.sourceDomain().get();
        List<HaCalls.Outcome> outcomes = forward.forward(command.deepCopy(), domain, mirror, binding);
        if (outcomes == null) return List.of();
        List<HaCalls.Outcome> pinned = new ArrayList<>();
        for (HaCalls.Outcome outcome : outcomes) {
            if (outcome == null) continue;
            if (outcome instanceof HaCalls.Send(Call call) && !call.domain().equals(domain)) continue;
            pinned.add(outcome);
        }
        return List.copyOf(pinned);
    }

    /**
     * What a value leaving the out-port {@code outPort} asks of the entity a target there switches: the target's
     * translation ({@link Builder#target(String, String, Target)}), refused for an out-port that is no target or an entity
     * this type does not switch; a call of another domain than the entity's is refused too (D12: no cross-domain call).
     * Pure; the root gate comes before it ({@code HaSink}).
     */
    public HaCalls.Outcome targetCall(String outPort, Endpoint.HaEntityRef end, JsonElement value, HaCalls.Mirror mirror) {
        Target target = targetCalls.get(outPort);
        if (target == null) return new HaCalls.Refused(HaCalls.Reason.NO_SUCH_PORT, "this device switches nothing from " + outPort);
        String domain = domainOf(end.entityId());
        if (!targetDomains.contains(domain)) return new HaCalls.Refused(HaCalls.Reason.UNKNOWN_DOMAIN, "this device cannot switch " + domain + " entities");
        HaCalls.Outcome outcome = target.call(domain, end, value, mirror);
        if (outcome instanceof HaCalls.Send(Call call) && !call.domain().equals(domain)) {
            return new HaCalls.Refused(HaCalls.Reason.UNKNOWN_DOMAIN, "a " + domain + " target may not call " + call.action());
        }
        return outcome;
    }

    /** The calls {@link #forward} would send: its {@link HaCalls.Send} outcomes alone. */
    public List<Call> calls(JsonObject command, HaCalls.Mirror mirror, Binding binding) {
        return forward(command, mirror, binding).stream().flatMap(outcome -> outcome.sent().stream()).toList();
    }

    /**
     * A {@link Forward} that sends the value of each command key in {@code ports} through {@link HaCalls#resolve} to the
     * source domain's in-port it maps to: command key → Home Assistant in-port key.
     */
    public static Forward ports(Map<String, String> ports) {
        Map<String, String> copy = Collections.unmodifiableMap(new LinkedHashMap<>(ports));
        return (command, domain, mirror, binding) -> {
            List<HaCalls.Outcome> outcomes = new ArrayList<>();
            copy.forEach((key, inPort) -> {
                JsonElement value = command.get(key);
                if (value != null) outcomes.add(HaCalls.resolve(domain, inPort, value, mirror));
            });
            return outcomes;
        };
    }

    /** {@code light} for {@code light.kitchen}. */
    public static String domainOf(String entityId) {
        int dot = entityId.indexOf('.');
        return dot < 0 ? entityId : entityId.substring(0, dot);
    }

    public static final class Builder {
        private final String primary;
        private final List<String> sourceDomains = new ArrayList<>();
        private final Set<String> mirroredKeys = new LinkedHashSet<>();
        private Reads reads = (domain, settings) -> Map.of();
        private Forward forward;
        private final Map<String, String> targets = new LinkedHashMap<>();
        private final Map<String, Target> targetCalls = new LinkedHashMap<>();
        private final List<String> targetDomains = new ArrayList<>();
        private final Map<String, JsonElement> off = new LinkedHashMap<>();
        private final Set<Shape> shapes = new LinkedHashSet<>();
        private Function<String, String> sourcePort = TwinAdapter::defaultSourcePort;
        private Function<String, List<String>> extraLeaves = domain -> List.of();
        private Extras extras;
        private final List<String> attributeLeaves = new ArrayList<>();

        // A null primary is an adapter of targets only.
        private Builder(String primary) {
            this.primary = primary;
        }

        /** The domains a source may be, in preference order, read by {@code reads}. */
        public Builder source(Reads reads, String... domains) {
            this.reads = Objects.requireNonNull(reads, "reads");
            sourceDomains.addAll(List.of(domains));
            return this;
        }

        /** Command keys the source owns besides the primary port (the keys of an inline light, say). */
        public Builder mirrors(String... keys) {
            mirroredKeys.addAll(List.of(keys));
            return this;
        }

        /** How forwarded commands become calls; without it the adapter is read-only. */
        public Builder forward(Forward forward) {
            this.forward = Objects.requireNonNull(forward, "forward");
            return this;
        }

        /**
         * A target: the setting {@code twin.<name>} switches an entity from the out-port {@code outPort}, whose values
         * reach the entity's in-port as they are ({@link Target#AS_IS}).
         */
        public Builder target(String name, String outPort) {
            return target(name, outPort, Target.AS_IS);
        }

        /** A target whose out-port's values become calls through {@code call}: edge rules, a press. */
        public Builder target(String name, String outPort, Target call) {
            targets.put(name, outPort);
            targetCalls.put(outPort, Objects.requireNonNull(call, "call"));
            return this;
        }

        /** The controllable domains a target may be. */
        public Builder targetDomains(String... domains) {
            targetDomains.addAll(List.of(domains));
            return this;
        }

        /** An in-port value taken while the source has no value and {@code twin.unavailable} is {@code off}. */
        public Builder off(String port, JsonElement value) {
            off.put(port, value.deepCopy());
            return this;
        }

        /** The out-port of a source domain the source wire carries, e.g. a light's {@code light} composite. */
        public Builder sourcePort(Function<String, String> sourcePort) {
            this.sourcePort = Objects.requireNonNull(sourcePort, "sourcePort");
            return this;
        }

        /**
         * Statestream leaves of a source domain the adapter needs besides what it reads, e.g. an {@code input_number}'s
         * {@code step}, to which a written value is snapped. Only leaves the domain allows are subscribed.
         */
        public Builder extraLeaves(Function<String, List<String>> extraLeaves) {
            this.extraLeaves = Objects.requireNonNull(extraLeaves, "extraLeaves");
            return this;
        }

        public Builder shapes(Shape... shapes) {
            this.shapes.addAll(List.of(shapes));
            return this;
        }

        /** Read-only values a source gives besides the in-port values ({@link Extras}); needs a source. */
        public Builder extras(Extras extras) {
            this.extras = Objects.requireNonNull(extras, "extras");
            return this;
        }

        /**
         * Attribute leaves a source's subscription also holds where its domain allows them, besides the source port's and
         * the reads' ({@link TwinAdapter#leaves}): what a map or the extras read from the document, such as a unit.
         */
        public Builder leaves(String... attributes) {
            attributeLeaves.addAll(List.of(attributes));
            return this;
        }

        /**
         * @throws IllegalStateException when the declaration is inconsistent on its own: no source and no target, a
         *                               source domain MineQTT cannot read or a duplicate one, a target domain it cannot
         *                               control, a malformed key, or control without a source. The type checks the
         *                               ports ({@code DeviceType.Builder#build}).
         */
        public TwinAdapter build() {
            if (primary != null && !Endpoint.isPortKey(primary)) fail("has a malformed primary port " + primary);
            if (primary == null && (!sourceDomains.isEmpty() || !mirroredKeys.isEmpty() || !off.isEmpty() || !shapes.isEmpty())) {
                fail("mirrors something without a primary port");
            }
            if (sourceDomains.isEmpty() && targets.isEmpty()) fail("has neither a source nor a target");
            if (new LinkedHashSet<>(sourceDomains).size() != sourceDomains.size()) fail("lists a source domain twice");
            for (String domain : sourceDomains) {
                if (!HaDomains.readable(domain)) fail("mirrors " + domain + ", which MineQTT cannot read");
            }
            for (String domain : targetDomains) {
                if (!HaDomains.controllable(domain)) fail("switches " + domain + ", which MineQTT cannot control");
            }
            if (!targets.isEmpty() && targetDomains.isEmpty()) fail("has targets but no target domain");
            if (forward != null && sourceDomains.isEmpty()) fail("forwards without a source");
            if ((extras != null || !attributeLeaves.isEmpty()) && sourceDomains.isEmpty()) fail("reads extras without a source");
            for (String leaf : attributeLeaves) {
                if (!Endpoint.isPortKey(leaf)) fail("subscribes the malformed leaf " + leaf);
            }
            for (String key : mirroredKeys) {
                if (!Endpoint.isPortKey(key)) fail("mirrors the malformed key " + key);
            }
            for (Map.Entry<String, String> target : targets.entrySet()) {
                if (!Endpoint.isPortKey(target.getKey()) || !Endpoint.isPortKey(target.getValue())) fail("has a malformed target " + target);
            }
            if (new LinkedHashSet<>(targets.values()).size() != targets.size()) fail("names one out-port under two targets");
            return new TwinAdapter(this);
        }

        private void fail(String problem) {
            throw new IllegalStateException("A twin adapter " + (primary == null ? "of targets" : "of " + primary) + " " + problem);
        }
    }
}

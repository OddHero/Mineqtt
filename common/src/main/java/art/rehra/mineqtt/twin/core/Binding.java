package art.rehra.mineqtt.twin.core;

import art.rehra.mineqtt.flow.core.Endpoint;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A device's binding to something outside the game (M3 plan §3.1, D6, D14): the source it mirrors, the targets its
 * out-ports switch, whether using it forwards to the source, and how the source is read. What is stored is declarative:
 * how each in-port is filled ({@link PortRead}) is derived from the device type's adapter and these settings, so a
 * corrected adapter reaches every stored binding (M3-7 deviation from the plan's {@code reads} component).
 * <p>
 * A binding with a source makes the device <em>bound</em> (mirrored): its HA discovery is reduced, its Raw Link input
 * pauses, Home Assistant {@code /set} and group commands to its mirrored ports are refused, and player or wire commands
 * to them are forwarded instead of applied. A binding the rules refuse ({@link #refused()}, the F2 pattern) stays bound,
 * reads nothing and forwards nothing until the rules allow it again. Pure, immutable and Minecraft-free.
 *
 * @param source   what the device mirrors: an {@link Endpoint.HaEntityRef}, or an {@link Endpoint.TopicRef} (the state
 *                 topic of an MQTT device preset, {@link Settings#preset()}, M3-17); null for a device that only has
 *                 targets (a Redstone Input, a Wall Switch)
 * @param targets  device out-port key → the {@link Endpoint.HaEntityRef} it switches, in a stable order; at most
 *                 {@value #MAX_TARGETS} (a {@link Endpoint.TopicRef} target is stored but no adapter takes one in M3)
 * @param control  whether a player's or a wire's command to a mirrored port is forwarded to the source; needs a source
 * @param settings how the source is read
 * @param refused  why the rules refuse the binding now, or null when it works
 */
public record Binding(Endpoint source, Map<String, Endpoint> targets, boolean control, Settings settings, String refused) {
    public static final int MAX_TARGETS = 8;
    /** The longest refusal kept, as a Raw Link's. */
    public static final int MAX_REFUSAL_LENGTH = 256;
    /** The longest entity id or topic a binding names. */
    public static final int MAX_REFERENCE_LENGTH = 255;

    public Binding {
        if (source != null) checkEnd(source, "source");
        Objects.requireNonNull(targets, "targets");
        if (targets.size() > MAX_TARGETS) throw new IllegalArgumentException("A binding has at most " + MAX_TARGETS + " targets");
        Map<String, Endpoint> copy = new LinkedHashMap<>();
        targets.forEach((port, target) -> {
            if (!Endpoint.isPortKey(port)) throw new IllegalArgumentException("Invalid target port: " + port);
            checkEnd(Objects.requireNonNull(target, "target"), "target");
            copy.put(port, target);
        });
        targets = Collections.unmodifiableMap(copy);
        Objects.requireNonNull(settings, "settings");
        // Only a topic source is read through a preset.
        if (settings.preset() != null && !(source instanceof Endpoint.TopicRef)) settings = settings.withPreset(null);
        if (source == null && targets.isEmpty()) throw new IllegalArgumentException("A binding needs a source or a target");
        if (control && source == null) throw new IllegalArgumentException("Control forwards to a source; a binding without one has no control");
        if (refused != null) {
            refused = refused.strip();
            if (refused.isEmpty()) refused = null;
            else if (refused.length() > MAX_REFUSAL_LENGTH) refused = refused.substring(0, MAX_REFUSAL_LENGTH);
        }
    }

    /** A binding that mirrors {@code source} with the default settings, control off. */
    public static Binding of(Endpoint source) {
        return new Binding(source, Map.of(), false, Settings.DEFAULT, null);
    }

    private static void checkEnd(Endpoint end, String role) {
        switch (end) {
            case Endpoint.HaEntityRef entity -> {
                if (entity.entityId().length() > MAX_REFERENCE_LENGTH) throw new IllegalArgumentException("The " + role + "'s entity id is too long");
            }
            case Endpoint.TopicRef topic -> {
                if (topic.topic().length() > MAX_REFERENCE_LENGTH) throw new IllegalArgumentException("The " + role + "'s topic is too long");
                Optional<String> problem = ValuePath.problem(topic.path());
                if (problem.isPresent()) throw new IllegalArgumentException("The " + role + "'s path: " + problem.get());
            }
            default -> throw new IllegalArgumentException("A binding's " + role + " is a Home Assistant entity or a topic, not " + end.describe());
        }
    }

    /** Whether the device mirrors a source: bound, whether or not the rules refuse the binding now. */
    public boolean bound() {
        return source != null;
    }

    /** Whether the binding works: the rules do not refuse it. */
    public boolean active() {
        return refused == null;
    }

    /** Whether commands to the mirrored ports are forwarded now: control is on and the binding works. */
    public boolean controls() {
        return control && active();
    }

    /** The Home Assistant entity of the source, if the source is one. */
    public Optional<String> sourceEntity() {
        return source instanceof Endpoint.HaEntityRef entity ? Optional.of(entity.entityId()) : Optional.empty();
    }

    /** The state topic of an MQTT device preset's source (M3-17), if the source is one with its preset named. */
    public Optional<String> sourceTopic() {
        return source instanceof Endpoint.TopicRef topic && settings.preset() != null ? Optional.of(topic.topic()) : Optional.empty();
    }

    /** The MQTT device preset the source is read and switched through, if it is a preset source. */
    public Optional<PresetRef> preset() {
        return sourceTopic().map(topic -> settings.preset());
    }

    /**
     * The Home Assistant domain the source is, or behaves as: an entity's own domain, or the kind of a preset source
     * ({@link BindingPreset#kind()}); empty without a source.
     */
    public Optional<String> sourceDomain() {
        if (source instanceof Endpoint.HaEntityRef entity) return Optional.of(entity.entityId().substring(0, entity.entityId().indexOf('.')));
        return preset().map(PresetRef::kind);
    }

    /**
     * The id of the documents the source's values come in: the entity id, or for a preset source the document id of its
     * topic ({@link BindingPreset#documentId}); empty without a source.
     */
    public Optional<String> sourceDocument() {
        Optional<String> entity = sourceEntity();
        if (entity.isPresent()) return entity;
        return preset().map(preset -> BindingPreset.documentId(preset.kind(), ((Endpoint.TopicRef) source).topic()));
    }

    /** The Home Assistant entity of the source or of any target: every one the rules judge. */
    public List<String> entities() {
        List<String> entities = new ArrayList<>();
        sourceEntity().ifPresent(entities::add);
        targets.values().forEach(target -> {
            if (target instanceof Endpoint.HaEntityRef entity && !entities.contains(entity.entityId())) entities.add(entity.entityId());
        });
        return entities;
    }

    /** What the source is, for status lines and logs: {@code light.kitchen} or {@code topic zigbee2mqtt/porch}. */
    public String describeSource() {
        if (source == null) return "nothing";
        return preset().map(preset -> preset.device() + " (" + preset.id() + ")").orElseGet(() -> describe(source));
    }

    /** What the binding names, for notices: its source, else its targets, e.g. {@code scene.movie, script.dim}. */
    public String describeEnds() {
        if (source != null) return describeSource();
        return String.join(", ", targets.values().stream().map(Binding::describe).toList());
    }

    /** One end as players read it: the entity id, or the topic with its path. */
    public static String describe(Endpoint end) {
        return switch (end) {
            case Endpoint.HaEntityRef entity -> entity.entityId();
            case Endpoint.TopicRef topic -> "topic " + topic.topic() + (topic.path().isEmpty() ? "" : " " + topic.path());
            default -> end.describe();
        };
    }

    /** The binding with another source; a source that is no topic leaves no preset behind. */
    public Binding withSource(Endpoint source) {
        Settings kept = source instanceof Endpoint.TopicRef ? settings : settings.withPreset(null);
        return new Binding(source, targets, control && source != null, kept, refused);
    }

    public Binding withControl(boolean control) {
        return new Binding(source, targets, control, settings, refused);
    }

    public Binding withSettings(Settings settings) {
        return new Binding(source, targets, control, settings, refused);
    }

    public Binding withRefused(String refused) {
        return new Binding(source, targets, control, settings, refused);
    }

    /** The binding with {@code port}'s target set, or removed for null. */
    public Binding withTarget(String port, Endpoint target) {
        Map<String, Endpoint> changed = new LinkedHashMap<>(targets);
        if (target == null) changed.remove(port);
        else changed.put(port, target);
        return new Binding(source, changed, control, settings, refused);
    }

    /**
     * The MQTT device preset a topic source is read and switched through (M3-17): stored with the binding, and resolved
     * against the loaded presets whenever the binding is judged, which also follows a datapack that moved the preset's
     * topic or changed its kind.
     *
     * @param id     the preset's id, {@code <namespace>:<path>}
     * @param device the device name its topics take ({@link BindingPreset#DEVICE})
     * @param kind   the preset's kind when the binding was last judged
     */
    public record PresetRef(String id, String device, String kind) {
        public PresetRef {
            if (id.length() > BindingPreset.MAX_ID_LENGTH || !BindingPreset.ID.matcher(id).matches()) throw new IllegalArgumentException("Not a preset id: " + id);
            if (device.isEmpty() || device.getBytes(StandardCharsets.UTF_8).length > BindingPreset.MAX_DEVICE_BYTES) {
                throw new IllegalArgumentException("A device name has 1 to " + BindingPreset.MAX_DEVICE_BYTES + " bytes");
            }
            if (!HaDomains.readable(kind)) throw new IllegalArgumentException("Not a kind MineQTT reads: " + kind);
        }

        /** How {@code twin.device} spells it: {@code <preset id> <device name>}. */
        public String setting() {
            return id + " " + device;
        }
    }

    /** What happens to a bound device while its source has no value ({@code unavailable}, {@code unknown}, cleared; D6). */
    public enum Unavailable {
        /** It keeps its last value and shows that the source is unavailable (the default: a door must not move). */
        HOLD,
        /** It turns off, as its adapter defines off. */
        OFF;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<Unavailable> of(String key) {
            for (Unavailable value : values()) {
                if (value.key().equals(key)) return Optional.of(value);
            }
            return Optional.empty();
        }
    }

    /**
     * How the source is read (D5, D7, D18): the per-type rows of the Twin tab.
     *
     * @param invert      on reads as off and the other way round
     * @param min         the source value that is level 0, or null for the entity's {@code min} attribute or 0
     * @param max         the source value that is level 15, or null for the entity's {@code max} attribute or 15
     * @param decimals    places of a shown number, {@link ValueMap#AUTO_DECIMALS} or 0 to {@value ValueMap#MAX_DECIMALS}
     * @param unavailable what happens while the source has no value
     * @param path        what to read instead of the adapter's default, or null for the default
     * @param preset      the MQTT device preset of a topic source, or null
     */
    public record Settings(boolean invert, BigDecimal min, BigDecimal max, int decimals, Unavailable unavailable, ValuePath path, PresetRef preset) {
        public static final Settings DEFAULT = new Settings(false, null, null, ValueMap.AUTO_DECIMALS, Unavailable.HOLD, null, null);
        /** The most digits before the point a range number has, as {@code twin.min} and {@code twin.max} take it. */
        public static final int MAX_INTEGER_DIGITS = 15;
        /** The most digits after the point a range number has. */
        public static final int MAX_FRACTION_DIGITS = 6;

        public Settings {
            if (decimals < ValueMap.AUTO_DECIMALS || decimals > ValueMap.MAX_DECIMALS) {
                throw new IllegalArgumentException("Decimals are auto or 0.." + ValueMap.MAX_DECIMALS + ", not " + decimals);
            }
            if (min != null && !fitsRange(min)) throw new IllegalArgumentException("min has more digits than a range number takes");
            if (max != null && !fitsRange(max)) throw new IllegalArgumentException("max has more digits than a range number takes");
            Objects.requireNonNull(unavailable, "unavailable");
            if (path != null && path.whole()) path = null;
        }

        /** Settings of a source that is no MQTT device preset's. */
        public Settings(boolean invert, BigDecimal min, BigDecimal max, int decimals, Unavailable unavailable, ValuePath path) {
            this(invert, min, max, decimals, unavailable, path, null);
        }

        /**
         * Whether {@code value} is a range number a binding keeps: at most {@value #MAX_INTEGER_DIGITS} digits before the
         * point and {@value #MAX_FRACTION_DIGITS} after it, as the Twin tab and {@code devices setting} take it. A stored
         * binding is bounded too, since block entity data comes from items and {@code /data}: a number such as
         * {@code 1e2000000000} would otherwise be written out in full at every save. Judged from the scale and the
         * precision only, never by writing the number out.
         */
        public static boolean fitsRange(BigDecimal value) {
            return value.scale() <= MAX_FRACTION_DIGITS && (long) value.precision() - value.scale() <= MAX_INTEGER_DIGITS;
        }

        public Settings withInvert(boolean invert) {
            return new Settings(invert, min, max, decimals, unavailable, path, preset);
        }

        public Settings withMin(BigDecimal min) {
            return new Settings(invert, min, max, decimals, unavailable, path, preset);
        }

        public Settings withMax(BigDecimal max) {
            return new Settings(invert, min, max, decimals, unavailable, path, preset);
        }

        public Settings withDecimals(int decimals) {
            return new Settings(invert, min, max, decimals, unavailable, path, preset);
        }

        public Settings withUnavailable(Unavailable unavailable) {
            return new Settings(invert, min, max, decimals, unavailable, path, preset);
        }

        public Settings withPath(ValuePath path) {
            return new Settings(invert, min, max, decimals, unavailable, path, preset);
        }

        public Settings withPreset(PresetRef preset) {
            return new Settings(invert, min, max, decimals, unavailable, path, preset);
        }
    }
}

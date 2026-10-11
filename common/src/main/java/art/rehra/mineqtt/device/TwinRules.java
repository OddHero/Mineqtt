package art.rehra.mineqtt.device;

import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.security.AclContext;
import art.rehra.mineqtt.twin.BindingPresets;
import art.rehra.mineqtt.twin.TwinAdapter;
import art.rehra.mineqtt.twin.core.Binding;
import art.rehra.mineqtt.twin.core.BindingPreset;
import art.rehra.mineqtt.twin.core.EntityGlobs;
import art.rehra.mineqtt.twin.core.HaDomains;
import art.rehra.mineqtt.twin.core.TwinHealth;
import art.rehra.mineqtt.twin.core.ValuePath;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.NameAndId;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Who may bind what, and what a binding does to the rest of a device (M3 plan D6, D13, D14), as pure functions the
 * runtime and the checks share:
 * <ul>
 *     <li>{@link #edit}: a {@code twin.*} change for an editor, with the twin gate composed on top of CONFIGURE (the
 *     block's own gate, which already refuses a player who may not build): {@code mineqtt:twin.bind} and
 *     {@code twin.allowedEntities} for a source or target, {@code mineqtt:twin.control} to turn control on. Operators and
 *     the console never bypass the entity list.</li>
 *     <li>{@link #recheck} and {@link #placed}: a stored binding judged again for the device's owner (at attach, after a
 *     config reload) or for the player who placed a copy, the F2 pattern: a binding the rules refuse stays, refused,
 *     and heals when they allow it again; a placed copy loses control unless its placer may turn it on.</li>
 *     <li>What a bound device refuses: it joins no group, lands no queued value of a mirrored port, and a new wire does
 *     not seed its mirrored ports.</li>
 *     <li>An MQTT device preset source (M3-17): the preset must be loaded ({@link BindingPresets}) and of a kind the type
 *     mirrors, the device name must make valid topics, and the state topic must pass {@link TopicAcl#checkTopicSource}
 *     for the binder and the owner; turning control on needs a preset that sends commands and a command topic that
 *     {@link TopicAcl#checkPublish} allows them (so an admin adds e.g. {@code zigbee2mqtt/+/set} to
 *     {@code acl.rawAllow}). Presets need {@code mineqtt:twin.bind}, but not the Home Assistant twin's statestream base or
 *     {@code twin.allowedEntities}: the ACL is their gate. A stored preset binding follows the loaded preset's topic and
 *     kind whenever it is judged ({@link #recheck}).</li>
 * </ul>
 */
public final class TwinRules {
    private TwinRules() {
    }

    /**
     * How the ACL judges the topics of an MQTT device preset for one subject (M3-17): the binder's, or the device owner's.
     */
    public interface Topics {
        /** Why the subject may not have a device mirror {@code topic}, or empty ({@link TopicAcl#checkTopicSource}). */
        Optional<String> source(String topic);

        /** Why the subject may not have a device publish its commands to {@code topic}, or empty ({@link TopicAcl#checkPublish}). */
        Optional<String> command(String topic);

        /** No ACL at hand (MineQTT is not running): every topic is refused. */
        Topics NONE = new Topics() {
            @Override
            public Optional<String> source(String topic) {
                return Optional.of("MineQTT is not running");
            }

            @Override
            public Optional<String> command(String topic) {
                return Optional.of("MineQTT is not running");
            }
        };

        /** {@code acl}'s judgement for {@code subject}. */
        static Topics of(TopicAcl acl, TopicAcl.Subject subject) {
            return new Topics() {
                @Override
                public Optional<String> source(String topic) {
                    TopicAcl.Decision decision = acl.checkTopicSource(subject, topic);
                    return decision.allowed() ? Optional.empty() : Optional.of(topic + ": " + decision.message());
                }

                @Override
                public Optional<String> command(String topic) {
                    TopicAcl.Decision decision = acl.checkPublish(subject, topic);
                    return decision.allowed() ? Optional.empty() : Optional.of(topic + ": " + decision.message());
                }
            };
        }
    }

    /**
     * What a binder may do, as the server judges them.
     *
     * @param mayBind    {@code mineqtt:twin.bind} (AUTO: everyone integrated, operators on dedicated servers)
     * @param mayControl {@code mineqtt:twin.control}
     * @param allowed    {@code twin.allowedEntities} of this server
     * @param off        why the twin is off on this server, if it is ({@link MineQTTConfig#twinOffReason})
     * @param topics     how the ACL judges an MQTT device preset's topics for them (M3-17)
     */
    public record Rights(boolean mayBind, boolean mayControl, EntityGlobs allowed, Optional<String> off, Topics topics) {
        public Rights {
            Objects.requireNonNull(allowed, "allowed");
            Objects.requireNonNull(off, "off");
            Objects.requireNonNull(topics, "topics");
        }

        /** Rights without an ACL for preset topics, which refuses them all. */
        public Rights(boolean mayBind, boolean mayControl, EntityGlobs allowed, Optional<String> off) {
            this(mayBind, mayControl, allowed, off, Topics.NONE);
        }

        /** No rights at all: a device without an owner. */
        public static final Rights NONE = new Rights(false, false, EntityGlobs.NONE, Optional.empty());

        /** These rights with the ACL's judgement of preset topics for their subject. */
        public Rights withTopics(Topics topics) {
            return new Rights(mayBind, mayControl, allowed, off, topics);
        }

        /** A player's or a command source's rights under the loaded config. */
        public static Rights of(PermissionSet permissions, boolean dedicatedServer) {
            return new Rights(AclContext.mayBindTwin(permissions, dedicatedServer), AclContext.mayControlTwin(permissions, dedicatedServer),
                    MineQTTConfig.twinAllowedEntities(dedicatedServer), MineQTTConfig.twinOffReason(dedicatedServer));
        }

        /**
         * A device owner's rights, who may be offline, by the server's op list; an ownerless device has none. Preset
         * topics are judged for the owner by the running server's ACL.
         */
        public static Rights owner(MinecraftServer server, @Nullable UUID owner, String ownerName) {
            PermissionSet permissions = owner == null ? PermissionSet.NO_PERMISSIONS : server.getProfilePermissions(new NameAndId(owner, ownerName));
            return of(permissions, server.isDedicatedServer()).withTopics(TwinRules.topics(server, AclContext.owner(server, owner, ownerName)));
        }
    }

    /** How the running server's ACL judges preset topics for {@code subject}; {@link Topics#NONE} when MineQTT is not running there. */
    public static Topics topics(MinecraftServer server, TopicAcl.Subject subject) {
        return MineqttRuntime.current().filter(runtime -> runtime.server() == server).map(runtime -> Topics.of(runtime.acl(), subject)).orElse(Topics.NONE);
    }

    /**
     * What an edit did.
     *
     * @param binding the binding after it; null when the device is unbound
     * @param refusal why it was refused, or null when it applied
     */
    public record Edited(@Nullable Binding binding, @Nullable String refusal) {
        static Edited refused(String refusal) {
            return new Edited(null, refusal);
        }
    }

    /**
     * A {@code twin.*} change for an editor with {@code rights}, checked against the type's adapter (M3 plan D19, D13),
     * and the result judged for the device's owner. An edit that changes nothing returns the current binding, judged
     * again, so binding the same entity anew heals a refusal the rules no longer make. The block's CONFIGURE gate and the
     * loaded check are the caller's.
     *
     * @param current the binding now; null for an unbound device
     * @param owner   what the device's owner may bind ({@link Rights#owner})
     * @param grouped whether the device is in a group, which a mirrored device may not be (D6)
     */
    public static Edited edit(TwinAdapter adapter, @Nullable Binding current, DeviceConfig.Twin change, Rights rights, Rights owner, boolean grouped) {
        Edited edited = editFor(adapter, current, change, rights, grouped);
        if (edited.refusal() != null || edited.binding() == null) return edited;
        // The device reads and switches for its owner from then on, so the binding is judged for them too, as a typed Raw
        // Link topic is (M2 plan D1): an editor never binds an entity for an owner who may not have it. A row changed on
        // a binding already refused for its owner keeps it refused; the attach and a reload heal it.
        Binding judged = recheck(adapter, edited.binding(), owner);
        boolean namesEntity = change instanceof DeviceConfig.TwinSource source && !source.entity().isEmpty()
                || change instanceof DeviceConfig.TwinTarget target && !target.entity().isEmpty()
                || change instanceof DeviceConfig.TwinDevice device && !device.unbinds()
                || change instanceof DeviceConfig.TwinPreset;
        if (namesEntity && judged.refused() != null) return Edited.refused("not for the device's owner: " + judged.refused());
        // The owner's device publishes the commands from then on, so their command topic must be one the owner may use.
        if (change instanceof DeviceConfig.TwinControl(boolean on) && on) {
            Optional<String> command = commandProblem(judged, owner);
            if (command.isPresent()) return Edited.refused("not for the device's owner: " + command.get());
        }
        return new Edited(judged, null);
    }

    private static Edited editFor(TwinAdapter adapter, @Nullable Binding current, DeviceConfig.Twin change, Rights rights, boolean grouped) {
        return switch (change) {
            case DeviceConfig.TwinSource source -> source(adapter, current, source.entity(), rights, grouped);
            case DeviceConfig.TwinDevice device -> device.unbinds() ? source(adapter, current, "", rights, grouped)
                    : presetSource(adapter, current, device.preset(), device.device(), rights, grouped);
            case DeviceConfig.TwinPreset preset -> {
                Optional<Binding.PresetRef> ref = current == null ? Optional.empty() : current.preset();
                if (ref.isEmpty()) yield Edited.refused("name the MQTT device too: twin.device " + preset.preset() + " <device name>");
                yield presetSource(adapter, current, preset.preset(), ref.get().device(), rights, grouped);
            }
            case DeviceConfig.TwinTarget target -> target(adapter, current, target.name(), target.entity(), rights);
            case DeviceConfig.TwinControl control -> {
                if (current == null || !current.bound()) yield Edited.refused("bind it to a Home Assistant entity first");
                if (!control.control()) yield new Edited(current.withControl(false), null);
                if (!adapter.controllable()) yield Edited.refused("this device is read-only towards Home Assistant");
                if (!rights.mayControl()) yield Edited.refused("turning control on needs the mineqtt:twin.control permission");
                Optional<String> command = commandProblem(current, rights);
                if (command.isPresent()) yield Edited.refused(command.get());
                yield new Edited(current.withControl(true), null);
            }
            case DeviceConfig.TwinPath path -> {
                if (current == null || current.sourceDomain().isEmpty()) yield Edited.refused("bind it to a Home Assistant entity first");
                if (!rights.mayBind()) yield Edited.refused(current.sourceEntity().flatMap(entity -> bindRefusal(rights, entity)).orElse(BIND_PERMISSION));
                Optional<String> problem = path.path() == null ? Optional.empty() : current.preset().isPresent() ? presetPathProblem(path.path())
                        : pathProblem(current.sourceDomain().get(), path.path());
                yield problem.isPresent() ? Edited.refused(problem.get()) : new Edited(current.withSettings(current.settings().withPath(path.path())), null);
            }
            case DeviceConfig.TwinInvert invert -> shaped(current, current == null ? null : current.settings().withInvert(invert.invert()));
            case DeviceConfig.TwinMin min -> shaped(current, current == null ? null : current.settings().withMin(min.min()));
            case DeviceConfig.TwinMax max -> shaped(current, current == null ? null : current.settings().withMax(max.max()));
            case DeviceConfig.TwinDecimals decimals -> shaped(current, current == null ? null : current.settings().withDecimals(decimals.decimals()));
            case DeviceConfig.TwinUnavailable unavailable -> shaped(current, current == null ? null : current.settings().withUnavailable(unavailable.unavailable()));
        };
    }

    private static Edited source(TwinAdapter adapter, @Nullable Binding current, String entity, Rights rights, boolean grouped) {
        if (entity.isEmpty()) {
            if (current == null || !current.bound()) return new Edited(current, null);
            return new Edited(current.targets().isEmpty() ? null : current.withSource(null), null);
        }
        if (current != null && current.sourceEntity().map(entity::equals).orElse(false)) return new Edited(current, null);
        if (grouped) return Edited.refused("a device in a group cannot follow Home Assistant; group the entities in Home Assistant instead");
        Optional<String> problem = adapter.sourceProblem(entity).or(() -> bindRefusal(rights, entity));
        if (problem.isPresent()) return Edited.refused(problem.get());
        Binding.Settings settings = current == null ? Binding.Settings.DEFAULT : current.settings();
        // A path reads the old entity's attributes; a new domain may not have them.
        if (settings.path() != null && pathProblem(TwinAdapter.domainOf(entity), settings.path()).isPresent()) settings = settings.withPath(null);
        // Control is turned on for one entity: a new source starts without it.
        Binding bound = current == null ? Binding.of(adapter.sourceEnd(entity)) : current.withSource(adapter.sourceEnd(entity)).withControl(false);
        return new Edited(bound.withSettings(settings), null);
    }

    /**
     * A source that is an MQTT device through a preset (M3-17): the preset loaded and of a kind the type mirrors, the
     * device name valid for it, the binder holding {@code mineqtt:twin.bind}, and the state topic allowed for them. Naming
     * the same preset and device again keeps the binding (and so heals a refusal the rules no longer make).
     */
    private static Edited presetSource(TwinAdapter adapter, @Nullable Binding current, String presetId, String device, Rights rights, boolean grouped) {
        Optional<Binding.PresetRef> before = current == null ? Optional.empty() : current.preset();
        if (before.isPresent() && before.get().id().equals(presetId) && before.get().device().equals(device)) return new Edited(current, null);
        if (grouped) return Edited.refused("a device in a group cannot follow an MQTT device; group the devices where they come from instead");
        if (!rights.mayBind()) return Edited.refused(BIND_PERMISSION);
        Optional<BindingPreset> found = BindingPresets.get(presetId);
        if (found.isEmpty()) return Edited.refused("no MQTT device preset " + presetId + " is loaded");
        BindingPreset preset = found.get();
        Optional<String> problem = adapter.kindProblem(preset.kind()).or(() -> preset.deviceProblem(device));
        if (problem.isPresent()) return Edited.refused(problem.get());
        String topic = preset.stateTopic(device);
        Optional<String> refused = rights.topics().source(topic);
        if (refused.isPresent()) return Edited.refused(refused.get());
        Binding.Settings settings = (current == null ? Binding.Settings.DEFAULT : current.settings()).withPreset(new Binding.PresetRef(presetId, device, preset.kind()));
        // A path read another source's document, which this preset may not make.
        if (current == null || !current.sourceDomain().map(preset.kind()::equals).orElse(false)) settings = settings.withPath(null);
        Endpoint.TopicRef source = new Endpoint.TopicRef(topic, "");
        // Control is turned on for one device: a new source starts without it.
        Binding bound = current == null ? new Binding(source, Map.of(), false, settings, null) : current.withSource(source).withControl(false).withSettings(settings);
        return new Edited(bound, null);
    }

    private static final String BIND_PERMISSION = "binding needs the mineqtt:twin.bind permission";

    /**
     * Why a preset source's commands may not be sent for {@code rights} (M3-17), or empty when they may or the source is
     * no preset's: the preset must be loaded and send commands, and its command topic must pass the ACL for them.
     */
    public static Optional<String> commandProblem(@Nullable Binding binding, Rights rights) {
        Optional<Binding.PresetRef> ref = binding == null ? Optional.empty() : binding.preset();
        if (ref.isEmpty()) return Optional.empty();
        Optional<BindingPreset> preset = BindingPresets.get(ref.get().id());
        if (preset.isEmpty()) return Optional.of("no MQTT device preset " + ref.get().id() + " is loaded");
        Optional<String> topic = preset.get().commandTopic(ref.get().device());
        if (topic.isEmpty() || !preset.get().controllable()) return Optional.of("the " + preset.get().name() + " preset is read-only");
        return rights.topics().command(topic.get());
    }

    /**
     * Why {@code path} may not be read from a preset source's document (M3-17), or empty when it may: one leaf, the state
     * or an attribute the preset gives (the preset itself never gives GPS or pictures).
     */
    public static Optional<String> presetPathProblem(ValuePath path) {
        Optional<String> leaf = path.statestreamLeaf();
        if (leaf.isEmpty()) return Optional.of("twin.path must read the state or one attribute, like attributes.temperature");
        return HaDomains.NEVER_ATTRIBUTES.contains(leaf.get()) ? Optional.of("MineQTT does not read " + leaf.get()) : Optional.empty();
    }

    private static Edited target(TwinAdapter adapter, @Nullable Binding current, String name, String entity, Rights rights) {
        Optional<String> port = adapter.targetPort(name);
        if (port.isEmpty()) return Edited.refused("this device has no target " + name);
        if (entity.isEmpty()) {
            if (current == null || !current.targets().containsKey(port.get())) return new Edited(current, null);
            // The last target of a device without a source leaves no binding at all (a Binding needs one or the other).
            if (!current.bound() && current.targets().size() == 1) return new Edited(null, null);
            return new Edited(current.withTarget(port.get(), null), null);
        }
        Optional<String> problem = adapter.targetProblem(name, entity).or(() -> bindRefusal(rights, entity));
        if (problem.isPresent()) return Edited.refused(problem.get());
        Binding changed = current == null ? new Binding(null, Map.of(port.get(), TwinAdapter.targetEnd(entity)), false, Binding.Settings.DEFAULT, null)
                : current.withTarget(port.get(), TwinAdapter.targetEnd(entity));
        return new Edited(changed, null);
    }

    // A Twin tab row changes how the source is read; there must be one.
    private static Edited shaped(@Nullable Binding current, Binding.@Nullable Settings settings) {
        if (current == null || !current.bound() || settings == null) return Edited.refused("bind it to a Home Assistant entity first");
        return new Edited(current.withSettings(settings), null);
    }

    /**
     * Why {@code path} may not be read from an entity of {@code domain}, or empty when it may: it must name one statestream
     * leaf, and that leaf must be the state, a timestamp or a whitelisted attribute (M3-4 decision 8), so no path shows
     * every client in range an attribute such as a lock's {@code changed_by}.
     */
    public static Optional<String> pathProblem(String domain, ValuePath path) {
        Optional<String> leaf = path.statestreamLeaf();
        if (leaf.isEmpty()) return Optional.of("twin.path must read the state or one attribute, like attributes.temperature");
        boolean readable = HaDomains.domain(domain).map(known -> known.readableLeaf(leaf.get())).orElse(false);
        return readable ? Optional.empty() : Optional.of("MineQTT does not read " + leaf.get() + " of " + domain + " entities");
    }

    /**
     * The twin gate for one entity (M3 plan D13): the twin is on, the binder holds {@code mineqtt:twin.bind}, and the
     * entity is in {@code twin.allowedEntities}, which takes no subject, so nobody bypasses it.
     */
    public static Optional<String> bindRefusal(Rights rights, String entity) {
        if (rights.mayBind() && rights.off().isPresent()) return Optional.of("the Home Assistant twin is off on this server: " + rights.off().get());
        return AclContext.twinBindRefusal(rights.mayBind(), rights.allowed(), entity);
    }

    /**
     * The share gate of an edit (M3 plan D3, M3-11, M3-14): the first problem {@code shareProblem} finds with an entity
     * {@code after} binds that {@code before} did not, source or target. Entities already bound stay editable when they
     * left the catalog; removing the binding is never refused.
     *
     * @param shareProblem why an entity is not shared now ({@code TwinRuntime.shareProblem}), or empty
     */
    public static Optional<String> shareRefusal(@Nullable Binding before, @Nullable Binding after, Function<String, Optional<String>> shareProblem) {
        if (after == null) return Optional.empty();
        Set<String> bound = before == null ? Set.of() : Set.copyOf(before.entities());
        return after.entities().stream().filter(entity -> !bound.contains(entity)).map(shareProblem).flatMap(Optional::stream).findFirst();
    }

    /** Why the rules refuse a stored binding for {@code rights} (its owner's), or empty when it works. */
    public static Optional<String> refusal(TwinAdapter adapter, Binding binding, Rights rights) {
        for (String entity : binding.entities()) {
            Optional<String> refused = bindRefusal(rights, entity);
            if (refused.isPresent()) return refused;
        }
        Optional<String> problem = adapter.problem(binding);
        if (problem.isPresent()) return problem;
        return presetRefusal(binding, rights);
    }

    // A preset source's own rules (M3-17): the permission, the preset loaded with the stored topic and kind, and the
    // state topic allowed. A source moved by a datapack was resolved before (resolve), so a mismatch here means a preset
    // that went away or a name it no longer takes.
    private static Optional<String> presetRefusal(Binding binding, Rights rights) {
        Optional<Binding.PresetRef> ref = binding.preset();
        if (ref.isEmpty()) return Optional.empty();
        if (!rights.mayBind()) return Optional.of(BIND_PERMISSION);
        Optional<BindingPreset> preset = BindingPresets.get(ref.get().id());
        if (preset.isEmpty()) return Optional.of("no MQTT device preset " + ref.get().id() + " is loaded");
        Optional<String> device = preset.get().deviceProblem(ref.get().device());
        if (device.isPresent()) return device;
        return rights.topics().source(binding.sourceTopic().orElseThrow());
    }

    /**
     * The binding judged again for {@code rights}: refused, or healed; the same binding when the verdict did not change.
     * A preset source first follows the loaded preset: a datapack that moved its state topic or changed its kind moves
     * the binding with it (control stays only while the kind does).
     */
    public static Binding recheck(TwinAdapter adapter, Binding binding, Rights rights) {
        Binding resolved = resolve(binding);
        String refused = refusal(adapter, resolved, rights).orElse(null);
        return Objects.equals(refused, resolved.refused()) ? resolved : resolved.withRefused(refused);
    }

    // A preset source as the loaded preset makes it now; the same binding when nothing moved or the preset is missing.
    private static Binding resolve(Binding binding) {
        Optional<Binding.PresetRef> ref = binding.preset();
        if (ref.isEmpty()) return binding;
        Optional<BindingPreset> preset = BindingPresets.get(ref.get().id());
        if (preset.isEmpty() || preset.get().deviceProblem(ref.get().device()).isPresent()) return binding;
        String topic = preset.get().stateTopic(ref.get().device());
        String kind = preset.get().kind();
        if (topic.equals(binding.sourceTopic().orElseThrow()) && kind.equals(ref.get().kind())) return binding;
        Binding.Settings settings = binding.settings().withPreset(new Binding.PresetRef(ref.get().id(), ref.get().device(), kind));
        if (!kind.equals(ref.get().kind())) settings = settings.withPath(null);
        Binding moved = binding.withSource(new Endpoint.TopicRef(topic, "")).withSettings(settings);
        return kind.equals(ref.get().kind()) ? moved.withControl(binding.control()) : moved.withControl(false);
    }

    /**
     * A binding that came with a placed item, judged for its placer (D13 #6): refused if they may not bind it, and without
     * control unless they may turn it on.
     */
    public static Binding placed(TwinAdapter adapter, Binding binding, Rights placer) {
        Binding checked = recheck(adapter, binding, placer);
        return checked.control() && !placer.mayControl() ? checked.withControl(false) : checked;
    }

    /**
     * What of a device's queued command lands at its attach (D14, the old {@code :2088} guard): all of it for an unbound
     * device. A bound device's mirrored ports follow its source only, so their queued values never land, as Home
     * Assistant's or anyone's; the keys its source does not own (a lamp's effect), which the route table queues for an
     * unloaded bound device as for any device, land as usual (wave 4 review).
     *
     * @param adapter the device type's adapter; null drops everything a bound device queued
     * @return the command that lands, or null when nothing is left
     */
    public static @Nullable JsonObject landingPending(@Nullable Binding binding, @Nullable TwinAdapter adapter, JsonObject pending) {
        if (binding == null || !binding.bound()) return pending;
        if (adapter == null) return null;
        JsonObject left = new JsonObject();
        pending.entrySet().forEach(entry -> {
            if (!adapter.mirroredKeys().contains(entry.getKey())) left.add(entry.getKey(), entry.getValue().deepCopy());
        });
        return left.isEmpty() ? null : left;
    }

    /**
     * Whether a new wire's seed into a device is skipped (D10 "Seeding"): a seed is a {@link CommandSource#BINDING}
     * command no root covers, so one into a mirrored port would forward to Home Assistant just because a wire was made.
     *
     * @param binding the target device's binding; null when unbound
     * @param adapter the target type's adapter; null when it has none
     */
    public static boolean seedSkipped(@Nullable Binding binding, @Nullable TwinAdapter adapter, JsonObject command) {
        return binding != null && binding.bound() && adapter != null && adapter.touchesMirror(command);
    }

    /**
     * The health a device with targets only shows (M3-14, wave 5 finding 3): {@link TwinHealth#REFUSED} while the rules
     * refuse its binding, {@link TwinHealth#NOT_SHARED} while Home Assistant no longer shares an entity a target switches
     * (it left the catalog, D3), healing when it is labelled again; else none (a device that switches reads nothing).
     *
     * @param readable whether the read gate lets an entity through now ({@code TwinRuntime.readable})
     */
    public static @Nullable TwinHealth targetHealth(Binding binding, Predicate<String> readable) {
        if (!binding.active()) return TwinHealth.REFUSED;
        return unsharedTarget(binding, readable).isPresent() ? TwinHealth.NOT_SHARED : null;
    }

    /**
     * The first entity a target of the binding switches that Home Assistant does not share now, if any: a press to it is
     * not sent (it would be answered {@code not_labelled}), and the device shows {@link TwinHealth#NOT_SHARED}.
     */
    public static Optional<String> unsharedTarget(Binding binding, Predicate<String> readable) {
        return binding.targets().values().stream().filter(end -> end instanceof Endpoint.HaEntityRef)
                .map(end -> ((Endpoint.HaEntityRef) end).entityId()).filter(entity -> !readable.test(entity)).findFirst();
    }

    /**
     * Whether a binding makes its device mirrored (D6): it has a source, refused or not. A mirrored device's source owns
     * its state, so it does not power on as its type would when its server starts.
     */
    public static boolean bound(@Nullable Binding binding) {
        return binding != null && binding.bound();
    }

    /** Why a bound device may not join a group (D6), or empty for an unbound one. */
    public static Optional<String> groupProblem(@Nullable Binding binding) {
        return binding != null && binding.bound()
                ? Optional.of("it follows " + binding.describeSource() + " from Home Assistant; group the entities in Home Assistant instead")
                : Optional.empty();
    }
}

package art.rehra.mineqtt.device;

import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.flow.core.PortSpec;
import art.rehra.mineqtt.flow.core.Mapping;
import art.rehra.mineqtt.flow.core.MessageBus;
import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.flow.core.Scope;
import art.rehra.mineqtt.flow.core.Wire;
import art.rehra.mineqtt.twin.core.HaDomains;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * What the flows {@link MessageBus} routes over (M2 plan §3.3): the wires of the {@link DeviceRegistry} and the ports
 * of the device types. A device's ports come from its type, a group's from its member type; a removed device, a group
 * without members' type and a type this MineQTT does not know have none, which the bus counts as unroutable.
 */
final class RegistryWiring implements MessageBus.Wiring {
    /**
     * The out-port of a preset's state topic (M3-17): a {@link Scope#TWIN} source wire carries a fingerprint of each new
     * payload as a JSON object, and the device reads the payload itself through its preset, so no port kind applies.
     */
    static final PortSpec TOPIC_SOURCE = new PortSpec() {
        @Override
        public PortType kind() {
            return PortType.text(HaDomains.MAX_TEXT);
        }

        @Override
        public boolean toggles() {
            return false;
        }
    };

    private final DeviceRegistry registry;
    private final TwinWiring twin;

    /** Without derived wires: the registry's alone. */
    RegistryWiring(DeviceRegistry registry) {
        this(registry, new TwinWiring());
    }

    /** The registry's wires and the ones derived from device bindings ({@link TwinWiring}, M3 plan §3.3). */
    RegistryWiring(DeviceRegistry registry, TwinWiring twin) {
        this.registry = registry;
        this.twin = twin;
    }

    /**
     * An entity's or a preset state topic's derived source wires; a device out-port's registry wires, then its derived
     * target wire.
     */
    @Override
    public List<Wire> from(Endpoint source) {
        if (source instanceof Endpoint.HaEntityRef || source instanceof Endpoint.TopicRef) return twin.from(source);
        List<Wire> registered = registry.wiresFrom(source);
        List<Wire> targets = source instanceof Endpoint.PortRef ? twin.from(source) : List.of();
        if (targets.isEmpty()) return registered;
        List<Wire> wires = new ArrayList<>(registered);
        wires.addAll(targets);
        return wires;
    }

    /**
     * A device's or group's out-port, a Home Assistant entity's ({@link HaDomains.Domain#readPort}), or a preset state
     * topic's, which carries a payload fingerprint ({@link #TOPIC_SOURCE}, M3-17).
     */
    @Override
    public Optional<? extends PortSpec> out(Endpoint source) {
        if (source instanceof Endpoint.HaEntityRef entity) {
            int dot = entity.entityId().indexOf('.');
            return HaDomains.domain(entity.entityId().substring(0, dot)).flatMap(domain -> domain.readPort(entity.port()));
        }
        if (source instanceof Endpoint.TopicRef) return Optional.of(TOPIC_SOURCE);
        return port(registry, source, Port.Direction.OUT);
    }

    /** A device's or group's in-port, or a Home Assistant entity's ({@link HaDomains.Domain#inPort}), a target wire's end. */
    @Override
    public Optional<? extends PortSpec> in(Endpoint target) {
        if (target instanceof Endpoint.HaEntityRef entity) {
            int dot = entity.entityId().indexOf('.');
            return HaDomains.domain(entity.entityId().substring(0, dot)).flatMap(domain -> domain.inPort(entity.port()));
        }
        return port(registry, target, Port.Direction.IN);
    }

    /** The port an endpoint names, of the type of its device or of its group's members; empty when it names none. */
    static Optional<Port> port(DeviceRegistry registry, Endpoint endpoint, Port.Direction direction) {
        return type(registry, endpoint).flatMap(type -> type.port(direction, key(endpoint)));
    }

    /**
     * Why a new wire from {@code from} to {@code to} may not be made (M2 plan §3.2), or empty when it may: both ends must
     * be ports of registered devices (or a group, as the target only), the source an out-port and the target an
     * in-port of another device that the mapping can carry the source's kind to, and no wire may join the two already.
     * Who may make it is the caller's to judge.
     */
    static Optional<String> problem(DeviceRegistry registry, Endpoint from, Endpoint to, Mapping mapping) {
        if (!(from instanceof Endpoint.PortRef source)) return Optional.of("a wire starts at a device's port");
        if (!(to instanceof Endpoint.PortRef) && !(to instanceof Endpoint.GroupPortRef)) return Optional.of("a wire ends at a device's or a group's port");
        if (registry.record(source.node()) == null) return Optional.of("its source device is not registered");
        if (to instanceof Endpoint.PortRef target && registry.record(target.node()) == null) return Optional.of("its target device is not registered");
        if (to instanceof Endpoint.GroupPortRef target && registry.group(target.group()) == null) return Optional.of("its target group is not registered");
        if (source.node().equals(DeviceRegistry.node(to))) return Optional.of("a device is not wired to itself");
        if (type(registry, from).isEmpty() || type(registry, to).isEmpty()) {
            return Optional.of("the type of " + (type(registry, from).isEmpty() ? "its source" : "its target") + " is unknown to this MineQTT, or the group has no members yet");
        }
        Optional<Port> out = port(registry, from, Port.Direction.OUT);
        if (out.isEmpty()) return Optional.of("its source has no out-port " + source.port());
        Optional<Port> in = port(registry, to, Port.Direction.IN);
        if (in.isEmpty()) return Optional.of("its target has no in-port " + key(to));
        if (!mapping.compatible(out.get().kind(), in.get().kind(), in.get().toggles())) {
            return Optional.of(out.get().describe() + " cannot drive " + in.get().describe() + " with " + mapping.name().toLowerCase(Locale.ROOT));
        }
        if (registry.wiresFrom(from).stream().anyMatch(wire -> wire.to().equals(to))) return Optional.of("these ports are wired already");
        return Optional.empty();
    }

    /**
     * The command a message with {@code payload} brings a wire's target {@code to}: its in-port's
     * {@link DeviceType#inPortCommand}, for the device or group it names; empty when it names none whose type is known.
     */
    static Optional<Command> command(DeviceRegistry registry, Endpoint to, JsonElement payload) {
        UUID target = DeviceRegistry.node(to);
        if (target == null) return Optional.empty();
        return type(registry, to).map(type -> new Command(target, type.inPortCommand(key(to), payload)));
    }

    /** The type of an endpoint's device, or of its group's members; empty when it names none or the type is unknown. */
    static Optional<DeviceType> type(DeviceRegistry registry, Endpoint endpoint) {
        return switch (endpoint) {
            case Endpoint.PortRef port -> Optional.ofNullable(registry.record(port.node())).map(record -> DeviceTypes.get(record.type()));
            case Endpoint.GroupPortRef port -> Optional.ofNullable(registry.group(port.group())).map(DeviceGroup::memberType).map(DeviceTypes::get);
            case Endpoint.TopicRef ignoredTopicRef -> Optional.empty();
            case Endpoint.HaEntityRef ignoredHaEntityRef -> Optional.empty();
        };
    }

    /** The port key of an endpoint; empty for one that names no port. */
    static String key(Endpoint endpoint) {
        return switch (endpoint) {
            case Endpoint.PortRef port -> port.port();
            case Endpoint.GroupPortRef port -> port.port();
            case Endpoint.TopicRef ignoredTopicRef -> "";
            case Endpoint.HaEntityRef ignoredHaEntityRef -> "";
        };
    }

    /** A wire's message as the command its target gets: the device or group {@code target}, and the JSON command. */
    record Command(UUID target, JsonObject command) {
    }
}

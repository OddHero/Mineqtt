package art.rehra.mineqtt.device;

import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.flow.core.Mapping;
import art.rehra.mineqtt.flow.core.Scope;
import art.rehra.mineqtt.flow.core.Wire;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The wires derived from device bindings (M3 plan §3.3): a loaded device whose working binding mirrors a Home Assistant
 * entity has one {@link Scope#TWIN} source wire, {@code HaEntityRef(entity, port) → PortRef(device, primary)}, owned by
 * the device's owner, and each target of a working binding one {@link Scope#TWIN} target wire,
 * {@code PortRef(device, outPort) → HaEntityRef(entity, inPort)} (M3-14: the Redstone Input's {@code powered}, the Wall
 * Switch's {@code left}/{@code right}). A device that mirrors an MQTT device preset (M3-17) has a source wire from its
 * state topic instead, {@code TopicRef(topic, "") → PortRef(device, primary)}, which carries a fingerprint of each new
 * payload; the device reads the payload through its preset. They are built when the device attaches or its binding changes and dropped when it detaches, never
 * persisted, and the bus routes over them next to the registry's ({@link RegistryWiring}). Their ids are derived from the
 * device, the port and the entity, so the bus's change filter and loop reports stay with the same wire across rebuilds.
 * Server thread only.
 */
final class TwinWiring {
    // device -> its source wire
    private final Map<UUID, Wire> byDevice = new LinkedHashMap<>();
    // source end (an entity's port or a preset's state topic) -> the wires leaving it, by device, in the order the devices were bound
    private final Map<Endpoint, Map<UUID, Wire>> bySource = new HashMap<>();
    // device -> its target wires by out-port, and each device out-port -> the target wire leaving it
    private final Map<UUID, Map<String, Wire>> targetsByDevice = new LinkedHashMap<>();
    private final Map<Endpoint.PortRef, Wire> byOutPort = new HashMap<>();

    /**
     * The source wire of {@code device}, which mirrors {@code source} into its in-port {@code primary}: always the same
     * id for the same three.
     */
    static Wire sourceWire(UUID device, String primary, Endpoint.HaEntityRef source, UUID owner) {
        UUID id = UUID.nameUUIDFromBytes((device + "|" + primary + "|" + source.entityId() + "|" + source.port()).getBytes(StandardCharsets.UTF_8));
        return new Wire(id, source, Endpoint.port(device, primary), Mapping.FOLLOW, Scope.TWIN, owner, true);
    }

    /**
     * The source wire of {@code device}, which mirrors the MQTT device preset on the state topic {@code source} into its
     * in-port {@code primary} (M3-17): always the same id for the same three.
     */
    static Wire topicSourceWire(UUID device, String primary, Endpoint.TopicRef source, UUID owner) {
        UUID id = UUID.nameUUIDFromBytes(("topic|" + device + "|" + primary + "|" + source.topic()).getBytes(StandardCharsets.UTF_8));
        return new Wire(id, source, Endpoint.port(device, primary), Mapping.FOLLOW, Scope.TWIN, owner, true);
    }

    /**
     * The target wire of {@code device}'s out-port {@code outPort}, which switches {@code target} (M3 plan §3.3):
     * {@code PortRef(device, outPort) → HaEntityRef(entity, inPort)}, {@link Scope#TWIN}, always the same id for the same
     * three. It carries the out-port's values as they are; the type's adapter turns them into calls at the sink.
     */
    static Wire targetWire(UUID device, String outPort, Endpoint.HaEntityRef target, UUID owner) {
        UUID id = UUID.nameUUIDFromBytes(("target|" + device + "|" + outPort + "|" + target.entityId() + "|" + target.port()).getBytes(StandardCharsets.UTF_8));
        return new Wire(id, Endpoint.port(device, outPort), target, Mapping.FOLLOW, Scope.TWIN, owner, true);
    }

    /**
     * Sets the target wires of {@code device}, by out-port; an empty map removes them.
     *
     * @return the wires that went or changed, which the bus forgets
     */
    List<Wire> setTargets(UUID device, Map<String, Wire> wires) {
        Map<String, Wire> before = wires.isEmpty() ? targetsByDevice.remove(device) : targetsByDevice.put(device, Map.copyOf(wires));
        List<Wire> replaced = new ArrayList<>();
        if (before != null) {
            before.forEach((port, wire) -> {
                byOutPort.remove((Endpoint.PortRef) wire.from());
                if (!wire.equals(wires.get(port))) replaced.add(wire);
            });
        }
        wires.values().forEach(wire -> byOutPort.put((Endpoint.PortRef) wire.from(), wire));
        return replaced;
    }

    /** The target wires of {@code device}, by out-port. */
    Map<String, Wire> targets(UUID device) {
        return targetsByDevice.getOrDefault(device, Map.of());
    }

    /** The entities {@code device}'s targets switch now. */
    Set<String> targetEntities(UUID device) {
        Set<String> entities = new LinkedHashSet<>();
        targets(device).values().forEach(wire -> entities.add(((Endpoint.HaEntityRef) wire.to()).entityId()));
        return entities;
    }

    /**
     * Sets the source wire of {@code device}, or removes it for null.
     *
     * @return the wire it replaced, if a different one
     */
    Optional<Wire> set(UUID device, Wire wire) {
        Wire before = wire == null ? byDevice.remove(device) : byDevice.put(device, wire);
        if (before != null) {
            Map<UUID, Wire> wires = bySource.get(before.from());
            if (wires != null) {
                wires.remove(device);
                if (wires.isEmpty()) bySource.remove(before.from());
            }
        }
        if (wire != null) bySource.computeIfAbsent(wire.from(), key -> new LinkedHashMap<>()).put(device, wire);
        return before == null || before.equals(wire) ? Optional.empty() : Optional.of(before);
    }

    /** The source wire of {@code device}, if it mirrors an entity now. */
    Optional<Wire> of(UUID device) {
        return Optional.ofNullable(byDevice.get(device));
    }

    /**
     * The wires leaving {@code source}: an entity's port's or a preset's state topic's source wires, or a device
     * out-port's target wire.
     */
    List<Wire> from(Endpoint source) {
        if (source instanceof Endpoint.PortRef port) {
            Wire target = byOutPort.get(port);
            return target == null ? List.of() : List.of(target);
        }
        if (!(source instanceof Endpoint.HaEntityRef || source instanceof Endpoint.TopicRef)) return List.of();
        Map<UUID, Wire> wires = bySource.get(source);
        return wires == null ? List.of() : List.copyOf(wires.values());
    }

    /** The source ends of {@code entityId} that wires leave now (one per port the devices bound to it read). */
    Set<Endpoint.HaEntityRef> sources(String entityId) {
        Set<Endpoint.HaEntityRef> sources = new LinkedHashSet<>();
        for (Endpoint source : bySource.keySet()) {
            if (source instanceof Endpoint.HaEntityRef entity && entity.entityId().equals(entityId)) sources.add(entity);
        }
        return sources;
    }

    /** The devices that mirror {@code entityId} now. */
    List<UUID> devices(String entityId) {
        List<UUID> devices = new ArrayList<>();
        bySource.forEach((source, wires) -> {
            if (source instanceof Endpoint.HaEntityRef entity && entity.entityId().equals(entityId)) devices.addAll(wires.keySet());
        });
        return devices;
    }

    /**
     * The devices whose targets switch {@code entityId} now: their health follows the entity's share
     * as a mirroring device's does.
     */
    List<UUID> targetDevices(String entityId) {
        List<UUID> devices = new ArrayList<>();
        targetsByDevice.forEach((device, wires) -> {
            if (wires.values().stream().anyMatch(wire -> ((Endpoint.HaEntityRef) wire.to()).entityId().equals(entityId))) devices.add(device);
        });
        return devices;
    }

    /** The devices that mirror the preset state topic {@code topic} now (M3-17). */
    List<UUID> topicDevices(String topic) {
        Map<UUID, Wire> wires = bySource.get(new Endpoint.TopicRef(topic, ""));
        return wires == null ? List.of() : List.copyOf(wires.keySet());
    }

    /** Whether {@code wire} is a derived one, never a registry wire. */
    boolean derived(UUID wire) {
        return byDevice.values().stream().anyMatch(candidate -> candidate.id().equals(wire))
                || byOutPort.values().stream().anyMatch(candidate -> candidate.id().equals(wire));
    }

    /** Every derived wire, by device. */
    Map<UUID, Wire> all() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(byDevice));
    }

    int size() {
        return byDevice.size();
    }

    void clear() {
        byDevice.clear();
        bySource.clear();
        targetsByDevice.clear();
        byOutPort.clear();
    }
}

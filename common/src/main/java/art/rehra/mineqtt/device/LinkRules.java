package art.rehra.mineqtt.device;

import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.flow.core.Mapping;
import art.rehra.mineqtt.flow.core.Wire;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Who may make which wire (M2 plan D9, §3.5), and the wires the Configurator's Link mode offers between two devices
 * (M2-16). A wire must fit the registry ({@link RegistryWiring#problem}: ports that exist and fit the mapping, no
 * self-wire, no second wire between the same two ports, a group only as the target); its owner must be allowed to
 * configure both ends, every member of a group end; and a player owns at most {@code limits.maxWiresPerPlayer} wires.
 * Wires made from the console or a command block belong to {@link Util#NIL_UUID}: they are trusted and count against
 * nobody's cap. Pure: the caller says who may configure what.
 */
public final class LinkRules {
    private LinkRules() {
    }

    /**
     * Why {@code wire} may not be made, or empty when it may.
     *
     * @param configures        whether the wire's owner may configure the device with this id; not asked for a wire of
     *                          {@link Util#NIL_UUID}
     * @param maxWiresPerPlayer the most wires one player may own; 0 for no limit
     */
    public static Optional<String> allowed(DeviceRegistry registry, Wire wire, Predicate<UUID> configures, int maxWiresPerPlayer) {
        Optional<String> problem = RegistryWiring.problem(registry, wire.from(), wire.to(), wire.mapping());
        if (problem.isPresent() || trusted(wire.owner())) return problem;
        for (UUID device : devices(registry, wire)) {
            if (!configures.test(device)) return Optional.of("you may not configure " + name(registry, device));
        }
        int owned = owned(registry, wire.owner());
        if (maxWiresPerPlayer > 0 && owned >= maxWiresPerPlayer) {
            return Optional.of("you own " + owned + " wires, the most this server allows (limits.maxWiresPerPlayer)");
        }
        return Optional.empty();
    }

    /**
     * Whether {@code wire} concerns device {@code device}: it starts or ends there, or ends at the device's group. Such a
     * wire is in the device's links (its Advanced tab), where a player who may configure the device may remove it when
     * {@link #mayRemove} agrees.
     */
    public static boolean touches(DeviceRegistry registry, Wire wire, UUID device) {
        if (endsAt(wire, device)) return true;
        DeviceRecord record = registry.record(device);
        return record != null && record.group() != null && wire.to() instanceof Endpoint.GroupPortRef port && port.group().equals(record.group());
    }

    /**
     * Whether a player who may configure device {@code device} may remove {@code wire} from its links. A wire that starts
     * or ends at the device may go, so an owner can always cut a wire to or from their device, whoever made it. A wire
     * into the device's group drives the other members too, so it only goes with CONFIGURE on its source or on every
     * member: one member's configurer cannot cut the others off a wire they could not have made. Their own device they
     * take out of the group instead.
     *
     * @param configures whether the player may configure the device with this id
     */
    public static boolean mayRemove(DeviceRegistry registry, Wire wire, UUID device, Predicate<UUID> configures) {
        if (!touches(registry, wire, device)) return false;
        if (endsAt(wire, device)) return true;
        if (wire.from() instanceof Endpoint.PortRef source && registry.record(source.node()) != null && configures.test(source.node())) return true;
        DeviceGroup group = wire.to() instanceof Endpoint.GroupPortRef port ? registry.group(port.group()) : null;
        return group != null && group.members().stream().allMatch(configures);
    }

    private static boolean endsAt(Wire wire, UUID device) {
        return device.equals(DeviceRegistry.node(wire.from())) || device.equals(DeviceRegistry.node(wire.to()));
    }

    /** Whether wires of this owner are trusted and uncapped: the console's and command blocks'. */
    public static boolean trusted(UUID owner) {
        return Util.NIL_UUID.equals(owner);
    }

    /** The wires a player owns, which count against {@code limits.maxWiresPerPlayer}. */
    public static int owned(DeviceRegistry registry, UUID owner) {
        return (int) registry.wires().stream().filter(wire -> wire.owner().equals(owner)).count();
    }

    /**
     * The devices a wire touches, whose configurers it concerns: its source, and its target device or every member of
     * its target group. An end that names nothing registered names no device.
     */
    public static List<UUID> devices(DeviceRegistry registry, Wire wire) {
        List<UUID> devices = new ArrayList<>();
        for (Endpoint end : List.of(wire.from(), wire.to())) {
            switch (end) {
                case Endpoint.PortRef port -> {
                    if (registry.record(port.node()) != null) devices.add(port.node());
                }
                case Endpoint.GroupPortRef port -> {
                    DeviceGroup group = registry.group(port.group());
                    if (group != null) devices.addAll(group.members());
                }
                case Endpoint.TopicRef _, Endpoint.HaEntityRef _ -> {
                }
            }
        }
        return devices;
    }

    /**
     * What Link mode offers from device {@code source} to device {@code target} (M2-16's port picker): every pair of an
     * out-port of the source and an in-port of the target, or of the target's group, that some mapping can join. The
     * default pair ({@link DefaultWiring#of}) comes first, the target's own ports before its group's, each in
     * declaration order. A pair already wired says so and offers its wire's mapping first, for the unlink. Empty when
     * either device is not registered or of a type this MineQTT does not know, and from a device to itself.
     */
    public static List<Option> options(DeviceRegistry registry, UUID source, UUID target) {
        DeviceRecord from = registry.record(source);
        DeviceRecord to = registry.record(target);
        if (from == null || to == null || source.equals(target)) return List.of();
        DeviceType fromType = DeviceTypes.get(from.type());
        DeviceType toType = DeviceTypes.get(to.type());
        if (fromType == null || toType == null) return List.of();
        DeviceGroup group = to.group() == null ? null : registry.group(to.group());
        Optional<DefaultWiring.Pair> preferred = DefaultWiring.of(fromType, toType);
        List<Option> options = new ArrayList<>();
        for (Port out : fromType.ports()) {
            if (out.direction() != Port.Direction.OUT) continue;
            for (Port in : toType.ports()) {
                if (in.direction() != Port.Direction.IN) continue;
                boolean isDefault = preferred.filter(pair -> pair.from().equals(out.key()) && pair.to().equals(in.key())).isPresent();
                option(registry, Endpoint.port(source, out.key()), Endpoint.port(target, in.key()), out, in, isDefault).ifPresent(options::add);
            }
        }
        // The target's group is a target too, with the same ports, as the target is one of its members.
        if (group != null && group.memberType() != null) {
            List<Option> groupOptions = options.stream()
                    .flatMap(option -> option(registry, option.from(), Endpoint.groupPort(group.id(), RegistryWiring.key(option.to())),
                            port(fromType, Port.Direction.OUT, option.from()), port(toType, Port.Direction.IN, option.to()), false).stream())
                    .toList();
            options.addAll(groupOptions);
        }
        return Stream.concat(options.stream().filter(Option::isDefault), options.stream().filter(option -> !option.isDefault())).toList();
    }

    // A pair the picker offers: the mappings that join its ports, or empty when none does.
    private static Optional<Option> option(DeviceRegistry registry, Endpoint from, Endpoint to, Port out, Port in, boolean isDefault) {
        List<Mapping> mappings = Stream.of(Mapping.values()).filter(mapping -> mapping.compatible(out.kind(), in.kind(), in.toggles())).toList();
        if (mappings.isEmpty()) return Optional.empty();
        Optional<Wire> wired = registry.wiresFrom(from).stream().filter(wire -> wire.to().equals(to)).findFirst();
        Mapping offered = wired.map(Wire::mapping).orElse(DefaultWiring.mapping(out, in));
        Mapping first = mappings.contains(offered) ? offered : mappings.getFirst();
        List<Mapping> ordered = new ArrayList<>(List.of(first));
        mappings.stream().filter(mapping -> mapping != first).forEach(ordered::add);
        return Optional.of(new Option(from, to, List.copyOf(ordered), wired.map(Wire::id), isDefault));
    }

    private static Port port(DeviceType type, Port.Direction direction, Endpoint endpoint) {
        return type.port(direction, RegistryWiring.key(endpoint)).orElseThrow();
    }

    private static String name(DeviceRegistry registry, UUID device) {
        DeviceRecord record = registry.record(device);
        return record == null ? device.toString() : record.slug();
    }

    /**
     * A wire Link mode offers.
     *
     * @param mappings  the mappings that can join its ports, the one to offer first at the front: the wired one, else
     *                  {@link DefaultWiring#mapping}'s
     * @param wired     the wire that joins these ports already, which the picker offers to remove
     * @param isDefault whether it is {@link DefaultWiring#of}'s pair for the two device types
     */
    public record Option(Endpoint from, Endpoint to, List<Mapping> mappings, Optional<UUID> wired, boolean isDefault) {
    }
}

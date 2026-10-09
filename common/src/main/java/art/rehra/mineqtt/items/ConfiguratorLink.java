package art.rehra.mineqtt.items;

import art.rehra.mineqtt.blocks.entities.DeviceBlockEntity;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.device.LinkRules;
import art.rehra.mineqtt.device.Port;
import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.flow.core.Mapping;
import art.rehra.mineqtt.flow.core.Scope;
import art.rehra.mineqtt.flow.core.Wire;
import art.rehra.mineqtt.network.MineqttNetworking;
import art.rehra.mineqtt.network.MineqttNetworking.LinkChoicePayload;
import art.rehra.mineqtt.network.MineqttNetworking.LinkPickerPayload;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.security.C2SGuard;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Configurator's Link mode (design §7.4, M2 plan §4.2 M2-16): click device A, then device B, and pick the wire in the
 * port picker the server offers ({@link LinkRules#options}, the default pair first); A's port then drives B's, through
 * the server's message bus and without a broker. Picking a pair that is wired already unlinks it, so clicking A, then B
 * again undoes a link; clicking A a second time instead of B stops linking from it. Both clicks and the pick need
 * CONFIGURE on both devices, and the wire counts against its maker's {@code limits.maxWiresPerPlayer}. Every step answers
 * in the action bar, naming the devices and ports. What a player picked first and the offer their picker answers are
 * kept on the server, per player, until they link, stop, change the Configurator's mode or leave. Server thread only.
 */
public final class ConfiguratorLink {
    // The source each player clicked first, and the offer their open picker answers.
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static int nextOffer;

    private record Session(UUID source, @Nullable Offer offer) {
    }

    /**
     * What a player's picker was offered: the wires from {@code source} to the device {@code target} at {@code targetPos}
     * (or its group), in the order the picker lists them.
     */
    private record Offer(int id, UUID source, UUID target, GlobalPos targetPos, List<LinkRules.Option> options) {
    }

    private ConfiguratorLink() {
    }

    /**
     * A click in Link mode on a device the player may use, within reach or from afar: the first picks the source, the
     * next on another device offers the wires to it, one on the source again stops.
     */
    public static void click(ServerPlayer player, DeviceBlockEntity device) {
        Optional<ConfiguratorTarget> clicked = ConfiguratorTarget.of(player, device);
        if (clicked.isEmpty()) return;
        MineqttRuntime runtime = clicked.get().runtime();
        DeviceRecord record = clicked.get().record();
        Session session = SESSIONS.get(player.getUUID());
        DeviceRecord source = session == null ? null : runtime.registry().record(session.source());
        if (session != null && source == null) {
            SESSIONS.remove(player.getUUID());
            tell(player, Component.translatable("mineqtt.configurator.link.gone"));
            return;
        }
        if (source == null) {
            pickSource(player, device, record);
        } else if (source.id().equals(record.id())) {
            SESSIONS.remove(player.getUUID());
            tell(player, Component.translatable("mineqtt.configurator.link.stopped", source.name()));
        } else {
            offer(player, runtime.devices(), runtime, source, record, device);
        }
    }

    private static void pickSource(ServerPlayer player, DeviceBlockEntity device, DeviceRecord record) {
        boolean drives = device.deviceType().ports().stream().anyMatch(port -> port.direction() == Port.Direction.OUT);
        if (!drives) {
            tell(player, Component.translatable("mineqtt.configurator.link.no_outputs", record.name()));
            return;
        }
        SESSIONS.put(player.getUUID(), new Session(record.id(), null));
        tell(player, Component.translatable("mineqtt.configurator.link.source", record.name()));
    }

    // The second click: the wires from the source to the clicked device, for the picker; the source stays picked.
    private static void offer(ServerPlayer player, DeviceRuntime devices, MineqttRuntime runtime, DeviceRecord source, DeviceRecord target,
                              DeviceBlockEntity device) {
        List<LinkRules.Option> options = LinkRules.options(runtime.registry(), source.id(), target.id());
        if (options.isEmpty()) {
            tell(player, Component.translatable("mineqtt.configurator.link.none", source.name(), target.name()));
            return;
        }
        List<LinkRules.Option> offered = options.subList(0, Math.min(options.size(), LinkPickerPayload.MAX_CHOICES));
        Offer offer = new Offer(++nextOffer, source.id(), target.id(), GlobalPos.of(device.getLevel().dimension(), device.getBlockPos()), List.copyOf(offered));
        SESSIONS.put(player.getUUID(), new Session(source.id(), offer));
        List<LinkPickerPayload.Choice> choices = offered.stream().map(option -> choice(devices, runtime, option)).toList();
        MineqttNetworking.sendLinkPicker(player, new LinkPickerPayload(offer.id(), source.name(), target.name(), choices));
    }

    private static LinkPickerPayload.Choice choice(DeviceRuntime devices, MineqttRuntime runtime, LinkRules.Option option) {
        Port out = devices.port(option.from(), Port.Direction.OUT).orElseThrow();
        Port in = devices.port(option.to(), Port.Direction.IN).orElseThrow();
        String group = option.to() instanceof Endpoint.GroupPortRef port ? runtime.registry().group(port.group()).name() : "";
        return LinkPickerPayload.Choice.of(out.key(), out.kind().describe(), in.key(), in.kind().describe(), group, option.mappings(),
                option.wired().isPresent(), option.isDefault());
    }

    /**
     * A click into the air, or on a block that is no device, in Link mode: stops linking from the source picked, or says
     * how Link mode works.
     */
    public static void clickAir(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        DeviceRecord source = session == null ? null : MineqttRuntime.current().map(runtime -> runtime.registry().record(session.source())).orElse(null);
        tell(player, source == null ? Component.translatable("mineqtt.configurator.link.hint")
                : Component.translatable("mineqtt.configurator.link.stopped", source.name()));
    }

    /**
     * {@code LinkChoiceC2S}: the player picked a wire in the picker, to make or to remove. The {@link C2SGuard} lets it
     * through only while it answers the player's open offer, they hold a Configurator in Link mode, still reach the device
     * they clicked second and may configure both devices; making the wire then passes {@link LinkRules#allowed} (a
     * group's members, the cap). Either way the player hears what happened in the action bar, and the source is no
     * longer picked.
     */
    public static void choose(ServerPlayer player, LinkChoicePayload payload) {
        MineqttRuntime runtime = ConfiguratorTarget.runtime(player).orElse(null);
        Session session = SESSIONS.get(player.getUUID());
        Offer offer = session == null || session.offer() == null || session.offer().id() != payload.offer() ? null : session.offer();
        if (runtime == null) return;
        DeviceRuntime devices = runtime.devices();
        Optional<BlockEntity> guarded = C2SGuard.requireOffer(player, payload, ConfiguratorMode.LINK, offer != null, offer == null ? null : offer.targetPos(),
                offer == null ? null : offer.target(), () -> devices.configurer(player).test(offer.source()), () -> devices.configurer(player).test(offer.target()));
        if (guarded.isEmpty() || payload.option() < 0 || payload.option() >= offer.options().size()) return;
        LinkRules.Option option = offer.options().get(payload.option());
        if (!option.mappings().contains(payload.mapping())) return;
        SESSIONS.remove(player.getUUID());
        String from = devices.label(option.from());
        String to = devices.label(option.to());
        if (payload.unlink()) {
            Wire wire = option.wired().map(runtime.registry()::wire).filter(found -> found.from().equals(option.from()) && found.to().equals(option.to())).orElse(null);
            if (wire == null) {
                tell(player, Component.translatable("mineqtt.configurator.link.refused", "that wire is gone"));
                return;
            }
            devices.removeWire(wire.id());
            tell(player, Component.translatable("mineqtt.configurator.link.unlinked", from, to));
            return;
        }
        Wire wire = new Wire(UUID.randomUUID(), option.from(), option.to(), payload.mapping(), Scope.WIRELESS, player.getUUID(), true);
        Optional<String> refusal = devices.addWire(wire, devices.configurer(player));
        if (refusal.isPresent()) {
            tell(player, Component.translatable("mineqtt.configurator.link.refused", refusal.get()));
        } else if (payload.mapping() == Mapping.FOLLOW) {
            tell(player, Component.translatable("mineqtt.configurator.link.linked", from, to));
        } else {
            tell(player, Component.translatable("mineqtt.configurator.link.linked_mapping", from, to, mappingName(payload.mapping())));
        }
    }

    /** How a mapping reads on screens and in the action bar: follows, inverted, toggles. */
    public static Component mappingName(Mapping mapping) {
        return Component.translatable("mineqtt.link.mapping." + mapping.name().toLowerCase(Locale.ROOT));
    }

    /** The player changed the Configurator's mode or left: what they picked is forgotten. */
    public static void forget(UUID player) {
        SESSIONS.remove(player);
    }

    /** The server stops. */
    public static void clear() {
        SESSIONS.clear();
    }

    private static void tell(ServerPlayer player, Component message) {
        ConfiguratorTarget.tell(player, message);
    }
}

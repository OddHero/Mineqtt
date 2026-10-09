package art.rehra.mineqtt.items;

import art.rehra.mineqtt.blocks.entities.DeviceBlockEntity;
import art.rehra.mineqtt.device.DeviceGroup;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.device.GroupRules;
import art.rehra.mineqtt.network.MineqttNetworking;
import art.rehra.mineqtt.network.MineqttNetworking.GroupNamePayload;
import art.rehra.mineqtt.network.MineqttNetworking.GroupPromptPayload;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.security.C2SGuard;
import com.mojang.serialization.DataResult;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Configurator's Group mode (design §7.4, M2 plan §4.2 M2-17): click device A, then device B of the same type, and B
 * joins A's group, taking over the group's state. When A is in no group, the player names a new one in a prompt, and A
 * and B make it, B with A's state. A stays picked, so each further click puts another device into its group; clicking A
 * again, or into the air, stops. Every click needs CONFIGURE on the device, and joining a group CONFIGURE on each of its
 * members ({@link GroupRules}). A device B leaves for A's group goes from its old one, which ends when it has no devices
 * left. Every step answers in the action bar. What a player picked first and the prompt they answer are kept on the
 * server, per player, until they stop, change the Configurator's mode or leave. Server thread only.
 */
public final class ConfiguratorGroup {
    // The device each player groups with, and the prompt their open name screen answers.
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static int nextOffer;

    private record Session(UUID source, @Nullable Prompt prompt) {
    }

    /** A name prompt: the device {@code target} at {@code targetPos} and the source make the group the name is for. */
    private record Prompt(int id, UUID target, GlobalPos targetPos) {
    }

    private ConfiguratorGroup() {
    }

    /**
     * A click in Group mode on a device the player may use, within reach or from afar: the first picks the device to group
     * with, each next one on another device puts it into that device's group, one on the picked device again stops.
     */
    public static void click(ServerPlayer player, DeviceBlockEntity device) {
        ConfiguratorTarget clicked = ConfiguratorTarget.of(player, device).orElse(null);
        if (clicked == null) return;
        DeviceRecord record = clicked.record();
        Session session = SESSIONS.get(player.getUUID());
        DeviceRecord source = session == null ? null : clicked.runtime().registry().record(session.source());
        if (session != null && source == null) {
            SESSIONS.remove(player.getUUID());
            tell(player, Component.translatable("mineqtt.configurator.group.gone"));
            return;
        }
        if (source == null) {
            if (!device.deviceType().groupable()) {
                tell(player, Component.translatable("mineqtt.configurator.group.not_groupable", record.name()));
                return;
            }
            SESSIONS.put(player.getUUID(), new Session(record.id(), null));
            tell(player, Component.translatable("mineqtt.configurator.group.source", record.name()));
        } else if (source.id().equals(record.id())) {
            SESSIONS.remove(player.getUUID());
            tell(player, Component.translatable("mineqtt.configurator.group.stopped", source.name()));
        } else if (!source.type().equals(record.type())) {
            tell(player, Component.translatable("mineqtt.configurator.group.other_type", record.name(), source.name()));
        } else {
            add(player, clicked.devices(), clicked.runtime(), source, record, device);
        }
    }

    // The device clicked joins the source's group; a source in no group asks for the name of a new one first.
    private static void add(ServerPlayer player, DeviceRuntime devices, MineqttRuntime runtime, DeviceRecord source, DeviceRecord target,
                            DeviceBlockEntity device) {
        DeviceGroup group = source.group() == null ? null : runtime.registry().group(source.group());
        if (group == null) {
            Prompt prompt = new Prompt(++nextOffer, target.id(), GlobalPos.of(device.getLevel().dimension(), device.getBlockPos()));
            SESSIONS.put(player.getUUID(), new Session(source.id(), prompt));
            MineqttNetworking.sendGroupPrompt(player, new GroupPromptPayload(prompt.id(), source.name(), target.name()));
            return;
        }
        tellResult(player, devices.changeGroup(target, new GroupRules.Join(group.id()), devices.configurer(player), false));
    }

    /**
     * {@code GroupNameC2S}: the player named the new group in the prompt. The {@link C2SGuard} lets it through only while it
     * answers the player's open prompt, they hold a Configurator in Group mode, still reach the device they clicked second
     * and may configure both devices. The device clicked first then makes the group with that name, and the second joins
     * it; the first stays picked for more devices.
     */
    public static void named(ServerPlayer player, GroupNamePayload payload) {
        MineqttRuntime runtime = ConfiguratorTarget.runtime(player).orElse(null);
        Session session = SESSIONS.get(player.getUUID());
        Prompt prompt = session == null || session.prompt() == null || session.prompt().id() != payload.offer() ? null : session.prompt();
        if (runtime == null) return;
        DeviceRuntime devices = runtime.devices();
        if (C2SGuard.requireOffer(player, payload, ConfiguratorMode.GROUP, prompt != null, prompt == null ? null : prompt.targetPos(),
                prompt == null ? null : prompt.target(), () -> devices.configurer(player).test(session.source()),
                () -> devices.configurer(player).test(prompt.target())).isEmpty()) {
            return;
        }
        SESSIONS.put(player.getUUID(), new Session(session.source(), null));
        DeviceRecord source = runtime.registry().record(session.source());
        DeviceRecord target = runtime.registry().record(prompt.target());
        if (source == null || target == null) {
            tell(player, Component.translatable("mineqtt.configurator.group.gone"));
            return;
        }
        // The name first, so a refused one makes nothing.
        DataResult<String> name = GroupRules.name(payload.name());
        if (name.error().isPresent()) {
            tell(player, Component.translatable("mineqtt.configurator.group.refused", name.error().get().message()));
            return;
        }
        // The source found a group meanwhile (another player, a command): the target joins that one instead.
        DeviceGroup group = source.group() == null ? null : runtime.registry().group(source.group());
        if (group != null) {
            tellResult(player, devices.changeGroup(target, new GroupRules.Join(group.id()), devices.configurer(player), false));
            return;
        }
        DeviceRuntime.Configured created = devices.changeGroup(source, new GroupRules.Create(name.getOrThrow()), devices.configurer(player), false);
        DeviceGroup made = source.group() == null ? null : runtime.registry().group(source.group());
        if (created.result() != DeviceRuntime.Configured.Result.APPLIED || made == null) {
            tellResult(player, created);
            return;
        }
        DeviceRuntime.Configured joined = devices.changeGroup(target, new GroupRules.Join(made.id()), devices.configurer(player), false);
        if (joined.result() == DeviceRuntime.Configured.Result.APPLIED) {
            tell(player, Component.translatable("mineqtt.configurator.group.created", made.name(), source.name(), target.name()));
        } else {
            tellResult(player, joined);
        }
    }

    /**
     * A click into the air, or on a block that is no device, in Group mode: stops grouping with the device picked, or
     * says how Group mode works.
     */
    public static void clickAir(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        DeviceRecord source = session == null ? null : MineqttRuntime.current().map(runtime -> runtime.registry().record(session.source())).orElse(null);
        tell(player, source == null ? Component.translatable("mineqtt.configurator.group.hint")
                : Component.translatable("mineqtt.configurator.group.stopped", source.name()));
    }

    /** The player changed the Configurator's mode or left: what they picked is forgotten. */
    public static void forget(UUID player) {
        SESSIONS.remove(player);
    }

    /** The server stops. */
    public static void clear() {
        SESSIONS.clear();
    }

    private static void tellResult(ServerPlayer player, DeviceRuntime.Configured result) {
        tell(player, Component.translatable(result.result() == DeviceRuntime.Configured.Result.APPLIED
                ? "mineqtt.configurator.group.applied" : "mineqtt.configurator.group.refused", result.detail()));
    }

    private static void tell(ServerPlayer player, Component message) {
        ConfiguratorTarget.tell(player, message);
    }
}

package art.rehra.mineqtt.ui.console;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.entities.TerminalBlockEntity;
import art.rehra.mineqtt.commands.MineQTTCommands;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceRegistry;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.device.DeviceTypes;
import art.rehra.mineqtt.ha.BirthWatcher;
import art.rehra.mineqtt.mqtt.core.ConnectionState;
import art.rehra.mineqtt.mqtt.core.MqttService;
import art.rehra.mineqtt.network.MineqttNetworking;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.security.AclContext;
import art.rehra.mineqtt.security.DeviceAccess;
import art.rehra.mineqtt.telemetry.TelemetrySampler;
import art.rehra.mineqtt.telemetry.core.TelemetrySnapshot;
import art.rehra.mineqtt.telemetry.core.WorldReading;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import dev.architectury.registry.menu.MenuRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.SimpleMenuProvider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The server side of the Bridge Console (M2 plan §4.2 M2-23): builds each viewer's {@link ConsoleView}, opens the screen
 * with it, sends it again every second while the screen is open, and runs the operators' {@link BridgeAction}s as the
 * commands they are. One per {@link MineqttRuntime}; server thread only.
 */
public final class BridgeConsole {
    /** How often an open console is brought up to date. */
    public static final int REFRESH_TICKS = 20;

    private final MineqttRuntime runtime;
    private int ticks;

    public BridgeConsole(MineqttRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Opens the console's screen for a player who may use it, with their view in the open data; it opens on the tab the
     * console last showed. Without a running MineQTT the player is told so instead.
     */
    public static void open(ServerPlayer player, TerminalBlockEntity terminal) {
        Optional<MineqttRuntime> runtime = running(player);
        if (runtime.isEmpty()) {
            player.sendSystemMessage(Component.translatable("mineqtt.console.not_running"), true);
            return;
        }
        BlockPos pos = terminal.getBlockPos();
        ConsoleView view = runtime.get().console().view(player, pos);
        String tabId = TabbedMqttMenu.initialTabId(ConsoleMenu.tabs(), terminal.getLastTabId());
        MenuRegistry.openExtendedMenu(player,
                new SimpleMenuProvider((containerId, inventory, viewer) -> new ConsoleMenu(containerId, inventory, pos, terminal, tabId, view),
                        terminal.getBlockState().getBlock().getName()),
                buf -> TabbedMqttMenu.writeOpenData(buf, pos, view.operator(), tabId, view));
    }

    /**
     * An operator's action from the console's screen, after {@code C2SGuard} let it through: the command it stands for,
     * with the player as its source, so they read its reply and the other operators and the log see it as theirs. Their
     * console then shows the result at once.
     */
    public static void act(ServerPlayer player, BridgeAction action) {
        Optional<MineqttRuntime> running = running(player);
        // The guard asked CONFIGURE, which only operators who may build hold on a console; asked again, as the command asks
        // its op level.
        if (running.isEmpty() || !AclContext.isOp(player.permissions()) || !DeviceAccess.mayBuild(player)) return;
        MineqttRuntime runtime = running.get();
        CommandSourceStack source = player.createCommandSourceStack();
        MineQTT.LOGGER.info("{} ran {} on a Bridge Console", player.getGameProfile().name(), action.key());
        switch (action) {
            case RECONNECT -> MineQTTCommands.reconnect(source, runtime);
            case REPUBLISH -> MineQTTCommands.republish(source, runtime);
            case PRUNE -> MineQTTCommands.prune(source, runtime);
        }
        // A screen click on a paused server's last tick may leave work for the pump (M2 plan D5).
        runtime.workQueued();
        if (player.containerMenu instanceof ConsoleMenu menu) runtime.console().send(player, menu);
    }

    /** {@code SERVER_POST}: every {@value #REFRESH_TICKS} ticks, each open console gets its viewer's view again. */
    public void tick() {
        if (++ticks < REFRESH_TICKS) return;
        ticks = 0;
        for (ServerPlayer player : runtime.server().getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof ConsoleMenu menu) send(player, menu);
        }
    }

    private void send(ServerPlayer player, ConsoleMenu menu) {
        MineqttNetworking.sendConsoleView(player, view(player, menu.blockPos));
    }

    /**
     * The console as a player sees it: the bridge, the world's telemetry (player names only for an operator), the devices
     * and groups they may configure (all for an operator, M2 plan §4.2 M2-23), and whether they may run its actions. A
     * player who may not build (P2), a spectator among them, configures nothing and runs nothing, an operator neither:
     * they see the bridge and the world alone, read-only.
     */
    public ConsoleView view(ServerPlayer player, BlockPos pos) {
        boolean operator = AclContext.isOp(player.permissions()) && DeviceAccess.mayBuild(player);
        DeviceRegistry registry = runtime.registry();
        Predicate<UUID> configures = runtime.devices().configurer(player);
        List<ConsoleView.DeviceRow> devices = ConsoleView.visibleDevices(registry.records(), operator, configures, this::row);
        List<ConsoleView.GroupRow> groups = ConsoleView.visibleGroups(registry.groups(), operator, configures);
        return ConsoleView.of(pos, bridge(), world(operator), devices, groups, operator);
    }

    /**
     * The world as the telemetry reads it now (M4-17), whether or not it is published; the names of the players the name
     * policy allows only for an operator.
     */
    private ConsoleView.World world(boolean operator) {
        TelemetrySampler telemetry = runtime.telemetry();
        WorldReading reading = telemetry.sample();
        TelemetrySnapshot.Details world = TelemetrySnapshot.of(reading, true, 0, 0).details().orElseThrow();
        return ConsoleView.World.of(world.tps(), world.mspt(), world.timeOfDay(), world.day(), world.isDay(), world.weather(), reading.playersOnline(),
                world.players(), operator, telemetry.statusLine(reading));
    }

    private ConsoleView.Bridge bridge() {
        MqttService mqtt = runtime.mqtt();
        MqttService.Stats stats = mqtt.stats();
        BirthWatcher.Heard heard = runtime.birth().heard();
        ConsoleView.HomeAssistant homeAssistant = switch (heard.status()) {
            case NOTHING -> ConsoleView.HomeAssistant.NOT_SEEN;
            case BIRTH -> ConsoleView.HomeAssistant.ONLINE;
            case WILL -> ConsoleView.HomeAssistant.OFFLINE;
        };
        long age = heard.atMillis() < 0 ? -1 : Math.max(0, Util.getMillis() - heard.atMillis());
        boolean dedicated = runtime.server().isDedicatedServer();
        DeviceRuntime.FlowCounters flow = runtime.devices().flowCounters();
        long wireDrops = flow.dropped().values().stream().mapToLong(Long::longValue).sum();
        return ConsoleView.Bridge.of(runtime.topics().root(), mqtt.state(), runtime.broker(), runtime.protocol().configValue(), homeAssistant, age,
                MineQTTConfig.haExpose(dedicated).name(), runtime.discovery().discovered(), serverState(dedicated),
                runtime.pauseOverridden() ? runtime.configuredPauseWhenEmptySeconds() : runtime.pauseWhenEmptySeconds(),
                stats.droppedInbound(), stats.droppedOutbound(), stats.queuedInbound(), stats.outboxSize(), runtime.registry().wires().size(),
                flow.delivered(), wireDrops);
    }

    private ConsoleView.ServerState serverState(boolean dedicated) {
        if (!dedicated) return ConsoleView.ServerState.INTEGRATED;
        if (runtime.pausedWhileEmpty()) return ConsoleView.ServerState.PAUSED;
        if (runtime.pauseOverridden()) return ConsoleView.ServerState.KEPT_TICKING;
        return runtime.pauseWhenEmptySeconds() > 0 ? ConsoleView.ServerState.TICKING : ConsoleView.ServerState.NEVER_PAUSES;
    }

    // A device as the console lists it: faulted, of an unknown type, loaded (with what its Raw Link lacks) or unloaded.
    private ConsoleView.DeviceRow row(DeviceRecord record) {
        DeviceRuntime devices = runtime.devices();
        Optional<DeviceRuntime.Fault> fault = devices.fault(record.pos());
        boolean attached = devices.isAttached(record.id());
        ConsoleView.State state = ConsoleView.state(fault.isPresent(), DeviceTypes.get(record.type()) != null, attached,
                runtime.mqtt().state().status() == ConnectionState.Status.CONNECTED);
        String detail = switch (state) {
            case FAULTED -> fault.get().text();
            case ONLINE, NO_BROKER -> devices.rawProblem(record.id()).orElse("");
            case UNLOADED, UNKNOWN_TYPE -> "";
        };
        return ConsoleView.DeviceRow.of(record.name(), record.slug(), ConsoleView.typeName(record.type()), state, detail, where(record.pos()));
    }

    private static String where(GlobalPos pos) {
        return ConsoleView.dimensionName(pos.dimension().identifier()) + " " + pos.pos().getX() + " " + pos.pos().getY() + " " + pos.pos().getZ();
    }

    private static Optional<MineqttRuntime> running(ServerPlayer player) {
        return MineqttRuntime.current().filter(runtime -> runtime.server() == player.level().getServer());
    }
}

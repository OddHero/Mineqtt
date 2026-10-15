package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.compat.Screens;
import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.blocks.TerminalBlock;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import art.rehra.mineqtt.mqtt.core.ConnectionState;
import art.rehra.mineqtt.network.MineqttNetworking.BridgeActionPayload;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.telemetry.core.Weather;
import art.rehra.mineqtt.ui.console.BridgeAction;
import art.rehra.mineqtt.ui.console.ConsoleBridgeTab;
import art.rehra.mineqtt.ui.console.ConsoleDevicesTab;
import art.rehra.mineqtt.ui.console.ConsoleGroupsTab;
import art.rehra.mineqtt.ui.console.ConsoleMenu;
import art.rehra.mineqtt.ui.console.ConsoleView;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The Bridge Console (M2 plan §4.2 M2-23). Placed by the player, it faces them. Opened by the non-op player, its screen
 * is the short panel titled "Bridge Console" with the Bridge, Devices and Groups tabs: the bridge's connection, broker and
 * topics, the world's tick rate, time and weather, the player online without a name and the telemetry line, no action
 * buttons and "Read only" in the title row; only the player's own lamp, not a private output of nobody, and only the
 * group of their lamps. An action payload the player sends anyway does nothing. Opped and reopened, the player sees every
 * device and group and the actions; a device placed while the screen is open appears within the refresh; Republish
 * answers in chat as the command does, rain shows within the refresh, {@code /mineqtt status} shows the same telemetry
 * line, and with a broker, Home Assistant's birth shows on the Bridge tab.
 */
public final class ConsoleGameTest implements FabricClientGameTest {
    private static final int Y = GameTestSession.GROUND_Y + 1;
    private static final BlockPos STAND = new BlockPos(0, Y, 0);
    // East of the player, so placing it they face east and its screen faces west, away from the default north.
    private static final BlockPos CONSOLE_GROUND = new BlockPos(3, GameTestSession.GROUND_Y, 0);
    private static final BlockPos LAMP = new BlockPos(2, Y, 3);
    private static final BlockPos PRIVATE_OUTPUT = new BlockPos(-2, Y, 3);
    private static final BlockPos LATE_OUTPUT = new BlockPos(-3, Y, 3);
    private static final int TELEPORT_RESEND_TICKS = 25;
    private static final int ANSWER_TICKS = 60;
    // The console's refresh (one second) plus room for the packet.
    private static final int REFRESH_TICKS = 60;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "console", ConsoleGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        // The server sends a teleport again after 20 ticks until it sees the confirmation, with the teleport's rotation.
        session.context().waitTicks(TELEPORT_RESEND_TICKS);
        BlockPos console = session.place(MineqttBlocks.TERMINAL_BLOCK.get(), CONSOLE_GROUND);
        Direction facing = session.onServer(s -> s.overworld().getBlockState(console).getValue(TerminalBlock.FACING));
        session.check(facing == Direction.WEST && facing != MineqttBlocks.TERMINAL_BLOCK.get().defaultBlockState().getValue(TerminalBlock.FACING),
                "the console's screen faces the player who placed it, not its default way (" + facing + ")");
        session.aim(console);
        session.screenshot("console_placed");
        String lamp = setUp(session);
        nonOp(session, console, lamp);
        operator(session, console);
        session.consoleCommand("deop " + session.playerName());
    }

    /** The player's own lamp in a group of its own, and a private output of nobody in another group; returns the lamp's slug. */
    private static String setUp(GameTestSession session) {
        session.place(MineqttBlocks.RGB_LED_BLOCK.get(), LAMP.below());
        session.consoleCommand("setblock " + PRIVATE_OUTPUT.getX() + " " + PRIVATE_OUTPUT.getY() + " " + PRIVATE_OUTPUT.getZ() + " mineqtt:redstone_emitter_block");
        session.awaitServer(s -> slug(s, PRIVATE_OUTPUT) != null && slug(s, LAMP) != null);
        String lamp = session.onServer(s -> slug(s, LAMP));
        String output = session.onServer(s -> slug(s, PRIVATE_OUTPUT));
        session.consoleCommand("mineqtt devices rename " + lamp + " Console Lamp");
        session.consoleCommand("mineqtt devices rename " + output + " Hidden Output");
        session.consoleCommand("mineqtt devices access hidden_output private");
        session.consoleCommand("mineqtt group create Console Lamps");
        session.consoleCommand("mineqtt group add console_lamps console_lamp");
        session.consoleCommand("mineqtt group create Hidden Outputs");
        session.consoleCommand("mineqtt group add hidden_outputs hidden_output");
        session.awaitServer(s -> "console_lamp".equals(slug(s, LAMP)) && "hidden_output".equals(slug(s, PRIVATE_OUTPUT)));
        return "console_lamp";
    }

    /** The non-op player sees the bridge, only what they may configure, and no actions; an action sent anyway does nothing. */
    private static void nonOp(GameTestSession session, BlockPos console, String lamp) {
        session.hold(Items.AIR);
        session.openBlock(console);
        session.check(session.tabIds().equals(List.of(ConsoleBridgeTab.ID, ConsoleDevicesTab.ID, ConsoleGroupsTab.ID)),
                "the console has the Bridge, Devices and Groups tabs (" + session.tabIds() + ")");
        session.check(session.onClient(mc -> GameTestSession.openScreen(mc).getTitle().getString()).equals("Bridge Console"), "its screen is titled Bridge Console");
        ConsoleView view = view(session);
        session.check(!view.operator(), "the view says the player may run no action");
        session.check(view.bridge().topicRoot().equals("mineqtt/" + GameTestSession.BRIDGE_ID), "the Bridge tab names the bridge's topics (" + view.bridge().topicRoot() + ")");
        session.check(session.broker().isPresent() == (view.bridge().connection() == ConnectionState.Status.CONNECTED),
                "and how its connection stands (" + view.bridge().connection() + ")");
        ConsoleView.World world = view.world();
        session.check(world.playersOnline() == 1 && world.players().isEmpty() && world.named() == 0,
                "the Bridge tab counts the player online and names nobody to a player who is no operator (" + world + ")");
        session.check(world.timeOfDay().matches("\\d\\d:\\d\\d") && world.tps() > 0 && world.weather() != null,
                "it shows the tick rate, the time of day and the weather (" + world.tps() + " TPS, " + world.timeOfDay() + ", " + world.weather() + ")");
        session.check(world.telemetry().equals("Telemetry: every 10 s · players: presence by consent (0 sharing) · broadcast off"),
                "and the telemetry line (" + world.telemetry() + ")");
        session.check(session.awaitTitleNote(Optional.of("Read only"), ANSWER_TICKS), "\"Read only\" shows in the title row");
        session.check(!session.onClient(mc -> visible(mc, actionButton(BridgeAction.REPUBLISH))), "no action button shows");
        session.screenshot("console_bridge_read_only");

        session.check(view.devices().stream().map(ConsoleView.DeviceRow::slug).toList().equals(List.of(lamp)) && view.deviceTotal() == 1,
                "the Devices tab lists the player's own lamp alone, not the private output (" + view.devices() + ")");
        session.check(view.devices().getFirst().name().equals("Console Lamp") && view.devices().getFirst().state() != ConsoleView.State.FAULTED,
                "with its name and how it runs (" + view.devices().getFirst() + ")");
        session.check(view.groups().stream().map(ConsoleView.GroupRow::slug).toList().equals(List.of("console_lamps")),
                "the Groups tab lists the group of the lamp, not the output's (" + view.groups() + ")");
        session.check(session.clickTab(ConsoleDevicesTab.ID), "the Devices tab opens");
        session.screenshot("console_devices_own");
        session.check(session.clickTab(ConsoleGroupsTab.ID), "the Groups tab opens");
        session.screenshot("console_groups_own");
        session.check(session.clickTab(ConsoleBridgeTab.ID), "the Bridge tab opens again");

        int mark = ChatLog.mark();
        session.send(new BridgeActionPayload(console, BridgeAction.REPUBLISH));
        Optional<ChatLog.Line> answer = session.awaitChat(mark, line -> line.text().startsWith("Republishing"), ANSWER_TICKS);
        session.check(answer.isEmpty(), "a republish the non-op sends anyway is dropped (" + answer + ")");
        session.closeScreen();
    }

    /**
     * Opped, the player sees every device and group and the actions; a device placed while the screen is open appears;
     * Republish answers as the command does; Home Assistant's birth shows.
     */
    private static void operator(GameTestSession session, BlockPos console) {
        session.consoleCommand("op " + session.playerName());
        session.openBlock(console);
        ConsoleView view = view(session);
        session.check(view.operator(), "the operator's view lets them run the actions");
        session.check(view.devices().stream().map(ConsoleView.DeviceRow::slug).sorted().toList().equals(List.of("console_lamp", "hidden_output")),
                "the operator sees every device (" + view.devices() + ")");
        session.check(view.groups().stream().map(ConsoleView.GroupRow::slug).toList().equals(List.of("console_lamps", "hidden_outputs")),
                "and every group (" + view.groups() + ")");
        session.check(session.awaitTitleNote(Optional.empty(), ANSWER_TICKS), "no note in the title row");
        session.check(session.onClient(mc -> visible(mc, actionButton(BridgeAction.REPUBLISH)) && visible(mc, actionButton(BridgeAction.RECONNECT))
                && visible(mc, actionButton(BridgeAction.PRUNE))), "the Reconnect, Republish and Prune buttons show");

        session.consoleCommand("setblock " + LATE_OUTPUT.getX() + " " + LATE_OUTPUT.getY() + " " + LATE_OUTPUT.getZ() + " mineqtt:redstone_emitter_block");
        session.check(session.await(() -> view(session).deviceTotal() == 3, REFRESH_TICKS),
                "a device placed while the screen is open appears within the refresh (" + view(session).devices() + ")");

        Optional<BrokerConfig> broker = session.broker();
        if (broker.isPresent()) {
            try (BrokerProbe probe = BrokerProbe.connect(broker.get())) {
                probe.publish("homeassistant/status", "online", false);
                session.check(session.await(() -> view(session).bridge().homeAssistant() == ConsoleView.HomeAssistant.ONLINE, REFRESH_TICKS),
                        "Home Assistant's birth shows on the Bridge tab (" + view(session).bridge().homeAssistant() + ")");
            }
        } else {
            session.skip("Home Assistant's birth shows on the Bridge tab", "no broker (" + GameTestSession.BROKER_ENV + " is not set)");
        }
        session.screenshot("console_bridge_operator");

        int mark = ChatLog.mark();
        session.clickWidget(actionButton(BridgeAction.REPUBLISH), "the Republish button");
        Optional<ChatLog.Line> answer = session.awaitChat(mark, line -> line.text().startsWith("Republishing Home Assistant discovery"), ANSWER_TICKS);
        session.check(answer.isPresent(), "Republish answers in chat as /mineqtt discovery republish does (" + answer + ")");
        session.consoleCommand("weather rain");
        session.check(session.await(() -> view(session).world().weather() == Weather.RAIN, REFRESH_TICKS),
                "rain shows on the Bridge tab within the refresh (" + view(session).world().weather() + ")");
        session.check(view(session).world().named() == 0, "nobody shares their name, so even the operator sees none");
        session.consoleCommand("weather clear");
        int statusMark = ChatLog.mark();
        session.playerCommand("mineqtt status");
        Optional<ChatLog.Line> status = session.awaitChat(statusMark, line -> line.text().contains("Telemetry: every 10 s · players: presence by consent"), ANSWER_TICKS);
        session.check(status.isPresent(), "/mineqtt status shows the same telemetry line (" + ChatLog.since(statusMark) + ")");
        session.check(session.clickTab(ConsoleDevicesTab.ID), "the Devices tab opens");
        session.screenshot("console_devices_operator");
        session.closeScreen();
    }

    private static ConsoleView view(GameTestSession session) {
        return session.onClient(mc -> ((ConsoleMenu) GameTestSession.openScreen(mc).getMenu()).view());
    }

    private static Predicate<AbstractWidget> actionButton(BridgeAction action) {
        String label = switch (action) {
            case RECONNECT -> "Reconnect";
            case REPUBLISH -> "Republish";
            case PRUNE -> "Prune";
        };
        return widget -> widget instanceof Button && widget.getMessage().getString().equals(label);
    }

    private static boolean visible(Minecraft mc, Predicate<AbstractWidget> which) {
        return Screens.current() != null && Screens.current().children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                .anyMatch(widget -> widget.visible && which.test(widget));
    }

    // The slug of the device at a position, once it is registered; null before.
    private static @Nullable String slug(MinecraftServer server, BlockPos pos) {
        if (!(server.overworld().getBlockEntity(pos) instanceof DeviceHost host) || host.deviceLink().id() == null) return null;
        DeviceRecord record = MineqttRuntime.of(server).registry().record(host.deviceLink().id());
        return record == null ? null : record.slug();
    }
}

package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.compat.Screens;
import art.rehra.mineqtt.blocks.LightRemoteBlock;
import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.blocks.SmartPlugBlock;
import art.rehra.mineqtt.blocks.TimerBlock;
import art.rehra.mineqtt.blocks.entities.TimerBlockEntity;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.ValueCodecs.LightState;
import art.rehra.mineqtt.device.types.SmartPlug;
import art.rehra.mineqtt.device.types.WallSwitch;
import art.rehra.mineqtt.items.ConfiguratorMode;
import art.rehra.mineqtt.items.CyberdeckDataUtil;
import art.rehra.mineqtt.items.MineqttItems;
import art.rehra.mineqtt.network.MineqttNetworking.BridgeActionPayload;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceActionPayload;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceConfigPayload;
import art.rehra.mineqtt.security.AccessMode;
import art.rehra.mineqtt.ui.console.BridgeAction;
import art.rehra.mineqtt.ui.console.ConsoleMenu;
import art.rehra.mineqtt.ui.console.ConsoleView;
import art.rehra.mineqtt.ui.device.ControlsTab;
import art.rehra.mineqtt.ui.device.DeviceMenu;
import art.rehra.mineqtt.ui.device.LightControl;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * A player who may not build (P2, adventure mode) only uses MineQTT blocks, an operator too. A right-click still
 * switches the Smart Lamp and the Smart Plug and presses the Wall Switch. The lamp's screen shows its Controls tab alone,
 * whose switch works, with a view that names no topic, owner or traffic and neither links nor group page; a rename or an
 * access change sent anyway is dropped without a word. The Redstone Input, which has no controls, opens no screen and says
 * why in the action bar; so do a name tag, a renamed paper and the Configurator in its modes, within reach or from afar. The
 * Alarm Clock, a timer device, opens its Controls tab alone: its status line shows, Trigger now works, and its schedule is
 * read-only, a time sent anyway changing nothing. The Bridge Console lists none of the player's own devices, which it
 * lists in survival mode, and opens read-only for an operator, without its actions, and drops one sent anyway. A game mode change while a screen is open rebuilds it as the new mode offers it, or
 * closes it.
 */
public final class AdventureGameTest implements FabricClientGameTest {
    private static final int Y = GameTestSession.GROUND_Y + 1;
    private static final BlockPos STAND = new BlockPos(0, Y, 0);
    private static final BlockPos LAMP_GROUND = new BlockPos(2, GameTestSession.GROUND_Y, 0);
    private static final BlockPos INPUT_GROUND = new BlockPos(-2, GameTestSession.GROUND_Y, 0);
    private static final BlockPos PLUG_GROUND = new BlockPos(2, GameTestSession.GROUND_Y, -2);
    private static final BlockPos CONSOLE_GROUND = new BlockPos(0, GameTestSession.GROUND_Y, -2);
    private static final BlockPos CLOCK = new BlockPos(-2, Y, -2);
    private static final BlockPos WALL = new BlockPos(0, Y + 1, 3);
    private static final BlockPos SWITCH = WALL.north();
    // Beyond every hand's reach, within the Configurator's, in clear sight.
    private static final BlockPos FAR_OUTPUT = new BlockPos(6, Y, 6);
    // The server sends a teleport again after 20 ticks until it sees the confirmation, with the teleport's rotation.
    private static final int TELEPORT_RESEND_TICKS = 25;
    private static final int ANSWER_TICKS = 40;
    // Ample for a screen to have opened, or a payload to have been answered, if one were to.
    private static final int QUIET_TICKS = 20;
    private static final String USES_ONLY = "Devices can be used but not configured in this game mode";
    private static final DeviceView.Grants USE_ONLY = new DeviceView.Grants(true, false, false, false);

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "adventure", AdventureGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        session.context().waitTicks(TELEPORT_RESEND_TICKS);
        BlockPos lamp = session.place(MineqttBlocks.RGB_LED_BLOCK.get(), LAMP_GROUND);
        BlockPos input = session.place(MineqttBlocks.PUBLISHER_BLOCK.get(), INPUT_GROUND);
        BlockPos plug = session.place(MineqttBlocks.SMART_PLUG_BLOCK.get(), PLUG_GROUND);
        BlockPos console = session.place(MineqttBlocks.TERMINAL_BLOCK.get(), CONSOLE_GROUND);
        setblock(session, CLOCK, "mineqtt:alarm_clock");
        setblock(session, WALL, "minecraft:stone");
        session.hold(MineqttBlocks.LIGHT_REMOTE_BLOCK.get().asItem());
        session.aim(WALL, Direction.NORTH);
        session.pressUse();
        setblock(session, FAR_OUTPUT, "mineqtt:redstone_emitter_block");
        for (BlockPos device : List.of(lamp, input, plug, SWITCH, FAR_OUTPUT, CLOCK)) {
            session.awaitServer(s -> s.overworld().getBlockEntity(device) instanceof DeviceHost host && host.deviceLink().attached());
        }
        session.hold(Items.AIR);
        GameType mode = session.onServer(s -> session.player(s).gameMode());
        session.check(mode == GameType.SURVIVAL, "the player starts in survival mode (" + mode + ")");

        console(session, console, true);
        modeChangeWhileOpen(session, lamp, input);
        gameMode(session, GameType.ADVENTURE);
        console(session, console, false);
        use(session, lamp, plug);
        wallSwitch(session);
        controlsOnly(session, lamp);
        noScreen(session, input);
        timer(session);
        configureRefused(session, lamp, input);
        configurator(session, lamp);
        operator(session, lamp, console);
        gameMode(session, mode);
    }

    /**
     * A screen open while the game mode changes is rebuilt as the new mode offers it: the lamp's six tabs and the Alarm
     * Clock's five become their Controls tab alone and back; the Redstone Input's screen closes, with the reason in the
     * action bar.
     */
    private static void modeChangeWhileOpen(GameTestSession session, BlockPos lamp, BlockPos input) {
        session.openBlock(lamp);
        session.check(session.tabIds().size() == 6 && session.screenView().map(view -> !view.controlsOnly()).orElse(false),
                "in survival the lamp's screen has all six tabs, Twin included (" + session.tabIds() + ")");
        int containerId = session.onClient(mc -> GameTestSession.openScreen(mc).getMenu().containerId);
        gameMode(session, GameType.ADVENTURE);
        session.check(session.awaitClient(mc -> Screens.current() instanceof TabbedMqttScreen screen && screen.getMenu().containerId != containerId
                && screen.getMenu() instanceof DeviceMenu menu && menu.controlsOnly(), ANSWER_TICKS), "switched to adventure mode, the open screen is rebuilt");
        session.check(session.tabIds().equals(List.of(ControlsTab.ID)), "with the Controls tab alone (" + session.tabIds() + ")");
        session.screenshot("adventure_lamp_rebuilt");
        gameMode(session, GameType.SURVIVAL);
        session.check(session.awaitClient(mc -> Screens.current() instanceof TabbedMqttScreen screen && screen.getMenu() instanceof DeviceMenu menu
                && !menu.controlsOnly() && menu.tabs.size() == 6, ANSWER_TICKS), "back in survival mode it has all six tabs again (" + session.tabIds() + ")");
        session.closeScreen();

        // The Alarm Clock is a timer device since M4: General, Controls, Advanced, Access and Diagnostics.
        session.openBlock(CLOCK);
        session.check(session.tabIds().size() == 5, "in survival the Alarm Clock's screen has its five tabs (" + session.tabIds() + ")");
        int clockContainer = session.onClient(mc -> GameTestSession.openScreen(mc).getMenu().containerId);
        gameMode(session, GameType.ADVENTURE);
        session.check(session.awaitClient(mc -> Screens.current() instanceof TabbedMqttScreen screen && screen.getMenu().containerId != clockContainer
                && screen.getMenu() instanceof DeviceMenu menu && menu.controlsOnly(), ANSWER_TICKS), "switched to adventure mode, the Alarm Clock's screen is rebuilt");
        session.check(session.tabIds().equals(List.of(ControlsTab.ID)), "with its Controls tab alone (" + session.tabIds() + ")");
        gameMode(session, GameType.SURVIVAL);
        session.closeScreen();

        session.openBlock(input);
        String name = session.onServer(s -> s.overworld().getBlockState(input).getBlock().getName().getString());
        int mark = ChatLog.mark();
        gameMode(session, GameType.ADVENTURE);
        session.check(session.awaitClient(mc -> Screens.current() == null, ANSWER_TICKS), "the " + name + "'s screen closes in adventure mode");
        String reason = name + " has no controls; devices can only be used in this game mode";
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals(reason), ANSWER_TICKS).isPresent(),
                "and the action bar says why (messages: " + ChatLog.since(mark) + ")");
        session.check(session.onServer(s -> session.player(s).containerMenu == session.player(s).inventoryMenu), "on the server too");
        gameMode(session, GameType.SURVIVAL);
    }

    /** In adventure mode a right-click still switches the lamp and the plug, and opens no screen. */
    private static void use(GameTestSession session, BlockPos lamp, BlockPos plug) {
        session.hold(Items.AIR);
        boolean on = light(session, lamp).on();
        session.aim(lamp);
        session.pressUse();
        session.check(awaitLight(session, lamp, light -> light.on() != on), "a right-click switches the lamp in adventure mode");
        session.context().waitTicks(QUIET_TICKS);
        session.check(noScreen(session), "and opens no screen");
        session.pressUse();
        session.check(awaitLight(session, lamp, light -> light.on() == on), "and back");

        session.aim(plug);
        session.pressUse();
        session.check(session.await(() -> plugPower(session, plug) == SmartPlug.ON_POWER, ANSWER_TICKS), "a right-click switches the plug on");
        session.screenshot("adventure_plug_on");
        session.pressUse();
        session.check(session.await(() -> plugPower(session, plug) == 0, ANSWER_TICKS), "and off");
    }

    /** In adventure mode a click on the Wall Switch still presses the button it hits. */
    private static void wallSwitch(GameTestSession session) {
        // Facing north, the player looks south: the left button is east of the plate's middle.
        Vec3 target = new Vec3(SWITCH.getX() + 10.5 / 16, SWITCH.getY() + 0.5, SWITCH.getZ() + 12.2 / 16);
        session.context().runOnClient(mc -> mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, target));
        session.context().waitFor(mc -> mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(SWITCH));
        session.awaitServer(s -> session.player(s).pick(5, 1.0F, false) instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(SWITCH) && LightRemoteBlock.buttonAt(Direction.NORTH, hit.getLocation().x - Vec3.atCenterOf(SWITCH).x,
                hit.getLocation().z - Vec3.atCenterOf(SWITCH).z) == WallSwitch.Button.LEFT);
        session.pressUse();
        session.check(session.await(() -> session.onServer(s -> s.overworld().getBlockState(SWITCH).getValue(LightRemoteBlock.LEFT)), ANSWER_TICKS),
                "a click presses the Wall Switch's left button in adventure mode");
        session.check(session.await(() -> !session.onServer(s -> s.overworld().getBlockState(SWITCH).getValue(LightRemoteBlock.LEFT)), ANSWER_TICKS),
                "and lets it go");
        session.context().waitTicks(QUIET_TICKS);
        session.check(noScreen(session), "without a screen");
    }

    /**
     * Sneak + right-click opens the lamp's Controls tab alone; its view names no topic, owner or traffic, no links or group
     * page arrive, and its switch works. A rename and an access change sent anyway are dropped without a word.
     */
    private static void controlsOnly(GameTestSession session, BlockPos lamp) {
        session.aim(lamp);
        session.sneakUse(lamp);
        session.context().waitForScreen(TabbedMqttScreen.class);
        session.check(session.tabIds().equals(List.of(ControlsTab.ID)), "the lamp's screen has the Controls tab alone (" + session.tabIds() + ")");
        DeviceView view = session.screenView().orElseThrow();
        session.check(view.controlsOnly() && view.grants().equals(USE_ONLY), "its view is controls-only and grants USE alone (" + view.grants() + ")");
        session.check(view.slug().isEmpty() && view.ownerName().isEmpty() && view.registration().topicRoot().isEmpty() && view.raw().isEmpty()
                        && view.status().isEmpty() && view.diagnostics().id().isEmpty() && view.diagnostics().lastOut().isEmpty(),
                "it names no topic, owner, status or traffic (" + view + ")");
        session.context().waitTicks(QUIET_TICKS);
        session.check(session.onClient(mc -> GameTestSession.openScreen(mc).getMenu() instanceof DeviceMenu menu && menu.links().isEmpty()
                && menu.groups().isEmpty() && "".equals(menu.deviceStatus())), "neither links nor group page nor a status line arrive");
        session.check(session.onClient(mc -> GameTestSession.openScreen(mc).getTitle().getString()).equals(view.name()), "the screen is titled with the lamp's name");
        boolean on = light(session, lamp).on();
        session.clickWidget(isButton("Switch:"), "the lamp's switch");
        session.check(awaitLight(session, lamp, light -> light.on() != on), "the Controls tab's switch works in adventure mode");
        session.screenshot("adventure_lamp_controls");

        String name = session.device(lamp, DeviceLink::name);
        int mark = ChatLog.mark();
        session.send(new DeviceConfigPayload(lamp, "name", StringTag.valueOf("Hacked Lamp")));
        session.send(new DeviceConfigPayload(lamp, "access", StringTag.valueOf("public")));
        session.context().waitTicks(QUIET_TICKS);
        session.check(name.equals(session.device(lamp, DeviceLink::name)) && session.device(lamp, DeviceLink::access) == AccessMode.CLAIM,
                "a rename and an access change sent anyway change nothing");
        session.check(ChatLog.since(mark).stream().noneMatch(line -> line.text().startsWith("Not changed")),
                "the guard drops them without a word (messages: " + ChatLog.since(mark) + ")");
        session.closeScreen();
    }

    /** The Redstone Input has no controls: it opens no screen, and says why. */
    private static void noScreen(GameTestSession session, BlockPos input) {
        session.hold(Items.AIR);
        String name = session.onServer(s -> s.overworld().getBlockState(input).getBlock().getName().getString());
        String reason = name + " has no controls; devices can only be used in this game mode";
        int mark = ChatLog.mark();
        session.aim(input);
        session.pressUse();
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals(reason), ANSWER_TICKS).isPresent(),
                "a right-click on the " + name + " says why in the action bar (messages: " + ChatLog.since(mark) + ")");
        session.context().waitTicks(QUIET_TICKS);
        session.check(noScreen(session), "and opens no screen");
        session.screenshot("adventure_input_refused");
    }

    /**
     * M4-3: a right-click on the Alarm Clock, which has no use of its own, opens its Controls tab alone, with its status
     * line; Trigger now works for a player who may use it, and its schedule is read-only: a time sent anyway changes
     * nothing.
     */
    private static void timer(GameTestSession session) {
        session.hold(Items.AIR);
        session.aim(CLOCK);
        session.pressUse();
        session.context().waitForScreen(TabbedMqttScreen.class);
        session.check(session.tabIds().equals(List.of(ControlsTab.ID)), "the Alarm Clock's screen has the Controls tab alone (" + session.tabIds() + ")");
        DeviceView view = session.screenView().orElseThrow();
        session.check(view.controlsOnly() && view.grants().equals(USE_ONLY) && !view.statusLine().isEmpty(),
                "its view is controls-only, grants USE alone and carries the status line (" + view.statusLine() + ")");
        session.screenshot("adventure_timer_controls");

        session.send(new DeviceActionPayload(CLOCK, "trigger", "PRESS"));
        session.check(session.await(() -> session.onServer(s -> s.overworld().getBlockState(CLOCK).getValue(TimerBlock.POWER)) == 15, ANSWER_TICKS),
                "Trigger now pulses the Alarm Clock for a player who may use it");
        String time = session.onServer(s -> ((TimerBlockEntity) s.overworld().getBlockEntity(CLOCK)).settings().time());
        int mark = ChatLog.mark();
        session.send(new DeviceConfigPayload(CLOCK, "time", StringTag.valueOf("07:15")));
        session.context().waitTicks(QUIET_TICKS);
        session.check(time.equals(session.onServer(s -> ((TimerBlockEntity) s.overworld().getBlockEntity(CLOCK)).settings().time())),
                "a time sent anyway changes nothing (" + time + ")");
        session.check(ChatLog.since(mark).stream().noneMatch(line -> line.text().startsWith("Not changed")),
                "the guard drops it without a word (messages: " + ChatLog.since(mark) + ")");
        session.closeScreen();
    }

    /** A named name tag on the lamp and a renamed paper on the Redstone Input are refused in the action bar. */
    private static void configureRefused(GameTestSession session, BlockPos lamp, BlockPos input) {
        String name = session.device(lamp, DeviceLink::name);
        holdNamed(session, Items.NAME_TAG, "Adventure Lamp");
        int mark = ChatLog.mark();
        session.aim(lamp);
        session.pressUse();
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals(USES_ONLY), ANSWER_TICKS).isPresent(),
                "a name tag on the lamp is refused in the action bar (messages: " + ChatLog.since(mark) + ")");
        session.check(name.equals(session.device(lamp, DeviceLink::name)), "the lamp keeps its name");
        session.screenshot("adventure_name_tag_refused");

        String topic = session.device(input, link -> link.raw() == null ? null : link.raw().topic());
        holdNamed(session, Items.PAPER, "adventure_channel");
        mark = ChatLog.mark();
        session.aim(input);
        session.pressUse();
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals(USES_ONLY), ANSWER_TICKS).isPresent(),
                "a renamed paper on the Redstone Input is refused likewise (messages: " + ChatLog.since(mark) + ")");
        session.check(Objects.equals(topic, session.device(input, link -> link.raw() == null ? null : link.raw().topic())),
                "its Raw Link is unchanged");
        session.hold(Items.AIR);
    }

    /**
     * The Configurator's modes are refused in adventure mode: Link mode on the lamp within reach, Group mode and Configure
     * mode on a Redstone Output beyond reach.
     */
    private static void configurator(GameTestSession session, BlockPos lamp) {
        session.hold(MineqttItems.Cyberdeck.get());
        setMode(session, ConfiguratorMode.LINK);
        int mark = ChatLog.mark();
        session.aim(lamp);
        session.pressUse();
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals(USES_ONLY), ANSWER_TICKS).isPresent(),
                "the Configurator in Link mode is refused on the lamp (messages: " + ChatLog.since(mark) + ")");

        setMode(session, ConfiguratorMode.GROUP);
        mark = ChatLog.mark();
        session.aimFar(FAR_OUTPUT);
        session.pressUse();
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals(USES_ONLY), ANSWER_TICKS).isPresent(),
                "in Group mode on a Redstone Output beyond reach (messages: " + ChatLog.since(mark) + ")");

        setMode(session, ConfiguratorMode.CONFIGURE);
        mark = ChatLog.mark();
        session.aimFar(FAR_OUTPUT);
        session.pressUse();
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals(USES_ONLY), ANSWER_TICKS).isPresent(),
                "and in Configure mode, which opens no screen from afar (messages: " + ChatLog.since(mark) + ")");
        session.context().waitTicks(QUIET_TICKS);
        session.check(noScreen(session), "no screen opened");
        session.hold(Items.AIR);
    }

    /**
     * The Bridge Console of a player who is no operator lists the devices they may configure: their own in survival mode,
     * none in adventure mode, where they may configure nothing.
     */
    private static void console(GameTestSession session, BlockPos console, boolean mayBuild) {
        session.openBlock(console);
        ConsoleView view = session.onClient(mc -> ((ConsoleMenu) GameTestSession.openScreen(mc).getMenu()).view());
        List<String> slugs = view.devices().stream().map(ConsoleView.DeviceRow::slug).toList();
        if (mayBuild) {
            session.check(!view.operator() && slugs.size() >= 3, "in survival mode the Bridge Console lists the player's own devices (" + slugs + ")");
        } else {
            session.check(!view.operator() && slugs.isEmpty() && view.groups().isEmpty(),
                    "in adventure mode it opens read-only and lists none of them, as the player may configure none (" + slugs + ")");
        }
        session.closeScreen();
    }

    /**
     * An operator in adventure mode only uses too: the lamp's screen is its Controls tab alone with USE, and the Bridge
     * Console opens read-only, listing nothing, without its actions; one sent anyway is dropped.
     */
    private static void operator(GameTestSession session, BlockPos lamp, BlockPos console) {
        session.consoleCommand("op " + session.playerName());
        session.aim(lamp);
        session.sneakUse(lamp);
        session.context().waitForScreen(TabbedMqttScreen.class);
        session.check(session.tabIds().equals(List.of(ControlsTab.ID)) && session.screenView().map(view -> view.grants().equals(USE_ONLY)).orElse(false),
                "an operator in adventure mode gets the lamp's Controls tab alone, with USE alone");
        session.closeScreen();

        session.openBlock(console);
        ConsoleView view = session.onClient(mc -> ((ConsoleMenu) GameTestSession.openScreen(mc).getMenu()).view());
        session.check(!view.operator() && view.devices().isEmpty() && view.groups().isEmpty(),
                "the Bridge Console opens for an operator in adventure mode read-only, listing nothing (" + view.devices() + ")");
        session.check(session.awaitTitleNote(Optional.of("Read only"), ANSWER_TICKS), "\"Read only\" shows in its title row");
        session.check(session.onClient(mc -> Screens.current().children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                .noneMatch(widget -> widget.visible && widget instanceof Button && List.of("Reconnect", "Republish", "Prune").contains(widget.getMessage().getString()))),
                "no action button shows");
        session.screenshot("adventure_console_operator");
        int mark = ChatLog.mark();
        session.send(new BridgeActionPayload(console, BridgeAction.REPUBLISH));
        Optional<ChatLog.Line> answer = session.awaitChat(mark, line -> line.text().startsWith("Republishing"), ANSWER_TICKS);
        session.check(answer.isEmpty(), "a republish sent anyway is dropped (" + answer + ")");
        session.closeScreen();
        session.consoleCommand("deop " + session.playerName());
    }

    /** Sets the player's game mode and waits until both sides know whether they may build. */
    private static void gameMode(GameTestSession session, GameType mode) {
        session.consoleCommand("gamemode " + mode.getSerializedName() + " " + session.playerName());
        boolean mayBuild = mode != GameType.ADVENTURE && mode != GameType.SPECTATOR;
        session.awaitServer(s -> session.player(s).gameMode() == mode);
        session.context().waitFor(mc -> mc.player.getAbilities().mayBuild == mayBuild);
    }

    private static void setMode(GameTestSession session, ConfiguratorMode mode) {
        session.onServer(s -> {
            CyberdeckDataUtil.setMode(session.player(s).getMainHandItem(), mode);
            return null;
        });
        session.context().waitFor(mc -> CyberdeckDataUtil.mode(mc.player.getMainHandItem()) == mode);
    }

    private static void holdNamed(GameTestSession session, Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        session.onServer(s -> {
            session.player(s).setItemInHand(InteractionHand.MAIN_HAND, stack.copy());
            return null;
        });
        session.context().waitFor(mc -> mc.player.getMainHandItem().is(item) && mc.player.getMainHandItem().has(DataComponents.CUSTOM_NAME));
    }

    private static void setblock(GameTestSession session, BlockPos pos, String block) {
        session.consoleCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block);
        session.context().waitFor(mc -> !mc.level.getBlockState(pos).isAir());
    }

    private static boolean noScreen(GameTestSession session) {
        return session.onClient(mc -> Screens.current() == null);
    }

    private static Predicate<AbstractWidget> isButton(String prefix) {
        return widget -> widget instanceof Button && widget.getMessage().getString().startsWith(prefix);
    }

    private static int plugPower(GameTestSession session, BlockPos plug) {
        return session.onServer(s -> s.overworld().getBlockState(plug).getValue(SmartPlugBlock.POWER));
    }

    private static LightState light(GameTestSession session, BlockPos lamp) {
        return LightControl.light(session.onServer(s -> {
            StateWriter state = new StateWriter();
            if (s.overworld().getBlockEntity(lamp) instanceof DeviceHost host) host.writeState(state);
            return state.json();
        }));
    }

    private static boolean awaitLight(GameTestSession session, BlockPos lamp, Predicate<LightState> matches) {
        return session.await(() -> matches.test(light(session, lamp)), ANSWER_TICKS);
    }
}

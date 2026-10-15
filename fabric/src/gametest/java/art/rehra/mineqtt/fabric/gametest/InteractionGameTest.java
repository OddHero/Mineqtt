package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.compat.Screens;
import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.blocks.SmartPlugBlock;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.ValueCodecs.LightState;
import art.rehra.mineqtt.device.types.SmartPlug;
import art.rehra.mineqtt.items.ConfiguratorMode;
import art.rehra.mineqtt.items.CyberdeckDataUtil;
import art.rehra.mineqtt.items.MineqttItems;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceActionPayload;
import art.rehra.mineqtt.registry.MineqttDataComponents;
import art.rehra.mineqtt.ui.CyberdeckMenu;
import art.rehra.mineqtt.ui.CyberdeckScreen;
import art.rehra.mineqtt.ui.device.ControlsTab;
import art.rehra.mineqtt.ui.device.DeviceMenu;
import art.rehra.mineqtt.ui.device.LightControl;
import art.rehra.mineqtt.ui.device.widgets.IntSlider;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import art.rehra.mineqtt.ui.framework.tabs.CyberdeckExplorerTab;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The interaction grammar of design §7.4 from a real client (M2 plan §4.2 M2-13). A right-click switches the Smart Lamp
 * on and off, also with a block in hand, and opens no screen; sneak + right-click opens it. The Smart Plug switches the
 * same way (M2-18) and powers the redstone lamp beside it. A sneaking player holding a block places it against a device. A named name tag renames the lamp without being used up, and the action bar names
 * the new state topic. A spectator's right-click only opens the screen, where they change nothing. A Configurator in
 * the off hand opens the lamp's screen within reach as it does from afar. The Configurator steps its mode with sneak + right-click into the air, opens the player's ceiling
 * lamp ten blocks up, whose Controls tab then switches it, and the screen closes once the Configurator leaves the hand or
 * the player moves beyond its range; a block that is no device does not open from afar. Pointing at nothing it opens the
 * explorer, whose listening the server keeps. A Configurator that still carries the Cyberdeck's explorer components loses
 * them the first tick it is carried, and the items its slots held come back.
 */
public final class InteractionGameTest implements FabricClientGameTest {
    private static final BlockPos STAND = new BlockPos(0, GameTestSession.GROUND_Y + 1, 0);
    private static final BlockPos LAMP_GROUND = new BlockPos(2, GameTestSession.GROUND_Y, 0);
    private static final BlockPos OUTPUT_GROUND = new BlockPos(-2, GameTestSession.GROUND_Y, 0);
    // Diagonally in front, clear of the line of sight to the far console.
    private static final BlockPos PLUG_GROUND = new BlockPos(2, GameTestSession.GROUND_Y, -2);
    // Ten blocks above the player's eyes, out of every hand's reach.
    private static final BlockPos CEILING_LAMP = new BlockPos(0, GameTestSession.GROUND_Y + 13, 0);
    private static final BlockPos FAR_CONSOLE = new BlockPos(0, GameTestSession.GROUND_Y + 1, 9);
    private static final BlockPos BEYOND_RANGE = new BlockPos(0, GameTestSession.GROUND_Y + 1, -24);
    private static final int ANSWER_TICKS = 40;
    // Ample for a screen to have opened if one were to.
    private static final int NO_SCREEN_TICKS = 20;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "interaction", InteractionGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        BlockPos lamp = session.place(MineqttBlocks.RGB_LED_BLOCK.get(), LAMP_GROUND);
        session.awaitServer(s -> s.overworld().getBlockEntity(lamp) instanceof DeviceHost host && host.deviceLink().attached());
        use(session, lamp);
        plug(session);
        sneakPlaces(session);
        nameTag(session, lamp);
        spectator(session, lamp);
        offHandConfigurator(session, lamp);
        configurator(session);
        explorer(session);
        deprecatedComponents(session);
    }

    /** A right-click uses the lamp, with an empty hand or a block in it; sneak + right-click opens its screen. */
    private static void use(GameTestSession session, BlockPos lamp) {
        session.hold(Items.AIR);
        session.check(!light(session, lamp).on(), "a placed lamp is off");
        session.aim(lamp);
        session.pressUse();
        session.check(awaitLight(session, lamp, LightState::on), "a right-click switches the lamp on");
        session.context().waitTicks(NO_SCREEN_TICKS);
        session.check(noScreen(session), "and opens no screen");
        session.screenshot("interaction_lamp_on");
        session.pressUse();
        session.check(awaitLight(session, lamp, light -> !light.on()), "a second right-click switches it off");

        session.hold(Items.STONE);
        session.aim(lamp);
        session.pressUse();
        session.check(awaitLight(session, lamp, LightState::on), "a right-click with a block in hand switches it too");
        session.check(session.onServer(s -> s.overworld().getBlockState(lamp.above()).isAir()), "and places nothing");

        session.hold(Items.AIR);
        session.aim(lamp);
        session.sneakUse();
        session.context().waitForScreen(TabbedMqttScreen.class);
        session.check(session.onClient(mc -> Screens.current() instanceof TabbedMqttScreen screen && screen.getMenu() instanceof DeviceMenu
                && lamp.equals(screen.getMenu().blockPos)), "sneak + right-click opens the lamp's screen");
        session.check(light(session, lamp).on(), "without switching the lamp");
        session.closeScreen();
    }

    /**
     * A right-click switches the Smart Plug (M2-18) on and off without a screen, and the redstone lamp beside it follows;
     * sneak + right-click opens the plug's screen, whose Controls tab has its switch, level and power-on behaviour.
     */
    private static void plug(GameTestSession session) {
        BlockPos plug = session.place(MineqttBlocks.SMART_PLUG_BLOCK.get(), PLUG_GROUND);
        session.awaitServer(s -> s.overworld().getBlockEntity(plug) instanceof DeviceHost host && host.deviceLink().attached());
        BlockPos redstoneLamp = plug.east();
        session.consoleCommand("setblock " + redstoneLamp.getX() + " " + redstoneLamp.getY() + " " + redstoneLamp.getZ() + " minecraft:redstone_lamp");
        session.hold(Items.AIR);
        session.check(plugPower(session, plug) == 0, "a placed plug is off");
        session.aim(plug);
        session.pressUse();
        session.check(session.await(() -> plugPower(session, plug) == SmartPlug.ON_POWER, ANSWER_TICKS), "a right-click switches the plug on to full power");
        session.check(session.await(() -> session.onServer(s -> s.overworld().getBlockState(redstoneLamp).getValue(RedstoneLampBlock.LIT)), ANSWER_TICKS),
                "and the redstone lamp beside it lights");
        session.check(plugState(session, plug).get("state").getAsString().equals("ON"), "its state says ON");
        session.context().waitTicks(NO_SCREEN_TICKS);
        session.check(noScreen(session), "and no screen opens");
        session.screenshot("interaction_plug_on");
        session.pressUse();
        session.check(session.await(() -> plugPower(session, plug) == 0, ANSWER_TICKS), "a second right-click switches it off");
        // A redstone lamp goes out a few ticks after its power.
        session.check(session.await(() -> !session.onServer(s -> s.overworld().getBlockState(redstoneLamp).getValue(RedstoneLampBlock.LIT)), ANSWER_TICKS),
                "and the redstone lamp goes out");
        session.awaitClient(mc -> !mc.level.getBlockState(redstoneLamp).getValue(RedstoneLampBlock.LIT), ANSWER_TICKS);
        session.screenshot("interaction_plug_off");

        session.aim(plug);
        session.sneakUse();
        session.context().waitForScreen(TabbedMqttScreen.class);
        session.check(session.onClient(mc -> Screens.current() instanceof TabbedMqttScreen screen && screen.getMenu() instanceof DeviceMenu
                && plug.equals(screen.getMenu().blockPos)), "sneak + right-click opens the plug's screen");
        session.check(session.clickTab(ControlsTab.ID), "the plug's screen has a Controls tab");
        session.check(session.onClient(mc -> hasWidget(mc, Button.class, "Switch: OFF") && hasWidget(mc, IntSlider.class, "Level: 0")
                        && hasWidget(mc, Button.class, "After a restart: As before") && widgetLabels(mc).stream().noneMatch(label -> label.startsWith("Mode:"))),
                "it shows its switch, level and power-on behaviour, and no mode: " + session.onClient(InteractionGameTest::widgetLabels));
        session.screenshot("interaction_plug_controls");
        session.closeScreen();
        session.consoleCommand("setblock " + redstoneLamp.getX() + " " + redstoneLamp.getY() + " " + redstoneLamp.getZ() + " air");
    }

    private static int plugPower(GameTestSession session, BlockPos plug) {
        return session.onServer(s -> s.overworld().getBlockState(plug).getValue(SmartPlugBlock.POWER));
    }

    private static JsonObject plugState(GameTestSession session, BlockPos plug) {
        return session.onServer(s -> {
            StateWriter state = new StateWriter();
            if (s.overworld().getBlockEntity(plug) instanceof DeviceHost host) host.writeState(state);
            return state.json();
        });
    }

    /** A sneaking player places a held block against a device, as against a chest; without sneaking it opens the screen. */
    private static void sneakPlaces(GameTestSession session) {
        BlockPos output = session.place(MineqttBlocks.REDSTONE_EMITTER_BLOCK.get(), OUTPUT_GROUND);
        session.awaitServer(s -> s.overworld().getBlockEntity(output) instanceof DeviceHost host && host.deviceLink().attached());
        session.hold(Items.STONE);
        session.aim(output, Direction.UP);
        session.sneakUse();
        // Crouching lowers the eyes, so the click may meet the output's side rather than its top.
        session.check(session.await(() -> stoneBeside(session, output) != null, ANSWER_TICKS), "sneak + right-click with stone in hand places it against the output");
        session.context().waitTicks(NO_SCREEN_TICKS);
        session.check(noScreen(session), "and opens no screen");
        BlockPos stone = stoneBeside(session, output);
        if (stone != null) {
            session.consoleCommand("setblock " + stone.getX() + " " + stone.getY() + " " + stone.getZ() + " air");
            session.context().waitFor(mc -> mc.level.getBlockState(stone).isAir());
        }

        session.check(session.openBlock(output).getMenu() instanceof DeviceMenu, "a right-click with stone in hand opens the output's screen");
        session.closeScreen();
    }

    private static @Nullable BlockPos stoneBeside(GameTestSession session, BlockPos pos) {
        return session.onServer(s -> Arrays.stream(Direction.values()).map(pos::relative)
                .filter(neighbour -> s.overworld().getBlockState(neighbour).is(Blocks.STONE)).findFirst().orElse(null));
    }

    /** A named name tag renames the lamp and stays; the action bar names the new topic. Sneaking opens the screen instead. */
    private static void nameTag(GameTestSession session, BlockPos lamp) {
        ItemStack tag = new ItemStack(Items.NAME_TAG);
        tag.set(DataComponents.CUSTOM_NAME, Component.literal("Kitchen Lamp"));
        session.onServer(s -> {
            session.player(s).setItemInHand(InteractionHand.MAIN_HAND, tag.copy());
            return null;
        });
        session.context().waitFor(mc -> mc.player.getMainHandItem().is(Items.NAME_TAG));
        boolean on = light(session, lamp).on();
        String topic = MineQTTConfig.baseTopic + "/" + GameTestSession.BRIDGE_ID + "/kitchen_lamp";

        int mark = ChatLog.mark();
        session.aim(lamp);
        session.pressUse();
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Renamed to Kitchen Lamp (" + topic + ")"), ANSWER_TICKS).isPresent(),
                "the name tag renames the lamp, and the action bar names its new topic (messages: " + ChatLog.since(mark) + ")");
        session.screenshot("interaction_name_tag");
        session.check("Kitchen Lamp".equals(session.device(lamp, DeviceLink::name)) && "kitchen_lamp".equals(session.device(lamp, DeviceLink::slug)),
                "the device is called Kitchen Lamp, with the slug kitchen_lamp");
        session.check(session.onServer(s -> session.player(s).getMainHandItem().is(Items.NAME_TAG) && session.player(s).getMainHandItem().getCount() == 1),
                "the name tag is not used up");
        session.check(noScreen(session) && light(session, lamp).on() == on, "no screen opens, and the lamp is not switched");

        mark = ChatLog.mark();
        session.pressUse();
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Already called Kitchen Lamp"), ANSWER_TICKS).isPresent(),
                "used again, it changes nothing (messages: " + ChatLog.since(mark) + ")");

        session.sneakUse();
        session.context().waitForScreen(TabbedMqttScreen.class);
        session.check(session.screenView().map(view -> view.name().equals("Kitchen Lamp")).orElse(false), "sneak + right-click with the name tag opens the screen");
        session.closeScreen();
        session.hold(Items.AIR);
    }

    /**
     * A spectator only looks: their right-click with a named name tag opens the lamp's screen, which neither switches nor
     * renames the lamp and grants them nothing there, and the server drops a command they send from it without a word.
     */
    private static void spectator(GameTestSession session, BlockPos lamp) {
        GameType mode = session.onServer(s -> session.player(s).gameMode());
        session.consoleCommand("gamemode spectator " + session.playerName());
        session.context().waitFor(mc -> mc.player.isSpectator());
        ItemStack tag = new ItemStack(Items.NAME_TAG);
        tag.set(DataComponents.CUSTOM_NAME, Component.literal("Spy Lamp"));
        session.onServer(s -> {
            session.player(s).setItemInHand(InteractionHand.MAIN_HAND, tag.copy());
            return null;
        });
        session.context().waitFor(mc -> mc.player.getMainHandItem().is(Items.NAME_TAG));
        boolean on = light(session, lamp).on();

        int mark = ChatLog.mark();
        session.aim(lamp);
        session.pressUse();
        session.context().waitForScreen(TabbedMqttScreen.class);
        session.check(session.onClient(mc -> Screens.current() instanceof TabbedMqttScreen screen && screen.getMenu() instanceof DeviceMenu
                && lamp.equals(screen.getMenu().blockPos)), "a spectator's right-click with a named name tag opens the lamp's screen");
        session.check(light(session, lamp).on() == on && "Kitchen Lamp".equals(session.device(lamp, DeviceLink::name)),
                "and neither switches nor renames the lamp");
        session.check(session.screenView().map(view -> view.grants().equals(DeviceView.Grants.NONE)).orElse(false),
                "the screen grants a spectator nothing");
        session.send(new DeviceActionPayload(lamp, "state", "TOGGLE"));
        session.context().waitTicks(NO_SCREEN_TICKS);
        // The runtime would refuse it too, with a word; the guard drops it before.
        session.check(light(session, lamp).on() == on && ChatLog.since(mark).stream().noneMatch(line -> line.text().startsWith("Not changed")),
                "the server drops a spectator's switch command without a word (messages: " + ChatLog.since(mark) + ")");
        session.screenshot("interaction_spectator");
        session.closeScreen();

        session.consoleCommand("gamemode " + mode.getSerializedName() + " " + session.playerName());
        session.context().waitFor(mc -> !mc.player.isSpectator());
        session.hold(Items.AIR);
    }

    /**
     * A Configurator in the off hand configures within reach as from afar: with nothing in the main hand a right-click
     * opens the lamp's screen instead of switching it.
     */
    private static void offHandConfigurator(GameTestSession session, BlockPos lamp) {
        session.consoleCommand("item replace entity " + session.playerName() + " weapon.offhand with " + BuiltInRegistries.ITEM.getKey(MineqttItems.Cyberdeck.get()));
        session.context().waitFor(mc -> mc.player.getOffhandItem().is(MineqttItems.Cyberdeck.get()));
        boolean on = light(session, lamp).on();
        session.aim(lamp);
        session.pressUse();
        session.context().waitForScreen(TabbedMqttScreen.class);
        session.check(session.onClient(mc -> Screens.current() instanceof TabbedMqttScreen screen && lamp.equals(screen.getMenu().blockPos)),
                "with the Configurator in the off hand and nothing in the main hand a right-click opens the lamp's screen");
        session.check(light(session, lamp).on() == on, "without switching the lamp");
        session.closeScreen();
        session.consoleCommand("item replace entity " + session.playerName() + " weapon.offhand with air");
        session.context().waitFor(mc -> mc.player.getOffhandItem().isEmpty());
    }

    /**
     * The Configurator: its mode, the player's ceiling lamp opened ten blocks up and switched from its Controls tab, the
     * screen closing without the Configurator in hand or beyond its range, and a block that is no device refused from afar.
     */
    private static void configurator(GameTestSession session) {
        session.consoleCommand("setblock " + CEILING_LAMP.getX() + " " + CEILING_LAMP.getY() + " " + CEILING_LAMP.getZ()
                + " mineqtt:rgb_led_block{mineqtt:{v:2,id:" + intArray(UUID.randomUUID()) + ",type:\"mineqtt:smart_lamp\",owner:"
                + intArray(session.playerId()) + ",ownerName:\"" + session.playerName() + "\"}}");
        session.awaitServer(s -> s.overworld().getBlockEntity(CEILING_LAMP) instanceof DeviceHost host && host.deviceLink().attached());
        session.hold(MineqttItems.Cyberdeck.get());

        int mark = ChatLog.mark();
        session.aimAtSky();
        session.sneakUse();
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Configurator mode: Link"), ANSWER_TICKS).isPresent(),
                "sneak + right-click into the air steps the mode to Link (messages: " + ChatLog.since(mark) + ")");
        session.check(session.onServer(s -> CyberdeckDataUtil.mode(session.player(s).getMainHandItem()) == ConfiguratorMode.LINK), "the Configurator is in Link mode");
        for (String mode : List.of("Group", "Copy-Paste", "Bind", "Room")) {
            mark = ChatLog.mark();
            session.sneakUse();
            session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Configurator mode: " + mode), ANSWER_TICKS).isPresent(),
                    "then to " + mode + " (messages: " + ChatLog.since(mark) + ")");
        }
        mark = ChatLog.mark();
        session.sneakUse();
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Configurator mode: Configure"), ANSWER_TICKS).isPresent(),
                "and on, after the last mode, to Configure again (messages: " + ChatLog.since(mark) + ")");
        session.check(session.onServer(s -> CyberdeckDataUtil.mode(session.player(s).getMainHandItem()) == ConfiguratorMode.CONFIGURE
                && session.player(s).getMainHandItem().has(MineqttDataComponents.CONFIGURATOR_MODE.get())), "the Configurator keeps its mode");
        session.check(noScreen(session), "and opens no screen");

        openFar(session, CEILING_LAMP);
        double distance = session.onServer(s -> Math.sqrt(new AABB(CEILING_LAMP).distanceToSqr(session.player(s).getEyePosition())));
        session.check(session.onClient(mc -> Screens.current() instanceof TabbedMqttScreen screen && screen.getMenu() instanceof DeviceMenu
                && CEILING_LAMP.equals(screen.getMenu().blockPos)), "the Configurator opens the ceiling lamp's screen " + String.format(Locale.ROOT, "%.1f", distance) + " blocks away");
        session.check(session.screenView().map(view -> view.grants().configure()).orElse(false), "with the player's own grants");
        session.check(session.clickTab(ControlsTab.ID), "its Controls tab opens");
        session.clickWidget(isButton("Switch:"), "the lamp's switch");
        session.check(awaitLight(session, CEILING_LAMP, LightState::on), "the switch from ten blocks away turns the ceiling lamp on");
        session.screenshot("interaction_configurator_ceiling");

        session.consoleCommand("item replace entity " + session.playerName() + " weapon.mainhand with air");
        session.check(session.awaitClient(mc -> Screens.current() == null, ANSWER_TICKS), "the screen closes once the Configurator leaves the hand");

        session.hold(MineqttItems.Cyberdeck.get());
        openFar(session, CEILING_LAMP);
        session.teleport(BEYOND_RANGE);
        session.check(session.awaitClient(mc -> Screens.current() == null, ANSWER_TICKS), "and once the player is beyond its range");
        session.teleport(STAND);

        session.consoleCommand("setblock " + FAR_CONSOLE.getX() + " " + FAR_CONSOLE.getY() + " " + FAR_CONSOLE.getZ() + " mineqtt:terminal_block");
        session.context().waitFor(mc -> !mc.level.getBlockState(FAR_CONSOLE).isAir());
        mark = ChatLog.mark();
        session.aimFar(FAR_CONSOLE);
        session.pressUse();
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().endsWith("opens only from close by"), ANSWER_TICKS).isPresent(),
                "a block that is no device does not open from afar (messages: " + ChatLog.since(mark) + ")");
        session.context().waitTicks(NO_SCREEN_TICKS);
        session.check(noScreen(session), "and its screen stays closed");

        // The ceiling lamp would be what the Configurator points at straight up.
        for (BlockPos pos : List.of(CEILING_LAMP, FAR_CONSOLE)) {
            session.consoleCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " air");
            session.context().waitFor(mc -> mc.level.getBlockState(pos).isAir());
        }
    }

    /** Pointing at nothing the Configurator opens the explorer; the server keeps whether it listens, not the item. */
    private static void explorer(GameTestSession session) {
        session.hold(MineqttItems.Cyberdeck.get());
        session.check(session.useHeldItem() instanceof CyberdeckScreen, "pointing at nothing, the Configurator opens its explorer");
        session.check(session.clickTab(CyberdeckExplorerTab.ID), "the Explorer tab opens");
        session.clickButton("Listen: OFF");
        session.check(session.awaitClient(mc -> listenLabel(mc).equals("Listen: ON"), ANSWER_TICKS), "Listen turns on");
        session.closeScreen();
        session.check(session.onServer(s -> !session.player(s).getMainHandItem().has(MineqttDataComponents.LISTENING.get())),
                "the Configurator carries no listening flag");
        session.useHeldItem();
        session.check(session.onClient(mc -> Screens.current() instanceof CyberdeckScreen screen && ((CyberdeckMenu) screen.getMenu()).listening()),
                "reopened, the explorer still listens, as the server says");
        session.check(session.clickTab(CyberdeckExplorerTab.ID) && session.onClient(InteractionGameTest::listenLabel).equals("Listen: ON"),
                "and its button shows it");
        session.clickButton("Listen: ON");
        session.closeScreen();
    }

    /**
     * A Configurator with the Cyberdeck's deprecated explorer components loses them the first tick it is carried, and
     * the items its topic slots held go back into the inventory.
     */
    @SuppressWarnings("deprecation")
    private static void deprecatedComponents(GameTestSession session) {
        int papers = session.onServer(s -> session.player(s).getInventory().countItem(Items.PAPER));
        session.onServer(s -> {
            ItemStack deck = new ItemStack(MineqttItems.Cyberdeck.get());
            deck.set(MineqttDataComponents.LISTENING.get(), true);
            deck.set(MineqttDataComponents.PRIVATE_MODE.get(), true);
            deck.set(MineqttDataComponents.INVENTORY.get(), ItemContainerContents.fromItems(List.of(new ItemStack(Items.PAPER, 3), ItemStack.EMPTY)));
            session.player(s).getInventory().setItem(20, deck);
            return null;
        });
        session.check(session.await(() -> session.onServer(s -> {
            ItemStack deck = session.player(s).getInventory().getItem(20);
            return deck.getItem() == MineqttItems.Cyberdeck.get() && !deck.has(MineqttDataComponents.LISTENING.get())
                    && !deck.has(MineqttDataComponents.PRIVATE_MODE.get()) && !deck.has(MineqttDataComponents.INVENTORY.get());
        }), ANSWER_TICKS), "the deprecated listening, private mode and inventory components are stripped");
        session.check(session.onServer(s -> session.player(s).getInventory().countItem(Items.PAPER)) == papers + 3,
                "the 3 papers its slots held are back in the inventory");
    }

    private static void openFar(GameTestSession session, BlockPos pos) {
        session.aimFar(pos);
        session.pressUse();
        session.context().waitForScreen(TabbedMqttScreen.class);
    }

    private static boolean noScreen(GameTestSession session) {
        return session.onClient(mc -> Screens.current() == null);
    }

    private static String listenLabel(Minecraft mc) {
        return ((TabbedMqttScreen) Screens.current()).children().stream()
                .filter(child -> child instanceof Button button && button.getMessage().getString().startsWith("Listen"))
                .map(child -> ((Button) child).getMessage().getString()).findFirst().orElse("");
    }

    private static boolean hasWidget(Minecraft mc, Class<? extends AbstractWidget> kind, String label) {
        return ((TabbedMqttScreen) Screens.current()).children().stream()
                .anyMatch(child -> kind.isInstance(child) && ((AbstractWidget) child).getMessage().getString().equals(label));
    }

    private static List<String> widgetLabels(Minecraft mc) {
        return ((TabbedMqttScreen) Screens.current()).children().stream().filter(AbstractWidget.class::isInstance)
                .map(child -> ((AbstractWidget) child).getMessage().getString()).toList();
    }

    private static Predicate<AbstractWidget> isButton(String prefix) {
        return widget -> widget instanceof Button && widget.getMessage().getString().startsWith(prefix);
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

    /** A UUID as an SNBT int array, the way block entity data stores it. */
    private static String intArray(UUID id) {
        return Arrays.toString(UUIDUtil.uuidToIntArray(id)).replace("[", "[I;").replace(" ", "");
    }
}

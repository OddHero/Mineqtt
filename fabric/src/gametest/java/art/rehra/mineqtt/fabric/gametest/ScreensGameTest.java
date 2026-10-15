package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.compat.Screens;
import art.rehra.mineqtt.blocks.BaseMqttBlock;
import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.blocks.entities.TerminalBlockEntity;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.fabric.config.MineQTTConfigScreen;
import art.rehra.mineqtt.items.MineqttItems;
import art.rehra.mineqtt.ui.CyberdeckScreen;
import art.rehra.mineqtt.ui.console.ConsoleMenu;
import art.rehra.mineqtt.ui.device.DeviceMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import com.mojang.blaze3d.platform.InputConstants;
import me.shedaniel.clothconfig2.gui.ClothConfigScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.Comparator;
import java.util.List;

/**
 * Every MineQTT screen opens: each block with a screen is placed by the non-op player, opened with the use key, and every
 * one of its tabs is clicked and photographed; then the Cyberdeck's screen the same way, and finally each category of the
 * config screen. A device's screen has no inventory and is titled with the device's name, the Bridge Console's has none
 * either and is titled with the block's name; every other screen keeps the player's inventory. A client crash while
 * building or drawing a screen fails the run.
 */
public final class ScreensGameTest implements FabricClientGameTest {
    /** Blocks are placed in a row along x, this far apart, with the player standing this far north of each. */
    private static final int SPACING = 2;
    private static final int STAND_OFF = 3;
    /** Mouse wheel notches that scroll any config category to its end, and the ticks its smooth scrolling takes. */
    private static final double CONFIG_SCROLL = 100;
    private static final int CONFIG_SCROLL_TICKS = 20;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "screens", ScreensGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.consoleCommand("gamemode creative " + session.playerName());
        List<Block> blocks = BuiltInRegistries.BLOCK.stream()
                .filter(block -> block instanceof BaseMqttBlock)
                .sorted(Comparator.comparing(block -> BuiltInRegistries.BLOCK.getKey(block).toString()))
                .toList();
        session.check(!blocks.isEmpty(), "MineQTT registers blocks with screens");

        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            BlockPos ground = new BlockPos(i * SPACING, GameTestSession.GROUND_Y, STAND_OFF);
            session.teleport(new BlockPos(ground.getX(), GameTestSession.GROUND_Y + 1, 0));
            BlockPos pos = session.place(block, ground);
            // A device keeps its owner in its link, a block that is no device in its own data; the Bridge Console has none.
            Kind kind = session.onServer(s -> switch (s.overworld().getBlockEntity(pos)) {
                case DeviceHost host -> Kind.DEVICE;
                case TerminalBlockEntity terminal -> Kind.CONSOLE;
                case null, default -> Kind.SLOTS;
            });
            String owner = session.onServer(s -> switch (s.overworld().getBlockEntity(pos)) {
                case DeviceHost host -> host.deviceLink().ownerName();
                case BaseMqttBlockEntity mqtt -> mqtt.getOwnerName();
                case null, default -> null;
            });
            session.check(kind == Kind.CONSOLE ? owner == null : session.playerName().equals(owner),
                    kind == Kind.CONSOLE ? id + " is placed and has no owner" : id + " is placed and owned by the player");
            session.openBlock(pos);
            layout(session, id.getPath(), kind);
            visitTabs(session, id.getPath());
        }

        session.hold(MineqttItems.Cyberdeck.get());
        TabbedMqttScreen screen = session.useHeldItem();
        session.check(screen instanceof CyberdeckScreen, "the Cyberdeck opens its screen");
        layout(session, "cyberdeck", Kind.SLOTS);
        visitTabs(session, "cyberdeck");

        configScreen(session);
    }

    /**
     * Opens the config screen ModMenu shows, photographs each category at its top and end, and leaves it with Escape
     * without saving. Its category tabs are not renderable widgets and do not all fit the window, so a category is
     * selected the way its tab button does it.
     */
    private static void configScreen(GameTestSession session) {
        ClientGameTestContext context = session.context();
        context.setScreen(() -> MineQTTConfigScreen.createConfigScreen(null));
        context.waitForScreen(ClothConfigScreen.class);
        int categories = session.onClient(mc -> configScreen(mc).getCategorizedEntries().size());
        session.check(categories > 0, "the config screen has categories");
        for (int i = 0; i < categories; i++) {
            int index = i;
            Category category = session.onClient(mc -> {
                ClothConfigScreen config = configScreen(mc);
                config.selectedCategoryIndex = index;
                //? if >=1.21.11 {
                config.init(config.width, config.height);
                //?} else {
                /*config.init(mc, config.width, config.height);
                *///?}
                Component selected = config.getSelectedCategory();
                return new Category(selected.getString(), config.getCategorizedEntries().get(selected).size());
            });
            session.check(category.entries() > 0, "the config screen shows its " + category.name() + " category");
            session.screenshot("config_" + index);
            if (scrollConfigToEnd(session)) session.screenshot("config_" + index + "_end");
        }
        session.check(!session.onClient(mc -> configScreen(mc).isEdited()), "opening the config screen edits nothing");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitForScreen(null);
    }

    /** Scrolls the open config category to its end with the mouse wheel; returns whether it had more entries to show. */
    private static boolean scrollConfigToEnd(GameTestSession session) {
        ClientGameTestContext context = session.context();
        // Over the entry labels on the left, which leave the wheel to the list.
        double[] cursor = session.onClient(mc -> new double[]{mc.getWindow().getScreenWidth() / 4.0, mc.getWindow().getScreenHeight() / 2.0});
        context.getInput().setCursorPos(cursor[0], cursor[1]);
        context.getInput().scroll(-CONFIG_SCROLL);
        context.waitTicks(CONFIG_SCROLL_TICKS);
        return session.onClient(mc -> configScreen(mc).listWidget.getScroll() > 0);
    }

    private static ClothConfigScreen configScreen(Minecraft mc) {
        if (Screens.current() instanceof ClothConfigScreen screen) return screen;
        throw new AssertionError("The config screen is not open: " + Screens.current());
    }

    private record Category(String name, int entries) {
    }

    /** What a screen is: a device's, the Bridge Console's, or one with slots and the player's inventory. */
    private enum Kind {
        DEVICE,
        CONSOLE,
        SLOTS
    }

    /**
     * The open screen's shape: a device's has no slots, not even the player's inventory, the short panel, and the device's
     * name as its title; the Bridge Console's is as short and has no slots either; any other keeps the inventory below its
     * tabs on the full panel.
     */
    private static void layout(GameTestSession session, String name, Kind kind) {
        String shape = session.onClient(mc -> {
            TabbedMqttScreen screen = GameTestSession.openScreen(mc);
            TabbedMqttMenu menu = screen.getMenu();
            if (kind == Kind.SLOTS) {
                return menu.hasPlayerInventory() && menu.slots.size() >= 36 && menu.guiHeight() == TabbedMqttMenu.GUI_HEIGHT ? "" : describe(menu);
            }
            if (kind == Kind.CONSOLE) {
                return menu instanceof ConsoleMenu && !menu.hasPlayerInventory() && menu.slots.isEmpty()
                        && menu.guiHeight() == TabbedMqttMenu.GUI_HEIGHT_NO_INVENTORY && screen.getTitle().getString().equals("Bridge Console")
                        ? "" : describe(menu) + ", titled " + screen.getTitle().getString();
            }
            String viewName = menu.deviceView().map(DeviceView::name).orElse(null);
            return menu instanceof DeviceMenu deviceMenu && !menu.hasPlayerInventory() && menu.slots.isEmpty()
                    && menu.guiHeight() == TabbedMqttMenu.GUI_HEIGHT_NO_INVENTORY && viewName != null
                    && deviceMenu.title().getString().equals(viewName) && screen.getTitle().getString().equals(viewName)
                    ? "" : describe(menu) + ", titled " + screen.getTitle().getString() + " for " + viewName;
        });
        session.check(shape.isEmpty(), switch (kind) {
            case DEVICE -> name + "'s screen has no inventory, the short panel and the device's name as its title (" + shape + ")";
            case CONSOLE -> name + "'s screen has no inventory, the short panel and the console's name as its title (" + shape + ")";
            case SLOTS -> name + "'s screen keeps the player's inventory (" + shape + ")";
        });
    }

    private static String describe(TabbedMqttMenu menu) {
        return menu.getClass().getSimpleName() + " with " + menu.slots.size() + " slots, inventory " + menu.hasPlayerInventory() + ", height " + menu.guiHeight();
    }

    /** Clicks through every tab of the open screen, photographs each one, and closes the screen. */
    private static void visitTabs(GameTestSession session, String name) {
        List<String> tabs = session.tabIds();
        session.check(!tabs.isEmpty(), name + " has tabs");
        for (String tab : tabs) {
            session.check(session.clickTab(tab), name + " shows its " + tab + " tab");
            session.screenshot(name + "_" + tab);
        }
        session.closeScreen();
    }
}

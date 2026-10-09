package art.rehra.mineqtt.ui.console;

import art.rehra.mineqtt.ui.MineqttMenuTypes;
import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The menu of a Bridge Console's screen (M2 plan §4.2 M2-23): the shared {@link MineqttMenuTypes#MQTT_TABBED_MENU} with
 * the console's three tabs, no slots and no player inventory, so its screen is the short panel of a device's. What it
 * shows comes from the {@link ConsoleView} in the open data and the views the server sends every second while it is open.
 * The player may "configure" it when they may run its actions, which only operators may.
 */
public class ConsoleMenu extends TabbedMqttMenu {

    private final @Nullable BlockEntity terminal;
    private ConsoleView view;
    // When the current view arrived (client, Util.getMillis): the ages it gives grow from there.
    private long viewArrived;

    /**
     * @param terminal the console's block entity on the server; null on the client, which asks nothing of it
     * @param view     the view the screen opens with
     */
    public ConsoleMenu(int containerId, Inventory playerInventory, BlockPos pos, @Nullable BlockEntity terminal, @Nullable String initialTabId,
                       ConsoleView view) {
        super(MineqttMenuTypes.MQTT_TABBED_MENU.get(), containerId, playerInventory, new SimpleContainer(0), tabs(), playerInventory.player, pos,
                initialTabId, view.operator(), null, false);
        this.terminal = terminal;
        setView(view);
    }

    /** The console's tabs, in their order. */
    public static List<MqttTab> tabs() {
        return List.of(new ConsoleBridgeTab(), new ConsoleDevicesTab(), new ConsoleGroupsTab());
    }

    /** The console as the server last sent it for this player. */
    public ConsoleView view() {
        return view;
    }

    /** Client: the server sent the console again. */
    public void setView(ConsoleView view) {
        this.view = view;
        viewArrived = Util.getMillis();
    }

    /**
     * How long ago, on this client's clock, the current view arrived (client): an age in the view plus this is the age
     * now, whatever the server's clock says.
     */
    public long viewAgeMillis() {
        return Math.max(0, Util.getMillis() - viewArrived);
    }

    @Override
    public boolean isFor(BlockEntity blockEntity) {
        return terminal == blockEntity;
    }

    /**
     * Valid while the console is where it was and the player stands within reach, with vanilla's slack. Only the server
     * asks; the client's menu has no block entity.
     */
    @Override
    public boolean stillValid(Player player) {
        return terminal != null && Container.stillValidBlockEntity(terminal, player);
    }
}

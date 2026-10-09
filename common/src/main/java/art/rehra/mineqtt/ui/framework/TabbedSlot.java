package art.rehra.mineqtt.ui.framework;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A {@link Slot} bound to a specific tab id; only active (rendered + interactive)
 * while the menu's active tab matches. Its items configure the block, so they only
 * move for a player whose menu is {@link TabbedMqttMenu#configurable()}; the menu remembers who moved them
 * ({@link TabbedMqttMenu#clicked}).
 */
public class TabbedSlot extends Slot {

    private final TabbedMqttMenu menu;
    private final String tabId;

    public TabbedSlot(TabbedMqttMenu menu, String tabId, Container container, int slot, int x, int y) {
        super(container, slot, x, y);
        this.menu = menu;
        this.tabId = tabId;
    }

    public String tabId() {
        return tabId;
    }

    @Override
    public boolean isActive() {
        return tabId.equals(menu.getActiveTabId());
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return menu.configurable() && super.mayPlace(stack);
    }

    @Override
    public boolean mayPickup(Player player) {
        return menu.configurable() && super.mayPickup(player);
    }
}

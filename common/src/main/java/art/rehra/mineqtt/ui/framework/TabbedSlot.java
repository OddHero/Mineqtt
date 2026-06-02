package art.rehra.mineqtt.ui.framework;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;

/**
 * A {@link Slot} bound to a specific tab id; only active (rendered + interactive)
 * while the menu's active tab matches.
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
}

package art.rehra.mineqtt.ui.framework.tabs;

import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class CyberdeckPublishTab extends MqttTab {
    public static final String ID = "cyberdeck_publish";

    public CyberdeckPublishTab() {
        super(ID);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.PAPER);
    }

    @Override
    public Component title() {
        return Component.literal("Publish");
    }

    @Override
    public void buildSlots(TabbedMqttMenu menu, Container container) {
        if (container.getContainerSize() >= 4) {
            // Reusing same index as PublisherValuesTab for consistency
            // Publisher block uses index 2 and 3 for ON/OFF payloads (items).
            // Cyberdeck uses index 2 for the item to be published.
            menu.addTabSlot(id(), container, 2, 80, 36); // Item payload slot
        }
    }
}

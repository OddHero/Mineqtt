package art.rehra.mineqtt.ui.framework.tabs;

import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Tab contributed by publisher-style blocks (RedstoneEmitter, Publisher): exposes the
 * two payload slots (ON value at container index 2, OFF value at container index 3).
 */
public class PublisherValuesTab extends MqttTab {

    public static final String ID = "publisher_values";

    public PublisherValuesTab() {
        super(ID);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.REDSTONE_TORCH);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.publisher_values");
    }

    @Override
    public void buildSlots(TabbedMqttMenu menu, Container container) {
        if (container.getContainerSize() >= 4) {
            menu.addTabSlot(id(), container, 2, 62, 36); // ON  value
            menu.addTabSlot(id(), container, 3, 98, 36); // OFF value
        }
    }
}

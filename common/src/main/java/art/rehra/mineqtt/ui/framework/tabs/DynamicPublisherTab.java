package art.rehra.mineqtt.ui.framework.tabs;

import art.rehra.mineqtt.ui.framework.MqttTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Tab for the Dynamic Publisher block: displays the data type selector and publish status.
 */
public class DynamicPublisherTab extends MqttTab {

    public static final String ID = "dynamic_publisher";

    public DynamicPublisherTab() {
        super(ID);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.CLOCK);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.dynamic_publisher");
    }
}

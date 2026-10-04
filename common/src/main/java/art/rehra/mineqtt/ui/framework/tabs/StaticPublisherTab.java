package art.rehra.mineqtt.ui.framework.tabs;

import art.rehra.mineqtt.ui.framework.MqttTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Tab for the Static Publisher block: displays the static message input and publish status.
 */
public class StaticPublisherTab extends MqttTab {

    public static final String ID = "static_publisher";

    public StaticPublisherTab() {
        super(ID);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.PAPER);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.static_publisher");
    }
}

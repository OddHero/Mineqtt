package art.rehra.mineqtt.ui.framework.tabs;

import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Humidity sensor tab: displays the current humidity reading.
 */
public class HumiditySensorTab extends MqttTab {

    public static final String ID = "humidity_sensor";

    public HumiditySensorTab() {
        super(ID);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.BUCKET);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.humidity_sensor");
    }

    @Override
    public void buildSlots(TabbedMqttMenu menu, Container container) {
        // No additional slots needed - humidity is displayed in the status area
    }
}

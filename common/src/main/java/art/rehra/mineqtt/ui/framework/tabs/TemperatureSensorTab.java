package art.rehra.mineqtt.ui.framework.tabs;

import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Temperature sensor tab: displays the current temperature reading.
 */
public class TemperatureSensorTab extends MqttTab {

    public static final String ID = "temperature_sensor";

    public TemperatureSensorTab() {
        super(ID);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.CAMPFIRE);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.temperature_sensor");
    }

    @Override
    public void buildSlots(TabbedMqttMenu menu, Container container) {
        // No additional slots needed - temperature is displayed in the status area
    }
}

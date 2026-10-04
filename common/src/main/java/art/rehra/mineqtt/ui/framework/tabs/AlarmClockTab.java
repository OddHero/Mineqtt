package art.rehra.mineqtt.ui.framework.tabs;

import art.rehra.mineqtt.ui.framework.MqttTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Tab for the Alarm Clock block: displays the list of configured alarms,
 * time input, and add/remove controls.
 */
public class AlarmClockTab extends MqttTab {

    public static final String ID = "alarm_clock";

    public AlarmClockTab() {
        super(ID);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.CLOCK);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.alarm_clock");
    }
}

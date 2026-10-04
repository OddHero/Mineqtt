package art.rehra.mineqtt.ui.framework.tabs;

import art.rehra.mineqtt.ui.framework.MqttTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Tab for the Interval Timer block: displays the interval input, unit selector,
 * and timer status information.
 */
public class IntervalTimerTab extends MqttTab {

    public static final String ID = "interval_timer";

    public IntervalTimerTab() {
        super(ID);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.CLOCK);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.interval_timer");
    }
}

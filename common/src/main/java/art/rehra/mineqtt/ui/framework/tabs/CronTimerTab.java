package art.rehra.mineqtt.ui.framework.tabs;

import art.rehra.mineqtt.ui.framework.MqttTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Tab for the Cron Timer block: displays the cron expression input and schedule status.
 */
public class CronTimerTab extends MqttTab {

    public static final String ID = "cron_timer";

    public CronTimerTab() {
        super(ID);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.CLOCK);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.cron_timer");
    }
}

package art.rehra.mineqtt.ui.framework.tabs;

import art.rehra.mineqtt.ui.framework.MqttTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Tab for the Game Time Timer block: displays trigger mode selector,
 * custom time configuration, and current game time information.
 */
public class GameTimeTimerTab extends MqttTab {

    public static final String ID = "game_time_timer";

    public GameTimeTimerTab() {
        super(ID);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.CLOCK);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.game_time_timer");
    }
}

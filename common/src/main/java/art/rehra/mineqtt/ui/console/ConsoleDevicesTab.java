package art.rehra.mineqtt.ui.console;

import art.rehra.mineqtt.ui.framework.MqttTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Bridge Console's device list: every device the player may configure (all for an operator), each with its type,
 * how it runs and what is wrong with it ({@link ConsoleView.DeviceRow}). No slots.
 */
public class ConsoleDevicesTab extends MqttTab {

    public static final String ID = "console_devices";

    public ConsoleDevicesTab() {
        super(ID);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.REDSTONE_TORCH);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.console_devices");
    }
}

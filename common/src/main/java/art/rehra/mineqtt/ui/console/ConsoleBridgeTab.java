package art.rehra.mineqtt.ui.console;

import art.rehra.mineqtt.ui.framework.MqttTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Bridge Console's first tab: the broker connection, the bridge's topics, Home Assistant's birth, whether the server
 * pauses, the drop counters and the wires ({@link ConsoleView.Bridge}), with the operators' actions at its foot. No slots.
 */
public class ConsoleBridgeTab extends MqttTab {

    public static final String ID = "console_bridge";

    public ConsoleBridgeTab() {
        super(ID);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.COMPARATOR);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.console_bridge");
    }
}

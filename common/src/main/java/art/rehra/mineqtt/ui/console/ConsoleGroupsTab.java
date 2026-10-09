package art.rehra.mineqtt.ui.console;

import art.rehra.mineqtt.ui.framework.MqttTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Bridge Console's group list: every group the player manages (all for an operator), with its member type, its
 * member count and whether Home Assistant discovers it ({@link ConsoleView.GroupRow}). No slots.
 */
public class ConsoleGroupsTab extends MqttTab {

    public static final String ID = "console_groups";

    public ConsoleGroupsTab() {
        super(ID);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.LEAD);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.console_groups");
    }
}

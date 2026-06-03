package art.rehra.mineqtt.ui.framework.tabs;

import art.rehra.mineqtt.ui.framework.MqttTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class CyberdeckExplorerTab extends MqttTab {
    public static final String ID = "cyberdeck_explorer";

    public CyberdeckExplorerTab() {
        super(ID);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.COMPASS);
    }

    @Override
    public Component title() {
        return Component.literal("Explorer");
    }
}

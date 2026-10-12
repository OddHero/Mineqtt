package art.rehra.mineqtt.ui.framework.tabs;

import art.rehra.mineqtt.ui.framework.MqttTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Configurator explorer's Traffic tab: the payloads of the topics it listens to. Its id stays the one it had as the
 * explorer's "Explorer" tab, before the Devices tab came first (M4 plan D13).
 */
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
        return Component.translatable("mineqtt.tab.cyberdeck_explorer");
    }
}

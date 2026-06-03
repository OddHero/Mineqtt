package art.rehra.mineqtt.ui.framework.tabs;

import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class CyberdeckPublishTab extends MqttTab {
    public static final String ID = "cyberdeck_publish";

    public CyberdeckPublishTab() {
        super(ID);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.PAPER);
    }

    @Override
    public Component title() {
        return Component.literal("Publish");
    }

    @Override
    public void buildSlots(TabbedMqttMenu menu, Container container) {
        // No slots: the payload is typed manually. Shift-clicking an item in the
        // player inventory fills the payload field with that item's name (handled
        // by CyberdeckPublishTabView via TabbedMqttScreen#slotClicked).
    }
}

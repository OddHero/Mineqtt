package art.rehra.mineqtt.ui.framework.tabs;

import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The default tab present on every MQTT block: shows the topic configuration
 * via the two "frequency" slots (base topic + sub topic, container indices 0 and 1).
 *
 * <p>Subclassed BlockEntities inherit this tab automatically through
 * {@code BaseMqttBlockEntity#getTabs()}.</p>
 */
public class SettingsTab extends MqttTab {

    public static final String ID = "settings";

    public SettingsTab() {
        super(ID);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.COMPARATOR);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.settings");
    }

    @Override
    public void buildSlots(TabbedMqttMenu menu, Container container) {
        if (container.getContainerSize() >= 2) {
            // Two frequency slots centered horizontally
            menu.addTabSlot(id(), container, 0, 62, 36);
            menu.addTabSlot(id(), container, 1, 98, 36);
        }
    }
}

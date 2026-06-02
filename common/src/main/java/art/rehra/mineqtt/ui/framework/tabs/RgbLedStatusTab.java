package art.rehra.mineqtt.ui.framework.tabs;

import art.rehra.mineqtt.ui.framework.MqttTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Read-only status tab for the RGB LED block; client view renders the current colour swatch.
 */
public class RgbLedStatusTab extends MqttTab {

    public static final String ID = "rgb_status";

    public RgbLedStatusTab() {
        super(ID);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.GLOWSTONE_DUST);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.rgb_status");
    }
}

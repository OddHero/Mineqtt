package art.rehra.mineqtt.ui.framework.tabs;

import art.rehra.mineqtt.ui.framework.MqttTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Tab added by the Light Remote block: contributes no extra slots; all of its
 * rich UI (power buttons, color presets, sliders, color picker, numeric input)
 * lives in the client-only {@code LightRemoteTabView}.
 */
public class LightRemoteTab extends MqttTab {

    public static final String ID = "light_remote";

    public LightRemoteTab() {
        super(ID);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.LIGHT);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.light_remote");
    }
}

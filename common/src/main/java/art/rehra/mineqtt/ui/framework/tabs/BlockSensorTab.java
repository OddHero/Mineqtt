package art.rehra.mineqtt.ui.framework.tabs;

import art.rehra.mineqtt.ui.framework.MqttTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class BlockSensorTab extends MqttTab {
    public static final String ID = "block_sensor";

    public BlockSensorTab() {
        super(ID);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.OBSERVER);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.block_sensor");
    }
}

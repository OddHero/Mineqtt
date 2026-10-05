package art.rehra.mineqtt.ui.framework.tabs;

import art.rehra.mineqtt.ui.framework.MqttTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Tab for the Block Emitter block: displays target block offset preview,
 * last received payload, execution status, and manual test emit trigger.
 */
public class BlockEmitterTab extends MqttTab {
    public static final String ID = "block_emitter";

    public BlockEmitterTab() {
        super(ID);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.DISPENSER);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.block_emitter");
    }
}

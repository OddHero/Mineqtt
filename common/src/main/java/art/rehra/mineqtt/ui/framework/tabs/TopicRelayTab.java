package art.rehra.mineqtt.ui.framework.tabs;

import art.rehra.mineqtt.ui.framework.MqttTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Tab for the Topic Relay block: displays source/target topic configuration,
 * cached message, and relay status.
 */
public class TopicRelayTab extends MqttTab {

    public static final String ID = "topic_relay";

    public TopicRelayTab() {
        super(ID);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.REPEATER);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.topic_relay");
    }
}

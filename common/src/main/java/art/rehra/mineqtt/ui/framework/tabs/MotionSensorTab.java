package art.rehra.mineqtt.ui.framework.tabs;

import art.rehra.mineqtt.blocks.entities.MotionSensorBlockEntity;
import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Presence-sensor tab: exposes the 5 spawn-egg filter slots (container indices 2..6), which the sensor reads.
 */
public class MotionSensorTab extends MqttTab {

    public static final String ID = "motion_filters";

    public MotionSensorTab() {
        super(ID);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.ZOMBIE_SPAWN_EGG);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.motion_filters");
    }

    @Override
    public void buildSlots(TabbedMqttMenu menu, Container container) {
        // 5 filter slots centered horizontally
        int start = 8 + (176 - 8 * 2 - 5 * 18) / 2;
        for (int i = 0; i < MotionSensorBlockEntity.NUM_FILTERS && MotionSensorBlockEntity.FIRST_FILTER + i < container.getContainerSize(); i++) {
            menu.addTabSlot(id(), container, MotionSensorBlockEntity.FIRST_FILTER + i, start + i * 18, 36);
        }
    }
}

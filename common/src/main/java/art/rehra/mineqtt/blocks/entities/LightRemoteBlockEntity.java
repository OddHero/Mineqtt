package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.tabs.LightRemoteTab;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class LightRemoteBlockEntity extends MqttPublisherBlockEntity {

    public LightRemoteBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.LIGHT_REMOTE_BLOCK.get(), pos, blockState);
    }

    @Override
    protected String getDefaultTopic() {
        return "/mineqtt/light";
    }

    @Override
    protected @NotNull Component getDefaultName() {
        return Component.translatable("container.mineqtt.light_remote");
    }

    @Override
    public List<MqttTab> getTabs() {
        List<MqttTab> tabs = super.getTabs();
        tabs.add(new LightRemoteTab());
        return tabs;
    }

    @Override
    public int getContainerSize() {
        return 2;
    }

    /**
     * Publishes a light command JSON payload to the configured MQTT topic.
     */
    public void publishLightCommand(String jsonPayload) {
        publish(jsonPayload);
    }

    public static class Ticker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos blockPos, BlockState blockState, T blockEntity) {
            if (blockEntity instanceof LightRemoteBlockEntity lightRemote) {
                String oldTopic = lightRemote.topic;
                String newCombinedTopic = lightRemote.getCombinedTopic();

                if (!newCombinedTopic.equals(oldTopic)) {
                    lightRemote.setTopic(newCombinedTopic);
                }
            }
        }
    }
}

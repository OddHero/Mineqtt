package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.legacy.LegacySubscriptionFacade;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public abstract class MqttPublisherBlockEntity extends BaseMqttBlockEntity {

    public MqttPublisherBlockEntity(BlockEntityType<?> blockEntityType, BlockPos pos, BlockState blockState) {
        super(blockEntityType, pos, blockState);
    }

    public void publish(String message) {
        String topic = getCombinedTopic();
        if (isEnabled() && !topic.isEmpty()) {
            LegacySubscriptionFacade.publish(level, worldPosition, topic, message);
        }
    }
}

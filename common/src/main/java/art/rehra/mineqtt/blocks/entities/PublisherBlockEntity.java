package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.ui.PublisherBlockMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

public class PublisherBlockEntity extends MqttPublisherBlockEntity {

    public PublisherBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.PUBLISHER_BLOCK.get(), pos, blockState);
    }

    @Override
    protected String getDefaultTopic() {
        return "/mineqtt/default";
    }

    @Override
    protected @NotNull Component getDefaultName() {
        return Component.translatable("container.mineqtt.publisher");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new PublisherBlockMenu(containerId, inventory, this, player, this.worldPosition);
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new PublisherBlockMenu(containerId, inventory, this, null, this.worldPosition);
    }

    public static class Ticker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos blockPos, BlockState blockState, T blockEntity) {
            if (blockEntity instanceof PublisherBlockEntity publisherBlockEntity) {
                // Get current combined topic
                String oldTopic = publisherBlockEntity.topic;
                String newCombinedTopic = publisherBlockEntity.getCombinedTopic();

                boolean topicChanged = !newCombinedTopic.equals(oldTopic);

                if (topicChanged) {
                    // Update to new combined topic
                    publisherBlockEntity.setTopic(newCombinedTopic);
                }
            }
        }
    }
}

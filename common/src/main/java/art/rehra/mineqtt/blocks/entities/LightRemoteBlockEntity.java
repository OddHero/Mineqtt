package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.ui.LightRemoteMenu;
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
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new LightRemoteMenu(containerId, inventory, this, player, this.worldPosition);
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new LightRemoteMenu(containerId, inventory, this, null, this.worldPosition);
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

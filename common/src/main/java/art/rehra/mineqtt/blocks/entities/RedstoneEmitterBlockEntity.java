package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.RedstoneEmitterBlock;
import art.rehra.mineqtt.ui.RedstoneEmitterBlockMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;

import static art.rehra.mineqtt.blocks.RedstoneEmitterBlock.POWER;

public class RedstoneEmitterBlockEntity extends MqttSubscriberBlockEntity {

    public RedstoneEmitterBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.REDSTONE_EMITTER_BLOCK_ENTITY.get(), pos, blockState);
    }

    @Override
    protected String getDefaultTopic() {
        return "/mineqtt/default";
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.mineqtt.subscriber");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new RedstoneEmitterBlockMenu(containerId, inventory, this, player, this.worldPosition);
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new RedstoneEmitterBlockMenu(containerId, inventory, this, null, this.worldPosition);
    }

    @Override
    public void onMessageReceived(String topic, String message) {
        // Set the Power state based on message content
        int power = -1;
        if (message.equalsIgnoreCase("ON") || message.equalsIgnoreCase("TRUE")) {
            power = 15;
        } else if (message.equalsIgnoreCase("OFF") || message.equalsIgnoreCase("FALSE")) {
            power = 0;
        } else {
            try {
                power = Integer.parseInt(message);
                power = Math.clamp(power, 0, 15);
            } catch (NumberFormatException e) {
                // Not a number, ignore
            }
        }

        if (power != -1) {
            BlockState currentState = this.level.getBlockState(this.worldPosition);
            if (currentState.hasProperty(POWER) && currentState.getValue(POWER) != power) {
                BlockState newState = currentState.setValue(POWER, power);
                this.level.setBlock(this.worldPosition, newState, Block.UPDATE_ALL);
                if (this.level.getBlockState(this.worldPosition).getBlock() instanceof RedstoneEmitterBlock emitterBlock) {
                    emitterBlock.updateNeighbors(this.level, this.worldPosition, newState);
                }
            }
        }
    }

    public static class Ticker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos blockPos, BlockState blockState, T blockEntity) {
            if (blockEntity instanceof RedstoneEmitterBlockEntity emitterBlockEntity) {
                // Get current combined topic
                String newCombinedTopic = emitterBlockEntity.getCombinedTopic();

                boolean topicChanged = !newCombinedTopic.equals(emitterBlockEntity.topic);

                if (topicChanged) {
                    // Update to new combined topic
                    emitterBlockEntity.setTopic(newCombinedTopic);

                    // Update subscription
                    emitterBlockEntity.updateSubscription(newCombinedTopic);

                    // If disabled (no first slot item), ensure block is unpowered
                    if (!emitterBlockEntity.isEnabled()) {
                        BlockState currentState = level.getBlockState(blockPos);
                        if (currentState.hasProperty(POWER) && currentState.getValue(POWER) > 0) {
                            BlockState newState = currentState.setValue(POWER, 0);
                            level.setBlock(blockPos, newState, Block.UPDATE_ALL);
                            if (level.getBlockState(blockPos).getBlock() instanceof RedstoneEmitterBlock emitterBlock) {
                                emitterBlock.updateNeighbors(level, blockPos, newState);
                            }
                            MineQTT.LOGGER.info("RedstoneEmitterBlockEntity disabled - no base path item in first slot");
                        }
                    }
                }
            }
        }
    }
}

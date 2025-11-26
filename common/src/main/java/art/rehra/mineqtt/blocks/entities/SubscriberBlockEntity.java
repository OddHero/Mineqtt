package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.mqtt.SubscriptionManager;
import art.rehra.mineqtt.ui.SubscriberBlockMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import static art.rehra.mineqtt.blocks.RedstoneSubscriberBlock.POWERED;

public class SubscriberBlockEntity extends MqttSubscriberBlockEntity {

    public SubscriberBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.SUBSCRIBER_BLOCK.get(), pos, blockState);
    }

    @Override
    protected String getDefaultTopic() {
        return "/mineqtt/default";
    }


    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveWithFullMetadata(registries);
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.mineqtt.subscriber");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new SubscriberBlockMenu(containerId, inventory, this, player, this.worldPosition);
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new SubscriberBlockMenu(containerId, inventory, this, null, this.worldPosition);
    }

    @Override
    public void onMessageReceived(String topic, String message) {
        // Set the Power state based on message content
        if (message.equalsIgnoreCase("ON") || message.equalsIgnoreCase("1") || message.equalsIgnoreCase("TRUE")) {
            // Set block to powered state
            BlockState currentState = this.level.getBlockState(this.worldPosition);
            if (currentState.hasProperty(POWERED) && !currentState.getValue(POWERED)) {
                this.level.setBlock(this.worldPosition, currentState.setValue(POWERED, true), Block.UPDATE_ALL);
            }
        } else if (message.equalsIgnoreCase("OFF") || message.equalsIgnoreCase("0") || message.equalsIgnoreCase("FALSE")) {
            // Set block to unpowered state
            BlockState currentState = this.level.getBlockState(this.worldPosition);
            if (currentState.hasProperty(POWERED) && currentState.getValue(POWERED)) {
                this.level.setBlock(this.worldPosition, currentState.setValue(POWERED, false), Block.UPDATE_ALL);
            }
        }
    }

    public static class Ticker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos blockPos, BlockState blockState, T blockEntity) {
            if (blockEntity instanceof SubscriberBlockEntity subscriberBlockEntity) {
                // Get current combined topic
                String newCombinedTopic = subscriberBlockEntity.getCombinedTopic();

                boolean topicChanged = !newCombinedTopic.equals(subscriberBlockEntity.topic);

                if (topicChanged) {
                    // Update to new combined topic
                    subscriberBlockEntity.setTopic(newCombinedTopic);

                    // Update subscription
                    subscriberBlockEntity.updateSubscription(newCombinedTopic);

                    // If disabled (no first slot item), ensure block is unpowered
                    if (!subscriberBlockEntity.isEnabled()) {
                        BlockState currentState = level.getBlockState(blockPos);
                        if (currentState.hasProperty(POWERED) && currentState.getValue(POWERED)) {
                            level.setBlock(blockPos, currentState.setValue(POWERED, false), Block.UPDATE_ALL);
                            MineQTT.LOGGER.info("SubscriberBlockEntity disabled - no base path item in first slot");
                        }
                    }
                }
            }
        }
    }
}

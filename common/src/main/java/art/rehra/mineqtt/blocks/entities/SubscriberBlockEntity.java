package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.mqtt.ICallbackTarget;
import art.rehra.mineqtt.mqtt.SubscriptionManager;
import art.rehra.mineqtt.ui.SubscriberBlockMenu;
import dev.architectury.registry.menu.ExtendedMenuProvider;
import dev.architectury.registry.menu.MenuRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import static art.rehra.mineqtt.blocks.RedstoneSubscriberBlock.POWERED;

public class SubscriberBlockEntity extends BaseContainerBlockEntity implements ExtendedMenuProvider, ICallbackTarget {

    private String topic = "/mineqtt/default";
    public final int INVENTORY_SIZE = 2;
    private NonNullList<ItemStack> items;

    public SubscriberBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.SUBSCRIBER_BLOCK.get(), pos, blockState);

        this.items = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
    }

    public String getTopic() {
        return topic;
    }

    public String getBasePath() {
        return ParseItemStackTopic(getItem(0));
    }

    public String getSubPath() {
        return getItem(1).isEmpty() ? "" : ParseItemStackTopic(getItem(1));
    }

    public String getCombinedTopic() {
        String basePath = getBasePath();
        String subPath = getSubPath();

        if (basePath.isEmpty()) {
            return "";
        }

        if (subPath.isEmpty()) {
            return basePath;
        }

        return basePath + "/" + subPath;
    }

    public boolean isEnabled() {
        // Only enabled if first slot has an item (base path is required)
        return !getItem(0).isEmpty();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("topic", this.topic);
        ContainerHelper.saveAllItems(output, this.items);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.topic = input.getString("topic").isPresent() ? input.getString("topic").get() : "/mineqtt/default";
        this.items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, this.items);

        // Update subscriptions based on current items
        if (isEnabled()) {
            updateSubscriptions();
        }
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
    protected NonNullList<ItemStack> getItems() {
        return this.items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    //@Override
    //protected AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
    //    MineQTT.LOGGER.info("Creating SubscriberBlockMenu with topic: " + this.topic);
    //    return new SubscriberBlockMenu(containerId, inventory, this, null);
    //}

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new SubscriberBlockMenu(containerId, inventory, this, player, this.worldPosition);
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new SubscriberBlockMenu(containerId, inventory, this,null, this.worldPosition);
    }

    @Override
    public int getContainerSize() {
        return INVENTORY_SIZE;
    }

    public void setTopic(String topic) {
        if (!this.topic.equals(topic)) {
            this.topic = topic;
            markUpdated();
        }
    }

    private void updateSubscriptions() {
        // Subscribe to the combined topic if enabled
        String combinedTopic = getCombinedTopic();
        if (isEnabled() && !combinedTopic.isEmpty()) {
            SubscriptionManager.subscribe(combinedTopic, this);
        }
    }

    private void unsubscribeAll() {
        // Unsubscribe from current topic
        if (!topic.isEmpty()) {
            SubscriptionManager.unsubscribe(this.topic, this);
        }
    }

    private void markUpdated() {
        this.setChanged();
        this.level.sendBlockUpdated(this.getBlockPos(), this.getBlockState(), this.getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override
    public void saveExtraData(FriendlyByteBuf buf) {

    }

    public static String ParseItemStackTopic(ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return "minecraft/default";
        } else if (itemStack.getItem() == Items.PAPER) {
            return itemStack.getHoverName().getString();
        } else {
            return itemStack.getItem().toString().replace(":", "/");
        }
    }

    @Override
    public BlockPos getPosition() {
        return this.worldPosition;
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
                String oldTopic = subscriberBlockEntity.topic;
                String newCombinedTopic = subscriberBlockEntity.getCombinedTopic();

                boolean topicChanged = !newCombinedTopic.equals(oldTopic);

                if (topicChanged) {
                    // Unsubscribe from old topic
                    subscriberBlockEntity.unsubscribeAll();

                    // Update to new combined topic
                    subscriberBlockEntity.setTopic(newCombinedTopic);

                    // Subscribe to new combined topic if block is enabled
                    if (subscriberBlockEntity.isEnabled() && !newCombinedTopic.isEmpty()) {
                        SubscriptionManager.subscribe(newCombinedTopic, subscriberBlockEntity);
                    } else {
                        // If disabled (no first slot item), ensure block is unpowered
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

package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.mqtt.ICallbackTarget;
import art.rehra.mineqtt.mqtt.SubscriptionManager;
import dev.architectury.registry.menu.ExtendedMenuProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public abstract class MqttSubscriberBlockEntity extends BaseContainerBlockEntity implements ExtendedMenuProvider, ICallbackTarget {

    protected String topic = "";
    protected String currentSubscribedTopic = "";
    public final int INVENTORY_SIZE = 2;
    protected NonNullList<ItemStack> items;

    public MqttSubscriberBlockEntity(BlockEntityType<?> blockEntityType, BlockPos pos, BlockState blockState) {
        super(blockEntityType, pos, blockState);
        this.items = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
        this.topic = getDefaultTopic();
    }

    protected abstract String getDefaultTopic();

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
        return !getItem(0).isEmpty();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("topic", this.topic);
        output.putString("currentSubscribedTopic", this.currentSubscribedTopic);
        ContainerHelper.saveAllItems(output, this.items);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.topic = input.getString("topic").orElse(getDefaultTopic());
        this.currentSubscribedTopic = input.getString("currentSubscribedTopic").orElse("");
        this.items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, this.items);

        // Subscribe to current topic after loading
        if (isEnabled() && !this.currentSubscribedTopic.isEmpty()) {
            SubscriptionManager.subscribe(this.currentSubscribedTopic, this);
        }
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return this.items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
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

    protected void updateSubscription(String newCombinedTopic) {
        // Only change subscription if the topic actually changed
        if (!newCombinedTopic.equals(currentSubscribedTopic)) {
            // Unsubscribe from old topic if we were subscribed
            if (!currentSubscribedTopic.isEmpty()) {
                MineQTT.LOGGER.info("Unsubscribing from: " + currentSubscribedTopic);
                SubscriptionManager.unsubscribe(currentSubscribedTopic, this);
            }

            // Update current subscribed topic
            currentSubscribedTopic = newCombinedTopic;

            // Subscribe to new topic if enabled
            if (isEnabled() && !newCombinedTopic.isEmpty()) {
                MineQTT.LOGGER.info("Subscribing to: " + newCombinedTopic);
                SubscriptionManager.subscribe(newCombinedTopic, this);
            }
        }
    }

    protected void unsubscribeAll() {
        if (!currentSubscribedTopic.isEmpty()) {
            MineQTT.LOGGER.info("Unsubscribing from all: " + currentSubscribedTopic);
            SubscriptionManager.unsubscribe(currentSubscribedTopic, this);
            currentSubscribedTopic = "";
        }
    }

    protected void markUpdated() {
        this.setChanged();
        if (this.level != null) {
            this.level.sendBlockUpdated(this.getBlockPos(), this.getBlockState(), this.getBlockState(), Block.UPDATE_CLIENTS);
        }
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
    public ResourceKey<Level> getDimension() {
        return this.level != null ? this.level.dimension() : Level.OVERWORLD;
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        unsubscribeAll();
    }
}


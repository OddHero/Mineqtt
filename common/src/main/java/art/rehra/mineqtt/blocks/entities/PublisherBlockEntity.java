package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.ui.PublisherBlockMenu;
import dev.architectury.registry.menu.ExtendedMenuProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.NotNull;

public class PublisherBlockEntity extends BaseContainerBlockEntity implements ExtendedMenuProvider {

    private String topic = "/mineqtt/default";
    public final int INVENTORY_SIZE = 2;
    private NonNullList<ItemStack> items;

    public PublisherBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.PUBLISHER_BLOCK.get(), pos, blockState);

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

        if(basePath.isEmpty()) {
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

    public void setTopic(String topic) {
        if (!this.topic.equals(topic)) {
            this.topic = topic;
            markUpdated();
        }
    }

    private void markUpdated() {
        this.setChanged();
        this.level.sendBlockUpdated(this.getBlockPos(), this.getBlockState(), this.getBlockState(), Block.UPDATE_CLIENTS);
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
    }

    @Override
    public @org.jetbrains.annotations.Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveWithFullMetadata(registries);
    }

    @Override
    protected @NotNull Component getDefaultName() {
        return Component.translatable("container.mineqtt.publisher");
    }

    @Override
    public NonNullList<ItemStack> getItems() {
        return this.items;
    }

    @Override
    public void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        MineQTT.LOGGER.info("Creating PublisherBlockMenu with topic: " + this.topic);
        return new PublisherBlockMenu(containerId, inventory, this, player, this.worldPosition);
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new PublisherBlockMenu(containerId, inventory, this, null, this.worldPosition);
    }

    @Override
    public int getContainerSize() {
        return INVENTORY_SIZE;
    }

    @Override
    public void saveExtraData(FriendlyByteBuf buf) {
        // No extra data needed for now
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

    public static class Ticker<T extends BlockEntity> implements BlockEntityTicker<T> {

        @Override
        public void tick(Level level, BlockPos blockPos, BlockState blockState, T blockEntity) {
            if (blockEntity instanceof PublisherBlockEntity publisherBlockEntity) {
                // Get current combined topic
                String oldTopic = publisherBlockEntity.topic;
                String newCombinedTopic = publisherBlockEntity.getCombinedTopic();

                boolean topicChanged = !newCombinedTopic.equals(oldTopic);

                if (topicChanged) {
                    MineQTT.LOGGER.info("PublisherBlockEntity at " + blockPos.toShortString() + " combined topic changed: " + oldTopic + " -> " + newCombinedTopic);

                    // Update to new combined topic
                    publisherBlockEntity.setTopic(newCombinedTopic);
                }
            }
        }
    }
}

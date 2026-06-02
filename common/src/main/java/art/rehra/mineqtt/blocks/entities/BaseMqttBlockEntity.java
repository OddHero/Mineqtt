package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.tabs.SettingsTab;
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
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public abstract class BaseMqttBlockEntity extends BaseContainerBlockEntity implements ExtendedMenuProvider {

    public static final int INVENTORY_SIZE = 4;
    protected String topic = "";
    protected NonNullList<ItemStack> items;
    /**
     * Last tab the player opened on this block; persisted via NBT and sent with {@link #saveExtraData}.
     */
    protected String lastTabId = "";

    public BaseMqttBlockEntity(BlockEntityType<?> blockEntityType, BlockPos pos, BlockState blockState) {
        super(blockEntityType, pos, blockState);
        this.items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
        this.topic = getDefaultTopic();
    }

    public static String parseItemStackTopic(ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return "minecraft/default";
        } else if (itemStack.getItem() == Items.PAPER) {
            return itemStack.getHoverName().getString();
        } else {
            return itemStack.getItem().toString().replace(":", "/");
        }
    }

    protected abstract String getDefaultTopic();

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        if (!this.topic.equals(topic)) {
            this.topic = topic;
            markUpdated();
        }
    }

    public String getBasePath() {
        return parseItemStackTopic(getItem(0));
    }

    public String getSubPath() {
        return getItem(1).isEmpty() ? "" : parseItemStackTopic(getItem(1));
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

    public void markUpdated() {
        this.setChanged();
        if (this.level != null) {
            this.level.sendBlockUpdated(this.getBlockPos(), this.getBlockState(), this.getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("topic", this.topic);
        output.putString("lastTabId", this.lastTabId);
        ContainerHelper.saveAllItems(output, this.items);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.topic = input.getString("topic").orElse(getDefaultTopic());
        this.lastTabId = input.getString("lastTabId").orElse("");
        this.items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, this.items);
    }

    public String getLastTabId() {
        return lastTabId;
    }

    public void setLastTabId(String tabId) {
        if (tabId == null || tabId.equals(this.lastTabId)) return;
        this.lastTabId = tabId;
        this.setChanged();
    }

    /**
     * Returns the ordered list of tabs this block exposes in its tabbed GUI.
     * Subclasses should call {@code super.getTabs()} and append their own tab(s)
     * so that the inheritance chain naturally accumulates tabs.
     */
    public List<MqttTab> getTabs() {
        List<MqttTab> list = new ArrayList<>();
        list.add(new SettingsTab());
        return list;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block." + this.getBlockState().getBlock().getDescriptionId().substring("block.".length()));
    }

    @Nullable
    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new TabbedMqttMenu(containerId, inventory, inventory.player, this.getBlockPos(), this.lastTabId);
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

    @Override
    public void saveExtraData(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.getBlockPos());
    }
}

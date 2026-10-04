package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.tabs.StaticPublisherTab;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Block entity for the Static Publisher block.
 * Stores a predefined message and publishes it on redstone rising-edge triggers.
 */
public class StaticPublisherBlockEntity extends MqttPublisherBlockEntity {

    /** Container slots: 0-1 = topic config (base + sub), 2 = static message item (optional) */
    public static final int INVENTORY_SIZE = 3;

    private String staticMessage = "";
    private boolean wasPowered = false;
    private String lastPublishStatus = "";

    public StaticPublisherBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.STATIC_PUBLISHER_BLOCK.get(), pos, blockState);
        this.items = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
    }

    @Override
    protected String getDefaultTopic() {
        return "/mineqtt/static_publisher";
    }

    @Override
    protected @NotNull Component getDefaultName() {
        return Component.translatable("container.mineqtt.static_publisher");
    }

    public String getStaticMessage() {
        return staticMessage;
    }

    public void setStaticMessage(String message) {
        if (message == null) message = "";
        if (!this.staticMessage.equals(message)) {
            this.staticMessage = message;
            markUpdated();
        }
    }

    public boolean isWasPowered() {
        return wasPowered;
    }

    public void setWasPowered(boolean wasPowered) {
        this.wasPowered = wasPowered;
    }

    public String getLastPublishStatus() {
        return lastPublishStatus;
    }

    public void setLastPublishStatus(String status) {
        if (status == null) status = "";
        if (!this.lastPublishStatus.equals(status)) {
            this.lastPublishStatus = status;
            this.setChanged();
        }
    }

    /**
     * Publish the static message if configured. Returns true if a message was published.
     */
    public boolean publishStaticMessage() {
        String message = this.staticMessage.isEmpty()
                ? "true"
                : this.staticMessage;

        if (!isEnabled()) {
            this.setLastPublishStatus("Disabled – no base topic item");
            return false;
        }

        String topic = getCombinedTopic();
        if (topic.isEmpty()) {
            this.setLastPublishStatus("Error – no topic configured");
            return false;
        }

        try {
            this.publish(message);
            this.setLastPublishStatus("Published to " + topic);
            MineQTT.LOGGER.debug("[StaticPublisher] Published '{}' to {} at {}", message, topic, this.getBlockPos());
            return true;
        } catch (Exception e) {
            this.setLastPublishStatus("Error – " + e.getMessage());
            MineQTT.LOGGER.error("[StaticPublisher] Failed to publish: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public int getContainerSize() {
        return INVENTORY_SIZE;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("staticMessage", this.staticMessage);
        output.putBoolean("wasPowered", this.wasPowered);
        output.putString("lastPublishStatus", this.lastPublishStatus);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.staticMessage = input.getString("staticMessage").orElse("");
        this.wasPowered = input.getBooleanOr("wasPowered", false);
        this.lastPublishStatus = input.getString("lastPublishStatus").orElse("");
        this.items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, this.items);
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveWithFullMetadata(registries);
    }

    @Override
    public List<MqttTab> getTabs() {
        List<MqttTab> tabs = super.getTabs();
        tabs.add(new StaticPublisherTab());
        return tabs;
    }

    public static class Ticker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos blockPos, BlockState blockState, T blockEntity) {
            if (blockEntity instanceof StaticPublisherBlockEntity entity && !level.isClientSide()) {
                // Update combined topic if items changed
                String newCombinedTopic = entity.getCombinedTopic();
                if (!newCombinedTopic.equals(entity.topic)) {
                    entity.setTopic(newCombinedTopic);
                }
            }
        }
    }
}

package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.tabs.DynamicPublisherTab;
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
 * Block entity for the Dynamic Publisher block.
 * Generates and publishes dynamic server information on redstone rising-edge triggers.
 */
public class DynamicPublisherBlockEntity extends MqttPublisherBlockEntity {

    /** Container slots: 0-1 = topic config (base + sub) */
    public static final int INVENTORY_SIZE = 2;

    private int dataTypeOrdinal = 0;
    private boolean wasPowered = false;
    private String lastPublishStatus = "";

    public DynamicPublisherBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.DYNAMIC_PUBLISHER_BLOCK.get(), pos, blockState);
        this.items = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
    }

    @Override
    protected String getDefaultTopic() {
        return "/mineqtt/dynamic_publisher";
    }

    @Override
    protected @NotNull Component getDefaultName() {
        return Component.translatable("container.mineqtt.dynamic_publisher");
    }

    public DynamicDataType getDataType() {
        return DynamicDataType.fromOrdinal(this.dataTypeOrdinal);
    }

    public void setDataType(DynamicDataType dataType) {
        this.dataTypeOrdinal = dataType.ordinal();
        markUpdated();
    }

    public int getDataTypeOrdinal() {
        return dataTypeOrdinal;
    }

    public void setDataTypeOrdinal(int ordinal) {
        if (this.dataTypeOrdinal != ordinal) {
            this.dataTypeOrdinal = DynamicDataType.fromOrdinal(ordinal).ordinal();
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
     * Generate the current dynamic value based on the configured data type.
     */
    public String generateCurrentValue() {
        if (this.level == null || this.level.isClientSide()) {
            return "";
        }
        return getDataType().generateValue(this.level);
    }

    /**
     * Publish the dynamically generated value. Returns true if a message was published.
     */
    public boolean publishDynamicMessage() {
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
            String value = generateCurrentValue();
            this.publish(value);
            this.setLastPublishStatus("Published '" + value + "' to " + topic);
            MineQTT.LOGGER.debug("[DynamicPublisher] Published '{}' to {} at {}", value, topic, this.getBlockPos());
            return true;
        } catch (Exception e) {
            this.setLastPublishStatus("Error – " + e.getMessage());
            MineQTT.LOGGER.error("[DynamicPublisher] Failed to publish: {}", e.getMessage());
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
        output.putInt("dataType", this.dataTypeOrdinal);
        output.putBoolean("wasPowered", this.wasPowered);
        output.putString("lastPublishStatus", this.lastPublishStatus);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.dataTypeOrdinal = input.getIntOr("dataType", 0);
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
        tabs.add(new DynamicPublisherTab());
        return tabs;
    }

    public static class Ticker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos blockPos, BlockState blockState, T blockEntity) {
            if (blockEntity instanceof DynamicPublisherBlockEntity entity && !level.isClientSide()) {
                // Update combined topic if items changed
                String newCombinedTopic = entity.getCombinedTopic();
                if (!newCombinedTopic.equals(entity.topic)) {
                    entity.setTopic(newCombinedTopic);
                }
            }
        }
    }
}

package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.ui.MotionSensorBlockMenu;
import dev.architectury.registry.menu.ExtendedMenuProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public class MotionSensorBlockEntity extends BaseContainerBlockEntity implements ExtendedMenuProvider {

    // Number of filter slots for mob head filters
    public static final int NUM_FILTERS = 5;
    public final int INVENTORY_SIZE = 2;
    private String topic = "/mineqtt/default";
    private NonNullList<ItemStack> items;

    // Current motion detection state
    private boolean motionDetected = false;
    private long lastMotionTime = 0;

    public MotionSensorBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.MOTION_SENSOR_BLOCK.get(), pos, blockState);
        this.items = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
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

    private void markUpdated() {
        this.setChanged();
        this.level.sendBlockUpdated(this.getBlockPos(), this.getBlockState(), this.getBlockState(), 3);
    }

    public boolean isMotionDetected() {
        return motionDetected;
    }

    public void setMotionDetected(boolean detected) {
        this.motionDetected = detected;
        if (detected) {
            this.lastMotionTime = System.currentTimeMillis();
        }
        markUpdated();
    }

    public long getLastMotionTime() {
        return lastMotionTime;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("topic", this.topic);
        ContainerHelper.saveAllItems(output, this.items);
        output.putByte("MotionDetected", (byte) (motionDetected ? 1 : 0));
        output.putLong("LastMotionTime", lastMotionTime);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.topic = input.getString("topic").orElse("/mineqtt/default");
        this.items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, this.items);
        this.motionDetected = input.getByteOr("MotionDetected", (byte) 0) != 0;
        this.lastMotionTime = input.getLongOr("LastMotionTime", 0L);
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
    public AbstractContainerMenu createMenu(int id, Inventory playerInventory, Player player) {
        return new MotionSensorBlockMenu(id, playerInventory, this, player, this.worldPosition);
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory playerInventory) {
        return new MotionSensorBlockMenu(id, playerInventory, this, null, this.worldPosition);
    }

    @Override
    protected Component getDefaultName() {
        return Component.literal("Motion Sensor");
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Motion Sensor");
    }

    @Override
    public void saveExtraData(FriendlyByteBuf buf) {
    }

    /**
     * Ticker for updating motion sensor logic
     */
    public static class Ticker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (level != null && !level.isClientSide && blockEntity instanceof MotionSensorBlockEntity motionSensor) {
                // Future: Check for mobs in front of the sensor
                // For now, this is a placeholder for mob detection logic
            }
        }
    }
}


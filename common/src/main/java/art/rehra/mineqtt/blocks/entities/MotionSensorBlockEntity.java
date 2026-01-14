package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.ui.MotionSensorBlockMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;

import java.util.List;

public class MotionSensorBlockEntity extends MqttPublisherBlockEntity {

    // Number of filter slots for mob head filters
    public static final int NUM_FILTERS = 5;

    // Current motion detection state
    private boolean motionDetected = false;
    private long lastMotionTime = 0;
    private int lastCount = -1;

    public MotionSensorBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.MOTION_SENSOR_BLOCK.get(), pos, blockState);
    }

    @Override
    protected String getDefaultTopic() {
        return "/mineqtt/default";
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

    public int getLastCount() {
        return lastCount;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putByte("MotionDetected", (byte) (motionDetected ? 1 : 0));
        output.putLong("LastMotionTime", lastMotionTime);
        output.putInt("LastCount", lastCount);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.motionDetected = input.getByteOr("MotionDetected", (byte) 0) != 0;
        this.lastMotionTime = input.getLongOr("LastMotionTime", 0L);
        this.lastCount = input.getIntOr("LastCount", -1);
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

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putInt("LastCount", lastCount);
        tag.putByte("MotionDetected", (byte) (motionDetected ? 1 : 0));
        return tag;
    }

    @Override
    public int getContainerSize() {
        return INVENTORY_SIZE + NUM_FILTERS;
    }

    public boolean matchesFilters(LivingEntity entity) {
        boolean hasFilters = false;
        for (int i = 0; i < NUM_FILTERS; i++) {
            ItemStack filterStack = getItem(INVENTORY_SIZE + i);
            if (!filterStack.isEmpty()) {
                hasFilters = true;
                if (isMatching(filterStack, entity)) {
                    return true;
                }
            }
        }
        return !hasFilters;
    }

    private boolean isMatching(ItemStack filterStack, LivingEntity entity) {
        if (filterStack.getItem() instanceof SpawnEggItem spawnEgg) {
            if (this.level == null) return false;
            EntityType<?> type = spawnEgg.getType(this.level.registryAccess(), filterStack);
            return type != null && type.equals(entity.getType());
        }
        return false;
    }

    /**
     * Ticker for updating motion sensor logic
     */
    public static class Ticker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (level != null && !level.isClientSide && blockEntity instanceof MotionSensorBlockEntity sensor) {
                // Update topic if needed (common logic)
                String oldTopic = sensor.topic;
                String newCombinedTopic = sensor.getCombinedTopic();
                if (!newCombinedTopic.equals(oldTopic)) {
                    sensor.setTopic(newCombinedTopic);
                }

                // Detection logic - every 10 ticks (0.5s) to save performance
                if (level.getGameTime() % 10 == 0) {
                    if (sensor.isEnabled()) {
                        net.minecraft.core.Direction facing = state.getValue(art.rehra.mineqtt.blocks.MotionSensorBlock.FACING);

                        // Detection box: 5x5x5 area in front of the sensor
                        AABB box = new AABB(pos).inflate(2.0).move(facing.getStepX() * 2.5, facing.getStepY() * 2.5, facing.getStepZ() * 2.5);

                        List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, box, entity -> {
                            if (entity instanceof Player) return false;
                            return sensor.matchesFilters(entity);
                        });

                        int count = entities.size();
                        boolean detected = count > 0;
                        if (detected != sensor.isMotionDetected()) {
                            sensor.setMotionDetected(detected);
                        }

                        if (count != sensor.lastCount) {
                            sensor.lastCount = count;
                            sensor.publish(String.valueOf(count));
                            sensor.markUpdated();
                        }
                    } else if (sensor.isMotionDetected() || sensor.lastCount != 0) {
                        // Reset if disabled
                        sensor.setMotionDetected(false);
                        sensor.lastCount = 0;
                        sensor.publish("0");
                        sensor.markUpdated();
                    }
                }
            }
        }
    }
}


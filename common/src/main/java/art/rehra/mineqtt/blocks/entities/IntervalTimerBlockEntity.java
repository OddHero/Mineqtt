package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.IntervalTimerBlock;
import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.tabs.IntervalTimerTab;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.List;

import static art.rehra.mineqtt.blocks.IntervalTimerBlock.POWER;

/**
 * Block entity for the Interval Timer: counts ticks and fires a 1-tick redstone pulse
 * at a configurable interval (ticks, seconds, minutes, or hours).
 */
public class IntervalTimerBlockEntity extends BaseMqttBlockEntity {

    /**
     * The interval value entered by the user (e.g. 5, 10, 2).
     */
    private long intervalValue = 20;

    /**
     * Time unit index: 0=ticks, 1=seconds, 2=minutes, 3=hours.
     */
    private int unitIndex = 0;

    /**
     * Current tick counter; persists across world reloads.
     */
    private long tickCounter = 0;

    /**
     * Server tick count when the timer last triggered, used for progress display.
     */
    private long lastTriggerTime = 0;

    public static final int UNIT_TICKS = 0;
    public static final int UNIT_SECONDS = 1;
    public static final int UNIT_MINUTES = 2;
    public static final int UNIT_HOURS = 3;

    public static final int TICKS_PER_SECOND = 20;
    public static final int TICKS_PER_MINUTE = 1200;
    public static final int TICKS_PER_HOUR = 72000;

    public IntervalTimerBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.INTERVAL_TIMER_BLOCK.get(), pos, blockState);
    }

    @Override
    protected String getDefaultTopic() {
        return "";
    }

    @Override
    protected net.minecraft.network.chat.Component getDefaultName() {
        return net.minecraft.network.chat.Component.translatable("container.mineqtt.interval_timer");
    }

    public long getIntervalValue() {
        return intervalValue;
    }

    public void setIntervalValue(long value) {
        if (value <= 0) {
            MineQTT.LOGGER.warn("[IntervalTimer] Invalid interval value at {}: {}", this.worldPosition, value);
            return;
        }
        this.intervalValue = value;
        markUpdated();
    }

    public int getUnitIndex() {
        return unitIndex;
    }

    public void setUnitIndex(int unitIndex) {
        if (unitIndex < 0 || unitIndex > 3) {
            MineQTT.LOGGER.warn("[IntervalTimer] Invalid unit index at {}: {}", this.worldPosition, unitIndex);
            return;
        }
        this.unitIndex = unitIndex;
        markUpdated();
    }

    /**
     * Returns the configured interval converted to ticks.
     */
    public long getIntervalTicks() {
        switch (unitIndex) {
            case UNIT_SECONDS: return intervalValue * TICKS_PER_SECOND;
            case UNIT_MINUTES: return intervalValue * TICKS_PER_MINUTE;
            case UNIT_HOURS:   return intervalValue * TICKS_PER_HOUR;
            case UNIT_TICKS:
            default:           return intervalValue;
        }
    }

    public long getTickCounter() {
        return tickCounter;
    }

    public long getLastTriggerTime() {
        return lastTriggerTime;
    }

    /**
     * Returns the remaining ticks until the next trigger.
     */
    public long getRemainingTicks() {
        long interval = getIntervalTicks();
        return Math.max(0, interval - tickCounter);
    }

    /**
     * Sets both interval value and unit in one call (used by networking payload).
     */
    public void setInterval(long value, int unitIndex) {
        if (value <= 0) {
            MineQTT.LOGGER.warn("[IntervalTimer] Invalid interval value at {}: {}", this.worldPosition, value);
            return;
        }
        if (unitIndex < 0 || unitIndex > 3) {
            MineQTT.LOGGER.warn("[IntervalTimer] Invalid unit index at {}: {}", this.worldPosition, unitIndex);
            return;
        }
        this.intervalValue = value;
        this.unitIndex = unitIndex;
        // Reset counter when interval changes
        this.tickCounter = 0;
        markUpdated();
    }

    @Override
    public List<MqttTab> getTabs() {
        List<MqttTab> tabs = new ArrayList<>();
        tabs.add(new IntervalTimerTab());
        return tabs;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putLong("intervalValue", this.intervalValue);
        output.putInt("unitIndex", this.unitIndex);
        output.putLong("tickCounter", this.tickCounter);
        output.putLong("lastTriggerTime", this.lastTriggerTime);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.intervalValue = input.getLong("intervalValue").orElse(20L);
        this.unitIndex = input.getInt("unitIndex").orElse(0);
        this.tickCounter = input.getLong("tickCounter").orElse(0L);
        this.lastTriggerTime = input.getLong("lastTriggerTime").orElse(0L);
    }

    /**
     * Server-side ticker: increments the counter and fires a 1-tick redstone pulse.
     */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (!(blockEntity instanceof IntervalTimerBlockEntity entity)) return;

            long interval = entity.getIntervalTicks();
            if (interval <= 0) return;

            // If currently powered, turn off (this is the delayed off-tick)
            if (state.hasProperty(POWER) && state.getValue(POWER) > 0) {
                BlockState newState = state.setValue(POWER, 0);
                level.setBlock(pos, newState, Block.UPDATE_ALL);
                if (level.getBlockState(pos).getBlock() instanceof IntervalTimerBlock block) {
                    block.updateNeighbors(level, pos, newState);
                }
                return;
            }

            // Increment counter
            entity.tickCounter++;

            // Check if interval has elapsed
            if (entity.tickCounter >= interval) {
                // Fire a 1-tick pulse at POWER=15
                BlockState newState = state.setValue(POWER, 15);
                level.setBlock(pos, newState, Block.UPDATE_ALL);
                if (level.getBlockState(pos).getBlock() instanceof IntervalTimerBlock block) {
                    block.updateNeighbors(level, pos, newState);
                }

                // Reset counter and record trigger time
                entity.tickCounter = 0;
                entity.lastTriggerTime = level.getGameTime();
                entity.setChanged();

                // Schedule the block entity to tick again next tick to turn off
                level.scheduleTick(pos, (IntervalTimerBlock) state.getBlock(), 1);
            }
        }
    }
}

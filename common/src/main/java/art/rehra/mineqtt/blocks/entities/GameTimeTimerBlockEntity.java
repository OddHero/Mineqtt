package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.GameTimeTimerBlock;
import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.tabs.GameTimeTimerTab;
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

import static art.rehra.mineqtt.blocks.GameTimeTimerBlock.POWER;

/**
 * Block entity for the Game Time Timer: monitors Minecraft's day/night cycle
 * and fires a 1-tick redstone pulse at the configured trigger time.
 */
public class GameTimeTimerBlockEntity extends BaseMqttBlockEntity {

    /**
     * Detection window in ticks around the trigger time (±20 ticks).
     */
    public static final int DETECTION_WINDOW = 20;

    /**
     * The configured trigger mode.
     */
    private int triggerModeIndex = 0;

    /**
     * Custom tick value if using CUSTOM mode (0-23999).
     */
    private int customTime = 6000;

    /**
     * The last Minecraft day number (gameTime / 24000) when we triggered.
     * Used to prevent duplicate triggers on the same day.
     */
    private long lastTriggerDay = -1;

    /**
     * Timestamp of the last trigger (level.getGameTime()).
     */
    private long lastTriggerTime = 0;

    public GameTimeTimerBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.GAME_TIME_TIMER_BLOCK.get(), pos, blockState);
    }

    @Override
    protected String getDefaultTopic() {
        return "";
    }

    @Override
    protected net.minecraft.network.chat.Component getDefaultName() {
        return net.minecraft.network.chat.Component.translatable("container.mineqtt.game_time_timer");
    }

    /**
     * Returns the configured trigger mode index.
     */
    public int getTriggerModeIndex() {
        return triggerModeIndex;
    }

    /**
     * Sets the trigger mode by ordinal index.
     */
    public void setTriggerModeIndex(int index) {
        if (index < 0 || index >= GameTimeTrigger.values().length) {
            MineQTT.LOGGER.warn("[GameTimeTimer] Invalid trigger mode index at {}: {}", this.worldPosition, index);
            return;
        }
        this.triggerModeIndex = index;
        markUpdated();
    }

    /**
     * Returns the configured trigger mode enum.
     */
    public GameTimeTrigger getTriggerMode() {
        return GameTimeTrigger.byIndex(triggerModeIndex);
    }

    /**
     * Returns the custom time value for CUSTOM mode.
     */
    public int getCustomTime() {
        return customTime;
    }

    /**
     * Sets the custom time value. Validates range 0-23999.
     */
    public void setCustomTime(int time) {
        if (time < 0 || time >= 24000) {
            MineQTT.LOGGER.warn("[GameTimeTimer] Invalid custom time at {}: {} (must be 0-23999)", this.worldPosition, time);
            return;
        }
        this.customTime = time;
        markUpdated();
    }

    /**
     * Returns the last Minecraft day when the timer triggered.
     */
    public long getLastTriggerDay() {
        return lastTriggerDay;
    }

    /**
     * Returns the last trigger time in game ticks.
     */
    public long getLastTriggerTime() {
        return lastTriggerTime;
    }

    /**
     * Returns the target Minecraft tick for the current trigger configuration.
     */
    public int getTargetTicks() {
        GameTimeTrigger mode = getTriggerMode();
        if (mode == GameTimeTrigger.CUSTOM) {
            return customTime;
        }
        return mode.getDefaultTicks();
    }

    /**
     * Returns the remaining ticks until the next trigger.
     */
    public long getRemainingTicks(Level level) {
        if (level == null) return 0;
        long gameTime = level.getGameTime();
        int timeOfDay = (int) (gameTime % 24000);
        int target = getTargetTicks();

        long diff = target - timeOfDay;
        if (diff < 0) {
            diff += 24000;
        }
        return diff;
    }

    @Override
    public List<MqttTab> getTabs() {
        List<MqttTab> tabs = new ArrayList<>();
        tabs.add(new GameTimeTimerTab());
        return tabs;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("triggerModeIndex", this.triggerModeIndex);
        output.putInt("customTime", this.customTime);
        output.putLong("lastTriggerDay", this.lastTriggerDay);
        output.putLong("lastTriggerTime", this.lastTriggerTime);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.triggerModeIndex = input.getInt("triggerModeIndex").orElse(0);
        this.customTime = input.getInt("customTime").orElse(6000);
        this.lastTriggerDay = input.getLong("lastTriggerDay").orElse(-1L);
        this.lastTriggerTime = input.getLong("lastTriggerTime").orElse(0L);
    }

    /**
     * Server-side ticker: checks current Minecraft time against the configured trigger
     * and fires a 1-tick redstone pulse when a match is found.
     */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (!(blockEntity instanceof GameTimeTimerBlockEntity entity)) return;

            // If currently powered, turn off (this is the delayed off-tick)
            if (state.hasProperty(POWER) && state.getValue(POWER) > 0) {
                BlockState newState = state.setValue(POWER, 0);
                level.setBlock(pos, newState, Block.UPDATE_ALL);
                if (level.getBlockState(pos).getBlock() instanceof GameTimeTimerBlock block) {
                    block.updateNeighbors(level, pos, newState);
                }
                return;
            }

            long gameTime = level.getGameTime();
            int timeOfDay = (int) (gameTime % 24000);
            long currentDay = gameTime / 24000;

            int target = entity.getTargetTicks();

            // Check if we are within the detection window of the target time
            int diff = timeOfDay - target;
            // Handle wrap-around for midnight/sunrise triggers
            if (diff > 12000) diff -= 24000;
            if (diff < -12000) diff += 24000;

            if (Math.abs(diff) <= DETECTION_WINDOW) {
                // Check if we already triggered today
                if (entity.lastTriggerDay != currentDay) {
                    // Fire a 1-tick pulse at POWER=15
                    BlockState newState = state.setValue(POWER, 15);
                    level.setBlock(pos, newState, Block.UPDATE_ALL);
                    if (level.getBlockState(pos).getBlock() instanceof GameTimeTimerBlock block) {
                        block.updateNeighbors(level, pos, newState);
                    }

                    entity.lastTriggerTime = gameTime;
                    entity.lastTriggerDay = currentDay;
                    entity.setChanged();

                    // Schedule the block entity to tick again next tick to turn off
                    level.scheduleTick(pos, (GameTimeTimerBlock) state.getBlock(), 1);
                }
            }
        }
    }
}

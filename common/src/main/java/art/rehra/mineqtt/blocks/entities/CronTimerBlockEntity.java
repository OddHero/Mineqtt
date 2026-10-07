package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.CronTimerBlock;
import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.tabs.CronTimerTab;
import art.rehra.mineqtt.util.CronSchedule;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static art.rehra.mineqtt.blocks.CronTimerBlock.POWER;

public class CronTimerBlockEntity extends BaseMqttBlockEntity {

    private String cronExpression = "";
    private CronSchedule parsedSchedule = null;
    /**
     * The tick count when we last triggered, to avoid duplicate triggers within the same game tick.
     */
    private long lastTriggerTick = -1L;

    public CronTimerBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.CRON_TIMER_BLOCK.get(), pos, blockState);
    }

    @Override
    protected String getDefaultTopic() {
        return "";
    }

    @Override
    protected net.minecraft.network.chat.Component getDefaultName() {
        return net.minecraft.network.chat.Component.translatable("container.mineqtt.cron_timer");
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String expression) {
        this.cronExpression = expression;
        if (expression == null || expression.isBlank()) {
            this.parsedSchedule = null;
        } else {
            try {
                this.parsedSchedule = CronSchedule.parse(expression);
            } catch (IllegalArgumentException e) {
                MineQTT.LOGGER.warn("[CronTimer] Invalid cron expression at {}: {}", this.worldPosition, e.getMessage());
                this.parsedSchedule = null;
            }
        }
        markUpdated();
    }

    public CronSchedule getParsedSchedule() {
        return parsedSchedule;
    }

    public long getLastTriggerTick() {
        return lastTriggerTick;
    }

    @Override
    public List<MqttTab> getTabs() {
        List<MqttTab> tabs = new ArrayList<>();
        tabs.add(new CronTimerTab());
        return tabs;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("cronExpression", this.cronExpression);
        output.putLong("lastTriggerTick", this.lastTriggerTick);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.cronExpression = input.getString("cronExpression").orElse("");
        this.lastTriggerTick = input.getLong("lastTriggerTick").orElse(-1L);
        if (!this.cronExpression.isBlank()) {
            try {
                this.parsedSchedule = CronSchedule.parse(this.cronExpression);
            } catch (IllegalArgumentException e) {
                MineQTT.LOGGER.warn("[CronTimer] Failed to load cron expression: {}", e.getMessage());
                this.parsedSchedule = null;
            }
        }
    }

    /**
     * Server-side ticker: evaluates the cron schedule each tick and fires a 1-tick redstone pulse.
     */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (!(blockEntity instanceof CronTimerBlockEntity entity)) return;
            if (entity.parsedSchedule == null) return;

            // If currently powered, turn off (this is the delayed off-tick)
            if (state.hasProperty(POWER) && state.getValue(POWER) > 0) {
                BlockState newState = state.setValue(POWER, 0);
                level.setBlock(pos, newState, Block.UPDATE_ALL);
                if (level.getBlockState(pos).getBlock() instanceof CronTimerBlock block) {
                    block.updateNeighbors(level, pos, newState);
                }
                return;
            }

            long currentTick = level.getGameTime();

            // Skip if we already triggered this game tick
            if (entity.lastTriggerTick == currentTick) return;

            LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
            // Use the tick within the second (0-19) derived from game time
            int tickInSecond = (int) (currentTick % 20);

            if (entity.parsedSchedule.matches(now, tickInSecond)) {
                // Fire a 1-tick pulse at POWER=15
                BlockState newState = state.setValue(POWER, 15);
                level.setBlock(pos, newState, Block.UPDATE_ALL);
                if (level.getBlockState(pos).getBlock() instanceof CronTimerBlock block) {
                    block.updateNeighbors(level, pos, newState);
                }

                entity.lastTriggerTick = currentTick;
                entity.setChanged();

                // Schedule the block entity to tick again next tick to turn off
                level.scheduleTick(pos, (CronTimerBlock) state.getBlock(), 1);
            }
        }
    }

    /**
     * Handles the scheduled tick to turn off the redstone power.
     */
    public static void clearPower(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(POWER) && state.getValue(POWER) > 0) {
            BlockState newState = state.setValue(POWER, 0);
            level.setBlock(pos, newState, Block.UPDATE_ALL);
            if (level.getBlockState(pos).getBlock() instanceof CronTimerBlock block) {
                block.updateNeighbors(level, pos, newState);
            }
        }
    }
}

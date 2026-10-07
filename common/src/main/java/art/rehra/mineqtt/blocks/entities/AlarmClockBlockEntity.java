package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.AlarmClockBlock;
import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.tabs.AlarmClockTab;
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

import static art.rehra.mineqtt.blocks.AlarmClockBlock.POWER;

/**
 * Block entity for the Alarm Clock: stores a single alarm time (in Minecraft ticks),
 * checks each tick for a match, and fires a 1-tick redstone pulse.
 */
public class AlarmClockBlockEntity extends BaseMqttBlockEntity {

    /**
     * The alarm time in Minecraft ticks (0-23999). -1 means no alarm set.
     */
    private int alarmTime = -1;

    /**
     * The last Minecraft day number (gameTime / 24000) when the alarm was triggered.
     * Used to prevent re-triggering within the same day.
     */
    private long lastAlarmDay = -1;

    /**
     * Timestamp of the last trigger (level.getGameTime()).
     */
    private long lastTriggerTime = 0;

    public AlarmClockBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.ALARM_CLOCK_BLOCK.get(), pos, blockState);
    }

    @Override
    protected String getDefaultTopic() {
        return "";
    }

    @Override
    protected net.minecraft.network.chat.Component getDefaultName() {
        return net.minecraft.network.chat.Component.translatable("container.mineqtt.alarm_clock");
    }

    /**
     * Returns the alarm time in Minecraft ticks, or -1 if no alarm is set.
     */
    public int getAlarmTime() {
        return alarmTime;
    }

    /**
     * Returns true if an alarm is configured.
     */
    public boolean hasAlarm() {
        return alarmTime >= 0;
    }

    /**
     * Returns the last Minecraft day when the alarm fired.
     */
    public long getLastAlarmDay() {
        return lastAlarmDay;
    }

    /**
     * Returns the last trigger time in game ticks.
     */
    public long getLastTriggerTime() {
        return lastTriggerTime;
    }

    /**
     * Sets the single alarm time. Use -1 to clear the alarm.
     */
    public void setAlarm(int minecraftTicks) {
        if (minecraftTicks != -1 && (minecraftTicks < 0 || minecraftTicks >= 24000)) {
            MineQTT.LOGGER.warn("[AlarmClock] Invalid alarm time at {}: {} ticks (must be 0-23999)", this.worldPosition, minecraftTicks);
            return;
        }
        this.alarmTime = minecraftTicks;
        markUpdated();
    }

    /**
     * Converts an alarm time string (HH:MM) to Minecraft ticks.
     * Example: "09:00" -> 9000, "12:30" -> 12500
     */
    public static int parseAlarmTime(String timeStr) {
        String[] parts = timeStr.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid time format: " + timeStr + " (expected HH:MM)");
        }
        int hours = Integer.parseInt(parts[0].trim());
        int minutes = Integer.parseInt(parts[1].trim());
        if (hours < 0 || hours > 23) {
            throw new IllegalArgumentException("Hours must be 0-23, got " + hours);
        }
        if (minutes < 0 || minutes > 59) {
            throw new IllegalArgumentException("Minutes must be 0-59, got " + minutes);
        }
        // Map 24-hour time to Minecraft ticks: hour * 1000 + minute * (1000/60)
        // Simplified: hour * 1000 + minute * 16 (approximate)
        // More precise: (hours * 3600 + minutes * 60) * 24000 / 86400
        return (hours * 3600 + minutes * 60) * 24000 / 86400;
    }

    /**
     * Converts Minecraft ticks to a human-readable HH:MM string.
     */
    public static String formatAlarmTime(int minecraftTicks) {
        // Convert Minecraft ticks to seconds of day, then to HH:MM
        int totalSeconds = minecraftTicks * 86400 / 24000;
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        return String.format("%02d:%02d", hours, minutes);
    }

    @Override
    public List<MqttTab> getTabs() {
        List<MqttTab> tabs = new ArrayList<>();
        tabs.add(new AlarmClockTab());
        return tabs;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("alarmTime", this.alarmTime);
        output.putLong("lastAlarmDay", this.lastAlarmDay);
        output.putLong("lastTriggerTime", this.lastTriggerTime);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.alarmTime = input.getInt("alarmTime").orElse(-1);
        this.lastAlarmDay = input.getLong("lastAlarmDay").orElse(-1L);
        this.lastTriggerTime = input.getLong("lastTriggerTime").orElse(0L);
    }

    /**
     * Server-side ticker: checks current Minecraft time against the configured alarm
     * and fires a 1-tick redstone pulse when a match is found.
     */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (!(blockEntity instanceof AlarmClockBlockEntity entity)) return;

            // If currently powered, turn off (this is the delayed off-tick)
            if (state.hasProperty(POWER) && state.getValue(POWER) > 0) {
                BlockState newState = state.setValue(POWER, 0);
                level.setBlock(pos, newState, Block.UPDATE_ALL);
                if (level.getBlockState(pos).getBlock() instanceof AlarmClockBlock block) {
                    block.updateNeighbors(level, pos, newState);
                }
                return;
            }

            // No alarm configured
            if (!entity.hasAlarm()) return;

            long gameTime = level.getGameTime();
            int timeOfDay = (int) (gameTime % 24000);
            long currentDay = gameTime / 24000;

            // Reset triggered status at the start of a new day
            if (currentDay != entity.lastAlarmDay) {
                entity.lastAlarmDay = currentDay;
            }

            int alarmTime = entity.alarmTime;

            // Allow a small window (±1 tick) for matching
            if (Math.abs(timeOfDay - alarmTime) <= 1) {
                // Check if we already triggered today
                if (entity.lastAlarmDay != currentDay) {
                    // Fire a 1-tick pulse at POWER=15
                    BlockState newState = state.setValue(POWER, 15);
                    level.setBlock(pos, newState, Block.UPDATE_ALL);
                    if (level.getBlockState(pos).getBlock() instanceof AlarmClockBlock block) {
                        block.updateNeighbors(level, pos, newState);
                    }

                    entity.lastTriggerTime = gameTime;
                    entity.lastAlarmDay = currentDay;
                    entity.setChanged();

                    // Schedule the block entity to tick again next tick to turn off
                    level.scheduleTick(pos, (AlarmClockBlock) state.getBlock(), 1);
                }
            }
        }
    }
}

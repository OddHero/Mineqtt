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
 * Block entity for the Alarm Clock: manages a list of alarm times (in Minecraft ticks),
 * checks each tick for matches, and fires a 1-tick redstone pulse.
 */
public class AlarmClockBlockEntity extends BaseMqttBlockEntity {

    /**
     * List of alarm times in Minecraft ticks (0-23999). Each entry corresponds to an
     * entry in the enabled list at the same index.
     */
    private final List<Integer> alarmTimes = new ArrayList<>();

    /**
     * Enabled state for each alarm. True means the alarm will fire.
     */
    private final List<Boolean> alarmEnabled = new ArrayList<>();

    /**
     * The last Minecraft day number (gameTime / 24000) when alarms were triggered.
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
     * Returns the list of alarm times in Minecraft ticks.
     */
    public List<Integer> getAlarmTimes() {
        return new ArrayList<>(alarmTimes);
    }

    /**
     * Returns the list of enabled states for each alarm.
     */
    public List<Boolean> getAlarmEnabled() {
        return new ArrayList<>(alarmEnabled);
    }

    /**
     * Returns the last Minecraft day when alarms fired.
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
     * Adds a new alarm time. Validates the range (0-23999).
     */
    public void addAlarm(int minecraftTicks) {
        if (minecraftTicks < 0 || minecraftTicks >= 24000) {
            MineQTT.LOGGER.warn("[AlarmClock] Invalid alarm time at {}: {} ticks (must be 0-23999)", this.worldPosition, minecraftTicks);
            return;
        }
        this.alarmTimes.add(minecraftTicks);
        this.alarmEnabled.add(true);
        markUpdated();
    }

    /**
     * Removes the alarm at the given index.
     */
    public void removeAlarm(int index) {
        if (index < 0 || index >= this.alarmTimes.size()) {
            MineQTT.LOGGER.warn("[AlarmClock] Invalid alarm index at {}: {}", this.worldPosition, index);
            return;
        }
        this.alarmTimes.remove(index);
        this.alarmEnabled.remove(index);
        markUpdated();
    }

    /**
     * Toggles the enabled state of the alarm at the given index.
     */
    public void toggleAlarm(int index) {
        if (index < 0 || index >= this.alarmEnabled.size()) {
            MineQTT.LOGGER.warn("[AlarmClock] Invalid alarm index at {}: {}", this.worldPosition, index);
            return;
        }
        this.alarmEnabled.set(index, !this.alarmEnabled.get(index));
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

        // Save alarm times
        output.putInt("alarmCount", this.alarmTimes.size());
        for (int i = 0; i < this.alarmTimes.size(); i++) {
            output.putInt("alarmTime_" + i, this.alarmTimes.get(i));
            output.putBoolean("alarmEnabled_" + i, this.alarmEnabled.get(i));
        }

        output.putLong("lastAlarmDay", this.lastAlarmDay);
        output.putLong("lastTriggerTime", this.lastTriggerTime);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);

        int count = input.getInt("alarmCount").orElse(0);
        this.alarmTimes.clear();
        this.alarmEnabled.clear();
        for (int i = 0; i < count; i++) {
            input.getInt("alarmTime_" + i).ifPresent(t -> this.alarmTimes.add(t));
            this.alarmEnabled.add(input.getBooleanOr("alarmEnabled_" + i, true));
        }

        this.lastAlarmDay = input.getLong("lastAlarmDay").orElse(-1L);
        this.lastTriggerTime = input.getLong("lastTriggerTime").orElse(0L);
    }

    /**
     * Server-side ticker: checks current Minecraft time against configured alarms
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

            // No alarms configured
            if (entity.alarmTimes.isEmpty()) return;

            long gameTime = level.getGameTime();
            int timeOfDay = (int) (gameTime % 24000);
            long currentDay = gameTime / 24000;

            // Reset triggered status at the start of a new day
            if (currentDay != entity.lastAlarmDay) {
                entity.lastAlarmDay = currentDay;
            }

            // Check each alarm
            for (int i = 0; i < entity.alarmTimes.size(); i++) {
                if (!entity.alarmEnabled.get(i)) continue;

                int alarmTime = entity.alarmTimes.get(i);

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
                        break; // Fire only one alarm per tick
                    }
                }
            }
        }
    }
}

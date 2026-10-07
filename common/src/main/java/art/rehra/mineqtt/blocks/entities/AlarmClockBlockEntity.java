package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.AlarmClockBlock;
import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.tabs.AlarmClockTab;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.clock.WorldClocks;
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
 * checks each tick for a match, and fires a button-length (30-tick) redstone pulse.
 */
public class AlarmClockBlockEntity extends BaseMqttBlockEntity {

    /**
     * Pulse duration in Minecraft ticks (30 ticks = 1.5s, standard wooden button pulse length).
     */
    public static final int PULSE_DURATION = 30;

    /**
     * Detection window in ticks around the alarm time (±20 ticks = 1s tolerance).
     */
    public static final int DETECTION_WINDOW = 20;

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
     * Gets the global Minecraft day time, reading from overworld clock manager if available.
     */
    public static long getWorldDayTime(Level level) {
        if (level == null) return 0;
        try {
            var registry = level.registryAccess().lookup(Registries.WORLD_CLOCK);
            if (registry.isPresent()) {
                var overworldClock = registry.get().get(WorldClocks.OVERWORLD);
                if (overworldClock.isPresent()) {
                    return level.clockManager().getTotalTicks(overworldClock.get());
                }
            }
        } catch (Throwable ignored) {
        }
        return level.getGameTime();
    }

    /**
     * Returns the remaining ticks until the next alarm trigger, or 0 if no alarm is set.
     */
    public long getRemainingTicks(Level level) {
        if (level == null || !hasAlarm()) return 0;
        long dayTime = getWorldDayTime(level);
        int timeOfDay = (int) (dayTime % 24000);
        if (timeOfDay < 0) {
            timeOfDay += 24000;
        }
        long diff = alarmTime - timeOfDay;
        if (diff < 0) {
            diff += 24000;
        }
        return diff;
    }

    /**
     * Converts an alarm time string (HH:MM) to Minecraft ticks.
     * Minecraft daytime: 06:00 is tick 0 (sunrise), 12:00 is tick 6000 (noon),
     * 18:00 is tick 12000 (sunset), 00:00 is tick 18000 (midnight).
     * Example: "06:00" -> 0, "12:00" -> 6000, "18:00" -> 12000, "00:00" -> 18000
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
        int totalMinutes = hours * 60 + minutes;
        int adjustedMinutes = (totalMinutes - 360 + 1440) % 1440;
        return (int) Math.round(adjustedMinutes * 24000.0 / 1440.0) % 24000;
    }

    /**
     * Converts Minecraft ticks to a human-readable HH:MM string matching standard Minecraft daytime.
     * Tick 0 -> 06:00, Tick 6000 -> 12:00, Tick 12000 -> 18:00, Tick 18000 -> 00:00.
     */
    public static String formatAlarmTime(int minecraftTicks) {
        int adjustedMinutes = (int) Math.round(minecraftTicks * 1440.0 / 24000.0) % 1440;
        int totalMinutes = (adjustedMinutes + 360) % 1440;
        int hours = totalMinutes / 60;
        int minutes = totalMinutes % 60;
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
     * and fires a button-length (30-tick) redstone pulse when a match is found.
     */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (!(blockEntity instanceof AlarmClockBlockEntity entity)) return;

            long gameTime = level.getGameTime();

            // If currently powered, check if the pulse duration has elapsed
            if (state.hasProperty(POWER) && state.getValue(POWER) > 0) {
                if (gameTime - entity.lastTriggerTime >= PULSE_DURATION || gameTime < entity.lastTriggerTime) {
                    BlockState newState = state.setValue(POWER, 0);
                    level.setBlock(pos, newState, Block.UPDATE_ALL);
                    if (level.getBlockState(pos).getBlock() instanceof AlarmClockBlock block) {
                        block.updateNeighbors(level, pos, newState);
                    }
                }
                return;
            }

            // No alarm configured
            if (!entity.hasAlarm()) return;

            long dayTime = getWorldDayTime(level);
            int timeOfDay = (int) (dayTime % 24000);
            if (timeOfDay < 0) {
                timeOfDay += 24000;
            }
            long currentDay = dayTime / 24000;
            int alarmTime = entity.alarmTime;

            int diff = timeOfDay - alarmTime;
            // Handle wrap-around at midnight / day boundary
            if (diff > 12000) diff -= 24000;
            if (diff < -12000) diff += 24000;

            // Allow matching within detection window
            if (Math.abs(diff) <= DETECTION_WINDOW) {
                // Check if we already triggered today
                if (entity.lastAlarmDay != currentDay) {
                    // Fire a button-length pulse at POWER=15
                    BlockState newState = state.setValue(POWER, 15);
                    level.setBlock(pos, newState, Block.UPDATE_ALL);
                    if (level.getBlockState(pos).getBlock() instanceof AlarmClockBlock block) {
                        block.updateNeighbors(level, pos, newState);
                    }

                    entity.lastTriggerTime = gameTime;
                    entity.lastAlarmDay = currentDay;
                    entity.setChanged();
                }
            }
        }
    }
}

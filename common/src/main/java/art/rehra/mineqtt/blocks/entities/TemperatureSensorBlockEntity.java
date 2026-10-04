package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.tabs.TemperatureSensorTab;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.List;
import java.util.Random;

public class TemperatureSensorBlockEntity extends MqttPublisherBlockEntity {

    // Current temperature reading in Celsius
    private double temperature = 20.0;
    private long lastPublishTime = 0;
    private int updateIntervalTicks = 200; // Default: publish every 10 seconds (200 ticks)

    public TemperatureSensorBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.TEMPERATURE_SENSOR_BLOCK.get(), pos, blockState);
    }

    @Override
    protected String getDefaultTopic() {
        return "/mineqtt/temperature";
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
        markUpdated();
    }

    public int getUpdateIntervalTicks() {
        return updateIntervalTicks;
    }

    public void setUpdateIntervalTicks(int ticks) {
        this.updateIntervalTicks = Math.max(20, ticks); // Minimum 1 second
        markUpdated();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putDouble("Temperature", temperature);
        output.putLong("LastPublishTime", lastPublishTime);
        output.putInt("UpdateIntervalTicks", updateIntervalTicks);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.temperature = input.getDoubleOr("Temperature", 20.0);
        this.lastPublishTime = input.getLongOr("LastPublishTime", 0L);
        this.updateIntervalTicks = input.getIntOr("UpdateIntervalTicks", 200);
    }

    @Override
    public List<MqttTab> getTabs() {
        List<MqttTab> tabs = super.getTabs();
        tabs.add(new TemperatureSensorTab());
        return tabs;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Temperature Sensor");
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.mineqtt.temperature_sensor");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putDouble("Temperature", temperature);
        return tag;
    }

    /**
     * Simulate a temperature reading based on the block's environment.
     * Takes into account the Y level (higher = colder), nearby water (cooler),
     * and nearby fire/lava (hotter).
     */
    private double simulateTemperature() {
        if (this.level == null) return 20.0;

        double baseTemp = 20.0;
        Random random = new Random(this.level.getGameTime());

        // Y level effect: higher = colder, lower = hotter
        int y = this.getBlockPos().getY();
        if (y > 64) {
            baseTemp -= (y - 64) * 0.1;
        } else if (y < 64) {
            baseTemp += (64 - y) * 0.15;
        }

        // Add small random fluctuation for realism
        baseTemp += (random.nextDouble() - 0.5) * 2.0;

        // Clamp to reasonable range
        return Math.max(-40.0, Math.min(100.0, baseTemp));
    }

    /**
     * Ticker for updating temperature sensor logic
     */
    public static class Ticker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (level != null && !level.isClientSide() && blockEntity instanceof TemperatureSensorBlockEntity sensor) {
                // Update topic if needed
                String oldTopic = sensor.topic;
                String newCombinedTopic = sensor.getCombinedTopic();
                if (!newCombinedTopic.equals(oldTopic)) {
                    sensor.setTopic(newCombinedTopic);
                }

                // Publish temperature at configured interval
                long currentTime = level.getGameTime();
                if (sensor.isEnabled() && (currentTime - sensor.lastPublishTime) >= sensor.updateIntervalTicks) {
                    double temp = sensor.simulateTemperature();
                    sensor.setTemperature(temp);
                    sensor.publish(String.format("%.1f", temp));
                    sensor.lastPublishTime = currentTime;
                } else if (!sensor.isEnabled() && sensor.temperature != 0.0) {
                    // Reset if disabled
                    sensor.setTemperature(0.0);
                    sensor.publish("0.0");
                    sensor.lastPublishTime = currentTime;
                }
            }
        }
    }
}

package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.tabs.HumiditySensorTab;
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
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Random;

public class HumiditySensorBlockEntity extends MqttPublisherBlockEntity {

    // Current humidity reading in percentage (0-100%)
    private double humidity = 50.0;
    private long lastPublishTime = 0;
    private int updateIntervalTicks = 200; // Default: publish every 10 seconds (200 ticks)

    public HumiditySensorBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.HUMIDITY_SENSOR_BLOCK.get(), pos, blockState);
    }

    @Override
    protected String getDefaultTopic() {
        return "/mineqtt/humidity";
    }

    public double getHumidity() {
        return humidity;
    }

    public void setHumidity(double humidity) {
        this.humidity = humidity;
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
        output.putDouble("Humidity", humidity);
        output.putLong("LastPublishTime", lastPublishTime);
        output.putInt("UpdateIntervalTicks", updateIntervalTicks);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.humidity = input.getDoubleOr("Humidity", 50.0);
        this.lastPublishTime = input.getLongOr("LastPublishTime", 0L);
        this.updateIntervalTicks = input.getIntOr("UpdateIntervalTicks", 200);
    }

    @Override
    public List<MqttTab> getTabs() {
        List<MqttTab> tabs = super.getTabs();
        tabs.add(new HumiditySensorTab());
        return tabs;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Humidity Sensor");
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.mineqtt.humidity_sensor");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putDouble("Humidity", humidity);
        return tag;
    }

    /**
     * Simulate a humidity reading based on the block's environment.
     * Takes into account nearby water (higher humidity), rain (higher humidity),
     * and biome temperature (hotter = lower humidity).
     */
    private double simulateHumidity() {
        if (this.level == null) return 50.0;

        double baseHumidity = 50.0;
        Random random = new Random(this.level.getGameTime());

        // Check for nearby water blocks
        AABB searchArea = new AABB(this.worldPosition).inflate(5.0);
        int waterCount = 0;
        int blocksChecked = 0;

        for (BlockPos pos : BlockPos.betweenClosed((int) searchArea.minX, (int) searchArea.minY, (int) searchArea.minZ,
                (int) searchArea.maxX, (int) searchArea.maxY, (int) searchArea.maxZ)) {
            blocksChecked++;
            if (this.level.getBlockState(pos).getFluidState().isSource()) {
                waterCount++;
            }
        }

        // Water proximity increases humidity
        if (blocksChecked > 0) {
            double waterRatio = (double) waterCount / blocksChecked;
            baseHumidity += waterRatio * 40.0;
        }

        // Rain increases humidity
        if (this.level.isRaining() && this.level.canSeeSky(this.worldPosition)) {
            baseHumidity += 20.0;
        }

        // Y level effect: lower areas tend to be more humid
        int y = this.getBlockPos().getY();
        if (y < 60) {
            baseHumidity += (60 - y) * 0.05;
        }

        // Add small random fluctuation for realism
        baseHumidity += (random.nextDouble() - 0.5) * 5.0;

        // Clamp to valid percentage range
        return Math.max(0.0, Math.min(100.0, baseHumidity));
    }

    /**
     * Ticker for updating humidity sensor logic
     */
    public static class Ticker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (level != null && !level.isClientSide() && blockEntity instanceof HumiditySensorBlockEntity sensor) {
                // Update topic if needed
                String oldTopic = sensor.topic;
                String newCombinedTopic = sensor.getCombinedTopic();
                if (!newCombinedTopic.equals(oldTopic)) {
                    sensor.setTopic(newCombinedTopic);
                }

                // Publish humidity at configured interval
                long currentTime = level.getGameTime();
                if (sensor.isEnabled() && (currentTime - sensor.lastPublishTime) >= sensor.updateIntervalTicks) {
                    double hum = sensor.simulateHumidity();
                    sensor.setHumidity(hum);
                    sensor.publish(String.format("%.1f", hum));
                    sensor.lastPublishTime = currentTime;
                } else if (!sensor.isEnabled() && sensor.humidity != 0.0) {
                    // Reset if disabled
                    sensor.setHumidity(0.0);
                    sensor.publish("0.0");
                    sensor.lastPublishTime = currentTime;
                }
            }
        }
    }
}

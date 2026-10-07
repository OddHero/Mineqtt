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
     * Considers biome precipitation, daytime, nearby fire/lava/water,
     * rain, and sky visibility for realistic readings.
     */
    private double simulateHumidity() {
        if (this.level == null) return 50.0;

        BlockPos pos = this.getBlockPos();
        double baseHumidity = 50.0;

        // Biome precipitation: use hasPrecipitation() as proxy for downfall
        boolean hasPrecipitation = this.level.getBiome(pos).value().hasPrecipitation();
        baseHumidity = hasPrecipitation ? 60.0 : 25.0; // Precipitation biomes are more humid

        // Daytime factor: slight humidity variation
        long gameTime = this.level.getGameTime() % 24000;
        if (gameTime >= 6000 && gameTime <= 12000) {
            // Daytime: slightly drier
            baseHumidity -= 5.0;
        } else if (gameTime > 18000 || gameTime < 6000) {
            // Nighttime: slightly more humid
            baseHumidity += 5.0;
        }

        // Sky visibility: enclosed spaces tend to be more humid
        boolean skyVisible = this.level.canSeeSky(pos);
        if (!skyVisible) {
            baseHumidity += 10.0; // Caves are more humid
        }

        // Nearby water increases humidity
        int waterCount = 0;
        int fireCount = 0;
        int scanRadius = 5;
        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dy = -scanRadius; dy <= scanRadius; dy++) {
                for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos checkPos = pos.offset(dx, dy, dz);
                    var blockState = this.level.getBlockState(checkPos);
                    net.minecraft.world.level.block.Block block = blockState.getBlock();
                    if (block == net.minecraft.world.level.block.Blocks.WATER
                            || block == net.minecraft.world.level.block.Blocks.FROSTED_ICE
                            || blockState.getFluidState().isSource()) {
                        waterCount++;
                    }
                    if (block == net.minecraft.world.level.block.Blocks.FIRE
                            || block == net.minecraft.world.level.block.Blocks.SOUL_FIRE
                            || block == net.minecraft.world.level.block.Blocks.LAVA
                            || block == net.minecraft.world.level.block.Blocks.CAMPFIRE
                            || block == net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE
                            || block == net.minecraft.world.level.block.Blocks.MAGMA_BLOCK) {
                        fireCount++;
                    }
                }
            }
        }

        // Water proximity increases humidity
        if (waterCount > 0) {
            baseHumidity += Math.min(waterCount * 2.0, 30.0); // Up to +30%
        }

        // Fire/lava decreases humidity
        if (fireCount > 0) {
            baseHumidity -= Math.min(fireCount * 5.0, 30.0); // Up to -30%
        }

        // Rain increases humidity significantly
        if (this.level.isRaining() && skyVisible) {
            baseHumidity += 20.0;
        }

        // Y level effect: lower areas tend to be more humid
        int y = pos.getY();
        if (y < 60) {
            baseHumidity += (60 - y) * 0.05;
        }

        // Random fluctuation for realism
        Random random = new Random(this.level.getGameTime() + pos.asLong());
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

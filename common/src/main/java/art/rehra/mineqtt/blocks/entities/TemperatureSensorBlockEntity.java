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
     * Considers biome temperature, light level, daytime, nearby fire/lava/water,
     * and sky visibility for realistic readings.
     */
    private double simulateTemperature() {
        if (this.level == null) return 20.0;

        BlockPos pos = this.getBlockPos();
        double baseTemp = 20.0;

        // Biome-based temperature: use precipitation as a proxy (snowy biomes are colder)
        boolean hasPrecipitation = this.level.getBiome(pos).value().hasPrecipitation();
        baseTemp = hasPrecipitation ? 10.0 : 25.0; // Snowy/rainy biomes are cooler

        // Y level effect: higher = colder, lower = hotter
        int y = pos.getY();
        if (y > 64) {
            baseTemp -= (y - 64) * 0.05;
        } else if (y < 64) {
            baseTemp += (64 - y) * 0.1;
        }

        // Daytime factor: day is warmer, night is cooler
        long gameTime = this.level.getGameTime() % 24000;
        double dayFactor = 0.0;
        if (gameTime >= 6000 && gameTime <= 12000) {
            // Daytime: warmer
            dayFactor = 5.0;
        } else if (gameTime > 13000 && gameTime <= 18000) {
            // Sunset: moderate
            dayFactor = 2.0;
        } else if (gameTime > 18000 || gameTime < 6000) {
            // Nighttime: cooler
            dayFactor = -5.0;
        }
        baseTemp += dayFactor;

        // Light level: dark areas slightly cooler
        int lightLevel = this.level.getLightEmission(pos);
        if (lightLevel == 0) {
            // Check ambient light
            int ambientLight = this.level.getMaxLocalRawBrightness(pos);
            if (ambientLight < 4) {
                baseTemp -= 2.0; // Dark caves are cooler
            }
        }

        // Sky visibility: exposed to sky has more temperature swings
        boolean skyVisible = this.level.canSeeSky(pos);
        if (skyVisible) {
            baseTemp += dayFactor * 0.5; // Amplify day/night effect when exposed
        } else {
            // Enclosed spaces have more stable temperature
            baseTemp -= dayFactor * 0.3;
        }

        // Nearby fire/lava detection (5-block radius)
        int fireCount = 0;
        int waterCount = 0;
        int scanRadius = 5;
        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dy = -scanRadius; dy <= scanRadius; dy++) {
                for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos checkPos = pos.offset(dx, dy, dz);
                    var blockState = this.level.getBlockState(checkPos);
                    net.minecraft.world.level.block.Block block = blockState.getBlock();
                    if (block == net.minecraft.world.level.block.Blocks.FIRE
                            || block == net.minecraft.world.level.block.Blocks.SOUL_FIRE
                            || block == net.minecraft.world.level.block.Blocks.LAVA
                            || block == net.minecraft.world.level.block.Blocks.CAMPFIRE
                            || block == net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE
                            || block == net.minecraft.world.level.block.Blocks.MAGMA_BLOCK) {
                        fireCount++;
                    } else if (block == net.minecraft.world.level.block.Blocks.WATER
                            || block == net.minecraft.world.level.block.Blocks.FROSTED_ICE) {
                        waterCount++;
                    }
                }
            }
        }

        // Fire/lava increases temperature
        if (fireCount > 0) {
            baseTemp += Math.min(fireCount * 3.0, 30.0); // Up to +30°C
        }

        // Water decreases temperature
        if (waterCount > 0) {
            baseTemp -= Math.min(waterCount * 1.0, 10.0); // Up to -10°C
        }

        // Random fluctuation for realism
        Random random = new Random(this.level.getGameTime() + pos.asLong());
        baseTemp += (random.nextDouble() - 0.5) * 2.0;

        // Clamp to reasonable range
        return Math.max(-50.0, Math.min(120.0, baseTemp));
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

package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommandResult;
import art.rehra.mineqtt.device.CommandSource;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.ClimateSensor;
import art.rehra.mineqtt.device.types.ClimateSensor.Climate;
import art.rehra.mineqtt.legacy.LegacyNbtV1;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * The Climate Sensor ({@link ClimateSensor}), in the block entity of the v1 temperature sensor, whose id it keeps. Its
 * settings and its last reading are in the {@code mineqtt} compound; v1 data of a temperature sensor, or of a humidity
 * sensor that converted into it, is read on load and adopted when the device attaches. Clients get no render data.
 */
public class TemperatureSensorBlockEntity extends DeviceBlockEntity {
    private final DeviceLink link = new DeviceLink(ClimateSensor.TYPE);
    private Climate climate = Climate.DEFAULT;
    // Whether the sensor took a reading since it attached; the reading it loaded with may be stale.
    private boolean measured;

    public TemperatureSensorBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.TEMPERATURE_SENSOR_BLOCK.get(), pos, blockState);
    }

    @Override
    public DeviceType deviceType() {
        return ClimateSensor.TYPE;
    }

    @Override
    public DeviceLink deviceLink() {
        return link;
    }

    @Override
    public void writeState(StateWriter out) {
        climate.write(out).put(CommonFields.LOCATION, GlobalPos.of(level.dimension(), worldPosition));
    }

    @Override
    public CommandResult applyCommand(CommandReader in, CommandSource source) {
        Optional<Climate> next = climate.apply(in);
        if (next.isEmpty()) return CommandResult.rejected("not a climate sensor command: " + in.keys());
        if (next.get().equals(climate)) return CommandResult.unchanged();
        boolean rawValueChanged = next.get().rawValue() != climate.rawValue();
        climate = next.get();
        setChanged();
        // The Raw Link carries the reading now named at once: a retained copy of the other one must not wait for the next reading.
        if (rawValueChanged && measured) link.publishRaw(this, climate.raw());
        return CommandResult.applied();
    }

    /**
     * The reading the Raw Link output carries, once the sensor took one after the attach: until then it is the reading
     * saved at the unload, which a retained publish must never repeat (M2 plan D2).
     */
    @Override
    public @Nullable String rawValue() {
        return measured ? climate.raw() : null;
    }

    // Takes a reading: the Raw Link gets it every time, as v1 sensors published theirs; the state as the type allows.
    private void measure(ServerLevel level) {
        Climate next = climate.read(ClimateSensor.measure(environment(level)), level.getGameTime());
        measured = true;
        boolean changed = !next.reading().equals(climate.reading());
        climate = next;
        setChanged();
        link.publishRaw(this, climate.raw());
        if (changed) link.stateChanged(this);
    }

    // What the sensor feels where it stands. Only blocks in loaded chunks count, so a reading never loads a chunk.
    private ClimateSensor.Environment environment(ServerLevel level) {
        BlockPos pos = worldPosition;
        Holder<Biome> biome = level.getBiome(pos);
        Biome.Precipitation precipitation = biome.value().getPrecipitationAt(pos, level.getSeaLevel());
        int heat = 0;
        int cold = 0;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int radius = ClimateSensor.SCAN_RADIUS;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    at.setWithOffset(pos, dx, dy, dz);
                    if (!level.isLoaded(at)) continue;
                    BlockState state = level.getBlockState(at);
                    if (state.is(BlockTags.FIRE) || state.is(BlockTags.CAMPFIRES) || state.is(Blocks.MAGMA_BLOCK) || state.getFluidState().is(FluidTags.LAVA)) {
                        heat++;
                    } else if (state.is(BlockTags.ICE) || state.getFluidState().is(FluidTags.WATER)) {
                        cold++;
                    }
                }
            }
        }
        return new ClimateSensor.Environment(biome.unwrapKey().map(key -> key.identifier().toString()).orElse(""), biome.value().getBaseTemperature(),
                switch (precipitation) {
                    case NONE -> ClimateSensor.Precipitation.NONE;
                    case RAIN -> ClimateSensor.Precipitation.RAIN;
                    case SNOW -> ClimateSensor.Precipitation.SNOW;
                }, pos.getY() - level.getSeaLevel(), level.getSkyDarken(), level.getBrightness(LightLayer.SKY, pos),
                level.getBrightness(LightLayer.BLOCK, pos), level.canSeeSky(pos), level.isRainingAt(pos), heat, cold);
    }

    @Override
    protected void saveDevice(ValueOutput output) {
        saveSensor(link, climate, output);
    }

    @Override
    protected void loadDevice(ValueInput input) {
        climate = loadSensor(link, input);
    }

    /** A placed copy keeps its interval and Raw Link value, and reads its own spot at its first tick. */
    @Override
    protected void placedCopy() {
        climate = climate.placed();
        measured = false;
        if (level instanceof ServerLevel serverLevel) climate = climate.read(ClimateSensor.measure(environment(serverLevel)), serverLevel.getGameTime());
    }

    /**
     * A sensor's data: the link with the sensor as its {@code state}, or v1's keys while its v1 data waits for adoption,
     * so it is still v1 data when it loads again.
     */
    public static void saveSensor(DeviceLink link, Climate climate, ValueOutput output) {
        if (link.awaitingAdoption()) {
            link.saveV1(output);
            LegacyNbtV1.writeClimate(output, climate);
        } else {
            link.save(output).store("state", Climate.CODEC, climate);
        }
    }

    /**
     * Loads a sensor's link and returns the sensor; v1 data of a temperature or humidity sensor, also a placed v1 item's,
     * is kept for adoption.
     */
    public static Climate loadSensor(DeviceLink link, ValueInput input) {
        return link.load(input, LegacyNbtV1.hasClimateKeys(input))
                .map(data -> data.read("state", Climate.CODEC).orElse(Climate.DEFAULT))
                .orElseGet(() -> LegacyNbtV1.climateState(input));
    }

    /** A reading at the first tick after the attach, then every interval. */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (!(blockEntity instanceof TemperatureSensorBlockEntity sensor) || !(level instanceof ServerLevel serverLevel)) return;
            if (!sensor.link.attached()) {
                sensor.measured = false;
            } else if (!sensor.measured || sensor.climate.due(level.getGameTime())) {
                sensor.measure(serverLevel);
            }
        }
    }
}

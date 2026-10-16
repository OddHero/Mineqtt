package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.blocks.entities.VibrationSensorBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * The Vibration Sensor ({@link VibrationSensorBlockEntity}): hears vibrations all around it, so it has no facing. Its block
 * entity is the game event listener ({@code EntityBlock#getListener} finds it); a comparator reads the last vibration's
 * frequency while the sensor reports one, as a calibrated sculk sensor's.
 */
public class VibrationSensorBlock extends BaseMqttBlock {
    public static final MapCodec<VibrationSensorBlock> CODEC = simpleCodec(VibrationSensorBlock::new);

    public VibrationSensorBlock(Properties properties) {
        super(properties, CODEC);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VibrationSensorBlockEntity(pos, state);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    //? if >=1.21.9 {
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
    //?} else {
    /*protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
    *///?}
        return level.getBlockEntity(pos) instanceof VibrationSensorBlockEntity sensor ? sensor.comparatorLevel() : 0;
    }

    @Override
    /** The server carries vibrations to the sensor every tick; the client has nothing to tick. */
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : new VibrationSensorBlockEntity.ServerTicker<>();
    }
}

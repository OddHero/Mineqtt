package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.blocks.entities.DynamicPublisherBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.redstone.ExperimentalRedstoneUtils;
import net.minecraft.world.level.redstone.Orientation;
import org.jetbrains.annotations.Nullable;

import java.util.Random;

/**
 * Dynamic Publisher block: publishes dynamically generated server information on redstone rising-edge.
 */
public class DynamicPublisherBlock extends BaseMqttBlock {

    public static final MapCodec<DynamicPublisherBlock> CODEC = simpleCodec(DynamicPublisherBlock::new);
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public DynamicPublisherBlock(Properties properties) {
        super(properties, CODEC);
        this.registerDefaultState(this.getStateDefinition().any().setValue(POWERED, false));
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, @Nullable Orientation orientation, boolean movedByPiston) {
        if (!level.isClientSide()) {
            boolean wasPowered = state.getValue(POWERED);
            boolean shouldTurnOn = this.getInputSignal(level, pos, state) > 0;

            if (shouldTurnOn && !wasPowered) {
                // Rising edge: turn on and publish
                level.setBlock(pos, state.setValue(POWERED, true), 3);
                if (level.getBlockEntity(pos) instanceof DynamicPublisherBlockEntity entity) {
                    entity.setWasPowered(true);
                    entity.publishDynamicMessage();
                }
                // Spawn particles on rising edge
                spawnActivationParticles(level, pos);
            } else if (!shouldTurnOn && wasPowered) {
                // Falling edge: turn off
                level.setBlock(pos, state.setValue(POWERED, false), 3);
                if (level.getBlockEntity(pos) instanceof DynamicPublisherBlockEntity entity) {
                    entity.setWasPowered(false);
                }
            }
        }
        super.neighborChanged(state, level, pos, neighborBlock, orientation, movedByPiston);
    }

    private void spawnActivationParticles(Level level, BlockPos pos) {
        Random random = new Random();
        for (int i = 0; i < 5; i++) {
            double x = pos.getX() + 0.5 + random.nextDouble() * 0.5 - 0.25;
            double y = pos.getY() + random.nextDouble() * 0.5;
            double z = pos.getZ() + 0.5 + random.nextDouble() * 0.5 - 0.25;
            if (level instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(new ItemParticleOption(net.minecraft.core.particles.ParticleTypes.ITEM, (Item) Items.REDSTONE), x, y, z, 1, 0, 0, 0, 0);
            }
        }
    }

    protected int getInputSignal(Level level, BlockPos pos, BlockState state) {
        int maxSignal = 0;
        for (Direction direction : Direction.values()) {
            BlockPos blockpos = pos.relative(direction);
            int i = level.getSignal(blockpos, direction);
            if (i >= 15) {
                return i;
            }
            BlockState blockstate = level.getBlockState(blockpos);
            int signal = Math.max(i, blockstate.is(Blocks.REDSTONE_WIRE) ? blockstate.getValue(RedStoneWireBlock.POWER) : 0);
            maxSignal = Math.max(maxSignal, signal);
        }
        return maxSignal;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, net.minecraft.world.item.ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (this.getInputSignal(level, pos, state) > 0) {
            level.scheduleTick(pos, this, 1);
        }
    }

    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        this.updateNeighbors(level, pos, state);
    }

    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        if (!movedByPiston) {
            this.updateNeighbors(level, pos, state);
        }
    }

    protected void updateNeighbors(Level level, BlockPos pos, BlockState state) {
        for (Direction direction : Direction.values()) {
            BlockPos blockPos = pos.relative(direction);
            Orientation orientation = ExperimentalRedstoneUtils.initialOrientation(level, direction, Direction.UP);
            level.neighborChanged(blockPos, this, orientation);
            level.updateNeighborsAtExceptFromFacing(blockPos, this, direction.getOpposite(), orientation);
        }
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(POWERED, false);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(POWERED) ? 15 : 0;
    }

    @Override
    protected int getDirectSignal(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(POWERED) ? 15 : 0;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DynamicPublisherBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return new DynamicPublisherBlockEntity.Ticker<>();
    }
}

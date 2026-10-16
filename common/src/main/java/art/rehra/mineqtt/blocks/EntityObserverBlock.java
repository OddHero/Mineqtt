package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.blocks.entities.EntityObserverBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import org.jetbrains.annotations.Nullable;

/**
 * The Entity Observer ({@link EntityObserverBlockEntity}): its face, which points at the player who placed it, watches the
 * space in front. Set on a wall it looks into the room, set on the floor it looks up, as a dispenser faces.
 */
public class EntityObserverBlock extends BaseMqttBlock {
    public static final MapCodec<EntityObserverBlock> CODEC = simpleCodec(EntityObserverBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;

    public EntityObserverBlock(Properties properties) {
        super(properties, CODEC);
        registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // The watched space is where the placing player stands: the face points back at them.
        return defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EntityObserverBlockEntity(pos, state);
    }

    @Override
    /** The server looks in front a few times a second; the client has nothing to tick. */
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : new EntityObserverBlockEntity.ServerTicker<>();
    }
}

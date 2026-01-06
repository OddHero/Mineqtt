package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.blocks.entities.MotionSensorBlockEntity;
import com.mojang.serialization.MapCodec;
import dev.architectury.event.events.common.InteractionEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class MotionSensorBlock extends BaseEntityBlock implements InteractionEvent.RightClickBlock {

    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final MapCodec<MotionSensorBlock> CODEC = simpleCodec(MotionSensorBlock::new);

    // Define shapes for each facing direction - simplified dome shape
    private static final VoxelShape NORTH_SHAPE = Block.box(4.0, 4.0, 10.0, 12.0, 12.0, 16.0);
    private static final VoxelShape SOUTH_SHAPE = Block.box(4.0, 4.0, 0.0, 12.0, 12.0, 6.0);
    private static final VoxelShape EAST_SHAPE = Block.box(0.0, 4.0, 4.0, 6.0, 12.0, 12.0);
    private static final VoxelShape WEST_SHAPE = Block.box(10.0, 4.0, 4.0, 16.0, 12.0, 12.0);
    private static final VoxelShape UP_SHAPE = Block.box(4.0, 0.0, 4.0, 12.0, 6.0, 12.0);
    private static final VoxelShape DOWN_SHAPE = Block.box(4.0, 10.0, 4.0, 12.0, 16.0, 12.0);

    public MotionSensorBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.getStateDefinition().any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case NORTH -> NORTH_SHAPE;
            case SOUTH -> SOUTH_SHAPE;
            case EAST -> EAST_SHAPE;
            case WEST -> WEST_SHAPE;
            case UP -> UP_SHAPE;
            case DOWN -> DOWN_SHAPE;
        };
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MotionSensorBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return new MotionSensorBlockEntity.Ticker<>();
    }

    @Override
    public InteractionResult click(Player player, InteractionHand hand, BlockPos pos, Direction face) {
        if (player.level().getBlockEntity(pos) == null) {
            return InteractionResult.PASS;
        }

        if (player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }

        BlockEntity blockEntity = player.level().getBlockEntity(pos);
        if (blockEntity instanceof net.minecraft.world.MenuProvider menuProvider) {
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                if (!art.rehra.mineqtt.MineQTT.permissionManager.canInteract(serverPlayer, pos, art.rehra.mineqtt.integrations.MineqttPermission.INTERACT)) {
                    return InteractionResult.PASS;
                }
                dev.architectury.registry.menu.MenuRegistry.openExtendedMenu(serverPlayer, menuProvider, buf -> {
                    buf.writeBlockPos(pos);
                });
            }
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.PASS;
    }
}


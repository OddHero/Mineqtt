package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.blocks.entities.BlockEmitterBlockEntity;
import art.rehra.mineqtt.config.MineQTTConfig;
import com.mojang.serialization.MapCodec;
import dev.architectury.event.EventResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
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
 * The Block Placer ({@link BlockEmitterBlockEntity}): its face, which points away from the player who placed it, builds
 * into the block in front; the container behind it holds the items it places.
 */
public class BlockEmitterBlock extends BaseMqttBlock {
    public static final MapCodec<BlockEmitterBlock> CODEC = simpleCodec(BlockEmitterBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;

    public BlockEmitterBlock(Properties properties) {
        super(properties, CODEC);
        registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Match standard directional blocks: output face points away from placing player
        return defaultBlockState().setValue(FACING, context.getNearestLookingDirection());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /** The kill switch of design §8.3: while it is off, using the block says so. */
    @Override
    public EventResult click(Player player, InteractionHand hand, BlockPos pos, Direction face) {
        if (!MineQTTConfig.blockPlacerEnabled && hand == InteractionHand.MAIN_HAND && !player.isShiftKeyDown()
                && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(Component.translatable("mineqtt.block_emitter.disabled"));
        }
        return super.click(player, hand, pos, face);
    }

    /** The block in front changed: the placer looks whether the block it placed still stands. */
    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction, BlockPos neighborPos,
                                     BlockState neighborState, RandomSource random) {
        if (direction == state.getValue(FACING) && level.getBlockEntity(pos) instanceof BlockEmitterBlockEntity placer) placer.frontChanged();
        return super.updateShape(state, level, ticks, pos, direction, neighborPos, neighborState, random);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BlockEmitterBlockEntity(pos, state);
    }

    /** The server follows the switch and the block it placed; the client has nothing to tick. */
    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return level.isClientSide() ? null : new BlockEmitterBlockEntity.ServerTicker<>();
    }
}

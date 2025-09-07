package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.config.MineQTTConfig;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.redstone.ExperimentalRedstoneUtils;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.level.BlockGetter;

import java.util.EnumSet;

public class RedstonePublisherBlock extends MineQTTBlock {
    public static final MapCodec<RedstonePublisherBlock> CODEC = simpleCodec(RedstonePublisherBlock::new);

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    public RedstonePublisherBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, @Nullable Orientation orientation, boolean movedByPiston) {
        if (!level.isClientSide) {
            boolean flag = state.getValue(POWERED);
            boolean flag1 = this.shouldTurnOn(level, pos, state);
            if (flag1 && !flag) {
                level.setBlock(pos, state.setValue(POWERED, true), 3);
                sendMqttMessage(MineQTTConfig.getTopicPath("switch"), "true");
            } else if (!flag1 && flag) {
                level.setBlock(pos, state.setValue(POWERED, false), 3);
                sendMqttMessage(MineQTTConfig.getTopicPath("switch"), "false");
            }
        }
        super.neighborChanged(state, level, pos, neighborBlock, orientation, movedByPiston);
    }

    protected boolean shouldTurnOn(Level level, BlockPos pos, BlockState state) {
        return this.getInputSignal(level, pos, state) > 0;
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
            int signal = Math.max(i, blockstate.is(Blocks.REDSTONE_WIRE) ? (Integer)blockstate.getValue(RedStoneWireBlock.POWER) : 0);
            maxSignal = Math.max(maxSignal, signal);
        }
        return maxSignal;
    }

    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        if (this.shouldTurnOn(level, pos, state)) {
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


}

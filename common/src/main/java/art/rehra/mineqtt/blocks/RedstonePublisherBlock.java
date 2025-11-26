package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.entities.PublisherBlockEntity;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.integrations.MineqttPermission;
import com.mojang.serialization.MapCodec;
import dev.architectury.event.events.common.InteractionEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
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

public class RedstonePublisherBlock extends BaseEntityBlock implements InteractionEvent.RightClickBlock {
    public static final MapCodec<RedstonePublisherBlock> CODEC = simpleCodec(RedstonePublisherBlock::new);

    public static final BooleanProperty POWERED;

    static {
        POWERED = BlockStateProperties.POWERED;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    public RedstonePublisherBlock(Properties properties) {
        super(properties);

        this.registerDefaultState(this.getStateDefinition().any().setValue(POWERED, false));

        // Don't register event in constructor - causes startup freeze
        // Event will be registered after all blocks are initialized
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, @Nullable Orientation orientation, boolean movedByPiston) {
        if (!level.isClientSide) {
            boolean flag = state.getValue(POWERED);
            boolean flag1 = this.shouldTurnOn(level, pos, state);
            if (flag1 && !flag) {
                level.setBlock(pos, state.setValue(POWERED, true), 3);
                // Publish to combined topic if enabled
                publishToCombinedTopic(level, pos, "true");
            } else if (!flag1 && flag) {
                level.setBlock(pos, state.setValue(POWERED, false), 3);
                // Publish to combined topic if enabled
                publishToCombinedTopic(level, pos, "false");
            }
        }
        super.neighborChanged(state, level, pos, neighborBlock, orientation, movedByPiston);
    }

    private String getCombinedTopicFromBlockEntity(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof PublisherBlockEntity publisherBlockEntity) {
            return publisherBlockEntity.getCombinedTopic();
        }
        return MineQTTConfig.getTopicPath("switch"); // fallback to default
    }

    private boolean isPublisherEnabled(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof PublisherBlockEntity publisherBlockEntity) {
            return publisherBlockEntity.isEnabled();
        }
        return false;
    }

    private void publishToCombinedTopic(Level level, BlockPos pos, String message) {
        if (!isPublisherEnabled(level, pos)) {
            MineQTT.LOGGER.debug("Publisher at " + pos + " is disabled (no base path item in first slot)");
            return;
        }

        String combinedTopic = getCombinedTopicFromBlockEntity(level, pos);

        // Publish to combined topic if enabled
        if (!combinedTopic.isEmpty()) {
            sendMqttMessage(combinedTopic, message);
            MineQTT.LOGGER.info("Published '" + message + "' to combined topic: " + combinedTopic);
        }
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

    @Override
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

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(POWERED, false);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }

    @Override
    protected boolean isSignalSource(BlockState state) { return true; }

    // Publishing MQTT message utility method
    protected void sendMqttMessage(String topic, String message) {
        if (MineQTT.mqttClient != null && MineQTT.mqttClient.getState().isConnected()) {
            MineQTT.mqttClient.publishWith()
                    .topic(topic)
                    .payload(message.getBytes())
                    .send();
        }else {
            MineQTT.LOGGER.warn("MQTT client not connected, cannot send message to topic: " + topic);
        }
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PublisherBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return new PublisherBlockEntity.Ticker<>();
    }

    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity, ItemStack tool) {
        if (blockEntity instanceof Container container) {
            Containers.dropContents(level, pos, container);
            level.updateNeighbourForOutputSignal(pos, this);
        }
        super.playerDestroy(level, player, pos, state, blockEntity, tool);
    }

    @Override
    public InteractionResult click(Player player, InteractionHand hand, BlockPos pos, Direction face) {
        if (player.level().getBlockEntity(pos) == null || !(player.level().getBlockEntity(pos) instanceof PublisherBlockEntity blockEntity)) {
            return InteractionResult.PASS;
        }

        if (player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }

        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            if (!MineQTT.permissionManager.canInteract(serverPlayer, pos, MineqttPermission.INTERACT)) {
                return InteractionResult.PASS;
            }
            dev.architectury.registry.menu.MenuRegistry.openExtendedMenu(serverPlayer, blockEntity, buf -> {
                buf.writeBlockPos(blockEntity.getBlockPos());
            });
        }

        return InteractionResult.SUCCESS;
    }

    @Override
    protected @Nullable MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof PublisherBlockEntity) {
            return (MenuProvider) blockEntity;
        } else {
            return null;
        }
    }
}

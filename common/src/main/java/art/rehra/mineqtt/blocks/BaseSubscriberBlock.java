package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.integrations.MineqttPermission;
import com.hivemq.client.mqtt.mqtt3.message.unsubscribe.Mqtt3Unsubscribe;
import com.mojang.serialization.MapCodec;
import dev.architectury.event.events.common.InteractionEvent;
import dev.architectury.registry.menu.MenuRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import net.minecraft.core.Direction;

public abstract class BaseSubscriberBlock extends BaseEntityBlock implements InteractionEvent.RightClickBlock {

    protected final MapCodec<? extends BaseSubscriberBlock> codec;
    private static boolean eventsRegistered = false;

    public BaseSubscriberBlock(Properties properties, MapCodec<? extends BaseSubscriberBlock> codec) {
        super(properties);
        this.codec = codec;
        // Don't register event handler in constructor - causes startup freeze
        // Event registration should happen after all blocks are initialized
    }

    // Call this after all blocks are registered
    public static void registerEvents() {
        if (!eventsRegistered) {
            InteractionEvent.RIGHT_CLICK_BLOCK.register((player, hand, pos, face) -> {
                if (player.level().getBlockEntity(pos) instanceof BlockEntity be
                    && player.level().getBlockState(pos).getBlock() instanceof BaseSubscriberBlock block) {
                    return block.click(player, hand, pos, face);
                }
                return InteractionResult.PASS;
            });
            eventsRegistered = true;
        }
    }

    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity, ItemStack tool) {
        if (blockEntity instanceof Container container) {
            Containers.dropContents(level, pos, container);
            level.updateNeighbourForOutputSignal(pos, this);
        }
        MineQTT.LOGGER.debug("Unsubscribing from MQTT topic due to block destruction at " + pos);
        if (MineQTT.mqttClient != null && MineQTT.mqttClient.getState().isConnected()) {
            MineQTT.mqttClient.unsubscribe(Mqtt3Unsubscribe.builder().topicFilter(MineQTTConfig.getTopicPath("door")).build());
        }
        super.playerDestroy(level, player, pos, state, blockEntity, tool);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        level.scheduleTick(pos, this, 1);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return codec;
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
        if (blockEntity instanceof MenuProvider menuProvider) {
            if (player instanceof ServerPlayer serverPlayer) {
                if (!MineQTT.permissionManager.canInteract(serverPlayer, pos, MineqttPermission.INTERACT)) {
                    return InteractionResult.PASS;
                }
                MenuRegistry.openExtendedMenu(serverPlayer, menuProvider, buf -> {
                    buf.writeBlockPos(pos);
                });
            }
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.PASS;
    }

    @Override
    protected @Nullable MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof MenuProvider) {
            return (MenuProvider) blockEntity;
        }
        return null;
    }
}


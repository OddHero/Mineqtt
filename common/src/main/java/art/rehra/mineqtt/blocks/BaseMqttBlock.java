package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.integrations.MineqttPermission;
import com.mojang.serialization.MapCodec;
import dev.architectury.event.events.common.InteractionEvent;
import dev.architectury.registry.menu.MenuRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public abstract class BaseMqttBlock extends BaseEntityBlock implements InteractionEvent.RightClickBlock {

    private static boolean eventsRegistered = false;
    protected final MapCodec<? extends BaseMqttBlock> codec;

    protected BaseMqttBlock(Properties properties, MapCodec<? extends BaseMqttBlock> codec) {
        super(properties);
        this.codec = codec;
    }

    public static void registerInteractionEvents() {
        if (!eventsRegistered) {
            InteractionEvent.RIGHT_CLICK_BLOCK.register((player, hand, pos, face) -> {
                BlockState state = player.level().getBlockState(pos);
                if (state.getBlock() instanceof BaseMqttBlock block) {
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
        super.playerDestroy(level, player, pos, state, blockEntity, tool);
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
        return blockEntity instanceof MenuProvider ? (MenuProvider) blockEntity : null;
    }
}

package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.security.DeviceAccess;
import com.mojang.serialization.MapCodec;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.InteractionEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
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
                return EventResult.pass();
            });
            eventsRegistered = true;
        }
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide()) return;
        BlockEntity be = level.getBlockEntity(pos);
        // The item's block entity data is applied by now and is untrusted (design §8.6).
        if (be instanceof DeviceHost host) {
            host.deviceLink().onPlacedBy(be, placer);
        }
        if (be instanceof BaseMqttBlockEntity mqtt) {
            // An owner copied with the item's data is forged; only a placing player becomes the owner.
            mqtt.setOwnerName(placer instanceof Player p ? p.getGameProfile().name() : "");
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
    public EventResult click(Player player, InteractionHand hand, BlockPos pos, Direction face) {
        if (player.level().getBlockEntity(pos) == null) {
            return EventResult.pass();
        }

        if (player.isShiftKeyDown()) {
            return EventResult.pass();
        }

        BlockEntity blockEntity = player.level().getBlockEntity(pos);
        if (blockEntity instanceof BaseMqttBlockEntity mqtt) {
            if (player instanceof ServerPlayer serverPlayer) {
                if (!DeviceAccess.check(serverPlayer, mqtt, MineqttPermission.USE)) {
                    return EventResult.pass();
                }
                mqtt.openScreen(serverPlayer);
            }
            return EventResult.interruptTrue();
        }

        return EventResult.pass();
    }

    @Override
    protected @Nullable MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof MenuProvider ? (MenuProvider) blockEntity : null;
    }
}

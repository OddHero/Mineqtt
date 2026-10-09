package art.rehra.mineqtt.items;

import art.rehra.mineqtt.device.DeviceRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * The item of a block that is a device: placing it is refused once the player owns {@code limits.maxDevicesPerPlayer}
 * devices ({@link DeviceRuntime#mayPlaceDevice}). The refusal comes before vanilla places anything, so no block, no block
 * entity and no device is made and the item is kept; the server sends the client the real blocks and its slots back,
 * which undoes the client's predicted placement.
 */
public class DeviceBlockItem extends BlockItem {
    public DeviceBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    /**
     * Whether a placement that is possible otherwise is refused for the device limit: only on the server, which knows the
     * player's devices, and only for a player (a block placed by no player has no owner to count against). The client
     * used the item up when it predicted the placement, and the server's copy of its slots did not see that, so a refusal
     * sends the player's slots again.
     */
    public static boolean refused(BlockPlaceContext context) {
        if (!(context.getPlayer() instanceof ServerPlayer player) || DeviceRuntime.mayPlaceDevice(player)) return false;
        player.containerMenu.sendAllDataToRemote();
        return true;
    }

    @Override
    protected @Nullable BlockState getPlacementState(BlockPlaceContext context) {
        BlockState state = super.getPlacementState(context);
        return state == null || refused(context) ? null : state;
    }
}

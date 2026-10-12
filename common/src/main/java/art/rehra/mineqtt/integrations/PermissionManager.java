package art.rehra.mineqtt.integrations;

import art.rehra.mineqtt.MineQTT;
import io.github.flemmli97.flan.api.ClaimHandler;
import io.github.flemmli97.flan.api.data.IPermissionStorage;
import io.github.flemmli97.flan.api.permission.BuiltinPermission;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Dictionary;

public class PermissionManager {
    private static final Dictionary<MineqttPermission, Identifier> flanPermissionMappings = new java.util.Hashtable<>();


    public PermissionManager() {
        if (IsFlanAvailable()) {
            MineQTT.LOGGER.info("Flan mod detected, enabling Flan claim permissions integration.");
            flanPermissionMappings.put(MineqttPermission.USE, BuiltinPermission.INTERACTBLOCK);
            // The screen holds the block's items, so configuring a block is opening a container.
            flanPermissionMappings.put(MineqttPermission.CONFIGURE, BuiltinPermission.OPENCONTAINER);
        }
    }


    private boolean IsFlanAvailable() {
        return MineQTT.modLoaderUtils.isModLoaded("flan");
    }

    /** Whether a claim mod decides who a claim trusts; without one {@link #canInteract} trusts every player. */
    public boolean hasClaimMod() {
        return IsFlanAvailable();
    }


    /**
     * Whether the claim at {@code pos}, in the player's own level, trusts the player with {@code permission}. Without
     * a claim mod every player is trusted.
     */
    public boolean canInteract(ServerPlayer player, BlockPos pos, MineqttPermission permission) {
        boolean canInteract = true;

        // Ask Flan for permission
        if (IsFlanAvailable()) {
            canInteract &= ClaimHandler.canInteract(player, pos, flanPermissionMappings.get(permission));
        }

        return canInteract;
    }

    /**
     * Whether the claim at {@code pos}, in the player's own level, lets the player place a block there (M4 plan D12: a
     * Block Placer's online owner). Without a claim mod every player may.
     */
    public boolean canPlace(ServerPlayer player, BlockPos pos) {
        return !IsFlanAvailable() || ClaimHandler.canInteract(player, pos, BuiltinPermission.PLACE);
    }

    /**
     * Whether the claim at {@code pos}, in the player's own level, lets the player open a container there (M4 plan D12:
     * the container behind a Block Placer, for its online owner). Without a claim mod every player may.
     */
    public boolean canOpenContainer(ServerPlayer player, BlockPos pos) {
        return !IsFlanAvailable() || ClaimHandler.canInteract(player, pos, BuiltinPermission.OPENCONTAINER);
    }

    /**
     * Whether two positions of a level lie in one claim (M4 plan D12): the claim mod answers both with the same
     * permission container, which wilderness shares too. Asks no player, so it also holds for a device whose owner is
     * offline. Without a claim mod every position shares the one claim of the whole world.
     */
    public boolean sameClaim(ServerLevel level, BlockPos a, BlockPos b) {
        if (!IsFlanAvailable()) return true;
        IPermissionStorage claims = ClaimHandler.getPermissionStorage(level);
        return claims.getForPermissionCheck(a) == claims.getForPermissionCheck(b);
    }
}

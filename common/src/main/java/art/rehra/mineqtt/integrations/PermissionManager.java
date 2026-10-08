package art.rehra.mineqtt.integrations;

import art.rehra.mineqtt.MineQTT;
import io.github.flemmli97.flan.api.ClaimHandler;
import io.github.flemmli97.flan.api.permission.BuiltinPermission;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
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
}

package art.rehra.mineqtt.integrations;

import art.rehra.mineqtt.MineQTT;
import io.github.flemmli97.flan.api.ClaimHandler;
import io.github.flemmli97.flan.api.permission.BuiltinPermission;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Dictionary;

public class PermissionManager {
    private static final Dictionary<MineqttPermission, ResourceLocation> flanPermissionMappings = new java.util.Hashtable<>();


    public PermissionManager() {
        if (IsFlanAvailable()) {
            MineQTT.LOGGER.info("Flan mod detected, enabling Flan claim permissions integration.");
            flanPermissionMappings.put(MineqttPermission.INTERACT, BuiltinPermission.INTERACTBLOCK);
        }
    }


    private boolean IsFlanAvailable() {
        return MineQTT.modLoaderUtils.isModLoaded("flan");
    }


    public boolean canInteract(ServerPlayer player, BlockPos pos, MineqttPermission permission) {
        boolean canInteract = true;

        // Ask Flan for permission
        if (IsFlanAvailable()) {
            canInteract &= ClaimHandler.canInteract(player, pos, flanPermissionMappings.get(permission));
        }

        return canInteract;
    }
}

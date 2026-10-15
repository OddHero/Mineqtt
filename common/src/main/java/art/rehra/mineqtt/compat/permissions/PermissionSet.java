package art.rehra.mineqtt.compat.permissions;

//? if <1.21.11 {
/*import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.function.Function;

// Minecraft's permission set of 1.21.11 for the versions before, which know only operator levels: what MineQTT asks of it.
// Stonecutter swaps the imports of net.minecraft.server.permissions for this package, and x.permissions() for of(x). A
// permission atom is never granted, as there is no permission API to grant it.
@FunctionalInterface
public interface PermissionSet {
    PermissionSet NO_PERMISSIONS = permission -> false;

    boolean hasPermission(Permission permission);

    static PermissionSet of(ServerPlayer player) {
        for (int level = 4; level > 0; level--) {
            if (player.hasPermissions(level)) return LevelBasedPermissionSet.forLevel(level);
        }
        return LevelBasedPermissionSet.ALL;
    }

    static PermissionSet of(CommandSourceStack source) {
        for (int level = 4; level > 0; level--) {
            if (source.hasPermission(level)) return LevelBasedPermissionSet.forLevel(level);
        }
        return LevelBasedPermissionSet.ALL;
    }

    // The cast keeps the call out of the replacement that turns server.getProfilePermissions into this method.
    static PermissionSet ofProfile(MinecraftServer server, NameAndId profile) {
*///?}
//? if >=1.21.9 && <1.21.11 {
/*        return LevelBasedPermissionSet.forLevel(((MinecraftServer) server).getProfilePermissions(profile));
*///?} else if <1.21.9 {
/*        return LevelBasedPermissionSet.forLevel(((MinecraftServer) server).getProfilePermissions(profile.toProfile()));
*///?}
//? if <1.21.11 {
/*    }

    static Function<NameAndId, PermissionSet> profiles(MinecraftServer server) {
        return profile -> ofProfile(server, profile);
    }
}
*///?}

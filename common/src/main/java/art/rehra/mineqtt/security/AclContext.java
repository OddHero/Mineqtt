package art.rehra.mineqtt.security;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Who asks the {@link TopicAcl}: players, command sources and the owners of devices. An op is anyone with the
 * gamemaster permission (level 2). An admin holds {@link #ADMIN}, which only a permissions mod can grant, or is an
 * op of level 3; the console is both.
 */
public final class AclContext {
    /** The {@code mineqtt.admin} permission of the design: {@code mineqtt:admin} as a vanilla permission atom. */
    public static final Permission ADMIN = Permission.Atom.create(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "admin"));
    /** The {@code mineqtt.ha.expose} permission of the design: {@code mineqtt:ha.expose} as a vanilla permission atom. */
    public static final Permission HA_EXPOSE = Permission.Atom.create(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "ha.expose"));
    /** A device without a known owner: no private subtree, no operator bypass. */
    public static final TopicAcl.Subject NOBODY = new TopicAcl.Subject(null, false, false);

    private AclContext() {
    }

    public static TopicAcl.Subject of(ServerPlayer player) {
        return new TopicAcl.Subject(player.getUUID(), isOp(player.permissions()), isAdmin(player.permissions()));
    }

    /** A player, else the console or a command block, which have no private subtree. */
    public static TopicAcl.Subject of(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player != null) return of(player);
        if (source.getEntity() == null && source.permissions().hasPermission(Permissions.COMMANDS_OWNER)) return TopicAcl.Subject.CONSOLE;
        return new TopicAcl.Subject(null, isOp(source.permissions()), isAdmin(source.permissions()));
    }

    /** The owner of a device, who may be offline: their op status comes from the server's op list. */
    public static TopicAcl.Subject owner(MinecraftServer server, @Nullable UUID owner, String ownerName) {
        if (owner == null) return NOBODY;
        PermissionSet permissions = server.getProfilePermissions(new NameAndId(owner, ownerName));
        return new TopicAcl.Subject(owner, isOp(permissions), isAdmin(permissions));
    }

    /**
     * Whether a device's owner may expose it to Home Assistant under {@code ha.expose} OPT_IN (design §8.8): an op, or
     * {@link #HA_EXPOSE}. A device without a known owner may not.
     */
    public static boolean mayExpose(MinecraftServer server, @Nullable UUID owner, String ownerName) {
        if (owner == null) return false;
        PermissionSet permissions = server.getProfilePermissions(new NameAndId(owner, ownerName));
        return isOp(permissions) || permissions.hasPermission(HA_EXPOSE);
    }

    /** Operators (level 2) pass every device access check and may bypass the raw allowlist where it is configured. */
    public static boolean isOp(PermissionSet permissions) {
        return permissions.hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    private static boolean isAdmin(PermissionSet permissions) {
        return permissions.hasPermission(ADMIN) || permissions.hasPermission(Permissions.COMMANDS_ADMIN);
    }
}

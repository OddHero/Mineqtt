package art.rehra.mineqtt.security;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Function;

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
        return owner(server::getProfilePermissions, owner, ownerName);
    }

    /**
     * The owner of a device as {@link #owner(MinecraftServer, UUID, String)} sees them, with the server's permissions of a
     * player who may be offline ({@code MinecraftServer#getProfilePermissions}).
     */
    public static TopicAcl.Subject owner(Function<NameAndId, ? extends PermissionSet> profilePermissions, @Nullable UUID owner, String ownerName) {
        if (owner == null) return NOBODY;
        PermissionSet permissions = profilePermissions.apply(new NameAndId(owner, ownerName));
        return new TopicAcl.Subject(owner, isOp(permissions), isAdmin(permissions));
    }

    /**
     * Whether a device's owner may expose it to Home Assistant under {@code ha.expose} OPT_IN (design §8.8): an op, or
     * {@link #HA_EXPOSE}. A device without a known owner may not.
     */
    public static boolean mayExpose(MinecraftServer server, @Nullable UUID owner, String ownerName) {
        return owner != null && mayExpose(server.getProfilePermissions(new NameAndId(owner, ownerName)));
    }

    /**
     * Whether these permissions may expose devices to Home Assistant, or hide them again (M2 plan D6): an op, or
     * {@link #HA_EXPOSE}. It is also what a command source needs to reach {@code /mineqtt} at all.
     */
    public static boolean mayExpose(PermissionSet permissions) {
        return isOp(permissions) || permissions.hasPermission(HA_EXPOSE);
    }

    /**
     * The UUID an owner name stands for without a lookup (M2 plan D7). A server that does not authenticate players
     * ({@code online-mode=false}) gives every player the UUID vanilla derives from their name
     * ({@link UUIDUtil#createOfflinePlayerUUID}), so the name is enough to match. It is never stored as the owner: behind
     * a proxy that forwards online UUIDs nobody carries it. On an online-mode server only the player's profile can tell,
     * so there is none; nor is there for no name.
     */
    public static @Nullable UUID offlineOwner(String ownerName, boolean onlineMode) {
        return onlineMode || ownerName.isEmpty() ? null : UUIDUtil.createOfflinePlayerUUID(ownerName);
    }

    /**
     * The online player a v1 owner name binds (M2 plan D7): only one called exactly that, case included. Vanilla's
     * {@code PlayerList.getPlayerByName} ignores case, but a server without authentication tells {@code Steve} and
     * {@code steve} apart (their derived UUIDs differ), so a differently cased player must never take the owner's place.
     * The UUID of the player who is online is authoritative in every mode, also the one a proxy forwards to an
     * offline-mode server.
     *
     * @param players the players online
     * @return their UUID, or null when nobody of that exact name is online
     */
    public static @Nullable UUID onlineOwner(String ownerName, Iterable<NameAndId> players) {
        if (ownerName.isEmpty()) return null;
        for (NameAndId player : players) {
            if (player.name().equals(ownerName)) return player.id();
        }
        return null;
    }

    /**
     * The owner of a block that may know them by name alone: the UUID it stores, else what {@link #offlineOwner} derives
     * on a server without authentication. Null while an online-mode server has not resolved the name yet.
     */
    public static @Nullable UUID knownOwner(MinecraftServer server, @Nullable UUID owner, String ownerName) {
        return owner != null ? owner : offlineOwner(ownerName, server.usesAuthentication());
    }

    /** Operators (level 2) pass every device access check and may bypass the raw allowlist where it is configured. */
    public static boolean isOp(PermissionSet permissions) {
        return permissions.hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    private static boolean isAdmin(PermissionSet permissions) {
        return permissions.hasPermission(ADMIN) || permissions.hasPermission(Permissions.COMMANDS_ADMIN);
    }
}

package art.rehra.mineqtt.security;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.twin.core.EntityGlobs;
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

import java.util.Optional;
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
    /**
     * The {@code mineqtt.twin.bind} permission (M3 plan D13): bind a device to a Home Assistant entity, or change its
     * source or targets. Holders have it on dedicated servers; on integrated servers everyone does.
     */
    public static final Permission TWIN_BIND = Permission.Atom.create(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "twin.bind"));
    /** The {@code mineqtt.twin.control} permission (M3 plan D13): turn control on, so using a bound device switches Home Assistant. */
    public static final Permission TWIN_CONTROL = Permission.Atom.create(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "twin.control"));
    /**
     * The {@code mineqtt.rooms.admin} permission (M4 plan D11): make rooms that apply to every device inside them, not
     * only the maker's own, because such a room changes the Home Assistant area of other players' devices. Operators
     * (level 2) have it too.
     */
    public static final Permission ROOMS_ADMIN = Permission.Atom.create(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "rooms.admin"));
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
     * {@link #HA_EXPOSE}. It is also what a command source needs to reach {@code /mineqtt devices} (M4 plan D14).
     */
    public static boolean mayExpose(PermissionSet permissions) {
        return isOp(permissions) || permissions.hasPermission(HA_EXPOSE);
    }

    /**
     * Whether the explorer shows a subject the topics of the presence device with this slug ({@code player/<slug>}, M4 plan
     * D10): admins see every player's, a player only their own; the bare {@code player} level belongs to no one.
     *
     * @param slugOf the slug of a player's presence device, if they have one
     */
    public static boolean mayReadPlayerTopic(TopicAcl.Subject subject, String slug, Function<UUID, Optional<String>> slugOf) {
        if (subject.admin()) return true;
        return subject.player() != null && !slug.isEmpty() && slugOf.apply(subject.player()).filter(slug::equals).isPresent();
    }

    /** Whether these permissions may make rooms for every device inside them ({@link #ROOMS_ADMIN}): an op, or the permission. */
    public static boolean mayAdminRooms(PermissionSet permissions) {
        return isOp(permissions) || permissions.hasPermission(ROOMS_ADMIN);
    }

    /** Whether these permissions may bind devices to Home Assistant on this server type ({@link #TWIN_BIND}). */
    public static boolean mayBindTwin(PermissionSet permissions, boolean dedicatedServer) {
        return twinPermitted(dedicatedServer, isOp(permissions), permissions.hasPermission(TWIN_BIND));
    }

    /** Whether these permissions may turn on Home Assistant control of a bound device ({@link #TWIN_CONTROL}). */
    public static boolean mayControlTwin(PermissionSet permissions, boolean dedicatedServer) {
        return twinPermitted(dedicatedServer, isOp(permissions), permissions.hasPermission(TWIN_CONTROL));
    }

    /**
     * {@link #mayBindTwin} for a device's owner, who may be offline: a re-check at attach or after a config reload
     * judges the owner by the server's op list. A device without a known owner has the rights of a player without any.
     */
    public static boolean mayBindTwin(MinecraftServer server, @Nullable UUID owner, String ownerName) {
        PermissionSet permissions = owner == null ? PermissionSet.NO_PERMISSIONS : server.getProfilePermissions(new NameAndId(owner, ownerName));
        return mayBindTwin(permissions, server.isDedicatedServer());
    }

    /** {@link #mayControlTwin} for a device's owner, who may be offline, as {@link #mayBindTwin(MinecraftServer, UUID, String)}. */
    public static boolean mayControlTwin(MinecraftServer server, @Nullable UUID owner, String ownerName) {
        PermissionSet permissions = owner == null ? PermissionSet.NO_PERMISSIONS : server.getProfilePermissions(new NameAndId(owner, ownerName));
        return mayControlTwin(permissions, server.isDedicatedServer());
    }

    /**
     * The AUTO rule of both twin permissions (M3 plan D13): everyone on integrated servers (the host owns the house
     * and the broker); operators (level 2) and holders of the permission on dedicated servers.
     */
    public static boolean twinPermitted(boolean dedicatedServer, boolean op, boolean granted) {
        return !dedicatedServer || op || granted;
    }

    /**
     * Why binding a device to {@code entityId} is refused, or empty when it may be bound (M3 plan D13): the binder needs
     * {@link #TWIN_BIND}, and the entity must match {@code twin.allowedEntities}. The second rule has no subject on
     * purpose: operators and the console do not bypass it, so an empty list (the dedicated default) keeps the twin off
     * for everyone.
     *
     * @param mayBind {@link #mayBindTwin} of the binder
     * @param allowed {@code MineQTTConfig.twinAllowedEntities(dedicated)}
     */
    public static Optional<String> twinBindRefusal(boolean mayBind, EntityGlobs allowed, String entityId) {
        if (!mayBind) return Optional.of("binding to Home Assistant needs the mineqtt:twin.bind permission");
        if (allowed.isEmpty()) return Optional.of("the Home Assistant twin is off on this server: set twin.allowedEntities");
        if (!allowed.matches(entityId)) return Optional.of(entityId + " is not in twin.allowedEntities");
        return Optional.empty();
    }

    /**
     * {@link #twinBindRefusal(boolean, EntityGlobs, String)} under the loaded config: the twin may also be off for want
     * of a statestream base ({@link MineQTTConfig#twinOffReason}).
     */
    public static Optional<String> twinBindRefusal(PermissionSet permissions, boolean dedicatedServer, String entityId) {
        boolean mayBind = mayBindTwin(permissions, dedicatedServer);
        Optional<String> off = MineQTTConfig.twinOffReason(dedicatedServer);
        if (mayBind && off.isPresent()) return Optional.of("the Home Assistant twin is off on this server: " + off.get());
        return twinBindRefusal(mayBind, MineQTTConfig.twinAllowedEntities(dedicatedServer), entityId);
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

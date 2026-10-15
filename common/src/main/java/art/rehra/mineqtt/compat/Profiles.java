package art.rehra.mineqtt.compat;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.Optional;
import java.util.UUID;

/**
 * Players by name and id. From 1.21.9 on the server's name cache, op list and owner check take a NameAndId; before, a game
 * profile (compat.NameAndId stands in for NameAndId there).
 */
public final class Profiles {

    private Profiles() {
    }

    public static NameAndId of(ServerPlayer player) {
        //? if >=1.21.9 {
        return player.nameAndId();
        //?} else {
        /*return new NameAndId(player.getGameProfile());
        *///?}
    }

    /** The player the server's name cache knows by this name. */
    public static Optional<NameAndId> byName(MinecraftServer server, String name) {
        //? if >=1.21.9 {
        return server.services().nameToIdCache().get(name);
        //?} else {
        /*return server.getProfileCache() == null ? Optional.empty() : server.getProfileCache().get(name).map(NameAndId::new);
        *///?}
    }

    /** The player the server's name cache knows by this id. */
    public static Optional<NameAndId> byId(MinecraftServer server, UUID id) {
        //? if >=1.21.9 {
        return server.services().nameToIdCache().get(id);
        //?} else {
        /*return server.getProfileCache() == null ? Optional.empty() : server.getProfileCache().get(id).map(NameAndId::new);
        *///?}
    }

    public static boolean isOp(MinecraftServer server, NameAndId player) {
        //? if >=1.21.9 {
        return server.getPlayerList().isOp(player);
        //?} else {
        /*return server.getPlayerList().isOp(player.toProfile());
        *///?}
    }

    public static boolean isSingleplayerOwner(MinecraftServer server, NameAndId player) {
        //? if >=1.21.9 {
        return server.isSingleplayerOwner(player);
        //?} else {
        /*return server.isSingleplayerOwner(player.toProfile());
        *///?}
    }
}

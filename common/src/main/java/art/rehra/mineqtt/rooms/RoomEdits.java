package art.rehra.mineqtt.rooms;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.DeviceRegistry;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.security.AclContext;
import art.rehra.mineqtt.security.DeviceAccess;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * Making, renaming, resizing and removing rooms (M4 plan D11), the one path behind {@code /mineqtt rooms} and the
 * Configurator's Room mode: each edit is judged by {@link RoomRules} and the build rights at its corners, kept in the
 * registry, and handed to the devices it touches ({@code DeviceRuntime#roomChanged}), whose discovery then names the room
 * as their area. Server thread only.
 * <ul>
 *     <li>A player makes rooms that apply to their own devices, where they may build at both corners: they may build at
 *     all (not in adventure or spectator mode), the level lets them interact there (spawn protection, the world border)
 *     and the claim there trusts them to configure. A room for every device inside it ({@code for-all}) changes the area
 *     of other players' devices, so it needs an operator or {@code mineqtt:rooms.admin}.</li>
 *     <li>The server (the console, RCON, a command block) makes rooms for every device; they belong to
 *     {@link Util#NIL_UUID}.</li>
 *     <li>Only the room's owner or an operator renames, resizes or removes it, and a player only where they may build
 *     at all (not in adventure or spectator mode). Renaming or resizing a room for every device needs
 *     {@code mineqtt:rooms.admin} as making it did, also for its owner.</li>
 * </ul>
 */
public final class RoomEdits {
    private RoomEdits() {
    }

    /**
     * Who edits rooms.
     *
     * @param id         the player, or {@link Util#NIL_UUID} for the server
     * @param player     the player, null for the server
     * @param op         an operator (level 2), whom no per-player limit holds and who edits every room
     * @param roomsAdmin may make rooms for every device ({@link AclContext#mayAdminRooms})
     */
    public record Actor(UUID id, @Nullable ServerPlayer player, boolean op, boolean roomsAdmin) {
        public static Actor of(CommandSourceStack source) {
            ServerPlayer player = source.getPlayer();
            return player != null ? of(player) : server(source.permissions());
        }

        public static Actor of(ServerPlayer player) {
            return new Actor(player.getUUID(), player, AclContext.isOp(player.permissions()), AclContext.mayAdminRooms(player.permissions()));
        }

        /** The console, RCON or a command block, with the permissions it runs with. */
        public static Actor server(PermissionSet permissions) {
            return new Actor(Util.NIL_UUID, null, AclContext.isOp(permissions), AclContext.mayAdminRooms(permissions));
        }
    }

    /**
     * What an edit did.
     *
     * @param applied  whether the room was made, changed or removed
     * @param message  the reply: what changed, or why nothing did
     * @param room     the room as it is now; for a removal, as it was
     * @param affected how many devices the edit may have moved into or out of the room
     */
    public record Result(boolean applied, String message, @Nullable Room room, int affected) {
        static Result refused(String message) {
            return new Result(false, message, null, 0);
        }
    }

    /**
     * Why the actor may not make a room between two corners, or empty when they may: its size, their count of rooms,
     * {@code for-all} without the right, and for a player the build rights at both corners. The name is judged on its own
     * ({@link RoomRules#nameProblem}), so the Configurator asks for it only after the box passed.
     */
    public static Optional<String> createProblem(DeviceRegistry registry, Actor actor, ResourceKey<Level> dimension, BlockPos a, BlockPos b, boolean forAll) {
        // The server's rooms are for everyone too: a source without a player needs the right as well (they all have it,
        // from level 2 up, unless a server lowered the level of its functions).
        if ((forAll || actor.player() == null) && !actor.roomsAdmin()) {
            return Optional.of("A room for everyone's devices needs operator rights or mineqtt:rooms.admin");
        }
        Optional<String> size = RoomRules.sizeProblem(a, b, MineQTTConfig.roomsMaxSize);
        if (size.isPresent()) return size;
        Optional<String> count = RoomRules.countProblem(registry.rooms(), actor.id(), actor.op(), MineQTTConfig.limitsMaxRoomsPerPlayer);
        if (count.isPresent()) return count;
        return buildProblem(actor, dimension, a, b);
    }

    /**
     * Why a player may not build at both corners of a box in {@code dimension}, or empty when they may; the server builds
     * anywhere. The claim mod judges the player in their own level, so a player makes rooms only in the dimension they
     * are in.
     */
    public static Optional<String> buildProblem(Actor actor, ResourceKey<Level> dimension, BlockPos a, BlockPos b) {
        ServerPlayer player = actor.player();
        if (player == null) return Optional.empty();
        if (!DeviceAccess.mayBuild(player)) return Optional.of("Rooms cannot be made in this game mode");
        if (!player.level().dimension().equals(dimension)) return Optional.of("Rooms are made in the dimension you are in");
        for (BlockPos corner : new BlockPos[]{a, b}) {
            if (!player.level().mayInteract(player, corner) || !MineQTT.permissionManager.canInteract(player, corner, MineqttPermission.CONFIGURE)) {
                return Optional.of("You may not build at " + corner.toShortString());
            }
        }
        return Optional.empty();
    }

    /** Makes a room between two corners in any order, if the actor may ({@link #createProblem}) and the name is free. */
    public static Result create(MineqttRuntime runtime, Actor actor, String typedName, ResourceKey<Level> dimension, BlockPos a, BlockPos b,
                                boolean forAll) {
        DeviceRegistry registry = runtime.registry();
        String name = RoomRules.name(typedName);
        Optional<String> problem = RoomRules.nameProblem(name)
                .or(() -> createProblem(registry, actor, dimension, a, b, forAll))
                .or(() -> RoomRules.nameTaken(registry.rooms(), actor.id(), name, null));
        if (problem.isPresent()) return Result.refused("Room not made: " + problem.get());
        // The server's rooms are for every device: nobody's devices are the server's own.
        Room room = Room.between(UUID.randomUUID(), name, dimension, a, b, actor.id(), forAll || actor.player() == null, System.currentTimeMillis());
        registry.addRoom(room);
        int affected = runtime.devices().roomChanged(null, room);
        return new Result(true, "Made room " + room.name() + " (" + room.size() + ", " + scope(room) + "), with " + devices(affected) + " inside", room, affected);
    }

    /** Renames a room, if the actor may edit it and the owner has no other room of that name. */
    public static Result rename(MineqttRuntime runtime, Actor actor, Room room, String typedName) {
        DeviceRegistry registry = runtime.registry();
        String name = RoomRules.name(typedName);
        Optional<String> problem = editProblem(actor, room).or(() -> RoomRules.forAllProblem(room, actor.roomsAdmin()))
                .or(() -> RoomRules.nameProblem(name))
                .or(() -> RoomRules.nameTaken(registry.rooms(), room.owner(), name, room.id()));
        if (problem.isPresent()) return Result.refused("Room not renamed: " + problem.get());
        if (name.equals(room.name())) return new Result(false, "Room " + room.name() + " has that name already", room, 0);
        Room renamed = room.renamed(name);
        registry.replaceRoom(renamed);
        int affected = runtime.devices().roomChanged(room, renamed);
        return new Result(true, "Renamed room " + room.name() + " to " + renamed.name() + ", with " + devices(affected) + " inside", renamed, affected);
    }

    /** Moves a room's corners, in its dimension, if the actor may edit it and build at the new corners. */
    public static Result resize(MineqttRuntime runtime, Actor actor, Room room, BlockPos a, BlockPos b) {
        Optional<String> problem = editProblem(actor, room).or(() -> RoomRules.forAllProblem(room, actor.roomsAdmin()))
                .or(() -> RoomRules.sizeProblem(a, b, MineQTTConfig.roomsMaxSize))
                .or(() -> buildProblem(actor, room.dimension(), a, b));
        if (problem.isPresent()) return Result.refused("Room not resized: " + problem.get());
        Room resized = room.resized(a, b);
        if (resized.equals(room)) return new Result(false, "Room " + room.name() + " has these corners already", room, 0);
        runtime.registry().replaceRoom(resized);
        int affected = runtime.devices().roomChanged(room, resized);
        return new Result(true, "Resized room " + room.name() + " to " + resized.size() + ", with " + devices(affected) + " inside before or after", resized, affected);
    }

    /** Removes a room, if the actor may edit it; the devices in it are in the next room that holds them, or in none. */
    public static Result remove(MineqttRuntime runtime, Actor actor, Room room) {
        Optional<String> problem = editProblem(actor, room);
        if (problem.isPresent()) return Result.refused("Room not removed: " + problem.get());
        runtime.registry().removeRoom(room.id());
        int affected = runtime.devices().roomChanged(room, null);
        return new Result(true, "Removed room " + room.name() + ", which held " + devices(affected), room, affected);
    }

    // The owner or an operator, and a player only in a game mode that builds (as making a room needs).
    private static Optional<String> editProblem(Actor actor, Room room) {
        if (!RoomRules.mayEdit(room, actor.id(), actor.op())) return Optional.of("only its owner or an operator may change room " + room.name());
        ServerPlayer player = actor.player();
        if (player != null && !DeviceAccess.mayBuild(player)) return Optional.of("Rooms cannot be changed in this game mode");
        return Optional.empty();
    }

    /** Whose devices a room applies to, as replies say it. */
    public static String scope(Room room) {
        return room.forAll() ? "for every device in it" : "for its owner's devices";
    }

    private static String devices(int count) {
        return count == 1 ? "1 device" : count + " devices";
    }
}

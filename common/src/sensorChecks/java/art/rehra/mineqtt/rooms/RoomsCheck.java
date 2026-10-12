package art.rehra.mineqtt.rooms;

import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.config.StaticDefaults;
import art.rehra.mineqtt.device.Action;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceRegistry;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.types.BlockPlacer;
import art.rehra.mineqtt.device.types.RedstoneOutput;
import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.device.types.SmartPlug;
import art.rehra.mineqtt.device.types.Timers;
import art.rehra.mineqtt.device.types.WallSwitch;
import art.rehra.mineqtt.items.ConfiguratorRoom;
import art.rehra.mineqtt.network.MineqttNetworking;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Rooms (M4 plan D11), run by Gradle's verifyRooms task (bootstrapped for the codecs): corners, names, sizes and counts,
 * which room a device is in, which devices an edit touches, a group's common room, how commands name a room, and what
 * the server may make without a player.
 */
public final class RoomsCheck {
    private static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
    private static final UUID ALEX = UUID.fromString("ec561538-f3fd-461d-aff5-086b22154bce");
    private static final Identifier LAMP = Identifier.fromNamespaceAndPath("mineqtt", "smart_lamp");
    private static int nextDevice;

    static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MineQTTConfig.resetToDefaults();
        corners();
        names();
        sizes();
        counts();
        membership();
        editsTouch();
        groups();
        references();
        serverRooms();
        offers();
        outlines();
        suggestions();
        roomSwitch();
        System.out.println("Room checks passed.");
    }

    private static void corners() {
        Room room = room("Kitchen", STEVE, false, 10, new BlockPos(5, 67, -1), new BlockPos(0, 64, 5));
        check(room.min().equals(new BlockPos(0, 64, -1)) && room.max().equals(new BlockPos(5, 67, 5)), "Corners in any order become min and max: " + room);
        check(room.sizeX() == 6 && room.sizeY() == 4 && room.sizeZ() == 7 && room.volume() == 168 && room.size().equals("6 x 4 x 7"),
                "Both corners are inside: " + room.size());
        check(room.contains(new BlockPos(0, 64, -1)) && room.contains(new BlockPos(5, 67, 5)) && !room.contains(new BlockPos(6, 64, 0))
                && !room.contains(new BlockPos(0, 63, 0)), "Containment is inclusive at both corners and nothing beyond");
        check(room.contains(GlobalPos.of(Level.OVERWORLD, new BlockPos(1, 65, 1))) && !room.contains(GlobalPos.of(Level.NETHER, new BlockPos(1, 65, 1))),
                "A room holds positions of its own dimension only");
        Room single = room("Closet", STEVE, false, 11, BlockPos.ZERO, BlockPos.ZERO);
        check(single.volume() == 1 && single.size().equals("1 x 1 x 1"), "One block is a room too");
        expectFailure(() -> new Room(UUID.randomUUID(), "Bad", Level.OVERWORLD, new BlockPos(1, 0, 0), BlockPos.ZERO, STEVE, false, 0),
                "A room whose min exceeds its max");
        Room resized = room.resized(new BlockPos(9, 9, 9), new BlockPos(7, 7, 7));
        check(resized.id().equals(room.id()) && resized.name().equals("Kitchen") && resized.min().equals(new BlockPos(7, 7, 7)) && resized.created() == room.created(),
                "A resize keeps id, name and age");
        check(room.renamed("Cookhouse").min().equals(room.min()) && room.renamed("Cookhouse").name().equals("Cookhouse"), "A rename keeps the box");
        check(room.shortId().equals(room.id().toString().substring(0, 8)), "The short id is the id's first eight digits");
        Room read = Room.CODEC.parse(JsonOps.INSTANCE, Room.CODEC.encodeStart(JsonOps.INSTANCE, room).getOrThrow()).getOrThrow();
        check(read.equals(room), "A room round-trips through its codec");
        check(!Room.CODEC.encodeStart(JsonOps.INSTANCE, room).getOrThrow().getAsJsonObject().has("forAll"), "A room for its owner writes no forAll");
    }

    private static void names() {
        check(RoomRules.name("  Kitchen  ").equals("Kitchen"), "Names are stripped");
        check(RoomRules.name("Ki\u0000tch\u0007en").equals(RoomRules.name(RoomRules.name("Ki\u0000tch\u0007en"))), "Cleaning a name twice changes nothing");
        check(RoomRules.nameProblem("Kitchen").isEmpty() && RoomRules.nameProblem("Living Room 2").isEmpty(), "Ordinary names are valid");
        check(RoomRules.nameProblem("").isPresent() && RoomRules.nameProblem("   ").isPresent(), "A blank name is refused");
        check(RoomRules.nameProblem("x".repeat(RoomRules.MAX_NAME_LENGTH)).isEmpty() && RoomRules.nameProblem("x".repeat(RoomRules.MAX_NAME_LENGTH + 1)).isPresent(),
                "At most " + RoomRules.MAX_NAME_LENGTH + " characters");
        String emoji = "🍳".repeat(RoomRules.MAX_NAME_LENGTH);
        check(RoomRules.nameProblem(emoji).isEmpty(), "Characters are code points, not UTF-16 units");
        check(RoomRules.nameProblem(" Kitchen").isPresent() && RoomRules.nameProblem("Kit\u0000chen").isPresent(), "A name not cleaned is refused");
        check(RoomRules.key("KITCHEN").equals(RoomRules.key("kitchen")) && RoomRules.key("Straße").equals(RoomRules.key("STRASSE")), "Names compare ignoring case");

        List<Room> rooms = List.of(room("Kitchen", STEVE, false, 1, BlockPos.ZERO, BlockPos.ZERO));
        check(RoomRules.nameTaken(rooms, STEVE, "kitchen", null).isPresent(), "A name is unique among one owner's rooms, ignoring case");
        check(RoomRules.nameTaken(rooms, STEVE, "Kitchen", rooms.getFirst().id()).isEmpty(), "A room's own name is free for it (a rename to another case)");
        check(RoomRules.nameTaken(rooms, ALEX, "Kitchen", null).isEmpty(), "Another owner may use the name");
    }

    private static void sizes() {
        int max = MineQTTConfig.roomsMaxSize;
        check(max == StaticDefaults.DEFAULT_ROOMS_MAX_SIZE, "rooms.maxSize defaults to " + StaticDefaults.DEFAULT_ROOMS_MAX_SIZE);
        check(RoomRules.sizeProblem(BlockPos.ZERO, new BlockPos(max - 1, max - 1, max - 1), max).isEmpty(), "A side of exactly rooms.maxSize blocks fits");
        check(RoomRules.sizeProblem(BlockPos.ZERO, new BlockPos(max, 0, 0), max).isPresent(), "One block more on x does not");
        check(RoomRules.sizeProblem(new BlockPos(0, -64, 0), new BlockPos(0, -64 + max, 0), max).isPresent(), "nor on y");
        check(RoomRules.sizeProblem(new BlockPos(-5, 0, 5), new BlockPos(-5, 0, 5 - max), max).isPresent(), "nor on z, corners in any order");
        check(RoomRules.sizeProblem(new BlockPos(-30_000_000, 0, 0), new BlockPos(30_000_000, 0, 0), max).isPresent(), "A huge span does not overflow");
        check(RoomRules.sizeProblem(BlockPos.ZERO, new BlockPos(7, 7, 7), 8).isEmpty() && RoomRules.sizeProblem(BlockPos.ZERO, new BlockPos(8, 0, 0), 8).isPresent(),
                "The cap follows the configured size");
    }

    private static void counts() {
        int perPlayer = MineQTTConfig.limitsMaxRoomsPerPlayer;
        check(perPlayer == StaticDefaults.DEFAULT_LIMITS_MAX_ROOMS_PER_PLAYER, "limits.maxRoomsPerPlayer defaults to " + perPlayer);
        List<Room> rooms = new ArrayList<>();
        for (int i = 0; i < perPlayer; i++) rooms.add(room("Room " + i, STEVE, false, i, BlockPos.ZERO, BlockPos.ZERO));
        check(RoomRules.countProblem(rooms.subList(0, perPlayer - 1), STEVE, false, perPlayer).isEmpty(), "Below the limit a player makes another");
        check(RoomRules.countProblem(rooms, STEVE, false, perPlayer).isPresent(), "At the limit not");
        check(RoomRules.countProblem(rooms, STEVE, true, perPlayer).isEmpty(), "An operator is exempt from the per-player limit");
        check(RoomRules.countProblem(rooms, ALEX, false, perPlayer).isEmpty(), "Another player's rooms do not count");
        check(RoomRules.countProblem(rooms, STEVE, false, 0).isEmpty(), "0 is no limit of a player's own");
        List<Room> full = new ArrayList<>();
        for (int i = 0; i < StaticDefaults.MAX_ROOMS_PER_WORLD; i++) full.add(room("Room " + i, i % 2 == 0 ? STEVE : Util.NIL_UUID, false, i, BlockPos.ZERO, BlockPos.ZERO));
        check(RoomRules.countProblem(full, ALEX, true, 0).isPresent(), "A full world refuses even an operator");
    }

    private static void membership() {
        DeviceRecord stevesLamp = device(STEVE, 2, 65, 2);
        DeviceRecord alexLamp = device(ALEX, 2, 65, 3);
        DeviceRecord ownerless = device(null, 3, 65, 3);
        Room kitchen = room("Kitchen", STEVE, false, 100, new BlockPos(0, 64, 0), new BlockPos(5, 67, 5));
        Room pantry = room("Pantry", STEVE, false, 200, new BlockPos(2, 65, 2), new BlockPos(2, 65, 2));
        Room alexRoom = room("Den", ALEX, false, 50, new BlockPos(2, 65, 2), new BlockPos(2, 65, 2));
        RoomIndex index = new RoomIndex(List.of(kitchen, pantry, alexRoom));
        check(index.roomOf(stevesLamp).map(Room::name).equals(Optional.of("Pantry")), "The smallest applicable room wins: the pantry in the kitchen");
        check(index.roomOf(alexLamp).isEmpty(), "Steve's kitchen does not apply to Alex's lamp");
        check(index.roomOf(ownerless).isEmpty(), "Nor to an ownerless device");
        check(new RoomIndex(List.of(kitchen)).roomOf(stevesLamp).map(Room::name).equals(Optional.of("Kitchen")), "Without the pantry: the kitchen");
        check(new RoomIndex(List.of(kitchen, alexRoom)).roomOf(stevesLamp).map(Room::name).equals(Optional.of("Kitchen")),
                "Alex's smaller room over Steve's lamp changes nothing");

        Room house = room("House", Util.NIL_UUID, true, 10, new BlockPos(-10, 0, -10), new BlockPos(10, 100, 10));
        RoomIndex shared = new RoomIndex(List.of(house, kitchen));
        check(shared.roomOf(alexLamp).map(Room::name).equals(Optional.of("House")) && shared.roomOf(ownerless).map(Room::name).equals(Optional.of("House")),
                "A room for everyone applies to every device inside, ownerless ones too");
        check(shared.roomOf(stevesLamp).map(Room::name).equals(Optional.of("Kitchen")), "A smaller room of the owner still wins");

        Room older = room("Older", STEVE, false, 1, new BlockPos(0, 64, 0), new BlockPos(5, 67, 5));
        Room newer = room("Newer", STEVE, false, 2, new BlockPos(0, 64, 0), new BlockPos(5, 67, 5));
        check(new RoomIndex(List.of(newer, older)).roomOf(stevesLamp).map(Room::name).equals(Optional.of("Older")), "Of two of one size the older wins");
        Room twinA = new Room(UUID.fromString("00000000-0000-0000-0000-00000000000a"), "A", Level.OVERWORLD, older.min(), older.max(), STEVE, false, 5);
        Room twinB = new Room(UUID.fromString("00000000-0000-0000-0000-00000000000b"), "B", Level.OVERWORLD, older.min(), older.max(), STEVE, false, 5);
        check(new RoomIndex(List.of(twinB, twinA)).roomOf(stevesLamp).map(Room::name).equals(Optional.of("A")), "Then the lower id, so the answer never wavers");

        DeviceRecord nether = new DeviceRecord(UUID.randomUUID(), LAMP, GlobalPos.of(Level.NETHER, new BlockPos(2, 65, 2)), "Nether Lamp", "nether_lamp");
        nether.setOwner(STEVE);
        check(index.roomOf(nether).isEmpty(), "An overworld room does not hold the same coordinates in the nether");
        check(index.at(stevesLamp.pos()).size() == 3 && index.at(stevesLamp.pos()).getFirst().volume() == 1, "Every room holding a position, smallest first");
        check(RoomIndex.EMPTY.roomOf(stevesLamp).isEmpty(), "No rooms, no room");

        check(index.near(Level.OVERWORLD, new BlockPos(2, 65, 2), 16).stream().map(Room::name).toList().equals(List.of("Den", "Pantry", "Kitchen")),
                "Rooms around a position inside them all: nearest first, then in precedence order");
        check(index.near(Level.OVERWORLD, new BlockPos(2, 65, 40), 16).isEmpty() && index.near(Level.NETHER, new BlockPos(2, 65, 2), 16).isEmpty(),
                "Nothing beyond the radius or in another dimension");
        check(index.near(Level.OVERWORLD, new BlockPos(2, 65, 21), 16).stream().map(Room::name).toList().equals(List.of("Kitchen")),
                "The kitchen reaches z 5, so it is 16 away from z 21; the pantry is 19 away");
    }

    private static void editsTouch() {
        DeviceRecord in = device(STEVE, 1, 64, 1);
        DeviceRecord edge = device(STEVE, 5, 64, 5);
        DeviceRecord out = device(STEVE, 9, 64, 9);
        DeviceRecord alex = device(ALEX, 2, 64, 2);
        List<DeviceRecord> records = List.of(in, edge, out, alex);
        Room kitchen = room("Kitchen", STEVE, false, 1, new BlockPos(0, 64, 0), new BlockPos(5, 64, 5));
        check(RoomIndex.affected(records, null, kitchen).equals(Set.of(in.id(), edge.id())), "A new room touches its owner's devices inside it");
        check(RoomIndex.affected(records, kitchen, null).equals(Set.of(in.id(), edge.id())), "A removed room the same");
        check(RoomIndex.affected(records, kitchen, kitchen.renamed("Cookhouse")).equals(Set.of(in.id(), edge.id())), "A rename the same");
        Room grown = kitchen.resized(new BlockPos(0, 64, 0), new BlockPos(9, 64, 9));
        check(RoomIndex.affected(records, kitchen, grown).equals(Set.of(in.id(), edge.id(), out.id())), "A resize touches the old box and the new");
        Room moved = kitchen.resized(new BlockPos(8, 64, 8), new BlockPos(9, 64, 9));
        check(RoomIndex.affected(records, kitchen, moved).equals(Set.of(in.id(), edge.id(), out.id())), "A move touches those it left and those it reached");
        Room forAll = room("Hall", Util.NIL_UUID, true, 1, new BlockPos(0, 64, 0), new BlockPos(5, 64, 5));
        check(RoomIndex.affected(records, null, forAll).equals(Set.of(in.id(), edge.id(), alex.id())), "A room for everyone touches every device inside");
        check(RoomIndex.affected(records, null, null).isEmpty(), "No room, no device");
    }

    private static void groups() {
        DeviceRecord a = device(STEVE, 1, 64, 1);
        DeviceRecord b = device(STEVE, 2, 64, 1);
        DeviceRecord c = device(STEVE, 20, 64, 1);
        Room kitchen = room("Kitchen", STEVE, false, 1, new BlockPos(0, 64, 0), new BlockPos(5, 64, 5));
        Room pantry = room("Pantry", STEVE, false, 2, new BlockPos(2, 64, 1), new BlockPos(2, 64, 1));
        RoomIndex index = new RoomIndex(List.of(kitchen));
        check(index.commonRoom(List.of(a, b)).map(Room::name).equals(Optional.of("Kitchen")), "Members in one room: the group's area");
        check(index.commonRoom(List.of(a, b, c)).isEmpty(), "One member outside: none");
        check(index.commonRoom(List.of()).isEmpty(), "No members: none");
        check(new RoomIndex(List.of(kitchen, pantry)).commonRoom(List.of(a, b)).isEmpty(), "Members in the kitchen and in the pantry inside it: none");
        check(index.commonRoom(List.of(c)).isEmpty(), "A member in no room: none");
    }

    private static void references() {
        Room stevesKitchen = room("Kitchen", STEVE, false, 1, BlockPos.ZERO, BlockPos.ZERO);
        Room alexKitchen = room("Kitchen", ALEX, false, 2, BlockPos.ZERO, BlockPos.ZERO);
        Room sharedKitchen = room("kitchen", Util.NIL_UUID, true, 3, BlockPos.ZERO, BlockPos.ZERO);
        Room alexDen = room("Den", ALEX, false, 4, BlockPos.ZERO, BlockPos.ZERO);
        List<Room> rooms = List.of(stevesKitchen, alexKitchen, sharedKitchen, alexDen);
        UUID bob = UUID.randomUUID();
        check(found(RoomRules.resolve(rooms, "KITCHEN", STEVE, false)) == stevesKitchen, "A player's own room of the name comes first, ignoring case");
        check(found(RoomRules.resolve(rooms, "kitchen", bob, false)) == sharedKitchen, "Another player sees only the room for everyone");
        check(RoomRules.resolve(rooms, "Den", bob, false) instanceof RoomRules.Resolution.NotFound, "Alex's den is not Bob's to see");
        check(found(RoomRules.resolve(rooms, "Den", bob, true)) == alexDen, "An operator sees every room");
        check(RoomRules.resolve(rooms, "kitchen", bob, true) instanceof RoomRules.Resolution.Ambiguous ambiguous && ambiguous.rooms().size() == 3,
                "Three kitchens an operator sees are ambiguous");
        check(found(RoomRules.resolve(rooms, "#" + alexKitchen.shortId(), bob, true)) == alexKitchen, "#<id> names one exactly");
        check(found(RoomRules.resolve(rooms, "#" + alexKitchen.shortId().toUpperCase(), bob, true)) == alexKitchen, "in any case");
        check(RoomRules.resolve(rooms, "#" + alexKitchen.shortId(), bob, false) instanceof RoomRules.Resolution.NotFound, "but only among the rooms one sees");
        check(RoomRules.resolve(rooms, "Attic", STEVE, false) instanceof RoomRules.Resolution.NotFound, "An unknown name is not found");

        check(RoomRules.mayEdit(stevesKitchen, STEVE, false) && !RoomRules.mayEdit(stevesKitchen, ALEX, false) && RoomRules.mayEdit(stevesKitchen, ALEX, true),
                "Only the owner or an operator changes a room");
        check(!RoomRules.mayEdit(sharedKitchen, STEVE, false) && RoomRules.mayEdit(sharedKitchen, Util.NIL_UUID, false),
                "A room the server made is the server's or an operator's to change");
        Room stevesHall = room("Hall", STEVE, true, 5, BlockPos.ZERO, BlockPos.ZERO);
        check(RoomRules.mayEdit(stevesHall, STEVE, false) && RoomRules.forAllProblem(stevesHall, false)
                        .filter(problem -> problem.contains("mineqtt:rooms.admin")).isPresent()
                        && RoomRules.forAllProblem(stevesHall, true).isEmpty() && RoomRules.forAllProblem(stevesKitchen, false).isEmpty(),
                "Renaming or resizing a room for everyone needs the admin right, also for its owner who lost it; their own rooms need none");
        check(RoomRules.visibleTo(sharedKitchen, bob, false) && RoomRules.visibleTo(stevesKitchen, STEVE, false) && !RoomRules.visibleTo(stevesKitchen, bob, false),
                "A player sees their own rooms and those for everyone");
    }

    /** The server (console, RCON, a command block) needs no build rights and makes rooms for everyone; the caps still hold. */
    private static void serverRooms() {
        DeviceRegistry registry = new DeviceRegistry("world_7f3a", UUID.randomUUID());
        PermissionSet console = LevelBasedPermissionSet.OWNER;
        RoomEdits.Actor server = RoomEdits.Actor.server(console);
        check(server.id().equals(Util.NIL_UUID) && server.player() == null && server.op() && server.roomsAdmin(), "The console is an operator without a player");
        check(RoomEdits.createProblem(registry, server, Level.OVERWORLD, BlockPos.ZERO, new BlockPos(5, 3, 5), true).isEmpty(), "The server makes a room for everyone");
        check(RoomEdits.createProblem(registry, server, Level.OVERWORLD, BlockPos.ZERO, new BlockPos(5, 3, 5), false).isEmpty(), "and without for-all, which it implies");
        check(RoomEdits.createProblem(registry, server, Level.OVERWORLD, BlockPos.ZERO, new BlockPos(500, 3, 5), true).isPresent(), "The size cap holds the server too");
        check(RoomEdits.buildProblem(server, Level.NETHER, BlockPos.ZERO, BlockPos.ZERO).isEmpty(), "The server builds anywhere");
        RoomEdits.Actor commandBlock = RoomEdits.Actor.server(LevelBasedPermissionSet.GAMEMASTER);
        check(commandBlock.op() && commandBlock.roomsAdmin(), "A command block runs at level 2: an operator");
        RoomEdits.Actor lowFunction = RoomEdits.Actor.server(PermissionSet.NO_PERMISSIONS);
        check(RoomEdits.createProblem(registry, lowFunction, Level.OVERWORLD, BlockPos.ZERO, BlockPos.ZERO, false).isPresent(),
                "A source without a player and without the right makes no room, as its rooms are for everyone");
        RoomEdits.Actor holder = RoomEdits.Actor.server(permission -> permission.equals(art.rehra.mineqtt.security.AclContext.ROOMS_ADMIN));
        check(!holder.op() && holder.roomsAdmin() && RoomEdits.createProblem(registry, holder, Level.OVERWORLD, BlockPos.ZERO, BlockPos.ZERO, true).isEmpty(),
                "mineqtt:rooms.admin alone is enough for a room for everyone");
        RoomEdits.Actor player = new RoomEdits.Actor(STEVE, null, false, false);
        check(RoomEdits.createProblem(registry, player, Level.OVERWORLD, BlockPos.ZERO, BlockPos.ZERO, true).orElse("").contains("mineqtt:rooms.admin"),
                "for-all without the right is refused, naming the permission");
        check(RoomEdits.scope(room("Hall", Util.NIL_UUID, true, 1, BlockPos.ZERO, BlockPos.ZERO)).contains("every device")
                && room("Hall", Util.NIL_UUID, true, 1, BlockPos.ZERO, BlockPos.ZERO).serverMade(), "A server room is for every device");
    }

    /** M4-15: the Room mode's offers: a box or a picked room, answered once by its own id before it lapses, as it fits. */
    private static void offers() {
        RoomOffer box = RoomOffer.create(3, Level.OVERWORLD, new BlockPos(5, 67, 5), new BlockPos(0, 64, 0), 10_000);
        check(box.min().equals(new BlockPos(0, 64, 0)) && box.max().equals(new BlockPos(5, 67, 5)) && box.sizeX() == 6 && box.sizeY() == 4 && box.sizeZ() == 6,
                "An offered box is normalised and sized like a room");
        check(!box.edits() && box.room() == null && box.expires() == 10_000 + RoomOffer.LIFETIME_MILLIS, "A box offer names no room and lapses after its lifetime");
        check(box.answers(3, 10_000) && box.answers(3, box.expires()) && !box.answers(3, box.expires() + 1) && !box.answers(2, 10_000),
                "Only its own id answers it, until it lapses");
        Room kitchen = room("Kitchen", STEVE, false, 1, BlockPos.ZERO, new BlockPos(5, 67, 5));
        RoomOffer picked = RoomOffer.edit(4, kitchen, 10_000);
        check(picked.edits() && kitchen.id().equals(picked.room()) && picked.min().equals(kitchen.min()), "A picked room's offer names it");
        for (RoomOffer.Op op : RoomOffer.Op.values()) {
            check(box.answerProblem(op, false, false).isEmpty() == (op == RoomOffer.Op.CREATE), "A box takes only CREATE, not " + op);
            check(picked.answerProblem(op, false, false).isEmpty() == (op != RoomOffer.Op.CREATE), "A picked room takes RENAME and REMOVE, not " + op);
        }
        check(box.answerProblem(RoomOffer.Op.CREATE, true, false).isPresent() && box.answerProblem(RoomOffer.Op.CREATE, true, true).isEmpty(),
                "for-all needs the right");
        check(picked.answerProblem(RoomOffer.Op.REMOVE, true, true).isPresent(), "for-all comes with a new room only");
        check(RoomRules.mayEdit(kitchen, STEVE, false) && !RoomRules.mayEdit(kitchen, ALEX, false) && RoomRules.mayEdit(kitchen, ALEX, true),
                "A picked room is renamed or removed only by its owner or an operator, as Room mode picks only those");
    }

    /** M4-15: the outline of a box: its eight corners and points along its edges, within the particle budget. */
    private static void outlines() {
        ConfiguratorRoom.Box block = new ConfiguratorRoom.Box(new BlockPos(2, 64, 2), new BlockPos(2, 64, 2), ConfiguratorRoom.PENDING_COLOR);
        List<double[]> points = ConfiguratorRoom.edgePoints(block, 512);
        check(points.size() == 8 + 12, "A single block's outline: eight corners and one point in the middle of each edge: " + points.size());
        check(points.stream().allMatch(point -> point[0] >= 2 && point[0] <= 3 && point[1] >= 64 && point[1] <= 65 && point[2] >= 2 && point[2] <= 3),
                "Every point lies on the block's outer box");
        ConfiguratorRoom.Box big = new ConfiguratorRoom.Box(BlockPos.ZERO, new BlockPos(127, 127, 127), ConfiguratorRoom.OWN_COLOR);
        for (int budget : List.of(512, 128, 40, 8)) {
            List<double[]> capped = ConfiguratorRoom.edgePoints(big, budget);
            check(capped.size() <= budget && capped.size() >= 8, "A room of 128 per side stays within " + budget + " points: " + capped.size());
        }
        check(ConfiguratorRoom.edgePoints(big, 7).isEmpty(), "Without room for the corners nothing is drawn");
        check(ConfiguratorRoom.MAX_PARTICLES == 512 && ConfiguratorRoom.MAX_OUTLINED_ROOMS == 4 && ConfiguratorRoom.OUTLINE_RADIUS == 32
                && ConfiguratorRoom.OUTLINE_INTERVAL_TICKS == 10, "512 particles, 4 rooms within 32 blocks, every 10 ticks");
        Room own = room("Own", STEVE, false, 1, BlockPos.ZERO, BlockPos.ZERO);
        Room shared = room("Shared", Util.NIL_UUID, true, 1, BlockPos.ZERO, BlockPos.ZERO);
        Room alex = room("Alex", ALEX, false, 1, BlockPos.ZERO, BlockPos.ZERO);
        check(ConfiguratorRoom.color(own, STEVE) == ConfiguratorRoom.OWN_COLOR && ConfiguratorRoom.color(shared, STEVE) == ConfiguratorRoom.FOR_ALL_COLOR
                && ConfiguratorRoom.color(alex, STEVE) == ConfiguratorRoom.OTHER_COLOR, "Own cyan, for everyone green, another player's grey");
        check(ConfiguratorRoom.PENDING_COLOR == 0xFFFFFF, "The box being chosen is white");
    }

    /** M4-15: the prompt suggests names of rooms the player sees but does not own; their own names are taken. */
    private static void suggestions() {
        DeviceRegistry registry = new DeviceRegistry("world_7f3a", UUID.randomUUID());
        registry.addRoom(room("Kitchen", Util.NIL_UUID, true, 1, BlockPos.ZERO, BlockPos.ZERO));
        registry.addRoom(room("kitchen", ALEX, true, 2, BlockPos.ZERO, BlockPos.ZERO));
        registry.addRoom(room("Hall", Util.NIL_UUID, true, 3, BlockPos.ZERO, BlockPos.ZERO));
        registry.addRoom(room("Den", ALEX, false, 4, BlockPos.ZERO, BlockPos.ZERO));
        registry.addRoom(room("hall", STEVE, false, 5, BlockPos.ZERO, BlockPos.ZERO));
        RoomEdits.Actor steve = new RoomEdits.Actor(STEVE, null, false, false);
        check(ConfiguratorRoom.suggestions(registry, steve).equals(List.of("Kitchen")),
                "Rooms for everyone, once per name ignoring case, without Steve's own names and without Alex's private den: "
                        + ConfiguratorRoom.suggestions(registry, steve));
        RoomEdits.Actor op = new RoomEdits.Actor(UUID.randomUUID(), null, true, true);
        check(ConfiguratorRoom.suggestions(registry, op).equals(List.of("Den", "Hall", "Kitchen")), "An operator sees every room's name: "
                + ConfiguratorRoom.suggestions(registry, op));

        // M4 plan D11, M4-23: Home Assistant's area names after the rooms, for players who may bind devices to it.
        List<String> areas = List.of("Bedroom", "KITCHEN", "Living Room", "hall", "x".repeat(40), "Den");
        check(ConfiguratorRoom.areaSuggestions(false, areas).isEmpty(), "A player who may not bind devices to Home Assistant gets no area names");
        check(ConfiguratorRoom.areaSuggestions(true, areas).equals(areas), "One who may gets them");
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) many.add("Area " + i);
        check(ConfiguratorRoom.areaSuggestions(true, many).size() == 16, "At most 16 area names");
        check(ConfiguratorRoom.suggestions(registry, steve, ConfiguratorRoom.areaSuggestions(true, areas)).equals(List.of("Kitchen", "Bedroom", "Living Room", "Den")),
                "After the rooms, the areas that name neither a suggested room nor one of Steve's own (hall), as Home Assistant compares them, "
                        + "and only names a room may have: " + ConfiguratorRoom.suggestions(registry, steve, areas));
        check(ConfiguratorRoom.suggestions(registry, op, List.of("living room", "Living Room", "Pantry")).equals(List.of("Den", "Hall", "Kitchen", "living room", "Pantry")),
                "An area name comes once: " + ConfiguratorRoom.suggestions(registry, op, List.of("living room", "Living Room", "Pantry")));
        DeviceRegistry crowded = new DeviceRegistry("world_7f3b", UUID.randomUUID());
        for (int i = 0; i < 40; i++) crowded.addRoom(room(String.format("Room %02d", i), Util.NIL_UUID, true, i, BlockPos.ZERO, BlockPos.ZERO));
        List<String> mixed = ConfiguratorRoom.suggestions(crowded, steve, many.subList(0, 16));
        check(mixed.size() == MineqttNetworking.RoomPromptPayload.MAX_SUGGESTIONS && mixed.get(15).equals("Room 15") && mixed.get(16).equals("Area 0")
                && mixed.getLast().equals("Area 15"), "Of 40 rooms and 16 areas the prompt keeps the 16 areas and 16 rooms: " + mixed);

        // The area a room suggests to Home Assistant: its spelling of a catalog area that matches, else the room's name.
        check(RoomRules.area("living room", List.of("Kitchen", "Living Room")).equals("Living Room"), "Case ignored: Home Assistant's spelling");
        check(RoomRules.area("LivingRoom", List.of("Living Room")).equals("Living Room"), "Spaces ignored, as Home Assistant compares area names");
        check(RoomRules.area("Pantry", List.of("Kitchen")).equals("Pantry"), "No match: the room's name");
        check(RoomRules.area("Kitchen", List.of()).equals("Kitchen"), "No catalog: the room's name");
    }

    private static Room found(RoomRules.Resolution resolution) {
        check(resolution instanceof RoomRules.Resolution.Found, "Expected a room, got " + resolution);
        return ((RoomRules.Resolution.Found) resolution).room();
    }

    /**
     * M4-19 (D1, D13): a room's All on and All off reach the devices whose room it is (not those of a nested room) that the
     * player finds and that switch on and off (lamps, plugs and outputs; never a timer, whose state arms it), by name, at
     * most 64 with the rest counted; another player's device in a room for everyone is skipped for a player who does not
     * find it; a player switches at most once a second, and the record of who switched holds only the last second.
     */
    private static void roomSwitch() {
        Room house = room("House", Util.NIL_UUID, true, 10, new BlockPos(0, 64, 0), new BlockPos(9, 67, 9));
        Room pantry = room("Pantry", STEVE, false, 20, new BlockPos(8, 65, 8), new BlockPos(8, 65, 8));
        RoomIndex index = new RoomIndex(List.of(house, pantry));
        DeviceRecord second = new DeviceRecord(UUID.randomUUID(), LAMP, GlobalPos.of(Level.OVERWORLD, new BlockPos(1, 65, 1)), "B Lamp", "b_lamp");
        second.setOwner(STEVE);
        DeviceRecord first = new DeviceRecord(UUID.randomUUID(), LAMP, GlobalPos.of(Level.OVERWORLD, new BlockPos(2, 65, 1)), "a lamp", "a_lamp");
        first.setOwner(STEVE);
        DeviceRecord alexLamp = device(ALEX, 3, 65, 1);
        DeviceRecord sensor = device(STEVE, 4, 65, 1);
        DeviceRecord inPantry = device(STEVE, 8, 65, 8);
        DeviceRecord outside = device(STEVE, 20, 65, 20);
        List<DeviceRecord> records = List.of(second, first, alexLamp, sensor, inPantry, outside);
        Predicate<DeviceRecord> stevesFinds = record -> STEVE.equals(record.owner());
        Predicate<DeviceRecord> hasState = record -> record != sensor;
        RoomSwitch.Selection steve = RoomSwitch.targets(records, house, index::roomOf, stevesFinds, hasState);
        check(steve.targets().equals(List.of(first, second)) && steve.skipped() == 0,
                "Steve's All on of the house reaches his two lamps by name; Alex's lamp, the device without a state, the pantry's lamp and one outside are left: "
                        + steve.targets().stream().map(DeviceRecord::slug).toList());
        RoomSwitch.Selection console = RoomSwitch.targets(records, house, index::roomOf, record -> true, hasState);
        check(console.targets().equals(List.of(first, second, alexLamp)), "The console reaches Alex's lamp in the room for everyone too");
        check(RoomSwitch.targets(records, pantry, index::roomOf, stevesFinds, hasState).targets().equals(List.of(inPantry)),
                "The pantry's switch reaches the pantry's lamp alone");
        List<DeviceRecord> many = new ArrayList<>();
        for (int i = 0; i < RoomSwitch.MAX_DEVICES + 6; i++) many.add(device(STEVE, i % 10, 64 + i / 30, (i / 10) % 3));
        RoomSwitch.Selection capped = RoomSwitch.targets(many, house, index::roomOf, stevesFinds, record -> true);
        check(capped.targets().size() == RoomSwitch.MAX_DEVICES && capped.skipped() == 6, "At most 64 at once, the rest counted: " + capped.skipped());

        for (DeviceType actuator : List.of(SmartLamp.TYPE, SmartPlug.TYPE, RedstoneOutput.TYPE)) {
            check(RoomSwitch.switches(actuator), "A room switch reaches the " + actuator.model());
        }
        check(Timers.all().size() == 4, "Four timer types");
        for (DeviceType timer : Timers.all()) {
            check(timer.action(Action.STATE).isPresent() && !RoomSwitch.switches(timer),
                    "The " + timer.model() + " declares state, which arms it, so a room's All off leaves it armed");
        }
        for (DeviceType other : List.of(WallSwitch.TYPE, BlockPlacer.TYPE)) {
            check(!RoomSwitch.switches(other), "A room switch leaves the " + other.model() + " alone");
        }

        check(RoomSwitch.due(null, 0) && !RoomSwitch.due(1_000L, 1_999L) && RoomSwitch.due(1_000L, 2_000L), "A player switches a room at most once a second");
        Map<UUID, Long> lastSwitch = new HashMap<>();
        check(RoomSwitch.admit(lastSwitch, STEVE, 1_000L) && lastSwitch.equals(Map.of(STEVE, 1_000L)), "A first switch is admitted and recorded");
        check(!RoomSwitch.admit(lastSwitch, STEVE, 1_999L) && lastSwitch.equals(Map.of(STEVE, 1_000L)),
                "A second switch within the second is refused and does not move the time");
        check(RoomSwitch.admit(lastSwitch, ALEX, 1_500L) && lastSwitch.equals(Map.of(STEVE, 1_000L, ALEX, 1_500L)),
                "Another player is not held back by Steve's switch");
        check(RoomSwitch.admit(lastSwitch, STEVE, 2_000L) && lastSwitch.equals(Map.of(STEVE, 2_000L, ALEX, 1_500L)),
                "A second later Steve switches again");
        check(RoomSwitch.admit(lastSwitch, STEVE, 3_100L) && lastSwitch.equals(Map.of(STEVE, 3_100L)),
                "Entries that can refuse nobody any more are dropped at the next switch: " + lastSwitch);
        for (int i = 0; i < 1_000; i++) RoomSwitch.admit(lastSwitch, new UUID(0, i), 10_000L + i * RoomSwitch.MIN_INTERVAL_MILLIS);
        check(lastSwitch.size() == 1, "A thousand players switching one after another leave one entry, not a thousand: " + lastSwitch.size());
        check(RoomSwitch.Result.TOO_SOON.describe("Kitchen", true).equals("One room switch a second; try Kitchen again in a moment"), "Too soon is said");
        check(new RoomSwitch.Result(false, 0, 0, 0, 0, 0).describe("Kitchen", false)
                .equals("Nothing in Kitchen to switch off: no lamp, plug or output of yours there"), "An empty switch is said");
        check(new RoomSwitch.Result(false, 2, 1, 1, 1, 3).describe("Kitchen", true)
                        .equals("Kitchen: 2 switched on, 1 already on, 1 queued until loaded, 1 refused, 3 left out (at most 64 at once)"),
                "A switch says what became of each device: " + new RoomSwitch.Result(false, 2, 1, 1, 1, 3).describe("Kitchen", true));
    }

    private static Room room(String name, UUID owner, boolean forAll, long created, BlockPos a, BlockPos b) {
        return Room.between(UUID.randomUUID(), name, Level.OVERWORLD, a, b, owner, forAll, created);
    }

    private static DeviceRecord device(UUID owner, int x, int y, int z) {
        int n = nextDevice++;
        DeviceRecord record = new DeviceRecord(UUID.randomUUID(), LAMP, GlobalPos.of(Level.OVERWORLD, new BlockPos(x, y, z)), "Lamp " + n, "lamp_" + n);
        record.setOwner(owner);
        return record;
    }

    private static void expectFailure(Runnable action, String what) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(what + " was accepted");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}

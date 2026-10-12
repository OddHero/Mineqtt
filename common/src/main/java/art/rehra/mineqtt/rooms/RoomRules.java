package art.rehra.mineqtt.rooms;

import art.rehra.mineqtt.config.StaticDefaults;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.twin.core.AreaNames;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The rules of rooms (M4 plan D11) that need no world: names, sizes, counts, who may change or see a room, and how a
 * command names one. The commands and the Configurator's Room mode judge every room through these, so both refuse
 * alike ({@link RoomEdits}).
 * <ul>
 *     <li>A name is free text, sanitised and stripped, of 1 to {@value #MAX_NAME_LENGTH} characters, and unique among one
 *     owner's rooms ignoring case.</li>
 *     <li>Each side of a new or resized room holds at most {@code rooms.maxSize} blocks, both corners included.</li>
 *     <li>A world holds at most {@value StaticDefaults#MAX_ROOMS_PER_WORLD} rooms, a player at most
 *     {@code limits.maxRoomsPerPlayer} (0 = no limit of their own); operators are exempt from their own limit.</li>
 *     <li>Only the room's owner or an operator renames, resizes or removes it; a player sees their own rooms and those
 *     for everyone, an operator every room.</li>
 *     <li>A room names its Home Assistant area in Home Assistant's own spelling when the catalog knows an area of that
 *     name, case and spaces ignored ({@link #area}).</li>
 * </ul>
 */
public final class RoomRules {
    /** The longest room name, in characters (code points). */
    public static final int MAX_NAME_LENGTH = 32;
    /** How a command names a room by id, after a {@code #}: the first hex digits of its id ({@link Room#shortId()}). */
    private static final Pattern SHORT_ID = Pattern.compile("#([0-9a-f]{8})");
    /** The precedence of rooms that hold the same device: the smallest first, then the oldest, then by id. */
    public static final Comparator<Room> PRECEDENCE = Comparator.comparingLong(Room::volume).thenComparingLong(Room::created).thenComparing(Room::id);

    private RoomRules() {
    }

    /** A typed name as a room keeps it: sanitised like every text, stripped; empty when nothing is left. */
    public static String name(String typed) {
        return ValueCodecs.sanitizeText(typed.strip()).strip();
    }

    /** Why a name is no room name ({@link #name} gives it already cleaned), or empty for a valid one. */
    public static Optional<String> nameProblem(String name) {
        if (name.isBlank()) return Optional.of("A room needs a name");
        if (name.codePointCount(0, name.length()) > MAX_NAME_LENGTH) return Optional.of("A room name has at most " + MAX_NAME_LENGTH + " characters");
        if (!name.equals(name(name))) return Optional.of("A room name holds no control characters and no spaces at either end");
        return Optional.empty();
    }

    /**
     * The area a room's name suggests to Home Assistant (M4 plan D11): the spelling of a catalog area whose name matches
     * it as Home Assistant compares area names (case folded, spaces removed), so Home Assistant reuses that area under its
     * own name; the room's name when none matches or no catalog is known.
     *
     * @param areas the catalog's area names ({@code TwinRuntime#areas}); empty without a catalog
     */
    public static String area(String roomName, Collection<String> areas) {
        return AreaNames.spelling(roomName, areas).orElse(roomName);
    }

    /** A name as rooms compare it: case is ignored. */
    public static String key(String name) {
        return name.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }

    /** Why a box between two corners is too large for {@code maxSize} blocks per side, or empty when it fits. */
    public static Optional<String> sizeProblem(BlockPos a, BlockPos b, int maxSize) {
        BlockPos min = BlockPos.min(a, b);
        BlockPos max = BlockPos.max(a, b);
        long x = (long) max.getX() - min.getX() + 1;
        long y = (long) max.getY() - min.getY() + 1;
        long z = (long) max.getZ() - min.getZ() + 1;
        if (x > maxSize || y > maxSize || z > maxSize) {
            return Optional.of("A room is at most " + maxSize + " blocks on each side; this one is " + x + " x " + y + " x " + z);
        }
        return Optional.empty();
    }

    /**
     * Why {@code owner} may not make another room, or empty when they may: the world is full, or they reached
     * {@code maxPerPlayer} (0 = no limit) and are not exempt.
     *
     * @param exempt whether they are an operator, whom only the world's cap holds
     */
    public static Optional<String> countProblem(Collection<Room> rooms, UUID owner, boolean exempt, int maxPerPlayer) {
        if (rooms.size() >= StaticDefaults.MAX_ROOMS_PER_WORLD) {
            return Optional.of("This world has " + StaticDefaults.MAX_ROOMS_PER_WORLD + " rooms, as many as it may hold");
        }
        if (exempt || maxPerPlayer <= 0) return Optional.empty();
        long owned = rooms.stream().filter(room -> room.owner().equals(owner)).count();
        return owned >= maxPerPlayer ? Optional.of("You have " + owned + " rooms, as many as this server allows (limits.maxRoomsPerPlayer)") : Optional.empty();
    }

    /** Why {@code name} is taken among the owner's rooms (ignoring case), or empty; the room {@code except} does not count. */
    public static Optional<String> nameTaken(Collection<Room> rooms, UUID owner, String name, @Nullable UUID except) {
        String key = key(name);
        return rooms.stream().filter(room -> room.owner().equals(owner) && !room.id().equals(except) && key(room.name()).equals(key)).findFirst()
                .map(room -> "There is a room named " + room.name() + " already");
    }

    /** Whether {@code actor} may rename, resize or remove the room: its owner or an operator. */
    public static boolean mayEdit(Room room, UUID actor, boolean op) {
        return op || room.owner().equals(actor);
    }

    /**
     * Why the actor may not rename or resize a room for everyone's devices, or empty: such a room sets the area of other
     * players' devices, so changing it needs the right that making it needed, also for its owner once they lost it.
     */
    public static Optional<String> forAllProblem(Room room, boolean roomsAdmin) {
        return room.forAll() && !roomsAdmin
                ? Optional.of("room " + room.name() + " is for everyone's devices, which needs operator rights or mineqtt:rooms.admin")
                : Optional.empty();
    }

    /** Whether {@code actor} sees the room in lists and suggestions: their own, one for everyone, or any for an operator. */
    public static boolean visibleTo(Room room, UUID actor, boolean op) {
        return op || room.forAll() || room.owner().equals(actor);
    }

    /** What a room reference resolved to. */
    public sealed interface Resolution {
        record Found(Room room) implements Resolution {
        }

        record NotFound(String reference) implements Resolution {
        }

        /** Several rooms the actor sees have the name, none of them their own: the reply names each by id. */
        record Ambiguous(List<Room> rooms) implements Resolution {
        }
    }

    /**
     * The room a command names: {@code #<8 hex>} names one by id among those the actor sees; a name (ignoring case) names
     * the actor's own room of that name, else the single room of that name they see. Several of others' rooms with the
     * name are ambiguous.
     */
    public static Resolution resolve(Collection<Room> rooms, String reference, UUID actor, boolean op) {
        String typed = name(reference);
        var id = SHORT_ID.matcher(typed.toLowerCase(Locale.ROOT));
        if (id.matches()) {
            List<Room> byId = rooms.stream().filter(room -> visibleTo(room, actor, op) && room.shortId().equals(id.group(1))).toList();
            return byId.size() == 1 ? new Resolution.Found(byId.getFirst()) : byId.isEmpty() ? new Resolution.NotFound(typed) : new Resolution.Ambiguous(byId);
        }
        String key = key(typed);
        Optional<Room> own = rooms.stream().filter(room -> room.owner().equals(actor) && key(room.name()).equals(key)).findFirst();
        if (own.isPresent()) return new Resolution.Found(own.get());
        List<Room> named = rooms.stream().filter(room -> visibleTo(room, actor, op) && key(room.name()).equals(key)).sorted(PRECEDENCE).toList();
        if (named.isEmpty()) return new Resolution.NotFound(typed);
        return named.size() == 1 ? new Resolution.Found(named.getFirst()) : new Resolution.Ambiguous(named);
    }
}

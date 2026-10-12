package art.rehra.mineqtt.rooms;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;

import java.util.Optional;
import java.util.UUID;

/**
 * A room (M4 plan D11): a named box of blocks in one dimension, made by a player for their own devices or, as
 * {@link #forAll()}, for every device inside it. Rooms are geometry only: which room a device is in is derived from its
 * position ({@link RoomIndex#roomOf}), never stored, so an edit follows every device without touching chunks. A device's
 * room names the area Home Assistant suggests for it ({@code suggested_area}). Immutable; the registry replaces a room
 * to rename or resize it.
 *
 * @param min     the corner with the smallest coordinates, inclusive
 * @param max     the corner with the largest coordinates, inclusive
 * @param owner   the player who made it, {@link Util#NIL_UUID} for one the server made (console, RCON, a command block)
 * @param forAll  whether it applies to every device inside it, not only its owner's
 * @param created when it was made, in milliseconds since the epoch: of two rooms of one size the older wins
 */
public record Room(UUID id, String name, ResourceKey<Level> dimension, BlockPos min, BlockPos max, UUID owner, boolean forAll, long created) {
    public static final Codec<Room> CODEC = RecordCodecBuilder.<Room>create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Room::id),
            Codec.STRING.fieldOf("name").forGetter(Room::name),
            Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Room::dimension),
            BlockPos.CODEC.fieldOf("min").forGetter(Room::min),
            BlockPos.CODEC.fieldOf("max").forGetter(Room::max),
            UUIDUtil.CODEC.fieldOf("owner").forGetter(Room::owner),
            Codec.BOOL.optionalFieldOf("forAll", false).forGetter(Room::forAll),
            Codec.LONG.fieldOf("created").forGetter(Room::created)
    ).apply(instance, Room::between)).validate(Room::validate);

    public Room {
        if (min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) {
            throw new IllegalArgumentException("A room's min corner " + min.toShortString() + " exceeds its max corner " + max.toShortString());
        }
    }

    /** A new room between two corners in any order. */
    public static Room between(UUID id, String name, ResourceKey<Level> dimension, BlockPos a, BlockPos b, UUID owner, boolean forAll, long created) {
        return new Room(id, name, dimension, BlockPos.min(a, b), BlockPos.max(a, b), owner, forAll, created);
    }

    /** Whether the server made it (console, RCON, a command block): such a room applies to every device inside it. */
    public boolean serverMade() {
        return Util.NIL_UUID.equals(owner);
    }

    /** Whether it applies to a device of this owner (null for an ownerless device): its owner's, or every device for {@link #forAll}. */
    public boolean appliesTo(Optional<UUID> deviceOwner) {
        return forAll || deviceOwner.map(owner::equals).orElse(false);
    }

    public boolean contains(GlobalPos pos) {
        return dimension.equals(pos.dimension()) && contains(pos.pos());
    }

    public boolean contains(BlockPos pos) {
        return pos.getX() >= min.getX() && pos.getX() <= max.getX() && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    /** Its size along x, y and z in blocks, both corners included. */
    public int sizeX() {
        return max.getX() - min.getX() + 1;
    }

    public int sizeY() {
        return max.getY() - min.getY() + 1;
    }

    public int sizeZ() {
        return max.getZ() - min.getZ() + 1;
    }

    /** How many blocks it holds. */
    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    /** {@code 6 x 4 x 6}, as replies and the prompt show the size. */
    public String size() {
        return sizeX() + " x " + sizeY() + " x " + sizeZ();
    }

    /** The first eight hex digits of its id, which name it where its name is ambiguous ({@code #1a2b3c4d}). */
    public String shortId() {
        return id.toString().substring(0, 8);
    }

    public Room renamed(String newName) {
        return new Room(id, newName, dimension, min, max, owner, forAll, created);
    }

    /** The same room between two new corners in any order, in the same dimension. */
    public Room resized(BlockPos a, BlockPos b) {
        return new Room(id, name, dimension, BlockPos.min(a, b), BlockPos.max(a, b), owner, forAll, created);
    }

    // A stored room needs a name of 1 to 32 characters. Neither the text rules of today nor its size are judged again, so
    // neither a stricter sanitiser nor a lowered rooms.maxSize loses the rooms made before.
    private static DataResult<Room> validate(Room room) {
        String name = room.name();
        if (name.isBlank() || name.codePointCount(0, name.length()) > RoomRules.MAX_NAME_LENGTH) {
            return DataResult.error(() -> "Room " + room.id() + " has no name of 1 to " + RoomRules.MAX_NAME_LENGTH + " characters");
        }
        return DataResult.success(room);
    }
}

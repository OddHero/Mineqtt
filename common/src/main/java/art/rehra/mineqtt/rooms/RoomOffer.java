package art.rehra.mineqtt.rooms;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * What the Configurator's Room mode offered a player to answer from its name prompt (M4 plan D11): a box to make a room
 * of, or a room of theirs to rename or remove. The server keeps it per player; an answer is taken only for the offer the
 * player holds now, within {@value #LIFETIME_MILLIS} ms, and once, so a late or replayed answer does nothing.
 *
 * @param id      what the answer names
 * @param room    the room to rename or remove; null for a box to make a room of
 * @param expires when the offer lapses, in the server's milliseconds
 */
public record RoomOffer(int id, @Nullable UUID room, ResourceKey<Level> dimension, BlockPos min, BlockPos max, long expires) {
    /** How long a player has to answer the prompt. */
    public static final long LIFETIME_MILLIS = 120_000;

    /** What an answer asks for. */
    public enum Op {
        /** Make a room of the offered box. */
        CREATE,
        /** Give the offered room a new name. */
        RENAME,
        /** Remove the offered room. */
        REMOVE
    }

    /** A box between two corners to make a room of, offered at {@code now}. */
    public static RoomOffer create(int id, ResourceKey<Level> dimension, BlockPos a, BlockPos b, long now) {
        return new RoomOffer(id, null, dimension, BlockPos.min(a, b), BlockPos.max(a, b), now + LIFETIME_MILLIS);
    }

    /** A room to rename or remove, offered at {@code now}. */
    public static RoomOffer edit(int id, Room room, long now) {
        return new RoomOffer(id, room.id(), room.dimension(), room.min(), room.max(), now + LIFETIME_MILLIS);
    }

    /** Whether it offers a room to rename or remove rather than a box. */
    public boolean edits() {
        return room != null;
    }

    /** Whether an answer naming {@code answered} at {@code now} answers this offer: the same id, before it lapsed. */
    public boolean answers(int answered, long now) {
        return id == answered && now <= expires;
    }

    /**
     * Why an answer does not fit the offer, or empty: a box is made into a room, a room is renamed or removed, and only a
     * new room may be for everyone, by a player who may make one.
     *
     * @param mayForAll whether the player may make rooms for every device ({@code mineqtt:rooms.admin} or an operator)
     */
    public Optional<String> answerProblem(Op op, boolean forAll, boolean mayForAll) {
        if (edits() == (op == Op.CREATE)) return Optional.of(edits() ? "this prompt renames or removes a room" : "this prompt makes a room");
        if (forAll && op != Op.CREATE) return Optional.of("only a new room is made for everyone");
        if (forAll && !mayForAll) return Optional.of("A room for everyone's devices needs operator rights or mineqtt:rooms.admin");
        return Optional.empty();
    }

    /** Its size along x, y and z, both corners included. */
    public int sizeX() {
        return max.getX() - min.getX() + 1;
    }

    public int sizeY() {
        return max.getY() - min.getY() + 1;
    }

    public int sizeZ() {
        return max.getZ() - min.getZ() + 1;
    }
}

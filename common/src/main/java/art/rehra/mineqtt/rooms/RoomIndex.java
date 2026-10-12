package art.rehra.mineqtt.rooms;

import art.rehra.mineqtt.device.DeviceRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Which room a device is in (M4 plan D11), derived from the rooms and never stored: the smallest room <i>applicable</i>
 * to the device that contains its position, of two of one size the older ({@link RoomRules#PRECEDENCE}). A room is
 * applicable to a device in its dimension when it is the device owner's room or one for everyone, so a stranger's room
 * over your house changes nothing, a pantry inside the kitchen wins for the devices in it, and rooms may overlap.
 * Built from the registry's rooms, again after every edit; at most 512 rooms per world keep a lookup cheap. Immutable.
 */
public final class RoomIndex {
    public static final RoomIndex EMPTY = new RoomIndex(List.of());

    // Dimension -> its rooms in precedence order, so the first that holds a device and applies to it is its room.
    private final Map<ResourceKey<Level>, List<Room>> byDimension = new HashMap<>();

    public RoomIndex(Collection<Room> rooms) {
        for (Room room : rooms) byDimension.computeIfAbsent(room.dimension(), dimension -> new ArrayList<>()).add(room);
        byDimension.values().forEach(list -> list.sort(RoomRules.PRECEDENCE));
    }

    /** The room of a device at {@code pos} owned by {@code owner} (null for an ownerless device). */
    public Optional<Room> roomOf(GlobalPos pos, @Nullable UUID owner) {
        Optional<UUID> deviceOwner = Optional.ofNullable(owner);
        for (Room room : byDimension.getOrDefault(pos.dimension(), List.of())) {
            if (room.contains(pos.pos()) && room.appliesTo(deviceOwner)) return Optional.of(room);
        }
        return Optional.empty();
    }

    public Optional<Room> roomOf(DeviceRecord record) {
        return roomOf(record.pos(), record.owner());
    }

    /** Every room that holds {@code pos}, applicable or not, in precedence order. */
    public List<Room> at(GlobalPos pos) {
        return byDimension.getOrDefault(pos.dimension(), List.of()).stream().filter(room -> room.contains(pos.pos())).toList();
    }

    /**
     * The rooms of a dimension that come within {@code radius} blocks of {@code center} on every axis, nearest first, for
     * the Configurator's outline.
     */
    public List<Room> near(ResourceKey<Level> dimension, BlockPos center, int radius) {
        return byDimension.getOrDefault(dimension, List.of()).stream()
                .filter(room -> distance(room, center) <= radius)
                .sorted((a, b) -> Integer.compare(distance(a, center), distance(b, center)))
                .toList();
    }

    // The largest of the distances along x, y and z from a position to a room's box; 0 inside it.
    private static int distance(Room room, BlockPos pos) {
        int dx = Math.max(0, Math.max(room.min().getX() - pos.getX(), pos.getX() - room.max().getX()));
        int dy = Math.max(0, Math.max(room.min().getY() - pos.getY(), pos.getY() - room.max().getY()));
        int dz = Math.max(0, Math.max(room.min().getZ() - pos.getZ(), pos.getZ() - room.max().getZ()));
        return Math.max(dx, Math.max(dy, dz));
    }

    /**
     * The devices whose room may differ after a room edit: those inside the room's box before or after it that the room
     * applies to. Only rooms that hold a device decide its room, so no other device can change; the discovery of each is
     * checked again, and published only where its {@code suggested_area} changed.
     *
     * @param before the room before the edit, null for one just made
     * @param after  the room after the edit, null for one just removed
     */
    public static Set<UUID> affected(Collection<DeviceRecord> records, @Nullable Room before, @Nullable Room after) {
        Set<UUID> affected = new LinkedHashSet<>();
        for (DeviceRecord record : records) {
            Optional<UUID> owner = Optional.ofNullable(record.owner());
            boolean inBefore = before != null && before.contains(record.pos()) && before.appliesTo(owner);
            boolean inAfter = after != null && after.contains(record.pos()) && after.appliesTo(owner);
            if (inBefore || inAfter) affected.add(record.id());
        }
        return affected;
    }

    /**
     * The room every member of a group is in, for the group's {@code suggested_area}: empty for a group without members,
     * or when a member is in no room or in another room than the rest.
     */
    public Optional<Room> commonRoom(Collection<DeviceRecord> members) {
        Room common = null;
        for (DeviceRecord member : members) {
            Optional<Room> room = roomOf(member);
            if (room.isEmpty() || common != null && !common.id().equals(room.get().id())) return Optional.empty();
            common = room.get();
        }
        return Optional.ofNullable(common);
    }
}

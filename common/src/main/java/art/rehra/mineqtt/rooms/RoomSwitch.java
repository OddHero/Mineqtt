package art.rehra.mineqtt.rooms;

import art.rehra.mineqtt.device.Action;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A room's All on and All off (M4 plan D1, D13): the switch state of every device in the room that the player finds (the
 * explorer's rule: their own devices they may configure, every device for an operator who may build) and that switches on
 * and off ({@link #switches}: lamps, plugs and outputs, not timers), at most {@value #MAX_DEVICES} of them, at most once
 * per {@value #MIN_INTERVAL_MILLIS} ms per player.
 * A device of another player in a room for everyone is skipped, as is a device of a nested room (the pantry in the
 * kitchen belongs to the pantry, as its {@code suggested_area} does). The command twin is {@code /mineqtt rooms switch}.
 * Pure; {@code DeviceRuntime#roomSwitch} sends the commands, and {@code verifyRooms} checks the rules.
 */
public final class RoomSwitch {
    /** At most this many devices are switched at once. */
    public static final int MAX_DEVICES = 64;
    /** A player switches a room at most once in this many milliseconds. */
    public static final long MIN_INTERVAL_MILLIS = 1000;

    private RoomSwitch() {
    }

    /**
     * The devices a switch reaches, by name, and how many it leaves out over the cap.
     *
     * @param skipped how many more devices the switch would have reached beyond {@value #MAX_DEVICES}
     */
    public record Selection(List<DeviceRecord> targets, int skipped) {
        public Selection {
            targets = List.copyOf(targets);
        }
    }

    /**
     * What a switch did.
     *
     * @param rate      it was refused: the player switched a room less than {@value #MIN_INTERVAL_MILLIS} ms ago
     * @param applied   devices that switched
     * @param unchanged devices already as asked
     * @param queued    unloaded devices whose switch waits until they load
     * @param refused   devices that took no command (an unloaded device that is unavailable, a mirrored one that refused)
     * @param skipped   devices left out over the cap of {@value #MAX_DEVICES}
     */
    public record Result(boolean rate, int applied, int unchanged, int queued, int refused, int skipped) {
        /** The refusal of a switch that came too soon. */
        public static final Result TOO_SOON = new Result(true, 0, 0, 0, 0, 0);

        /** How many devices the switch reached. */
        public int reached() {
            return applied + unchanged + queued + refused;
        }

        /** What the player is told, in the command's reply and the explorer's action bar alike. */
        public String describe(String room, boolean on) {
            if (rate) return "One room switch a second; try " + room + " again in a moment";
            String state = on ? "on" : "off";
            if (reached() == 0) return "Nothing in " + room + " to switch " + state + ": no lamp, plug or output of yours there";
            StringBuilder text = new StringBuilder(room).append(": ").append(applied).append(" switched ").append(state);
            if (unchanged > 0) text.append(", ").append(unchanged).append(" already ").append(state);
            if (queued > 0) text.append(", ").append(queued).append(" queued until loaded");
            if (refused > 0) text.append(", ").append(refused).append(" refused");
            if (skipped > 0) text.append(", ").append(skipped).append(" left out (at most ").append(MAX_DEVICES).append(" at once)");
            return text.toString();
        }
    }

    /**
     * Whether a room switch reaches devices of this type: an actuator whose {@code state} is its power, the kind a group
     * switches together (a lamp, a plug, an output). A timer declares {@code state} too, but there it arms or disarms the
     * timer, which a room's All off must not do to a sunset timer that switches the lamps.
     */
    public static boolean switches(DeviceType type) {
        return type.groupable() && type.action(Action.STATE).isPresent();
    }

    /**
     * The devices of {@code room} a switch reaches: those whose room is this one ({@code RoomIndex.roomOf}, so not one of a
     * nested room), that the player finds and that switch on and off, by name then slug, at most {@value #MAX_DEVICES}.
     *
     * @param roomOf   the room a device is in
     * @param finds    whether the player finds a device (the explorer's rule)
     * @param hasState whether a device's type switches on and off ({@link #switches})
     */
    public static Selection targets(Collection<DeviceRecord> records, Room room, Function<DeviceRecord, Optional<Room>> roomOf,
                                    Predicate<DeviceRecord> finds, Predicate<DeviceRecord> hasState) {
        List<DeviceRecord> reached = new ArrayList<>();
        for (DeviceRecord record : records) {
            if (roomOf.apply(record).filter(in -> in.id().equals(room.id())).isPresent() && hasState.test(record) && finds.test(record)) reached.add(record);
        }
        reached.sort(Comparator.comparing((DeviceRecord record) -> record.name().toLowerCase(Locale.ROOT)).thenComparing(DeviceRecord::slug));
        int skipped = Math.max(0, reached.size() - MAX_DEVICES);
        return new Selection(reached.subList(0, reached.size() - skipped), skipped);
    }

    /**
     * Whether a player may switch a room now, a pure function of the time of their last switch.
     *
     * @param lastMillis when they last switched a room, on a monotonic clock in milliseconds; null if never
     */
    public static boolean due(@Nullable Long lastMillis, long nowMillis) {
        return lastMillis == null || nowMillis - lastMillis >= MIN_INTERVAL_MILLIS;
    }

    /**
     * Whether {@code player} may switch a room now ({@link #due}); if so, the switch is recorded in {@code lastSwitch}.
     * Every entry that could no longer refuse anyone is dropped first, so the map holds only the players who switched a
     * room within the last {@value #MIN_INTERVAL_MILLIS} ms, however many players switch in a server's run.
     *
     * @param lastSwitch when each player last switched a room, on a monotonic clock in milliseconds
     */
    public static boolean admit(Map<UUID, Long> lastSwitch, UUID player, long nowMillis) {
        lastSwitch.values().removeIf(lastMillis -> due(lastMillis, nowMillis));
        if (lastSwitch.containsKey(player)) return false;
        lastSwitch.put(player, nowMillis);
        return true;
    }
}

package art.rehra.mineqtt.device.types;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.device.Access;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.Field;
import art.rehra.mineqtt.device.RawLinkSpec;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.UnloadedPolicy;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.ha.Ha;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.phys.AABB;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * The Presence Sensor ({@code motion_sensor_block}): counts the players, the mobs or both in the area in front of it
 * and reports whether that area is occupied (design §3.3, §4.4, §7.2). The count follows the area at once; occupancy
 * stays on for the clear delay after the last detection, as a PIR sensor's does. Its state is published at most once
 * a second, as sensors' is. The Raw Link publishes the count on every change, as v1 motion sensors did.
 */
public final class PresenceSensor {
    public static final int MIN_RANGE = 1;
    public static final int MAX_RANGE = 8;
    /** v1's area: five blocks deep and five wide. */
    public static final int DEFAULT_RANGE = 5;
    public static final int MAX_CLEAR_DELAY = 3600;
    public static final int DEFAULT_CLEAR_DELAY = 10;
    /** How often the sensor looks, in game ticks: v1's interval. */
    public static final int SCAN_INTERVAL = 10;
    private static final int TICKS_PER_SECOND = 20;

    /** What the sensor counts. */
    public enum Detect implements StringRepresentable {
        /** Players that are not spectators; first, so it is the default. */
        PLAYERS,
        /** Every other living entity, which v1 motion sensors counted. */
        MOBS,
        ALL;

        public boolean players() {
            return this != MOBS;
        }

        public boolean mobs() {
            return this != PLAYERS;
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final Field<Boolean> OCCUPANCY = Field.bool("occupancy", Access.READ);
    public static final Field<Integer> COUNT = Field.intRange("count", 0, Integer.MAX_VALUE, Access.READ);
    public static final Field<Detect> DETECT = Field.enumOf("detect", Detect.class, Access.READ_WRITE);
    public static final Field<Integer> RANGE = new Field<>("range", ValueCodecs.intRange(MIN_RANGE, MAX_RANGE), DEFAULT_RANGE, Access.READ_WRITE, false);
    /** Seconds occupancy stays on after the area was last occupied. */
    public static final Field<Integer> CLEAR_DELAY = new Field<>("clear_delay", ValueCodecs.intRange(0, MAX_CLEAR_DELAY), DEFAULT_CLEAR_DELAY,
            Access.READ_WRITE, false);

    public static final DeviceType TYPE = DeviceType.builder(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "presence_sensor"))
            .model("Presence Sensor").unloaded(UnloadedPolicy.UNAVAILABLE).minInterval(Duration.ofSeconds(1))
            .field(OCCUPANCY).field(COUNT).field(DETECT).field(RANGE).field(CLEAR_DELAY).field(CommonFields.LOCATION)
            .entity(Ha.binarySensor("occupancy", OCCUPANCY).deviceClass("occupancy"))
            .entity(Ha.sensor("count", COUNT).stateClass("measurement"))
            .entity(Ha.select("detect", DETECT).config())
            .entity(Ha.number("range", RANGE).range(MIN_RANGE, MAX_RANGE).unit("blocks").config())
            .entity(Ha.number("clear_delay", CLEAR_DELAY).range(0, MAX_CLEAR_DELAY).unit("s").config())
            .entity(Ha.sensor("location", CommonFields.LOCATION).name("Location").diagnostic().disabledByDefault())
            // The count is the payload; slots 2 and 3 are spawn-egg filters, as they were in v1.
            .rawLink(RawLinkSpec.out().withoutCustomPayloads())
            .build();

    private PresenceSensor() {
    }

    /**
     * The area a sensor watches: a cube with the range as its edge that starts at the sensor's centre and reaches out
     * of its face. The default range is v1's box, {@code new AABB(pos).inflate(2).move(facing * 2.5)}.
     */
    public static AABB area(BlockPos pos, Direction facing, int range) {
        int edge = Math.clamp(range, MIN_RANGE, MAX_RANGE);
        double reach = edge / 2.0;
        return new AABB(pos).inflate((edge - 1) / 2.0).move(facing.getStepX() * reach, facing.getStepY() * reach, facing.getStepZ() * reach);
    }

    /**
     * A sensor's settings and what it saw.
     *
     * @param clearDelay seconds occupancy stays on after the area was last occupied
     * @param lastSeen   the game time of the last scan that counted anything; not part of the state JSON
     */
    public record Presence(Detect detect, int range, int clearDelay, int count, boolean occupancy, long lastSeen) {
        public static final Presence DEFAULT = new Presence(Detect.PLAYERS, DEFAULT_RANGE, DEFAULT_CLEAR_DELAY, 0, false, 0);
        /** The sensor in the block entity's {@code state} compound; unreadable values keep their default. */
        public static final Codec<Presence> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                DETECT.codec().lenientOptionalFieldOf(DETECT.key(), Detect.PLAYERS).forGetter(Presence::detect),
                RANGE.codec().lenientOptionalFieldOf(RANGE.key(), DEFAULT_RANGE).forGetter(Presence::range),
                CLEAR_DELAY.codec().lenientOptionalFieldOf(CLEAR_DELAY.key(), DEFAULT_CLEAR_DELAY).forGetter(Presence::clearDelay),
                COUNT.codec().lenientOptionalFieldOf(COUNT.key(), 0).forGetter(Presence::count),
                OCCUPANCY.codec().lenientOptionalFieldOf(OCCUPANCY.key(), false).forGetter(Presence::occupancy),
                Codec.LONG.lenientOptionalFieldOf("last_seen", 0L).forGetter(Presence::lastSeen)
        ).apply(instance, Presence::new));

        public Presence {
            range = Math.clamp(range, MIN_RANGE, MAX_RANGE);
            clearDelay = Math.clamp(clearDelay, 0, MAX_CLEAR_DELAY);
            count = Math.max(0, count);
        }

        /**
         * A v1 motion sensor: it counted mobs in its 5×5×5 box. v1 had no clear delay; its time of the last motion was
         * wall-clock time, so the delay of a sensor that saw something runs out at its first scan.
         *
         * @param lastCount v1's last published count, -1 before its first scan
         */
        public static Presence ofV1(int lastCount, boolean motionDetected) {
            return new Presence(Detect.MOBS, DEFAULT_RANGE, DEFAULT_CLEAR_DELAY, lastCount, motionDetected, 0);
        }

        /**
         * The sensor after a partial command: {@code detect}, {@code range} and {@code clear_delay}.
         *
         * @return empty when the command has none of these keys in a readable form
         */
        public Optional<Presence> apply(CommandReader command) {
            Optional<Detect> newDetect = command.get(DETECT);
            Optional<Integer> newRange = command.get(RANGE);
            Optional<Integer> newClearDelay = command.get(CLEAR_DELAY);
            if (newDetect.isEmpty() && newRange.isEmpty() && newClearDelay.isEmpty()) return Optional.empty();
            return Optional.of(new Presence(newDetect.orElse(detect), newRange.orElse(range), newClearDelay.orElse(clearDelay),
                    count, occupancy, lastSeen));
        }

        /**
         * The sensor after a scan at {@code gameTime} that counted {@code newCount}: occupied while it counts anything,
         * and for the clear delay after that. A last detection in the future (data from another world) has expired.
         */
        public Presence scanned(int newCount, long gameTime) {
            if (newCount > 0) return new Presence(detect, range, clearDelay, newCount, true, gameTime);
            boolean held = occupancy && gameTime >= lastSeen && gameTime - lastSeen < (long) clearDelay * TICKS_PER_SECOND;
            return new Presence(detect, range, clearDelay, 0, held, lastSeen);
        }

        /** Writes every readable field but the location. */
        public StateWriter write(StateWriter out) {
            return out.put(OCCUPANCY, occupancy).put(COUNT, count).put(DETECT, detect).put(RANGE, range).put(CLEAR_DELAY, clearDelay);
        }
    }
}

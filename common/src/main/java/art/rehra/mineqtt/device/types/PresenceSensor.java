package art.rehra.mineqtt.device.types;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.device.Access;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.Field;
import art.rehra.mineqtt.device.RawLinkSpec;
import art.rehra.mineqtt.device.Setting;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.UnloadedPolicy;
import art.rehra.mineqtt.ha.Ha;
import art.rehra.mineqtt.flow.core.PortType;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.AABB;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    /** How many entity types the filter names at most. */
    public static final int MAX_FILTER = 8;
    // Room for MAX_FILTER long modded ids and their separators.
    private static final int MAX_FILTER_TEXT = 512;
    private static final Pattern FILTER_SEPARATOR = Pattern.compile("[,\\s]+");
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
    public static final Field<Integer> RANGE = Field.intRange("range", MIN_RANGE, MAX_RANGE, DEFAULT_RANGE, "blocks", Access.READ_WRITE);
    /** Seconds occupancy stays on after the area was last occupied. */
    public static final Field<Integer> CLEAR_DELAY = Field.intRange("clear_delay", 0, MAX_CLEAR_DELAY, DEFAULT_CLEAR_DELAY, "s", Access.READ_WRITE);
    /**
     * The mobs counted: entity type ids, written {@code zombie, pig} ({@link #filterText}); empty counts every mob. It took
     * over from v1's spawn-egg slots (M2-12). Its codec checks what players, commands and Home Assistant set
     * ({@link #parseFilter}); saved data is read with {@link #storedFilter}.
     */
    public static final Field<List<Identifier>> FILTER = new Field<>("filter", Codec.STRING.comapFlatMap(PresenceSensor::parseFilter,
            PresenceSensor::filterText), List.of(), Access.READ_WRITE, false, PortType.text(MAX_FILTER_TEXT));
    /**
     * A saved filter ({@link #storedFilter}): an id whose mob is not registered now, e.g. of a removed mod, stays in it,
     * counts nothing and is back in use once its mob is.
     */
    private static final Codec<List<Identifier>> STORED_FILTER = Codec.STRING.xmap(PresenceSensor::storedFilter, PresenceSensor::filterText);

    public static final DeviceType TYPE = DeviceType.builder(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "presence_sensor"))
            .model("Presence Sensor").unloaded(UnloadedPolicy.UNAVAILABLE).minInterval(Duration.ofSeconds(1))
            .field(OCCUPANCY).field(COUNT).field(DETECT).field(RANGE).field(CLEAR_DELAY).field(FILTER).field(CommonFields.LOCATION)
            .setting(new Setting<>(DETECT)).setting(new Setting<>(RANGE)).setting(new Setting<>(CLEAR_DELAY)).setting(new Setting<>(FILTER))
            .entity(Ha.binarySensor("occupancy", OCCUPANCY).deviceClass("occupancy"))
            .entity(Ha.sensor("count", COUNT).stateClass("measurement"))
            .entity(Ha.select("detect", DETECT).config())
            .entity(Ha.number("range", RANGE).range(MIN_RANGE, MAX_RANGE).unit("blocks").config())
            .entity(Ha.number("clear_delay", CLEAR_DELAY).range(0, MAX_CLEAR_DELAY).unit("s").config())
            .entity(Ha.sensor("location", CommonFields.LOCATION).name("Location").diagnostic().disabledByDefault())
            // The count is the payload, 0 for "off"; there are no custom payloads, as v1's slots 2 and 3 were spawn-egg
            // filters. A count is no wireless redstone, so it is not retained unless the link says so (M2 plan D2).
            .rawLink(RawLinkSpec.out(custom -> "0").withoutCustomPayloads())
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
     * The entity types a filter text names (M2-12): ids separated by commas or spaces, {@code minecraft:} when no namespace
     * is given, each once; empty for no filter. Every id must name a registered entity type other than the player, whom
     * {@link Detect} counts, and at most {@value #MAX_FILTER} may be given.
     */
    public static DataResult<List<Identifier>> parseFilter(String text) {
        List<Identifier> filter = new ArrayList<>();
        for (String token : FILTER_SEPARATOR.split(text.strip())) {
            if (token.isEmpty()) continue;
            Identifier id = Identifier.tryParse(token);
            if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) return DataResult.error(() -> "No such mob: " + token);
            if (id.equals(EntityType.getKey(EntityTypes.PLAYER))) return DataResult.error(() -> "Players are counted by detect, not by the filter");
            if (!filter.contains(id)) filter.add(id);
        }
        if (filter.size() > MAX_FILTER) return DataResult.error(() -> "A filter names at most " + MAX_FILTER + " mobs");
        return DataResult.success(List.copyOf(filter));
    }

    /**
     * The entity types a saved filter text names, which {@link #parseFilter} once took: every id kept, also one whose mob
     * is not registered now, so none is lost while a mod is away. Left out are only what is no id at all, written by
     * hand, and the player, whom {@link Detect} counts.
     */
    public static List<Identifier> storedFilter(String text) {
        List<Identifier> filter = new ArrayList<>();
        Identifier player = EntityType.getKey(EntityTypes.PLAYER);
        for (String token : FILTER_SEPARATOR.split(text.strip())) {
            Identifier id = token.isEmpty() ? null : Identifier.tryParse(token);
            if (id != null && !id.equals(player) && !filter.contains(id)) filter.add(id);
        }
        return List.copyOf(filter);
    }

    /** A filter as its text: {@code zombie, pig, othermod:wisp}, vanilla mobs without their namespace; empty for none. */
    public static String filterText(List<Identifier> filter) {
        return filter.stream().map(id -> id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE) ? id.getPath() : id.toString())
                .collect(Collectors.joining(", "));
    }

    /**
     * A sensor's settings and what it saw.
     *
     * @param clearDelay seconds occupancy stays on after the area was last occupied
     * @param filter     the mobs it counts; empty for every mob
     * @param lastSeen   the game time of the last scan that counted anything; not part of the state JSON
     */
    public record Presence(Detect detect, int range, int clearDelay, List<Identifier> filter, int count, boolean occupancy, long lastSeen) {
        public static final Presence DEFAULT = new Presence(Detect.PLAYERS, DEFAULT_RANGE, DEFAULT_CLEAR_DELAY, List.of(), 0, false, 0);
        /**
         * The sensor in the block entity's {@code state} compound; unreadable values keep their default, and the filter
         * keeps the ids of mobs that are not registered now ({@link PresenceSensor#storedFilter}).
         */
        public static final Codec<Presence> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                DETECT.codec().lenientOptionalFieldOf(DETECT.key(), Detect.PLAYERS).forGetter(Presence::detect),
                RANGE.codec().lenientOptionalFieldOf(RANGE.key(), DEFAULT_RANGE).forGetter(Presence::range),
                CLEAR_DELAY.codec().lenientOptionalFieldOf(CLEAR_DELAY.key(), DEFAULT_CLEAR_DELAY).forGetter(Presence::clearDelay),
                STORED_FILTER.lenientOptionalFieldOf(FILTER.key(), List.of()).forGetter(Presence::filter),
                COUNT.codec().lenientOptionalFieldOf(COUNT.key(), 0).forGetter(Presence::count),
                OCCUPANCY.codec().lenientOptionalFieldOf(OCCUPANCY.key(), false).forGetter(Presence::occupancy),
                Codec.LONG.lenientOptionalFieldOf("last_seen", 0L).forGetter(Presence::lastSeen)
        ).apply(instance, Presence::new));

        public Presence {
            range = Math.clamp(range, MIN_RANGE, MAX_RANGE);
            clearDelay = Math.clamp(clearDelay, 0, MAX_CLEAR_DELAY);
            filter = List.copyOf(filter);
            count = Math.max(0, count);
        }

        /**
         * A v1 motion sensor: it counted mobs in its 5×5×5 box. v1 had no clear delay; its time of the last motion was
         * wall-clock time, so the delay of a sensor that saw something runs out at its first scan.
         *
         * @param lastCount v1's last published count, -1 before its first scan
         */
        public static Presence ofV1(int lastCount, boolean motionDetected) {
            return new Presence(Detect.MOBS, DEFAULT_RANGE, DEFAULT_CLEAR_DELAY, List.of(), lastCount, motionDetected, 0);
        }

        /** The same sensor counting the mobs of {@code newFilter}, e.g. the eggs its old slots held. */
        public Presence withFilter(List<Identifier> newFilter) {
            return new Presence(detect, range, clearDelay, newFilter, count, occupancy, lastSeen);
        }

        /**
         * A placed copy (M2 plan D14): the settings stay, what it saw is gone until its first scan.
         */
        public Presence placed() {
            return new Presence(detect, range, clearDelay, filter, 0, false, 0);
        }

        /** Whether a mob of this type is counted: every mob without a filter, else the filter's. */
        public boolean counts(EntityType<?> mob) {
            return filter.isEmpty() || filter.contains(EntityType.getKey(mob));
        }

        /**
         * The sensor a state JSON shows, e.g. a screen's view; what is missing or unreadable keeps its default. A state
         * has no time of the last detection.
         */
        public static Presence of(JsonObject state) {
            return CODEC.parse(JsonOps.INSTANCE, state).result().orElse(DEFAULT);
        }

        /**
         * The sensor after a partial command: {@code detect}, {@code range}, {@code clear_delay} and {@code filter}.
         *
         * @return empty when the command has none of these keys in a readable form
         */
        public Optional<Presence> apply(CommandReader command) {
            Optional<Detect> newDetect = command.get(DETECT);
            Optional<Integer> newRange = command.get(RANGE);
            Optional<Integer> newClearDelay = command.get(CLEAR_DELAY);
            Optional<List<Identifier>> newFilter = command.get(FILTER);
            if (newDetect.isEmpty() && newRange.isEmpty() && newClearDelay.isEmpty() && newFilter.isEmpty()) return Optional.empty();
            return Optional.of(new Presence(newDetect.orElse(detect), newRange.orElse(range), newClearDelay.orElse(clearDelay),
                    newFilter.orElse(filter), count, occupancy, lastSeen));
        }

        /**
         * The sensor after a scan at {@code gameTime} that counted {@code newCount}: occupied while it counts anything,
         * and for the clear delay after that. A last detection in the future (data from another world) has expired.
         */
        public Presence scanned(int newCount, long gameTime) {
            if (newCount > 0) return new Presence(detect, range, clearDelay, filter, newCount, true, gameTime);
            boolean held = occupancy && gameTime >= lastSeen && gameTime - lastSeen < (long) clearDelay * TICKS_PER_SECOND;
            return new Presence(detect, range, clearDelay, filter, 0, held, lastSeen);
        }

        /** Writes every readable field but the location. */
        public StateWriter write(StateWriter out) {
            return out.put(OCCUPANCY, occupancy).put(COUNT, count).put(DETECT, detect).put(RANGE, range).put(CLEAR_DELAY, clearDelay)
                    .put(FILTER, filter);
        }
    }
}

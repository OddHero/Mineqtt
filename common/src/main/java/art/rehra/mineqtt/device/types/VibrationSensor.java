package art.rehra.mineqtt.device.types;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.device.Access;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.EventSpec;
import art.rehra.mineqtt.device.Field;
import art.rehra.mineqtt.device.RawLinkSpec;
import art.rehra.mineqtt.device.Setting;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.UnloadedPolicy;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.ha.Ha;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The Vibration Sensor ({@code vibration_sensor}): hears the vibrations a sculk sensor hears within its range, through
 * vanilla's vibration system, so wool blocks them, wool carpets and sneaking dampen them and spectators make none. Every
 * vibration it hears is an event of its group ({@link Group}: {@code step}, {@code block}, {@code door} and so on) with
 * the vanilla frequency (1 to 15), the game event and who caused it ({@link Source}), and turns {@code vibration} on
 * until the clear delay after the last one, as a PIR sensor's occupancy. Its state is published at most once a second,
 * as sensors' is, and each group's event at most once a second; it hears at most one vibration every
 * {@value #COOLDOWN_TICKS} ticks.
 * <p>
 * Nothing about a player leaves the server: a player's vibration is reported as {@code source} {@code player} with its
 * group, game event and frequency, never with a name, a UUID, a position or the distance ({@link #reportedDistance}).
 */
public final class VibrationSensor {
    public static final int MIN_RANGE = 1;
    /** The farthest any vanilla game event is heard ({@code GameEvent#notificationRadius}). */
    public static final int MAX_RANGE = 16;
    /** A sculk sensor's range. */
    public static final int DEFAULT_RANGE = 8;
    /** At least a second, so that the state published at most once a second shows every vibration as on. */
    public static final int MIN_CLEAR_DELAY = 1;
    public static final int MAX_CLEAR_DELAY = 3600;
    public static final int DEFAULT_CLEAR_DELAY = 10;
    /** Vanilla's highest vibration frequency. */
    public static final int MAX_FREQUENCY = 15;
    /** The fewest game ticks between two vibrations the sensor hears: at most two a second. */
    public static final int COOLDOWN_TICKS = 10;
    /** The shortest time between two published events of one group. */
    public static final Duration EVENT_INTERVAL = Duration.ofSeconds(1);
    /** The type of the Home Assistant device triggers, one per group as its subtype. */
    public static final String TRIGGER_TYPE = "vibration";
    /** The attributes every vibration event carries besides {@code event_type}. */
    public static final String FREQUENCY_KEY = "frequency";
    public static final String GAME_EVENT_KEY = "game_event";
    public static final String SOURCE_KEY = "source";
    private static final int TICKS_PER_SECOND = 20;
    /** The game events that are this block's own placement and breaking, which a sculk sensor does not hear either. */
    private static final List<String> OWN_BLOCK_EVENTS = List.of("minecraft:block_place", "minecraft:block_destroy");

    /**
     * What kind of vibration it was: a fixed set, so Home Assistant's event types never change. The game event decides,
     * and for a landing, splash or bounce what landed (an item, a projectile, else a step).
     */
    public enum Group implements StringRepresentable {
        /** Moving: steps, swimming, flapping, gliding, landing. */
        STEP,
        /** A projectile shot or landing. */
        PROJECTILE,
        /** Using an item: eating, drinking, equipping, an instrument, a dropped item landing. */
        ITEM,
        /** An entity acting, interacting, mounting, getting hurt, dying, placed or teleporting. */
        ENTITY,
        /** A door, trapdoor or fence gate opening or closing. */
        DOOR,
        /** A container opening or closing. */
        CONTAINER,
        /** A block placed, broken, changed or switched (buttons, levers, pressure plates, pistons), or a fluid. */
        BLOCK,
        /** Explosions, lightning, resonance, and any game event of a mod. */
        OTHER;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The group's event, also its out-port. */
        public EventSpec spec() {
            return SPECS.get(this);
        }
    }

    /** Who caused a vibration, as far as anybody may know: a player is never named. */
    public enum Source implements StringRepresentable {
        /** Nothing alive or moving: redstone, a dispenser, a piston, fluids, lightning. */
        WORLD,
        /** An entity that is no player and acts for none: a mob, a dropped item, a minecart, a mob's arrow. */
        ENTITY,
        /** A player, their arrow, the item they threw or the mount they ride. */
        PLAYER;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Which vibrations the sensor hears, by who caused them. */
    public enum Detect implements StringRepresentable {
        /** Every vibration; first, so it is the default. */
        ALL,
        /** Those an entity caused, players included: no redstone contraption sets it off. */
        ENTITIES,
        PLAYERS,
        /** Every vibration but the players'. */
        NON_PLAYERS;

        public boolean hears(Source source) {
            return switch (this) {
                case ALL -> true;
                case ENTITIES -> source != Source.WORLD;
                case PLAYERS -> source == Source.PLAYER;
                case NON_PLAYERS -> source != Source.PLAYER;
            };
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final PortType SOURCE_KIND = PortType.enumOf(Arrays.stream(Source.values()).map(Source::getSerializedName).toList());
    // Built here rather than in the enum, whose constants this class's own initialisation needs.
    private static final Map<Group, EventSpec> SPECS = specs();

    /**
     * The groups of vanilla's vibrations ({@code #minecraft:vibrations}) by game event id; the ids, not the constants, so
     * that an event a version lacks ({@code minecraft:bounce} before 1.21.5) costs nothing. Landings are decided by what
     * landed ({@link #group}).
     */
    private static final Map<String, Group> GROUPS = Map.ofEntries(
            Map.entry("minecraft:step", Group.STEP),
            Map.entry("minecraft:swim", Group.STEP),
            Map.entry("minecraft:flap", Group.STEP),
            Map.entry("minecraft:elytra_glide", Group.STEP),
            Map.entry("minecraft:hit_ground", Group.STEP),
            Map.entry("minecraft:splash", Group.STEP),
            Map.entry("minecraft:bounce", Group.STEP),
            Map.entry("minecraft:projectile_shoot", Group.PROJECTILE),
            Map.entry("minecraft:projectile_land", Group.PROJECTILE),
            Map.entry("minecraft:item_interact_finish", Group.ITEM),
            Map.entry("minecraft:instrument_play", Group.ITEM),
            Map.entry("minecraft:equip", Group.ITEM),
            Map.entry("minecraft:unequip", Group.ITEM),
            Map.entry("minecraft:eat", Group.ITEM),
            Map.entry("minecraft:drink", Group.ITEM),
            Map.entry("minecraft:entity_action", Group.ENTITY),
            Map.entry("minecraft:entity_interact", Group.ENTITY),
            Map.entry("minecraft:entity_mount", Group.ENTITY),
            Map.entry("minecraft:entity_dismount", Group.ENTITY),
            Map.entry("minecraft:entity_damage", Group.ENTITY),
            Map.entry("minecraft:entity_die", Group.ENTITY),
            Map.entry("minecraft:entity_place", Group.ENTITY),
            Map.entry("minecraft:shear", Group.ENTITY),
            Map.entry("minecraft:teleport", Group.ENTITY),
            Map.entry("minecraft:block_open", Group.DOOR),
            Map.entry("minecraft:block_close", Group.DOOR),
            Map.entry("minecraft:container_open", Group.CONTAINER),
            Map.entry("minecraft:container_close", Group.CONTAINER),
            Map.entry("minecraft:block_place", Group.BLOCK),
            Map.entry("minecraft:block_destroy", Group.BLOCK),
            Map.entry("minecraft:block_change", Group.BLOCK),
            Map.entry("minecraft:block_attach", Group.BLOCK),
            Map.entry("minecraft:block_detach", Group.BLOCK),
            Map.entry("minecraft:block_activate", Group.BLOCK),
            Map.entry("minecraft:block_deactivate", Group.BLOCK),
            Map.entry("minecraft:fluid_place", Group.BLOCK),
            Map.entry("minecraft:fluid_pickup", Group.BLOCK),
            Map.entry("minecraft:note_block_play", Group.BLOCK),
            Map.entry("minecraft:prime_fuse", Group.BLOCK),
            Map.entry("minecraft:explode", Group.OTHER),
            Map.entry("minecraft:lightning_strike", Group.OTHER));
    /** The landings, which belong to what landed. */
    private static final List<String> LANDINGS = List.of("minecraft:hit_ground", "minecraft:splash", "minecraft:bounce");

    public static final Field<Boolean> VIBRATION = Field.bool("vibration", Access.READ);
    /** How many vibrations the sensor heard since it was placed. */
    public static final Field<Integer> COUNT = Field.intRange("count", 0, Integer.MAX_VALUE, Access.READ);
    /** The last vibration's frequency, 1 to 15; 0 before the first. */
    public static final Field<Integer> FREQUENCY = Field.intRange("frequency", 0, MAX_FREQUENCY, Access.READ);
    /** The last vibration's group ({@link Group}); empty before the first. */
    public static final Field<String> LAST_GROUP = Field.text("last_group", Access.READ);
    /** The last vibration's game event id, e.g. {@code minecraft:block_open}; empty before the first. */
    public static final Field<String> LAST_EVENT = Field.text("last_event", Access.READ);
    /** Who caused the last vibration ({@link Source}); empty before the first. */
    public static final Field<String> LAST_SOURCE = Field.text("last_source", Access.READ);
    /** Blocks from the sensor to the last vibration, as vanilla measures it; 0 for a player's ({@link #reportedDistance}). */
    public static final Field<Double> LAST_DISTANCE = Field.decimal("last_distance", 0, MAX_RANGE, 1, 0.0, "m", Access.READ);
    public static final Field<Detect> DETECT = Field.enumOf("detect", Detect.class, Access.READ_WRITE);
    public static final Field<Integer> RANGE = Field.intRange("range", MIN_RANGE, MAX_RANGE, DEFAULT_RANGE, "blocks", Access.READ_WRITE);
    /** Seconds {@code vibration} stays on after the last vibration. */
    public static final Field<Integer> CLEAR_DELAY = Field.intRange("clear_delay", MIN_CLEAR_DELAY, MAX_CLEAR_DELAY, DEFAULT_CLEAR_DELAY, "s", Access.READ_WRITE);

    // Unknown before the first vibration; a sensor shows nothing for an empty state.
    private static final String FREQUENCY_TEMPLATE = "{{ value_json.frequency if value_json.count > 0 else None }}";
    private static final String LAST_EVENT_TEMPLATE = "{{ value_json.last_event if value_json.last_event else None }}";
    // A player's vibration has no distance, and neither has the sensor before its first.
    private static final String DISTANCE_TEMPLATE = "{{ value_json.last_distance if value_json.last_source in ['" + Source.WORLD.getSerializedName()
            + "', '" + Source.ENTITY.getSerializedName() + "'] else None }}";

    public static final DeviceType TYPE = type();

    private VibrationSensor() {
    }

    private static DeviceType type() {
        DeviceType.Builder builder = DeviceType.builder(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "vibration_sensor"))
                .model("Vibration Sensor").unloaded(UnloadedPolicy.UNAVAILABLE).minInterval(Duration.ofSeconds(1)).noV1Topic()
                .field(VIBRATION).field(COUNT).field(FREQUENCY).field(LAST_GROUP).field(LAST_EVENT).field(LAST_SOURCE).field(LAST_DISTANCE)
                .field(DETECT).field(RANGE).field(CLEAR_DELAY).field(CommonFields.LOCATION)
                .setting(new Setting<>(DETECT)).setting(new Setting<>(RANGE)).setting(new Setting<>(CLEAR_DELAY));
        for (Group group : Group.values()) builder.event(group.spec());
        builder.entity(Ha.binarySensor("vibration", VIBRATION).name(null).deviceClass("vibration"))
                // Home Assistant's event entities know no vibration class; motion is the nearest.
                .entity(Ha.event("action", Arrays.stream(Group.values()).map(Group::getSerializedName).toArray(String[]::new))
                        .name("Vibration").deviceClass("motion"))
                .entity(Ha.sensor("frequency", FREQUENCY).stateClass("measurement").valueTemplate(FREQUENCY_TEMPLATE))
                .entity(Ha.sensor("last_event", LAST_EVENT).valueTemplate(LAST_EVENT_TEMPLATE))
                .entity(Ha.sensor("last_distance", LAST_DISTANCE).deviceClass("distance").unit("m").stateClass("measurement")
                        .valueTemplate(DISTANCE_TEMPLATE))
                .entity(Ha.sensor("count", COUNT).name("Vibrations").stateClass("total_increasing"));
        for (Group group : Group.values()) builder.entity(Ha.deviceTrigger(TRIGGER_TYPE, group.getSerializedName(), group.getSerializedName()));
        return builder.entity(Ha.select("detect", DETECT).config())
                .entity(Ha.number("range", RANGE).range(MIN_RANGE, MAX_RANGE).unit("blocks").config())
                .entity(Ha.number("clear_delay", CLEAR_DELAY).range(MIN_CLEAR_DELAY, MAX_CLEAR_DELAY).unit("s").config())
                .entity(Ha.sensor("location", CommonFields.LOCATION).name("Location").diagnostic().disabledByDefault())
                // A new device without v1 data. Each vibration's frequency, as a calibrated sculk sensor's comparator gives
                // it; never retained, as an event carries no level.
                .rawLink(RawLinkSpec.events())
                .build();
    }

    // Each group's event with the attributes every vibration carries, published at most once per EVENT_INTERVAL.
    private static Map<Group, EventSpec> specs() {
        Map<Group, EventSpec> specs = new EnumMap<>(Group.class);
        for (Group group : Group.values()) {
            specs.put(group, EventSpec.of(group.getSerializedName())
                    .attribute(FREQUENCY_KEY, PortType.integer(1, MAX_FREQUENCY))
                    .attribute(GAME_EVENT_KEY, PortType.text(ValueCodecs.MAX_TEXT_LENGTH))
                    .attribute(SOURCE_KEY, SOURCE_KIND)
                    .minInterval(EVENT_INTERVAL));
        }
        return Collections.unmodifiableMap(specs);
    }

    /**
     * The group of a vibration: by its game event, a landing by what landed. A game event of a mod, or one this table does
     * not know, is {@link Group#OTHER}.
     *
     * @param gameEvent  the game event's id, e.g. {@code minecraft:step}
     * @param item       whether a dropped item caused it
     * @param projectile whether a projectile caused it
     */
    public static Group group(String gameEvent, boolean item, boolean projectile) {
        if (LANDINGS.contains(gameEvent)) {
            if (item) return Group.ITEM;
            if (projectile) return Group.PROJECTILE;
        }
        return GROUPS.getOrDefault(gameEvent, Group.OTHER);
    }

    /** The game event ids the table groups; every other one is {@link Group#OTHER}. */
    public static Map<String, Group> groups() {
        return GROUPS;
    }

    /**
     * Who caused a vibration.
     *
     * @param entity whether an entity caused it
     * @param player whether a player did, directly or through their projectile, thrown item or mount
     */
    public static Source source(boolean entity, boolean player) {
        if (player) return Source.PLAYER;
        return entity ? Source.ENTITY : Source.WORLD;
    }

    /**
     * Whether a game event is this sensor's own placement or breaking, which it does not hear, as a sculk sensor does not.
     *
     * @param atSensor whether the event happened at the sensor's position
     */
    public static boolean ownBlockEvent(String gameEvent, boolean atSensor) {
        return atSensor && OWN_BLOCK_EVENTS.contains(gameEvent);
    }

    /** The distance reported for a vibration: none (0) for a player's, as it would tell where the player is. */
    public static double reportedDistance(Source source, double distance) {
        return source == Source.PLAYER ? 0 : distance;
    }

    /**
     * A group's event: {@code {"event_type":"step","frequency":1,"game_event":"minecraft:step","source":"player"}}.
     */
    public static JsonObject event(Group group, int frequency, String gameEvent, Source source) {
        JsonObject event = group.spec().payload();
        event.addProperty(FREQUENCY_KEY, Math.clamp(frequency, 1, MAX_FREQUENCY));
        event.addProperty(GAME_EVENT_KEY, gameEvent);
        event.addProperty(SOURCE_KEY, source.getSerializedName());
        return event;
    }

    /**
     * A sensor's settings and what it heard last.
     *
     * @param count     how many vibrations it heard since it was placed
     * @param frequency the last vibration's frequency; 0 before the first
     * @param group     the last vibration's group; empty before the first
     * @param gameEvent the last vibration's game event id; empty before the first
     * @param source    who caused the last vibration; empty before the first
     * @param distance  blocks to the last vibration, 0 for a player's
     * @param lastHeard the game time of the last vibration; not part of the state JSON
     */
    public record Hearing(Detect detect, int range, int clearDelay, boolean vibration, int count, int frequency, String group, String gameEvent,
                          String source, double distance, long lastHeard) {
        public static final Hearing DEFAULT = new Hearing(Detect.ALL, DEFAULT_RANGE, DEFAULT_CLEAR_DELAY, false, 0, 0, "", "", "", 0, 0);
        /** The sensor in the block entity's {@code state} compound; unreadable values keep their default. */
        public static final Codec<Hearing> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                DETECT.codec().lenientOptionalFieldOf(DETECT.key(), Detect.ALL).forGetter(Hearing::detect),
                RANGE.codec().lenientOptionalFieldOf(RANGE.key(), DEFAULT_RANGE).forGetter(Hearing::range),
                CLEAR_DELAY.codec().lenientOptionalFieldOf(CLEAR_DELAY.key(), DEFAULT_CLEAR_DELAY).forGetter(Hearing::clearDelay),
                Codec.BOOL.lenientOptionalFieldOf(VIBRATION.key(), false).forGetter(Hearing::vibration),
                COUNT.codec().lenientOptionalFieldOf(COUNT.key(), 0).forGetter(Hearing::count),
                FREQUENCY.codec().lenientOptionalFieldOf(FREQUENCY.key(), 0).forGetter(Hearing::frequency),
                Codec.STRING.lenientOptionalFieldOf(LAST_GROUP.key(), "").forGetter(Hearing::group),
                Codec.STRING.lenientOptionalFieldOf(LAST_EVENT.key(), "").forGetter(Hearing::gameEvent),
                Codec.STRING.lenientOptionalFieldOf(LAST_SOURCE.key(), "").forGetter(Hearing::source),
                LAST_DISTANCE.codec().lenientOptionalFieldOf(LAST_DISTANCE.key(), 0.0).forGetter(Hearing::distance),
                Codec.LONG.lenientOptionalFieldOf("last_heard", 0L).forGetter(Hearing::lastHeard)
        ).apply(instance, Hearing::new));

        public Hearing {
            range = Math.clamp(range, MIN_RANGE, MAX_RANGE);
            clearDelay = Math.clamp(clearDelay, MIN_CLEAR_DELAY, MAX_CLEAR_DELAY);
            count = Math.max(0, count);
            frequency = Math.clamp(frequency, 0, MAX_FREQUENCY);
            group = ValueCodecs.sanitizeText(group);
            gameEvent = ValueCodecs.sanitizeText(gameEvent);
            source = ValueCodecs.sanitizeText(source);
            // Rounded as the state shows it.
            distance = Math.round(Math.clamp(distance, 0, MAX_RANGE) * 10) / 10.0;
        }

        /**
         * Whether the sensor still rests after the vibration it heard last: it hears one every {@value #COOLDOWN_TICKS}
         * ticks at most. A last vibration in the future (data from another world) is over.
         */
        public boolean resting(long gameTime) {
            return count > 0 && gameTime >= lastHeard && gameTime - lastHeard < COOLDOWN_TICKS;
        }

        /** Whether {@code vibration} is on past the clear delay at {@code gameTime}, or set by a last vibration in the future. */
        public boolean expired(long gameTime) {
            return vibration && (gameTime < lastHeard || gameTime - lastHeard >= (long) clearDelay * TICKS_PER_SECOND);
        }

        /** The sensor after a vibration at {@code gameTime}: on, counted, and what it was; a player's without its distance. */
        public Hearing heard(Group newGroup, int newFrequency, String newGameEvent, Source newSource, double newDistance, long gameTime) {
            int counted = count == Integer.MAX_VALUE ? count : count + 1;
            return new Hearing(detect, range, clearDelay, true, counted, newFrequency, newGroup.getSerializedName(), newGameEvent,
                    newSource.getSerializedName(), reportedDistance(newSource, newDistance), gameTime);
        }

        /** The sensor with {@code vibration} off; what it heard last stays. */
        public Hearing cleared() {
            return new Hearing(detect, range, clearDelay, false, count, frequency, group, gameEvent, source, distance, lastHeard);
        }

        /** A placed copy (M2 plan D14): the settings stay, what it heard is gone. */
        public Hearing placed() {
            return new Hearing(detect, range, clearDelay, false, 0, 0, "", "", "", 0, 0);
        }

        /** What a comparator reads: the last vibration's frequency while {@code vibration} is on, as a calibrated sculk sensor's. */
        public int comparatorLevel() {
            return vibration ? frequency : 0;
        }

        /**
         * The sensor after a partial command: {@code detect}, {@code range} and {@code clear_delay}.
         *
         * @return empty when the command has none of these keys in a readable form
         */
        public Optional<Hearing> apply(CommandReader command) {
            Optional<Detect> newDetect = command.get(DETECT);
            Optional<Integer> newRange = command.get(RANGE);
            Optional<Integer> newClearDelay = command.get(CLEAR_DELAY);
            if (newDetect.isEmpty() && newRange.isEmpty() && newClearDelay.isEmpty()) return Optional.empty();
            return Optional.of(new Hearing(newDetect.orElse(detect), newRange.orElse(range), newClearDelay.orElse(clearDelay), vibration, count, frequency,
                    group, gameEvent, source, distance, lastHeard));
        }

        /** Writes every readable field but the location. */
        public StateWriter write(StateWriter out) {
            return out.put(VIBRATION, vibration).put(COUNT, count).put(FREQUENCY, frequency).put(LAST_GROUP, group).put(LAST_EVENT, gameEvent)
                    .put(LAST_SOURCE, source).put(LAST_DISTANCE, distance).put(DETECT, detect).put(RANGE, range).put(CLEAR_DELAY, clearDelay);
        }
    }
}

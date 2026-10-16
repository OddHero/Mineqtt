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
import art.rehra.mineqtt.presence.PrivacyPolicy;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Entity Observer ({@code entity_observer}): reports the entity in front of its face, the nearest of those in the
 * block spaces it watches, with how many there are, its name, health and distance, type-specific details as the primary
 * Home Assistant sensor's attributes, and fires {@code changed} when another entity becomes the nearest or the last one
 * leaves. Its state is published at most once a second, as sensors' is; {@code changed} at most twice a second.
 * <p>
 * It never loads a chunk: it only looks while the entities of every block space it watches are loaded. Players are counted
 * like any entity, but nothing else about a player leaves the server unless that player shares it ({@link #names}):
 * a player in front is {@value #PLAYER}, named only with the player's consent to full sharing on a server that allows it,
 * and never with health, distance, position, details or entity data. Entity data ({@code /data get entity}) is never part
 * of its state; it reaches the Raw Link only in the {@code full} payload, only while the server allows it
 * ({@code entityObserver.exportEntityData}), only for an entity that is no player and stands in the observer's claim
 * ({@link #exportsEntityData}), with every UUID but the entity's own left out ({@link #withoutForeignUuids}), and cut at
 * {@value #MAX_ENTITY_DATA_BYTES} bytes.
 */
public final class EntityObserver {
    /** The event type of a change of the nearest entity. */
    public static final String CHANGED = "changed";
    /** The type id a player in front is reported as. */
    public static final String PLAYER = "minecraft:player";
    public static final int MIN_RANGE = 1;
    public static final int MAX_RANGE = 8;
    /** The block space in front of its face: the observer reports on what stands right there. */
    public static final int DEFAULT_RANGE = 1;
    /** How often the observer looks, in game ticks: the Presence Sensor's interval. */
    public static final int SCAN_INTERVAL = 10;
    /** How often a {@code full} payload whose entity data alone changed is sent again, in game ticks. */
    public static final int DATA_SAMPLE_TICKS = 20;
    /** The most a {@code full} payload carries of entity data, as UTF-8 JSON. */
    public static final int MAX_ENTITY_DATA_BYTES = 16 * 1024;
    /** Health and maximum health reach at most the maximum health attribute's ceiling. */
    public static final double MAX_HEALTH_POINTS = 1024;
    /** The farthest an entity in the watched spaces can be from the face, with room to spare. */
    public static final double MAX_DISTANCE = 16;
    /** Text among the details is cut to this many code points. */
    public static final int MAX_DETAIL_TEXT = 64;
    /** A UUID written as text, as some entity data keeps one. */
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    /** The key of an entity's own UUID in its data. */
    private static final String OWN_UUID = "UUID";
    /**
     * Keys whose values name a player as text: a player head's profile (name, id and textures), its pre-1.20.5 form, and a
     * written book's author. Left out wherever they occur, e.g. on a head a zombie wears or a book on the ground.
     */
    private static final Set<String> NAMING_KEYS = Set.of("profile", "minecraft:profile", "SkullOwner", "author");

    /** What the observer looks at. */
    public enum Detect implements StringRepresentable {
        /** Every entity but spectators; first, so it is the default. */
        ALL,
        /** Every entity but players. */
        NON_PLAYERS,
        /** Living entities that are no players, as the Presence Sensor's mobs. */
        MOBS;

        /** Whether an entity of this kind is counted. */
        public boolean counts(boolean player, boolean living) {
            return switch (this) {
                case ALL -> true;
                case NON_PLAYERS -> !player;
                case MOBS -> !player && living;
            };
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What the Raw Link carries. */
    public enum Payload implements StringRepresentable {
        /** Everything the state has, the entity's position and, where allowed, its entity data, as JSON; first, so the default. */
        FULL,
        ENTITY_ID,
        NAME,
        COUNT;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Details are a JSON object; anything else reads as none. */
    private static final Codec<JsonObject> DETAILS_CODEC = ExtraCodecs.JSON.comapFlatMap(
            json -> json instanceof JsonObject object ? DataResult.success(object.deepCopy()) : DataResult.error(() -> "Details are no JSON object"),
            JsonObject::deepCopy);
    /** Details as they are saved: their JSON text, so booleans stay booleans in NBT. */
    private static final Codec<JsonObject> STORED_DETAILS = Codec.STRING.xmap(EntityObserver::parseDetails, JsonObject::toString);

    /** The type id of the nearest entity, e.g. {@code minecraft:cow}; empty while nothing is there. */
    public static final Field<String> ENTITY = Field.text("entity", Access.READ);
    public static final Field<Boolean> PRESENT = Field.bool("present", Access.READ);
    /** How many entities the observer sees, players included. */
    public static final Field<Integer> COUNT = Field.intRange("count", 0, Integer.MAX_VALUE, Access.READ);
    /** The nearest entity's name: its custom name, else its type's (an item's for a dropped item); a player's only by consent. */
    public static final Field<String> NAME = Field.text("entity_name", Access.READ);
    /** Whether the nearest entity is a living entity whose health is reported: never a player. */
    public static final Field<Boolean> LIVING = Field.bool("living", Access.READ);
    public static final Field<Double> HEALTH = Field.decimal("health", 0, MAX_HEALTH_POINTS, 1, 0.0, "HP", Access.READ);
    public static final Field<Double> MAX_HEALTH = Field.decimal("max_health", 0, MAX_HEALTH_POINTS, 1, 0.0, "HP", Access.READ);
    /** Blocks from the centre of the observer's face to the nearest entity's feet; 0 for a player or nothing. */
    public static final Field<Double> DISTANCE = Field.decimal("distance", 0, MAX_DISTANCE, 1, 0.0, "m", Access.READ);
    /** Type-specific details of the nearest entity ({@link Details}); the Home Assistant sensor's attributes. */
    public static final Field<JsonObject> DETAILS = Field.json("details", DETAILS_CODEC, new JsonObject(), Access.READ);
    public static final Field<Detect> DETECT = Field.enumOf("detect", Detect.class, Access.READ_WRITE);
    public static final Field<Integer> RANGE = Field.intRange("range", MIN_RANGE, MAX_RANGE, DEFAULT_RANGE, "blocks", Access.READ_WRITE);
    public static final Field<Payload> PAYLOAD = Field.enumOf("payload", Payload.class, Access.READ_WRITE);
    /** Another entity became the nearest, or the last one left; with the type id of the nearest after it, empty for none. */
    public static final EventSpec CHANGED_EVENT = EventSpec.of(CHANGED).attribute(ENTITY.key(), PortType.text(ValueCodecs.MAX_TEXT_LENGTH))
            .minInterval(Duration.ofMillis(500));

    // Home Assistant reads "none" while nothing is there, so an automation can wait for it; a sensor ignores an empty state.
    private static final String ENTITY_TEMPLATE = "{{ value_json.entity if value_json.present else 'none' }}";
    private static final String NAME_TEMPLATE = "{{ value_json.entity_name if value_json.present else None }}";
    private static final String HEALTH_TEMPLATE = "{{ value_json.health if value_json.living else None }}";
    private static final String MAX_HEALTH_TEMPLATE = "{{ value_json.max_health if value_json.living else None }}";
    private static final String DISTANCE_TEMPLATE = "{{ value_json.distance if value_json.present and value_json.entity != '" + PLAYER + "' else None }}";

    public static final DeviceType TYPE = DeviceType.builder(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "entity_observer"))
            .model("Entity Observer").unloaded(UnloadedPolicy.UNAVAILABLE).minInterval(Duration.ofSeconds(1)).deadband(DISTANCE, 0.5).noV1Topic()
            .field(ENTITY).field(PRESENT).field(COUNT).field(NAME).field(LIVING).field(HEALTH).field(MAX_HEALTH).field(DISTANCE).field(DETAILS)
            .field(DETECT).field(RANGE).field(PAYLOAD).field(CommonFields.LOCATION)
            .setting(new Setting<>(DETECT)).setting(new Setting<>(RANGE)).setting(new Setting<>(PAYLOAD))
            .event(CHANGED_EVENT)
            .entity(Ha.sensor("entity", ENTITY).name(null).valueTemplate(ENTITY_TEMPLATE).jsonAttributes(DETAILS))
            .entity(Ha.binarySensor("present", PRESENT).deviceClass("occupancy"))
            .entity(Ha.sensor("count", COUNT).stateClass("measurement"))
            .entity(Ha.sensor("entity_name", NAME).name("Name").valueTemplate(NAME_TEMPLATE))
            .entity(Ha.sensor("health", HEALTH).unit("HP").stateClass("measurement").valueTemplate(HEALTH_TEMPLATE))
            .entity(Ha.sensor("max_health", MAX_HEALTH).unit("HP").valueTemplate(MAX_HEALTH_TEMPLATE))
            .entity(Ha.sensor("distance", DISTANCE).deviceClass("distance").unit("m").stateClass("measurement").valueTemplate(DISTANCE_TEMPLATE))
            .entity(Ha.event(CHANGED, CHANGED))
            .entity(Ha.select("detect", DETECT).config())
            .entity(Ha.number("range", RANGE).range(MIN_RANGE, MAX_RANGE).unit("blocks").config())
            .entity(Ha.select("payload", PAYLOAD).config().disabledByDefault())
            .entity(Ha.sensor("location", CommonFields.LOCATION).name("Location").diagnostic().disabledByDefault())
            // A new device without v1 data; not retained unless the link says so. An empty message clears a topic the link
            // leaves while it retains, as the observer has no "off" value.
            .rawLink(RawLinkSpec.out(custom -> "").withoutCustomPayloads())
            .build();

    private EntityObserver() {
    }

    /** The block spaces an observer watches: {@code range} spaces straight out of its face, the nearest first. */
    public static List<BlockPos> spaces(BlockPos pos, Direction facing, int range) {
        int spaces = Math.clamp(range, MIN_RANGE, MAX_RANGE);
        List<BlockPos> list = new ArrayList<>(spaces);
        for (int step = 1; step <= spaces; step++) list.add(pos.relative(facing, step));
        return List.copyOf(list);
    }

    /** The box of the watched spaces ({@link #spaces}); an entity whose box touches it is in front. */
    public static AABB area(BlockPos pos, Direction facing, int range) {
        List<BlockPos> spaces = spaces(pos, facing, range);
        BlockPos first = spaces.getFirst();
        BlockPos last = spaces.getLast();
        return new AABB(Math.min(first.getX(), last.getX()), Math.min(first.getY(), last.getY()), Math.min(first.getZ(), last.getZ()),
                Math.max(first.getX(), last.getX()) + 1, Math.max(first.getY(), last.getY()) + 1, Math.max(first.getZ(), last.getZ()) + 1);
    }

    /** The centre of the observer's face, from which distances are measured. */
    public static Vec3 face(BlockPos pos, Direction facing) {
        return Vec3.atCenterOf(pos).add(facing.getStepX() * 0.5, facing.getStepY() * 0.5, facing.getStepZ() * 0.5);
    }

    /**
     * Whether a player in front is named (M4 plan D9, D10): a name at a fixed device tells where the player is, so it takes
     * what a position takes, the player's consent to full sharing on a server that shares that much.
     *
     * @param shared what is shared about the player ({@link PrivacyPolicy#shared})
     */
    public static boolean names(PrivacyPolicy.Shared shared) {
        return shared == PrivacyPolicy.Shared.FULL;
    }

    /**
     * Whether a {@code full} payload carries the nearest entity's data: only while the server allows it, never for a player,
     * and only when the entity stands in the observer's claim, which it always does without a claim mod.
     *
     * @param allowed   {@code entityObserver.exportEntityData}
     * @param sameClaim whether the observer and the entity's block share one claim
     */
    public static boolean exportsEntityData(boolean allowed, boolean player, boolean sameClaim) {
        return allowed && !player && sameClaim;
    }

    /**
     * Entity data without what names others: a tamed animal's owner, an item's thrower, a villager's gossip targets, the
     * player a piglin is angry at or an allay likes. Every UUID (four ints, or its text form) is left out, except the
     * entity's own {@code UUID} at the top, and so is every player profile and book author ({@link #NAMING_KEYS}). Free
     * text such as custom names stays. The data is not changed.
     */
    public static JsonObject withoutForeignUuids(JsonObject data) {
        JsonObject copy = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
            if (entry.getKey().equals(OWN_UUID) && isUuid(entry.getValue())) {
                copy.add(entry.getKey(), entry.getValue().deepCopy());
            } else if (!isUuid(entry.getValue()) && !NAMING_KEYS.contains(entry.getKey())) {
                copy.add(entry.getKey(), scrubbed(entry.getValue()));
            }
        }
        return copy;
    }

    private static JsonElement scrubbed(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject copy = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (!isUuid(entry.getValue()) && !NAMING_KEYS.contains(entry.getKey())) copy.add(entry.getKey(), scrubbed(entry.getValue()));
            }
            return copy;
        }
        if (element.isJsonArray()) {
            JsonArray copy = new JsonArray();
            for (JsonElement item : element.getAsJsonArray()) {
                if (!isUuid(item)) copy.add(scrubbed(item));
            }
            return copy;
        }
        return element.deepCopy();
    }

    // A UUID as entity data keeps it: an int array of four (NBT's form; converted to JSON, four Integer numbers) or its text.
    private static boolean isUuid(JsonElement element) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) return UUID_TEXT.matcher(element.getAsString()).matches();
        if (!element.isJsonArray() || element.getAsJsonArray().size() != 4) return false;
        for (JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber() || !(item.getAsNumber() instanceof Integer)) return false;
        }
        return true;
    }

    /**
     * Whether a look is a change: another entity is the nearest, or the last one left.
     *
     * @param before the nearest entity at the last look, by its runtime id; empty when there was none
     * @param after  the nearest entity now; empty for none
     */
    public static boolean changed(Optional<Integer> before, Optional<Integer> after) {
        return !before.equals(after);
    }

    /** The {@code changed} event: {@code {"event_type":"changed","entity":"minecraft:cow"}}; an empty entity when none is left. */
    public static JsonObject changedEvent(String entity) {
        JsonObject event = CHANGED_EVENT.payload();
        event.addProperty(ENTITY.key(), entity);
        return event;
    }

    /**
     * What the Raw Link carries: the type id, the name or the count as text, or everything as JSON. The JSON's
     * {@code position} is the entity's, null for a player or nothing; {@code entity_data} is null unless {@code entityData}
     * is given ({@link #exportsEntityData}, {@link #withoutForeignUuids}); data beyond {@value #MAX_ENTITY_DATA_BYTES}
     * bytes is left out too, and {@code "truncated":true} says so.
     *
     * @param position   the nearest entity's position; null for a player or nothing
     * @param entityData the nearest entity's data, when it may be exported; null otherwise
     */
    public static String payload(Observation observation, @Nullable Vec3 position, String dimension, @Nullable JsonObject entityData) {
        switch (observation.payload()) {
            case ENTITY_ID:
                return observation.entity();
            case NAME:
                return observation.name();
            case COUNT:
                return Integer.toString(observation.count());
            default:
                break;
        }
        JsonObject data = new JsonObject();
        data.addProperty("entity", observation.entity());
        data.addProperty("name", observation.name());
        data.addProperty("present", observation.present());
        data.addProperty("count", observation.count());
        data.addProperty("living", observation.living());
        data.add("health", observation.living() ? new JsonPrimitive(observation.health()) : JsonNull.INSTANCE);
        data.add("max_health", observation.living() ? new JsonPrimitive(observation.maxHealth()) : JsonNull.INSTANCE);
        boolean located = observation.present() && !observation.player() && position != null;
        data.add("distance", located ? new JsonPrimitive(observation.distance()) : JsonNull.INSTANCE);
        data.add("details", observation.details().deepCopy());
        if (located) {
            JsonObject at = new JsonObject();
            at.addProperty("x", round(position.x));
            at.addProperty("y", round(position.y));
            at.addProperty("z", round(position.z));
            data.add("position", at);
        } else {
            data.add("position", JsonNull.INSTANCE);
        }
        data.addProperty("dimension", dimension);
        JsonElement exported = entityData == null || observation.player() ? JsonNull.INSTANCE : entityData;
        if (exported.toString().getBytes(StandardCharsets.UTF_8).length > MAX_ENTITY_DATA_BYTES) {
            data.add("entity_data", JsonNull.INSTANCE);
            data.addProperty("truncated", true);
        } else {
            data.add("entity_data", exported);
        }
        return data.toString();
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static double tenths(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private static JsonObject parseDetails(String text) {
        try {
            return JsonParser.parseString(text) instanceof JsonObject object ? object : new JsonObject();
        } catch (JsonParseException e) {
            return new JsonObject();
        }
    }

    /** Type-specific details of an entity, each written only where the entity has it; text is cut, keys come in a fixed order. */
    public static final class Details {
        private final JsonObject json = new JsonObject();

        public Details text(String key, @Nullable String value) {
            if (value != null && !value.isEmpty()) json.addProperty(key, cut(value));
            return this;
        }

        public Details flag(String key, @Nullable Boolean value) {
            if (value != null) json.addProperty(key, value);
            return this;
        }

        public Details number(String key, @Nullable Integer value) {
            if (value != null) json.addProperty(key, value);
            return this;
        }

        public JsonObject build() {
            return json.deepCopy();
        }

        private static String cut(String value) {
            String clean = ValueCodecs.sanitizeText(value);
            return clean.codePointCount(0, clean.length()) <= MAX_DETAIL_TEXT ? clean
                    : clean.substring(0, clean.offsetByCodePoints(0, MAX_DETAIL_TEXT - 1)) + "…";
        }
    }

    /**
     * What the observer saw of the nearest entity, as its state shows it.
     *
     * @param entity    the type id; empty for nothing
     * @param name      its name, as {@link #NAME} has it
     * @param living    whether its health is reported
     * @param distance  blocks from the face; 0 for a player or nothing
     * @param details   its details ({@link Details})
     */
    public record Seen(String entity, String name, boolean living, double health, double maxHealth, double distance, JsonObject details) {
        public static final Seen NOTHING = new Seen("", "", false, 0, 0, 0, new JsonObject());

        public Seen {
            // Custom names may carry formatting codes and control characters; the state shows clean text.
            entity = ValueCodecs.sanitizeText(entity);
            name = ValueCodecs.sanitizeText(name);
            details = details.deepCopy();
        }

        /**
         * A player in front: their type id, their name only when they share it ({@link #names}), else the player type's
         * name, and nothing else.
         *
         * @param typeName   the player type's name in the server's language
         * @param playerName the player's name
         */
        public static Seen player(PrivacyPolicy.Shared shared, String typeName, String playerName) {
            return new Seen(PLAYER, names(shared) ? playerName : typeName, false, 0, 0, 0, new JsonObject());
        }

        /** Any other entity; health is reported for a living one only. */
        public static Seen entity(String entity, String name, boolean living, double health, double maxHealth, double distance, JsonObject details) {
            return new Seen(entity, name, living, living ? health : 0, living ? maxHealth : 0, distance, details);
        }
    }

    /**
     * An observer's settings and what it saw last.
     *
     * @param count  how many entities it saw
     * @param looked whether it ever looked; not part of the state
     */
    public record Observation(Detect detect, int range, Payload payload, int count, String entity, String name, boolean living, double health,
                              double maxHealth, double distance, JsonObject details, boolean looked) {
        public static final Observation DEFAULT = new Observation(Detect.ALL, DEFAULT_RANGE, Payload.FULL, 0, "", "", false, 0, 0, 0, new JsonObject(), false);
        /** The observer in the block entity's {@code state} compound; unreadable values keep their default. */
        public static final Codec<Observation> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                DETECT.codec().lenientOptionalFieldOf(DETECT.key(), Detect.ALL).forGetter(Observation::detect),
                RANGE.codec().lenientOptionalFieldOf(RANGE.key(), DEFAULT_RANGE).forGetter(Observation::range),
                PAYLOAD.codec().lenientOptionalFieldOf(PAYLOAD.key(), Payload.FULL).forGetter(Observation::payload),
                COUNT.codec().lenientOptionalFieldOf(COUNT.key(), 0).forGetter(Observation::count),
                Codec.STRING.lenientOptionalFieldOf(ENTITY.key(), "").forGetter(Observation::entity),
                Codec.STRING.lenientOptionalFieldOf(NAME.key(), "").forGetter(Observation::name),
                Codec.BOOL.lenientOptionalFieldOf(LIVING.key(), false).forGetter(Observation::living),
                HEALTH.codec().lenientOptionalFieldOf(HEALTH.key(), 0.0).forGetter(Observation::health),
                MAX_HEALTH.codec().lenientOptionalFieldOf(MAX_HEALTH.key(), 0.0).forGetter(Observation::maxHealth),
                DISTANCE.codec().lenientOptionalFieldOf(DISTANCE.key(), 0.0).forGetter(Observation::distance),
                STORED_DETAILS.lenientOptionalFieldOf(DETAILS.key(), new JsonObject()).forGetter(Observation::details),
                Codec.BOOL.lenientOptionalFieldOf("looked", false).forGetter(Observation::looked)
        ).apply(instance, Observation::new));

        public Observation {
            range = Math.clamp(range, MIN_RANGE, MAX_RANGE);
            count = Math.max(0, count);
            // Rounded as the state shows them, so a look that changes nothing shown is no change.
            health = tenths(Math.clamp(health, 0, MAX_HEALTH_POINTS));
            maxHealth = tenths(Math.clamp(maxHealth, 0, MAX_HEALTH_POINTS));
            distance = tenths(Math.clamp(distance, 0, MAX_DISTANCE));
            details = details.deepCopy();
        }

        public boolean present() {
            return count > 0;
        }

        /** Whether the nearest entity is a player. */
        public boolean player() {
            return entity.equals(PLAYER);
        }

        /** The observer after a look that saw {@code newCount} entities, the nearest as {@code seen}. */
        public Observation looked(int newCount, Seen seen) {
            Seen nearest = newCount > 0 ? seen : Seen.NOTHING;
            return new Observation(detect, range, payload, newCount, nearest.entity(), nearest.name(), nearest.living(), nearest.health(),
                    nearest.maxHealth(), nearest.distance(), nearest.details(), true);
        }

        /** A placed copy (M2 plan D14): the settings stay, what it saw is gone until its first look. */
        public Observation placed() {
            return new Observation(detect, range, payload, 0, "", "", false, 0, 0, 0, new JsonObject(), false);
        }

        /**
         * The observer after a partial command: {@code detect}, {@code range} and {@code payload}.
         *
         * @return empty when the command has none of these keys in a readable form
         */
        public Optional<Observation> apply(CommandReader command) {
            Optional<Detect> newDetect = command.get(DETECT);
            Optional<Integer> newRange = command.get(RANGE);
            Optional<Payload> newPayload = command.get(PAYLOAD);
            if (newDetect.isEmpty() && newRange.isEmpty() && newPayload.isEmpty()) return Optional.empty();
            return Optional.of(new Observation(newDetect.orElse(detect), newRange.orElse(range), newPayload.orElse(payload), count, entity, name, living,
                    health, maxHealth, distance, details, looked));
        }

        /** Writes every readable field but the location. */
        public StateWriter write(StateWriter out) {
            return out.put(ENTITY, entity).put(PRESENT, present()).put(COUNT, count).put(NAME, name).put(LIVING, living).put(HEALTH, health)
                    .put(MAX_HEALTH, maxHealth).put(DISTANCE, distance).put(DETAILS, details).put(DETECT, detect).put(RANGE, range).put(PAYLOAD, payload);
        }
    }
}

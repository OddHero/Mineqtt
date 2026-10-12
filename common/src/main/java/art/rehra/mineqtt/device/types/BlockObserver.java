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
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The Block Observer ({@code block_sensor_block}, M4 plan D12): reports the block in front of its face, its state
 * properties and the comparator level it gives, and fires {@code changed} when that block changes. It took over from the
 * v1 Block Sensor, whose topic became its Raw Link output and whose data mode its {@code payload} setting. Its state is
 * published at most once a second, as sensors' is; {@code changed} at most twice a second.
 * <p>
 * It never loads the chunk of the block it watches. Block entity data (a chest's items, a sign's text) is never part of
 * its state, and reaches its Raw Link only in the {@code full} payload, only while the server allows it
 * ({@code blockObserver.exportBlockEntityData}) and only when the observer and the block share one claim
 * ({@link #exportsBlockEntity}), cut at {@value #MAX_BLOCK_ENTITY_BYTES} bytes.
 */
public final class BlockObserver {
    /** The event type of a change of the block in front. */
    public static final String CHANGED = "changed";
    /** The most a {@code full} payload carries of block entity data, as UTF-8 JSON. */
    public static final int MAX_BLOCK_ENTITY_BYTES = 16 * 1024;
    /** How often the block entity data of a {@code full} payload is sampled, in game ticks: v1's twice a second. */
    public static final int BLOCK_ENTITY_SAMPLE_TICKS = 10;

    /** What counts as a change of the block in front: another block, or any change of its state too. */
    public enum ChangeOn implements StringRepresentable {
        BLOCK,
        /** Any change of the block state; the default. */
        STATE;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What the Raw Link carries: v1's data modes. */
    public enum Payload implements StringRepresentable {
        /** Id, name, properties, position and dimension as JSON; v1's default, so first. */
        FULL("full", "full_data"),
        BLOCK_ID("block_id", "block_id"),
        NAME("name", "name"),
        PROPERTIES("properties", "properties");

        private final String name;
        private final String v1;

        Payload(String name, String v1) {
            this.name = name;
            this.v1 = v1;
        }

        @Override
        public String getSerializedName() {
            return name;
        }

        /** v1's data mode ({@code BlockSensorDataMode}); an unknown one is v1's fallback, the full data. */
        public static Payload ofV1(String mode) {
            for (Payload payload : values()) if (payload.v1.equals(mode)) return payload;
            return FULL;
        }

        /** The name v1 saved this mode under. */
        public String v1Name() {
            return v1;
        }
    }

    private static final Codec<Map<String, String>> PROPERTIES_CODEC = Codec.unboundedMap(Codec.STRING, Codec.STRING);

    /** The id of the block in front, e.g. {@code minecraft:chest}; empty before the first look. */
    public static final Field<String> BLOCK = Field.text("block", Access.READ);
    /** Its state properties, name to value, e.g. {@code {"facing":"north"}}; the Home Assistant sensor's attributes. */
    public static final Field<Map<String, String>> PROPERTIES = Field.json("properties", PROPERTIES_CODEC, Map.of(), Access.READ);
    /** The comparator level the block gives; 0 for a block that gives none. */
    public static final Field<Integer> SIGNAL = Field.level("signal", Access.READ);
    public static final Field<ChangeOn> CHANGE_ON = Field.enumOf("change_on", ChangeOn.class, ChangeOn.STATE, Access.READ_WRITE);
    public static final Field<Payload> PAYLOAD = Field.enumOf("payload", Payload.class, Access.READ_WRITE);
    /** A change, with the id of the block in front after it. */
    public static final EventSpec CHANGED_EVENT = EventSpec.of(CHANGED).attribute(BLOCK.key(), PortType.text(ValueCodecs.MAX_TEXT_LENGTH))
            .minInterval(Duration.ofMillis(500));

    public static final DeviceType TYPE = DeviceType.builder(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "block_observer"))
            .model("Block Observer").unloaded(UnloadedPolicy.UNAVAILABLE).minInterval(Duration.ofSeconds(1))
            .field(BLOCK).field(PROPERTIES).field(SIGNAL).field(CHANGE_ON).field(PAYLOAD).field(CommonFields.LOCATION)
            .setting(new Setting<>(CHANGE_ON)).setting(new Setting<>(PAYLOAD))
            .event(CHANGED_EVENT)
            .entity(Ha.sensor("block", BLOCK).name(null).jsonAttributes(PROPERTIES))
            .entity(Ha.sensor("signal", SIGNAL).stateClass("measurement"))
            .entity(Ha.event(CHANGED, CHANGED))
            .entity(Ha.select("change_on", CHANGE_ON).config())
            .entity(Ha.select("payload", PAYLOAD).config().disabledByDefault())
            .entity(Ha.sensor("location", CommonFields.LOCATION).name("Location").diagnostic().disabledByDefault())
            // v1 sent its data unretained at QoS 0; slots 2 and 3 were never payloads. An empty message clears a topic the
            // link leaves while it retains, as the observer has no "off" value.
            .rawLink(RawLinkSpec.out(custom -> "").withoutCustomPayloads())
            .build();

    private BlockObserver() {
    }

    /** The id of a block state's block, e.g. {@code minecraft:stone}. */
    public static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /** A block state's properties, name to value, in the order the block declares them. */
    public static Map<String, String> properties(BlockState state) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (Property<?> property : state.getProperties()) properties.put(property.getName(), propertyValue(state, property));
        return Collections.unmodifiableMap(properties);
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /**
     * Whether a {@code full} payload carries the block entity data of the block in front (D12): only while the server
     * allows it and the observer and its target share one claim, which they always do without a claim mod.
     *
     * @param allowed   {@code blockObserver.exportBlockEntityData}
     * @param sameClaim whether the observer and its target share one claim
     */
    public static boolean exportsBlockEntity(boolean allowed, boolean sameClaim) {
        return allowed && sameClaim;
    }

    /**
     * Whether a look at the block in front is a change: another block, or with {@link ChangeOn#STATE} any other state.
     *
     * @param before the block state of the last look; null before the first one
     */
    public static boolean changed(ChangeOn changeOn, @Nullable BlockState before, BlockState after) {
        if (before == null) return true;
        return changeOn == ChangeOn.BLOCK ? !after.is(before.getBlock()) : before != after;
    }

    /**
     * Whether the first look after the device attached is a change against what it saw before it unloaded: another block
     * id, or with {@link ChangeOn#STATE} other properties too.
     */
    public static boolean changedSince(ChangeOn changeOn, String block, Map<String, String> properties, BlockState now) {
        if (!block.equals(blockId(now))) return true;
        return changeOn == ChangeOn.STATE && !properties.equals(properties(now));
    }

    /** The {@code changed} event: {@code {"event_type":"changed","block":"minecraft:chest"}}. */
    public static JsonObject changedEvent(String block) {
        JsonObject event = CHANGED_EVENT.payload();
        event.addProperty(BLOCK.key(), block);
        return event;
    }

    /**
     * What the Raw Link carries for the block in front, as v1's data modes: the id, the name in the server's language,
     * the properties as JSON, or the full JSON with id, name, properties, position, dimension and {@code block_entity}.
     * That is null unless {@code entityData} is given ({@link #exportsBlockEntity}); data beyond
     * {@value #MAX_BLOCK_ENTITY_BYTES} bytes is left out too, and {@code "truncated":true} says so.
     *
     * @param entityData the target's block entity data, when it may be exported; null otherwise
     */
    public static String payload(Payload mode, BlockState target, BlockPos targetPos, String dimension, @Nullable CompoundTag entityData) {
        String id = blockId(target);
        String name = target.getBlock().getName().getString();
        if (mode == Payload.BLOCK_ID) return id;
        if (mode == Payload.NAME) return name;
        JsonObject properties = PROPERTIES_CODEC.encodeStart(JsonOps.INSTANCE, properties(target)).getOrThrow().getAsJsonObject();
        if (mode == Payload.PROPERTIES) return properties.toString();

        JsonObject data = new JsonObject();
        data.addProperty("id", id);
        data.addProperty("name", name);
        data.add("properties", properties);
        JsonObject position = new JsonObject();
        position.addProperty("x", targetPos.getX());
        position.addProperty("y", targetPos.getY());
        position.addProperty("z", targetPos.getZ());
        data.add("position", position);
        data.addProperty("dimension", dimension);
        JsonElement entity = entityData == null ? JsonNull.INSTANCE : NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, entityData);
        if (entity.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BLOCK_ENTITY_BYTES) {
            data.add("block_entity", JsonNull.INSTANCE);
            data.addProperty("truncated", true);
        } else {
            data.add("block_entity", entity);
        }
        return data.toString();
    }

    /**
     * An observer's settings and what it saw last.
     *
     * @param block      the id of the block in front at the last look; empty before the first
     * @param properties its properties then
     * @param signal     the comparator level it gave then
     */
    public record Observation(ChangeOn changeOn, Payload payload, String block, Map<String, String> properties, int signal) {
        public static final Observation DEFAULT = new Observation(ChangeOn.STATE, Payload.FULL, "", Map.of(), 0);
        /** The observer in the block entity's {@code state} compound; unreadable values keep their default. */
        public static final Codec<Observation> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                CHANGE_ON.codec().lenientOptionalFieldOf(CHANGE_ON.key(), ChangeOn.STATE).forGetter(Observation::changeOn),
                PAYLOAD.codec().lenientOptionalFieldOf(PAYLOAD.key(), Payload.FULL).forGetter(Observation::payload),
                Codec.STRING.lenientOptionalFieldOf(BLOCK.key(), "").forGetter(Observation::block),
                PROPERTIES_CODEC.lenientOptionalFieldOf(PROPERTIES.key(), Map.of()).forGetter(Observation::properties),
                SIGNAL.codec().lenientOptionalFieldOf(SIGNAL.key(), 0).forGetter(Observation::signal)
        ).apply(instance, Observation::new));

        public Observation {
            properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
            signal = Math.clamp(signal, 0, PortType.MAX_LEVEL);
        }

        /** A v1 Block Sensor: its data mode is the payload; it counted any change of the state. */
        public static Observation ofV1(Payload payload) {
            return new Observation(ChangeOn.STATE, payload, "", Map.of(), 0);
        }

        /** The observer after a look at {@code target}, which gave {@code newSignal}. */
        public Observation looked(BlockState target, int newSignal) {
            return new Observation(changeOn, payload, blockId(target), BlockObserver.properties(target), newSignal);
        }

        /** A placed copy (M2 plan D14): the settings stay, what it saw is gone until its first look. */
        public Observation placed() {
            return new Observation(changeOn, payload, "", Map.of(), 0);
        }

        /**
         * The observer after a partial command: {@code change_on} and {@code payload}.
         *
         * @return empty when the command has none of these keys in a readable form
         */
        public Optional<Observation> apply(CommandReader command) {
            Optional<ChangeOn> newChangeOn = command.get(CHANGE_ON);
            Optional<Payload> newPayload = command.get(PAYLOAD);
            if (newChangeOn.isEmpty() && newPayload.isEmpty()) return Optional.empty();
            return Optional.of(new Observation(newChangeOn.orElse(changeOn), newPayload.orElse(payload), block, properties, signal));
        }

        /** Writes every readable field but the location. */
        public StateWriter write(StateWriter out) {
            return out.put(BLOCK, block).put(PROPERTIES, properties).put(SIGNAL, signal).put(CHANGE_ON, changeOn).put(PAYLOAD, payload);
        }
    }
}

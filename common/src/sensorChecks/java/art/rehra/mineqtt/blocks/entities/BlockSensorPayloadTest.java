package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.DeviceTraffic;
import art.rehra.mineqtt.device.Port;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.BlockObserver;
import art.rehra.mineqtt.device.types.BlockObserver.ChangeOn;
import art.rehra.mineqtt.device.types.BlockObserver.Observation;
import art.rehra.mineqtt.device.types.BlockObserver.Payload;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dependency-free regression checks of the Block Observer (M4 plan D12), run by Gradle's verifyBlockSensor task: the Raw
 * Link payload of each {@code payload} setting, block entity data only under the server's flag and the same claim and
 * never beyond 16 KiB, the state with its comparator level, the {@code changed} event and its throttle, what
 * {@code change_on} counts, and v1's data modes.
 */
public final class BlockSensorPayloadTest {
    private static final BlockPos POS = new BlockPos(-5, 64, 12);

    static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        payloads();
        blockEntityData();
        state();
        changes();
        throttle();
        v1Modes();
        System.out.println("Block Observer checks passed.");
    }

    /** Each payload setting gives v1's shape of the same data mode. */
    private static void payloads() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        check(payload(Payload.BLOCK_ID, stone, null).equals("minecraft:stone"), "block_id: the registry id");
        check(payload(Payload.NAME, stone, null).equals(Blocks.STONE.getName().getString()), "name: the display name");
        check(payload(Payload.PROPERTIES, stone, null).equals("{}"), "properties: a block without properties");
        check(payload(Payload.BLOCK_ID, Blocks.AIR.defaultBlockState(), null).equals("minecraft:air"), "A removed block is air");

        BlockState observer = Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.POWERED, true);
        JsonObject properties = json(payload(Payload.PROPERTIES, observer, null));
        check(properties.get("powered").getAsString().equals("true"), "A boolean state value");
        check(properties.get("facing").getAsString().equals(observer.getValue(ObserverBlock.FACING).getName()), "A direction state value");
        check(!properties.has("id"), "properties carries nothing else");
        check(properties.keySet().stream().toList().equals(observer.getProperties().stream().map(property -> property.getName()).toList()),
                "Properties in the order the block declares them: " + properties);

        JsonObject full = json(payload(Payload.FULL, stone, null));
        check(full.get("id").getAsString().equals("minecraft:stone") && full.get("name").getAsString().equals(Blocks.STONE.getName().getString()),
                "full: id and name");
        check(full.getAsJsonObject("position").get("x").getAsInt() == -5 && full.getAsJsonObject("position").get("y").getAsInt() == 64
                && full.getAsJsonObject("position").get("z").getAsInt() == 12, "full: the position, negative coordinates included");
        check(full.get("dimension").getAsString().equals("minecraft:overworld") && full.getAsJsonObject("properties").isEmpty(), "full: dimension and properties");
        check(full.has("block_entity") && full.get("block_entity").isJsonNull() && !full.has("truncated"),
                "full keeps v1's shape: block_entity is null without data: " + full);
        check(full.keySet().stream().toList().equals(List.of("id", "name", "properties", "position", "dimension", "block_entity")),
                "full has v1's keys in v1's order: " + full.keySet());
        check(Payload.FULL == BlockObserver.PAYLOAD.defaultValue() && ChangeOn.STATE == BlockObserver.CHANGE_ON.defaultValue(),
                "A new observer sends the full data and counts every state change, as v1 did");
    }

    /**
     * D12: a chest's items, a sign's text are no business of the observer's state; the full payload carries them only
     * while the server allows it and the observer and the block share one claim, and never more than 16 KiB of it.
     */
    private static void blockEntityData() {
        check(!BlockObserver.exportsBlockEntity(false, true) && !BlockObserver.exportsBlockEntity(false, false)
                && !BlockObserver.exportsBlockEntity(true, false) && BlockObserver.exportsBlockEntity(true, true),
                "Block entity data only with blockObserver.exportBlockEntityData and the same claim");

        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.putString("CustomName", "Quoted \"name\" with \nnewline");
        chest.putByteArray("bytes", new byte[]{1, -2, 3});
        chest.putIntArray("ints", new int[]{1, 2, 3});
        chest.putLongArray("longs", new long[]{4L, 5L});
        CompoundTag item = new CompoundTag();
        item.putString("id", "minecraft:diamond");
        item.putInt("count", 7);
        ListTag items = new ListTag();
        items.add(item);
        chest.put("Items", items);
        BlockState chestState = Blocks.CHEST.defaultBlockState();
        JsonObject withoutFlag = json(payload(Payload.FULL, chestState, null));
        check(withoutFlag.get("block_entity").isJsonNull(), "By default a chest's full payload has no block entity data: " + withoutFlag);
        JsonObject entity = json(payload(Payload.FULL, chestState, chest)).getAsJsonObject("block_entity");
        check(entity.get("CustomName").getAsString().equals("Quoted \"name\" with \nnewline"), "JSON escaping");
        check(entity.getAsJsonArray("Items").get(0).getAsJsonObject().get("count").getAsInt() == 7, "Nested inventory data");
        check(entity.getAsJsonArray("bytes").get(1).getAsInt() == -2 && entity.getAsJsonArray("ints").size() == 3
                && entity.getAsJsonArray("longs").get(1).getAsLong() == 5L, "Array conversion");
        for (Payload other : new Payload[]{Payload.BLOCK_ID, Payload.NAME, Payload.PROPERTIES}) {
            check(!payload(other, chestState, chest).contains("diamond"), other + " never carries block entity data");
        }

        CompoundTag fits = new CompoundTag();
        fits.putString("text", "x".repeat(BlockObserver.MAX_BLOCK_ENTITY_BYTES - "{\"text\":\"\"}".length()));
        JsonObject atCap = json(payload(Payload.FULL, chestState, fits));
        check(atCap.get("block_entity").isJsonObject() && !atCap.has("truncated"), "Data of exactly 16 KiB is sent whole");
        CompoundTag tooBig = new CompoundTag();
        tooBig.putString("text", "x".repeat(BlockObserver.MAX_BLOCK_ENTITY_BYTES - "{\"text\":\"\"}".length() + 1));
        JsonObject cut = json(payload(Payload.FULL, chestState, tooBig));
        check(cut.get("block_entity").isJsonNull() && cut.get("truncated").getAsBoolean(), "One byte more is cut: block_entity null, truncated true");
        CompoundTag multibyte = new CompoundTag();
        multibyte.putString("text", "é".repeat(BlockObserver.MAX_BLOCK_ENTITY_BYTES / 2));
        check(json(payload(Payload.FULL, chestState, multibyte)).has("truncated"), "The cut counts UTF-8 bytes, not characters");
    }

    /** The state: id, properties as the sensor's attributes, the comparator level clamped to 0-15, the settings. */
    private static void state() {
        BlockState wire = Blocks.REDSTONE_WIRE.defaultBlockState().setValue(RedStoneWireBlock.POWER, 9);
        Observation looked = Observation.DEFAULT.looked(wire, 4);
        JsonObject state = looked.write(new StateWriter()).json();
        check(state.get("block").getAsString().equals("minecraft:redstone_wire") && state.get("signal").getAsInt() == 4
                && state.getAsJsonObject("properties").get("power").getAsString().equals("9")
                && state.get("change_on").getAsString().equals("state") && state.get("payload").getAsString().equals("full"),
                "The state names the block, its properties, its comparator level and the settings: " + state);
        check(state.getAsJsonObject("properties").keySet().size() == wire.getProperties().size(), "Every property is an attribute");
        check(Observation.DEFAULT.looked(wire, 99).signal() == 15 && Observation.DEFAULT.looked(wire, -3).signal() == 0, "The level is clamped to 0-15");
        check(BlockObserver.TYPE.minInterval().toMillis() == 1000, "Its state is published at most once a second, as sensors'");

        Optional<Observation> set = looked.apply(command("{\"payload\":\"block_id\",\"change_on\":\"BLOCK\"}"));
        check(set.isPresent() && set.get().payload() == Payload.BLOCK_ID && set.get().changeOn() == ChangeOn.BLOCK && set.get().block().equals(looked.block()),
                "Both settings at once; what it saw stays: " + set);
        check(looked.apply(command("{\"payload\":\"everything\"}")).isEmpty() && looked.apply(command("{\"block\":\"minecraft:tnt\"}")).isEmpty()
                && looked.apply(command("{\"signal\":15}")).isEmpty(), "Readings and unknown values are no commands");
        check(looked.placed().equals(new Observation(ChangeOn.STATE, Payload.FULL, "", Map.of(), 0)), "A placed copy keeps its settings and has seen nothing");

        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        for (Observation observation : new Observation[]{Observation.DEFAULT, looked, set.orElseThrow(), looked.placed()}) {
            TagValueOutput nbt = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
            nbt.store("state", Observation.CODEC, observation);
            Optional<Observation> read = TagValueInput.create(ProblemReporter.DISCARDING, registries, nbt.buildResult()).read("state", Observation.CODEC);
            check(read.equals(Optional.of(observation)), "The observer round-trips through NBT: " + observation + " -> " + read);
        }
        CompoundTag garbage = new CompoundTag();
        CompoundTag garbageState = new CompoundTag();
        garbageState.putString("payload", "everything");
        garbageState.putString("change_on", "block");
        garbageState.putInt("signal", 99);
        garbage.put("state", garbageState);
        check(TagValueInput.create(ProblemReporter.DISCARDING, registries, garbage).read("state", Observation.CODEC)
                        .equals(Optional.of(new Observation(ChangeOn.BLOCK, Payload.FULL, "", Map.of(), 15))),
                "An unreadable value keeps its default, the rest is read");
    }

    /** change_on: another block, or any change of its state; the first look after an attach compares with the saved one. */
    private static void changes() {
        BlockState wire0 = Blocks.REDSTONE_WIRE.defaultBlockState();
        BlockState wire9 = wire0.setValue(RedStoneWireBlock.POWER, 9);
        BlockState stone = Blocks.STONE.defaultBlockState();
        check(BlockObserver.changed(ChangeOn.STATE, wire0, wire9) && !BlockObserver.changed(ChangeOn.BLOCK, wire0, wire9),
                "A wire's power is a change of its state, not another block");
        check(BlockObserver.changed(ChangeOn.STATE, wire0, stone) && BlockObserver.changed(ChangeOn.BLOCK, wire0, stone), "Another block is a change for both");
        check(!BlockObserver.changed(ChangeOn.STATE, wire9, wire9) && !BlockObserver.changed(ChangeOn.BLOCK, wire9, wire9), "The same state is no change");
        check(BlockObserver.changed(ChangeOn.BLOCK, null, stone), "Without a look before, anything is new");

        Observation saved = Observation.DEFAULT.looked(wire0, 0);
        check(!BlockObserver.changedSince(ChangeOn.STATE, saved.block(), saved.properties(), wire0), "The block it saw before it unloaded is no change");
        check(BlockObserver.changedSince(ChangeOn.STATE, saved.block(), saved.properties(), wire9)
                && !BlockObserver.changedSince(ChangeOn.BLOCK, saved.block(), saved.properties(), wire9), "Other properties count only for state");
        check(BlockObserver.changedSince(ChangeOn.BLOCK, saved.block(), saved.properties(), stone), "Another block counts for block");

        JsonObject event = BlockObserver.changedEvent("minecraft:stone");
        check(event.equals(JsonParser.parseString("{\"event_type\":\"changed\",\"block\":\"minecraft:stone\"}")), "The changed event: " + event);
        check(BlockObserver.TYPE.eventProblem(event).isEmpty(), "The type declares it");
        check(BlockObserver.TYPE.port(Port.Direction.OUT, "changed").isPresent() && BlockObserver.TYPE.port(Port.Direction.OUT, "signal").isPresent()
                && BlockObserver.TYPE.port(Port.Direction.IN, "payload").isPresent(), "Its ports");
    }

    /** D12, D3: a flickering wire in front gives at most two changed events a second on the action topic. */
    private static void throttle() {
        check(BlockObserver.CHANGED_EVENT.minInterval().toMillis() == 500 && !BlockObserver.CHANGED_EVENT.busOnly(), "changed: at most every 500 ms");
        DeviceTraffic traffic = new DeviceTraffic();
        int published = 0;
        // A wire switched every other tick for a second.
        for (long millis = 0; millis < 1000; millis += 100) {
            if (traffic.admitEvent(BlockObserver.CHANGED_EVENT, millis)) published++;
        }
        check(published == 2 && traffic.throttled() == 8, "A wire flickering for a second gives two changed events, eight held back: " + published);
    }

    /** v1's data modes are the payload setting; an old or unknown mode is the full data, as in v1. */
    private static void v1Modes() {
        check(Payload.ofV1("full_data") == Payload.FULL && Payload.ofV1("block_id") == Payload.BLOCK_ID && Payload.ofV1("name") == Payload.NAME
                && Payload.ofV1("properties") == Payload.PROPERTIES, "v1's four modes");
        check(Payload.ofV1("old_or_unknown") == Payload.FULL && Payload.ofV1("") == Payload.FULL, "An old save falls back to the full data");
        for (Payload payload : Payload.values()) check(Payload.ofV1(payload.v1Name()) == payload, "Saved mode round trip: " + payload);
        check(Observation.ofV1(Payload.NAME).equals(new Observation(ChangeOn.STATE, Payload.NAME, "", Map.of(), 0)), "A v1 sensor counts every state change");
    }

    private static String payload(Payload mode, BlockState state, CompoundTag entity) {
        return BlockObserver.payload(mode, state, POS, "minecraft:overworld", entity);
    }

    private static CommandReader command(String json) {
        return new CommandReader(JsonParser.parseString(json).getAsJsonObject());
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}

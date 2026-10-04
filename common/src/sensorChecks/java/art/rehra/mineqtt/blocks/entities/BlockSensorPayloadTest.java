package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.blocks.entities.BlockSensorBlockEntity.DataMode;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Dependency-free regression checks, run by Gradle's verifyBlockSensor task.
 */
public final class BlockSensorPayloadTest {
    static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BlockPos pos = new BlockPos(-5, 64, 12);
        BlockState stone = Blocks.STONE.defaultBlockState();
        check(payload(stone, pos, DataMode.BLOCK_ID, null).equals("minecraft:stone"), "Registry ID");
        check(payload(stone, pos, DataMode.NAME, null).equals(Blocks.STONE.getName().getString()), "Display name");
        check(payload(stone, pos, DataMode.PROPERTIES, null).equals("{}"), "Block without properties");
        check(payload(Blocks.AIR.defaultBlockState(), pos, DataMode.BLOCK_ID, null).equals("minecraft:air"), "Removed block");

        BlockState observer = Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.POWERED, true);
        JsonObject properties = json(payload(observer, pos, DataMode.PROPERTIES, null));
        check(properties.get("powered").getAsString().equals("true"), "Boolean state value");
        check(properties.get("facing").getAsString().equals(observer.getValue(ObserverBlock.FACING).getName()), "Direction state value");
        check(!properties.has("id"), "Properties mode has no extra fields");

        JsonObject full = json(payload(stone, pos, DataMode.FULL_DATA, null));
        check(full.get("id").getAsString().equals("minecraft:stone"), "Full data ID");
        check(full.get("name").getAsString().equals(Blocks.STONE.getName().getString()), "Full data name");
        check(full.getAsJsonObject("position").get("x").getAsInt() == -5, "Negative coordinate");
        check(full.getAsJsonObject("position").get("y").getAsInt() == 64, "Y coordinate");
        check(full.getAsJsonObject("position").get("z").getAsInt() == 12, "Z coordinate");
        check(full.get("dimension").getAsString().equals("minecraft:overworld"), "Dimension");
        check(full.get("block_entity").isJsonNull(), "Absent block entity");

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
        JsonObject entity = json(payload(Blocks.CHEST.defaultBlockState(), pos, DataMode.FULL_DATA, chest)).getAsJsonObject("block_entity");
        check(entity.get("CustomName").getAsString().equals("Quoted \"name\" with \nnewline"), "JSON escaping");
        check(entity.getAsJsonArray("Items").get(0).getAsJsonObject().get("count").getAsInt() == 7, "Nested inventory data");
        check(entity.getAsJsonArray("bytes").get(1).getAsInt() == -2, "Byte array conversion");
        check(entity.getAsJsonArray("ints").size() == 3, "Int array conversion");
        check(entity.getAsJsonArray("longs").get(1).getAsLong() == 5L, "Long array conversion");

        for (DataMode mode : DataMode.values()) check(DataMode.fromId(mode.id()) == mode, "Saved mode round trip");
        check(DataMode.fromId("old_or_unknown") == DataMode.FULL_DATA, "Old save fallback");
        System.out.println("Block Sensor payload regression checks passed.");
    }

    private static String payload(BlockState state, BlockPos pos, DataMode mode, CompoundTag entity) {
        return BlockSensorBlockEntity.createPayload(state, pos, mode, "minecraft:overworld", entity);
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
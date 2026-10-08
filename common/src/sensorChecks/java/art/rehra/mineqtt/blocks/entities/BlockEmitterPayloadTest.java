package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.blocks.entities.BlockEmitterBlockEntity.ParsedPayload;
import art.rehra.mineqtt.blocks.entities.BlockSensorBlockEntity.DataMode;
import art.rehra.mineqtt.config.MineQTTConfig;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Dependency-free regression checks, run by Gradle's verifyBlockEmitter task. They enable the block placer through the
 * {@link MineQTTConfig} static first.
 */
public final class BlockEmitterPayloadTest {

    static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        // The block placer is off by default (design §8.3) and then decodes nothing; the checks below need it on.
        check(!MineQTTConfig.blockPlacerEnabled, "The block placer is disabled by default");
        check(BlockEmitterBlockEntity.parsePayload("minecraft:stone", null) == null, "A disabled block placer decodes nothing");
        MineQTTConfig.blockPlacerEnabled = true;

        BlockPos pos = new BlockPos(-5, 64, 12);
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();

        // 1. Registry ID / String payload tests
        ParsedPayload p1 = BlockEmitterBlockEntity.parsePayload("minecraft:stone", null);
        check(p1 != null && p1.newState().is(Blocks.STONE), "Registry ID: minecraft:stone");

        ParsedPayload p2 = BlockEmitterBlockEntity.parsePayload("minecraft:air", null);
        check(p2 != null && p2.newState().is(Blocks.AIR), "Registry ID: minecraft:air");

        ParsedPayload p3 = BlockEmitterBlockEntity.parsePayload("stone", null);
        check(p3 != null && p3.newState().is(Blocks.STONE), "Short ID: stone");

        ParsedPayload p4 = BlockEmitterBlockEntity.parsePayload(Blocks.STONE.getName().getString(), null);
        check(p4 != null && p4.newState().is(Blocks.STONE), "Display name: Stone");

        ParsedPayload p5 = BlockEmitterBlockEntity.parsePayload(Blocks.OAK_PLANKS.getName().getString(), null);
        check(p5 != null && p5.newState().is(Blocks.OAK_PLANKS), "Display name: Oak Planks");

        ParsedPayload p6 = BlockEmitterBlockEntity.parsePayload("\"minecraft:stone\"", null);
        check(p6 != null && p6.newState().is(Blocks.STONE), "Quoted string: \"minecraft:stone\"");

        // 2. Properties JSON payload tests
        BlockState observerDefault = Blocks.OBSERVER.defaultBlockState();
        ParsedPayload p7 = BlockEmitterBlockEntity.parsePayload("{\"facing\":\"south\",\"powered\":\"true\"}", observerDefault);
        check(p7 != null, "Properties JSON parsed");
        check(p7.newState().getValue(ObserverBlock.FACING) == Direction.SOUTH, "Observer facing updated to south");
        check(p7.newState().getValue(ObserverBlock.POWERED), "Observer powered updated to true");
        check(!p7.isFullData(), "Properties mode is not full data");

        ParsedPayload p8 = BlockEmitterBlockEntity.parsePayload("{}", stone);
        check(p8 != null && p8.newState() == stone, "Empty properties JSON keeps state");

        BlockState chestDefault = Blocks.CHEST.defaultBlockState();
        ParsedPayload p9 = BlockEmitterBlockEntity.parsePayload("{\"waterlogged\":\"true\"}", chestDefault);
        check(p9 != null && p9.newState().getValue(ChestBlock.WATERLOGGED), "Chest waterlogged property updated");

        // 3. Full Data JSON payload tests
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

        BlockState chestFacingSouth = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH);
        String fullDataPayload = BlockSensorBlockEntity.createPayload(chestFacingSouth, pos, DataMode.FULL_DATA, "minecraft:overworld", chest);

        ParsedPayload p10 = BlockEmitterBlockEntity.parsePayload(fullDataPayload, null);
        check(p10 != null, "Full data payload parsed");
        check(p10.isFullData(), "Full data flag is true");
        check(p10.newState().is(Blocks.CHEST), "Full data block type is Chest");
        check(p10.newState().getValue(ChestBlock.FACING) == Direction.SOUTH, "Full data property facing preserved");
        check(p10.blockEntityData() != null, "Full data block entity present");
        check(p10.blockEntityData().getString("CustomName").orElse("").equals("Quoted \"name\" with \nnewline") || p10.blockEntityData().getStringOr("CustomName", "").equals("Quoted \"name\" with \nnewline") || "Quoted \"name\" with \nnewline".equals(p10.blockEntityData().getString("CustomName").toString()), "Full data CustomName roundtrip");

        Tag itemsTag = p10.blockEntityData().get("Items");
        check(itemsTag instanceof ListTag, "Items is ListTag");
        ListTag itemsList = (ListTag) itemsTag;
        check(itemsList.size() == 1, "Items size is 1");
        Tag firstItemTag = itemsList.get(0);
        check(firstItemTag instanceof CompoundTag, "First item is CompoundTag");
        CompoundTag firstItem = (CompoundTag) firstItemTag;
        check(firstItem.getString("id").map(s -> s.equals("minecraft:diamond")).orElse(false) || "minecraft:diamond".equals(firstItem.getStringOr("id", "")), "Full data item ID roundtrip");
        check(firstItem.getInt("count").map(c -> c == 7).orElse(false) || firstItem.getIntOr("count", 0) == 7, "Full data item count roundtrip");

        Tag bytesTag = p10.blockEntityData().get("bytes");
        check(bytesTag instanceof ListTag && ((ListTag) bytesTag).size() == 3, "Full data bytes list size is 3");
        Tag intsTag = p10.blockEntityData().get("ints");
        check(intsTag instanceof ListTag && ((ListTag) intsTag).size() == 3, "Full data ints list size is 3");
        Tag longsTag = p10.blockEntityData().get("longs");
        check(longsTag instanceof ListTag && ((ListTag) longsTag).size() == 2, "Full data longs list size is 2");

        String stoneFullPayload = BlockSensorBlockEntity.createPayload(stone, pos, DataMode.FULL_DATA, "minecraft:overworld", null);
        ParsedPayload p11 = BlockEmitterBlockEntity.parsePayload(stoneFullPayload, null);
        check(p11 != null && p11.newState().is(Blocks.STONE), "Full data stone block");
        check(p11.blockEntityData() == null, "Full data stone has null block entity");
        check(p11.isFullData(), "Full data stone flag is true");

        // 4. Edge cases & error handling tests
        check(BlockEmitterBlockEntity.parsePayload(null, null) == null, "Null payload returns null");
        check(BlockEmitterBlockEntity.parsePayload("", null) == null, "Empty payload returns null");
        check(BlockEmitterBlockEntity.parsePayload("   ", null) == null, "Whitespace payload returns null");
        check(BlockEmitterBlockEntity.parsePayload("minecraft:non_existent_block_xyz", null) == null, "Unknown block ID returns null");
        check(BlockEmitterBlockEntity.parsePayload("{\"id\":\"minecraft:non_existent_block_xyz\"}", null) == null, "Unknown block ID in JSON returns null");
        check(BlockEmitterBlockEntity.parsePayload("{malformed_json:", null) == null, "Malformed JSON returns null");

        ParsedPayload p12 = BlockEmitterBlockEntity.parsePayload("{\"unknown_prop_123\":\"val\"}", observerDefault);
        check(p12 != null && p12.newState() == observerDefault, "Unknown property ignored safely");

        System.out.println("Block Emitter payload regression checks passed.");
    }

    private static void check(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }
}

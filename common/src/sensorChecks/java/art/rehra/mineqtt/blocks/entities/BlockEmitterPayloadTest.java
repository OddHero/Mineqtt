package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.PlacerRules;
import art.rehra.mineqtt.device.RawLinkSpec;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.device.types.BlockPlacer;
import art.rehra.mineqtt.security.TokenBucket;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dependency-free regression checks of the Block Placer (M4 plan D12), run by Gradle's verifyBlockEmitter task: which
 * blocks and properties it takes, the denies no datapack lifts, that block entity data and property-only edits are
 * refused, the replaceable and own-block rules, four actions a second, taking and returning the item, the switch, the
 * claim and spawn protection rules, its Raw Link grammar and the shipped allowlist.
 */
public final class BlockEmitterPayloadTest {
    private static final Set<Block> PLACEABLE = new HashSet<>();

    static void main(String[] args) throws IOException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Item stacks need their items' default components, which a server binds when it loads its data packs.
        for (Item item : List.of(Items.WOOL.white(), Items.GLASS, Items.STONE)) item.builtInRegistryHolder().bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
        PLACEABLE.addAll(resolveTag("mineqtt", "placeable"));
        allowlist();
        accepted();
        hardDenies();
        noNbt();
        rules();
        rate();
        material();
        grammar();
        switchAndType();
        System.out.println("Block Placer checks passed.");
    }

    /** The shipped #mineqtt:placeable: wool, concrete, terracotta, glass, the stained glass, the lamp and the sea lantern. */
    private static void allowlist() {
        check(PLACEABLE.size() == 16 + 16 + 17 + 1 + 16 + 2, "68 placeable blocks: " + PLACEABLE.size());
        for (Block block : PLACEABLE) check(PlacerRules.hardDenial(block).isEmpty(), "No shipped placeable block is denied by the code: " + block);
        check(PLACEABLE.contains(Blocks.WOOL.white()) && PLACEABLE.contains(Blocks.CONCRETE.black()) && PLACEABLE.contains(Blocks.TERRACOTTA)
                && PLACEABLE.contains(Blocks.GLASS) && PLACEABLE.contains(Blocks.STAINED_GLASS.lime()) && PLACEABLE.contains(Blocks.REDSTONE_LAMP)
                && PLACEABLE.contains(Blocks.SEA_LANTERN), "The blocks D12 names");
        check(!PLACEABLE.contains(Blocks.BARRIER) && !PLACEABLE.contains(Blocks.REDSTONE_BLOCK) && !PLACEABLE.contains(Blocks.TNT)
                && !PLACEABLE.contains(Blocks.GLASS_PANE), "No barrier (#minecraft:impermeable is not used), no power source, no TNT");
        List<Holder<Block>> tag = new ArrayList<>(PLACEABLE.stream().map(Block::builtInRegistryHolder).toList());
        tag.add(Blocks.TNT.builtInRegistryHolder());
        tag.add(Blocks.CHEST.builtInRegistryHolder());
        List<String> options = PlacerRules.options(tag);
        check(options.size() == PLACEABLE.size() && !options.contains("minecraft:tnt") && !options.contains("minecraft:chest")
                && options.equals(options.stream().sorted().toList()) && options.contains("minecraft:white_wool"),
                "Home Assistant is offered the tag sorted, without what a datapack added that the code denies: " + options.size());
    }

    /** Ids with and without namespace and properties; the canonical text keeps only what was given. */
    private static void accepted() {
        PlacerRules.Request wool = allowed("minecraft:white_wool").getOrThrow();
        check(wool.state() == Blocks.WOOL.white().defaultBlockState() && wool.text().equals("minecraft:white_wool") && !wool.givesProperties(), "An id");
        check(allowed("white_wool").getOrThrow().text().equals("minecraft:white_wool"), "Without a namespace, minecraft's");
        check(allowed("\"minecraft:glass\"").getOrThrow().state().is(Blocks.GLASS), "One pair of quotes is allowed");
        PlacerRules.Request lit = allowed("minecraft:redstone_lamp[lit=true]").getOrThrow();
        check(lit.state().getValue(RedstoneLampBlock.LIT) && lit.text().equals("minecraft:redstone_lamp[lit=true]") && lit.givesProperties(),
                "Properties are taken and kept as given: " + lit.text());
        PlacerRules.Request json = allowed("{\"id\":\"minecraft:redstone_lamp\",\"properties\":{\"lit\":true}}").getOrThrow();
        check(json.state() == lit.state() && json.text().equals(lit.text()), "The JSON form is the same block: " + json.text());
        check(allowed("{\"id\":\"minecraft:black_concrete\"}").getOrThrow().state().is(Blocks.CONCRETE.black()), "The JSON form without properties");
        refused("minecraft:redstone_lamp[color=red]", "not a block", "A property the block does not have");
        refused("minecraft:redstone_lamp[lit=maybe]", "not a block", "A value the property does not have");
        refused("minecraft:oak_planks", "not on the #mineqtt:placeable list", "A block outside the tag");
        refused("#minecraft:wool", "not a block", "A tag is no block");
        refused("minecraft:white_wool please", "text after it", "Nothing may follow the block");
        refused("minecraft:nothing_like_it", "not a block", "An unknown block");
        refused("", "no block given", "Nothing");
        check(PlacerRules.parse("Stone", BuiltInRegistries.BLOCK).error().isPresent(), "v1's display names are no ids any more");
    }

    /** D12: a datapack may add anything to the tag; the code still refuses these. */
    private static void hardDenies() {
        for (Block denied : List.of(Blocks.TNT, Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK, Blocks.STRUCTURE_BLOCK, Blocks.JIGSAW, Blocks.BARRIER,
                Blocks.SPAWNER, Blocks.BEDROCK, Blocks.CHEST, Blocks.WATER, Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.AIR, Blocks.NETHER_PORTAL,
                Blocks.END_PORTAL, Blocks.END_GATEWAY, Blocks.LIGHT)) {
            Optional<String> denial = PlacerRules.check(denied.defaultBlockState(), block -> true);
            check(denial.isPresent(), "Denied whatever the tag says: " + denied);
        }
        check(PlacerRules.check(Blocks.CHEST.defaultBlockState(), block -> true).orElseThrow().contains("block entity"),
                "A chest a datapack tags is refused for its block entity");
        check(PlacerRules.check(Blocks.WOOL.white().defaultBlockState(), PLACEABLE::contains).isEmpty(), "Wool passes");
    }

    /** Block entity data is never accepted, in any form, and a property-only edit is refused. */
    private static void noNbt() {
        refused("{\"id\":\"minecraft:chest\",\"block_entity\":{}}", PlacerRules.NO_NBT, "A block_entity key");
        refused("{\"id\":\"minecraft:white_wool\",\"block_entity\":null}", PlacerRules.NO_NBT, "A block_entity key, even null");
        refused("minecraft:chest{Items:[]}", PlacerRules.NO_NBT, "SNBT after the id");
        refused("minecraft:white_wool{}", PlacerRules.NO_NBT, "A brace after the id");
        refused("{Items:[{id:\"minecraft:diamond\"}]}", PlacerRules.NO_NBT, "SNBT alone");
        refused("{\"id\":\"minecraft:redstone_lamp\",\"properties\":{\"lit\":{\"x\":1}}}", PlacerRules.NO_NBT, "A property that is no plain value");
        refused("{\"facing\":\"south\",\"powered\":\"true\"}", "only an id and properties", "v1's property-only edit");
        refused("{\"id\":\"minecraft:white_wool\",\"count\":64}", "only an id and properties", "Another key");
        refused("{\"properties\":{\"lit\":\"true\"}}", "needs its id", "Properties without an id");
        check(PlacerRules.blockText(JsonParser.parseString("{\"id\":\"minecraft:redstone_lamp\",\"properties\":{\"lit\":\"false\"}}").getAsJsonObject())
                .getOrThrow().equals("minecraft:redstone_lamp[lit=false]"), "The JSON form as the /setblock form");
    }

    /** Replaceable spaces, the own-block rule, the claim rule and spawn protection. */
    private static void rules() {
        check(Blocks.AIR.defaultBlockState().canBeReplaced() && Blocks.WATER.defaultBlockState().canBeReplaced()
                && Blocks.SHORT_GRASS.defaultBlockState().canBeReplaced() && !Blocks.STONE.defaultBlockState().canBeReplaced()
                && !Blocks.WOOL.white().defaultBlockState().canBeReplaced(), "Only a replaceable space takes a block");
        BlockState wool = Blocks.WOOL.white().defaultBlockState();
        check(PlacerRules.ownBlock(wool, wool) && !PlacerRules.ownBlock(wool, Blocks.STONE.defaultBlockState()) && !PlacerRules.ownBlock(null, wool)
                && !PlacerRules.ownBlock(Blocks.REDSTONE_LAMP.defaultBlockState(), Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true)),
                "It clears only the state it last saw; untracked blocks (a v1 emitter's) are never its");
        BlockState lamp = Blocks.REDSTONE_LAMP.defaultBlockState();
        BlockState lit = lamp.setValue(RedstoneLampBlock.LIT, true);
        check(PlacerRules.follow(lamp, lit) == lit && PlacerRules.follow(wool, wool) == wool && PlacerRules.ownBlock(PlacerRules.follow(lamp, lit), lit),
                "The same block in another state is still its block, in the new state, so it may clear the lit lamp");
        check(PlacerRules.follow(wool, Blocks.STONE.defaultBlockState()) == null && PlacerRules.follow(wool, Blocks.AIR.defaultBlockState()) == null
                && PlacerRules.follow(null, wool) == null, "Another block, air or nothing placed: nothing there is its");

        check(PlacerRules.claimAllows(false, false, false), "Without a claim mod nothing is checked");
        check(PlacerRules.claimAllows(true, true, false), "The same claim: allowed, the owner offline");
        check(!PlacerRules.claimAllows(true, false, false), "Across a claim line with the owner offline: refused (an offline owner cannot be asked)");
        check(PlacerRules.claimAllows(true, false, true), "Across a claim line, the owner online and allowed to build there: allowed");

        BlockPos spawn = new BlockPos(0, 64, 0);
        check(PlacerRules.underSpawnProtection(16, true, spawn, new BlockPos(16, 70, -16), false, false), "Inside the square of the radius");
        check(!PlacerRules.underSpawnProtection(16, true, spawn, new BlockPos(17, 70, 0), false, false), "Outside it");
        check(!PlacerRules.underSpawnProtection(16, false, spawn, BlockPos.ZERO, false, false), "Only in the respawn dimension");
        check(!PlacerRules.underSpawnProtection(16, true, spawn, BlockPos.ZERO, true, false), "Without any operator vanilla protects nothing");
        check(!PlacerRules.underSpawnProtection(16, true, spawn, BlockPos.ZERO, false, true), "An operator owner builds there");
        check(!PlacerRules.underSpawnProtection(0, true, spawn, BlockPos.ZERO, false, false), "A radius of 0 protects nothing");
    }

    /** Four actions a second per placer: ten messages at once act four times. */
    private static void rate() {
        AtomicLong nanos = new AtomicLong();
        TokenBucket bucket = new TokenBucket(BlockPlacer.ACTIONS_PER_SECOND, BlockPlacer.ACTIONS_PER_SECOND, nanos::get);
        int acted = 0;
        for (int message = 0; message < 10; message++) {
            if (BlockEmitterBlockEntity.actionRefusal(true, bucket).isEmpty()) acted++;
        }
        check(acted == 4, "Ten messages at once act four times: " + acted);
        check(BlockEmitterBlockEntity.actionRefusal(true, bucket).orElseThrow().startsWith("too fast"), "The fifth says why");
        nanos.addAndGet(250_000_000L);
        check(BlockEmitterBlockEntity.actionRefusal(true, bucket).isEmpty() && BlockEmitterBlockEntity.actionRefusal(true, bucket).isPresent(),
                "A quarter of a second later one more");
    }

    /** blockPlacer.consumeItems: the item comes from the container behind, through the face it touches, and goes back. */
    private static void material() {
        SimpleContainer chest = new SimpleContainer(3);
        chest.setItem(1, new ItemStack(Items.WOOL.white(), 2));
        check(PlacerRules.take(chest, Direction.NORTH, Items.WOOL.white()) && chest.getItem(1).getCount() == 1, "One wool is taken");
        check(!PlacerRules.take(chest, Direction.NORTH, Items.GLASS), "No glass, no material");
        check(PlacerRules.take(chest, Direction.NORTH, Items.WOOL.white()) && chest.getItem(1).isEmpty(), "The last one");
        check(!PlacerRules.take(chest, Direction.NORTH, Items.WOOL.white()), "Then none");
        check(!PlacerRules.take(chest, Direction.NORTH, Items.AIR), "Air is never material");
        check(PlacerRules.give(chest, Direction.NORTH, new ItemStack(Items.WOOL.white())).isEmpty() && chest.countItem(Items.WOOL.white()) == 1, "Clear gives it back");
        SimpleContainer full = new SimpleContainer(1);
        full.setItem(0, new ItemStack(Items.STONE, 64));
        check(PlacerRules.give(full, Direction.NORTH, new ItemStack(Items.WOOL.white())).getCount() == 1, "A full container leaves it to be dropped");
        check(PlacerRules.give(null, Direction.NORTH, new ItemStack(Items.WOOL.white())).getCount() == 1, "No container leaves it to be dropped");

        // A sided container (a furnace-like one): only slot 0 faces up, and only slot 0 may be taken from.
        Sided sided = new Sided();
        sided.setItem(0, new ItemStack(Items.GLASS));
        sided.setItem(1, new ItemStack(Items.WOOL.white()));
        check(!PlacerRules.take(sided, Direction.UP, Items.WOOL.white()) && sided.getItem(1).getCount() == 1, "A slot the face does not offer is not taken from");
        check(PlacerRules.take(sided, Direction.UP, Items.GLASS) && sided.getItem(0).isEmpty(), "The slot it offers is");
        check(!PlacerRules.take(sided, Direction.DOWN, Items.WOOL.white()), "A face that offers nothing gives nothing");
    }

    /** The Raw Link grammar: place, clear and a block to place. */
    private static void grammar() {
        check(command("place").equals(json("{\"place\":\"PRESS\"}")) && command(" ON ").equals(json("{\"place\":\"PRESS\"}"))
                && command("true").equals(json("{\"place\":\"PRESS\"}")), "place, ON, true place");
        check(command("CLEAR").equals(json("{\"clear\":\"PRESS\"}")) && command("off").equals(json("{\"clear\":\"PRESS\"}"))
                && command("0").equals(json("{\"clear\":\"PRESS\"}")), "clear, OFF, 0 clear");
        check(command("minecraft:white_wool").equals(json("{\"block\":\"minecraft:white_wool\",\"place\":\"PRESS\"}")), "A block sets it and places");
        check(command("{\"id\":\"minecraft:chest\",\"block_entity\":{}}").get("block").getAsString().equals("{\"id\":\"minecraft:chest\",\"block_entity\":{}}"),
                "The grammar reads only the shape: the device refuses block entity data, and counts it");
        check(ValueCodecs.PLACER.command("", ValueCodecs.CustomPayloads.NONE).isEmpty() && ValueCodecs.PLACER.command("  ", ValueCodecs.CustomPayloads.NONE).isEmpty(),
                "An empty message commands nothing");
        RawLinkSpec spec = BlockPlacer.TYPE.rawLink().orElseThrow();
        check(spec.direction() == RawLinkSpec.Direction.IN && !spec.acceptRetained() && spec.grammar() == ValueCodecs.PLACER && !spec.customPayloads(),
                "A Raw Link input that never applies a retained message (D12), without custom payloads");
    }

    /** The switch: off answers every action with disabled and costs no token; the type's own rules. */
    private static void switchAndType() {
        check(!MineQTTConfig.blockPlacerEnabled, "The block placer is off by default (design §8.3)");
        AtomicLong nanos = new AtomicLong();
        TokenBucket bucket = new TokenBucket(1, 1, nanos::get);
        check(BlockEmitterBlockEntity.actionRefusal(false, bucket).equals(Optional.of(BlockEmitterBlockEntity.DISABLED)),
                "Off, every action is answered with disabled");
        check(BlockEmitterBlockEntity.actionRefusal(true, bucket).isEmpty(), "and spends no token");
        check(MineQTTConfig.blockPlacerConsumeItems, "The placer consumes items by default");
        check(BlockPlacer.TYPE.editsWorld() && BlockPlacer.TYPE.unloadedPolicy() == art.rehra.mineqtt.device.UnloadedPolicy.UNAVAILABLE
                && BlockPlacer.TYPE.minInterval().isZero(), "It edits the world, never queues while unloaded and publishes at once");
        check(BlockPlacer.TYPE.action("place").isPresent() && BlockPlacer.TYPE.action("clear").isPresent() && BlockPlacer.TYPE.setting("block").isPresent()
                && BlockPlacer.TYPE.use().isEmpty(), "place and clear are actions, block a setting; a right-click opens its screen");
    }

    private static DataResult<PlacerRules.Request> allowed(String text) {
        return PlacerRules.allowed(text, BuiltInRegistries.BLOCK, PLACEABLE::contains);
    }

    private static void refused(String text, String reason, String description) {
        Optional<String> error = allowed(text).error().map(DataResult.Error::message);
        check(error.filter(message -> message.contains(reason)).isPresent(), description + ": '" + text + "' is refused for " + reason + ", got " + error);
    }

    private static JsonObject command(String payload) {
        return ValueCodecs.PLACER.command(payload, ValueCodecs.CustomPayloads.NONE).orElseThrow();
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    // A block tag as the shipped files hold it, nested tags resolved; vanilla's files come from the game's jar.
    private static Set<Block> resolveTag(String namespace, String path) throws IOException {
        Set<Block> blocks = new HashSet<>();
        String file = "/data/" + namespace + "/tags/block/" + path + ".json";
        try (InputStream in = BlockEmitterPayloadTest.class.getResourceAsStream(file)) {
            check(in != null, "Missing tag file " + file);
            for (JsonElement value : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("values")) {
                String entry = value.getAsString();
                if (entry.startsWith("#")) {
                    Identifier tag = Identifier.parse(entry.substring(1));
                    blocks.addAll(resolveTag(tag.getNamespace(), tag.getPath()));
                } else {
                    Identifier id = Identifier.parse(entry);
                    check(BuiltInRegistries.BLOCK.containsKey(id), "The tag names a block that exists: " + id);
                    blocks.add(BuiltInRegistries.BLOCK.getValue(id));
                }
            }
        }
        return blocks;
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    // A container that offers slot 0 to its top face only, and lets only slot 0 be taken.
    private static final class Sided extends SimpleContainer implements WorldlyContainer {
        Sided() {
            super(2);
        }

        @Override
        public int[] getSlotsForFace(Direction direction) {
            return direction == Direction.UP ? new int[]{0, 1} : new int[0];
        }

        @Override
        public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction direction) {
            return slot == 0;
        }

        @Override
        public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
            return slot == 0;
        }
    }
}

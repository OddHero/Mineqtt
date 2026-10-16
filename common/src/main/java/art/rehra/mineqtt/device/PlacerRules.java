package art.rehra.mineqtt.device;

import art.rehra.mineqtt.MineQTT;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.DataResult;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/**
 * What a Block Placer may build (M4 plan D12), as functions of what they are given, so the checks run them without a
 * world: the payload grammar, the allowlist {@code #mineqtt:placeable} over a supplied tag, the hard denies no datapack
 * lifts, the own-block rule of {@code clear}, the claim and spawn protection rules, and taking and returning the item
 * through the container behind the placer.
 * <ul>
 *     <li>A block is {@code id[property=value,...]} as {@code /setblock} writes it, or the JSON form
 *     {@code {"id":...,"properties":{...}}}. Block entity data is never accepted, in any form: a {@code block_entity}
 *     key, a brace after the id, SNBT.</li>
 *     <li>Only blocks of the tag, or any block while the container behind the placer unlocks them
 *     ({@link #containerUnlocks}: placing costs the item, so only what somebody put there), and of those never one with
 *     a block entity, a game master block (command blocks, structure and jigsaw blocks), TNT, a block no tool breaks
 *     (bedrock, barriers), a fluid, fire, air, a portal, a block of two parts or one without an item.</li>
 * </ul>
 */
public final class PlacerRules {
    /** The blocks a Block Placer may place, {@code data/mineqtt/tags/block/placeable.json}. */
    public static final TagKey<Block> PLACEABLE = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "placeable"));
    /** What a refused message with block entity data is told. */
    public static final String NO_NBT = "NBT is never accepted";
    private static final String ID = "id";
    private static final String PROPERTIES = "properties";

    private PlacerRules() {
    }

    /**
     * A block a placer may be set to: its state and its text, the id and the properties given, in their order.
     *
     * @param text the canonical text: {@code minecraft:oak_stairs[facing=east]}; only the properties given
     */
    public record Request(BlockState state, String text) {
        /** Whether properties were given; without, the block takes the state it would take placed by a dispenser. */
        public boolean givesProperties() {
            return text.indexOf('[') >= 0;
        }
    }

    /**
     * Reads a block from a payload, a Home Assistant select or a player's setting: the {@code /setblock} form or the JSON
     * form, one pair of quotes around it allowed. Refused: anything with block entity data ({@value #NO_NBT}), a tag, an
     * unknown block or property, text after the block. Whether the placer may place it is {@link #check}'s question.
     */
    public static DataResult<Request> parse(String payload, HolderLookup<Block> blocks) {
        String text = ValueCodecs.unquote(payload.strip()).strip();
        if (text.isEmpty()) return DataResult.error(() -> "no block given");
        if (text.startsWith("{")) {
            Optional<JsonElement> json = ValueCodecs.strictJson(text);
            if (json.isEmpty() || !json.get().isJsonObject()) return DataResult.error(() -> NO_NBT);
            return blockText(json.get().getAsJsonObject()).flatMap(converted -> parseText(converted, blocks));
        }
        return parseText(text, blocks);
    }

    /**
     * The JSON form as the {@code /setblock} form: {@code {"id":"minecraft:oak_stairs","properties":{"facing":"east"}}}
     * is {@code minecraft:oak_stairs[facing=east]}. A {@code block_entity} key refuses the whole message, as does any
     * other key besides the two, and a property that is no plain value.
     */
    public static DataResult<String> blockText(JsonObject json) {
        if (json.has("block_entity")) return DataResult.error(() -> NO_NBT);
        for (String key : json.keySet()) {
            if (!key.equals(ID) && !key.equals(PROPERTIES)) return DataResult.error(() -> "a block has only an id and properties, not " + key);
        }
        JsonElement id = json.get(ID);
        if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) return DataResult.error(() -> "a block needs its id");
        JsonElement properties = json.get(PROPERTIES);
        if (properties == null) return DataResult.success(id.getAsString());
        if (!properties.isJsonObject()) return DataResult.error(() -> "properties are an object of names and values");
        StringJoiner joined = new StringJoiner(",", "[", "]");
        for (Map.Entry<String, JsonElement> property : properties.getAsJsonObject().entrySet()) {
            if (!property.getValue().isJsonPrimitive()) return DataResult.error(() -> NO_NBT);
            JsonPrimitive value = property.getValue().getAsJsonPrimitive();
            joined.add(property.getKey() + "=" + value.getAsString());
        }
        return DataResult.success(properties.getAsJsonObject().isEmpty() ? id.getAsString() : id.getAsString() + joined);
    }

    // The /setblock form, read by vanilla's parser without block entity data; nothing may follow the block.
    private static DataResult<Request> parseText(String text, HolderLookup<Block> blocks) {
        if (text.indexOf('{') >= 0 || text.indexOf('}') >= 0) return DataResult.error(() -> NO_NBT);
        StringReader reader = new StringReader(text);
        BlockStateParser.BlockResult result;
        try {
            result = BlockStateParser.parseForBlock(blocks, reader, false);
        } catch (CommandSyntaxException e) {
            return DataResult.error(() -> "not a block: " + e.getRawMessage().getString());
        }
        if (reader.canRead()) return DataResult.error(() -> "not a block: text after it at " + reader.getCursor());
        return DataResult.success(new Request(result.blockState(), canonical(result)));
    }

    // The id and the properties given, in the order given.
    private static String canonical(BlockStateParser.BlockResult result) {
        String id = BuiltInRegistries.BLOCK.getKey(result.blockState().getBlock()).toString();
        if (result.properties().isEmpty()) return id;
        StringJoiner joined = new StringJoiner(",", "[", "]");
        result.properties().forEach((property, value) -> joined.add(property.getName() + "=" + valueName(property, value)));
        return id + joined;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String valueName(Property<T> property, Comparable<?> value) {
        return property.getName((T) value);
    }

    /**
     * Why no placer may place a block, whatever any tag or container says, or empty when the code allows it: a block
     * entity (so no NBT can ever be written), a game master block, TNT, a block no tool breaks, a fluid, fire, air, a
     * portal, a block of two parts (a door, a tall plant: the placer sets one block) and a block without an item of its
     * own (a piston head, an attached stem), which no container holds.
     */
    public static Optional<String> hardDenial(Block block) {
        BlockState state = block.defaultBlockState();
        if (state.isAir()) return Optional.of("air is no block to place");
        if (block.asItem() == Items.AIR) return Optional.of("a block without an item of its own is never placed");
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) return Optional.of("a block of two parts is never placed");
        if (state.hasBlockEntity()) return Optional.of("a block with a block entity is never placed");
        if (block instanceof GameMasterBlock) return Optional.of("a game master block is never placed");
        if (block instanceof TntBlock) return Optional.of("TNT is never placed");
        if (block.defaultDestroyTime() < 0) return Optional.of("a block no tool breaks is never placed");
        if (block instanceof LiquidBlock || !state.getFluidState().isEmpty()) return Optional.of("a fluid is never placed");
        if (block instanceof BaseFireBlock) return Optional.of("fire is never placed");
        if (block instanceof Portal) return Optional.of("a portal is never placed");
        return Optional.empty();
    }

    /**
     * Why a placer may not place a block state, or empty when it may: the hard denies first, then the allowlist.
     *
     * @param placeable whether a block is in {@code #mineqtt:placeable} now
     */
    public static Optional<String> check(BlockState state, Predicate<Block> placeable) {
        Optional<String> denial = hardDenial(state.getBlock());
        if (denial.isPresent()) return denial;
        if (!placeable.test(state.getBlock())) {
            return Optional.of(BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " is not on the #mineqtt:placeable list");
        }
        return Optional.empty();
    }

    /** {@link #parse} and {@link #check} in one: a block a placer may be set to and place now, or why not. */
    public static DataResult<Request> allowed(String payload, HolderLookup<Block> blocks, Predicate<Block> placeable) {
        return parse(payload, blocks).flatMap(request -> check(request.state(), placeable)
                .<DataResult<Request>>map(problem -> DataResult.error(() -> problem))
                .orElseGet(() -> DataResult.success(request)));
    }

    /** Whether a block is in {@code #mineqtt:placeable} as the server's tags say now. */
    public static boolean placeable(Block block) {
        return block.builtInRegistryHolder().is(PLACEABLE);
    }

    /**
     * Whether the container behind a placer unlocks blocks beyond the tag: only while placing costs the item
     * ({@code blockPlacer.consumeItems}), so every block placed is one somebody put there, and
     * {@code blockPlacer.containerUnlocks} is on.
     */
    public static boolean containerUnlocks(boolean consumeItems, boolean containerUnlocks) {
        return consumeItems && containerUnlocks;
    }

    /**
     * What a placer may be set to and place, after the hard denies ({@link #check}): a block of the tag, or with the
     * container unlocking ({@link #containerUnlocks}) any block; that one is placed only with its item from the container.
     */
    public static Predicate<Block> allowedBlocks(Predicate<Block> placeable, boolean unlocked) {
        return unlocked ? block -> true : placeable;
    }

    /**
     * The blocks the container behind a placer offers as its choices: the blocks of the block items it holds in the
     * slots the placer takes from ({@link #take}), without the hard denies and the blocks {@code allowed} refuses,
     * sorted and distinct.
     *
     * @param face the side of the container the placer touches
     */
    public static List<String> choices(Container container, Direction face, Predicate<Block> allowed) {
        TreeSet<String> ids = new TreeSet<>();
        for (int slot : slots(container, face)) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) continue;
            if (container instanceof WorldlyContainer worldly && !worldly.canTakeItemThroughFace(slot, stack, face)) continue;
            Block block = blockItem.getBlock();
            if (hardDenial(block).isEmpty() && allowed.test(block)) ids.add(BuiltInRegistries.BLOCK.getKey(block).toString());
        }
        return List.copyOf(ids);
    }

    /**
     * The block ids a Home Assistant select offers: the members of the tag a placer may place, without the hard denies a
     * datapack may have put in it, sorted. The select keeps at most its own maximum.
     */
    public static List<String> options(Iterable<Holder<Block>> tag) {
        List<String> options = new ArrayList<>();
        for (Holder<Block> holder : tag) {
            if (hardDenial(holder.value()).isEmpty()) options.add(BuiltInRegistries.BLOCK.getKey(holder.value()).toString());
        }
        return options.stream().distinct().sorted().toList();
    }

    /**
     * The own-block rule: a placer clears or replaces only the block it placed, in the state it last saw it in. Before
     * it asks, it {@link #follow follows} that block, so a change of state of the same block (power lights a lamp) stays
     * its block; a block a v1 emitter placed is never its.
     *
     * @param placed what it placed there, as it last saw it, null for nothing
     */
    public static boolean ownBlock(@Nullable BlockState placed, BlockState now) {
        return placed != null && placed == now;
    }

    /**
     * What the placer counts as its block when it looks again: the same block in another state (a lamp lit by power, or
     * anything else that changed its state in place) is still its block, in the new state; another block there (broken,
     * replaced, moved away by a piston) is no longer its.
     *
     * @param placed what it placed there, as it last saw it, null for nothing
     * @return the state to keep as its block, or null when nothing there is its
     */
    public static @Nullable BlockState follow(@Nullable BlockState placed, BlockState now) {
        return placed != null && now.is(placed.getBlock()) ? now : null;
    }

    /**
     * The claim rule (D12): without a claim mod nothing is checked, as everywhere in MineQTT; with one, the placer and its
     * target share one claim, or the owner is online and the claim at the target lets them place. An offline owner cannot
     * be asked, so a placer across a claim line places nothing while they are away.
     *
     * @param ownerMayPlace whether the owner is online and may place at the target; false for an offline owner
     */
    public static boolean claimAllows(boolean claimMod, boolean sameClaim, boolean ownerMayPlace) {
        return !claimMod || sameClaim || ownerMayPlace;
    }

    /**
     * Vanilla's spawn protection of a dedicated server, for an owner who may be offline: the square of
     * {@code spawn-protection} blocks around the respawn point of the respawn dimension, unless no operator is set or the
     * owner is one.
     */
    public static boolean underSpawnProtection(int radius, boolean respawnDimension, BlockPos respawn, BlockPos target, boolean noOperators, boolean ownerOp) {
        if (!respawnDimension || noOperators || ownerOp || radius <= 0) return false;
        return Math.max(Math.abs(target.getX() - respawn.getX()), Math.abs(target.getZ() - respawn.getZ())) <= radius;
    }

    /**
     * Takes one item of {@code item} out of the container behind a placer, through the face that touches it: a
     * {@link WorldlyContainer} only from the slots that face offers and allows taking from.
     *
     * @param face the side of the container the placer touches
     * @return false when there is none to take
     */
    public static boolean take(Container container, Direction face, Item item) {
        if (item == Items.AIR) return false;
        for (int slot : slots(container, face)) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || !stack.is(item)) continue;
            if (container instanceof WorldlyContainer worldly && !worldly.canTakeItemThroughFace(slot, stack, face)) continue;
            stack.shrink(1);
            container.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
            container.setChanged();
            return true;
        }
        return false;
    }

    /**
     * Puts an item back into the container behind a placer through the face that touches it, as a hopper inserts.
     *
     * @return what did not fit, empty when all did
     */
    public static ItemStack give(@Nullable Container container, Direction face, ItemStack stack) {
        if (container == null || stack.isEmpty()) return stack;
        return HopperBlockEntity.addItem(null, container, stack, face);
    }

    private static int[] slots(Container container, Direction face) {
        return container instanceof WorldlyContainer worldly ? worldly.getSlotsForFace(face) : IntStream.range(0, container.getContainerSize()).toArray();
    }
}

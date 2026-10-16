package art.rehra.mineqtt.device.types;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.device.Access;
import art.rehra.mineqtt.device.Action;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.Field;
import art.rehra.mineqtt.device.PlacerRules;
import art.rehra.mineqtt.device.RawLinkSpec;
import art.rehra.mineqtt.device.Setting;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.UnloadedPolicy;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.ha.Ha;
import art.rehra.mineqtt.ha.HaEntity;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.TreeSet;

/**
 * The Block Placer ({@code block_emitter_block}, M4 plan D12): places the block it is set to into the space in front of
 * its face and clears it again, from Home Assistant, a wire, its Raw Link or a player. It took over from the v1 Block
 * Emitter, whose topic became its Raw Link input. It is the first device that changes the world, so every rule of D12
 * holds: off unless {@code blockPlacer.enabled}, only blocks of {@code #mineqtt:placeable} (or, while
 * {@code blockPlacer.containerUnlocks} is on and placing costs the item, any block from the container behind it) and
 * never one the code denies ({@link PlacerRules}), never block entity data, only into a replaceable space, only its own block cleared, the claim
 * rule, four actions a second, the item taken from the container behind it ({@code blockPlacer.consumeItems}), retained
 * messages and an unloaded placer's commands never applied, and configured by operators on a dedicated server
 * ({@link DeviceType#editsWorld()}).
 * <p>
 * Its {@code last_result} says what came of the last action, a refusal with its reason.
 */
public final class BlockPlacer {
    public static final String PLACE = "place";
    public static final String CLEAR = "clear";
    /** At most this many actions a second, and as many at once. */
    public static final int ACTIONS_PER_SECOND = 4;

    /** The block it places: an id, with properties if given ({@link PlacerRules#parse}); empty for none yet. */
    public static final Field<String> BLOCK = Field.text("block", Access.READ_WRITE);
    /** Whether the block it placed stands in front of it. */
    public static final Field<Boolean> PLACED = Field.bool("placed", Access.READ);
    /** What came of the last action, e.g. {@code placed minecraft:white_wool} or a refusal with its reason. */
    public static final Field<String> LAST_RESULT = Field.text("last_result", Access.READ);
    /**
     * The blocks it can place from the container behind it, which Home Assistant's select offers: the block ids of the
     * block items there that it may place ({@link PlacerRules#choices}), and the block it is set to, so that one stays an
     * option. Empty while it takes no items or has no container behind it it may open, and while that container holds
     * none and no block is set: the select then offers the whole {@code #mineqtt:placeable} list ({@link #options()}).
     */
    public static final Field<List<String>> CHOICES = Field.json("choices", Codec.STRING.listOf(), List.of(), Access.READ);
    /**
     * The state of Home Assistant's block select: the id without its properties, so it is always an option, and
     * {@code None} (unknown) while no block is set, which Home Assistant would otherwise log as an invalid option.
     */
    public static final String SELECT_TEMPLATE = "{{ value_json.block.split('[')[0] if value_json.block else None }}";

    public static final DeviceType TYPE = DeviceType.builder(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "block_placer"))
            .model("Block Placer").unloaded(UnloadedPolicy.UNAVAILABLE).editsWorld()
            .field(BLOCK).field(PLACED).field(LAST_RESULT).field(CHOICES).field(CommonFields.LOCATION)
            .setting(new Setting<>(BLOCK))
            .action(Action.press(PLACE)).action(Action.press(CLEAR))
            .entity(Ha.selectDynamic("block", BLOCK, BlockPlacer::options).valueTemplate(SELECT_TEMPLATE).optionsFrom(CHOICES))
            .entity(Ha.button(PLACE))
            .entity(Ha.button(CLEAR))
            .entity(Ha.binarySensor("placed", PLACED))
            .entity(Ha.sensor("last_result", LAST_RESULT).name("Last result").diagnostic())
            .entity(Ha.sensor("location", CommonFields.LOCATION).name("Location").diagnostic().disabledByDefault())
            // A retained message would replay a stale build at startup (D12): the input never applies one. v1's slots 2 and
            // 3 never were payloads.
            .rawLink(RawLinkSpec.in(ValueCodecs.PLACER).withoutCustomPayloads())
            .build();

    private BlockPlacer() {
    }

    /**
     * The blocks Home Assistant's select offers a placer without {@link #CHOICES}, and a group of placers:
     * {@code #mineqtt:placeable} as the server's tags say now, without the blocks the code denies. A datapack reload that
     * changes the tag changes them ({@code refreshDynamicOptions}).
     */
    public static List<String> options() {
        return PlacerRules.options(BuiltInRegistries.BLOCK.getTagOrEmpty(PlacerRules.PLACEABLE));
    }

    /**
     * The block a placer placed and stands in front of it, in the state it last saw it in (a change of the block's state
     * in place is followed, {@link art.rehra.mineqtt.device.PlacerRules#follow}).
     *
     * @param pos   where it placed it
     * @param state the state it placed, or the state it changed to since
     */
    public record Placed(BlockPos pos, BlockState state) {
        public static final Codec<Placed> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(Placed::pos),
                BlockState.CODEC.fieldOf("state").forGetter(Placed::state)
        ).apply(instance, Placed::new));
    }

    /**
     * A placer's setting, what it said last and the choices it last saw behind it; what it placed is kept beside it
     * ({@link Placed}). The choices are kept so a placer loading again publishes the options Home Assistant already has.
     *
     * @param block      the block it places, as {@link PlacerRules.Request#text()} writes it; empty for none
     * @param lastResult what came of its last action
     * @param choices    the {@link #CHOICES} it last saw
     */
    public record Settings(String block, String lastResult, List<String> choices) {
        public static final Settings DEFAULT = new Settings("", "", List.of());
        /** The placer in the block entity's {@code state} compound; unreadable values keep their default. */
        public static final Codec<Settings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.lenientOptionalFieldOf(BLOCK.key(), "").forGetter(Settings::block),
                Codec.STRING.lenientOptionalFieldOf(LAST_RESULT.key(), "").forGetter(Settings::lastResult),
                Codec.STRING.listOf().lenientOptionalFieldOf(CHOICES.key(), List.of()).forGetter(Settings::choices)
        ).apply(instance, Settings::new));

        public Settings {
            block = ValueCodecs.sanitizeText(block);
            lastResult = ValueCodecs.sanitizeText(lastResult);
            choices = List.copyOf(choices);
        }

        /** A setting and last result without choices yet: the placer looks behind itself at its next tick. */
        public Settings(String block, String lastResult) {
            this(block, lastResult, List.of());
        }

        public Settings withBlock(String newBlock) {
            return new Settings(newBlock, lastResult, choices);
        }

        public Settings withResult(String result) {
            return new Settings(block, result, choices);
        }

        public Settings withChoices(List<String> newChoices) {
            return new Settings(block, lastResult, newChoices);
        }

        /** Writes every readable field but the location; {@code placed} says whether its block stands. */
        public StateWriter write(StateWriter out, boolean placed) {
            return out.put(BLOCK, block).put(PLACED, placed).put(LAST_RESULT, lastResult).put(CHOICES, choices);
        }
    }

    /**
     * A placer's choices ({@link #CHOICES}): the blocks the container behind it offers and the block it is set to (its id
     * without properties), sorted and distinct, at most as many as a select offers; empty when both are.
     *
     * @param fromContainer the container's blocks ({@link PlacerRules#choices}); null without a container it takes from
     *                      and may open, which leaves the choices empty
     * @param block         the block it is set to, as its setting writes it; empty for none
     */
    public static List<String> choices(@Nullable List<String> fromContainer, String block) {
        if (fromContainer == null) return List.of();
        TreeSet<String> all = new TreeSet<>(fromContainer);
        int properties = block.indexOf('[');
        String id = properties < 0 ? block : block.substring(0, properties);
        if (!id.isBlank()) all.add(id);
        return all.stream().limit(HaEntity.Select.MAX_OPTIONS).toList();
    }
}

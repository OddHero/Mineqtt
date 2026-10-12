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
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * The Block Placer ({@code block_emitter_block}, M4 plan D12): places the block it is set to into the space in front of
 * its face and clears it again, from Home Assistant, a wire, its Raw Link or a player. It took over from the v1 Block
 * Emitter, whose topic became its Raw Link input. It is the first device that changes the world, so every rule of D12
 * holds: off unless {@code blockPlacer.enabled}, only blocks of {@code #mineqtt:placeable} and never one the code denies
 * ({@link PlacerRules}), never block entity data, only into a replaceable space, only its own block cleared, the claim
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
     * The state of Home Assistant's block select: the id without its properties, so it is always an option, and
     * {@code None} (unknown) while no block is set, which Home Assistant would otherwise log as an invalid option.
     */
    public static final String SELECT_TEMPLATE = "{{ value_json.block.split('[')[0] if value_json.block else None }}";

    public static final DeviceType TYPE = DeviceType.builder(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "block_placer"))
            .model("Block Placer").unloaded(UnloadedPolicy.UNAVAILABLE).editsWorld()
            .field(BLOCK).field(PLACED).field(LAST_RESULT).field(CommonFields.LOCATION)
            .setting(new Setting<>(BLOCK))
            .action(Action.press(PLACE)).action(Action.press(CLEAR))
            .entity(Ha.selectDynamic("block", BLOCK, BlockPlacer::options).valueTemplate(SELECT_TEMPLATE))
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
     * The blocks Home Assistant's select offers: {@code #mineqtt:placeable} as the server's tags say now, without the
     * blocks the code denies. A datapack reload that changes the tag changes them ({@code refreshDynamicOptions}).
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
     * A placer's setting and what it said last; what it placed is kept beside it ({@link Placed}).
     *
     * @param block      the block it places, as {@link PlacerRules.Request#text()} writes it; empty for none
     * @param lastResult what came of its last action
     */
    public record Settings(String block, String lastResult) {
        public static final Settings DEFAULT = new Settings("", "");
        /** The placer in the block entity's {@code state} compound; unreadable values keep their default. */
        public static final Codec<Settings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.lenientOptionalFieldOf(BLOCK.key(), "").forGetter(Settings::block),
                Codec.STRING.lenientOptionalFieldOf(LAST_RESULT.key(), "").forGetter(Settings::lastResult)
        ).apply(instance, Settings::new));

        public Settings {
            block = ValueCodecs.sanitizeText(block);
            lastResult = ValueCodecs.sanitizeText(lastResult);
        }

        public Settings withBlock(String newBlock) {
            return new Settings(newBlock, lastResult);
        }

        public Settings withResult(String result) {
            return new Settings(block, result);
        }

        /** Writes every readable field but the location; {@code placed} says whether its block stands. */
        public StateWriter write(StateWriter out, boolean placed) {
            return out.put(BLOCK, block).put(PLACED, placed).put(LAST_RESULT, lastResult);
        }
    }
}

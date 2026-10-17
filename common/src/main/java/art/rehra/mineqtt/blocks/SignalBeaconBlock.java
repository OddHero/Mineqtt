package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.blocks.entities.SignalBeaconBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import org.jetbrains.annotations.Nullable;

/**
 * The Signal Beacon ({@link SignalBeaconBlockEntity}): a lens in a frame, placed on top of a vanilla beacon (or anywhere
 * in its beam). {@link #COLOR} is the dye its beam has and {@link #LIT} whether the beam passes now; the block entity
 * keeps both in step with the device, also through a blink, so every client's beacon draws the same beam without any
 * client code. In the beacon's beam scan the block stands in for stained glass of its colour while lit and for an
 * opaque block while not ({@link #beamStandIn}); its own model is opaque, so no render layer is needed on any version.
 */
public class SignalBeaconBlock extends BaseMqttBlock {
    public static final MapCodec<SignalBeaconBlock> CODEC = simpleCodec(SignalBeaconBlock::new);
    public static final EnumProperty<DyeColor> COLOR = EnumProperty.create("color", DyeColor.class);
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public SignalBeaconBlock(Properties properties) {
        super(properties, CODEC);
        registerDefaultState(getStateDefinition().any().setValue(COLOR, DyeColor.WHITE).setValue(LIT, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(COLOR, LIT);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SignalBeaconBlockEntity(pos, state);
    }

    /** The server blinks the beam and looks for the beacon below; the client has nothing to tick. */
    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : new SignalBeaconBlockEntity.ServerTicker<>();
    }

    /**
     * What a beacon's beam scan sees instead of {@code state}: a lit Signal Beacon is stained glass of its colour (the
     * beam takes that colour, or mixes it with the colours below as vanilla mixes glass), one that is not lit is tinted
     * glass, which stops the beam as any opaque block does. Any other block is itself.
     */
    public static BlockState beamStandIn(BlockState state) {
        if (!(state.getBlock() instanceof SignalBeaconBlock)) return state;
        return state.getValue(LIT) ? stainedGlass(state.getValue(COLOR)).defaultBlockState() : Blocks.TINTED_GLASS.defaultBlockState();
    }

    /** The stained glass block of a dye: one of a colour collection from 26.2, a field per colour before. */
    public static Block stainedGlass(DyeColor dye) {
        //? if >=26.2 {
        return Blocks.STAINED_GLASS.pick(dye);
        //?} else {
        /*return switch (dye) {
            case WHITE -> Blocks.WHITE_STAINED_GLASS;
            case ORANGE -> Blocks.ORANGE_STAINED_GLASS;
            case MAGENTA -> Blocks.MAGENTA_STAINED_GLASS;
            case LIGHT_BLUE -> Blocks.LIGHT_BLUE_STAINED_GLASS;
            case YELLOW -> Blocks.YELLOW_STAINED_GLASS;
            case LIME -> Blocks.LIME_STAINED_GLASS;
            case PINK -> Blocks.PINK_STAINED_GLASS;
            case GRAY -> Blocks.GRAY_STAINED_GLASS;
            case LIGHT_GRAY -> Blocks.LIGHT_GRAY_STAINED_GLASS;
            case CYAN -> Blocks.CYAN_STAINED_GLASS;
            case PURPLE -> Blocks.PURPLE_STAINED_GLASS;
            case BLUE -> Blocks.BLUE_STAINED_GLASS;
            case BROWN -> Blocks.BROWN_STAINED_GLASS;
            case GREEN -> Blocks.GREEN_STAINED_GLASS;
            case RED -> Blocks.RED_STAINED_GLASS;
            case BLACK -> Blocks.BLACK_STAINED_GLASS;
        };
        *///?}
    }
}

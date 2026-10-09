package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.blocks.SmartPlugBlock;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommandResult;
import art.rehra.mineqtt.device.CommandSource;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.SmartPlug;
import art.rehra.mineqtt.device.types.SmartPlug.Plug;
import art.rehra.mineqtt.legacy.LegacyNbtV1;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.Optional;

import static art.rehra.mineqtt.blocks.SmartPlugBlock.POWER;

/**
 * The Smart Plug ({@link SmartPlug}): on or off as Home Assistant, its group, its Raw Link or a right-click set it. Its
 * state is in the {@code mineqtt} compound, and the block's {@code power} property follows it; v1 data, which only had
 * that property, is read on load and adopted when the device attaches. Clients get no render data: the block state
 * shows whether it is on.
 */
public class SmartPlugBlockEntity extends DeviceBlockEntity {
    private final DeviceLink link = new DeviceLink(SmartPlug.TYPE);
    private Plug plug;

    public SmartPlugBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.SMART_PLUG_BLOCK_ENTITY.get(), pos, blockState);
        this.plug = Plug.ofPower(blockState.getValue(POWER));
    }

    @Override
    public DeviceType deviceType() {
        return SmartPlug.TYPE;
    }

    @Override
    public DeviceLink deviceLink() {
        return link;
    }

    @Override
    public void writeState(StateWriter out) {
        plug.write(out).put(CommonFields.LOCATION, GlobalPos.of(level.dimension(), worldPosition));
    }

    @Override
    public CommandResult applyCommand(CommandReader in, CommandSource source) {
        Optional<Plug> next = plug.apply(in);
        if (next.isEmpty()) return CommandResult.rejected("not a plug command: " + in.keys());
        if (next.get().equals(plug)) return CommandResult.unchanged();
        setPlug(next.get());
        return CommandResult.applied();
    }

    @Override
    public void powerOn() {
        Plug next = plug.poweredOn();
        if (!next.equals(plug)) setPlug(next);
    }

    /** As in v1, taking the topic away switches the plug off. */
    @Override
    public void rawRemoved() {
        if (!plug.on()) return;
        setPlug(new Plug(false, plug.powerOn()));
        link.stateChanged(this);
    }

    @Override
    protected void saveDevice(ValueOutput output) {
        savePlug(link, plug, output);
    }

    @Override
    protected void loadDevice(ValueInput input) {
        plug = loadPlug(link, input, getBlockState().getValue(POWER));
    }

    /** A placed copy keeps its power-on behaviour and is off. */
    @Override
    protected void placedCopy() {
        setPlug(plug.placed());
    }

    /**
     * A plug's data: the link with the plug as its {@code state}, or v1's keys while its v1 data waits for adoption, so
     * it is still v1 data when it loads again.
     */
    public static void savePlug(DeviceLink link, Plug state, ValueOutput output) {
        if (link.awaitingAdoption()) {
            link.saveV1(output);
            LegacyNbtV1.writeSubscribed(output, link.pendingV1().combinedTopic());
        } else {
            link.save(output).store("state", Plug.CODEC, state);
        }
    }

    /**
     * Loads a plug's link and returns its state; v1 data, also a placed v1 item's, is kept for adoption and only had the
     * level of its block.
     *
     * @param blockPower the {@code power} property of the block
     */
    public static Plug loadPlug(DeviceLink link, ValueInput input, int blockPower) {
        return link.load(input, LegacyNbtV1.hasSubscribedKeys(input))
                .map(data -> data.read("state", Plug.CODEC).orElse(Plug.DEFAULT))
                .orElseGet(() -> Plug.ofPower(blockPower));
    }

    private void setPlug(Plug next) {
        plug = next;
        setChanged();
        updatePower();
    }

    // The block emits the plug's level; changing it keeps this block entity.
    private void updatePower() {
        if (level == null) return;
        BlockState state = getBlockState();
        if (state.getBlock() instanceof SmartPlugBlock plugBlock && state.getValue(POWER) != plug.power()) {
            BlockState powered = state.setValue(POWER, plug.power());
            level.setBlock(worldPosition, powered, Block.UPDATE_ALL);
            plugBlock.updateNeighbors(level, worldPosition, powered);
        }
    }

    /** The block's level. */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (!(blockEntity instanceof SmartPlugBlockEntity plug) || level.isClientSide()) return;
            // Item data, or a state set while the block was another one, is applied once the block is in the world.
            if (state.getValue(POWER) != plug.plug.power()) plug.updatePower();
        }
    }
}

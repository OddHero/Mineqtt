package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.blocks.RedstonePublisherBlock;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommandResult;
import art.rehra.mineqtt.device.CommandSource;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.RawLink;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.RedstoneInput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import static art.rehra.mineqtt.blocks.RedstonePublisherBlock.POWERED;

/**
 * The Redstone Input ({@link RedstoneInput}): reports the signal its block receives. The signal is in the
 * {@code mineqtt} compound; v1 data is read on load and adopted when the device attaches. Clients get no render data:
 * the block state shows whether it is powered.
 */
public class PublisherBlockEntity extends DeviceBlockEntity {
    private final DeviceLink link = new DeviceLink(RedstoneInput.TYPE);
    private int signal;
    // Neighbours in other chunks may have changed while this one was unloaded; the first server tick after the attach
    // reads the signal, so an edge it finds reaches the Raw Link and the Pulse event.
    private boolean signalRead;

    public PublisherBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.PUBLISHER_BLOCK.get(), pos, blockState);
    }

    @Override
    public DeviceType deviceType() {
        return RedstoneInput.TYPE;
    }

    @Override
    public DeviceLink deviceLink() {
        return link;
    }

    @Override
    public void writeState(StateWriter out) {
        RedstoneInput.write(out, signal).put(CommonFields.LOCATION, GlobalPos.of(level.dimension(), worldPosition));
    }

    @Override
    public CommandResult applyCommand(CommandReader in, CommandSource source) {
        return CommandResult.rejected("a Redstone Input takes no commands");
    }

    /**
     * v1's payload for whether the block is powered, once the signal was read after the attach: until then the
     * {@code powered} property is the one saved at the unload, which a retained publish must never repeat. From then on
     * every neighbour change keeps it live.
     */
    @Override
    public @Nullable String rawValue() {
        RawLink raw = link.raw();
        return raw == null || !signalRead ? null : RedstoneInput.rawPayload(getBlockState().getValue(POWERED), raw.customPayloads());
    }

    /**
     * The signal the block receives now. Its state follows; a rising or falling edge also switches the block's
     * {@code powered} property and publishes v1's payload to the Raw Link (a converted Static Publisher's link only the
     * rising one), and a rising edge is a {@code pulse} event.
     */
    public void signalChanged(int newSignal) {
        if (level == null) return;
        int received = Math.clamp(newSignal, 0, RedstoneInput.MAX_SIGNAL);
        BlockState state = level.getBlockState(worldPosition);
        boolean wasPowered = state.getValue(POWERED);
        boolean powered = received > 0;
        if (received == signal && powered == wasPowered) return;
        signal = received;
        setChanged();
        if (powered != wasPowered) {
            level.setBlock(worldPosition, state.setValue(POWERED, powered), Block.UPDATE_ALL);
            RawLink raw = link.raw();
            if (raw != null) link.publishRawEdge(this, powered, RedstoneInput.rawPayload(powered, raw.customPayloads()));
            if (powered) link.publishEvent(this, RedstoneInput.pulse(received));
        }
        link.stateChanged(this);
    }

    @Override
    protected void saveDevice(ValueOutput output) {
        saveInput(link, signal, output);
    }

    @Override
    protected void loadDevice(ValueInput input) {
        signal = loadInput(link, input, getBlockState().getValue(POWERED));
    }

    /** A placed copy reads its signal again after its attach, as every input does; until then it reports none. */
    @Override
    protected void placedCopy() {
        signal = 0;
    }

    /**
     * An input's data: the link with the signal in its {@code state}. v1 publishers wrote no keys of their own, so v1
     * data waiting for adoption writes only the keys every v1 device wrote.
     */
    public static void saveInput(DeviceLink link, int signal, ValueOutput output) {
        if (link.awaitingAdoption()) {
            link.saveV1(output);
        } else {
            link.save(output).child("state").putInt(RedstoneInput.SIGNAL.key(), signal);
        }
    }

    /**
     * Loads an input's link and returns its signal; v1 data, also a placed v1 item's, is kept for adoption. v1 kept no
     * signal: a powered block counts as full power until the signal is read after the attach. A v1 publisher wrote no
     * key of its own, so a {@code mineqtt} compound without a device id is no device's: it is a fresh block entity's, into
     * which a placed item's v1 data was merged.
     *
     * @param blockPowered the {@code powered} property of the block
     */
    public static int loadInput(DeviceLink link, ValueInput input, boolean blockPowered) {
        return link.load(input, true)
                .map(data -> data.child("state").map(state -> state.getIntOr(RedstoneInput.SIGNAL.key(), 0)).orElse(0))
                .orElse(blockPowered ? RedstoneInput.MAX_SIGNAL : 0);
    }

    /**
     * The signal, once after the attach: an edge it finds is published, otherwise the level it confirms is published once
     * to a retaining Raw Link, as the broker may hold an older one.
     */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (!(blockEntity instanceof PublisherBlockEntity input) || level.isClientSide()) return;
            if (!input.signalRead && input.link.attached()) {
                input.signalRead = true;
                boolean wasPowered = state.getValue(POWERED);
                input.signalChanged(RedstonePublisherBlock.inputSignal(level, pos));
                if (level.getBlockState(pos).getValue(POWERED) == wasPowered) input.link.rawValueKnown(input);
            }
        }
    }
}

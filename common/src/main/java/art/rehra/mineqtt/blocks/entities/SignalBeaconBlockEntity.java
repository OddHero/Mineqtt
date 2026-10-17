package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.blocks.SignalBeaconBlock;
import art.rehra.mineqtt.compat.BeaconBeams;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommandResult;
import art.rehra.mineqtt.device.CommandSource;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.SignalBeacon;
import art.rehra.mineqtt.device.types.SignalBeacon.Beam;
import art.rehra.mineqtt.device.types.SignalBeacon.Status;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * The Signal Beacon ({@link SignalBeacon}): its beam (on, dye, effect) is in the {@code mineqtt} compound. The block state
 * shows it to every client: {@code color} the dye, {@code lit} whether the beam passes now, which the server ticker
 * flips for a blink (game time, so every Signal Beacon blinks in step). The ticker also looks for the beacon below every
 * {@value SignalBeacon#STATUS_INTERVAL_TICKS} ticks; what it finds is published, never saved. No render data: the block
 * state is all a client needs.
 */
public class SignalBeaconBlockEntity extends DeviceBlockEntity {
    private final DeviceLink link = new DeviceLink(SignalBeacon.TYPE);
    private Beam beam = Beam.DEFAULT;
    private Status status = Status.NO_BEACON;
    private long lastScan = Long.MIN_VALUE / 2;

    public SignalBeaconBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.SIGNAL_BEACON.get(), pos, blockState);
    }

    @Override
    public DeviceType deviceType() {
        return SignalBeacon.TYPE;
    }

    @Override
    public DeviceLink deviceLink() {
        return link;
    }

    @Override
    public void writeState(StateWriter out) {
        SignalBeacon.write(out, beam, status).put(CommonFields.LOCATION, GlobalPos.of(level.dimension(), worldPosition));
    }

    /**
     * A light command ({@code state}, {@code color}, a colour temperature, {@code effect}; {@link Beam#merge}) or an
     * {@code alarm} state ({@link SignalBeacon#alarm}), which the light keys of the same command override.
     */
    @Override
    public CommandResult applyCommand(CommandReader in, CommandSource source) {
        if (!SignalBeacon.knows(in)) return CommandResult.rejected("not a signal beacon command: " + in.keys());
        if (in.has(SignalBeacon.ALARM) && SignalBeacon.alarm(in.json().get(SignalBeacon.ALARM)).isEmpty() && in.keys().size() == 1) {
            return CommandResult.rejected("not an alarm state: " + in.json().get(SignalBeacon.ALARM));
        }
        Beam next = SignalBeacon.apply(beam, in.json());
        if (next.equals(beam)) return CommandResult.unchanged();
        beam = next;
        setChanged();
        showBeam();
        return CommandResult.applied();
    }

    /** The beam as the device keeps it; read by the game tests. */
    public Beam beam() {
        return beam;
    }

    /** Whether a beacon's beam reaches the block, as last looked; read by the game tests. */
    public Status beaconStatus() {
        return status;
    }

    @Override
    protected void saveDevice(ValueOutput output) {
        link.save(output).store("state", Beam.CODEC, beam);
    }

    @Override
    protected void loadDevice(ValueInput input) {
        beam = link.load(input, false).flatMap(data -> data.read("state", Beam.CODEC)).orElse(Beam.DEFAULT);
    }

    /** A placed copy keeps its dye and effect, switched off. */
    @Override
    protected void placedCopy() {
        beam = beam.placed();
        showBeam();
    }

    // The block state shows the beam now: its dye, and whether it passes at this game time.
    private void showBeam() {
        if (level == null || level.isClientSide()) return;
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof SignalBeaconBlock)) return;
        BlockState shown = state.setValue(SignalBeaconBlock.COLOR, beam.dye()).setValue(SignalBeaconBlock.LIT, beam.lit(level.getGameTime()));
        if (shown != state) level.setBlock(worldPosition, shown, Block.UPDATE_ALL);
    }

    // One server tick: the blink, and every few ticks the beacon below.
    private void tick(ServerLevel level) {
        showBeam();
        long now = level.getGameTime();
        if (now - lastScan < SignalBeacon.STATUS_INTERVAL_TICKS) return;
        lastScan = now;
        Status found = BeaconBeams.status(level, worldPosition);
        if (found == status) return;
        status = found;
        link.stateChanged(this);
    }

    /** Each server tick: the blink and the beacon below. */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (blockEntity instanceof SignalBeaconBlockEntity beacon && level instanceof ServerLevel serverLevel) beacon.tick(serverLevel);
        }
    }
}

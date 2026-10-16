package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.compat.Vibrations;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommandResult;
import art.rehra.mineqtt.device.CommandSource;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.VibrationSensor;
import art.rehra.mineqtt.device.types.VibrationSensor.Hearing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * The Vibration Sensor ({@link VibrationSensor}): a game event listener on vanilla's vibration system, as a sculk sensor
 * is. A vibration travels to it (one block a tick, shown by vanilla's vibration particle), and once it arrives the sensor
 * reports it. Its settings and what it heard last are in the {@code mineqtt} compound; a vibration still travelling when
 * the chunk unloads is not saved, so no entity's UUID is kept with the block. Clients get no render data.
 * <p>
 * Who caused a vibration is only ever asked as "a player or not" ({@link Vibrations#causedByPlayer}); the entity itself is
 * never stored, published or logged.
 */
public class VibrationSensorBlockEntity extends DeviceBlockEntity implements GameEventListener.Provider<VibrationSystem.Listener>, VibrationSystem {
    private final DeviceLink link = new DeviceLink(VibrationSensor.TYPE);
    private Hearing hearing = Hearing.DEFAULT;
    private final VibrationSystem.Data vibrationData = new VibrationSystem.Data();
    private final VibrationSystem.User vibrationUser;
    private final VibrationSystem.Listener vibrationListener;

    public VibrationSensorBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.VIBRATION_SENSOR.get(), pos, blockState);
        this.vibrationUser = new SensorUser(pos);
        this.vibrationListener = new VibrationSystem.Listener(this);
    }

    @Override
    public DeviceType deviceType() {
        return VibrationSensor.TYPE;
    }

    @Override
    public DeviceLink deviceLink() {
        return link;
    }

    @Override
    public void writeState(StateWriter out) {
        hearing.write(out).put(CommonFields.LOCATION, GlobalPos.of(level.dimension(), worldPosition));
    }

    @Override
    public CommandResult applyCommand(CommandReader in, CommandSource source) {
        Optional<Hearing> next = hearing.apply(in);
        if (next.isEmpty()) return CommandResult.rejected("not a vibration sensor command: " + in.keys());
        if (next.get().equals(hearing)) return CommandResult.unchanged();
        hearing = next.get();
        setChanged();
        return CommandResult.applied();
    }

    @Override
    public VibrationSystem.Data getVibrationData() {
        return vibrationData;
    }

    @Override
    public VibrationSystem.User getVibrationUser() {
        return vibrationUser;
    }

    @Override
    public VibrationSystem.Listener getListener() {
        return vibrationListener;
    }

    /** What a comparator reads: the last vibration's frequency while {@code vibration} is on. */
    public int comparatorLevel() {
        return hearing.comparatorLevel();
    }

    /**
     * A vibration arrived: the state and the group's event (with the frequency on the Raw Link) go out as the type allows,
     * and comparators read the new level.
     */
    private void heard(ServerLevel level, Holder<GameEvent> event, @Nullable Entity sourceEntity, @Nullable Entity projectileOwner, float distance) {
        String gameEvent = Vibrations.id(event);
        int frequency = VibrationSystem.getGameEventFrequency(event);
        boolean player = Vibrations.causedByPlayer(sourceEntity) || Vibrations.causedByPlayer(projectileOwner);
        VibrationSensor.Source source = VibrationSensor.source(sourceEntity != null, player);
        if (frequency <= 0 || !hearing.detect().hears(source)) return;
        VibrationSensor.Group group = VibrationSensor.group(gameEvent, Vibrations.item(sourceEntity), Vibrations.projectile(sourceEntity));
        hearing = hearing.heard(group, frequency, gameEvent, source, distance, level.getGameTime());
        setChanged();
        link.stateChanged(this);
        link.publishEvent(this, VibrationSensor.event(group, frequency, gameEvent, source), Integer.toString(frequency));
        updateComparators(level);
    }

    // Turns vibration off once the clear delay after the last vibration is over.
    private void clearIfDue(ServerLevel level) {
        if (!hearing.expired(level.getGameTime())) return;
        hearing = hearing.cleared();
        setChanged();
        link.stateChanged(this);
        updateComparators(level);
    }

    private void updateComparators(Level level) {
        level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
    }

    @Override
    protected void saveDevice(ValueOutput output) {
        link.save(output).store("state", Hearing.CODEC, hearing);
    }

    @Override
    protected void loadDevice(ValueInput input) {
        hearing = link.load(input, false).flatMap(data -> data.read("state", Hearing.CODEC)).orElse(Hearing.DEFAULT);
    }

    /** A placed copy keeps its settings, and has heard nothing yet. */
    @Override
    protected void placedCopy() {
        hearing = hearing.placed();
    }

    /** How the sensor takes part in vanilla's vibration system: what it hears, from how far, and what it does with it. */
    private final class SensorUser implements VibrationSystem.User {
        private final BlockPos pos;
        private final PositionSource positionSource;

        private SensorUser(BlockPos pos) {
            this.pos = pos;
            this.positionSource = new BlockPositionSource(pos);
        }

        /** The {@code range} setting, asked for every game event. */
        @Override
        public int getListenerRadius() {
            return hearing.range();
        }

        @Override
        public PositionSource getPositionSource() {
            return positionSource;
        }

        /**
         * Not its own placement or breaking, a vibration vanilla gives a frequency, not while it rests after the last one,
         * and only one {@code detect} lets through. Vanilla has already left out spectators, sneaking, wool and what is
         * not in {@code #minecraft:vibrations}, and checks the line for wool after this.
         */
        @Override
        public boolean canReceiveVibration(ServerLevel level, BlockPos at, Holder<GameEvent> event, GameEvent.@Nullable Context context) {
            if (VibrationSensor.ownBlockEvent(Vibrations.id(event), at.equals(pos))) return false;
            if (VibrationSystem.getGameEventFrequency(event) <= 0 || hearing.resting(level.getGameTime())) return false;
            Entity sourceEntity = context == null ? null : context.sourceEntity();
            return hearing.detect().hears(VibrationSensor.source(sourceEntity != null, Vibrations.causedByPlayer(sourceEntity)));
        }

        @Override
        public void onReceiveVibration(ServerLevel level, BlockPos at, Holder<GameEvent> event, @Nullable Entity sourceEntity,
                                       @Nullable Entity projectileOwner, float receivingDistance) {
            heard(level, event, sourceEntity, projectileOwner, receivingDistance);
        }
    }

    /** Vanilla's vibration ticker every tick, as travel time counts in ticks, and the clear delay. */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (!(blockEntity instanceof VibrationSensorBlockEntity sensor) || !(level instanceof ServerLevel serverLevel)) return;
            VibrationSystem.Ticker.tick(serverLevel, sensor.vibrationData, sensor.vibrationUser);
            sensor.clearIfDue(serverLevel);
        }
    }
}

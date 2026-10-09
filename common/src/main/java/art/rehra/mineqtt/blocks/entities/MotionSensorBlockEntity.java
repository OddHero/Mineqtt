package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.blocks.MotionSensorBlock;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommandResult;
import art.rehra.mineqtt.device.CommandSource;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.PresenceSensor;
import art.rehra.mineqtt.device.types.PresenceSensor.Presence;
import art.rehra.mineqtt.legacy.LegacyNbtV1;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * The Presence Sensor ({@link PresenceSensor}): counts what it detects in front of its face. Its settings, its mob
 * filter among them, and what it saw are in the {@code mineqtt} compound; v1 data is read on load and adopted when the
 * device attaches. The spawn eggs v1 and M1 kept in its filter slots became its filter (M2-12) and stay among its legacy
 * items. Clients get no render data.
 */
public class MotionSensorBlockEntity extends DeviceBlockEntity {
    private final DeviceLink link = new DeviceLink(PresenceSensor.TYPE);
    private Presence presence = Presence.DEFAULT;
    // Whether the sensor counted since it attached; the count it loaded with may be stale.
    private boolean scanned;

    public MotionSensorBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.MOTION_SENSOR_BLOCK.get(), pos, blockState);
    }

    @Override
    public DeviceType deviceType() {
        return PresenceSensor.TYPE;
    }

    @Override
    public DeviceLink deviceLink() {
        return link;
    }

    @Override
    public void writeState(StateWriter out) {
        presence.write(out).put(CommonFields.LOCATION, GlobalPos.of(level.dimension(), worldPosition));
    }

    @Override
    public CommandResult applyCommand(CommandReader in, CommandSource source) {
        Optional<Presence> next = presence.apply(in);
        if (next.isEmpty()) return CommandResult.rejected("not a presence sensor command: " + in.keys());
        if (next.get().equals(presence)) return CommandResult.unchanged();
        presence = next.get();
        setChanged();
        return CommandResult.applied();
    }

    /**
     * The count, which the Raw Link output carries, once the sensor scanned after the attach: until then it is the count
     * saved at the unload, which a retained publish must never repeat (M2 plan D2; retain is switchable since M2-6).
     */
    @Override
    public @Nullable String rawValue() {
        return scanned ? Integer.toString(presence.count()) : null;
    }

    // Counts what the sensor detects now; the count goes to the Raw Link at once, the state as the type allows.
    private void scan(ServerLevel level, BlockState state) {
        AABB area = PresenceSensor.area(worldPosition, state.getValue(MotionSensorBlock.FACING), presence.range());
        int count = 0;
        if (presence.detect().players()) {
            count += level.getEntitiesOfClass(Player.class, area, EntitySelector.NO_SPECTATORS.and(Entity::isAlive)).size();
        }
        if (presence.detect().mobs()) {
            count += level.getEntitiesOfClass(LivingEntity.class, area,
                    entity -> !(entity instanceof Player) && entity.isAlive() && presence.counts(entity.getType())).size();
        }
        Presence next = presence.scanned(count, level.getGameTime());
        boolean firstScan = !scanned;
        scanned = true;
        boolean countChanged = next.count() != presence.count();
        // The first count after the attach is published once to a retaining Raw Link when it confirms the saved one.
        if (firstScan && !countChanged) link.rawValueKnown(this);
        if (next.equals(presence)) return;
        boolean occupancyChanged = next.occupancy() != presence.occupancy();
        presence = next;
        setChanged();
        if (countChanged) link.publishRaw(this, Integer.toString(next.count()));
        if (countChanged || occupancyChanged) link.stateChanged(this);
    }

    @Override
    protected void saveDevice(ValueOutput output) {
        saveSensor(link, presence, output);
    }

    @Override
    protected void loadDevice(ValueInput input) {
        presence = loadSensor(link, input);
    }

    /** A placed copy keeps its settings and filter, and has seen nothing yet. */
    @Override
    protected void placedCopy() {
        presence = presence.placed();
    }

    /**
     * A sensor's data: the link with the sensor as its {@code state}, or v1's keys while its v1 data waits for adoption,
     * so it is still v1 data when it loads again.
     */
    public static void saveSensor(DeviceLink link, Presence presence, ValueOutput output) {
        if (link.awaitingAdoption()) {
            link.saveV1(output);
            LegacyNbtV1.writeMotion(output, presence);
        } else {
            link.save(output).store("state", Presence.CODEC, presence);
        }
    }

    /**
     * Loads a sensor's link and returns the sensor; v1 data, also a placed v1 item's, is kept for adoption. A sensor of
     * v1 or M1 counts the mobs of the spawn eggs in its old filter slots, which its legacy items hold.
     */
    public static Presence loadSensor(DeviceLink link, ValueInput input) {
        Presence loaded = link.load(input, LegacyNbtV1.hasMotionKeys(input))
                .map(data -> data.read("state", Presence.CODEC).orElse(Presence.DEFAULT))
                .orElseGet(() -> LegacyNbtV1.motionState(input));
        return link.loadedVersion() <= DeviceLink.VERSION_M1 ? loaded.withFilter(LegacyNbtV1.eggFilter(link.legacyItems())) : loaded;
    }

    /** A scan every {@link PresenceSensor#SCAN_INTERVAL} ticks once the device is attached. */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (!(blockEntity instanceof MotionSensorBlockEntity sensor) || !(level instanceof ServerLevel serverLevel)) return;
            if (sensor.link.attached() && level.getGameTime() % PresenceSensor.SCAN_INTERVAL == 0) sensor.scan(serverLevel, state);
        }
    }
}

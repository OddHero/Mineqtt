package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.blocks.MotionSensorBlock;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommandResult;
import art.rehra.mineqtt.device.CommandSource;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.PresenceSensor;
import art.rehra.mineqtt.device.types.PresenceSensor.Presence;
import art.rehra.mineqtt.legacy.LegacyNbtV1;
import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.tabs.MotionSensorTab;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Optional;

/**
 * The Presence Sensor ({@link PresenceSensor}): counts what it detects in front of its face. Its settings and what it
 * saw are in the {@code mineqtt} compound; v1 data is read on load and adopted when the device attaches. Slots 0 and
 * 1 edit the Raw Link with v1 topic rules; slots 2 to 6 are the spawn-egg filters for mobs, the slots v1's filter tab
 * showed (v1 read slots 4 to 8 instead).
 */
public class MotionSensorBlockEntity extends BaseMqttBlockEntity implements DeviceHost {
    /** The first of the spawn-egg filter slots. */
    public static final int FIRST_FILTER = 2;
    public static final int NUM_FILTERS = 5;
    // v1 had nine slots. Slots 7 and 8 were never shown; they are kept so nothing v1 left in them is lost, and drop
    // with the rest when the block is broken.
    private static final int CONTAINER_SIZE = INVENTORY_SIZE + NUM_FILTERS;

    private final DeviceLink link = new DeviceLink(PresenceSensor.TYPE);
    private Presence presence = Presence.DEFAULT;

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
        markUpdated();
        return CommandResult.applied();
    }

    /** The count, which the Raw Link output carries. */
    @Override
    public String rawValue() {
        return Integer.toString(presence.count());
    }

    public Presence getPresence() {
        return presence;
    }

    // The contents of the filter slots.
    private List<ItemStack> filters() {
        return getItems().subList(FIRST_FILTER, FIRST_FILTER + NUM_FILTERS);
    }

    /**
     * Whether a mob is counted: without a spawn egg among the filters every mob is, otherwise the mobs of those eggs.
     * Other items filter nothing.
     */
    public static boolean matchesFilters(List<ItemStack> filters, EntityType<?> type) {
        boolean filtered = false;
        for (ItemStack filter : filters) {
            if (!(filter.getItem() instanceof SpawnEggItem)) continue;
            if (SpawnEggItem.spawnsEntity(filter, type)) return true;
            filtered = true;
        }
        return !filtered;
    }

    // Counts what the sensor detects now; the count goes to the Raw Link at once, the state as the type allows.
    private void scan(ServerLevel level, BlockState state) {
        AABB area = PresenceSensor.area(worldPosition, state.getValue(MotionSensorBlock.FACING), presence.range());
        int count = 0;
        if (presence.detect().players()) {
            count += level.getEntitiesOfClass(Player.class, area, EntitySelector.NO_SPECTATORS.and(Entity::isAlive)).size();
        }
        if (presence.detect().mobs()) {
            List<ItemStack> filters = filters();
            count += level.getEntitiesOfClass(LivingEntity.class, area,
                    entity -> !(entity instanceof Player) && entity.isAlive() && matchesFilters(filters, entity.getType())).size();
        }
        Presence next = presence.scanned(count, level.getGameTime());
        if (next.equals(presence)) return;
        boolean countChanged = next.count() != presence.count();
        boolean occupancyChanged = next.occupancy() != presence.occupancy();
        presence = next;
        setChanged();
        if (countChanged) link.publishRaw(this, Integer.toString(next.count()));
        if (countChanged || occupancyChanged) {
            markUpdated();
            link.stateChanged(this);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        saveSensor(link, presence, output);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        presence = loadSensor(link, input);
        // v1 synced the topic to the slots only in its ticker; the adopted link is the slots' topic, not an edit.
        if (link.awaitingAdoption()) topic = getCombinedTopic();
    }

    @Override
    protected String getDefaultTopic() {
        return "";
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.mineqtt.motion_sensor");
    }

    @Override
    public List<MqttTab> getTabs() {
        List<MqttTab> tabs = super.getTabs();
        tabs.add(new MotionSensorTab());
        return tabs;
    }

    @Override
    public int getContainerSize() {
        return CONTAINER_SIZE;
    }

    /**
     * A sensor's keys besides the slots: the link with the sensor as its {@code state}, or v1's own keys while its v1
     * data waits for adoption, so it is still v1 data when it loads again.
     */
    public static void saveSensor(DeviceLink link, Presence presence, ValueOutput output) {
        if (link.awaitingAdoption()) {
            LegacyNbtV1.writeMotion(output, presence);
        } else {
            link.save(output).store("state", Presence.CODEC, presence);
        }
    }

    /** Loads a sensor's link and returns the sensor; v1 data, also a placed v1 item's, is kept for adoption. */
    public static Presence loadSensor(DeviceLink link, ValueInput input) {
        return link.load(input, LegacyNbtV1.hasMotionKeys(input))
                .map(data -> data.read("state", Presence.CODEC).orElse(Presence.DEFAULT))
                .orElseGet(() -> LegacyNbtV1.motionState(input));
    }

    /** Slot edits, and a scan every {@link PresenceSensor#SCAN_INTERVAL} ticks once the device is attached. */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (!(blockEntity instanceof MotionSensorBlockEntity sensor) || !(level instanceof ServerLevel serverLevel)) return;
            sensor.followSlots(serverLevel, sensor.link);
            if (sensor.link.attached() && level.getGameTime() % PresenceSensor.SCAN_INTERVAL == 0) sensor.scan(serverLevel, state);
        }
    }
}

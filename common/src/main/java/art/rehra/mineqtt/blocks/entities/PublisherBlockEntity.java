package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.blocks.RedstonePublisherBlock;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommandResult;
import art.rehra.mineqtt.device.CommandSource;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.RawLink;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.RedstoneInput;
import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.tabs.PublisherValuesTab;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static art.rehra.mineqtt.blocks.RedstonePublisherBlock.POWERED;

/**
 * The Redstone Input ({@link RedstoneInput}): reports the signal its block receives. The signal is in the
 * {@code mineqtt} compound; v1 data is read on load and adopted when the device attaches. Slots 0 and 1 edit the Raw
 * Link with v1 topic rules, slots 2 and 3 its custom on/off payloads (design §6.9).
 */
public class PublisherBlockEntity extends BaseMqttBlockEntity implements DeviceHost {
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

    /** v1's payload for whether the block is powered. */
    @Override
    public @Nullable String rawValue() {
        RawLink raw = link.raw();
        return raw == null ? null : RedstoneInput.rawPayload(getBlockState().getValue(POWERED), raw.customPayloads());
    }

    /**
     * The signal the block receives now. Its state follows; a rising or falling edge also switches the block's
     * {@code powered} property and publishes v1's payload to the Raw Link, and a rising edge is a {@code pulse} event.
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
            if (raw != null) link.publishRaw(this, RedstoneInput.rawPayload(powered, raw.customPayloads()));
            if (powered) link.publishEvent(this, RedstoneInput.pulse(received));
        }
        link.stateChanged(this);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        saveInput(link, signal, output);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        signal = loadInput(link, input, getBlockState().getValue(POWERED));
        // v1 synced the topic to the slots only in its ticker; the adopted link is the slots' topic, not an edit.
        if (link.awaitingAdoption()) topic = getCombinedTopic();
    }

    @Override
    protected String getDefaultTopic() {
        return "";
    }

    @Override
    protected @NotNull Component getDefaultName() {
        return Component.translatable("container.mineqtt.publisher");
    }

    @Override
    public List<MqttTab> getTabs() {
        List<MqttTab> tabs = super.getTabs();
        tabs.add(new PublisherValuesTab());
        return tabs;
    }

    /**
     * An input's keys besides the slots: the link with the signal in its {@code state}. v1 publishers wrote no keys of
     * their own, so v1 data waiting for adoption writes nothing more.
     */
    public static void saveInput(DeviceLink link, int signal, ValueOutput output) {
        if (!link.awaitingAdoption()) link.save(output).child("state").putInt(RedstoneInput.SIGNAL.key(), signal);
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

    /** Slot edits, and the signal once after the attach. */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (!(blockEntity instanceof PublisherBlockEntity input) || !(level instanceof ServerLevel serverLevel)) return;
            input.followSlots(serverLevel, input.link);
            if (!input.signalRead && input.link.attached()) {
                input.signalRead = true;
                input.signalChanged(RedstonePublisherBlock.inputSignal(level, pos));
            }
        }
    }
}

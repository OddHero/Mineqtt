package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.blocks.RedstoneEmitterBlock;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommandResult;
import art.rehra.mineqtt.device.CommandSource;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.RedstoneOutput;
import art.rehra.mineqtt.device.types.RedstoneOutput.Output;
import art.rehra.mineqtt.legacy.LegacyNbtV1;
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

import java.util.List;
import java.util.Optional;

import static art.rehra.mineqtt.blocks.RedstoneEmitterBlock.POWER;

/**
 * The Redstone Output ({@link RedstoneOutput}): emits the level Home Assistant, its group or its Raw Link set. The
 * level is in the {@code mineqtt} compound, and the block's {@code power} property follows it; v1 data, which only had
 * that property, is read on load and adopted when the device attaches. Slots 0 and 1 edit the Raw Link with v1 topic
 * rules, slots 2 and 3 its custom on/off payloads (design §6.9).
 */
public class RedstoneEmitterBlockEntity extends BaseMqttBlockEntity implements DeviceHost {
    private final DeviceLink link = new DeviceLink(RedstoneOutput.TYPE);
    private Output output;

    public RedstoneEmitterBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.REDSTONE_EMITTER_BLOCK_ENTITY.get(), pos, blockState);
        this.output = Output.ofLevel(blockState.getValue(POWER));
    }

    @Override
    public DeviceType deviceType() {
        return RedstoneOutput.TYPE;
    }

    @Override
    public DeviceLink deviceLink() {
        return link;
    }

    @Override
    public void writeState(StateWriter out) {
        output.write(out).put(CommonFields.LOCATION, GlobalPos.of(level.dimension(), worldPosition));
    }

    @Override
    public CommandResult applyCommand(CommandReader in, CommandSource source) {
        Optional<Output> next = output.apply(in);
        if (next.isEmpty()) return CommandResult.rejected("not an output command: " + in.keys());
        if (next.get().equals(output)) return CommandResult.unchanged();
        setOutput(next.get());
        return CommandResult.applied();
    }

    @Override
    public void powerOn() {
        Output next = output.poweredOn();
        if (!next.equals(output)) setOutput(next);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        saveOutput(link, this.output, topic, output);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        output = loadOutput(link, input, getBlockState().getValue(POWER));
        // v1 synced the topic to the slots only in its ticker; the adopted link is the slots' topic, not an edit.
        if (link.awaitingAdoption()) topic = getCombinedTopic();
    }

    @Override
    protected String getDefaultTopic() {
        return "";
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.mineqtt.subscriber");
    }

    @Override
    public List<MqttTab> getTabs() {
        List<MqttTab> tabs = super.getTabs();
        tabs.add(new PublisherValuesTab());
        return tabs;
    }

    /**
     * An output's keys besides the slots: the link with the output as its {@code state}, or v1's own key while its v1
     * data waits for adoption, so it is still v1 data when it loads again.
     */
    public static void saveOutput(DeviceLink link, Output state, String topic, ValueOutput output) {
        if (link.awaitingAdoption()) {
            LegacyNbtV1.writeEmitter(output, topic);
        } else {
            link.save(output).store("state", Output.CODEC, state);
        }
    }

    /**
     * Loads an output's link and returns its state; v1 data, also a placed v1 item's, is kept for adoption and only had
     * the level of its block.
     *
     * @param blockPower the {@code power} property of the block
     */
    public static Output loadOutput(DeviceLink link, ValueInput input, int blockPower) {
        return link.load(input, LegacyNbtV1.hasEmitterKeys(input))
                .map(data -> data.read("state", Output.CODEC).orElse(Output.DEFAULT))
                .orElseGet(() -> Output.ofLevel(blockPower));
    }

    private void setOutput(Output next) {
        output = next;
        setChanged();
        updatePower();
    }

    // The block emits the output's level; changing it keeps this block entity.
    private void updatePower() {
        if (level == null) return;
        BlockState state = getBlockState();
        if (state.getBlock() instanceof RedstoneEmitterBlock emitter && state.getValue(POWER) != output.power()) {
            BlockState powered = state.setValue(POWER, output.power());
            level.setBlock(worldPosition, powered, Block.UPDATE_ALL);
            emitter.updateNeighbors(level, worldPosition, powered);
        }
    }

    /** Slot edits and the block's level. */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (!(blockEntity instanceof RedstoneEmitterBlockEntity emitter) || !(level instanceof ServerLevel serverLevel)) return;
            // As in v1, taking the topic away turns the output off.
            if (emitter.followSlots(serverLevel, emitter.link) && emitter.link.raw() == null && emitter.output.on()) {
                emitter.setOutput(new Output(0, emitter.output.lastLevel(), emitter.output.mode(), emitter.output.powerOn()));
                emitter.link.stateChanged(emitter);
            }
            // Item data, or a level set while the block was another one, is applied once the block is in the world.
            if (state.getValue(POWER) != emitter.output.power()) emitter.updatePower();
        }
    }
}

package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.blocks.HumiditySensorBlock;
import art.rehra.mineqtt.device.DeviceRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A v1 humidity sensor's data until it converts into a Climate Sensor ({@link HumiditySensorBlock}) on the next tick of
 * its server, or at once when it was placed: the base keys (slots, topic, owner, private mode, name) and its own, saved
 * back as they were read. It neither publishes nor subscribes.
 */
public class HumiditySensorBlockEntity extends BaseMqttBlockEntity {
    private double humidity = 50.0;
    private long lastPublishTime;
    private int updateIntervalTicks = 200;

    public HumiditySensorBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.HUMIDITY_SENSOR_BLOCK.get(), pos, blockState);
    }

    @Override
    protected String getDefaultTopic() {
        return "/mineqtt/humidity";
    }

    /**
     * Converts on the next tick instead of resolving its owner: the Climate Sensor resolves it at its attach. A placed
     * block has converted by then, and the scheduled conversion finds this block entity removed.
     */
    @Override
    protected void loadedOnServer(ServerLevel level) {
        DeviceRuntime.scheduleConversion(level, this);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putDouble("Humidity", humidity);
        output.putLong("LastPublishTime", lastPublishTime);
        output.putInt("UpdateIntervalTicks", updateIntervalTicks);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.humidity = input.getDoubleOr("Humidity", 50.0);
        this.lastPublishTime = input.getLongOr("LastPublishTime", 0L);
        this.updateIntervalTicks = input.getIntOr("UpdateIntervalTicks", 200);
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("block.mineqtt.humidity_sensor_block");
    }
}

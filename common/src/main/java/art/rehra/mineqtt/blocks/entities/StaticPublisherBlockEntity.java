package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.blocks.StaticPublisherBlock;
import art.rehra.mineqtt.device.DeviceRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A v1 Static Publisher's data until it converts into a Redstone Input ({@link StaticPublisherBlock}) on the next tick of
 * its server, or at once when it was placed: the base keys (slots, topic, owner, private mode, name) and its own, saved
 * back as they were read. It neither publishes nor subscribes.
 */
public class StaticPublisherBlockEntity extends BaseMqttBlockEntity {
    private String staticMessage = "";
    private boolean wasPowered;
    private String lastPublishStatus = "";

    public StaticPublisherBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.STATIC_PUBLISHER_BLOCK.get(), pos, blockState);
    }

    @Override
    protected String getDefaultTopic() {
        return "/mineqtt/static_publisher";
    }

    /**
     * Converts on the next tick instead of resolving its owner: the Redstone Input resolves it at its attach. A placed
     * block has converted by then, and the scheduled conversion finds this block entity removed.
     */
    @Override
    protected void loadedOnServer(ServerLevel level) {
        DeviceRuntime.scheduleConversion(level, this);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("staticMessage", staticMessage);
        output.putBoolean("wasPowered", wasPowered);
        output.putString("lastPublishStatus", lastPublishStatus);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.staticMessage = input.getStringOr("staticMessage", "");
        this.wasPowered = input.getBooleanOr("wasPowered", false);
        this.lastPublishStatus = input.getStringOr("lastPublishStatus", "");
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("block.mineqtt.static_publisher");
    }
}

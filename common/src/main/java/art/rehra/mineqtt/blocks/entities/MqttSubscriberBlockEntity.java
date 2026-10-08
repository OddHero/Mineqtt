package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.mqtt.ICallbackTarget;
import art.rehra.mineqtt.mqtt.SubscriptionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A v1 block that receives the messages of its combined topic. Only the server block entity subscribes: loading
 * the data picks the topic, and the subscription opens once the block entity is in a server level.
 */
public abstract class MqttSubscriberBlockEntity extends BaseMqttBlockEntity implements ICallbackTarget {

    // The topic this block subscribes while enabled; still saved under its v1 key.
    protected String currentSubscribedTopic = "";

    public MqttSubscriberBlockEntity(BlockEntityType<?> blockEntityType, BlockPos pos, BlockState blockState) {
        super(blockEntityType, pos, blockState);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("currentSubscribedTopic", this.currentSubscribedTopic);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        // The slots decide the topic. A block loading with its chunk has no level yet and subscribes from setLevel;
        // a live one (data replaced by a command) moves its subscription at once.
        updateSubscription(getCombinedTopic());
    }

    /** Chunk load and placement: the first moment the block entity knows its level. */
    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        resubscribe();
    }

    /** A block entity added back to its chunk after a removal. */
    @Override
    public void clearRemoved() {
        super.clearRemoved();
        resubscribe();
    }

    /** Whether the block subscribes its topic; on the server only, so a block may consult the server config. */
    protected boolean listens() {
        return isEnabled();
    }

    protected void updateSubscription(String newCombinedTopic) {
        // Only change subscription if the topic actually changed
        if (!newCombinedTopic.equals(currentSubscribedTopic)) {
            // Unsubscribe from old topic if we were subscribed
            if (!currentSubscribedTopic.isEmpty()) {
                SubscriptionManager.unsubscribe(currentSubscribedTopic, this);
            }

            // Update current subscribed topic
            currentSubscribedTopic = newCombinedTopic;

            // Subscribe to new topic if enabled
            if (listens() && !newCombinedTopic.isEmpty()) {
                SubscriptionManager.subscribe(newCombinedTopic, this);
            }
        }
    }

    /** Subscribes the current topics again; subscribing a topic the block already has does nothing. */
    protected void resubscribe() {
        if (listens() && !currentSubscribedTopic.isEmpty()) {
            SubscriptionManager.subscribe(currentSubscribedTopic, this);
        }
    }

    /** Unload or removal. The topic is kept, so a block entity added back to its chunk subscribes it again. */
    @Override
    public void setRemoved() {
        super.setRemoved();
        if (!currentSubscribedTopic.isEmpty()) {
            SubscriptionManager.unsubscribe(currentSubscribedTopic, this);
        }
    }
}

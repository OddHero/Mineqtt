package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.mqtt.ICallbackTarget;
import art.rehra.mineqtt.mqtt.SubscriptionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public abstract class MqttSubscriberBlockEntity extends BaseMqttBlockEntity implements ICallbackTarget {

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
        this.currentSubscribedTopic = input.getString("currentSubscribedTopic").orElse("");

        // Register subscription intent after loading
        // The actual MQTT subscription will happen when the client connects
        String combinedTopic = getCombinedTopic();
        if (isEnabled() && !combinedTopic.isEmpty()) {
            this.currentSubscribedTopic = combinedTopic;
            SubscriptionManager.subscribe(combinedTopic, this);
        }
    }

    protected void updateSubscription(String newCombinedTopic) {
        // Only change subscription if the topic actually changed
        if (!newCombinedTopic.equals(currentSubscribedTopic)) {
            // Unsubscribe from old topic if we were subscribed
            if (!currentSubscribedTopic.isEmpty()) {
                MineQTT.LOGGER.info("Unsubscribing from: " + currentSubscribedTopic);
                SubscriptionManager.unsubscribe(currentSubscribedTopic, this);
            }

            // Update current subscribed topic
            currentSubscribedTopic = newCombinedTopic;

            // Subscribe to new topic if enabled
            if (isEnabled() && !newCombinedTopic.isEmpty()) {
                MineQTT.LOGGER.info("Subscribing to: " + newCombinedTopic);
                SubscriptionManager.subscribe(newCombinedTopic, this);
            }
        }
    }

    protected void unsubscribeAll() {
        if (!currentSubscribedTopic.isEmpty()) {
            MineQTT.LOGGER.info("Unsubscribing from all: " + currentSubscribedTopic);
            SubscriptionManager.unsubscribe(currentSubscribedTopic, this);
            currentSubscribedTopic = "";
        }
    }

    @Override
    public BlockPos getPosition() {
        return this.worldPosition;
    }

    @Override
    public ResourceKey<Level> getDimension() {
        return this.level != null ? this.level.dimension() : Level.OVERWORLD;
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        unsubscribeAll();
    }
}


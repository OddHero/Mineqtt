package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.mqtt.SubscriptionManager;
import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.tabs.TopicRelayTab;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Block entity for the Topic Relay block.
 * Subscribes to a source MQTT topic, caches the latest message,
 * and republishes it to a target topic on redstone rising-edge trigger.
 */
public class TopicRelayBlockEntity extends MqttSubscriberBlockEntity {

    /** Container slots: 0-1 = target topic config (base + sub) */
    public static final int INVENTORY_SIZE = 4;

    private String sourceTopic = "";
    private String cachedMessage = "";
    private boolean wasPowered = false;
    private boolean hasReceivedMessage = false;
    private String lastRelayStatus = "";
    private String currentSourceSubscribedTopic = "";

    public TopicRelayBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.TOPIC_RELAY_BLOCK.get(), pos, blockState);
        this.items = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
    }

    @Override
    protected String getDefaultTopic() {
        return "/mineqtt/topic_relay";
    }

    @Override
    protected @NotNull Component getDefaultName() {
        return Component.translatable("container.mineqtt.topic_relay");
    }

    public String getSourceTopic() {
        return sourceTopic;
    }

    public void setSourceTopic(String topic) {
        if (topic == null) topic = "";
        if (!this.sourceTopic.equals(topic)) {
            this.sourceTopic = topic;
            updateSourceSubscription();
            markUpdated();
        }
    }

    public String getCachedMessage() {
        return cachedMessage;
    }

    public void setCachedMessage(String message) {
        if (message == null) message = "";
        if (!this.cachedMessage.equals(message)) {
            this.cachedMessage = message;
            this.hasReceivedMessage = !message.isEmpty();
            markUpdated();
        }
    }

    public boolean isWasPowered() {
        return wasPowered;
    }

    public void setWasPowered(boolean wasPowered) {
        this.wasPowered = wasPowered;
    }

    public boolean hasReceivedMessage() {
        return hasReceivedMessage;
    }

    public String getLastRelayStatus() {
        return lastRelayStatus;
    }

    public void setLastRelayStatus(String status) {
        if (status == null) status = "";
        if (!this.lastRelayStatus.equals(status)) {
            this.lastRelayStatus = status;
            this.setChanged();
        }
    }

    /**
     * Returns the target topic (combined from inventory slots).
     */
    public String getTargetTopic() {
        return getCombinedTopic();
    }

    /**
     * Publish the cached message to the target topic if configured.
     * Returns true if a message was relayed.
     */
    public boolean relayCachedMessage() {
        String message = this.cachedMessage;
        if (message.isEmpty()) {
            this.setLastRelayStatus("No cached message to relay");
            return false;
        }

        String targetTopic = getTargetTopic();
        if (targetTopic.isEmpty()) {
            this.setLastRelayStatus("Error – no target topic configured");
            return false;
        }

        try {
            publishToTopic(targetTopic, message);
            this.setLastRelayStatus("Relayed to " + targetTopic);
            MineQTT.LOGGER.debug("[TopicRelay] Relayed '{}' to {} at {}", message, targetTopic, this.getBlockPos());
            return true;
        } catch (Exception e) {
            this.setLastRelayStatus("Error – " + e.getMessage());
            MineQTT.LOGGER.error("[TopicRelay] Failed to relay: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Publish a message to a specific topic using the MQTT client.
     */
    private void publishToTopic(String topic, String message) {
        if (MineQTT.mqttClient != null && MineQTT.mqttClient.getState().isConnected()) {
            MineQTT.mqttClient.publishWith()
                    .topic(topic)
                    .payload(message.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .send();
        } else {
            throw new RuntimeException("MQTT client not connected");
        }
    }

    /**
     * Update the source topic subscription when the source topic changes.
     */
    protected void updateSourceSubscription() {
        String newSourceTopic = this.sourceTopic;

        if (!newSourceTopic.equals(currentSourceSubscribedTopic)) {
            // Unsubscribe from old source topic
            if (!currentSourceSubscribedTopic.isEmpty()) {
                MineQTT.LOGGER.info("[TopicRelay] Unsubscribing from source: " + currentSourceSubscribedTopic);
                SubscriptionManager.unsubscribe(currentSourceSubscribedTopic, this);
            }

            // Update current source subscribed topic
            currentSourceSubscribedTopic = newSourceTopic;

            // Subscribe to new source topic if configured
            if (!newSourceTopic.isEmpty()) {
                MineQTT.LOGGER.info("[TopicRelay] Subscribing to source: " + newSourceTopic);
                SubscriptionManager.subscribe(newSourceTopic, this);
            }
        }
    }

    protected void unsubscribeSource() {
        if (!currentSourceSubscribedTopic.isEmpty()) {
            MineQTT.LOGGER.info("[TopicRelay] Unsubscribing from source: " + currentSourceSubscribedTopic);
            SubscriptionManager.unsubscribe(currentSourceSubscribedTopic, this);
            currentSourceSubscribedTopic = "";
        }
    }

    @Override
    public void onMessageReceived(String topic, String message) {
        // Cache the received message from the source topic
        this.setCachedMessage(message);
        this.setLastRelayStatus("Received from " + topic);
        MineQTT.LOGGER.debug("[TopicRelay] Cached message from {}: {}", topic, message);
    }

    @Override
    public int getContainerSize() {
        return INVENTORY_SIZE;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("sourceTopic", this.sourceTopic);
        output.putString("cachedMessage", this.cachedMessage);
        output.putBoolean("wasPowered", this.wasPowered);
        output.putBoolean("hasReceivedMessage", this.hasReceivedMessage);
        output.putString("lastRelayStatus", this.lastRelayStatus);
        output.putString("currentSourceSubscribedTopic", this.currentSourceSubscribedTopic);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.sourceTopic = input.getString("sourceTopic").orElse("");
        this.cachedMessage = input.getString("cachedMessage").orElse("");
        this.wasPowered = input.getBooleanOr("wasPowered", false);
        this.hasReceivedMessage = input.getBooleanOr("hasReceivedMessage", false);
        this.lastRelayStatus = input.getString("lastRelayStatus").orElse("");
        this.currentSourceSubscribedTopic = input.getString("currentSourceSubscribedTopic").orElse("");

        this.items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, this.items);

        // Restore source subscription on load
        if (!this.sourceTopic.isEmpty()) {
            this.currentSourceSubscribedTopic = this.sourceTopic;
            SubscriptionManager.subscribe(this.sourceTopic, this);
            MineQTT.LOGGER.info("[TopicRelay] Restored source subscription: " + this.sourceTopic);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        unsubscribeSource();
        unsubscribeAll();
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveWithFullMetadata(registries);
    }

    @Override
    public List<MqttTab> getTabs() {
        List<MqttTab> tabs = super.getTabs();
        tabs.add(new TopicRelayTab());
        return tabs;
    }

    public static class Ticker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos blockPos, BlockState blockState, T blockEntity) {
            if (blockEntity instanceof TopicRelayBlockEntity entity && !level.isClientSide()) {
                // Update target topic if items changed
                String newTargetTopic = entity.getTargetTopic();
                if (!newTargetTopic.equals(entity.topic)) {
                    entity.setTopic(newTargetTopic);
                }

                // Update target topic subscription
                String newCombinedTopic = entity.getCombinedTopic();
                if (!newCombinedTopic.equals(entity.currentSubscribedTopic)) {
                    entity.updateSubscription(newCombinedTopic);
                }
            }
        }
    }
}

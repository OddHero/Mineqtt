package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.config.MineQTTConfig;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Hashtable;
import java.util.concurrent.ConcurrentHashMap;

public class RedstoneSubscriberBlock extends MineQTTBlock {

    private ConcurrentHashMap<String, Mqtt3Publish> receivedMessages;

    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        MineQTT.LOGGER.debug("Placing MineQTT block at " + pos);
        subscribeToMqttTopic(MineQTTConfig.getTopicPath("switch"));
        level.scheduleTick(pos, this, 1);
    }

    public RedstoneSubscriberBlock(Properties properties) {
        super(properties);
        receivedMessages = new ConcurrentHashMap<>();
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(POWERED) ? 15 : 0;
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        MineQTT.LOGGER.debug("Ticking MineQTT block at " + pos);
        // Handle received MQTT messages if any
        for (String topic : receivedMessages.keySet()) {
            // Process the message as needed
            handleIncomingMqttMessage(state, level, pos, random, receivedMessages.get(topic));
            // Clear the message after processing
            receivedMessages.remove(topic);
        }
        // Enforce periodic ticking
        level.scheduleTick(pos, this, 1);
    }

    protected void handleIncomingMqttMessage(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, Mqtt3Publish publish) {
        if (level.isClientSide) {
            return; // Only process on the server side
        }
        MineQTT.LOGGER.info("Received MQTT message on topic: " + publish.getTopic() + " with payload: " + (publish.getPayload().isPresent() ? new String(publish.getPayloadAsBytes()) : "null"));
        String topic = publish.getTopic().toString();
        String payload = publish.getPayload().isPresent() ? new String(publish.getPayloadAsBytes()) : "";

        // Example: Toggle the POWERED state based on the payload
        if (payload.equalsIgnoreCase("true")) {
            level.setBlock(pos, state.setValue(POWERED, true), 3);
        } else if (payload.equalsIgnoreCase("false")) {
            level.setBlock(pos, state.setValue(POWERED, false), 3);
        }
    }


    // Subscribe to MQTT topic utility method
    protected void subscribeToMqttTopic(String topic) {
        MineQTT.LOGGER.info("Subscribing to MQTT topic: " + topic);
        if (MineQTT.mqttClient != null && MineQTT.mqttClient.getState().isConnected()) {
            MineQTT.mqttClient.subscribeWith()
                    .topicFilter(topic)
                    .callback(publish -> {
                        receivedMessages.put(topic, publish);
                    })
                    .send();
        } else {
            MineQTT.LOGGER.warn("MQTT client not connected, cannot subscribe to topic: " + topic);
        }
    }
}

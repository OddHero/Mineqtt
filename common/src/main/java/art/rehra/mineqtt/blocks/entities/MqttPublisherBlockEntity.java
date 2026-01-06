package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.charset.StandardCharsets;

public abstract class MqttPublisherBlockEntity extends BaseMqttBlockEntity {

    public MqttPublisherBlockEntity(BlockEntityType<?> blockEntityType, BlockPos pos, BlockState blockState) {
        super(blockEntityType, pos, blockState);
    }

    public void publish(String message) {
        String topic = getCombinedTopic();
        if (isEnabled() && !topic.isEmpty() && MineQTT.mqttClient != null && MineQTT.mqttClient.getState().isConnected()) {
            MineQTT.mqttClient.toAsync().publish(Mqtt3Publish.builder()
                    .topic(topic)
                    .payload(message.getBytes(StandardCharsets.UTF_8))
                    .build());
        }
    }
}

package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.MineQTT;
import com.hivemq.client.internal.mqtt.message.publish.MqttPublish;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jetbrains.annotations.Nullable;

import java.util.Hashtable;
import java.util.function.Consumer;

public class MineQTTBlock extends Block {

    public static final BooleanProperty POWERED;


    public MineQTTBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.getStateDefinition().any().setValue(POWERED, false));
    }



    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(POWERED, false);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }


    @Override
    protected boolean isSignalSource(BlockState state) { return true; }

    // Publishing MQTT message utility method
    protected void sendMqttMessage(String topic, String message) {
        if (MineQTT.mqttClient != null && MineQTT.mqttClient.getState().isConnected()) {
            MineQTT.mqttClient.publishWith()
                    .topic(topic)
                    .payload(message.getBytes())
                    .send();
        }else {
            MineQTT.LOGGER.warn("MQTT client not connected, cannot send message to topic: " + topic);
        }
    }



    static {
        POWERED = BlockStateProperties.POWERED;
    }

}

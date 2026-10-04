package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.SmartPlugBlock;
import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.tabs.PublisherValuesTab;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

import static art.rehra.mineqtt.blocks.SmartPlugBlock.POWER;

public class SmartPlugBlockEntity extends MqttSubscriberBlockEntity {

    private int currentPower = 0;

    public SmartPlugBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.SMART_PLUG_BLOCK_ENTITY.get(), pos, blockState);
    }

    @Override
    protected String getDefaultTopic() {
        return "/mineqtt/smartplug";
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.mineqtt.smart_plug");
    }

    @Override
    public List<MqttTab> getTabs() {
        List<MqttTab> tabs = super.getTabs();
        tabs.add(new PublisherValuesTab());
        return tabs;
    }

    @Override
    public void onMessageReceived(String topic, String message) {
        if (this.level == null) {
            return;
        }

        ItemStack onStack = getItem(2);
        ItemStack offStack = getItem(3);

        String customOn = onStack.isEmpty() ? null : parseItemStackTopic(onStack);
        String customOff = offStack.isEmpty() ? null : parseItemStackTopic(offStack);

        int newPower = -1;

        if (customOn != null || customOff != null) {
            if (message.equals(customOn)) {
                newPower = 15;
            } else if (message.equals(customOff)) {
                newPower = 0;
            }
        }

        if (newPower == -1) {
            if (message.equalsIgnoreCase("ON") || message.equalsIgnoreCase("TRUE") || message.equals("1")) {
                newPower = 15;
            } else if (message.equalsIgnoreCase("OFF") || message.equalsIgnoreCase("FALSE") || message.equals("0")) {
                newPower = 0;
            } else if (message.equalsIgnoreCase("TOGGLE")) {
                newPower = currentPower > 0 ? 0 : 15;
            }
        }

        if (newPower != -1) {
            setPower(newPower);
        }
    }

    private void setPower(int newPower) {
        if (this.level == null) {
            return;
        }

        BlockState currentStateBlock = this.level.getBlockState(this.worldPosition);
        if (currentStateBlock.hasProperty(POWER) && currentStateBlock.getValue(POWER) != newPower) {
            BlockState newStateBlock = currentStateBlock.setValue(POWER, newPower);
            this.level.setBlock(this.worldPosition, newStateBlock, Block.UPDATE_ALL);
            if (this.level.getBlockState(this.worldPosition).getBlock() instanceof SmartPlugBlock plugBlock) {
                plugBlock.updateNeighbors(this.level, this.worldPosition, newStateBlock);
            }
            this.currentPower = newPower;

            // Publish state change
            publishState(newPower);
        }
    }

    private void publishState(int power) {
        if (!isEnabled()) {
            return;
        }

        String combinedTopic = getCombinedTopic();
        if (combinedTopic.isEmpty()) {
            return;
        }

        ItemStack onStack = getItem(2);
        ItemStack offStack = getItem(3);

        String message;
        if (power > 0 && !onStack.isEmpty()) {
            message = parseItemStackTopic(onStack);
        } else if (power == 0 && !offStack.isEmpty()) {
            message = parseItemStackTopic(offStack);
        } else {
            message = power > 0 ? "ON" : "OFF";
        }

        sendMqttMessage(combinedTopic, message);
    }

    private void sendMqttMessage(String topic, String message) {
        if (MineQTT.mqttClient != null && MineQTT.mqttClient.getState().isConnected()) {
            MineQTT.mqttClient.publishWith()
                    .topic(topic)
                    .payload(message.getBytes())
                    .send();
        } else {
            MineQTT.LOGGER.warn("MQTT client not connected, cannot send message to topic: " + topic);
        }
    }

    public static class Ticker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos blockPos, BlockState blockState, T blockEntity) {
            if (blockEntity instanceof SmartPlugBlockEntity plugBlockEntity) {
                String newCombinedTopic = plugBlockEntity.getCombinedTopic();

                boolean topicChanged = !newCombinedTopic.equals(plugBlockEntity.topic);

                if (topicChanged) {
                    plugBlockEntity.setTopic(newCombinedTopic);
                    plugBlockEntity.updateSubscription(newCombinedTopic);

                    if (!plugBlockEntity.isEnabled()) {
                        BlockState currentState = level.getBlockState(blockPos);
                        if (currentState.hasProperty(POWER) && currentState.getValue(POWER) > 0) {
                            BlockState newState = currentState.setValue(POWER, 0);
                            level.setBlock(blockPos, newState, Block.UPDATE_ALL);
                            if (level.getBlockState(blockPos).getBlock() instanceof SmartPlugBlock plugBlock) {
                                plugBlock.updateNeighbors(level, blockPos, newState);
                            }
                            plugBlockEntity.currentPower = 0;
                            MineQTT.LOGGER.info("SmartPlugBlockEntity disabled - no base path item in first slot");
                        }
                    }
                }
            }
        }
    }
}

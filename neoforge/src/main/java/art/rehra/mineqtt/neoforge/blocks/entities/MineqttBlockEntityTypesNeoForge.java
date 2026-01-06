package art.rehra.mineqtt.neoforge.blocks.entities;

import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.blocks.entities.*;
import net.minecraft.world.level.block.entity.BlockEntityType;

public class MineqttBlockEntityTypesNeoForge extends MineqttBlockEntityTypes {

    public static void initBlockEntityTypes() {
        PUBLISHER_BLOCK = registerBlockEntity("publisher_block", () -> new BlockEntityType<>(PublisherBlockEntity::new, MineqttBlocks.PUBLISHER_BLOCK.get()));
        SUBSCRIBER_BLOCK = registerBlockEntity("subscriber_block", () -> new BlockEntityType<>(SubscriberBlockEntity::new, MineqttBlocks.SUBSCRIBER_BLOCK.get()));

        RGB_LED_BLOCK = registerBlockEntity("rgb_led_block", () -> new BlockEntityType<>(RgbLedBlockEntity::new, MineqttBlocks.RGB_LED_BLOCK.get()));
        MOTION_SENSOR_BLOCK = registerBlockEntity("motion_sensor_block", () -> new BlockEntityType<>(MotionSensorBlockEntity::new, MineqttBlocks.MOTION_SENSOR_BLOCK.get()));
        writeRegister();
    }
}

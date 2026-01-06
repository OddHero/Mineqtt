package art.rehra.mineqtt.fabric.blocks.entities;

import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.blocks.entities.*;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;

public class MineqttBlockEntityTypesFabric  extends MineqttBlockEntityTypes {

    public static void initBlockEntityTypes() {
        PUBLISHER_BLOCK = registerBlockEntity("publisher_block", () -> FabricBlockEntityTypeBuilder.create(PublisherBlockEntity::new, MineqttBlocks.PUBLISHER_BLOCK.get()).build());
        SUBSCRIBER_BLOCK = registerBlockEntity("subscriber_block", () -> FabricBlockEntityTypeBuilder.create(SubscriberBlockEntity::new, MineqttBlocks.SUBSCRIBER_BLOCK.get()).build());

        RGB_LED_BLOCK = registerBlockEntity("rgb_led_block", () -> FabricBlockEntityTypeBuilder.create(RgbLedBlockEntity::new, MineqttBlocks.RGB_LED_BLOCK.get()).build());
        MOTION_SENSOR_BLOCK = registerBlockEntity("motion_sensor_block", () -> FabricBlockEntityTypeBuilder.create(MotionSensorBlockEntity::new, MineqttBlocks.MOTION_SENSOR_BLOCK.get()).build());
        writeRegister();
    }
}

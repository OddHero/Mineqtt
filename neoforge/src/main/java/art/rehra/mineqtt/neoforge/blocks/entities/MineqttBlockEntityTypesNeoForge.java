package art.rehra.mineqtt.neoforge.blocks.entities;

import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.blocks.entities.MineqttBlockEntityTypes;
import art.rehra.mineqtt.blocks.entities.PublisherBlockEntity;
import art.rehra.mineqtt.blocks.entities.RgbLedBlockEntity;
import art.rehra.mineqtt.blocks.entities.SubscriberBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

public class MineqttBlockEntityTypesNeoForge extends MineqttBlockEntityTypes {

    public static void initBlockEntityTypes() {
        PUBLISHER_BLOCK = registerBlockEntity("publisher_block", () -> new BlockEntityType<>(PublisherBlockEntity::new, MineqttBlocks.PUBLISHER_BLOCK.get()));
        SUBSCRIBER_BLOCK = registerBlockEntity("subscriber_block", () -> new BlockEntityType<>(SubscriberBlockEntity::new, MineqttBlocks.SUBSCRIBER_BLOCK.get()));

        RGB_LED_BLOCK = registerBlockEntity("rgb_led_block", () -> new BlockEntityType<>(RgbLedBlockEntity::new, MineqttBlocks.RGB_LED_BLOCK.get()));
        writeRegister();
    }
}

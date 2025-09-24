package art.rehra.mineqtt.neoforge.blocks.entities;

import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.blocks.entities.MineqttBlockEntityTypes;
import art.rehra.mineqtt.blocks.entities.PublisherBlockEntity;
import art.rehra.mineqtt.blocks.entities.SubscriberBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

public class MineqttBlockEntityTypesNeoForge extends MineqttBlockEntityTypes {

    public static void initBlockEntityTypes() {
        PUBLISHER_BLOCK = registerBlockEntity("signal_block", () -> new BlockEntityType<>(PublisherBlockEntity::new, MineqttBlocks.PUBLISHER_BLOCK.get()));
        SUBSCRIBER_BLOCK = registerBlockEntity("subscriber_block", () -> new BlockEntityType<>(SubscriberBlockEntity::new, MineqttBlocks.SUBSCRIBER_BLOCK.get()));

        writeRegister();
    }
}

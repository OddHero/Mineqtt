package art.rehra.mineqtt.fabric.blocks.entities;

import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.blocks.entities.*;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;

public class MineqttBlockEntityTypesFabric  extends MineqttBlockEntityTypes {

    public static void initBlockEntityTypes() {
        PUBLISHER_BLOCK = registerBlockEntity("publisher_block", () -> FabricBlockEntityTypeBuilder.create(PublisherBlockEntity::new, MineqttBlocks.PUBLISHER_BLOCK.get()).build());
        REDSTONE_EMITTER_BLOCK_ENTITY = registerBlockEntity("redstone_emitter_block", () -> FabricBlockEntityTypeBuilder.create(RedstoneEmitterBlockEntity::new, MineqttBlocks.REDSTONE_EMITTER_BLOCK.get()).build());

        RGB_LED_BLOCK = registerBlockEntity("rgb_led_block", () -> FabricBlockEntityTypeBuilder.create(RgbLedBlockEntity::new, MineqttBlocks.RGB_LED_BLOCK.get()).build());
        MOTION_SENSOR_BLOCK = registerBlockEntity("motion_sensor_block", () -> FabricBlockEntityTypeBuilder.create(MotionSensorBlockEntity::new, MineqttBlocks.MOTION_SENSOR_BLOCK.get()).build());
        LIGHT_REMOTE_BLOCK = registerBlockEntity("light_remote_block", () -> FabricBlockEntityTypeBuilder.create(LightRemoteBlockEntity::new, MineqttBlocks.LIGHT_REMOTE_BLOCK.get()).build());
        BLOCK_SENSOR_BLOCK = registerBlockEntity("block_sensor_block", () -> FabricBlockEntityTypeBuilder.create(BlockSensorBlockEntity::new, MineqttBlocks.BLOCK_SENSOR_BLOCK.get()).build());
        BLOCK_EMITTER_BLOCK = registerBlockEntity("block_emitter_block", () -> FabricBlockEntityTypeBuilder.create(BlockEmitterBlockEntity::new, MineqttBlocks.BLOCK_EMITTER_BLOCK.get()).build());
        CRON_TIMER_BLOCK = registerBlockEntity("cron_timer", () -> FabricBlockEntityTypeBuilder.create(CronTimerBlockEntity::new, MineqttBlocks.CRON_TIMER_BLOCK.get()).build());
        INTERVAL_TIMER_BLOCK = registerBlockEntity("interval_timer", () -> FabricBlockEntityTypeBuilder.create(IntervalTimerBlockEntity::new, MineqttBlocks.INTERVAL_TIMER_BLOCK.get()).build());
        ALARM_CLOCK_BLOCK = registerBlockEntity("alarm_clock", () -> FabricBlockEntityTypeBuilder.create(AlarmClockBlockEntity::new, MineqttBlocks.ALARM_CLOCK_BLOCK.get()).build());
        GAME_TIME_TIMER_BLOCK = registerBlockEntity("game_time_timer", () -> FabricBlockEntityTypeBuilder.create(GameTimeTimerBlockEntity::new, MineqttBlocks.GAME_TIME_TIMER_BLOCK.get()).build());
        STATIC_PUBLISHER_BLOCK = registerBlockEntity("static_publisher", () -> FabricBlockEntityTypeBuilder.create(StaticPublisherBlockEntity::new, MineqttBlocks.STATIC_PUBLISHER_BLOCK.get()).build());
        DYNAMIC_PUBLISHER_BLOCK = registerBlockEntity("dynamic_publisher", () -> FabricBlockEntityTypeBuilder.create(DynamicPublisherBlockEntity::new, MineqttBlocks.DYNAMIC_PUBLISHER_BLOCK.get()).build());
        TOPIC_RELAY_BLOCK = registerBlockEntity("topic_relay", () -> FabricBlockEntityTypeBuilder.create(TopicRelayBlockEntity::new, MineqttBlocks.TOPIC_RELAY_BLOCK.get()).build());
        writeRegister();
    }
}

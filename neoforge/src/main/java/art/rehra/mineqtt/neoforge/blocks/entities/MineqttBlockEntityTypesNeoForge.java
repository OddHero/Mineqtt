package art.rehra.mineqtt.neoforge.blocks.entities;

import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.blocks.entities.*;
import net.minecraft.world.level.block.entity.BlockEntityType;

public class MineqttBlockEntityTypesNeoForge extends MineqttBlockEntityTypes {

    public static void initBlockEntityTypes() {
        PUBLISHER_BLOCK = registerBlockEntity("publisher_block", () -> new BlockEntityType<>(PublisherBlockEntity::new, MineqttBlocks.PUBLISHER_BLOCK.get()));
        REDSTONE_EMITTER_BLOCK_ENTITY = registerBlockEntity("redstone_emitter_block", () -> new BlockEntityType<>(RedstoneEmitterBlockEntity::new, MineqttBlocks.REDSTONE_EMITTER_BLOCK.get()));

        RGB_LED_BLOCK = registerBlockEntity("rgb_led_block", () -> new BlockEntityType<>(RgbLedBlockEntity::new, MineqttBlocks.RGB_LED_BLOCK.get()));
        MOTION_SENSOR_BLOCK = registerBlockEntity("motion_sensor_block", () -> new BlockEntityType<>(MotionSensorBlockEntity::new, MineqttBlocks.MOTION_SENSOR_BLOCK.get()));
        LIGHT_REMOTE_BLOCK = registerBlockEntity("light_remote_block", () -> new BlockEntityType<>(LightRemoteBlockEntity::new, MineqttBlocks.LIGHT_REMOTE_BLOCK.get()));
        BLOCK_SENSOR_BLOCK = registerBlockEntity("block_sensor_block", () -> new BlockEntityType<>(BlockSensorBlockEntity::new, MineqttBlocks.BLOCK_SENSOR_BLOCK.get()));
        BLOCK_EMITTER_BLOCK = registerBlockEntity("block_emitter_block", () -> new BlockEntityType<>(BlockEmitterBlockEntity::new, MineqttBlocks.BLOCK_EMITTER_BLOCK.get()));
        CRON_TIMER_BLOCK = registerBlockEntity("cron_timer", () -> new BlockEntityType<>(CronTimerBlockEntity::new, MineqttBlocks.CRON_TIMER_BLOCK.get()));
        INTERVAL_TIMER_BLOCK = registerBlockEntity("interval_timer", () -> new BlockEntityType<>(IntervalTimerBlockEntity::new, MineqttBlocks.INTERVAL_TIMER_BLOCK.get()));
        TEMPERATURE_SENSOR_BLOCK = registerBlockEntity("temperature_sensor_block", () -> new BlockEntityType<>(TemperatureSensorBlockEntity::new, MineqttBlocks.TEMPERATURE_SENSOR_BLOCK.get()));
        HUMIDITY_SENSOR_BLOCK = registerBlockEntity("humidity_sensor_block", () -> new BlockEntityType<>(HumiditySensorBlockEntity::new, MineqttBlocks.HUMIDITY_SENSOR_BLOCK.get()));
        ALARM_CLOCK_BLOCK = registerBlockEntity("alarm_clock", () -> new BlockEntityType<>(AlarmClockBlockEntity::new, MineqttBlocks.ALARM_CLOCK_BLOCK.get()));
        GAME_TIME_TIMER_BLOCK = registerBlockEntity("game_time_timer", () -> new BlockEntityType<>(GameTimeTimerBlockEntity::new, MineqttBlocks.GAME_TIME_TIMER_BLOCK.get()));
        STATIC_PUBLISHER_BLOCK = registerBlockEntity("static_publisher", () -> new BlockEntityType<>(StaticPublisherBlockEntity::new, MineqttBlocks.STATIC_PUBLISHER_BLOCK.get()));
        DYNAMIC_PUBLISHER_BLOCK = registerBlockEntity("dynamic_publisher", () -> new BlockEntityType<>(DynamicPublisherBlockEntity::new, MineqttBlocks.DYNAMIC_PUBLISHER_BLOCK.get()));
        TOPIC_RELAY_BLOCK = registerBlockEntity("topic_relay", () -> new BlockEntityType<>(TopicRelayBlockEntity::new, MineqttBlocks.TOPIC_RELAY_BLOCK.get()));
        SMART_PLUG_BLOCK_ENTITY = registerBlockEntity("smart_plug", () -> new BlockEntityType<>(SmartPlugBlockEntity::new, MineqttBlocks.SMART_PLUG_BLOCK.get()));
        writeRegister();
    }
}

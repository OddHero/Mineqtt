package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.function.Supplier;

public class MineqttBlockEntityTypes {

    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(MineQTT.MOD_ID, Registries.BLOCK_ENTITY_TYPE);

    public static RegistrySupplier<BlockEntityType<PublisherBlockEntity>> PUBLISHER_BLOCK;
    public static RegistrySupplier<BlockEntityType<RedstoneEmitterBlockEntity>> REDSTONE_EMITTER_BLOCK_ENTITY;
    public static RegistrySupplier<BlockEntityType<RgbLedBlockEntity>> RGB_LED_BLOCK;
    public static RegistrySupplier<BlockEntityType<MotionSensorBlockEntity>> MOTION_SENSOR_BLOCK;
    public static RegistrySupplier<BlockEntityType<BlockSensorBlockEntity>> BLOCK_SENSOR_BLOCK;
    public static RegistrySupplier<BlockEntityType<BlockEmitterBlockEntity>> BLOCK_EMITTER_BLOCK;
    public static RegistrySupplier<BlockEntityType<LightRemoteBlockEntity>> LIGHT_REMOTE_BLOCK;
    public static RegistrySupplier<BlockEntityType<CronTimerBlockEntity>> CRON_TIMER_BLOCK;
    public static RegistrySupplier<BlockEntityType<IntervalTimerBlockEntity>> INTERVAL_TIMER_BLOCK;
    public static RegistrySupplier<BlockEntityType<TemperatureSensorBlockEntity>> TEMPERATURE_SENSOR_BLOCK;
    public static RegistrySupplier<BlockEntityType<HumiditySensorBlockEntity>> HUMIDITY_SENSOR_BLOCK;
    public static RegistrySupplier<BlockEntityType<AlarmClockBlockEntity>> ALARM_CLOCK_BLOCK;
    public static RegistrySupplier<BlockEntityType<GameTimeTimerBlockEntity>> GAME_TIME_TIMER_BLOCK;
    public static RegistrySupplier<BlockEntityType<StaticPublisherBlockEntity>> STATIC_PUBLISHER_BLOCK;
    public static RegistrySupplier<BlockEntityType<DynamicPublisherBlockEntity>> DYNAMIC_PUBLISHER_BLOCK;
    public static RegistrySupplier<BlockEntityType<TopicRelayBlockEntity>> TOPIC_RELAY_BLOCK;
    public static RegistrySupplier<BlockEntityType<SmartPlugBlockEntity>> SMART_PLUG_BLOCK_ENTITY;

    public static void writeRegister(){
        BLOCK_ENTITIES.register();
    }

    public static <T extends BlockEntityType<?>> RegistrySupplier<T> registerBlockEntity(String name, Supplier<T> blockEntity) {
        return BLOCK_ENTITIES.register(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, name), blockEntity);
    }
}

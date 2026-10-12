package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.device.timer.core.TimerKind;
import art.rehra.mineqtt.legacy.DecommissionedBlockEntity;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MineqttBlockEntityTypes {

    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(MineQTT.MOD_ID, Registries.BLOCK_ENTITY_TYPE);

    public static RegistrySupplier<BlockEntityType<PublisherBlockEntity>> PUBLISHER_BLOCK;
    public static RegistrySupplier<BlockEntityType<RedstoneEmitterBlockEntity>> REDSTONE_EMITTER_BLOCK_ENTITY;
    public static RegistrySupplier<BlockEntityType<RgbLedBlockEntity>> RGB_LED_BLOCK;
    public static RegistrySupplier<BlockEntityType<MotionSensorBlockEntity>> MOTION_SENSOR_BLOCK;
    public static RegistrySupplier<BlockEntityType<BlockSensorBlockEntity>> BLOCK_SENSOR_BLOCK;
    public static RegistrySupplier<BlockEntityType<BlockEmitterBlockEntity>> BLOCK_EMITTER_BLOCK;
    public static RegistrySupplier<BlockEntityType<LightRemoteBlockEntity>> LIGHT_REMOTE_BLOCK;
    public static RegistrySupplier<BlockEntityType<TimerBlockEntity>> CRON_TIMER_BLOCK;
    public static RegistrySupplier<BlockEntityType<TimerBlockEntity>> INTERVAL_TIMER_BLOCK;
    public static RegistrySupplier<BlockEntityType<TemperatureSensorBlockEntity>> TEMPERATURE_SENSOR_BLOCK;
    public static RegistrySupplier<BlockEntityType<HumiditySensorBlockEntity>> HUMIDITY_SENSOR_BLOCK;
    public static RegistrySupplier<BlockEntityType<TimerBlockEntity>> ALARM_CLOCK_BLOCK;
    public static RegistrySupplier<BlockEntityType<TimerBlockEntity>> GAME_TIME_TIMER_BLOCK;
    public static RegistrySupplier<BlockEntityType<StaticPublisherBlockEntity>> STATIC_PUBLISHER_BLOCK;
    public static RegistrySupplier<BlockEntityType<DecommissionedBlockEntity>> DYNAMIC_PUBLISHER_BLOCK;
    public static RegistrySupplier<BlockEntityType<DecommissionedBlockEntity>> TOPIC_RELAY_BLOCK;
    public static RegistrySupplier<BlockEntityType<SmartPlugBlockEntity>> SMART_PLUG_BLOCK_ENTITY;
    public static RegistrySupplier<BlockEntityType<TerminalBlockEntity>> TERMINAL_BLOCK;
    public static RegistrySupplier<BlockEntityType<DisplayPanelBlockEntity>> DISPLAY_PANEL;

    /**
     * Registers every block entity type (M2 plan D13). {@code MineQTT.init()} calls it after {@code MineqttBlocks.init()}:
     * Architectury registers eagerly on Fabric, so each type's block must already be registered. The ids are the ones
     * the loader modules used before, which saved worlds hold (the Redstone Input's {@code publisher_block} among them).
     */
    public static void init() {
        MineQTT.LOGGER.info("Registering MineQTT Block Entities");

        PUBLISHER_BLOCK = registerBlockEntity("publisher_block", PublisherBlockEntity::new, MineqttBlocks.PUBLISHER_BLOCK);
        REDSTONE_EMITTER_BLOCK_ENTITY = registerBlockEntity("redstone_emitter_block", RedstoneEmitterBlockEntity::new, MineqttBlocks.REDSTONE_EMITTER_BLOCK);
        RGB_LED_BLOCK = registerBlockEntity("rgb_led_block", RgbLedBlockEntity::new, MineqttBlocks.RGB_LED_BLOCK);
        MOTION_SENSOR_BLOCK = registerBlockEntity("motion_sensor_block", MotionSensorBlockEntity::new, MineqttBlocks.MOTION_SENSOR_BLOCK);
        LIGHT_REMOTE_BLOCK = registerBlockEntity("light_remote_block", LightRemoteBlockEntity::new, MineqttBlocks.LIGHT_REMOTE_BLOCK);
        BLOCK_SENSOR_BLOCK = registerBlockEntity("block_sensor_block", BlockSensorBlockEntity::new, MineqttBlocks.BLOCK_SENSOR_BLOCK);
        BLOCK_EMITTER_BLOCK = registerBlockEntity("block_emitter_block", BlockEmitterBlockEntity::new, MineqttBlocks.BLOCK_EMITTER_BLOCK);
        CRON_TIMER_BLOCK = registerBlockEntity("cron_timer", (pos, state) -> new TimerBlockEntity(CRON_TIMER_BLOCK.get(), TimerKind.CRON, pos, state), MineqttBlocks.CRON_TIMER_BLOCK);
        INTERVAL_TIMER_BLOCK = registerBlockEntity("interval_timer", (pos, state) -> new TimerBlockEntity(INTERVAL_TIMER_BLOCK.get(), TimerKind.INTERVAL, pos, state), MineqttBlocks.INTERVAL_TIMER_BLOCK);
        TEMPERATURE_SENSOR_BLOCK = registerBlockEntity("temperature_sensor_block", TemperatureSensorBlockEntity::new, MineqttBlocks.TEMPERATURE_SENSOR_BLOCK);
        HUMIDITY_SENSOR_BLOCK = registerBlockEntity("humidity_sensor_block", HumiditySensorBlockEntity::new, MineqttBlocks.HUMIDITY_SENSOR_BLOCK);
        ALARM_CLOCK_BLOCK = registerBlockEntity("alarm_clock", (pos, state) -> new TimerBlockEntity(ALARM_CLOCK_BLOCK.get(), TimerKind.ALARM, pos, state), MineqttBlocks.ALARM_CLOCK_BLOCK);
        GAME_TIME_TIMER_BLOCK = registerBlockEntity("game_time_timer", (pos, state) -> new TimerBlockEntity(GAME_TIME_TIMER_BLOCK.get(), TimerKind.GAME_TIME, pos, state), MineqttBlocks.GAME_TIME_TIMER_BLOCK);
        STATIC_PUBLISHER_BLOCK = registerBlockEntity("static_publisher", StaticPublisherBlockEntity::new, MineqttBlocks.STATIC_PUBLISHER_BLOCK);
        // Decommissioned (M2-21): both keep their ids, so saved blocks load into the inert block entity with their items.
        DYNAMIC_PUBLISHER_BLOCK = registerBlockEntity("dynamic_publisher", (pos, state) -> new DecommissionedBlockEntity(DYNAMIC_PUBLISHER_BLOCK.get(), pos, state),
                MineqttBlocks.DYNAMIC_PUBLISHER_BLOCK);
        TOPIC_RELAY_BLOCK = registerBlockEntity("topic_relay", (pos, state) -> new DecommissionedBlockEntity(TOPIC_RELAY_BLOCK.get(), pos, state),
                MineqttBlocks.TOPIC_RELAY_BLOCK);
        SMART_PLUG_BLOCK_ENTITY = registerBlockEntity("smart_plug", SmartPlugBlockEntity::new, MineqttBlocks.SMART_PLUG_BLOCK);
        TERMINAL_BLOCK = registerBlockEntity("terminal_block", TerminalBlockEntity::new, MineqttBlocks.TERMINAL_BLOCK);
        DISPLAY_PANEL = registerBlockEntity("display_panel", DisplayPanelBlockEntity::new, MineqttBlocks.DISPLAY_PANEL);
        BLOCK_ENTITIES.register();
    }

    /** Every MineQTT block entity type; the entries resolve once {@link #init()} has run (on NeoForge: once the registry event has). */
    public static Iterable<RegistrySupplier<BlockEntityType<?>>> allTypes() {
        return BLOCK_ENTITIES;
    }

    /**
     * The types whose block entities have configuration slots ({@link BaseMqttBlockEntity}): every type but the devices',
     * which have no container since M2-12, the decommissioned blocks', which have none since M2-21, and the Bridge
     * Console's, which never had one (M2-23). Resolves like
     * {@link #allTypes()}.
     */
    public static List<BlockEntityType<?>> slottedTypes() {
        Set<BlockEntityType<?>> noSlots = Set.of(PUBLISHER_BLOCK.get(), REDSTONE_EMITTER_BLOCK_ENTITY.get(), RGB_LED_BLOCK.get(), MOTION_SENSOR_BLOCK.get(),
                SMART_PLUG_BLOCK_ENTITY.get(), TEMPERATURE_SENSOR_BLOCK.get(), LIGHT_REMOTE_BLOCK.get(), DYNAMIC_PUBLISHER_BLOCK.get(), TOPIC_RELAY_BLOCK.get(), TERMINAL_BLOCK.get(),
                DISPLAY_PANEL.get(), INTERVAL_TIMER_BLOCK.get(), GAME_TIME_TIMER_BLOCK.get(), ALARM_CLOCK_BLOCK.get(), CRON_TIMER_BLOCK.get(),
                BLOCK_SENSOR_BLOCK.get(), BLOCK_EMITTER_BLOCK.get());
        List<BlockEntityType<?>> slotted = new ArrayList<>();
        for (RegistrySupplier<BlockEntityType<?>> type : BLOCK_ENTITIES) {
            if (!noSlots.contains(type.get())) slotted.add(type.get());
        }
        return slotted;
    }

    private static <T extends BlockEntity> RegistrySupplier<BlockEntityType<T>> registerBlockEntity(String name, BlockEntityType.BlockEntitySupplier<T> factory,
                                                                                                    RegistrySupplier<Block> block) {
        return BLOCK_ENTITIES.register(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, name), () -> new BlockEntityType<>(factory, Set.of(block.get())));
    }
}

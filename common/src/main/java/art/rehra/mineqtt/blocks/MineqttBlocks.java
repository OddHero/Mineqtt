package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.MineQTT;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.function.Supplier;

public class MineqttBlocks {

    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(MineQTT.MOD_ID, Registries.BLOCK);

    public static RegistrySupplier<Block> TERMINAL_BLOCK;
    public static RegistrySupplier<Block> PUBLISHER_BLOCK;
    public static RegistrySupplier<Block> REDSTONE_EMITTER_BLOCK;
    public static RegistrySupplier<Block> RGB_LED_BLOCK;
    public static RegistrySupplier<Block> MOTION_SENSOR_BLOCK;
    public static RegistrySupplier<Block> BLOCK_SENSOR_BLOCK;
    public static RegistrySupplier<Block> BLOCK_EMITTER_BLOCK;
    public static RegistrySupplier<Block> LIGHT_REMOTE_BLOCK;
    public static RegistrySupplier<Block> CRON_TIMER_BLOCK;
    public static RegistrySupplier<Block> INTERVAL_TIMER_BLOCK;
    public static RegistrySupplier<Block> ALARM_CLOCK_BLOCK;
    public static RegistrySupplier<Block> GAME_TIME_TIMER_BLOCK;
    public static RegistrySupplier<Block> STATIC_PUBLISHER_BLOCK;
    public static RegistrySupplier<Block> DYNAMIC_PUBLISHER_BLOCK;
    public static RegistrySupplier<Block> TOPIC_RELAY_BLOCK;
    public static void init() {
        MineQTT.LOGGER.info("Registering MineQTT Blocks");
        // Register blocks here

        TERMINAL_BLOCK = registerBlock("terminal_block", () -> new Block(baseProperties("terminal_block").requiresCorrectToolForDrops().strength(3.5f)));
        PUBLISHER_BLOCK = registerBlock("redstone_publisher_block", () -> new RedstonePublisherBlock(baseProperties("redstone_publisher_block").requiresCorrectToolForDrops().strength(3.5f)));
        REDSTONE_EMITTER_BLOCK = registerBlock("redstone_emitter_block", () -> new RedstoneEmitterBlock(baseProperties("redstone_emitter_block").requiresCorrectToolForDrops().strength(3.5f)));
        RGB_LED_BLOCK = registerBlock("rgb_led_block", () -> new RgbLedBlock(baseProperties("rgb_led_block").requiresCorrectToolForDrops().strength(3.5f)));
        MOTION_SENSOR_BLOCK = registerBlock("motion_sensor_block", () -> new MotionSensorBlock(baseProperties("motion_sensor_block").requiresCorrectToolForDrops().strength(3.5f)));
        LIGHT_REMOTE_BLOCK = registerBlock("light_remote_block", () -> new LightRemoteBlock(baseProperties("light_remote_block").requiresCorrectToolForDrops().strength(3.5f)));
        BLOCK_SENSOR_BLOCK = registerBlock("block_sensor_block", () -> new BlockSensorBlock(baseProperties("block_sensor_block").requiresCorrectToolForDrops().strength(3.5f)));
        BLOCK_EMITTER_BLOCK = registerBlock("block_emitter_block", () -> new BlockEmitterBlock(baseProperties("block_emitter_block").requiresCorrectToolForDrops().strength(3.5f)));
        CRON_TIMER_BLOCK = registerBlock("cron_timer", () -> new CronTimerBlock(baseProperties("cron_timer").requiresCorrectToolForDrops().strength(3.5f)));
        INTERVAL_TIMER_BLOCK = registerBlock("interval_timer", () -> new IntervalTimerBlock(baseProperties("interval_timer").requiresCorrectToolForDrops().strength(3.5f)));
        ALARM_CLOCK_BLOCK = registerBlock("alarm_clock", () -> new AlarmClockBlock(baseProperties("alarm_clock").requiresCorrectToolForDrops().strength(3.5f)));
        GAME_TIME_TIMER_BLOCK = registerBlock("game_time_timer", () -> new GameTimeTimerBlock(baseProperties("game_time_timer").requiresCorrectToolForDrops().strength(3.5f)));
        STATIC_PUBLISHER_BLOCK = registerBlock("static_publisher", () -> new StaticPublisherBlock(baseProperties("static_publisher").requiresCorrectToolForDrops().strength(3.5f)));
        DYNAMIC_PUBLISHER_BLOCK = registerBlock("dynamic_publisher", () -> new DynamicPublisherBlock(baseProperties("dynamic_publisher").requiresCorrectToolForDrops().strength(3.5f)));
        TOPIC_RELAY_BLOCK = registerBlock("topic_relay", () -> new TopicRelayBlock(baseProperties("topic_relay").requiresCorrectToolForDrops().strength(3.5f)));
        BLOCKS.register();

        // Register interaction events after blocks are initialized
        registerInteractionEvents();
    }

    private static void registerInteractionEvents() {
        MineQTT.LOGGER.info("Registering block interaction events");
        BaseMqttBlock.registerInteractionEvents();
    }

    public static RegistrySupplier<Block> registerBlock(String name, Supplier<Block> block) {
        return BLOCKS.register(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, name), block);
    }

    public static Block.Properties baseProperties(String name) {
        return BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, name)));
    }
}

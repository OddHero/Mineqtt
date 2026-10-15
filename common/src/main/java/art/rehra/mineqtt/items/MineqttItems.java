package art.rehra.mineqtt.items;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.legacy.DecommissionedBlock;
import art.rehra.mineqtt.legacy.LegacyConverterBlock;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

import java.util.function.Supplier;

public class MineqttItems {

    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(MineQTT.MOD_ID, Registries.ITEM);

    public static RegistrySupplier<Item> Cyberdeck;
    public static RegistrySupplier<Item> TerminalBlock;
    public static RegistrySupplier<Item> RedstonePublisherBlock;
    public static RegistrySupplier<Item> RedstoneEmitterBlock;
    public static RegistrySupplier<Item> RgbLedBlock;
    public static RegistrySupplier<Item> MotionSensorBlock;
    public static RegistrySupplier<Item> BlockSensorBlock;
    public static RegistrySupplier<Item> BlockEmitterBlock;
    public static RegistrySupplier<Item> LightRemoteBlock;
    public static RegistrySupplier<Item> CronTimerBlock;
    public static RegistrySupplier<Item> IntervalTimerBlock;
    public static RegistrySupplier<Item> TemperatureSensorBlock;
    public static RegistrySupplier<Item> HumiditySensorBlock;
    public static RegistrySupplier<Item> AlarmClockBlock;
    public static RegistrySupplier<Item> GameTimeTimerBlock;
    public static RegistrySupplier<Item> StaticPublisherBlock;
    public static RegistrySupplier<Item> DynamicPublisherBlock;
    public static RegistrySupplier<Item> TopicRelayBlock;
    public static RegistrySupplier<Item> SmartPlugBlock;
    public static RegistrySupplier<Item> DisplayPanel;
    public static RegistrySupplier<Item> NetworkChip;
    public static void init(){
        MineQTT.LOGGER.info("Registering MineQTT Items");
        Cyberdeck = registerItem("cyberdeck", () -> new CyberdeckItem(baseProperties("cyberdeck")));
        // Devices have a DeviceBlockItem, which keeps a player within limits.maxDevicesPerPlayer (M2-26).
        TerminalBlock = registerItem("terminal_block", () -> new BlockItem(MineqttBlocks.TERMINAL_BLOCK.get(),baseProperties("terminal_block")));
        RedstonePublisherBlock = registerItem("redstone_publisher_block", () -> new DeviceBlockItem(MineqttBlocks.PUBLISHER_BLOCK.get(),baseProperties("redstone_publisher_block")));
        RedstoneEmitterBlock = registerItem("redstone_emitter_block", () -> new DeviceBlockItem(MineqttBlocks.REDSTONE_EMITTER_BLOCK.get(), baseProperties("redstone_emitter_block")));

        RgbLedBlock = registerItem("rgb_led_block", () -> new DeviceBlockItem(MineqttBlocks.RGB_LED_BLOCK.get(),baseProperties("rgb_led_block")));

        MotionSensorBlock = registerItem("motion_sensor_block", () -> new DeviceBlockItem(MineqttBlocks.MOTION_SENSOR_BLOCK.get(), baseProperties("motion_sensor_block")));

        LightRemoteBlock = registerItem("light_remote_block", () -> new DeviceBlockItem(MineqttBlocks.LIGHT_REMOTE_BLOCK.get(), baseProperties("light_remote_block")));

        BlockSensorBlock = registerItem("block_sensor_block", () -> new DeviceBlockItem(MineqttBlocks.BLOCK_SENSOR_BLOCK.get(), baseProperties("block_sensor_block")));
        BlockEmitterBlock = registerItem("block_emitter_block", () -> new DeviceBlockItem(MineqttBlocks.BLOCK_EMITTER_BLOCK.get(), baseProperties("block_emitter_block")));
        CronTimerBlock = registerItem("cron_timer", () -> new DeviceBlockItem(MineqttBlocks.CRON_TIMER_BLOCK.get(), baseProperties("cron_timer")));
        IntervalTimerBlock = registerItem("interval_timer", () -> new DeviceBlockItem(MineqttBlocks.INTERVAL_TIMER_BLOCK.get(), baseProperties("interval_timer")));
        TemperatureSensorBlock = registerItem("temperature_sensor_block", () -> new DeviceBlockItem(MineqttBlocks.TEMPERATURE_SENSOR_BLOCK.get(), baseProperties("temperature_sensor_block")));
        // Converts into the Climate Sensor in any inventory that ticks it (M2-19).
        HumiditySensorBlock = registerItem("humidity_sensor_block", () -> new LegacyConverterBlock.ConverterItem((LegacyConverterBlock) MineqttBlocks.HUMIDITY_SENSOR_BLOCK.get(),
                baseProperties("humidity_sensor_block")));
        AlarmClockBlock = registerItem("alarm_clock", () -> new DeviceBlockItem(MineqttBlocks.ALARM_CLOCK_BLOCK.get(), baseProperties("alarm_clock")));
        GameTimeTimerBlock = registerItem("game_time_timer", () -> new DeviceBlockItem(MineqttBlocks.GAME_TIME_TIMER_BLOCK.get(), baseProperties("game_time_timer")));
        // Converts into the Redstone Input in any inventory that ticks it (M2-20).
        StaticPublisherBlock = registerItem("static_publisher", () -> new LegacyConverterBlock.ConverterItem((LegacyConverterBlock) MineqttBlocks.STATIC_PUBLISHER_BLOCK.get(),
                baseProperties("static_publisher")));
        // Decommissioned (M2-21): placed, they only wait to be broken for their parts, as their tooltip says.
        DynamicPublisherBlock = registerItem("dynamic_publisher", () -> new BlockItem(MineqttBlocks.DYNAMIC_PUBLISHER_BLOCK.get(), retiredProperties("dynamic_publisher")));
        TopicRelayBlock = registerItem("topic_relay", () -> new BlockItem(MineqttBlocks.TOPIC_RELAY_BLOCK.get(), retiredProperties("topic_relay")));
        SmartPlugBlock = registerItem("smart_plug", () -> new DeviceBlockItem(MineqttBlocks.SMART_PLUG_BLOCK.get(), baseProperties("smart_plug")));
        DisplayPanel = registerItem("display_panel", () -> new DeviceBlockItem(MineqttBlocks.DISPLAY_PANEL.get(), baseProperties("display_panel")));
        // The one crafting gate of the whole line (M2 plan D8).
        NetworkChip = registerItem("network_chip", () -> new Item(baseProperties("network_chip")));
        ITEMS.register();
    }

    public static RegistrySupplier<Item> registerItem(String name, Supplier<Item> item) {
        return ITEMS.register(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, name), item);
    }

    public static Item.Properties baseProperties(String name) {
        //? if >=1.21.2 {
        return new Item.Properties().setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, name)));
        //?} else {
        /*return new Item.Properties(); // items know their id from 1.21.2 on
        *///?}
    }

    // A decommissioned block's item: its tooltip says it is retired and what replaced it.
    private static Item.Properties retiredProperties(String name) {
        return baseProperties(name).component(DataComponents.LORE, DecommissionedBlock.lore(name));
    }
}

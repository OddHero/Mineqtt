package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.MineQTT;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.function.Supplier;

public class MineqttBlocks {

    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(MineQTT.MOD_ID, Registries.BLOCK);

    public static RegistrySupplier<Block> TERMINAL_BLOCK;
    public static RegistrySupplier<Block> PUBLISHER_BLOCK;
    public static RegistrySupplier<Block> SUBSCRIBER_BLOCK;
    public static RegistrySupplier<Block> RGB_LED_BLOCK;
    public static RegistrySupplier<Block> MOTION_SENSOR_BLOCK;
    public static void init() {
        MineQTT.LOGGER.info("Registering MineQTT Blocks");
        // Register blocks here

        TERMINAL_BLOCK = registerBlock("terminal_block", () -> new Block(baseProperties("terminal_block").requiresCorrectToolForDrops().strength(3.5f)));
        PUBLISHER_BLOCK = registerBlock("redstone_publisher_block", () -> new RedstonePublisherBlock(baseProperties("redstone_publisher_block").requiresCorrectToolForDrops().strength(3.5f)));
        SUBSCRIBER_BLOCK = registerBlock("redstone_subscriber_block", () -> new RedstoneSubscriberBlock(baseProperties("redstone_subscriber_block").requiresCorrectToolForDrops().strength(3.5f)));
        RGB_LED_BLOCK = registerBlock("rgb_led_block", () -> new RgbLedBlock(baseProperties("rgb_led_block").requiresCorrectToolForDrops().strength(3.5f)));
        MOTION_SENSOR_BLOCK = registerBlock("motion_sensor_block", () -> new MotionSensorBlock(baseProperties("motion_sensor_block").requiresCorrectToolForDrops().strength(3.5f)));
        BLOCKS.register();

        // Register interaction events after blocks are initialized
        registerInteractionEvents();
    }

    private static void registerInteractionEvents() {
        MineQTT.LOGGER.info("Registering block interaction events");
        BaseSubscriberBlock.registerEvents();

        // Register for RedstoneSubscriberBlock
        dev.architectury.event.events.common.InteractionEvent.RIGHT_CLICK_BLOCK.register((player, hand, pos, face) -> {
            var block = player.level().getBlockState(pos).getBlock();
            if (block instanceof RedstoneSubscriberBlock rsb) {
                return rsb.click(player, hand, pos, face);
            }
            if (block instanceof RedstonePublisherBlock rpb) {
                return rpb.click(player, hand, pos, face);
            }
            return net.minecraft.world.InteractionResult.PASS;
        });
    }

    public static RegistrySupplier<Block> registerBlock(String name, Supplier<Block> block) {
        return BLOCKS.register(ResourceLocation.fromNamespaceAndPath(MineQTT.MOD_ID, name), block);
    }

    public static Block.Properties baseProperties(String name) {
        return BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(MineQTT.MOD_ID, name)));
    }
}

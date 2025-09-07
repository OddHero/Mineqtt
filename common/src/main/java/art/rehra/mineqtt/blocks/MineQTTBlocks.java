package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.MineQTT;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.function.Supplier;

public class MineQTTBlocks {

    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(MineQTT.MOD_ID, Registries.BLOCK);

    public static RegistrySupplier<Block> TerminalBlock;
    public static RegistrySupplier<Block> RedstonePublisherBlock;
    public static RegistrySupplier<Block> RedstoneSubscriberBlock;

    public static void init() {
        MineQTT.LOGGER.info("Registering MineQTT Blocks");
        // Register blocks here

        TerminalBlock = registerBlock("terminal_block", () -> new Block(baseProperties("terminal_block").requiresCorrectToolForDrops().strength(3.5f)));
        RedstonePublisherBlock = registerBlock("redstone_publisher_block", () -> new RedstonePublisherBlock(baseProperties("redstone_publisher_block").requiresCorrectToolForDrops().strength(3.5f)));
        RedstoneSubscriberBlock = registerBlock("redstone_subscriber_block", () -> new RedstoneSubscriberBlock(baseProperties("redstone_subscriber_block").requiresCorrectToolForDrops().strength(3.5f)));

        BLOCKS.register();
    }

    public static RegistrySupplier<Block> registerBlock(String name, Supplier<Block> block) {
        return BLOCKS.register(ResourceLocation.fromNamespaceAndPath(MineQTT.MOD_ID, name), block);
    }

    public static Block.Properties baseProperties(String name) {
        return BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(MineQTT.MOD_ID, name)));
    }
}

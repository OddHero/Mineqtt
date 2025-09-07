package art.rehra.mineqtt.items;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.MineQTTBlocks;
import art.rehra.mineqtt.tabs.MineQTTTabs;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

import java.util.function.Supplier;

public class MineQTTItems {

    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(MineQTT.MOD_ID, Registries.ITEM);

    public static RegistrySupplier<Item> Cyberdeck;
    public static RegistrySupplier<Item> TerminalBlock;
    public static RegistrySupplier<Item> RedstonePublisherBlock;
    public static RegistrySupplier<Item> RedstoneSubscriberBlock;

    public static void init(){
        MineQTT.LOGGER.info("Registering MineQTT Items");
        Cyberdeck = registerItem("cyberdeck", () -> new Item(baseProperties("cyberdeck").arch$tab(MineQTTTabs.MINEQTT_TAB)));
        TerminalBlock = registerItem("terminal_block", () -> new BlockItem(MineQTTBlocks.TerminalBlock.get(),baseProperties("terminal_block").arch$tab(MineQTTTabs.MINEQTT_TAB)));
        RedstonePublisherBlock = registerItem("redstone_publisher_block", () -> new BlockItem(MineQTTBlocks.RedstonePublisherBlock.get(),baseProperties("redstone_publisher_block").arch$tab(MineQTTTabs.MINEQTT_TAB)));
        RedstoneSubscriberBlock = registerItem("redstone_subscriber_block", () -> new BlockItem(MineQTTBlocks.RedstoneSubscriberBlock.get(),baseProperties("redstone_subscriber_block").arch$tab(MineQTTTabs.MINEQTT_TAB)));

        ITEMS.register();
    }

    public static RegistrySupplier<Item> registerItem(String name, Supplier<Item> item) {
        return ITEMS.register(ResourceLocation.fromNamespaceAndPath(MineQTT.MOD_ID, name), item);
    }

    public static Item.Properties baseProperties(String name) {
        return new Item.Properties().setId(ResourceKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(MineQTT.MOD_ID, name)));
    }
}

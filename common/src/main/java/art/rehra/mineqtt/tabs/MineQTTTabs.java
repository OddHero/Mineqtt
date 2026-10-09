package art.rehra.mineqtt.tabs;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.items.MineqttItems;
import art.rehra.mineqtt.registry.Catalog;
import dev.architectury.registry.CreativeTabRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

public class MineQTTTabs {

    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(MineQTT.MOD_ID, Registries.CREATIVE_MODE_TAB);

    public static RegistrySupplier<CreativeModeTab> MINEQTT_TAB;

    public static void init() {
        MineQTT.LOGGER.info("Registering MineQTT Creative Mode Tabs");

        MINEQTT_TAB = TABS.register("mineqtt_tab", () -> CreativeTabRegistry.create(builder -> builder
                .title(Component.translatable("category.mineqtt_tab"))
                .icon(() -> new ItemStack(MineqttItems.Cyberdeck.get()))
                // The Catalog decides what the tab shows and in which order; its ids resolve once the items exist.
                .displayItems((parameters, output) -> Catalog.ENTRIES.stream().filter(Catalog.Entry::creative)
                        .forEach(entry -> output.accept(BuiltInRegistries.ITEM.getOptional(entry.id())
                                .orElseThrow(() -> new IllegalStateException("Catalog entry " + entry.id() + " is not a registered item")))))));

        TABS.register();
    }
}

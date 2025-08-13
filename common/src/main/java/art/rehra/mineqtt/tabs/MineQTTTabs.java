package art.rehra.mineqtt.tabs;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.items.MineQTTItems;
import dev.architectury.registry.CreativeTabRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

public class MineQTTTabs {

    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(MineQTT.MOD_ID, Registries.CREATIVE_MODE_TAB);

    public static RegistrySupplier<CreativeModeTab> MINEQTT_TAB;

    public static void init() {
        MineQTT.LOGGER.info("Registering MineQTT Creative Mode Tabs");

        MINEQTT_TAB = TABS.register("mineqtt_tab", () -> CreativeTabRegistry.create(Component.translatable("category.mineqtt_tab"), () -> new ItemStack(MineQTTItems.Cyberdeck.get())));

        TABS.register();
    }
}

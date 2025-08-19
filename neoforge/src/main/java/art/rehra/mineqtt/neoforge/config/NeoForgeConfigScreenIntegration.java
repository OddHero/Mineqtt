package art.rehra.mineqtt.neoforge.config;

import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * NeoForge config screen integration for MineQTT.
 * This registers our ClothConfig screen with NeoForge's mod config system.
 */
public class NeoForgeConfigScreenIntegration {
    public static void register(ModContainer container) {
        // Register a simple config screen factory with NeoForge's mod menu system
        container.registerExtensionPoint(IConfigScreenFactory.class,
            (client, parent) -> new MineQTTConfigScreen(parent)
        );
    }
}

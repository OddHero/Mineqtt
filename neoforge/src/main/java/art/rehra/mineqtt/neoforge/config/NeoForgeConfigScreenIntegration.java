package art.rehra.mineqtt.neoforge.config;

import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * NeoForge config screen integration for MineQTT.
 * This registers the generated mineqtt.toml screen with NeoForge's mod list.
 */
public class NeoForgeConfigScreenIntegration {
    public static void register(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, MineQTTConfigScreen::createConfigScreen);
    }
}

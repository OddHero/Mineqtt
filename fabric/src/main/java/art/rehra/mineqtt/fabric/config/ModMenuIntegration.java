package art.rehra.mineqtt.fabric.config;

import art.rehra.mineqtt.fabric.config.MineQTTConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return MineQTTConfigScreen::createConfigScreen;
    }
}

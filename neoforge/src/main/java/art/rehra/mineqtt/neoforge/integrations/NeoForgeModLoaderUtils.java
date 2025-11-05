package art.rehra.mineqtt.neoforge.integrations;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.integrations.IModLoaderUtils;
import net.neoforged.fml.ModList;

public class NeoForgeModLoaderUtils implements IModLoaderUtils {


    @Override
    public boolean isModLoaded(String modId) {
        ModList modList = ModList.get();
        try {
            return modList.isLoaded(modId);
        } catch (Exception e) {
            MineQTT.LOGGER.error("Failed to check if mod {} is loaded", modId, e);
            return false;
        }
    }
}

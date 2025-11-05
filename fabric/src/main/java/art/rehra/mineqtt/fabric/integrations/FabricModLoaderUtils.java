package art.rehra.mineqtt.fabric.integrations;

import art.rehra.mineqtt.integrations.IModLoaderUtils;
import net.fabricmc.loader.api.FabricLoader;

public class FabricModLoaderUtils implements IModLoaderUtils {
    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }
}

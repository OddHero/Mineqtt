package art.rehra.mineqtt.neoforge;

import art.rehra.mineqtt.MineQTT;
import net.neoforged.fml.common.Mod;

@Mod(MineQTT.MOD_ID)
public final class MineqttNeoForge {
    public MineqttNeoForge() {
        // Run our common setup.
        MineQTT.init();
    }
}

package art.rehra.mineqtt.mqtt;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public interface ICallbackTarget {
    BlockPos getPosition();
    ResourceKey<Level> getDimension();
    void onMessageReceived(String topic, String message);
}

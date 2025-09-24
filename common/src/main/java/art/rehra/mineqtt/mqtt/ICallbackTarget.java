package art.rehra.mineqtt.mqtt;

import net.minecraft.core.BlockPos;

public interface ICallbackTarget {
    public BlockPos getPosition();
    void onMessageReceived(String topic, String message);
}

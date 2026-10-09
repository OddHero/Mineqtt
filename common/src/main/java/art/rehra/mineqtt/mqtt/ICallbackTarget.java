package art.rehra.mineqtt.mqtt;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * A block entity that receives the messages of its v1 topic through the
 * {@link art.rehra.mineqtt.legacy.LegacySubscriptionFacade}: the Block Emitter. The first three methods are
 * {@code BlockEntity}'s.
 */
public interface ICallbackTarget {
    Level getLevel();

    BlockPos getBlockPos();

    boolean isRemoved();

    /** Server thread: a message on {@code topic}, which matched one of the filters the block subscribed. */
    void onMessageReceived(String topic, String message);
}

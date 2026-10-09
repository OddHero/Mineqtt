package art.rehra.mineqtt.ui.framework;

import net.minecraft.server.level.ServerPlayer;

/**
 * A block entity with a tabbed screen ({@link TabbedMqttMenu}): a device or a block that is no device. Its screen opens
 * on the tab the block last showed, which {@code SetActiveTabPayload} remembers. Server side.
 */
public interface TabbedScreenHost {

    /** Opens the screen for a player who may use the block. */
    void openScreen(ServerPlayer player);

    /** The tab the block's screen last showed; empty for none. */
    String getLastTabId();

    void setLastTabId(String tabId);
}

package art.rehra.mineqtt.ui;

import art.rehra.mineqtt.items.CyberdeckItem;
import art.rehra.mineqtt.security.TokenBucket;
import art.rehra.mineqtt.ui.explorer.DevicesTab;
import art.rehra.mineqtt.ui.explorer.ExplorerActions;
import art.rehra.mineqtt.ui.explorer.ExplorerDirectory;
import art.rehra.mineqtt.ui.explorer.ExplorerPage;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.tabs.CyberdeckExplorerTab;
import art.rehra.mineqtt.ui.framework.tabs.CyberdeckPublishTab;
import art.rehra.mineqtt.ui.framework.tabs.SettingsTab;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * The Configurator's explorer: Devices (M4 plan D13, first and where it opens), Settings (two topic slots), Traffic (the
 * topics it listens to) and Publish. The topic slots hold their items only while the screen is open and give them back
 * when it closes, as a crafting table does; whether the player listens and publishes privately is kept on the server
 * ({@code CyberdeckSessionManager}) and comes with the open data. The Devices tab's pages come from the server on each
 * search ({@code ExplorerDirectory}), which this menu paces on the server.
 */
public class CyberdeckMenu extends TabbedMqttMenu {
    private static final int TOPIC_SLOTS = 2;

    public final ItemStack itemStack;
    private boolean listening;
    private boolean privateMode;
    // Server: the pace of the Devices tab's searches, made at the first one.
    private @Nullable TokenBucket explorerQueries;
    // Server: the pace of the Devices tab's actions and privacy choices, made at the first one.
    private @Nullable TokenBucket explorerActions;
    // Client: the Devices tab's page as the server last sent it.
    private @Nullable ExplorerPage explorerPage;

    public CyberdeckMenu(int containerId, Inventory playerInventory, ItemStack itemStack, boolean listening, boolean privateMode) {
        super(MineqttMenuTypes.CYBERDECK_MENU.get(), containerId, playerInventory, new SimpleContainer(TOPIC_SLOTS), List.of(
                new DevicesTab(),
                new SettingsTab(),
                new CyberdeckExplorerTab(),
                new CyberdeckPublishTab()
        ), playerInventory.player, null, DevicesTab.ID, true, null);
        this.itemStack = itemStack;
        this.listening = listening;
        this.privateMode = privateMode;
    }

    /** The Configurator in the player's main hand, else the one in their off hand, else an empty stack. */
    public static ItemStack heldConfigurator(Player player) {
        if (player.getMainHandItem().getItem() instanceof CyberdeckItem) return player.getMainHandItem();
        if (player.getOffhandItem().getItem() instanceof CyberdeckItem) return player.getOffhandItem();
        return ItemStack.EMPTY;
    }

    /** The open data {@link #fromOpenData} reads. */
    public static void writeOpenData(FriendlyByteBuf buf, boolean listening, boolean privateMode) {
        buf.writeBoolean(listening);
        buf.writeBoolean(privateMode);
    }

    /** The client's menu, built from the open data the server wrote. */
    public static CyberdeckMenu fromOpenData(int containerId, Inventory inventory, FriendlyByteBuf buf) {
        boolean listening = buf.readBoolean();
        boolean privateMode = buf.readBoolean();
        return new CyberdeckMenu(containerId, inventory, heldConfigurator(inventory.player), listening, privateMode);
    }

    /** Whether the explorer listens: the server's session, on the client as last sent or toggled. */
    public boolean listening() {
        return listening;
    }

    public void setListening(boolean listening) {
        this.listening = listening;
    }

    /** Whether Publish sends into the player's private subtree, on the client as last sent or toggled. */
    public boolean privateMode() {
        return privateMode;
    }

    public void setPrivateMode(boolean privateMode) {
        this.privateMode = privateMode;
    }

    /**
     * Server: takes one of the Devices tab's searches from the pace of this screen ({@code ExplorerDirectory}): a burst
     * of {@value ExplorerDirectory#QUERY_BURST}, then {@value ExplorerDirectory#QUERIES_PER_SECOND} per second.
     */
    public boolean takeExplorerQuery() {
        if (explorerQueries == null) {
            explorerQueries = new TokenBucket(ExplorerDirectory.QUERY_BURST, ExplorerDirectory.QUERIES_PER_SECOND, System::nanoTime);
        }
        return explorerQueries.tryTake();
    }

    /**
     * Server: takes one of the Devices tab's actions or privacy choices from the pace of this screen
     * ({@code ExplorerActions}): a burst of {@value ExplorerActions#ACTION_BURST}, then
     * {@value ExplorerActions#ACTIONS_PER_SECOND} per second.
     */
    public boolean takeExplorerAction() {
        if (explorerActions == null) {
            explorerActions = new TokenBucket(ExplorerActions.ACTION_BURST, ExplorerActions.ACTIONS_PER_SECOND, System::nanoTime);
        }
        return explorerActions.tryTake();
    }

    /** Client: the Devices tab's page as the server last sent it; empty before the first answer. */
    public Optional<ExplorerPage> explorerPage() {
        return Optional.ofNullable(explorerPage);
    }

    /** Client: the server answered a search of the Devices tab. */
    public void setExplorerPage(ExplorerPage page) {
        this.explorerPage = page;
    }

    /** Valid while the player holds this Configurator; the server closes the screen once they no longer do. */
    @Override
    public boolean stillValid(Player player) {
        return !itemStack.isEmpty() && (player.getMainHandItem() == itemStack || player.getOffhandItem() == itemStack);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!player.level().isClientSide()) {
            clearContainer(player, container);
        }
    }
}

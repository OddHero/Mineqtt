package art.rehra.mineqtt.ui;

import art.rehra.mineqtt.items.CyberdeckItem;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.tabs.CyberdeckExplorerTab;
import art.rehra.mineqtt.ui.framework.tabs.CyberdeckPublishTab;
import art.rehra.mineqtt.ui.framework.tabs.SettingsTab;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The Configurator's topic explorer: Settings (two topic slots), Explorer and Publish. The topic slots
 * hold their items only while the screen is open and give them back when it closes, as a crafting table does; whether
 * the player listens and publishes privately is kept on the server ({@code CyberdeckSessionManager}) and comes with the
 * open data.
 */
public class CyberdeckMenu extends TabbedMqttMenu {
    private static final int TOPIC_SLOTS = 2;

    public final ItemStack itemStack;
    private boolean listening;
    private boolean privateMode;

    public CyberdeckMenu(int containerId, Inventory playerInventory, ItemStack itemStack, boolean listening, boolean privateMode) {
        super(MineqttMenuTypes.CYBERDECK_MENU.get(), containerId, playerInventory, new SimpleContainer(TOPIC_SLOTS), List.of(
                new SettingsTab(),
                new CyberdeckExplorerTab(),
                new CyberdeckPublishTab()
        ), playerInventory.player, null, SettingsTab.ID, true, null);
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

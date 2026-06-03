package art.rehra.mineqtt.ui;

import art.rehra.mineqtt.items.CyberdeckDataUtil;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.tabs.CyberdeckExplorerTab;
import art.rehra.mineqtt.ui.framework.tabs.CyberdeckPublishTab;
import art.rehra.mineqtt.ui.framework.tabs.LightRemoteTab;
import art.rehra.mineqtt.ui.framework.tabs.SettingsTab;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public class CyberdeckMenu extends TabbedMqttMenu {
    public final ItemStack itemStack;

    public CyberdeckMenu(int containerId, Inventory playerInventory, ItemStack itemStack) {
        super(MineqttMenuTypes.CYBERDECK_MENU.get(), containerId, playerInventory, new SimpleContainer(4), List.of(
                new SettingsTab(),
                new CyberdeckExplorerTab(),
                new CyberdeckPublishTab(),
                new LightRemoteTab()
        ), playerInventory.player, null, SettingsTab.ID);
        this.itemStack = itemStack;

        // Load inventory from Data Components
        CyberdeckDataUtil.loadToContainer(itemStack, (SimpleContainer) container, player.registryAccess());
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!player.level().isClientSide) {
            // Save back to the item using Data Components persistence
            CyberdeckDataUtil.saveFromContainer(itemStack, (SimpleContainer) container, player.registryAccess());
        }
    }
}

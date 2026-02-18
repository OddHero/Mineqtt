package art.rehra.mineqtt.ui;

import art.rehra.mineqtt.items.CyberdeckDataUtil;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class CyberdeckMenu extends AbstractContainerMenu {
    public final SimpleContainer container = new SimpleContainer(2);
    public final Player player;
    public final ItemStack itemStack;

    public CyberdeckMenu(int containerId, Inventory playerInventory, ItemStack itemStack) {
        super(MineqttMenuTypes.CYBERDECK_MENU.get(), containerId);
        this.player = playerInventory.player;
        this.itemStack = itemStack;

        // Load inventory from Data Components
        CyberdeckDataUtil.loadToContainer(itemStack, container, player.registryAccess());

        // Player Inventory
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 174 + row * 18));
            }
        }

        // Player Hotbar
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 232));
        }

        // Cyberdeck Inventory - Topic slots
        this.addSlot(new Slot(container, 0, 9, 35)); // Base topic
        this.addSlot(new Slot(container, 1, 45, 35)); // Sub topic
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack newStack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot.hasItem()) {
            ItemStack originalStack = slot.getItem();
            newStack = originalStack.copy();

            if (index < 36) {
                // Move from player inventory to cyberdeck inventory
                if (!this.moveItemStackTo(originalStack, 36, 38, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (index < 38) {
                // Move from cyberdeck inventory to player inventory
                if (!this.moveItemStackTo(originalStack, 0, 36, false)) {
                    return ItemStack.EMPTY;
                }
            } else {
                return ItemStack.EMPTY;
            }

            if (originalStack.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }

            slot.onTake(player, originalStack);
        }

        return newStack;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.getInventory().contains(itemStack) || player.getMainHandItem() == itemStack || player.getOffhandItem() == itemStack;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!player.level().isClientSide) {
            // Save back to the item using Data Components persistence
            CyberdeckDataUtil.saveFromContainer(itemStack, container, player.registryAccess());
        }
    }
}

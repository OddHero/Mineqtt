package art.rehra.mineqtt.ui;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class LightRemoteMenu extends AbstractContainerMenu {
    public final Player player;
    public Container container;
    public BlockPos blockPos;

    public LightRemoteMenu(int containerId, Inventory playerInventory, Player player, BlockPos blockPos) {
        this(containerId, playerInventory, (Container) playerInventory.player.level().getBlockEntity(blockPos), player, blockPos);
    }

    public LightRemoteMenu(int containerId, Inventory playerInventory, Container container, Player player, BlockPos blockPos) {
        super(MineqttMenuTypes.LIGHT_REMOTE_BLOCK_MENU.get(), containerId);

        this.player = player;
        this.container = container;
        this.blockPos = blockPos;

        // Player Inventory (shifted down for taller GUI)
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 198 + row * 18));
            }
        }

        // Player Hotbar
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 256));
        }

        // Block Inventory - Topic slots (base topic + sub topic)
        this.addSlot(new Slot(container, 0, 8, 26));  // Base Topic
        this.addSlot(new Slot(container, 1, 44, 26));  // Sub Topic
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack newStack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot.hasItem()) {
            ItemStack originalStack = slot.getItem();
            newStack = originalStack.copy();

            if (index < 36) {
                // Move from player inventory to block inventory
                if (!this.moveItemStackTo(originalStack, 36, 38, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (index < 38) {
                // Move from block inventory to player inventory
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
        return this.container.stillValid(player);
    }
}

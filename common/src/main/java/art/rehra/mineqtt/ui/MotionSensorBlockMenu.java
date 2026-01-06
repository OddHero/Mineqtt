package art.rehra.mineqtt.ui;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class MotionSensorBlockMenu extends AbstractContainerMenu {
    public final Player player;
    public Container container;
    public BlockPos blockPos;

    public MotionSensorBlockMenu(int containerId, Inventory playerInventory, Player player, BlockPos blockPos) {
        this(containerId, playerInventory, (Container) playerInventory.player.level().getBlockEntity(blockPos), player, blockPos);
    }

    public MotionSensorBlockMenu(int containerId, Inventory playerInventory, Container container, Player player, BlockPos blockPos) {
        super(MineqttMenuTypes.MOTION_SENSOR_BLOCK_MENU.get(), containerId);

        this.player = player;
        this.container = container;
        this.blockPos = blockPos;

        // Player Inventory (slots 0-26)
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 102 + row * 18));
            }
        }

        // Player Hotbar (slots 27-35)
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 160));
        }

        // Block Inventory (slots 36+)
        this.addSlot(new Slot(container, 0, 62, 35));   // Base path
        this.addSlot(new Slot(container, 1, 98, 35));   // Sub path
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack newStack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot.hasItem()) {
            ItemStack originalStack = slot.getItem();
            newStack = originalStack.copy();

            int playerInvStart = 0;
            int playerInvEnd = 27;
            int playerHotbarStart = 27;
            int playerHotbarEnd = 36;
            int blockInvStart = 36;
            int blockInvEnd = 38; // 2 topic slots only

            if (index < playerInvEnd) {
                // Move from player inventory to block inventory
                if (!this.moveItemStackTo(originalStack, blockInvStart, blockInvEnd, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (index < playerHotbarEnd) {
                // Move from player hotbar to block inventory
                if (!this.moveItemStackTo(originalStack, blockInvStart, blockInvEnd, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (index < blockInvEnd) {
                // Move from block inventory to player inventory
                if (!this.moveItemStackTo(originalStack, playerInvStart, playerHotbarEnd, false)) {
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


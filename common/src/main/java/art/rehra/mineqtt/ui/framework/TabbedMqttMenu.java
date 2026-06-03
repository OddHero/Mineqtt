package art.rehra.mineqtt.ui.framework;

import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.ui.MineqttMenuTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Generic tabbed container menu used by every MQTT block.
 *
 * <p>Player inventory occupies the bottom of the GUI; each {@link MqttTab} contributes
 * its own slots via {@link MqttTab#buildSlots(TabbedMqttMenu, Container)}. Slots
 * belonging to non-active tabs are hidden via {@link TabbedSlot#isActive()}.</p>
 */
public class TabbedMqttMenu extends AbstractContainerMenu {

    /**
     * Y-position where the player inventory starts (matches the standard tabbed GUI).
     */
    public static final int PLAYER_INV_Y = 110;
    public static final int PLAYER_HOTBAR_Y = PLAYER_INV_Y + 58;
    public static final int GUI_WIDTH = 176;
    /**
     * Total GUI height, including the tab bar above.
     */
    public static final int GUI_HEIGHT = 192;

    public final Player player;
    public final Container container;
    public final BlockPos blockPos; // @Nullable for items
    public final List<MqttTab> tabs;
    private final int containerSlotStart;
    private final int containerSlotEnd;
    private String activeTabId;

    public TabbedMqttMenu(int containerId, Inventory playerInventory, Player player, BlockPos blockPos, String initialTabId) {
        this(MineqttMenuTypes.MQTT_TABBED_MENU.get(), containerId, playerInventory, resolveContainer(playerInventory, blockPos), resolveTabs(playerInventory, blockPos), player, blockPos, initialTabId);
    }

    public TabbedMqttMenu(MenuType<?> type,
                          int containerId,
                          Inventory playerInventory,
                          Container container,
                          List<MqttTab> tabs,
                          Player player,
                          BlockPos blockPos,
                          String initialTabId) {
        super(type, containerId);
        this.player = player;
        this.container = container;
        this.blockPos = blockPos;
        this.tabs = tabs.isEmpty() ? List.of() : Collections.unmodifiableList(new ArrayList<>(tabs));
        this.activeTabId = pickInitialTabId(this.tabs, initialTabId);

        // Player inventory
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, PLAYER_INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, PLAYER_HOTBAR_Y));
        }

        this.containerSlotStart = this.slots.size();
        for (MqttTab tab : this.tabs) {
            tab.buildSlots(this, this.container);
        }
        this.containerSlotEnd = this.slots.size();
    }

    private static Container resolveContainer(Inventory inv, BlockPos pos) {
        if (pos == null) return new SimpleContainer(0);
        var be = inv.player.level().getBlockEntity(pos);
        return be instanceof Container c ? c : new SimpleContainer(0);
    }

    private static List<MqttTab> resolveTabs(Inventory inv, BlockPos pos) {
        if (pos == null) return List.of();
        var be = inv.player.level().getBlockEntity(pos);
        return be instanceof BaseMqttBlockEntity mqtt ? mqtt.getTabs() : List.of();
    }

    private static String pickInitialTabId(List<MqttTab> tabs, String requested) {
        if (tabs.isEmpty()) return "";
        if (requested != null) {
            for (MqttTab t : tabs) if (t.id().equals(requested)) return requested;
        }
        return tabs.get(0).id();
    }

    public String getActiveTabId() {
        return activeTabId;
    }

    public MqttTab getActiveTab() {
        for (MqttTab t : tabs) if (t.id().equals(activeTabId)) return t;
        return tabs.isEmpty() ? null : tabs.get(0);
    }

    /**
     * Switches the visible tab. Must be invoked on both sides; the screen also
     * sends a {@code SetActiveTabPayload} so the block entity persists the choice.
     */
    public void setActiveTab(String tabId) {
        for (MqttTab t : tabs) {
            if (t.id().equals(tabId)) {
                this.activeTabId = tabId;
                return;
            }
        }
    }

    /**
     * Adds a slot bound to {@code tabId}. Helper for {@link MqttTab#buildSlots}.
     */
    public Slot addTabSlot(String tabId, Container c, int idx, int x, int y) {
        return this.addSlot(new TabbedSlot(this, tabId, c, idx, x, y));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack newStack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot.hasItem()) {
            ItemStack originalStack = slot.getItem();
            newStack = originalStack.copy();
            int playerInvEnd = 36;

            if (index < playerInvEnd) {
                // From player inventory: try to move into any active container slot.
                if (!moveToActiveContainerSlots(originalStack)) {
                    return ItemStack.EMPTY;
                }
            } else if (index < containerSlotEnd) {
                // From a container slot: move to player inventory.
                if (!this.moveItemStackTo(originalStack, 0, playerInvEnd, true)) {
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

    /**
     * Tries to move the stack into the slots of the currently active tab.
     */
    private boolean moveToActiveContainerSlots(ItemStack stack) {
        boolean moved = false;
        for (int i = containerSlotStart; i < containerSlotEnd && !stack.isEmpty(); i++) {
            Slot s = this.slots.get(i);
            if (!(s instanceof TabbedSlot ts) || !ts.tabId().equals(activeTabId)) continue;
            if (!s.mayPlace(stack)) continue;
            if (s.hasItem()) continue; // simple behaviour: skip occupied slots
            int max = Math.min(s.getMaxStackSize(stack), stack.getMaxStackSize());
            int take = Math.min(stack.getCount(), max);
            ItemStack copy = stack.copy();
            copy.setCount(take);
            s.set(copy);
            stack.shrink(take);
            moved = true;
        }
        return moved;
    }

    @Override
    public boolean stillValid(Player player) {
        return this.container.stillValid(player);
    }
}

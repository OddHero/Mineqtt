package art.rehra.mineqtt.ui.framework;

import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.network.MineqttNetworking;
import art.rehra.mineqtt.ui.MineqttMenuTypes;
import art.rehra.mineqtt.ui.console.ConsoleMenu;
import art.rehra.mineqtt.ui.console.ConsoleView;
import art.rehra.mineqtt.ui.device.DeviceMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Generic tabbed container menu used by every MQTT block.
 *
 * <p>The player's inventory occupies the bottom of the GUI, unless the menu leaves it out ({@link #hasPlayerInventory()}):
 * a device's screen has no slots, so it has no inventory either and is only as tall as its tabs need
 * ({@link #guiHeight()}). Each {@link MqttTab} contributes its own slots via
 * {@link MqttTab#buildSlots(TabbedMqttMenu, Container)}. Slots belonging to non-active tabs are hidden via
 * {@link TabbedSlot#isActive()}.</p>
 *
 * <p>Opening the screen of a block needs the USE permission; its slots configure the block, so a player without
 * CONFIGURE sees them but cannot change them ({@link #configurable()}).</p>
 */
public class TabbedMqttMenu extends AbstractContainerMenu {

    /**
     * Y-position where the player inventory starts (matches the standard tabbed GUI).
     */
    public static final int PLAYER_INV_Y = 110;
    public static final int PLAYER_HOTBAR_Y = PLAYER_INV_Y + 58;
    public static final int GUI_WIDTH = 176;
    /** The panel's height with the player's inventory; the tab bar sits above the panel. */
    public static final int GUI_HEIGHT = 192;
    /**
     * The panel's height without the player's inventory: the tabs' content ends by y 100 (the Advanced tab's Save row;
     * the others end by y 98, where the inventory's label starts on the taller panel, and keep vanilla's 7 px margin).
     */
    public static final int GUI_HEIGHT_NO_INVENTORY = 105;

    public final Player player;
    public final Container container;
    public final BlockPos blockPos; // @Nullable for items
    public final List<MqttTab> tabs;
    private final int playerSlotEnd;
    private final int containerSlotStart;
    private final int containerSlotEnd;
    private final boolean configurable;
    private @Nullable String deviceStatus;
    private @Nullable String rawProblem;
    private @Nullable DeviceView deviceView;
    private String activeTabId;

    public TabbedMqttMenu(int containerId, Inventory playerInventory, Player player, BlockPos blockPos, String initialTabId, boolean configurable) {
        this(MineqttMenuTypes.MQTT_TABBED_MENU.get(), containerId, playerInventory, resolveContainer(playerInventory, blockPos), resolveTabs(playerInventory, blockPos), player, blockPos, initialTabId, configurable, null);
    }

    /** The client side of a block's screen, from what {@link #writeOpenData} sent. */
    public static TabbedMqttMenu fromOpenData(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        boolean configurable = buf.readBoolean();
        String tabId = buf.readUtf();
        String status = buf.readNullable(FriendlyByteBuf::readUtf);
        DeviceView view = buf.readNullable(DeviceView.STREAM_CODEC);
        ConsoleView console = buf.readNullable(ConsoleView.STREAM_CODEC);
        if (console != null) {
            return new ConsoleMenu(containerId, playerInventory, pos, null, tabId, console);
        }
        // A device's screen is built from its type, which its client block entity knows.
        if (view != null && playerInventory.player.level().getBlockEntity(pos) instanceof DeviceHost host) {
            DeviceMenu menu = new DeviceMenu(containerId, playerInventory, (BlockEntity) host, tabId, configurable);
            menu.setDeviceView(view);
            return menu;
        }
        TabbedMqttMenu menu = new TabbedMqttMenu(MineqttMenuTypes.MQTT_TABBED_MENU.get(), containerId, playerInventory,
                resolveContainer(playerInventory, pos), resolveTabs(playerInventory, pos), playerInventory.player, pos, tabId, configurable, status);
        if (view != null) menu.setDeviceView(view);
        return menu;
    }

    /**
     * What the client needs to build a block's screen: the position, whether the player may configure the block, the tab
     * the server's menu opens on ({@link #initialTabId}), which the client's must open on too or a shift-click would fill
     * the slots of a tab the player does not see, and either a device's view (M2-7), which carries its values, status
     * line and Raw Link problem, or the status line of a block that is no device ({@code null} when it has none), cut to
     * {@link MineqttNetworking.DeviceStatusPayload#MAX_STATUS_CHARS} as a device's later status updates are. A device's
     * screens read the view, never the client's block entity, which carries only what the client draws.
     */
    public static void writeOpenData(FriendlyByteBuf buf, BlockPos pos, boolean configurable, String tabId, @Nullable String status, @Nullable DeviceView view) {
        writeOpenData(buf, pos, configurable, tabId, status, view, null);
    }

    /** The open data of a Bridge Console's screen (M2-23): the player's {@link ConsoleView} takes the place of a device's view. */
    public static void writeOpenData(FriendlyByteBuf buf, BlockPos pos, boolean configurable, String tabId, ConsoleView console) {
        writeOpenData(buf, pos, configurable, tabId, null, null, console);
    }

    private static void writeOpenData(FriendlyByteBuf buf, BlockPos pos, boolean configurable, String tabId, @Nullable String status,
                                      @Nullable DeviceView view, @Nullable ConsoleView console) {
        buf.writeBlockPos(pos);
        buf.writeBoolean(configurable);
        buf.writeUtf(tabId);
        buf.writeNullable(status == null ? null : MineqttNetworking.DeviceStatusPayload.cap(status), FriendlyByteBuf::writeUtf);
        buf.writeNullable(view, DeviceView.STREAM_CODEC);
        buf.writeNullable(console, ConsoleView.STREAM_CODEC);
    }

    public TabbedMqttMenu(MenuType<?> type,
                          int containerId,
                          Inventory playerInventory,
                          Container container,
                          List<MqttTab> tabs,
                          Player player,
                          BlockPos blockPos,
                          String initialTabId,
                          boolean configurable,
                          @Nullable String deviceStatus) {
        this(type, containerId, playerInventory, container, tabs, player, blockPos, initialTabId, configurable, deviceStatus, true);
    }

    /** @param withPlayerInventory whether the menu has the player's inventory and hotbar below its tabs */
    protected TabbedMqttMenu(MenuType<?> type,
                             int containerId,
                             Inventory playerInventory,
                             Container container,
                             List<MqttTab> tabs,
                             Player player,
                             BlockPos blockPos,
                             String initialTabId,
                             boolean configurable,
                             @Nullable String deviceStatus,
                             boolean withPlayerInventory) {
        super(type, containerId);
        this.player = player;
        this.container = container;
        this.blockPos = blockPos;
        this.configurable = configurable;
        this.deviceStatus = deviceStatus;
        this.tabs = tabs.isEmpty() ? List.of() : Collections.unmodifiableList(new ArrayList<>(tabs));
        this.activeTabId = initialTabId(this.tabs, initialTabId);

        if (withPlayerInventory) {
            for (int row = 0; row < 3; ++row) {
                for (int col = 0; col < 9; ++col) {
                    this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, PLAYER_INV_Y + row * 18));
                }
            }
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(playerInventory, col, 8 + col * 18, PLAYER_HOTBAR_Y));
            }
        }
        this.playerSlotEnd = this.slots.size();

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

    /** The tab a menu with these tabs opens on: the requested one when it has it, else the first; "" without tabs. */
    public static String initialTabId(List<MqttTab> tabs, @Nullable String requested) {
        if (tabs.isEmpty()) return "";
        if (requested != null) {
            for (MqttTab t : tabs) if (t.id().equals(requested)) return requested;
        }
        return tabs.get(0).id();
    }

    /** Whether the menu has the player's inventory and hotbar below its tabs. */
    public boolean hasPlayerInventory() {
        return playerSlotEnd > 0;
    }

    /** The height of the screen's panel: with room for the player's inventory when the menu has it. */
    public int guiHeight() {
        return hasPlayerInventory() ? GUI_HEIGHT : GUI_HEIGHT_NO_INVENTORY;
    }

    /** Whether this is the screen of that block entity; a player with it open sees the block's changes. */
    public boolean isFor(BlockEntity blockEntity) {
        return container == blockEntity;
    }

    /** Whether the player may change the slots; the server decides when the screen opens and enforces it. */
    public boolean configurable() {
        return configurable;
    }

    /**
     * A device's status line as the server saw it when the screen opened, or as it sent it since; null for blocks that are
     * no devices.
     */
    public @Nullable String deviceStatus() {
        return deviceStatus;
    }

    /** Why the device's Raw Link does nothing, as the server last sent it; null while it works and for other blocks. */
    public @Nullable String rawProblem() {
        return rawProblem;
    }

    /** Client: the server sent the device's status again, because its Raw Link changed or was judged again. */
    public void setDeviceStatus(String deviceStatus, @Nullable String rawProblem) {
        this.deviceStatus = deviceStatus;
        this.rawProblem = rawProblem;
    }

    /**
     * The device's view as the server last sent it for this player: with the open data (M2-7), and again whenever the
     * device changed since (M2-6). Empty for blocks that are no devices.
     */
    public Optional<DeviceView> deviceView() {
        return Optional.ofNullable(deviceView);
    }

    /** Client: the server sent the device's view; its status line and Raw Link problem come with it. */
    public void setDeviceView(DeviceView view) {
        this.deviceView = view;
        setDeviceStatus(view.status(), view.problem().orElse(null));
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

    /**
     * Every slot change through the menu is a click (a pickup, shift-click, swap, drag or throw). A click that changed
     * the block's slots makes the player its editor for a while ({@link BaseMqttBlockEntity#setLastEditor}), so they hear
     * when the topic they set is refused or moved; a click that changed nothing, or one of a player who may not configure
     * the block, does not.
     */
    @Override
    public void clicked(int slotId, int button, ContainerInput input, Player player) {
        if (!configurable || !(container instanceof BaseMqttBlockEntity blockEntity) || !(player instanceof ServerPlayer editor)) {
            super.clicked(slotId, button, input, player);
            return;
        }
        List<ItemStack> before = new ArrayList<>(containerSlotEnd - containerSlotStart);
        for (int i = containerSlotStart; i < containerSlotEnd; i++) before.add(slots.get(i).getItem().copy());
        super.clicked(slotId, button, input, player);
        for (int i = containerSlotStart; i < containerSlotEnd; i++) {
            if (!ItemStack.matches(before.get(i - containerSlotStart), slots.get(i).getItem())) {
                blockEntity.setLastEditor(editor);
                return;
            }
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack newStack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot.hasItem()) {
            ItemStack originalStack = slot.getItem();
            newStack = originalStack.copy();

            if (index < playerSlotEnd) {
                // From player inventory: try to move into any active container slot.
                if (!moveToActiveContainerSlots(originalStack)) {
                    return ItemStack.EMPTY;
                }
            } else if (index < containerSlotEnd) {
                // From a container slot: move to player inventory.
                if (!this.moveItemStackTo(originalStack, 0, playerSlotEnd, true)) {
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

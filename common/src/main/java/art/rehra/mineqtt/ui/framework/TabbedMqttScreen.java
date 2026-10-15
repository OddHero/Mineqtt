package art.rehra.mineqtt.ui.framework;

import art.rehra.mineqtt.network.MineqttNetworking;
import art.rehra.mineqtt.ui.console.ConsoleMenu;
import art.rehra.mineqtt.ui.device.DeviceMenu;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Generic screen for any block backed by {@link TabbedMqttMenu}.
 *
 * <p>The screen draws a row of square tab buttons above the GUI background
 * (styled after the creative-menu category bar). Each button renders the
 * {@link MqttTab#icon()} item; clicking switches to that tab. The currently
 * selected tab's {@link MqttTabView} renders its content + handles input.</p>
 *
 * <p>The panel's title row names the device on a device's screen and the active tab on any other. The panel is as
 * tall as the menu says ({@link TabbedMqttMenu#guiHeight()}): with the player's inventory and its label below the tab's
 * content, or without them on a device's screen.</p>
 */
public class TabbedMqttScreen extends AbstractContainerScreen<TabbedMqttMenu> {

    /** The widest a tab button gets; more tabs than fit share the panel's width ({@link #tabWidth}). */
    public static final int TAB_W = 28;
    public static final int TAB_H = 32;
    /**
     * Horizontal spacing between tab buttons.
     */
    public static final int TAB_SPACING = 0;
    /**
     * Vertical overlap between the tab bar and the GUI background.
     */
    public static final int TAB_OVERLAP = 4;
    // Vanilla's label colour, opaque: 26.2 reads text colours as ARGB, so one without alpha draws nothing.
    private static final int LABEL_COLOR = 0xFF404040;
    private static final int NOTE_COLOR = 0xFF777777;
    private static final int TITLE_GAP = 4;
    private final List<AbstractWidget> tabViewWidgets = new ArrayList<>();
    private MqttTabView activeView;
    private int lastMouseX = Integer.MIN_VALUE, lastMouseY = Integer.MIN_VALUE;

    public TabbedMqttScreen(TabbedMqttMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, TabbedMqttMenu.GUI_WIDTH, menu.guiHeight());
        this.inventoryLabelY = this.imageHeight - 94;
    }

    /** The panel's left edge on the screen; the tab bar starts there too. */
    public int getLeftPos() {
        return leftPos;
    }

    /** The panel's top edge on the screen; the tab bar sits above it. */
    public int getTopPos() {
        return topPos;
    }

    /** The view of the active tab, for client tests that read what it shows; empty while none is built. */
    public Optional<MqttTabView> activeView() {
        return Optional.ofNullable(activeView);
    }

    /** The note the active tab's view puts at the right of the title row ({@link MqttTabView#titleNote()}), if any. */
    public Optional<Component> titleNote() {
        return activeView != null ? activeView.titleNote() : Optional.empty();
    }

    @Override
    protected void init() {
        super.init();
        rebuildActiveView();
    }

    private void rebuildActiveView() {
        // Tear down previous view widgets
        for (AbstractWidget w : tabViewWidgets) {
            this.removeWidget(w);
        }
        tabViewWidgets.clear();
        leaveActiveView();

        activeView = MqttTabViews.create(this.menu.getActiveTabId(), this);
        activeView.init(this, this.leftPos, this.topPos);
        for (GuiEventListener w : activeView.widgets()) {
            if (w instanceof AbstractWidget aw) {
                addRenderableWidget(aw);
                tabViewWidgets.add(aw);
            }
        }
    }

    @Override
    public void onClose() {
        // Before the close packet, so the server still sees the open menu when the view's last payloads arrive.
        leaveActiveView();
        super.onClose();
    }

    @Override
    public void removed() {
        // Closed without onClose: by the server, or by another screen opening.
        leaveActiveView();
        super.removed();
    }

    private void leaveActiveView() {
        if (activeView != null) activeView.removed();
        activeView = null;
    }

    /**
     * Used by tab views that need to register their own widgets after init.
     */
    public <W extends AbstractWidget> W addTabWidget(W widget) {
        addRenderableWidget(widget);
        tabViewWidgets.add(widget);
        return widget;
    }

    public void switchTab(String tabId) {
        if (tabId.equals(this.menu.getActiveTabId())) return;
        this.menu.setActiveTab(tabId);
        NetworkManager.sendToServer(new MineqttNetworking.SetActiveTabPayload(java.util.Optional.ofNullable(this.menu.blockPos), tabId));
        rebuildActiveView();
    }

    // ===== Layout helpers =====

    private int tabBarX() {
        return this.leftPos;
    }

    private int tabBarY() {
        return this.topPos - TAB_H + TAB_OVERLAP;
    }

    /**
     * The width of each button in a tab bar of {@code tabs} tabs: {@link #TAB_W}, narrower when that many would run past
     * the panel's right edge, but never narrower than an icon.
     */
    public static int tabWidth(int tabs) {
        return Math.clamp((TabbedMqttMenu.GUI_WIDTH + TAB_SPACING) / Math.max(tabs, 1) - TAB_SPACING, 18, TAB_W);
    }

    private int tabW() {
        return tabWidth(this.menu.tabs.size());
    }

    private int tabBoundsX(int index) {
        return tabBarX() + index * (tabW() + TAB_SPACING);
    }

    private int tabIndexAt(double mouseX, double mouseY) {
        int by = tabBarY();
        if (mouseY < by || mouseY >= by + TAB_H) return -1;
        for (int i = 0; i < this.menu.tabs.size(); i++) {
            int bx = tabBoundsX(i);
            if (mouseX >= bx && mouseX < bx + tabW()) return i;
        }
        return -1;
    }

    // ===== Rendering =====

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        // Tab bar (drawn before the main panel so the active tab can overlap)
        renderTabBar(g);
        // Main GUI panel
        int x = this.leftPos, y = this.topPos, w = this.imageWidth, h = this.imageHeight;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF3A3A3A);
        g.fill(x, y, x + w, y + h, 0xFFC6C6C6);

        // Dynamic slot outlines for every slot.
        for (net.minecraft.world.inventory.Slot s : this.menu.slots) {
            if (s instanceof TabbedSlot ts && !ts.isActive()) continue;
            int sx = this.leftPos + s.x;
            int sy = this.topPos + s.y;
            // Sunken bevel: dark outline, mid background, subtle inner highlight.
            g.fill(sx - 1, sy - 1, sx + 17, sy + 17, 0xFF373737);
            g.fill(sx, sy, sx + 16, sy + 16, 0xFF8B8B8B);
        }
    }

    private void renderTabBar(GuiGraphicsExtractor g) {
        for (int i = 0; i < this.menu.tabs.size(); i++) {
            MqttTab tab = this.menu.tabs.get(i);
            boolean active = tab.id().equals(this.menu.getActiveTabId());
            int bx = tabBoundsX(i);
            int by = tabBarY();
            int tabW = tabW();
            int innerColor = active ? 0xFFC6C6C6 : 0xFF6E6E6E;
            int outline = active ? 0xFF1F1F1F : 0xFF2A2A2A;
            // Outline
            g.fill(bx - 1, by - 1, bx + tabW + 1, by + TAB_H + 1, outline);
            // Body
            g.fill(bx, by, bx + tabW, by + TAB_H, innerColor);
            // Hide bottom border on active tab so it blends into the GUI
            if (active) {
                g.fill(bx, by + TAB_H - 1, bx + tabW, by + TAB_H + TAB_OVERLAP, 0xFFC6C6C6);
            }
            // Icon
            ItemStack icon = tab.icon();
            if (!icon.isEmpty()) {
                int iconX = bx + (tabW - 16) / 2;
                int iconY = by + (TAB_H - TAB_OVERLAP - 16) / 2 + 2;
                g.item(icon, iconX, iconY);
            }
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // extractLabels is called inside a translated pose (origin = guiLeft,guiTop).
        // Convert the absolute mouse coords to the same local space so tooltips work.
        int localMX = lastMouseX == Integer.MIN_VALUE ? Integer.MIN_VALUE : lastMouseX - this.leftPos;
        int localMY = lastMouseY == Integer.MIN_VALUE ? Integer.MIN_VALUE : lastMouseY - this.topPos;
        // The title row: the title at the left as in vanilla, a note of the tab at the right, both truncated to fit.
        int titleW = this.imageWidth - 2 * this.titleLabelX;
        Optional<Component> note = titleNote();
        if (note.isPresent()) {
            int noteW = Math.min(this.font.width(note.get()), titleW / 3);
            GuiText.drawRight(g, note.get().getString(), this.titleLabelX + titleW, this.titleLabelY, noteW, NOTE_COLOR, localMX, localMY);
            titleW -= noteW + TITLE_GAP;
        }
        GuiText.drawTruncated(g, screenTitle(), this.titleLabelX, this.titleLabelY, titleW, LABEL_COLOR, localMX, localMY);
        if (this.menu.hasPlayerInventory()) {
            GuiText.drawTruncated(g, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, 9 * 18, LABEL_COLOR, localMX, localMY);
        }
    }

    /** What the title row names: the device for a device's screen, the console for a Bridge Console's, else the active tab. */
    private Component screenTitle() {
        if (this.menu instanceof DeviceMenu device) return device.title();
        if (this.menu instanceof ConsoleMenu) return this.title;
        MqttTab tab = this.menu.getActiveTab();
        return tab != null ? tab.title() : this.title;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        if (activeView != null) {
            activeView.renderContent(g, this.leftPos, this.topPos, mouseX, mouseY, partialTick);
            activeView.renderOverlay(g, this.leftPos, this.topPos, mouseX, mouseY, partialTick);
        }
        // Tab tooltips
        int tabIdx = tabIndexAt(mouseX, mouseY);
        if (tabIdx >= 0) {
            g.setTooltipForNextFrame(this.font, this.menu.tabs.get(tabIdx).title(), mouseX, mouseY);
        }
    }

    // ===== Input =====

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        if (activeView != null && activeView.isModal()) {
            // Modal views handle everything themselves
            activeView.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        if (button == 0) {
            int idx = tabIndexAt(mouseX, mouseY);
            if (idx >= 0) {
                switchTab(this.menu.tabs.get(idx).id());
                playClickSound();
                return true;
            }
        }
        if (activeView != null && activeView.mouseClicked(mouseX, mouseY, button)) return true;
        return super.mouseClicked(event, doubleClick);
    }

    private void playClickSound() {
        Minecraft.getInstance().getSoundManager().play(
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (activeView != null && activeView.mouseReleased(event.x(), event.y(), event.button())) return true;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (activeView != null && activeView.mouseDragged(event.x(), event.y(), event.button(), dx, dy)) return true;
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (activeView != null && activeView.mouseScrolled(mouseX, mouseY, dx, dy)) return true;
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        //? if >=26.3 {
        /*int scanCode = event.keycode();
        *///?} else {
        int scanCode = event.scancode();
        //?}
        int modifiers = event.modifiers();
        if (activeView != null && (activeView.isModal() || activeView.keyPressed(keyCode, scanCode, modifiers))) {
            if (activeView.isModal()) {
                activeView.keyPressed(keyCode, scanCode, modifiers);
                return true;
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (activeView != null && activeView.charTyped((char) event.codepoint(), 0)) return true;
        return super.charTyped(event);
    }

    @Override
    protected void slotClicked(net.minecraft.world.inventory.Slot slot,
                               int slotId,
                               int mouseButton,
                               net.minecraft.world.inventory.ContainerInput type) {
        // Let the active view intercept shift-clicks on player-inventory slots so
        // it can react to the picked item (e.g. fill a text field with its name)
        // instead of quick-moving the stack into a slot that may not exist.
        if (type == net.minecraft.world.inventory.ContainerInput.QUICK_MOVE
                && slot != null
                && slot.container instanceof Inventory
                && activeView != null) {
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && activeView.onShiftClickItem(stack)) {
                return;
            }
        }
        super.slotClicked(slot, slotId, mouseButton, type);
    }
}

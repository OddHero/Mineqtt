package art.rehra.mineqtt.ui.framework;

import art.rehra.mineqtt.network.MineqttNetworking;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Generic screen for any block backed by {@link TabbedMqttMenu}.
 *
 * <p>The screen draws a row of square tab buttons above the GUI background
 * (styled after the creative-menu category bar). Each button renders the
 * {@link MqttTab#icon()} item; clicking switches to that tab. The currently
 * selected tab's {@link MqttTabView} renders its content + handles input.</p>
 */
public class TabbedMqttScreen extends AbstractContainerScreen<TabbedMqttMenu> {

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
    private final List<AbstractWidget> tabViewWidgets = new ArrayList<>();
    private MqttTabView activeView;
    private int lastMouseX = Integer.MIN_VALUE, lastMouseY = Integer.MIN_VALUE;

    public TabbedMqttScreen(TabbedMqttMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.font = Minecraft.getInstance().font;
        this.imageWidth = TabbedMqttMenu.GUI_WIDTH;
        this.imageHeight = TabbedMqttMenu.GUI_HEIGHT;
        this.inventoryLabelY = this.imageHeight - 94;
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

        activeView = MqttTabViews.create(this.menu.getActiveTabId(), this);
        activeView.init(this, this.leftPos, this.topPos);
        for (GuiEventListener w : activeView.widgets()) {
            if (w instanceof AbstractWidget aw) {
                addRenderableWidget(aw);
                tabViewWidgets.add(aw);
            }
        }
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
        NetworkManager.sendToServer(new MineqttNetworking.SetActiveTabPayload(this.menu.blockPos, tabId));
        rebuildActiveView();
    }

    // ===== Layout helpers =====

    private int tabBarX() {
        return this.leftPos;
    }

    private int tabBarY() {
        return this.topPos - TAB_H + TAB_OVERLAP;
    }

    private int tabBoundsX(int index) {
        return tabBarX() + index * (TAB_W + TAB_SPACING);
    }

    private int tabIndexAt(double mouseX, double mouseY) {
        int by = tabBarY();
        if (mouseY < by || mouseY >= by + TAB_H) return -1;
        for (int i = 0; i < this.menu.tabs.size(); i++) {
            int bx = tabBoundsX(i);
            if (mouseX >= bx && mouseX < bx + TAB_W) return i;
        }
        return -1;
    }

    // ===== Rendering =====

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
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

    private void renderTabBar(GuiGraphics g) {
        for (int i = 0; i < this.menu.tabs.size(); i++) {
            MqttTab tab = this.menu.tabs.get(i);
            boolean active = tab.id().equals(this.menu.getActiveTabId());
            int bx = tabBoundsX(i);
            int by = tabBarY();
            int innerColor = active ? 0xFFC6C6C6 : 0xFF6E6E6E;
            int outline = active ? 0xFF1F1F1F : 0xFF2A2A2A;
            // Outline
            g.fill(bx - 1, by - 1, bx + TAB_W + 1, by + TAB_H + 1, outline);
            // Body
            g.fill(bx, by, bx + TAB_W, by + TAB_H, innerColor);
            // Hide bottom border on active tab so it blends into the GUI
            if (active) {
                g.fill(bx, by + TAB_H - 1, bx + TAB_W, by + TAB_H + TAB_OVERLAP, 0xFFC6C6C6);
            }
            // Icon
            ItemStack icon = tab.icon();
            if (!icon.isEmpty()) {
                int iconX = bx + (TAB_W - 16) / 2;
                int iconY = by + (TAB_H - TAB_OVERLAP - 16) / 2 + 2;
                g.renderItem(icon, iconX, iconY);
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        MqttTab tab = this.menu.getActiveTab();
        Component title = tab != null ? tab.title() : this.title;
        // renderLabels is called inside a translated pose (origin = guiLeft,guiTop).
        // Convert the absolute mouse coords to the same local space so tooltips work.
        int localMX = lastMouseX == Integer.MIN_VALUE ? Integer.MIN_VALUE : lastMouseX - this.leftPos;
        int localMY = lastMouseY == Integer.MIN_VALUE ? Integer.MIN_VALUE : lastMouseY - this.topPos;
        // Centered title — truncated so a long localized name can never overflow the GUI.
        GuiText.drawCentered(g, title, 0, 6, this.imageWidth, 0x404040, localMX, localMY);
        // Inventory label — capped to the inventory width.
        GuiText.drawTruncated(g, this.playerInventoryTitle, 8, this.inventoryLabelY, 9 * 18, 0x404040, localMX, localMY);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        this.renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);
        if (activeView != null) {
            activeView.renderContent(g, this.leftPos, this.topPos, mouseX, mouseY, partialTick);
            activeView.renderOverlay(g, this.leftPos, this.topPos, mouseX, mouseY, partialTick);
        }
        // Tab tooltips
        int tabIdx = tabIndexAt(mouseX, mouseY);
        if (tabIdx >= 0) {
            g.setTooltipForNextFrame(this.font, this.menu.tabs.get(tabIdx).title(), mouseX, mouseY);
        }
        this.renderTooltip(g, mouseX, mouseY);
    }

    // ===== Input =====

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
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
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void playClickSound() {
        Minecraft.getInstance().getSoundManager().play(
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (activeView != null && activeView.mouseReleased(mouseX, mouseY, button)) return true;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (activeView != null && activeView.mouseDragged(mouseX, mouseY, button, dx, dy)) return true;
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (activeView != null && activeView.mouseScrolled(mouseX, mouseY, dx, dy)) return true;
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (activeView != null && (activeView.isModal() || activeView.keyPressed(keyCode, scanCode, modifiers))) {
            if (activeView.isModal()) {
                activeView.keyPressed(keyCode, scanCode, modifiers);
                return true;
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (activeView != null && activeView.charTyped(c, modifiers)) return true;
        return super.charTyped(c, modifiers);
    }
}

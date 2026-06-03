package art.rehra.mineqtt.ui.framework;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Client-side counterpart of a {@link MqttTab}: renders the tab's content and
 * handles input. Created via {@link MqttTabViews} when the screen switches tabs.
 *
 * <p>The view is created fresh every time the player switches to its tab; any
 * widgets it returns from {@link #widgets()} are registered with the screen
 * (and unregistered when leaving the tab).</p>
 */
public interface MqttTabView {

    /**
     * Called once when this view becomes active. Override to compute layout / create widgets.
     */
    default void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
    }

    /**
     * Widgets returned here are added to the screen via {@code addRenderableWidget}.
     */
    default List<? extends GuiEventListener> widgets() {
        return List.of();
    }

    /**
     * Renders content under the player inventory area (above the inventory rows).
     */
    default void renderContent(GuiGraphics g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
    }

    /**
     * Optional overlay rendered above slots/widgets (e.g. picker popups).
     */
    default void renderOverlay(GuiGraphics g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
    }

    default boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    default boolean mouseReleased(double mouseX, double mouseY, int button) {
        return false;
    }

    default boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        return false;
    }

    default boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        return false;
    }

    default boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    default boolean charTyped(char c, int modifiers) {
        return false;
    }

    /**
     * Return true if this view is in a modal state (color picker, popup, …) so that the screen swallows other input.
     */
    default boolean isModal() {
        return false;
    }

    /**
     * Called when the player shift-clicks an item stack in their inventory while this view is active.
     * Return {@code true} to consume the click (suppressing the vanilla quick-move behavior).
     * The provided {@code stack} is a snapshot; do not mutate it.
     */
    default boolean onShiftClickItem(ItemStack stack) {
        return false;
    }
}

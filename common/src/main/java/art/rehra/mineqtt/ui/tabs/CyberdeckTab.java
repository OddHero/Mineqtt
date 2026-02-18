package art.rehra.mineqtt.ui.tabs;

import net.minecraft.client.gui.GuiGraphics;

public interface CyberdeckTab {
    void onInit();

    void onActivated();

    void onDeactivated();

    void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY);

    boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY);

    boolean keyPressed(int keyCode, int scanCode, int modifiers);
}

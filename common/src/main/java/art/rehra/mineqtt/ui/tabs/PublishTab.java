package art.rehra.mineqtt.ui.tabs;

import art.rehra.mineqtt.ui.CyberdeckScreen;
import net.minecraft.client.gui.GuiGraphics;

public class PublishTab implements CyberdeckTab {
    private final CyberdeckScreen screen;

    public PublishTab(CyberdeckScreen screen) {
        this.screen = screen;
    }

    @Override
    public void onInit() {
        // Controls are created by the screen; nothing to add here
        screen.setPublishControlsVisible(false);
    }

    @Override
    public void onActivated() {
        screen.setPublishControlsVisible(true);
    }

    @Override
    public void onDeactivated() {
        screen.setPublishControlsVisible(false);
    }

    @Override
    public void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(screen.getFont(), "Manual Publish", 8, 54, 0xFF555555, false);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }
}

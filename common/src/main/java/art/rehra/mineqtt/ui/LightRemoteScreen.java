package art.rehra.mineqtt.ui;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.entities.LightRemoteBlockEntity;
import art.rehra.mineqtt.network.MineqttNetworking;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

public class LightRemoteScreen extends AbstractContainerScreen<LightRemoteMenu> {

    public static final ResourceLocation BACKGROUND = ResourceLocation.fromNamespaceAndPath(MineQTT.MOD_ID, "textures/gui/light_remote/topic_screen.png");

    private static final int MARGIN = 8;
    private static final int TITLE_Y = 6;
    private static final int INFO_START_Y = 5;
    private static final int LINE_HEIGHT = 10;
    private static final int MAX_TEXT_WIDTH = 160;
    // Brightness step for increment/decrement
    private static final int BRIGHTNESS_STEP = 25;
    private static final int KELVIN_STEP = 500;
    private static final int KELVIN_MIN = 2000;
    private static final int KELVIN_MAX = 6500;
    // Button dimensions
    private static final int BTN_W = 50;
    private static final int BTN_H = 16;
    private static final int BTN_SMALL = 20;
    // Current light state tracked on the client
    private boolean lightOn = false;
    private int brightness = 255;
    private int red = 255;
    private int green = 255;
    private int blue = 255;
    private int kelvin = 4000;
    private float transition = 1.0f;
    // Tracks which color descriptor was last changed: "rgb" or "kelvin"
    private String lastColorMode = "kelvin";

    private static final int PICKER_W = 160;
    private static final int PICKER_H = 92;
    private static final int PICKER_PAD = 8;
    private static final int SLIDER_H = 10;
    private static final int SLIDER_SPACING = 6;
    // === Color picker popup state ===
    private boolean colorPickerOpen = false;
    // Picker geometry (relative to screen, computed on open)
    private int pickerX, pickerY;
    // Color preview swatch bounds (set in renderRemoteInfo)
    private int previewX0, previewY0, previewX1, previewY1;
    // Which slider is being dragged: 0=R, 1=G, 2=B, -1=none
    private int draggingSlider = -1;

    public LightRemoteScreen(LightRemoteMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.font = Minecraft.getInstance().font;
        this.imageHeight = 280;
        this.inventoryLabelY = 186;
    }

    @Override
    protected void init() {
        super.init();

        int guiLeft = (this.width - this.imageWidth) / 2;
        int guiTop = (this.height - this.imageHeight) / 2;

        // === Power Buttons Row (y = 44) ===
        int powerY = guiTop + 44;
        addRenderableWidget(Button.builder(Component.literal("§a⬤ ON"), btn -> {
            lightOn = true;
            sendCommand();
        }).bounds(guiLeft + 10, powerY, 70, BTN_H).build());

        addRenderableWidget(Button.builder(Component.literal("§c⬤ OFF"), btn -> {
            lightOn = false;
            sendCommand();
        }).bounds(guiLeft + 96, powerY, 70, BTN_H).build());

        // === Brightness Row (y = 64) ===
        int brightY = guiTop + 64;
        addRenderableWidget(Button.builder(Component.literal("−"), btn -> {
            brightness = Math.max(0, brightness - BRIGHTNESS_STEP);
            sendCommand();
        }).bounds(guiLeft + 10, brightY, BTN_SMALL, BTN_H).build());

        addRenderableWidget(Button.builder(Component.literal("+"), btn -> {
            brightness = Math.min(255, brightness + BRIGHTNESS_STEP);
            sendCommand();
        }).bounds(guiLeft + 146, brightY, BTN_SMALL, BTN_H).build());

        // === Color Preset Row 1 (y = 84) ===
        int colorY1 = guiTop + 84;
        int colorBtnW = 30;
        int colorSpacing = 4;
        int colorStartX = guiLeft + 10;

        // Red
        addRenderableWidget(Button.builder(Component.literal("§cR"), btn -> {
            red = 255;
            green = 0;
            blue = 0;
            lastColorMode = "rgb";
            lightOn = true;
            sendCommand();
        }).bounds(colorStartX, colorY1, colorBtnW, BTN_H).build());

        // Green
        addRenderableWidget(Button.builder(Component.literal("§aG"), btn -> {
            red = 0;
            green = 255;
            blue = 0;
            lastColorMode = "rgb";
            lightOn = true;
            sendCommand();
        }).bounds(colorStartX + colorBtnW + colorSpacing, colorY1, colorBtnW, BTN_H).build());

        // Blue
        addRenderableWidget(Button.builder(Component.literal("§9B"), btn -> {
            red = 0;
            green = 0;
            blue = 255;
            lastColorMode = "rgb";
            lightOn = true;
            sendCommand();
        }).bounds(colorStartX + 2 * (colorBtnW + colorSpacing), colorY1, colorBtnW, BTN_H).build());

        // White
        addRenderableWidget(Button.builder(Component.literal("§fW"), btn -> {
            red = 255;
            green = 255;
            blue = 255;
            lastColorMode = "rgb";
            lightOn = true;
            sendCommand();
        }).bounds(colorStartX + 3 * (colorBtnW + colorSpacing), colorY1, colorBtnW, BTN_H).build());

        // Yellow
        addRenderableWidget(Button.builder(Component.literal("§eY"), btn -> {
            red = 255;
            green = 200;
            blue = 0;
            lastColorMode = "rgb";
            lightOn = true;
            sendCommand();
        }).bounds(colorStartX + 4 * (colorBtnW + colorSpacing) - 2, colorY1, colorBtnW, BTN_H).build());

        // === Color Preset Row 2 (y = 104) ===
        int colorY2 = guiTop + 104;

        // Cyan
        addRenderableWidget(Button.builder(Component.literal("§bC"), btn -> {
            red = 0;
            green = 255;
            blue = 255;
            lastColorMode = "rgb";
            lightOn = true;
            sendCommand();
        }).bounds(colorStartX, colorY2, colorBtnW, BTN_H).build());

        // Magenta
        addRenderableWidget(Button.builder(Component.literal("§dM"), btn -> {
            red = 255;
            green = 0;
            blue = 255;
            lastColorMode = "rgb";
            lightOn = true;
            sendCommand();
        }).bounds(colorStartX + colorBtnW + colorSpacing, colorY2, colorBtnW, BTN_H).build());

        // Orange
        addRenderableWidget(Button.builder(Component.literal("§6O"), btn -> {
            red = 255;
            green = 100;
            blue = 0;
            lastColorMode = "rgb";
            lightOn = true;
            sendCommand();
        }).bounds(colorStartX + 2 * (colorBtnW + colorSpacing), colorY2, colorBtnW, BTN_H).build());

        // Purple
        addRenderableWidget(Button.builder(Component.literal("§5P"), btn -> {
            red = 128;
            green = 0;
            blue = 255;
            lastColorMode = "rgb";
            lightOn = true;
            sendCommand();
        }).bounds(colorStartX + 3 * (colorBtnW + colorSpacing), colorY2, colorBtnW, BTN_H).build());

        // Warm White
        addRenderableWidget(Button.builder(Component.literal("§eWW"), btn -> {
            red = 255;
            green = 180;
            blue = 100;
            lastColorMode = "rgb";
            lightOn = true;
            sendCommand();
        }).bounds(colorStartX + 4 * (colorBtnW + colorSpacing) - 2, colorY2, colorBtnW, BTN_H).build());

        // === Kelvin Row (y = 124) ===
        int kelvinY = guiTop + 124;
        addRenderableWidget(Button.builder(Component.literal("−K"), btn -> {
            kelvin = Math.max(KELVIN_MIN, kelvin - KELVIN_STEP);
            lastColorMode = "kelvin";
            sendCommand();
        }).bounds(guiLeft + 10, kelvinY, BTN_SMALL + 5, BTN_H).build());

        addRenderableWidget(Button.builder(Component.literal("+K"), btn -> {
            kelvin = Math.min(KELVIN_MAX, kelvin + KELVIN_STEP);
            lastColorMode = "kelvin";
            sendCommand();
        }).bounds(guiLeft + 141, kelvinY, BTN_SMALL + 5, BTN_H).build());

        // === Transition Row (y = 144) ===
        int transY = guiTop + 144;
        addRenderableWidget(Button.builder(Component.literal("−"), btn -> {
            transition = Math.max(0.0f, Math.round((transition - 0.1f) * 10) / 10.0f);
        }).bounds(guiLeft + 10, transY, BTN_SMALL, BTN_H).build());

        addRenderableWidget(Button.builder(Component.literal("+"), btn -> {
            transition = Math.round((transition + 0.1f) * 10) / 10.0f;
        }).bounds(guiLeft + 146, transY, BTN_SMALL, BTN_H).build());
    }

    private void sendCommand() {
        var pos = this.menu.blockPos;
        int brightnessPct = Math.round((brightness / 255.0f) * 100);
        String colorPart;
        if ("rgb".equals(lastColorMode)) {
            colorPart = String.format(java.util.Locale.US, ",\"rgb\":[%d,%d,%d]", red, green, blue);
        } else {
            colorPart = String.format(java.util.Locale.US, ",\"kelvin\":%d", kelvin);
        }
        String json = String.format(java.util.Locale.US,
                "{\"state\":\"%s\",\"brightness\":%d,\"brightness_pct\":%d%s,\"transition\":%.1f}",
                lightOn ? "ON" : "OFF",
                brightness,
                brightnessPct,
                colorPart,
                transition
        );
        NetworkManager.sendToServer(new MineqttNetworking.LightRemoteCommandPayload(pos, json));
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        Component title = Component.literal("Light Remote");
        int titleWidth = this.font.width(title);
        int titleX = (this.imageWidth - titleWidth) / 2;
        guiGraphics.drawString(this.font, title, titleX, TITLE_Y, 0x404040, false);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int guiLeft = (this.width - this.imageWidth) / 2;
        int guiTop = (this.height - this.imageHeight) / 2;
        guiGraphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, guiLeft, guiTop, 0.0F, 0.0F, this.imageWidth, this.imageHeight, 256, 512);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int guiLeft = (this.width - this.imageWidth) / 2;
        int guiTop = (this.height - this.imageHeight) / 2;

        var player = this.menu.player;
        var pos = this.menu.blockPos;
        var level = player.level();
        var blockEntity = level.getBlockEntity(pos);

        if (blockEntity instanceof LightRemoteBlockEntity lightRemote) {
            renderRemoteInfo(guiGraphics, guiLeft, guiTop, lightRemote);
        }

        if (colorPickerOpen) {
            renderColorPicker(guiGraphics, mouseX, mouseY);
        }

        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    private void openColorPicker() {
        // Position picker near the swatch but inside the screen
        pickerX = previewX0;
        pickerY = previewY0 - PICKER_H - 4;
        if (pickerY < 4) pickerY = previewY1 + 4;
        colorPickerOpen = true;
    }

    private void closeColorPicker() {
        colorPickerOpen = false;
        draggingSlider = -1;
    }

    private int sliderY(int idx) {
        return pickerY + PICKER_PAD + 12 + idx * (SLIDER_H + SLIDER_SPACING);
    }

    private int sliderX() {
        return pickerX + PICKER_PAD + 12;
    }

    private int sliderW() {
        return PICKER_W - PICKER_PAD * 2 - 12 - 30;
    }

    private void renderColorPicker(GuiGraphics g, int mouseX, int mouseY) {
        // Full-screen dim scrim to visually indicate modality (and block clicks via input handlers)
        g.fill(0, 0, this.width, this.height, 0x80000000);

        int x = pickerX, y = pickerY;
        int w = PICKER_W, h = PICKER_H;
        // Background panel
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xFF202020);
        g.fill(x, y, x + w, y + h, 0xFFE0E0E0);

        // Title
        g.drawString(this.font, "RGB Color", x + PICKER_PAD, y + PICKER_PAD - 2, 0xFF222222, false);

        int sx = sliderX();
        int sw = sliderW();
        int[] values = {red, green, blue};
        String[] labels = {"R", "G", "B"};
        int[] trackColors = {0xFFCC3030, 0xFF30AA30, 0xFF3060CC};
        for (int i = 0; i < 3; i++) {
            int sy = sliderY(i);
            // Label
            g.drawString(this.font, labels[i], x + PICKER_PAD, sy + 1, 0xFF222222, false);
            // Track
            g.fill(sx, sy, sx + sw, sy + SLIDER_H, 0xFF555555);
            // Fill proportional to value
            int filled = Math.round(values[i] / 255f * sw);
            g.fill(sx, sy, sx + filled, sy + SLIDER_H, trackColors[i]);
            // Knob
            int kx = sx + filled;
            g.fill(kx - 1, sy - 1, kx + 2, sy + SLIDER_H + 1, 0xFFFFFFFF);
            // Value text
            String vs = String.valueOf(values[i]);
            g.drawString(this.font, vs, sx + sw + 4, sy + 1, 0xFF222222, false);
        }

        // Current color preview
        int prevY = sliderY(2) + SLIDER_H + 6;
        int curColor = 0xFF000000 | (red << 16) | (green << 8) | blue;
        g.fill(x + PICKER_PAD, prevY, x + w - PICKER_PAD, prevY + 10, curColor);
        g.renderOutline(x + PICKER_PAD, prevY, w - PICKER_PAD * 2, 10, 0xFF333333);

        // Close text (top-right)
        String close = "[X]";
        int cw = this.font.width(close);
        g.drawString(this.font, close, x + w - cw - 3, y - 1, 0xFF990000, false);
    }

    private int sliderHit(double mx, double my) {
        int sx = sliderX();
        int sw = sliderW();
        for (int i = 0; i < 3; i++) {
            int sy = sliderY(i);
            if (mx >= sx && mx < sx + sw && my >= sy - 1 && my < sy + SLIDER_H + 1) {
                return i;
            }
        }
        return -1;
    }

    private void updateSliderFromMouse(int idx, double mx) {
        int sx = sliderX();
        int sw = sliderW();
        float t = (float) ((mx - sx) / (double) sw);
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        int v = Math.round(t * 255f);
        switch (idx) {
            case 0:
                red = v;
                break;
            case 1:
                green = v;
                break;
            case 2:
                blue = v;
                break;
        }
    }

    private boolean isInsidePicker(double mx, double my) {
        return mx >= pickerX - 2 && mx <= pickerX + PICKER_W + 2
                && my >= pickerY - 2 && my <= pickerY + PICKER_H + 2;
    }

    private void applyPickerColor() {
        lastColorMode = "rgb";
        lightOn = true;
        sendCommand();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (colorPickerOpen) {
            // Modal: swallow everything; do not delegate to super (which would hit underlying buttons)
            if (button == 0) {
                int hit = sliderHit(mouseX, mouseY);
                if (hit >= 0) {
                    draggingSlider = hit;
                    updateSliderFromMouse(hit, mouseX);
                    applyPickerColor();
                    return true;
                }
                if (!isInsidePicker(mouseX, mouseY)) {
                    closeColorPicker();
                    return true;
                }
            }
            return true; // swallow all input while modal
        }
        // Open picker on click of preview swatch
        if (button == 0 && mouseX >= previewX0 && mouseX < previewX1
                && mouseY >= previewY0 && mouseY < previewY1) {
            openColorPicker();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (colorPickerOpen) {
            if (button == 0 && draggingSlider >= 0) {
                updateSliderFromMouse(draggingSlider, mouseX);
                applyPickerColor();
            }
            return true; // swallow
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (colorPickerOpen) {
            if (button == 0) {
                draggingSlider = -1;
            }
            return true; // swallow
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dxs, double dys) {
        if (colorPickerOpen) {
            return true; // swallow scroll while modal
        }
        return super.mouseScrolled(mouseX, mouseY, dxs, dys);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (colorPickerOpen) {
            if (keyCode == 256 /* ESC */) {
                closeColorPicker();
                return true;
            }
            return true; // swallow all keys while modal (incl. inventory key 'E')
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (colorPickerOpen) {
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    private void renderRemoteInfo(GuiGraphics guiGraphics, int guiLeft, int guiTop, LightRemoteBlockEntity blockEntity) {
        int currentY = guiTop + INFO_START_Y;

        String combinedTopic = blockEntity.getCombinedTopic();
        boolean isEnabled = blockEntity.isEnabled();

        if (isEnabled && !combinedTopic.isEmpty()) {
            guiGraphics.drawString(this.font, "Topic:", guiLeft + MARGIN, currentY, 0xFF555555, false);
            currentY += LINE_HEIGHT;

            List<FormattedCharSequence> wrappedTopic = this.font.split(Component.literal(combinedTopic), MAX_TEXT_WIDTH);
            for (FormattedCharSequence line : wrappedTopic) {
                guiGraphics.drawString(this.font, line, guiLeft + MARGIN + 4, currentY, 0xFF0088FF, false);
                currentY += LINE_HEIGHT;
            }
        } else {
            guiGraphics.drawString(this.font, "No topic configured", guiLeft + MARGIN, currentY, 0xFF666666, false);
            currentY += LINE_HEIGHT;
            guiGraphics.drawString(this.font, "Place item in first slot to enable", guiLeft + MARGIN, currentY, 0xFF888888, false);
        }

        // Brightness label between the − and + buttons
        int brightY = guiTop + 64;
        String brightLabel = "Brightness: " + brightness + " (" + Math.round((brightness / 255.0f) * 100) + "%)";
        int brightWidth = this.font.width(brightLabel);
        guiGraphics.drawString(this.font, brightLabel, guiLeft + (this.imageWidth - brightWidth) / 2, brightY + 4, 0xFF333333, false);

        // Kelvin label
        int kelvinY = guiTop + 124;
        String kelvinLabel = "Kelvin: " + kelvin + "K";
        int kelvinWidth = this.font.width(kelvinLabel);
        guiGraphics.drawString(this.font, kelvinLabel, guiLeft + (this.imageWidth - kelvinWidth) / 2, kelvinY + 4, 0xFF333333, false);

        // Transition label between the − and + buttons
        int transY = guiTop + 144;
        String transLabel = String.format(java.util.Locale.US, "Transition: %.1fs", transition);
        int transWidth = this.font.width(transLabel);
        guiGraphics.drawString(this.font, transLabel, guiLeft + (this.imageWidth - transWidth) / 2, transY + 4, 0xFF333333, false);

        // Color preview swatch (clickable: opens color wheel popup)
        int previewY = guiTop + 162;
        int previewColor = 0xFF000000 | (red << 16) | (green << 8) | blue;
        this.previewX0 = guiLeft + 10;
        this.previewY0 = previewY;
        this.previewX1 = guiLeft + 166;
        this.previewY1 = previewY + 12;
        guiGraphics.fill(previewX0, previewY0, previewX1, previewY1, previewColor);
        guiGraphics.renderOutline(previewX0, previewY0, 156, 12, 0xFF333333);

        // State indicator
        String stateLabel = lightOn ? "§a⬤ ON" : "§c⬤ OFF";
        guiGraphics.drawString(this.font, stateLabel, guiLeft + 10, previewY + 15, 0xFFFFFFFF, false);
    }
}

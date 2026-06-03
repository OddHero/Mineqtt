package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.network.MineqttNetworking;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Tab view for the Light Remote: provides power, color presets, brightness/kelvin sliders,
 * an RGB color picker popup and a numeric input popup for sliders.
 *
 * <p>All input handling (sliders + modal popups) happens inside this view so the
 * underlying {@link TabbedMqttScreen} keeps being a clean container/inventory screen.</p>
 */
public class LightRemoteTabView implements MqttTabView {

    private static final int SLIDER_H = 8;
    private static final int PICKER_W = 160, PICKER_H = 92, PICKER_PAD = 8;
    private static final int PICK_SLIDER_H = 10, PICK_SPACING = 6;
    private static final int NUM_W = 120, NUM_H = 50;
    private static final int KELVIN_MIN = 2000, KELVIN_MAX = 6500;
    private static final float TRANS_MAX = 10.0f;
    private final TabbedMqttScreen screen;
    private final List<AbstractWidget> widgets = new ArrayList<>();
    // ---- Light state ----
    private boolean lightOn = false;
    private int brightness = 255;
    private int red = 255, green = 255, blue = 255;
    private int kelvin = 4000;
    private float transition = 1.0f;
    private String lastColorMode = "kelvin";
    // ---- Layout (filled in init/renderContent) ----
    private int guiLeft, guiTop;
    private int brightSliderX, brightSliderY, brightSliderW;
    private int kelvinSliderX, kelvinSliderY, kelvinSliderW;
    private int previewX0, previewY0, previewX1, previewY1;
    // ---- Drag/modal state ----
    private int draggingMainSlider = -1; // 0=bright, 1=kelvin
    private boolean pickerOpen = false;
    private int pickerX, pickerY;
    private int draggingPickerSlider = -1; // 0=R,1=G,2=B
    private boolean numInputOpen = false;
    private int numInputTarget = -1; // 0=bright, 1=kelvin, 2=transition
    private String numInputBuf = "";
    private int numInputX, numInputY;

    public LightRemoteTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        this.guiLeft = guiLeft;
        this.guiTop = guiTop;

        int btnH = 14;
        int powerY = guiTop + 18;
        widgets.add(Button.builder(Component.literal("§a⬤ ON"), b -> {
                    lightOn = true;
                    sendCommand();
                })
                .bounds(guiLeft + 8, powerY, 78, btnH).build());
        widgets.add(Button.builder(Component.literal("§c⬤ OFF"), b -> {
                    lightOn = false;
                    sendCommand();
                })
                .bounds(guiLeft + 90, powerY, 78, btnH).build());

        // Color presets — single row of 6 small buttons (R G B W Y + picker)
        int presetY = guiTop + 76;
        int presetH = 14;
        int[] presetColors = {0xCC0000, 0x00CC00, 0x0066CC, 0xFFFFFF, 0xFFCC00, 0x00CCCC, 0xCC00CC, 0xCC6600, 0x8000FF, 0xFFB464};
        String[] presetLabels = {"§cR", "§aG", "§9B", "§fW", "§eY", "§bC", "§dM", "§6O", "§5P", "§eWW"};
        int n = presetLabels.length;
        int sp = 1;
        int totalW = 176 - 16; // available width
        int pw = (totalW - (n - 1) * sp) / n;
        for (int i = 0; i < n; i++) {
            int color = presetColors[i];
            String label = presetLabels[i];
            widgets.add(Button.builder(Component.literal(label), b -> {
                this.red = (color >> 16) & 0xFF;
                this.green = (color >> 8) & 0xFF;
                this.blue = color & 0xFF;
                this.lastColorMode = "rgb";
                this.lightOn = true;
                sendCommand();
            }).bounds(guiLeft + 8 + i * (pw + sp), presetY, pw, presetH).build());
        }
    }

    @Override
    public List<? extends net.minecraft.client.gui.components.events.GuiEventListener> widgets() {
        return widgets;
    }

    // ---- Network ----

    private void sendCommand() {
        int brightnessPct = Math.round((brightness / 255.0f) * 100);
        String colorPart = "rgb".equals(lastColorMode)
                ? String.format(java.util.Locale.US, ",\"rgb\":[%d,%d,%d]", red, green, blue)
                : String.format(java.util.Locale.US, ",\"kelvin\":%d", kelvin);
        String json = String.format(java.util.Locale.US,
                "{\"state\":\"%s\",\"brightness\":%d,\"brightness_pct\":%d%s,\"transition\":%.1f}",
                lightOn ? "ON" : "OFF", brightness, brightnessPct, colorPart, transition);
        NetworkManager.sendToServer(new MineqttNetworking.LightRemoteCommandPayload(java.util.Optional.ofNullable(screen.getMenu().blockPos), json));
    }

    // ---- Rendering ----

    @Override
    public void renderContent(GuiGraphics g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        this.guiLeft = guiLeft;
        this.guiTop = guiTop;
        var font = Minecraft.getInstance().font;

        int sliderX = guiLeft + 8;
        int sliderW = TabbedMqttMenu.GUI_WIDTH - 16; // keep label/slider within panel

        // Brightness
        int by = guiTop + 36;
        GuiText.drawTruncated(g, "Brightness: " + brightness + " (" + Math.round((brightness / 255f) * 100) + "%)",
                sliderX, by, sliderW, 0xFF333333, mouseX, mouseY);
        brightSliderX = sliderX;
        brightSliderY = by + 11;
        brightSliderW = sliderW;
        drawSlider(g, brightSliderX, brightSliderY, brightSliderW, brightness / 255f, 0xFFCCAA30);

        // Kelvin
        int ky = guiTop + 56;
        GuiText.drawTruncated(g, "Kelvin: " + kelvin + "K", sliderX, ky, sliderW, 0xFF333333, mouseX, mouseY);
        kelvinSliderX = sliderX;
        kelvinSliderY = ky + 11;
        kelvinSliderW = sliderW;
        float kt = (kelvin - KELVIN_MIN) / (float) (KELVIN_MAX - KELVIN_MIN);
        drawSlider(g, kelvinSliderX, kelvinSliderY, kelvinSliderW, kt, 0xFF60A0E0);

        // Preview swatch
        int swatchY = guiTop + 92;
        int color = 0xFF000000 | (red << 16) | (green << 8) | blue;
        previewX0 = sliderX;
        previewY0 = swatchY;
        previewX1 = sliderX + sliderW;
        previewY1 = swatchY + 12;
        g.fill(previewX0, previewY0, previewX1, previewY1, color);
        g.renderOutline(previewX0, previewY0, sliderW, 12, 0xFF333333);
        // Drawn directly (not via GuiText) because it uses dropShadow + § color codes.
        String state = lightOn ? "§a⬤ ON" : "§c⬤ OFF";
        g.drawString(font, state, previewX1 - font.width(state) - 4, previewY0 + 2, 0xFFFFFFFF, true);
    }

    @Override
    public void renderOverlay(GuiGraphics g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        if (pickerOpen) renderPicker(g);
        if (numInputOpen) renderNumInput(g);
    }

    private void drawSlider(GuiGraphics g, int x, int y, int w, float t, int fill) {
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        g.fill(x, y, x + w, y + SLIDER_H, 0xFF555555);
        int filled = Math.round(t * w);
        g.fill(x, y, x + filled, y + SLIDER_H, fill);
        int kx = x + filled;
        g.fill(kx - 1, y - 1, kx + 2, y + SLIDER_H + 1, 0xFFFFFFFF);
    }

    // ---- Picker ----

    private void openPicker() {
        pickerX = Math.max(4, Math.min(screen.width - PICKER_W - 4, previewX0));
        pickerY = Math.max(4, previewY0 - PICKER_H - 4);
        pickerOpen = true;
    }

    private void closePicker() {
        pickerOpen = false;
        draggingPickerSlider = -1;
    }

    private int pickSliderY(int idx) {
        return pickerY + PICKER_PAD + 12 + idx * (PICK_SLIDER_H + PICK_SPACING);
    }

    private int pickSliderX() {
        return pickerX + PICKER_PAD + 12;
    }

    private int pickSliderW() {
        return PICKER_W - PICKER_PAD * 2 - 12 - 30;
    }

    private void renderPicker(GuiGraphics g) {
        g.fill(0, 0, screen.width, screen.height, 0x80000000);
        var font = Minecraft.getInstance().font;
        int x = pickerX, y = pickerY, w = PICKER_W, h = PICKER_H;
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xFF202020);
        g.fill(x, y, x + w, y + h, 0xFFE0E0E0);
        GuiText.drawTruncated(g, "RGB Color", x + PICKER_PAD, y + PICKER_PAD - 2, w - PICKER_PAD * 2, 0xFF222222);
        int sx = pickSliderX(), sw = pickSliderW();
        int[] vals = {red, green, blue};
        String[] labels = {"R", "G", "B"};
        int[] colors = {0xFFCC3030, 0xFF30AA30, 0xFF3060CC};
        for (int i = 0; i < 3; i++) {
            int sy = pickSliderY(i);
            GuiText.drawTruncated(g, labels[i], x + PICKER_PAD, sy + 1, 10, 0xFF222222);
            g.fill(sx, sy, sx + sw, sy + PICK_SLIDER_H, 0xFF555555);
            int filled = Math.round(vals[i] / 255f * sw);
            g.fill(sx, sy, sx + filled, sy + PICK_SLIDER_H, colors[i]);
            int kx = sx + filled;
            g.fill(kx - 1, sy - 1, kx + 2, sy + PICK_SLIDER_H + 1, 0xFFFFFFFF);
            // Numeric value, capped to the right-margin reservation (30px).
            GuiText.drawTruncated(g, String.valueOf(vals[i]), sx + sw + 4, sy + 1, 26, 0xFF222222);
        }
        int prevY = pickSliderY(2) + PICK_SLIDER_H + 6;
        int cur = 0xFF000000 | (red << 16) | (green << 8) | blue;
        g.fill(x + PICKER_PAD, prevY, x + w - PICKER_PAD, prevY + 10, cur);
        g.renderOutline(x + PICKER_PAD, prevY, w - PICKER_PAD * 2, 10, 0xFF333333);
        String close = "[X]";
        GuiText.drawRight(g, close, x + w - 3, y - 1, 20, 0xFF990000);
    }

    private int pickerSliderHit(double mx, double my) {
        int sx = pickSliderX(), sw = pickSliderW();
        for (int i = 0; i < 3; i++) {
            int sy = pickSliderY(i);
            if (mx >= sx && mx < sx + sw && my >= sy - 1 && my < sy + PICK_SLIDER_H + 1) return i;
        }
        return -1;
    }

    private void setPickerSlider(int idx, double mx) {
        int sx = pickSliderX(), sw = pickSliderW();
        float t = (float) ((mx - sx) / (double) sw);
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        int v = Math.round(t * 255f);
        switch (idx) {
            case 0 -> red = v;
            case 1 -> green = v;
            case 2 -> blue = v;
        }
    }

    private boolean insidePicker(double mx, double my) {
        return mx >= pickerX - 2 && mx <= pickerX + PICKER_W + 2 && my >= pickerY - 2 && my <= pickerY + PICKER_H + 2;
    }

    // ---- Num input ----

    private void openNumInput(int target, double mx, double my) {
        numInputTarget = target;
        numInputOpen = true;
        switch (target) {
            case 0 -> numInputBuf = String.valueOf(brightness);
            case 1 -> numInputBuf = String.valueOf(kelvin);
            case 2 -> numInputBuf = String.format(java.util.Locale.US, "%.1f", transition);
        }
        numInputX = (int) Math.max(4, Math.min(screen.width - NUM_W - 4, mx - NUM_W / 2.0));
        numInputY = (int) Math.max(4, Math.min(screen.height - NUM_H - 4, my - NUM_H - 4));
    }

    private void closeNumInput() {
        numInputOpen = false;
        numInputTarget = -1;
        numInputBuf = "";
    }

    private void commitNumInput() {
        try {
            switch (numInputTarget) {
                case 0 -> {
                    brightness = Math.max(0, Math.min(255, Integer.parseInt(numInputBuf.trim())));
                    sendCommand();
                }
                case 1 -> {
                    kelvin = Math.max(KELVIN_MIN, Math.min(KELVIN_MAX, Integer.parseInt(numInputBuf.trim())));
                    lastColorMode = "kelvin";
                    sendCommand();
                }
                case 2 -> {
                    float v = Float.parseFloat(numInputBuf.trim());
                    if (v < 0) v = 0;
                    if (v > TRANS_MAX) v = TRANS_MAX;
                    transition = Math.round(v * 10) / 10f;
                }
            }
        } catch (NumberFormatException ignored) {
        }
        closeNumInput();
    }

    private void renderNumInput(GuiGraphics g) {
        g.fill(0, 0, screen.width, screen.height, 0x80000000);
        var font = Minecraft.getInstance().font;
        int x = numInputX, y = numInputY, w = NUM_W, h = NUM_H;
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xFF202020);
        g.fill(x, y, x + w, y + h, 0xFFE0E0E0);
        String[] titles = {"Brightness (0-255)", "Kelvin (" + KELVIN_MIN + "-" + KELVIN_MAX + ")", "Transition (s)"};
        GuiText.drawTruncated(g, numInputTarget >= 0 ? titles[numInputTarget] : "",
                x + 6, y + 4, w - 12, 0xFF222222);
        int boxX = x + 6, boxY = y + 18, boxW = w - 12, boxH = 14;
        g.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xFFFFFFFF);
        g.renderOutline(boxX, boxY, boxW, boxH, 0xFF333333);
        GuiText.drawTruncated(g, numInputBuf + "_", boxX + 3, boxY + 3, boxW - 6, 0xFF000000);
        GuiText.drawTruncated(g, "Enter=OK  ESC=Cancel", x + 6, y + h - 10, w - 12, 0xFF555555);
    }

    private boolean insideNumInput(double mx, double my) {
        return mx >= numInputX - 2 && mx <= numInputX + NUM_W + 2 && my >= numInputY - 2 && my <= numInputY + NUM_H + 2;
    }

    // ---- Main-slider hit testing ----

    private int mainSliderHit(double mx, double my) {
        if (mx >= brightSliderX && mx < brightSliderX + brightSliderW
                && my >= brightSliderY - 1 && my < brightSliderY + SLIDER_H + 1) return 0;
        if (mx >= kelvinSliderX && mx < kelvinSliderX + kelvinSliderW
                && my >= kelvinSliderY - 1 && my < kelvinSliderY + SLIDER_H + 1) return 1;
        return -1;
    }

    private void setMainSlider(int idx, double mx) {
        int sx, sw;
        switch (idx) {
            case 0 -> {
                sx = brightSliderX;
                sw = brightSliderW;
            }
            case 1 -> {
                sx = kelvinSliderX;
                sw = kelvinSliderW;
            }
            default -> {
                return;
            }
        }
        float t = (float) ((mx - sx) / (double) sw);
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        if (idx == 0) {
            brightness = Math.round(t * 255f);
            sendCommand();
        } else {
            kelvin = Math.round(KELVIN_MIN + t * (KELVIN_MAX - KELVIN_MIN));
            lastColorMode = "kelvin";
            sendCommand();
        }
    }

    // ---- Input handling ----

    @Override
    public boolean isModal() {
        return pickerOpen || numInputOpen;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (numInputOpen) {
            if (button == 0 && !insideNumInput(mouseX, mouseY)) closeNumInput();
            return true;
        }
        if (pickerOpen) {
            if (button == 0) {
                int hit = pickerSliderHit(mouseX, mouseY);
                if (hit >= 0) {
                    draggingPickerSlider = hit;
                    setPickerSlider(hit, mouseX);
                    lastColorMode = "rgb";
                    lightOn = true;
                    sendCommand();
                    return true;
                }
                if (!insidePicker(mouseX, mouseY)) {
                    closePicker();
                }
            }
            return true;
        }
        int hit = mainSliderHit(mouseX, mouseY);
        if (hit >= 0) {
            if (button == 1) {
                openNumInput(hit, mouseX, mouseY);
                return true;
            }
            if (button == 0) {
                draggingMainSlider = hit;
                setMainSlider(hit, mouseX);
                return true;
            }
        }
        if (button == 0 && mouseX >= previewX0 && mouseX < previewX1 && mouseY >= previewY0 && mouseY < previewY1) {
            openPicker();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (numInputOpen) return true;
        if (pickerOpen) {
            if (button == 0 && draggingPickerSlider >= 0) {
                setPickerSlider(draggingPickerSlider, mouseX);
                lastColorMode = "rgb";
                lightOn = true;
                sendCommand();
            }
            return true;
        }
        if (draggingMainSlider >= 0 && button == 0) {
            setMainSlider(draggingMainSlider, mouseX);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (numInputOpen) return true;
        if (draggingMainSlider >= 0 && button == 0) {
            draggingMainSlider = -1;
            return true;
        }
        if (pickerOpen) {
            if (button == 0) draggingPickerSlider = -1;
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (numInputOpen) {
            if (keyCode == 256) {
                closeNumInput();
                return true;
            }
            if (keyCode == 257 || keyCode == 335) {
                commitNumInput();
                return true;
            }
            if (keyCode == 259) {
                if (!numInputBuf.isEmpty()) numInputBuf = numInputBuf.substring(0, numInputBuf.length() - 1);
                return true;
            }
            return true;
        }
        if (pickerOpen) {
            if (keyCode == 256) {
                closePicker();
                return true;
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (numInputOpen) {
            boolean allowDot = numInputTarget == 2 && numInputBuf.indexOf('.') < 0;
            if ((c >= '0' && c <= '9') || (allowDot && c == '.')) {
                if (numInputBuf.length() < 10) numInputBuf += c;
            }
            return true;
        }
        return pickerOpen;
    }
}

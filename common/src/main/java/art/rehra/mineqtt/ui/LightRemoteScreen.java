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

        this.renderTooltip(guiGraphics, mouseX, mouseY);
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

        // Color preview swatch
        int previewY = guiTop + 162;
        int previewColor = 0xFF000000 | (red << 16) | (green << 8) | blue;
        guiGraphics.fill(guiLeft + 10, previewY, guiLeft + 166, previewY + 12, previewColor);
        guiGraphics.renderOutline(guiLeft + 10, previewY, 156, 12, 0xFF333333);

        // State indicator
        String stateLabel = lightOn ? "§a⬤ ON" : "§c⬤ OFF";
        guiGraphics.drawString(this.font, stateLabel, guiLeft + 10, previewY + 15, 0xFFFFFFFF, false);
    }
}

package art.rehra.mineqtt.ui;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.entities.MotionSensorBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

public class MotionSensorBlockScreen extends AbstractContainerScreen<MotionSensorBlockMenu> {

    public static final ResourceLocation BACKGROUND = ResourceLocation.fromNamespaceAndPath(MineQTT.MOD_ID, "textures/gui/motion_sensor/topic_screen.png");

    private static final int MARGIN = 8;
    private static final int TITLE_Y = 6;
    private static final int INFO_START_Y = 5;
    private static final int LINE_HEIGHT = 10;
    private static final int MAX_TEXT_WIDTH = 160;
    private static final int STATUS_Y_OFFSET = 55;

    public MotionSensorBlockScreen(MotionSensorBlockMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.font = Minecraft.getInstance().font;
        this.imageHeight = 183;
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        Component title = Component.literal("Motion Sensor");
        int titleWidth = this.font.width(title);
        int titleX = (this.imageWidth - titleWidth) / 2;
        guiGraphics.drawString(this.font, title, titleX, TITLE_Y, 0x404040, false);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int guiLeft = (this.width - this.imageWidth) / 2;
        int guiTop = (this.height - this.imageHeight) / 2;
        guiGraphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, guiLeft, guiTop, 0.0F, 0.0F, this.imageWidth, this.imageHeight, 256, 256);
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
        var blockEntity = (MotionSensorBlockEntity) level.getBlockEntity(pos);

        if (blockEntity != null) {
            renderMotionSensorInfo(guiGraphics, guiLeft, guiTop, blockEntity);
        } else {
            String errorMsg = "No Block Entity Found";
            int errorWidth = this.font.width(errorMsg);
            int errorX = guiLeft + (this.imageWidth - errorWidth) / 2;
            guiGraphics.drawString(this.font, errorMsg, errorX, guiTop + INFO_START_Y, 0xFFAA0000, false);
        }

        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    private void renderMotionSensorInfo(GuiGraphics guiGraphics, int guiLeft, int guiTop, MotionSensorBlockEntity blockEntity) {
        int currentY = guiTop + INFO_START_Y;

        String combinedTopic = blockEntity.getCombinedTopic();
        boolean isEnabled = blockEntity.isEnabled();

        // Display topic configuration
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

        currentY = guiTop + STATUS_Y_OFFSET;

        // Display motion detection status
        boolean motionDetected = blockEntity.isMotionDetected();
        int statusColor = motionDetected ? 0xFF00FF00 : 0xFF666666;
        String statusText = motionDetected ? "Motion Detected: YES" : "Motion Detected: NO";

        guiGraphics.drawString(this.font, statusText, guiLeft + MARGIN, currentY, statusColor, false);

        // Show count
        int count = blockEntity.getLastCount();
        String countText = "Mobs: " + (count >= 0 ? count : 0);
        int countWidth = this.font.width(countText);
        guiGraphics.drawString(this.font, countText, guiLeft + this.imageWidth - MARGIN - countWidth, currentY, 0xFF555555, false);

        currentY = guiTop + 65;

        // Display mob filters info
        guiGraphics.drawString(this.font, "Mob Filters (Spawn Eggs):", guiLeft + MARGIN, currentY, 0xFF555555, false);
    }
}


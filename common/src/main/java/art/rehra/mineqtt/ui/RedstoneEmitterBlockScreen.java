package art.rehra.mineqtt.ui;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.RedstoneEmitterBlock;
import art.rehra.mineqtt.blocks.entities.RedstoneEmitterBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

public class RedstoneEmitterBlockScreen extends AbstractContainerScreen<RedstoneEmitterBlockMenu> {

    public static final ResourceLocation BACKGROUND = ResourceLocation.fromNamespaceAndPath(MineQTT.MOD_ID, "textures/gui/redstone_emitter/topic_screen.png");

    // GUI layout constants for better positioning
    private static final int MARGIN = 8;
    private static final int TITLE_Y = 6;
    private static final int INFO_START_Y = 5; // Moved up from 18
    private static final int LINE_HEIGHT = 10;
    private static final int MAX_TEXT_WIDTH = 160; // Leave some margin from GUI edges
    private static final int STATUS_Y_OFFSET = 50; // Fixed position for status below slots

    public RedstoneEmitterBlockScreen(RedstoneEmitterBlockMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.font = Minecraft.getInstance().font;
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Render custom title
        Component title = Component.literal("Redstone Emitter");
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
        var blockEntity = (RedstoneEmitterBlockEntity) level.getBlockEntity(pos);

        if (blockEntity != null) {
            renderEmitterInfo(guiGraphics, guiLeft, guiTop, blockEntity, level, pos);
        } else {
            // Center the error message
            String errorMsg = "No Block Entity Found";
            int errorWidth = this.font.width(errorMsg);
            int errorX = guiLeft + (this.imageWidth - errorWidth) / 2;
            guiGraphics.drawString(this.font, errorMsg, errorX, guiTop + INFO_START_Y, 0xFFAA0000, false);
        }

        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    private void renderEmitterInfo(GuiGraphics guiGraphics, int guiLeft, int guiTop, RedstoneEmitterBlockEntity blockEntity, net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        int currentY = guiTop + INFO_START_Y;

        // Get combined topic
        String combinedTopic = blockEntity.getCombinedTopic();
        boolean isEnabled = blockEntity.isEnabled();

        if (isEnabled && !combinedTopic.isEmpty()) {
            // Show only the full combined topic
            guiGraphics.drawString(this.font, "Topic:", guiLeft + MARGIN, currentY, 0xFF555555, false);
            currentY += LINE_HEIGHT;

            List<FormattedCharSequence> wrappedTopic = this.font.split(Component.literal(combinedTopic), MAX_TEXT_WIDTH);
            for (FormattedCharSequence line : wrappedTopic) {
                guiGraphics.drawString(this.font, line, guiLeft + MARGIN + 4, currentY, 0xFF0088FF, false);
                currentY += LINE_HEIGHT;
            }
        } else {
            // Show message if disabled
            guiGraphics.drawString(this.font, "No topic configured", guiLeft + MARGIN, currentY, 0xFF666666, false);
            currentY += LINE_HEIGHT;
            guiGraphics.drawString(this.font, "Place item in first slot to enable", guiLeft + MARGIN, currentY, 0xFF888888, false);
        }

        // Status section at fixed position
        currentY = guiTop + STATUS_Y_OFFSET;
        var currentState = level.getBlockState(pos);
        int power = currentState.getValue(RedstoneEmitterBlock.POWER);
        boolean isPowered = power > 0;
        String status = isEnabled ? (isPowered ? "Receiving (Power: " + power + ")" : "Listening") : "Disabled";
        int statusColor = isEnabled ? (isPowered ? 0xFF00AA00 : 0xFF0088FF) : 0xFF666666;

        guiGraphics.drawString(this.font, "Status:", guiLeft + MARGIN, currentY, 0xFF555555, false);
        currentY += LINE_HEIGHT;
        guiGraphics.drawString(this.font, status, guiLeft + MARGIN + 4, currentY, statusColor, false);
    }
}

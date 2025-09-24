package art.rehra.mineqtt.ui;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.entities.PublisherBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public class PublisherBlockScreen extends AbstractContainerScreen<PublisherBlockMenu> {

    public static final ResourceLocation BACKGROUND = ResourceLocation.fromNamespaceAndPath(MineQTT.MOD_ID, "textures/gui/publisher/topic_screen.png");

    public PublisherBlockScreen(PublisherBlockMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.font = Minecraft.getInstance().font;
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Do not render the default labels
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
        int margin = 5; // 5 pixels from the left edge of the GUI

        var player = this.menu.player;
        var pos = this.menu.blockPos;
        var level = player.level();
        var blockEntity = (PublisherBlockEntity) level.getBlockEntity(pos);

        if (blockEntity != null) {
            var topic = blockEntity.getTopic();
            guiGraphics.drawString(this.font, "Topic: ", guiLeft + margin, guiTop + margin, 0xFF000000, false);
            guiGraphics.drawString(this.font, topic, guiLeft + margin, guiTop + margin + 10, 0xFF000000, false);

            // Show current redstone state
            var currentState = level.getBlockState(pos);
            boolean isPowered = currentState.getValue(art.rehra.mineqtt.blocks.RedstonePublisherBlock.POWERED);
            String powerStatus = isPowered ? "Powered (Publishing)" : "Unpowered";
            guiGraphics.drawString(this.font, "Status: " + powerStatus, guiLeft + margin, guiTop + margin + 25, 0xFF000000, false);
        } else {
            var msg = "No Block Entity";
            guiGraphics.drawString(this.font, msg, guiLeft + margin, guiTop + margin + 10, 0xFF000000, false);
        }
    }
}

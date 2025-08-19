package art.rehra.mineqtt.neoforge.config;

import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.MineQTT;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class MineQTTConfigScreen extends Screen {
    private final Screen parent;
    private EditBox brokerUrlField;
    private EditBox clientIdField;
    private EditBox usernameField;
    private EditBox passwordField;
    private Button saveButton;
    private Button cancelButton;
    private Button resetButton;

    public MineQTTConfigScreen(Screen parent) {
        super(Component.literal("MineQTT Configuration"));
        this.parent = parent;
    }

    public static Screen createConfigScreen(Screen parent) {
        return new MineQTTConfigScreen(parent);
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int startY = 50;
        int fieldWidth = 200;
        int fieldHeight = 20;
        int spacing = 25;

        // Broker URL field
        this.brokerUrlField = new EditBox(this.font, centerX - fieldWidth / 2, startY, fieldWidth, fieldHeight, Component.literal("Broker URL"));
        this.brokerUrlField.setValue(MineQTTConfig.brokerUrl);
        this.brokerUrlField.setMaxLength(256);
        this.addRenderableWidget(this.brokerUrlField);

        // Client ID field
        this.clientIdField = new EditBox(this.font, centerX - fieldWidth / 2, startY + spacing, fieldWidth, fieldHeight, Component.literal("Client ID"));
        this.clientIdField.setValue(MineQTTConfig.clientId);
        this.clientIdField.setMaxLength(64);
        this.addRenderableWidget(this.clientIdField);

        // Username field
        this.usernameField = new EditBox(this.font, centerX - fieldWidth / 2, startY + spacing * 2, fieldWidth, fieldHeight, Component.literal("Username"));
        this.usernameField.setValue(MineQTTConfig.username);
        this.usernameField.setMaxLength(64);
        this.addRenderableWidget(this.usernameField);

        // Password field
        this.passwordField = new EditBox(this.font, centerX - fieldWidth / 2, startY + spacing * 3, fieldWidth, fieldHeight, Component.literal("Password"));
        this.passwordField.setValue(MineQTTConfig.password);
        this.passwordField.setMaxLength(64);
        this.addRenderableWidget(this.passwordField);

        // Buttons
        int buttonY = this.height - 40;
        int buttonWidth = 60;
        int buttonSpacing = 70;

        this.saveButton = Button.builder(Component.literal("Save"), this::onSave)
                .bounds(centerX - buttonSpacing, buttonY, buttonWidth, 20)
                .build();
        this.addRenderableWidget(this.saveButton);

        this.cancelButton = Button.builder(Component.literal("Cancel"), this::onCancel)
                .bounds(centerX - buttonWidth / 2, buttonY, buttonWidth, 20)
                .build();
        this.addRenderableWidget(this.cancelButton);

        this.resetButton = Button.builder(Component.literal("Reset"), this::onReset)
                .bounds(centerX + buttonSpacing - buttonWidth, buttonY, buttonWidth, 20)
                .build();
        this.addRenderableWidget(this.resetButton);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Let super.render handle the background rendering to avoid double blur
        super.render(graphics, mouseX, mouseY, partialTick);

        // Draw title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);

        // Draw field labels
        graphics.drawString(this.font, "Broker URL:", this.brokerUrlField.getX(), this.brokerUrlField.getY() - 12, 0xFFFFFF);
        graphics.drawString(this.font, "Client ID:", this.clientIdField.getX(), this.clientIdField.getY() - 12, 0xFFFFFF);
        graphics.drawString(this.font, "Username:", this.usernameField.getX(), this.usernameField.getY() - 12, 0xFFFFFF);
        graphics.drawString(this.font, "Password:", this.passwordField.getX(), this.passwordField.getY() - 12, 0xFFFFFF);
    }

    private void onSave(Button button) {
        // Update config values
        MineQTTConfig.brokerUrl = this.brokerUrlField.getValue();
        MineQTTConfig.clientId = this.clientIdField.getValue();
        MineQTTConfig.username = this.usernameField.getValue();
        MineQTTConfig.password = this.passwordField.getValue();

        // Save config through handler
        if (MineQTT.getConfigHandler() != null) {
            MineQTT.getConfigHandler().saveConfig();
        }

        // Close screen
        this.minecraft.setScreen(this.parent);
    }

    private void onCancel(Button button) {
        // Close without saving
        this.minecraft.setScreen(this.parent);
    }

    private void onReset(Button button) {
        // Reset to defaults
        MineQTTConfig.resetToDefaults();

        // Update field values
        this.brokerUrlField.setValue(MineQTTConfig.brokerUrl);
        this.clientIdField.setValue(MineQTTConfig.clientId);
        this.usernameField.setValue(MineQTTConfig.username);
        this.passwordField.setValue(MineQTTConfig.password);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}

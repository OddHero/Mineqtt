package art.rehra.mineqtt.ui;

import art.rehra.mineqtt.network.MineqttNetworking.RoomNamePayload;
import art.rehra.mineqtt.network.MineqttNetworking.RoomPromptPayload;
import art.rehra.mineqtt.rooms.RoomOffer;
import art.rehra.mineqtt.rooms.RoomRules;
import art.rehra.mineqtt.ui.framework.GuiText;
import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * The name prompt of the Configurator's Room mode (M4 plan D11). For the box of two corners it asks for the new room's
 * name, offers names to reuse (a button that steps through them) and, to a player who may, makes the room for every
 * device in it; Create (or Enter) sends it. For a room the player picked it shows its name to change, with Rename (or
 * Enter) and Remove. Cancel or Escape closes it, and nothing changes. Client only; the server checks the answer against
 * its own offer and the room rules.
 */
public class RoomNameScreen extends Screen {
    private static final int PANEL_W = 220;
    private static final int PANEL_H = 112;
    private static final int MARGIN = 8;
    private static final int FIELD_Y = 30;
    private static final int FIELD_H = 18;
    private static final int OPTION_Y = 52;
    private static final int OPTION_H = 16;
    private static final int BUTTON_Y = 84;
    private static final int BUTTON_H = 20;
    private static final int GAP = 4;
    private static final int PANEL_COLOR = 0xFFC6C6C6;
    private static final int PANEL_OUTLINE = 0xFF3A3A3A;
    private static final int TITLE_COLOR = 0xFF404040;
    private static final int HINT_COLOR = 0xFF555555;

    private final RoomPromptPayload prompt;
    private int left;
    private int top;
    private EditBox nameField;
    private Button confirmButton;
    private @Nullable Button suggestionButton;
    private @Nullable Button forAllButton;
    private int nextSuggestion;
    private boolean forAll;

    public RoomNameScreen(RoomPromptPayload prompt) {
        super(prompt.edits() ? Component.translatable("mineqtt.room_name.title_edit", prompt.existingName())
                : Component.translatable("mineqtt.room_name.title_create", prompt.sizeX(), prompt.sizeY(), prompt.sizeZ()));
        this.prompt = prompt;
    }

    /** Opens the prompt the server sent (client). */
    public static void open(RoomPromptPayload prompt) {
        Minecraft.getInstance().gui.setScreen(new RoomNameScreen(prompt));
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;
        int inner = PANEL_W - 2 * MARGIN;
        nameField = addRenderableWidget(new EditBox(font, left + MARGIN, top + FIELD_Y, inner, FIELD_H, Component.translatable("mineqtt.room_name.field")));
        nameField.setMaxLength(RoomRules.MAX_NAME_LENGTH);
        nameField.setHint(Component.translatable("mineqtt.room_name.hint").withStyle(EditBox.DEFAULT_HINT_STYLE));
        nameField.setValue(prompt.existingName());
        setInitialFocus(nameField);

        int optionW = (inner - GAP) / 2;
        suggestionButton = null;
        if (!prompt.suggestions().isEmpty()) {
            suggestionButton = addRenderableWidget(Button.builder(Component.empty(), button -> takeSuggestion())
                    .bounds(left + MARGIN, top + OPTION_Y, prompt.edits() || !prompt.mayForAll() ? inner : optionW, OPTION_H)
                    .tooltip(Tooltip.create(Component.translatable("mineqtt.room_name.suggestion.tooltip"))).build());
            showSuggestion();
        }
        forAllButton = null;
        if (!prompt.edits() && prompt.mayForAll()) {
            forAllButton = addRenderableWidget(Button.builder(Component.empty(), button -> toggleForAll())
                    .bounds(left + PANEL_W - MARGIN - optionW, top + OPTION_Y, optionW, OPTION_H)
                    .tooltip(Tooltip.create(Component.translatable("mineqtt.room_name.for_all.tooltip"))).build());
            showForAll();
        }

        int y = top + BUTTON_Y;
        if (prompt.edits()) {
            int buttonW = (inner - 2 * GAP) / 3;
            confirmButton = addRenderableWidget(Button.builder(Component.translatable("mineqtt.room_name.rename"), button -> confirm())
                    .bounds(left + MARGIN, y, buttonW, BUTTON_H).build());
            addRenderableWidget(Button.builder(Component.translatable("mineqtt.room_name.remove"), button -> remove())
                    .bounds(left + MARGIN + buttonW + GAP, y, buttonW, BUTTON_H)
                    .tooltip(Tooltip.create(Component.translatable("mineqtt.room_name.remove.tooltip"))).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                    .bounds(left + PANEL_W - MARGIN - buttonW, y, buttonW, BUTTON_H).build());
        } else {
            int buttonW = (inner - GAP) / 2;
            confirmButton = addRenderableWidget(Button.builder(Component.translatable("mineqtt.room_name.create"), button -> confirm())
                    .bounds(left + MARGIN, y, buttonW, BUTTON_H).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                    .bounds(left + PANEL_W - MARGIN - buttonW, y, buttonW, BUTTON_H).build());
        }
        // Inactive until a name is typed from the first frame on, not only once extractRenderState has run.
        confirmButton.active = sendable();
    }

    /** What the server asked. */
    public RoomPromptPayload prompt() {
        return prompt;
    }

    /** The name typed so far. */
    public String name() {
        return nameField.getValue();
    }

    /** Whether the new room is to be for every device in it. */
    public boolean forAll() {
        return forAll;
    }

    // A name the server would refuse as blank or too long is not sent, nor the picked room's own name.
    private boolean sendable() {
        String name = RoomRules.name(nameField.getValue());
        return RoomRules.nameProblem(name).isEmpty() && !(prompt.edits() && name.equals(prompt.existingName()));
    }

    private void takeSuggestion() {
        if (prompt.suggestions().isEmpty()) return;
        nameField.setValue(prompt.suggestions().get(nextSuggestion));
        nextSuggestion = (nextSuggestion + 1) % prompt.suggestions().size();
        showSuggestion();
    }

    private void showSuggestion() {
        if (suggestionButton != null) {
            suggestionButton.setMessage(Component.translatable("mineqtt.room_name.suggestion", prompt.suggestions().get(nextSuggestion)));
        }
    }

    private void toggleForAll() {
        forAll = !forAll;
        showForAll();
    }

    private void showForAll() {
        if (forAllButton != null) {
            forAllButton.setMessage(Component.translatable(forAll ? "mineqtt.room_name.for_all.on" : "mineqtt.room_name.for_all.off"));
        }
    }

    private void confirm() {
        if (!sendable()) return;
        RoomOffer.Op op = prompt.edits() ? RoomOffer.Op.RENAME : RoomOffer.Op.CREATE;
        NetworkManager.sendToServer(new RoomNamePayload(prompt.offer(), op, RoomRules.name(nameField.getValue()), forAll && op == RoomOffer.Op.CREATE));
        onClose();
    }

    private void remove() {
        if (!prompt.edits()) return;
        NetworkManager.sendToServer(new RoomNamePayload(prompt.offer(), RoomOffer.Op.REMOVE, prompt.existingName(), false));
        onClose();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        g.fill(left - 1, top - 1, left + PANEL_W + 1, top + PANEL_H + 1, PANEL_OUTLINE);
        g.fill(left, top, left + PANEL_W, top + PANEL_H, PANEL_COLOR);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        confirmButton.active = sendable();
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int inner = PANEL_W - 2 * MARGIN;
        GuiText.drawTruncated(g, getTitle(), left + MARGIN, top + 6, inner, TITLE_COLOR, mouseX, mouseY);
        GuiText.drawTruncated(g, Component.translatable(prompt.edits() ? "mineqtt.room_name.prompt_edit" : "mineqtt.room_name.prompt_create"),
                left + MARGIN, top + 18, inner, HINT_COLOR, mouseX, mouseY);
        GuiText.drawTruncated(g, Component.translatable("mineqtt.room_name.area"), left + MARGIN, top + OPTION_Y + OPTION_H + 3, inner, HINT_COLOR, mouseX, mouseY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
            confirm();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

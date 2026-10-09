package art.rehra.mineqtt.ui.device;

import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.LinkSetting;
import art.rehra.mineqtt.ui.device.widgets.IntSlider;
import art.rehra.mineqtt.ui.device.widgets.LightPicker;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Draws the {@link ControlsTab} from the device's {@link DeviceView}: a row per control, the light picker four. A switch
 * sends ON or OFF, a cycle button the next value, a slider the number it stands on while the player drags it, a text
 * field its text when the player presses Enter or Set. What a
 * player picks goes out at most every {@value #SEND_INTERVAL_MILLIS} ms, well within the C2S guard's
 * {@value art.rehra.mineqtt.security.C2SGuard#PACKETS_PER_SECOND} payloads a second, and the last pick always goes out,
 * at the latest when the view is left. A control shows the device's value from each new view, except while the player
 * works it and for a moment after, so a view on its way does not pull it back; a refused change snaps back after that.
 * A control the view's grants do not allow is read-only, and says why on hover.
 */
public class ControlsTabView implements MqttTabView {

    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    // Below the title row; the last row ends above the panel's foot (TabbedMqttMenu.GUI_HEIGHT_NO_INVENTORY).
    private static final int FIRST_ROW_Y = 17;
    private static final int ROW_H = 14;
    private static final int PITCH = 16;
    private static final int GAP = 4;
    private static final int LABEL_COLOR = 0xFF555555;
    private static final int PREVIEW_COLOR = 0xFF0066CC;
    private static final int MUTED_COLOR = 0xFF777777;
    private static final long SEND_INTERVAL_MILLIS = 100;
    // How long a control keeps what the player picked before it follows the views again.
    private static final long SETTLE_MILLIS = 750;
    // Static: a view is rebuilt on every tab switch and resize, but the guard's limit counts per player.
    private static long lastSendMillis;

    private final TabbedMqttScreen screen;
    private final ControlsTab tab;
    private final List<Row> rows = new ArrayList<>();
    private final List<AbstractWidget> widgets = new ArrayList<>();
    // The latest pick of each control not sent yet, in the order they were first picked.
    private final Map<String, Pick> pending = new LinkedHashMap<>();
    // The view the widgets were last brought up to date with, and its state.
    private @Nullable DeviceView synced;
    private JsonObject syncedState = new JsonObject();

    public ControlsTabView(TabbedMqttScreen screen) {
        this.screen = screen;
        this.tab = (ControlsTab) screen.getMenu().tabs.stream().filter(ControlsTab.class::isInstance).findFirst().orElseThrow();
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        rows.clear();
        widgets.clear();
        int x = guiLeft + 8;
        int y = guiTop + FIRST_ROW_Y;
        for (DeviceTabs.Control control : tab.controls()) {
            Row row = switch (control.widget()) {
                case TOGGLE -> new ToggleRow(control, x, y);
                case CYCLE -> new CycleRow(control, x, y);
                case SLIDER -> new SliderRow(control, x, y);
                case LIGHT -> new LightRow(control, x, y);
                case PRESS -> new PressRow(control, x, y);
                case TEXT_FIELD -> new TextRow(control, x, y);
            };
            rows.add(row);
            widgets.addAll(row.widgets());
            y += control.widget().rows() * PITCH;
        }
        synced = null;
        view().ifPresent(view -> {
            sync(view);
            rows.forEach(row -> row.follow(syncedState));
        });
    }

    @Override
    public List<? extends GuiEventListener> widgets() {
        return widgets;
    }

    private Optional<DeviceView> view() {
        return screen.getMenu().deviceView();
    }

    // A new view: who may work which control. The values follow in renderContent, row by row.
    private void sync(DeviceView view) {
        synced = view;
        syncedState = view.stateJson();
        rows.forEach(row -> row.grants(view));
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        flush(false);
        Optional<DeviceView> current = view();
        if (current.isEmpty()) return;
        if (current.get() != synced) sync(current.get());
        long now = Util.getMillis();
        for (Row row : rows) {
            if (!row.held() && now >= row.localUntil) row.follow(syncedState);
            row.render(g, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        // A release beside a slider does not reach it; it stops dragging either way.
        rows.forEach(Row::release);
        return false;
    }

    /** Enter sends the text field that has the focus; while one has it, it takes every other key but Escape. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_ESCAPE) return false;
        for (Row row : rows) {
            if (row instanceof TextRow text && text.field.isFocused()) return text.keyPressed(keyCode, scanCode, modifiers);
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // A click elsewhere ends the typing, so the screen's keys work again (the inventory key closes it).
        for (Row row : rows) {
            if (row instanceof TextRow text && !text.field.isMouseOver(mouseX, mouseY)) text.field.setFocused(false);
        }
        return false;
    }

    @Override
    public void removed() {
        // A disconnect closes the screen after the connection is gone.
        if (Minecraft.getInstance().getConnection() != null) flush(true);
    }

    /**
     * A control's pick: the latest one of each control goes out, at most one every interval. The light picker's rows
     * each send part of a light, so a pick there joins the one still pending instead of replacing it.
     */
    private void send(DeviceTabs.Control control, JsonElement value) {
        DeviceView view = view().orElse(null);
        if (view == null || !control.editable(view.grants())) return;
        Pick earlier = pending.get(control.key());
        JsonElement picked = earlier != null && control.widget() == DeviceTabs.Widget.LIGHT
                ? LightControl.merge(earlier.value().getAsJsonObject(), value.getAsJsonObject()) : value;
        pending.put(control.key(), new Pick(control, view.pos(), picked));
        flush(false);
    }

    /** @param all send every pick still pending, because the view is being left */
    private void flush(boolean all) {
        while (!pending.isEmpty()) {
            long now = Util.getMillis();
            if (!all && now - lastSendMillis < SEND_INTERVAL_MILLIS) return;
            var first = pending.entrySet().iterator().next();
            pending.remove(first.getKey());
            lastSendMillis = now;
            NetworkManager.sendToServer(first.getValue().payload());
            if (!all) return;
        }
    }

    /** A value picked with a control of the device at {@code pos}, not sent yet. */
    private record Pick(DeviceTabs.Control control, BlockPos pos, JsonElement value) {
        CustomPacketPayload payload() {
            return control.send(pos, value);
        }
    }

    private static Component label(DeviceTabs.Control control) {
        return Component.translatableWithFallback("mineqtt.device.control." + control.key(), control.key());
    }

    // Why a control is read-only, or null when the player may work it.
    private static @Nullable Tooltip readOnly(DeviceTabs.Control control, DeviceView view) {
        if (control.editable(view.grants())) return null;
        return Tooltip.create(Component.translatable(control.gate() == LinkSetting.Gate.USE ? "mineqtt.device.controls.no_use" : "mineqtt.device.general.read_only"));
    }

    /** The widgets of one control. */
    private abstract class Row {
        final DeviceTabs.Control control;
        // Until then the control shows what the player picked rather than the views.
        long localUntil;

        Row(DeviceTabs.Control control) {
            this.control = control;
        }

        abstract List<? extends AbstractWidget> widgets();

        /** Whether the player the view was built for may work the control; read-only says why on hover. */
        abstract void grants(DeviceView view);

        /** Shows the device's value; not while the player works the control or just picked with it. */
        void follow(JsonObject state) {
        }

        void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        }

        boolean held() {
            return false;
        }

        void release() {
        }

        /** Sends a value the player picked, which the control shows rather than the views for a moment. */
        void pick(JsonElement value) {
            localUntil = Util.getMillis() + SETTLE_MILLIS;
            send(control, value);
        }

        void grants(AbstractWidget widget, DeviceView view) {
            widget.active = control.editable(view.grants());
            widget.setTooltip(readOnly(control, view));
        }
    }

    /** A switch: shows on or off and sends the other, which it shows at once. */
    private final class ToggleRow extends Row {
        private final Button button;
        private boolean on;

        ToggleRow(DeviceTabs.Control control, int x, int y) {
            super(control);
            this.button = Button.builder(Component.empty(), pressed -> {
                show(!on);
                pick(control.toggled(on));
            }).bounds(x, y, CONTENT_W, ROW_H).build();
        }

        @Override
        List<Button> widgets() {
            return List.of(button);
        }

        @Override
        void grants(DeviceView view) {
            grants(button, view);
        }

        @Override
        void follow(JsonObject state) {
            show(control.on(state));
        }

        private void show(boolean on) {
            this.on = on;
            button.setMessage(CommonComponents.optionNameValue(label(control), CommonComponents.optionStatus(on)));
        }
    }

    /** A cycle button: shows the value and sends the next, which it shows at once, so a quick second click steps on. */
    private final class CycleRow extends Row {
        private final Button button;
        private String selected = "";

        CycleRow(DeviceTabs.Control control, int x, int y) {
            super(control);
            this.button = Button.builder(Component.empty(), pressed -> {
                show(control.next(selected));
                pick(new JsonPrimitive(selected));
            }).bounds(x, y, CONTENT_W, ROW_H).build();
        }

        @Override
        List<Button> widgets() {
            return List.of(button);
        }

        @Override
        void grants(DeviceView view) {
            grants(button, view);
        }

        @Override
        void follow(JsonObject state) {
            show(control.selected(state));
        }

        private void show(String selected) {
            this.selected = selected;
            button.setMessage(CommonComponents.optionNameValue(label(control),
                    Component.translatableWithFallback("mineqtt.device.control." + control.key() + "." + selected, selected)));
        }
    }

    /** A slider over the kind's whole numbers, labelled with the number and its unit. */
    private final class SliderRow extends Row {
        private final IntSlider slider;

        SliderRow(DeviceTabs.Control control, int x, int y) {
            super(control);
            this.slider = new IntSlider(x, y, CONTENT_W, ROW_H, control.min(), control.max(), 1, control.min(),
                    number -> CommonComponents.optionNameValue(label(control), number(control, number)),
                    number -> pick(control.leveled(number)));
        }

        @Override
        List<IntSlider> widgets() {
            return List.of(slider);
        }

        @Override
        void grants(DeviceView view) {
            grants(slider, view);
        }

        @Override
        void follow(JsonObject state) {
            int level = control.level(state);
            if (slider.number() != level) slider.show(level);
        }

        @Override
        boolean held() {
            return slider.held();
        }

        @Override
        void release() {
            slider.release();
        }

        private static Component number(DeviceTabs.Control control, int number) {
            return control.unit().<Component>map(unit -> Component.translatableWithFallback("mineqtt.device.unit." + unit, "%s " + unit, number))
                    .orElseGet(() -> Component.translatable("mineqtt.device.control.number", number));
        }
    }

    /** The light picker. */
    private final class LightRow extends Row {
        private final LightPicker picker;

        LightRow(DeviceTabs.Control control, int x, int y) {
            super(control);
            this.picker = new LightPicker(x, y, CONTENT_W, ROW_H, PITCH, this::pick);
        }

        @Override
        List<? extends AbstractWidget> widgets() {
            return picker.widgets();
        }

        @Override
        void grants(DeviceView view) {
            picker.setEditable(control.editable(view.grants()), readOnly(control, view));
        }

        @Override
        void follow(JsonObject state) {
            picker.show(LightControl.light(state));
        }

        @Override
        void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            picker.render(g, mouseX, mouseY);
        }

        @Override
        boolean held() {
            return picker.held();
        }

        @Override
        void release() {
            picker.release();
        }
    }

    /**
     * A text field with its label and a Set button: Enter or Set sends the text, without the spaces around it, never
     * typing alone. The device's text replaces the field's unless the player changed it since; what the player sent is
     * replaced by the text the server keeps (a filter as its ids), or by the old one when the server refused it.
     */
    private final class TextRow extends Row {
        private final EditBox field;
        private final Button set;
        private final int labelX;
        private final int labelY;
        private String syncedText = "";

        TextRow(DeviceTabs.Control control, int x, int y) {
            super(control);
            Font font = GuiText.font();
            Component setLabel = Component.translatable("mineqtt.device.controls.set");
            int labelW = font.width(label(control));
            int setW = font.width(setLabel) + 10;
            this.labelX = x;
            this.labelY = y + 3;
            this.field = new EditBox(font, x + labelW + GAP, y, CONTENT_W - labelW - setW - 2 * GAP, ROW_H, label(control));
            field.setMaxLength(control.maxLength());
            field.setTextColorUneditable(MUTED_COLOR);
            field.setHint(Component.translatableWithFallback("mineqtt.device.control." + control.key() + ".hint", "").withStyle(EditBox.DEFAULT_HINT_STYLE));
            this.set = Button.builder(setLabel, pressed -> commit()).bounds(x + CONTENT_W - setW, y, setW, ROW_H).build();
        }

        @Override
        List<AbstractWidget> widgets() {
            return List.of(field, set);
        }

        @Override
        void grants(DeviceView view) {
            boolean editable = control.editable(view.grants());
            field.setEditable(editable);
            // Nor does a read-only field take the focus, so no cursor suggests it would.
            field.active = editable;
            set.visible = editable;
            Tooltip readOnly = readOnly(control, view);
            field.setTooltip(readOnly != null ? readOnly
                    : Tooltip.create(Component.translatableWithFallback("mineqtt.device.control." + control.key() + ".tooltip", "")));
        }

        @Override
        void follow(JsonObject state) {
            String text = control.text(state);
            if (field.getValue().equals(syncedText) && !field.getValue().equals(text)) {
                field.setValue(text);
                field.moveCursorToStart(false);
            }
            syncedText = text;
        }

        @Override
        void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            boolean pending = !field.getValue().strip().equals(syncedText);
            field.setTextColor(pending ? PREVIEW_COLOR : EditBox.DEFAULT_TEXT_COLOR);
            set.active = pending;
            g.text(GuiText.font(), label(control), labelX, labelY, LABEL_COLOR, false);
        }

        boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
                commit();
                return true;
            }
            return field.keyPressed(new KeyEvent(keyCode, scanCode, modifiers)) || field.canConsumeInput();
        }

        // The field keeps the sent text until the view that answers it, which it then follows.
        private void commit() {
            String text = field.getValue().strip();
            if (text.equals(syncedText)) return;
            field.setValue(text);
            field.moveCursorToStart(false);
            syncedText = text;
            pick(new JsonPrimitive(text));
        }
    }

    /** A button that fires an action. */
    private final class PressRow extends Row {
        private final Button button;

        PressRow(DeviceTabs.Control control, int x, int y) {
            super(control);
            this.button = Button.builder(label(control), pressed -> send(control, control.pressed())).bounds(x, y, CONTENT_W, ROW_H).build();
        }

        @Override
        List<Button> widgets() {
            return List.of(button);
        }

        @Override
        void grants(DeviceView view) {
            grants(button, view);
        }
    }
}

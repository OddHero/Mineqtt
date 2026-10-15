package art.rehra.mineqtt.ui.device;

import art.rehra.mineqtt.compat.Inputs;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import art.rehra.mineqtt.util.CronSchedule;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Draws the Cron Timer's Schedule tab ({@link CronScheduleTab}): a box per field under its short name, a box whose token
 * does not parse in red; the preset button, which fills the boxes; the joined expression with Clear and Set; and the next
 * three fire times of what the boxes hold, or why it does not parse. The boxes follow the timer's expression until the
 * player edits them, and again once Set's answer arrives. A player who may not configure the timer sees it read-only.
 */
public class CronScheduleTabView implements MqttTabView {
    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    private static final int FIRST_ROW_Y = 17;
    private static final int PITCH = 16;
    private static final int ROW_H = 14;
    private static final int GAP = 4;
    private static final int BOX_GAP = 2;
    private static final int BOX_W = (CONTENT_W - BOX_GAP * (CronScheduleTab.FIELDS.size() - 1)) / CronScheduleTab.FIELDS.size();
    // The boxes sit under their names, within the first two rows.
    private static final int BOX_Y = FIRST_ROW_Y + 11;
    private static final int LABEL_COLOR = 0xFF555555;
    private static final int TEXT_COLOR = 0xFF404040;
    private static final int PREVIEW_COLOR = 0xFF0066CC;
    private static final int MUTED_COLOR = 0xFF777777;
    private static final int INVALID_COLOR = 0xFFCC3333;
    private static final DateTimeFormatter TODAY = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter LATER = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ROOT);

    private final TabbedMqttScreen screen;
    private final CronScheduleTab tab;
    private final List<EditBox> boxes = new ArrayList<>();
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private Button preset;
    private Button clear;
    private Button set;
    // The preset the button shows; -1 before the player stepped to one.
    private int presetIndex = -1;
    // The timer's expression the boxes last followed.
    private String synced = "";
    private @Nullable DeviceView syncedView;
    // The preview's last search: its expression, zone and wall-clock second, and the fire times it found.
    private @Nullable String firesExpression;
    private @Nullable ZoneId firesZone;
    private long firesSecond;
    private List<ZonedDateTime> fires = List.of();

    public CronScheduleTabView(TabbedMqttScreen screen) {
        this.screen = screen;
        this.tab = (CronScheduleTab) screen.getMenu().tabs.stream().filter(CronScheduleTab.class::isInstance).findFirst().orElseThrow();
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        boxes.clear();
        widgets.clear();
        int x = guiLeft + 8;
        for (int i = 0; i < CronScheduleTab.FIELDS.size(); i++) {
            String field = CronScheduleTab.FIELDS.get(i);
            EditBox box = new EditBox(GuiText.font(), x + i * (BOX_W + BOX_GAP), guiTop + BOX_Y, BOX_W, ROW_H, Component.translatable("mineqtt.schedule.field." + field));
            box.setMaxLength(32);
            box.setTextColorUneditable(MUTED_COLOR);
            box.setResponder(text -> refresh());
            boxes.add(box);
            widgets.add(box);
        }
        preset = Button.builder(presetLabel(), pressed -> nextPreset()).bounds(x, guiTop + FIRST_ROW_Y + 2 * PITCH, CONTENT_W, ROW_H).build();
        Component clearLabel = Component.translatable("mineqtt.schedule.clear");
        Component setLabel = Component.translatable("mineqtt.device.controls.set");
        int setW = GuiText.font().width(setLabel) + 10;
        int clearW = GuiText.font().width(clearLabel) + 10;
        int actionsY = guiTop + FIRST_ROW_Y + 3 * PITCH;
        set = Button.builder(setLabel, pressed -> commit()).bounds(x + CONTENT_W - setW, actionsY, setW, ROW_H).build();
        clear = Button.builder(clearLabel, pressed -> send("")).bounds(x + CONTENT_W - setW - GAP - clearW, actionsY, clearW, ROW_H).build();
        widgets.add(preset);
        widgets.add(clear);
        widgets.add(set);
        syncedView = null;
        view().ifPresent(view -> {
            fill(cron(view));
            follow(view);
        });
        refresh();
    }

    @Override
    public List<? extends GuiEventListener> widgets() {
        return widgets;
    }

    private Optional<DeviceView> view() {
        return screen.getMenu().deviceView();
    }

    // The timer's expression as its state holds it, also one that does not parse.
    private String cron(DeviceView view) {
        return tab.control().text(view.stateJson());
    }

    // A new view: the boxes follow the timer's expression unless the player changed them since they last did; the
    // controls follow who may configure the timer.
    private void follow(DeviceView view) {
        syncedView = view;
        String expression = cron(view);
        if (CronScheduleTab.join(tokens()).equals(synced) && !expression.equals(synced)) fill(expression);
        synced = expression;
        boolean editable = tab.control().editable(view.grants());
        Tooltip readOnly = editable ? null : Tooltip.create(Component.translatable(view.controlsOnly() ? "mineqtt.game_mode.uses_only" : "mineqtt.device.general.read_only"));
        for (int i = 0; i < boxes.size(); i++) {
            EditBox box = boxes.get(i);
            box.setEditable(editable);
            box.active = editable;
            String field = CronScheduleTab.FIELDS.get(i);
            box.setTooltip(readOnly != null ? readOnly : Tooltip.create(Component.translatable("mineqtt.schedule.field." + field + ".tooltip")));
        }
        preset.active = editable;
        clear.visible = editable;
        set.visible = editable;
        refresh();
    }

    private void fill(String expression) {
        List<String> tokens = CronScheduleTab.split(expression);
        for (int i = 0; i < boxes.size(); i++) {
            boxes.get(i).setValue(tokens.get(i));
            boxes.get(i).moveCursorToStart(false);
        }
    }

    private List<String> tokens() {
        return boxes.stream().map(EditBox::getValue).toList();
    }

    // Colours each box by whether its token parses; Set is open for a whole expression that differs from the timer's.
    private void refresh() {
        if (set == null) return;
        boolean valid = true;
        for (int i = 0; i < boxes.size(); i++) {
            boolean fieldValid = CronScheduleTab.fieldProblem(i, boxes.get(i).getValue()).isEmpty();
            boxes.get(i).setTextColor(fieldValid ? EditBox.DEFAULT_TEXT_COLOR : INVALID_COLOR);
            valid &= fieldValid;
        }
        String expression = CronScheduleTab.join(tokens());
        set.active = valid && !expression.equals(synced);
        clear.active = !synced.isEmpty();
    }

    private void nextPreset() {
        presetIndex = (presetIndex + 1) % CronScheduleTab.PRESETS.size();
        preset.setMessage(presetLabel());
        fill(CronScheduleTab.PRESETS.get(presetIndex).expression());
        refresh();
    }

    private Component presetLabel() {
        if (presetIndex < 0) return Component.translatable("mineqtt.schedule.presets");
        return Component.translatable("mineqtt.schedule.preset", Component.translatable("mineqtt.schedule.preset." + CronScheduleTab.PRESETS.get(presetIndex).key()));
    }

    // Sends the boxes' expression when every token parses.
    private void commit() {
        String expression = CronScheduleTab.join(tokens());
        if (!set.active || expression.isEmpty()) return;
        send(expression);
    }

    private void send(String expression) {
        DeviceView view = view().orElse(null);
        if (view == null || !tab.control().editable(view.grants())) return;
        NetworkManager.sendToServer(tab.control().send(view.pos(), new JsonPrimitive(expression)));
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        Optional<DeviceView> current = view();
        if (current.isPresent() && current.get() != syncedView) follow(current.get());
        int x = guiLeft + 8;
        for (int i = 0; i < CronScheduleTab.FIELDS.size(); i++) {
            Component name = Component.translatable("mineqtt.schedule.field." + CronScheduleTab.FIELDS.get(i) + ".short");
            GuiText.drawCentered(g, name, x + i * (BOX_W + BOX_GAP), guiTop + FIRST_ROW_Y + 1, BOX_W, LABEL_COLOR);
        }
        String expression = CronScheduleTab.join(tokens());
        int actionsY = guiTop + FIRST_ROW_Y + 3 * PITCH;
        int expressionW = (set.visible ? clear.getX() - GAP : x + CONTENT_W) - x;
        boolean pending = !expression.equals(synced);
        GuiText.drawTruncated(g, expression.isEmpty() ? Component.translatable("mineqtt.schedule.none") : Component.literal(expression),
                x, actionsY + 3, expressionW, pending ? PREVIEW_COLOR : TEXT_COLOR, mouseX, mouseY);
        GuiText.drawTruncated(g, preview(expression), x, guiTop + FIRST_ROW_Y + 4 * PITCH + 3, CONTENT_W, previewColor(expression), mouseX, mouseY);
    }

    // The next fire times of the boxes' expression in the server's zone, why a box does not parse, or that there is none.
    private Component preview(String expression) {
        for (int i = 0; i < boxes.size(); i++) {
            Optional<String> problem = CronScheduleTab.fieldProblem(i, boxes.get(i).getValue());
            if (problem.isPresent() && !expression.isEmpty()) {
                return Component.translatable("mineqtt.schedule.invalid", Component.translatable("mineqtt.schedule.field." + CronScheduleTab.FIELDS.get(i)),
                        CronSchedule.getFieldRanges()[i]);
            }
        }
        if (expression.isEmpty()) return Component.translatable("mineqtt.schedule.none");
        ZoneId zone = CronScheduleTab.zoneOf(syncedView == null ? "" : stateText(syncedView, "next_fire"));
        Instant now = Instant.now();
        List<ZonedDateTime> fires = fires(expression, zone, now);
        if (fires.isEmpty()) return Component.translatable("mineqtt.schedule.never");
        List<String> shown = fires.stream().map(fire -> (fire.toLocalDate().equals(now.atZone(zone).toLocalDate()) ? TODAY : LATER).format(fire)).toList();
        return Component.translatable("mineqtt.schedule.next", String.join(", ", shown));
    }

    // The preview's fire times, searched again only when the expression or the zone changes or the second turns, not each frame.
    private List<ZonedDateTime> fires(String expression, ZoneId zone, Instant now) {
        long second = now.getEpochSecond();
        if (!expression.equals(firesExpression) || !zone.equals(firesZone) || second != firesSecond) {
            firesExpression = expression;
            firesZone = zone;
            firesSecond = second;
            fires = CronScheduleTab.nextFires(expression, zone, now, CronScheduleTab.PREVIEW);
        }
        return fires;
    }

    private int previewColor(String expression) {
        for (int i = 0; i < boxes.size(); i++) {
            if (!expression.isEmpty() && CronScheduleTab.fieldProblem(i, boxes.get(i).getValue()).isPresent()) return INVALID_COLOR;
        }
        return expression.isEmpty() ? MUTED_COLOR : TEXT_COLOR;
    }

    private static String stateText(DeviceView view, String key) {
        var value = view.stateJson().get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    /** Enter sets the expression while a box has the focus; a box takes every other key but Escape. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_ESCAPE) return false;
        for (EditBox box : boxes) {
            if (!box.isFocused()) continue;
            if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
                commit();
                return true;
            }
            return Inputs.keyPressed(box, keyCode, scanCode, modifiers) || box.canConsumeInput();
        }
        return false;
    }

    /** A click beside a box ends the typing there, so the screen's keys work again. */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (EditBox box : boxes) {
            if (!box.isMouseOver(mouseX, mouseY)) box.setFocused(false);
        }
        return false;
    }
}

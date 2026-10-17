package art.rehra.mineqtt.ui.device;

import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.types.AlarmPanel;
import art.rehra.mineqtt.network.MineqttNetworking.AlarmKeypadPayload;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Draws the Alarm Panel's keypad ({@link AlarmKeypadTab}): the typed code as dots above twelve keys (1 to 9, C, 0 and the
 * erase key), the panel's state and the action keys beside them. The digit keys and the keyboard's digits, Backspace and
 * Enter (Disarm) type into the entry, which lives only here and is cleared after every send. A wrong code shows as such
 * once the panel counted it; a lockout shows its seconds left.
 */
public class AlarmKeypadTabView implements MqttTabView {
    private static final int FIRST_ROW_Y = 17;
    private static final int KEYS_Y = FIRST_ROW_Y + 16;
    private static final int KEY_W = 20;
    private static final int KEY_H = 14;
    private static final int PITCH = 16;
    private static final int GAP = 2;
    private static final int RIGHT_X = 8 + 3 * (KEY_W + GAP) + 4;
    private static final int RIGHT_W = 168 - RIGHT_X;
    private static final int TEXT_COLOR = 0xFF404040;
    private static final int MUTED_COLOR = 0xFF777777;
    private static final int DISARMED_COLOR = 0xFF2E8B37;
    private static final int ARMED_COLOR = 0xFFB02020;
    private static final int AMBER_COLOR = 0xFFC07000;
    private static final int WRONG_COLOR = 0xFFCC3333;
    private static final String[] DIGIT_KEYS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "C", "0", "←"};

    private final TabbedMqttScreen screen;
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private String entry = "";
    private Button armAway;
    private Button armHome;
    private Button disarm;
    private Button setCode;
    private Button removeCode;
    // The wrong-code count when the player last sent a disarm, to tell them that one was wrong; -1 for none pending.
    private int wrongBefore = -1;
    private boolean wrongShown;

    public AlarmKeypadTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        widgets.clear();
        for (int i = 0; i < DIGIT_KEYS.length; i++) {
            String key = DIGIT_KEYS[i];
            int x = guiLeft + 8 + (i % 3) * (KEY_W + GAP);
            int y = guiTop + KEYS_Y + (i / 3) * PITCH;
            widgets.add(Button.builder(Component.literal(key), pressed -> key(key)).bounds(x, y, KEY_W, KEY_H).build());
        }
        int x = guiLeft + RIGHT_X;
        armAway = Button.builder(Component.translatable("mineqtt.alarm_panel.key.arm_away"), pressed -> send(AlarmKeypadPayload.Op.ARM_AWAY))
                .bounds(x, guiTop + KEYS_Y, RIGHT_W, KEY_H).build();
        armHome = Button.builder(Component.translatable("mineqtt.alarm_panel.key.arm_home"), pressed -> send(AlarmKeypadPayload.Op.ARM_HOME))
                .bounds(x, guiTop + KEYS_Y + PITCH, RIGHT_W, KEY_H).build();
        disarm = Button.builder(Component.translatable("mineqtt.alarm_panel.key.disarm"), pressed -> send(AlarmKeypadPayload.Op.DISARM))
                .bounds(x, guiTop + KEYS_Y + 2 * PITCH, RIGHT_W, KEY_H).build();
        // Set code takes the room its label needs, Remove the rest.
        Component setLabel = Component.translatable("mineqtt.alarm_panel.key.set_code");
        int half = Math.min(RIGHT_W - GAP - 38, Math.max((RIGHT_W - GAP) / 2, GuiText.font().width(setLabel) + 4));
        setCode = Button.builder(setLabel, pressed -> send(AlarmKeypadPayload.Op.SET_CODE))
                .bounds(x, guiTop + KEYS_Y + 3 * PITCH, half, KEY_H).build();
        setCode.setTooltip(Tooltip.create(Component.translatable("mineqtt.alarm_panel.key.set_code.tooltip", AlarmPanel.MIN_CODE_LENGTH, AlarmPanel.MAX_CODE_LENGTH)));
        removeCode = Button.builder(Component.translatable("mineqtt.alarm_panel.key.remove_code"), pressed -> send(AlarmKeypadPayload.Op.CLEAR_CODE))
                .bounds(x + half + GAP, guiTop + KEYS_Y + 3 * PITCH, RIGHT_W - half - GAP, KEY_H).build();
        removeCode.setTooltip(Tooltip.create(Component.translatable("mineqtt.alarm_panel.key.remove_code.tooltip")));
        widgets.add(armAway);
        widgets.add(armHome);
        widgets.add(disarm);
        widgets.add(setCode);
        widgets.add(removeCode);
        refresh();
    }

    @Override
    public List<? extends GuiEventListener> widgets() {
        return widgets;
    }

    private Optional<DeviceView> view() {
        return screen.getMenu().deviceView();
    }

    private void key(String key) {
        switch (key) {
            case "C" -> entry = "";
            case "←" -> entry = entry.isEmpty() ? "" : entry.substring(0, entry.length() - 1);
            default -> entry = AlarmKeypadTab.typed(entry, key.charAt(0));
        }
        wrongShown = false;
        refresh();
    }

    // Sends a key with the entry (only Disarm and Set code use it), then forgets the entry.
    private void send(AlarmKeypadPayload.Op op) {
        DeviceView view = view().orElse(null);
        if (view == null) return;
        boolean withCode = op == AlarmKeypadPayload.Op.DISARM || op == AlarmKeypadPayload.Op.SET_CODE;
        if (op == AlarmKeypadPayload.Op.DISARM) wrongBefore = AlarmKeypadTab.number(view.stateJson(), AlarmPanel.WRONG_CODES.key());
        NetworkManager.sendToServer(new AlarmKeypadPayload(view.pos(), op, withCode ? entry : ""));
        entry = "";
        wrongShown = false;
        refresh();
    }

    // The keys follow the viewer's grants and the panel's state.
    private void refresh() {
        if (armAway == null) return;
        DeviceView view = view().orElse(null);
        AlarmKeypadTab.Keys keys = view == null ? new AlarmKeypadTab.Keys(false, false, false, false)
                : AlarmKeypadTab.Keys.of(view.grants(), view.stateJson(), entry);
        armAway.active = keys.arm();
        armHome.active = keys.arm();
        disarm.active = keys.disarm();
        boolean configure = view != null && view.grants().configure();
        setCode.visible = configure;
        removeCode.visible = configure;
        setCode.active = keys.setCode();
        removeCode.active = keys.removeCode();
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        refresh();
        JsonObject state = view().map(DeviceView::stateJson).orElseGet(JsonObject::new);
        int wrong = AlarmKeypadTab.number(state, AlarmPanel.WRONG_CODES.key());
        if (wrongBefore >= 0 && wrong > wrongBefore) {
            wrongShown = true;
            wrongBefore = -1;
        } else if (wrongBefore >= 0 && AlarmKeypadTab.alarmState(state) == AlarmPanel.AlarmState.DISARMED) {
            wrongBefore = -1;
        }
        int lockout = AlarmKeypadTab.number(state, AlarmPanel.LOCKOUT.key());
        int keysW = 3 * KEY_W + 2 * GAP;
        Component display;
        int displayColor;
        if (!entry.isEmpty()) {
            display = Component.literal(AlarmKeypadTab.masked(entry));
            displayColor = TEXT_COLOR;
        } else if (lockout > 0) {
            display = Component.translatable("mineqtt.alarm_panel.locked_out", lockout);
            displayColor = WRONG_COLOR;
        } else if (wrongShown) {
            display = Component.translatable("mineqtt.alarm_panel.wrong_code");
            displayColor = WRONG_COLOR;
        } else {
            display = Component.translatable(AlarmKeypadTab.bool(state, AlarmPanel.CODE_SET.key()) ? "mineqtt.alarm_panel.enter_code" : "mineqtt.alarm_panel.no_code");
            displayColor = MUTED_COLOR;
        }
        GuiText.drawCentered(g, display, guiLeft + 8, guiTop + FIRST_ROW_Y + 3, keysW, displayColor, mouseX, mouseY);
        AlarmPanel.AlarmState alarm = AlarmKeypadTab.alarmState(state);
        int left = AlarmKeypadTab.number(state, AlarmPanel.TIME_LEFT.key());
        Component status = left > 0 ? Component.translatable("mineqtt.alarm_panel.state_left", stateName(alarm), left) : stateName(alarm);
        GuiText.drawTruncated(g, status, guiLeft + RIGHT_X, guiTop + FIRST_ROW_Y + 3, RIGHT_W, color(alarm), mouseX, mouseY);
    }

    private static Component stateName(AlarmPanel.AlarmState alarm) {
        return Component.translatable("mineqtt.alarm_panel.state." + alarm.getSerializedName());
    }

    private static int color(AlarmPanel.AlarmState alarm) {
        return switch (alarm) {
            case DISARMED -> DISARMED_COLOR;
            case ARMING, PENDING -> AMBER_COLOR;
            case ARMED_HOME, ARMED_AWAY, TRIGGERED -> ARMED_COLOR;
        };
    }

    /** Digits type, Backspace erases the last one, Enter disarms with the entry. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_BACKSPACE) {
            key("←");
            return true;
        }
        if ((keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) && disarm != null && disarm.active) {
            send(AlarmKeypadPayload.Op.DISARM);
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (c < '0' || c > '9') return false;
        key(String.valueOf(c));
        return true;
    }

    /** Leaving the tab forgets what was typed. */
    @Override
    public void removed() {
        entry = "";
    }
}

package art.rehra.mineqtt.ui.console;

import art.rehra.mineqtt.mqtt.core.ConnectionState;
import art.rehra.mineqtt.network.MineqttNetworking.BridgeActionPayload;
import art.rehra.mineqtt.ui.device.DiagnosticsTab;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Draws the {@link ConsoleBridgeTab} from the player's {@link ConsoleView}: a row each for the broker connection, the
 * broker, the bridge's topics, Home Assistant, the server's pause state, the drop counters and the wires, the details on
 * hover; and, for an operator, a button per {@link BridgeAction} at the foot. Everyone else sees "Read only" in the title
 * row and no buttons.
 */
public class ConsoleBridgeTabView implements MqttTabView {

    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    private static final int FIRST_Y = 17;
    // Seven rows of the font's height, so the actions' row below them ends by y 99, as a device tab's content does (P1).
    private static final int LINE_PITCH = 9;
    private static final int FOOT_Y = 85;
    private static final int BUTTON_H = 14;
    private static final int BUTTON_PADDING = 6;
    private static final int GAP = 4;
    private static final int TOOLTIP_W = 200;
    private static final int LABEL_COLOR = 0xFF555555;
    private static final int VALUE_COLOR = 0xFF303030;
    private static final int MUTED_COLOR = 0xFF777777;
    private static final int ONLINE_COLOR = 0xFF2E7D32;
    private static final int WARNING_COLOR = 0xFF9A6700;
    private static final int REFUSED_COLOR = 0xFFAA0000;
    // How long a sent action keeps its button busy: the reply comes in chat and the console's view follows at once.
    private static final long SETTLE_MILLIS = 750;
    private static final List<String> LABELS = List.of("connection", "broker", "topics", "home_assistant", "server", "dropped", "wires");

    private final TabbedMqttScreen screen;
    private final Map<BridgeAction, Button> buttons = new EnumMap<>(BridgeAction.class);
    private int left;
    private int valueX;
    private long busyUntil;

    public ConsoleBridgeTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        Font font = GuiText.font();
        left = guiLeft + 8;
        int labelW = LABELS.stream().mapToInt(label -> font.width(Component.translatable("mineqtt.console.bridge." + label))).max().orElse(0);
        valueX = left + labelW + GAP + 2;
        // Each button as wide as its label, the room left spread between them; equal widths if the labels do not fit.
        BridgeAction[] actions = BridgeAction.values();
        int[] widths = new int[actions.length];
        int total = 0;
        for (int i = 0; i < actions.length; i++) {
            widths[i] = font.width(Component.translatable("mineqtt.console.action." + actions[i].key())) + BUTTON_PADDING;
            total += widths[i];
        }
        int spacing = actions.length > 1 ? (CONTENT_W - total) / (actions.length - 1) : 0;
        if (spacing < 2) {
            Arrays.fill(widths, (CONTENT_W - (actions.length - 1) * 2) / actions.length);
            spacing = 2;
        }
        buttons.clear();
        int x = left;
        for (int i = 0; i < actions.length; i++) {
            BridgeAction action = actions[i];
            int width = i == actions.length - 1 ? left + CONTENT_W - x : widths[i];
            buttons.put(action, Button.builder(Component.translatable("mineqtt.console.action." + action.key()), button -> send(action))
                    .bounds(x, guiTop + FOOT_Y, width, BUTTON_H)
                    .tooltip(Tooltip.create(Component.translatable("mineqtt.console.action." + action.key() + ".tooltip"))).build());
            x += width + spacing;
        }
        boolean operator = menu().view().operator();
        buttons.values().forEach(button -> button.visible = operator);
    }

    @Override
    public List<? extends GuiEventListener> widgets() {
        return new ArrayList<>(buttons.values());
    }

    @Override
    public Optional<Component> titleNote() {
        return menu().view().operator() ? Optional.empty() : Optional.of(Component.translatable("mineqtt.device.general.read_only.short"));
    }

    private ConsoleMenu menu() {
        return (ConsoleMenu) screen.getMenu();
    }

    private void send(BridgeAction action) {
        ConsoleView view = menu().view();
        if (!view.operator() || Util.getMillis() < busyUntil) return;
        busyUntil = Util.getMillis() + SETTLE_MILLIS;
        NetworkManager.sendToServer(new BridgeActionPayload(view.pos(), action));
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        ConsoleView view = menu().view();
        ConsoleView.Bridge bridge = view.bridge();
        boolean busy = Util.getMillis() < busyUntil;
        for (Button button : buttons.values()) {
            button.visible = view.operator();
            button.active = !busy;
        }
        int y = guiTop + FIRST_Y;
        row(g, "connection", connection(bridge), connectionColor(bridge.connection()),
                bridge.connectionDetail().isEmpty() ? null : Component.literal(bridge.connectionDetail()), y, mouseX, mouseY);
        row(g, "broker", Component.literal(bridge.broker()), bridge.broker().equals("none") ? MUTED_COLOR : VALUE_COLOR,
                Component.literal(bridge.broker()), y += LINE_PITCH, mouseX, mouseY);
        row(g, "topics", Component.literal(bridge.topicRoot()), VALUE_COLOR, Component.literal(bridge.topicRoot()), y += LINE_PITCH, mouseX, mouseY);
        row(g, "home_assistant", homeAssistant(bridge, menu().viewAgeMillis()), homeAssistantColor(bridge.homeAssistant()),
                Component.translatable("mineqtt.console.bridge.home_assistant.tooltip", bridge.haExpose(), bridge.discovered()), y += LINE_PITCH, mouseX, mouseY);
        row(g, "server", Component.translatable("mineqtt.console.server." + bridge.server().name().toLowerCase(Locale.ROOT), bridge.pauseSeconds()),
                bridge.server() == ConsoleView.ServerState.PAUSED ? WARNING_COLOR : VALUE_COLOR,
                Component.translatable("mineqtt.console.server." + bridge.server().name().toLowerCase(Locale.ROOT) + ".tooltip", bridge.pauseSeconds()),
                y += LINE_PITCH, mouseX, mouseY);
        row(g, "dropped", Component.translatable("mineqtt.console.bridge.dropped.value", bridge.droppedIn(), bridge.droppedOut()),
                bridge.droppedIn() + bridge.droppedOut() > 0 ? WARNING_COLOR : VALUE_COLOR,
                Component.translatable("mineqtt.console.bridge.dropped.tooltip", bridge.queuedIn(), bridge.outbox()), y += LINE_PITCH, mouseX, mouseY);
        row(g, "wires", Component.translatable("mineqtt.console.bridge.wires.value", bridge.wires(), bridge.wireDrops()),
                bridge.wireDrops() > 0 ? WARNING_COLOR : VALUE_COLOR,
                Component.translatable("mineqtt.console.bridge.wires.tooltip", bridge.delivered(), bridge.wireDrops()),
                y += LINE_PITCH, mouseX, mouseY);
    }

    /** One row: its label, its value cut to the row, and on hover the whole value with its details, if any. */
    private void row(GuiGraphicsExtractor g, String label, Component value, int color, @Nullable Component details, int y, int mouseX, int mouseY) {
        GuiText.drawTruncated(g, Component.translatable("mineqtt.console.bridge." + label), left, y, valueX - left - GAP, LABEL_COLOR);
        GuiText.drawTruncated(g, value, valueX, y, left + CONTENT_W - valueX, color);
        if (details != null && mouseX >= left && mouseX < left + CONTENT_W && mouseY >= y && mouseY < y + LINE_PITCH) {
            Font font = GuiText.font();
            List<FormattedCharSequence> lines = new ArrayList<>(font.split(value, TOOLTIP_W));
            if (!details.getString().equals(value.getString())) lines.addAll(font.split(details, TOOLTIP_W));
            g.setTooltipForNextFrame(font, lines, mouseX, mouseY);
        }
    }

    private static Component connection(ConsoleView.Bridge bridge) {
        return Component.translatable("mineqtt.console.connection." + bridge.connection().name().toLowerCase(Locale.ROOT), bridge.protocol());
    }

    private static int connectionColor(ConnectionState.Status status) {
        return switch (status) {
            case CONNECTED -> ONLINE_COLOR;
            case CONNECTING, RECONNECTING -> WARNING_COLOR;
            case DISABLED, STOPPED -> MUTED_COLOR;
            case FAILED -> REFUSED_COLOR;
        };
    }

    // Home Assistant's birth or last will with its age, counting on from the view's arrival on this client's clock.
    private static Component homeAssistant(ConsoleView.Bridge bridge, long since) {
        return switch (bridge.homeAssistant()) {
            case NOT_SEEN -> Component.translatable("mineqtt.console.home_assistant.not_seen");
            case ONLINE -> Component.translatable("mineqtt.console.home_assistant.online", DiagnosticsTab.age(bridge.homeAssistantAgeMillis() + since));
            case OFFLINE -> Component.translatable("mineqtt.console.home_assistant.offline", DiagnosticsTab.age(bridge.homeAssistantAgeMillis() + since));
        };
    }

    private static int homeAssistantColor(ConsoleView.HomeAssistant homeAssistant) {
        return switch (homeAssistant) {
            case NOT_SEEN -> MUTED_COLOR;
            case ONLINE -> ONLINE_COLOR;
            case OFFLINE -> WARNING_COLOR;
        };
    }
}

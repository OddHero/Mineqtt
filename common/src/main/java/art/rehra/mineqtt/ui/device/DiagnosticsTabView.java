package art.rehra.mineqtt.ui.device;

import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceEjectPayload;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceRetryPayload;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Draws the {@link DiagnosticsTab} from the device's {@link DeviceView}: whether it runs, with a Retry button while it
 * is faulted; its UUID with a copy button; the last message in and out with its age, size and payload, the topic and the
 * whole shown payload on hover; and how many went each way and how many it dropped, the bridge's own drop counters on
 * hover; and, while the device keeps the items of its old configuration slots (M2-12), an Eject button that hands them to
 * the player. A player who may not configure the device sees no payloads. A refused retry or eject shows its reason in
 * the status row. Ages count on from the view's arrival on this client's clock, so the server's clock never matters.
 */
public class DiagnosticsTabView implements MqttTabView {

    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    private static final int STATUS_Y = 17;
    private static final int ID_Y = 33;
    private static final int IN_Y = 49;
    private static final int OUT_Y = 67;
    // The counts and the Eject button; the row ends above the panel's foot.
    private static final int FOOT_Y = 85;
    private static final int FOOT_H = 12;
    private static final int LINE_H = 9;
    private static final int ROW_H = 14;
    private static final int GAP = 4;
    private static final int LABEL_COLOR = 0xFF555555;
    private static final int VALUE_COLOR = 0xFF303030;
    private static final int MUTED_COLOR = 0xFF777777;
    private static final int ONLINE_COLOR = 0xFF2E7D32;
    private static final int WARNING_COLOR = 0xFF9A6700;
    private static final int REFUSED_COLOR = 0xFFAA0000;
    private static final int TOOLTIP_W = 2 * CONTENT_W;
    private static final long COPIED_MILLIS = 1500;
    // How long a sent retry keeps the button busy before the views count again.
    private static final long SETTLE_MILLIS = 750;

    private final TabbedMqttScreen screen;
    private Button retryButton;
    private Button copyButton;
    private Button ejectButton;
    private int left;
    private int valueX;
    private @Nullable DeviceView synced;
    private boolean retrySent;
    private long retrySentUntil;
    private boolean ejectSent;
    private long ejectSentUntil;
    private long copiedUntil;

    public DiagnosticsTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        Font font = GuiText.font();
        left = guiLeft + 8;
        int labelW = Stream.of("status", "id", "in", "out").mapToInt(label -> font.width(Component.translatable("mineqtt.device.diagnostics." + label)))
                .max().orElse(0);
        valueX = left + labelW + GAP + 2;
        int right = left + CONTENT_W;

        Component retry = Component.translatable("mineqtt.device.diagnostics.retry");
        int retryW = font.width(retry) + 10;
        retryButton = Button.builder(retry, button -> retry()).bounds(right - retryW, guiTop + STATUS_Y, retryW, ROW_H).build();
        Component copy = Component.translatable("mineqtt.device.general.copy");
        int copyW = Math.max(font.width(copy), font.width(Component.translatable("mineqtt.device.general.copied"))) + 10;
        copyButton = Button.builder(copy, button -> copyId()).bounds(right - copyW, guiTop + ID_Y, copyW, ROW_H)
                .tooltip(Tooltip.create(Component.translatable("mineqtt.device.diagnostics.copy.tooltip"))).build();
        // Its width follows the item count it names (renderContent).
        ejectButton = Button.builder(Component.empty(), button -> eject()).bounds(right, guiTop + FOOT_Y, 0, FOOT_H).build();
        synced = null;
        view().ifPresent(this::sync);
    }

    @Override
    public List<? extends GuiEventListener> widgets() {
        return List.of(retryButton, copyButton, ejectButton);
    }

    private Optional<DeviceView> view() {
        return screen.getMenu().deviceView();
    }

    private DeviceMenu menu() {
        return (DeviceMenu) screen.getMenu();
    }

    private void sync(DeviceView view) {
        synced = view;
        retryButton.visible = view.diagnostics().availability() == DeviceView.Availability.FAULTED;
        retryButton.setTooltip(Tooltip.create(Component.translatable(view.grants().configure()
                ? "mineqtt.device.diagnostics.retry.tooltip" : "mineqtt.device.general.read_only")));
        ejectButton.visible = view.legacyItems() > 0;
        ejectButton.setMessage(Component.translatable("mineqtt.device.diagnostics.eject", view.legacyItems()));
        int ejectW = GuiText.font().width(ejectButton.getMessage()) + 10;
        ejectButton.setX(left + CONTENT_W - ejectW);
        ejectButton.setWidth(ejectW);
        ejectButton.setTooltip(Tooltip.create(Component.translatable(view.grants().configure()
                ? "mineqtt.device.diagnostics.eject.tooltip" : "mineqtt.device.general.read_only", view.legacyItems())));
    }

    private void eject() {
        DeviceView view = synced;
        if (view == null || ejectSent || !DiagnosticsTab.ejectable(view)) return;
        ejectSent = true;
        ejectSentUntil = Util.getMillis() + SETTLE_MILLIS;
        menu().forgetRefusal();
        NetworkManager.sendToServer(new DeviceEjectPayload(view.pos()));
    }

    private void retry() {
        DeviceView view = synced;
        if (view == null || retrySent || !DiagnosticsTab.retryable(view)) return;
        retrySent = true;
        retrySentUntil = Util.getMillis() + SETTLE_MILLIS;
        menu().forgetRefusal();
        NetworkManager.sendToServer(new DeviceRetryPayload(view.pos()));
    }

    private void copyId() {
        if (synced == null) return;
        synced.diagnostics().id().ifPresent(id -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(id.toString());
            copiedUntil = Util.getMillis() + COPIED_MILLIS;
        });
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        Optional<DeviceView> current = view();
        if (current.isEmpty()) return;
        DeviceView view = current.get();
        if (view != synced) sync(view);
        DeviceView.Diagnostics diagnostics = view.diagnostics();
        Optional<DeviceMenu.Refusal> refusal = menu().refusal().filter(refused -> DiagnosticsTab.REFUSAL_KEYS.contains(refused.key()));
        // A retry is answered by the view of its attach, which hides the button, by a refusal, or after a while by the
        // view of its new fault.
        if (refusal.isPresent() || Util.getMillis() >= retrySentUntil) retrySent = false;
        // An eject is answered by the view without the items, which hides the button, or by a refusal.
        if (refusal.isPresent() || Util.getMillis() >= ejectSentUntil) ejectSent = false;
        retryButton.active = DiagnosticsTab.retryable(view) && !retrySent;
        ejectButton.active = DiagnosticsTab.ejectable(view) && !ejectSent;
        retryButton.setMessage(Component.translatable(retrySent ? "mineqtt.device.diagnostics.retrying" : "mineqtt.device.diagnostics.retry"));

        int statusW = (retryButton.visible ? retryButton.getX() - GAP : left + CONTENT_W) - valueX;
        label(g, "status", guiTop + STATUS_Y + 3);
        if (refusal.isPresent()) {
            GuiText.drawTruncated(g, refusal.get().reason(), valueX, guiTop + STATUS_Y + 3, statusW, REFUSED_COLOR, mouseX, mouseY);
        } else {
            GuiText.drawTruncated(g, availability(diagnostics), valueX, guiTop + STATUS_Y + 3, statusW, availabilityColor(diagnostics.availability()), mouseX, mouseY);
        }

        label(g, "id", guiTop + ID_Y + 3);
        copyButton.active = diagnostics.id().isPresent();
        copyButton.setMessage(Component.translatable(Util.getMillis() < copiedUntil ? "mineqtt.device.general.copied" : "mineqtt.device.general.copy"));
        int idW = copyButton.getX() - GAP - valueX;
        if (diagnostics.id().isPresent()) {
            GuiText.drawTruncated(g, diagnostics.id().map(UUID::toString).orElseThrow(), valueX, guiTop + ID_Y + 3, idW, VALUE_COLOR, mouseX, mouseY);
        } else {
            GuiText.drawTruncated(g, Component.translatable("mineqtt.device.diagnostics.id.none"), valueX, guiTop + ID_Y + 3, idW, MUTED_COLOR);
        }

        long since = menu().viewAgeMillis();
        traffic(g, "in", guiTop + IN_Y, diagnostics.lastIn(), since, mouseX, mouseY);
        traffic(g, "out", guiTop + OUT_Y, diagnostics.lastOut(), since, mouseX, mouseY);

        Component counts = Component.translatable("mineqtt.device.diagnostics.counts", diagnostics.received(), diagnostics.dropped(), diagnostics.sent());
        int countsY = guiTop + FOOT_Y + (FOOT_H - LINE_H) / 2 + 1;
        int countsW = (ejectButton.visible ? ejectButton.getX() - GAP : left + CONTENT_W) - left;
        GuiText.drawTruncated(g, counts, left, countsY, countsW, MUTED_COLOR);
        if (hovered(mouseX, mouseY, left, countsY, countsW, LINE_H)) {
            Font font = GuiText.font();
            g.setTooltipForNextFrame(font, font.split(Component.translatable("mineqtt.device.diagnostics.counts.tooltip",
                    diagnostics.bridgeDroppedIn(), diagnostics.bridgeDroppedOut()), TOOLTIP_W), mouseX, mouseY);
        }
    }

    /**
     * One direction's two lines: when the last message went and its size, then its payload, or why it is not shown.
     * Hovering them shows the topic and the payload.
     */
    private void traffic(GuiGraphicsExtractor g, String direction, int y, Optional<DeviceView.Traffic> last, long since, int mouseX, int mouseY) {
        Font font = GuiText.font();
        label(g, direction, y);
        int lineW = left + CONTENT_W - valueX;
        if (last.isEmpty()) {
            GuiText.drawTruncated(g, Component.translatable("mineqtt.device.diagnostics.none"), valueX, y, lineW, MUTED_COLOR);
            return;
        }
        DeviceView.Traffic message = last.get();
        Component when = Component.translatable("mineqtt.device.diagnostics.message", DiagnosticsTab.age(message.ageMillis() + since),
                Component.translatable("mineqtt.device.diagnostics.bytes", message.bytes()));
        GuiText.drawTruncated(g, when, valueX, y, lineW, VALUE_COLOR);
        Component payload = message.payload().<Component>map(Component::literal)
                .orElseGet(() -> Component.translatable("mineqtt.device.diagnostics.payload.hidden"));
        int payloadColor = message.payload().isPresent() ? VALUE_COLOR : MUTED_COLOR;
        if (message.payload().filter(String::isEmpty).isPresent()) {
            payload = Component.translatable("mineqtt.device.diagnostics.payload.empty");
            payloadColor = MUTED_COLOR;
        }
        GuiText.drawTruncated(g, payload, valueX, y + LINE_H, lineW, payloadColor);
        if (hovered(mouseX, mouseY, valueX, y, lineW, 2 * LINE_H)) {
            List<FormattedCharSequence> tooltip = new ArrayList<>(font.split(Component.translatable("mineqtt.device.diagnostics.topic", message.topic()), TOOLTIP_W));
            Component shown = message.payload().filter(text -> !text.isEmpty()).<Component>map(Component::literal)
                    .orElseGet(() -> Component.translatable(message.payload().isPresent() ? "mineqtt.device.diagnostics.payload.empty"
                            : "mineqtt.device.diagnostics.payload.hidden.tooltip"));
            tooltip.addAll(font.split(shown, TOOLTIP_W));
            g.setTooltipForNextFrame(font, tooltip, mouseX, mouseY);
        }
    }

    private static Component availability(DeviceView.Diagnostics diagnostics) {
        String key = "mineqtt.device.diagnostics.availability." + switch (diagnostics.availability()) {
            case ONLINE -> "online";
            case DISCONNECTED -> "disconnected";
            case FAULTED -> "faulted";
            case NOT_RUNNING -> "not_running";
        };
        return Component.translatable(key, diagnostics.detail());
    }

    private static int availabilityColor(DeviceView.Availability availability) {
        return switch (availability) {
            case ONLINE -> ONLINE_COLOR;
            case DISCONNECTED -> WARNING_COLOR;
            case FAULTED -> REFUSED_COLOR;
            case NOT_RUNNING -> MUTED_COLOR;
        };
    }

    private void label(GuiGraphicsExtractor g, String key, int y) {
        GuiText.drawTruncated(g, Component.translatable("mineqtt.device.diagnostics." + key), left, y, valueX - left - GAP, LABEL_COLOR);
    }

    private static boolean hovered(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}

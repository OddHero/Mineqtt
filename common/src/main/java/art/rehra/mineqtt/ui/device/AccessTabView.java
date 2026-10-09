package art.rehra.mineqtt.ui.device;

import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceClaimPayload;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import com.google.gson.JsonPrimitive;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Draws the {@link AccessTab} from the device's {@link DeviceView}: the owner, with a button that claims the device while
 * it has none; the access mode as a button that steps through private, claim and public, with what the mode means; and
 * what the claim at the device means on this server, decided by a claim mod or, without one, trusting everyone. Only the
 * owner or an operator may change the mode, so for anyone else the button is read-only and says why on hover. A change
 * the server refused shows its reason in place of the mode's meaning and the claim hint until the tab sends the next
 * one ({@link DeviceMenu#refusal}).
 */
public class AccessTabView implements MqttTabView {

    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    private static final int OWNER_Y = 17;
    private static final int MODE_Y = 33;
    private static final int MEANING_Y = 51;
    // Two lines each: what the mode means, and what the claim means on this server; both end above the panel's foot.
    private static final int MEANING_H = 18;
    private static final int CLAIM_HINT_Y = 74;
    private static final int ROW_H = 14;
    private static final int GAP = 4;
    private static final int LABEL_COLOR = 0xFF555555;
    private static final int VALUE_COLOR = 0xFF303030;
    private static final int MUTED_COLOR = 0xFF777777;
    private static final int REFUSED_COLOR = 0xFFAA0000;
    // How long what the player sent shows before the views count again, so a view already on its way does not undo it.
    private static final long SETTLE_MILLIS = 750;

    private final TabbedMqttScreen screen;
    private final AccessTab tab;
    private Button claimButton;
    private Button modeButton;
    private int left;
    private int valueX;
    // The view the widgets were last brought up to date with; what was sent since, shown until a view or a refusal answers.
    private @Nullable DeviceView synced;
    private @Nullable String sentMode;
    private boolean claimSent;
    private long sentUntil;

    public AccessTabView(TabbedMqttScreen screen) {
        this.screen = screen;
        this.tab = (AccessTab) screen.getMenu().tabs.stream().filter(AccessTab.class::isInstance).findFirst().orElseThrow();
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        Font font = GuiText.font();
        left = guiLeft + 8;
        int labelW = Stream.of("owner", "mode").mapToInt(label -> font.width(Component.translatable("mineqtt.device.access." + label))).max().orElse(0);
        valueX = left + labelW + GAP + 2;
        int right = left + CONTENT_W;

        Component claim = Component.translatable("mineqtt.device.access.claim");
        int claimW = font.width(claim) + 10;
        claimButton = Button.builder(claim, button -> claim()).bounds(right - claimW, guiTop + OWNER_Y, claimW, ROW_H).build();

        int modeW = tab.access().values().stream().mapToInt(mode -> font.width(modeLabel(mode))).max().orElse(0) + 12;
        modeButton = Button.builder(Component.empty(), button -> stepMode()).bounds(valueX, guiTop + MODE_Y, modeW, ROW_H).build();

        synced = null;
        view().ifPresent(this::sync);
    }

    @Override
    public List<? extends GuiEventListener> widgets() {
        return List.of(claimButton, modeButton);
    }

    private Optional<DeviceView> view() {
        return screen.getMenu().deviceView();
    }

    private DeviceMenu menu() {
        return (DeviceMenu) screen.getMenu();
    }

    private static Component modeLabel(String mode) {
        return Component.translatable("mineqtt.device.access.mode." + mode);
    }

    // A new view: what the device has now, and who may change it.
    private void sync(DeviceView view) {
        synced = view;
        boolean nobody = view.ownership().owner() == DeviceView.Owner.NOBODY;
        claimButton.visible = nobody;
        claimButton.setTooltip(Tooltip.create(Component.translatable(AccessTab.claimable(view)
                ? "mineqtt.device.access.claim.tooltip" : "mineqtt.device.general.read_only")));
        boolean change = tab.access().editable(view.grants());
        modeButton.setTooltip(Tooltip.create(Component.translatable(change ? "mineqtt.device.access.mode.tooltip" : "mineqtt.device.access.denied")));
    }

    private void claim() {
        DeviceView view = synced;
        if (view == null || claimSent || !AccessTab.claimable(view)) return;
        claimSent = true;
        sentUntil = Util.getMillis() + SETTLE_MILLIS;
        menu().forgetRefusal();
        NetworkManager.sendToServer(new DeviceClaimPayload(view.pos()));
    }

    // Steps on from what the button shows, so a second click before the answer goes one further.
    private void stepMode() {
        DeviceView view = synced;
        if (view == null || !tab.access().editable(view.grants())) return;
        String next = tab.access().next(shownMode(view));
        sentMode = next;
        sentUntil = Util.getMillis() + SETTLE_MILLIS;
        menu().forgetRefusal();
        NetworkManager.sendToServer(tab.access().send(view.pos(), new JsonPrimitive(next)));
    }

    private String shownMode(DeviceView view) {
        return sentMode != null ? sentMode : view.access().getSerializedName();
    }

    // A player who may change nothing here sees why, as on the other tabs.
    @Override
    public Optional<Component> titleNote() {
        DeviceView view = synced;
        return view != null && !tab.access().editable(view.grants()) && !AccessTab.claimable(view)
                ? Optional.of(Component.translatable("mineqtt.device.general.read_only.short")) : Optional.empty();
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        Optional<DeviceView> current = view();
        if (current.isEmpty()) return;
        DeviceView view = current.get();
        if (view != synced) sync(view);
        Optional<DeviceMenu.Refusal> refusal = menu().refusal().filter(refused -> AccessTab.REFUSAL_KEYS.contains(refused.key()));
        // What was sent is answered by a view that shows it, by a refusal, or after a while by any view.
        if (refusal.isPresent() || Util.getMillis() >= sentUntil || view.access().getSerializedName().equals(sentMode)) sentMode = null;
        if (refusal.isPresent() || Util.getMillis() >= sentUntil || view.ownership().owner() != DeviceView.Owner.NOBODY) claimSent = false;
        boolean change = tab.access().editable(view.grants());
        boolean claimable = AccessTab.claimable(view);

        label(g, "owner", guiTop + OWNER_Y + 3);
        int ownerW = (claimButton.visible ? claimButton.getX() - GAP : left + CONTENT_W) - valueX;
        switch (view.ownership().owner()) {
            case NOBODY -> GuiText.drawTruncated(g, Component.translatable("mineqtt.device.access.owner.nobody"), valueX, guiTop + OWNER_Y + 3,
                    ownerW, MUTED_COLOR, mouseX, mouseY);
            case KNOWN -> GuiText.drawTruncated(g, view.ownerName(), valueX, guiTop + OWNER_Y + 3, ownerW, VALUE_COLOR, mouseX, mouseY);
            case NOT_VERIFIED -> GuiText.drawTruncated(g, Component.translatable("mineqtt.device.access.owner.not_verified", view.ownerName()),
                    valueX, guiTop + OWNER_Y + 3, ownerW, MUTED_COLOR, mouseX, mouseY);
        }
        claimButton.active = claimable && !claimSent;

        label(g, "mode", guiTop + MODE_Y + 3);
        String mode = shownMode(view);
        modeButton.setMessage(modeLabel(mode));
        modeButton.active = change;

        if (refusal.isPresent()) {
            // The runtime's reason names the device too, so it takes the room of the claim hint as well.
            GuiText.drawWrappedScaled(g, Component.translatable("mineqtt.device.refused", refusal.get().reason()).getString(), left, guiTop + MEANING_Y,
                    CONTENT_W, CLAIM_HINT_Y + MEANING_H - MEANING_Y, REFUSED_COLOR, mouseX, mouseY);
            return;
        }
        GuiText.drawWrappedScaled(g, Component.translatable("mineqtt.device.access.mode." + mode + ".meaning").getString(), left, guiTop + MEANING_Y,
                CONTENT_W, MEANING_H, VALUE_COLOR, mouseX, mouseY);
        String hint = view.ownership().claimMod() ? "mineqtt.device.access.claim_mod" : "mineqtt.device.access.no_claim_mod";
        GuiText.drawWrappedScaled(g, Component.translatable(hint).getString(), left, guiTop + CLAIM_HINT_Y, CONTENT_W, MEANING_H, MUTED_COLOR, mouseX, mouseY);
    }

    private void label(GuiGraphicsExtractor g, String key, int y) {
        GuiText.drawTruncated(g, Component.translatable("mineqtt.device.access." + key), left, y, valueX - left - GAP, LABEL_COLOR);
    }
}

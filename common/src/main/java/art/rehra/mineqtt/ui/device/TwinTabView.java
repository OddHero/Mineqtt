package art.rehra.mineqtt.ui.device;

import art.rehra.mineqtt.compat.Inputs;
import art.rehra.mineqtt.compat.Screens;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.LinkSetting;
import art.rehra.mineqtt.device.TwinView;
import art.rehra.mineqtt.network.MineqttNetworking.TwinResultsPayload;
import art.rehra.mineqtt.network.MineqttNetworking.TwinSearchPayload;
import art.rehra.mineqtt.twin.core.TwinHealth;
import art.rehra.mineqtt.ui.TwinPickerScreen;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Draws the {@link TwinTab} from the {@link TwinView} the server sends with every view of the device (M3 plan D19): the
 * source cycle (This block, Home Assistant, MQTT device), the entity it mirrors with the picker behind it or the MQTT
 * device's preset and name (M3-17), the entity's name, id and area, the settings that shape the binding, and the health
 * line with Resume while paused. Every change goes out as the binding key's {@code DeviceConfigC2S}, as
 * {@code /mineqtt devices setting} would send it; nothing changes here until the server's next view shows it. A cell the
 * player may not change is read-only and says why on hover; the details of the binding reach only a player who may
 * configure the device. The Configurator's Bind mode opens the picker as soon as the view asks for it.
 * <p>
 * The source cycle of a bound device unbinds it; on an unbound device it steps This block → Home Assistant (opening the
 * entity picker) → MQTT device → This block, kept on the menu while the picker covers the screen. The "MQTT device"
 * source cycles through the presets the server lists for the type (a preset search, {@code TwinSearchC2S} of kind
 * {@code PRESET}) and takes the device's name in a text field: Enter binds {@code twin.device "<preset> <name>"}; a preset
 * stepped on a bound device goes out as {@code twin.preset}.
 */
public class TwinTabView implements MqttTabView {
    private static final int MUTED_COLOR = 0xFF777777;
    private static final int GOOD_COLOR = 0xFF2E7D32;
    private static final int WARN_COLOR = 0xFF9A5B00;
    private static final int BAD_COLOR = 0xFFAA0000;
    private static final int PREVIEW_COLOR = 0xFF0066CC;
    private static final int TEXT_Y = 3;
    // Narrower cycle cells show their value without the setting's name.
    private static final int NARROW_W = 60;
    // An unanswered preset search is asked again after this long, a few times: the server drops a search over its rate.
    private static final long PRESET_RESEND_MILLIS = 1000;
    private static final int PRESET_TRIES = 3;

    private final TabbedMqttScreen screen;
    private final DeviceMenu menu;
    private final TwinTab tab;
    private final List<AbstractWidget> widgets = new ArrayList<>();
    // Every cell, also both of a place the source cycle shares (TwinTab.BySource).
    private final List<CellView> cells = new ArrayList<>();
    private final List<CellView[]> bySource = new ArrayList<>();
    private @Nullable Button resume;
    private int left;
    private int top;
    // The view the cells were last brought up to date with, and the preset list they showed with it.
    private @Nullable TwinView synced;
    private @Nullable TwinResultsPayload syncedPresets;
    private @Nullable TwinTab.SourceMode syncedMode;
    // The preset search: when it was last sent, and how often.
    private long presetsAskedAt;
    private int presetTries;

    public TwinTabView(TabbedMqttScreen screen) {
        this.screen = screen;
        this.menu = (DeviceMenu) screen.getMenu();
        this.tab = (TwinTab) screen.getMenu().tabs.stream().filter(TwinTab.class::isInstance).findFirst().orElseThrow();
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        cells.clear();
        bySource.clear();
        widgets.clear();
        resume = null;
        left = guiLeft + 8;
        top = guiTop;
        for (int index = 0; index < tab.rows().size(); index++) {
            TwinTab.Row row = tab.rows().get(index);
            List<int[]> spans = row.spans();
            for (int i = 0; i < row.cells().size(); i++) {
                int x = left + spans.get(i)[0];
                int width = spans.get(i)[1];
                int y = guiTop + TwinTab.rowY(index);
                if (row.cells().get(i) instanceof TwinTab.BySource place) {
                    CellView homeAssistant = cell(place.homeAssistant(), x, y, width);
                    CellView mqttDevice = cell(place.mqttDevice(), x, y, width);
                    bySource.add(new CellView[]{homeAssistant, mqttDevice});
                    add(homeAssistant);
                    add(mqttDevice);
                } else {
                    add(cell(row.cells().get(i), x, y, width));
                }
            }
        }
        tab.resume().ifPresent(control -> {
            HealthView health = cells.stream().filter(HealthView.class::isInstance).map(HealthView.class::cast).findFirst().orElseThrow();
            Component label = Component.translatable("mineqtt.twin.resume");
            int width = GuiText.font().width(label) + 10;
            resume = Button.builder(label, pressed -> send(control, control.pressed()))
                    .bounds(health.x + health.width - width, health.y, width, TwinTab.ROW_H)
                    .tooltip(Tooltip.create(Component.translatable("mineqtt.twin.resume.tooltip"))).build();
            resume.visible = false;
            widgets.add(resume);
        });
        synced = null;
        view().ifPresent(this::sync);
    }

    private CellView cell(TwinTab.Cell cell, int x, int y, int width) {
        return switch (cell) {
            case TwinTab.Source source -> new SourceView(x, y, width);
            case TwinTab.Pick pick -> new PickView(pick.key(), x, y, width);
            case TwinTab.Clear clear -> new ClearView(clear.key(), x, y, width);
            case TwinTab.Info info -> new InfoView(info.key(), x, y, width);
            case TwinTab.Preset preset -> new PresetView(x, y, width);
            case TwinTab.Device device -> new DeviceNameView(x, y, width);
            case TwinTab.Setting setting -> switch (setting.control().widget()) {
                case TOGGLE -> new ToggleView(setting.control(), x, y, width);
                case CYCLE -> new CycleView(setting.control(), x, y, width);
                case TEXT_FIELD -> new FieldView(setting.control(), x, y, width);
                default -> throw new IllegalStateException("The Twin tab has no cell for " + setting.control());
            };
            case TwinTab.Health health -> new HealthView(x, y, width);
            case TwinTab.BySource place -> throw new IllegalStateException("A place holds plain cells: " + place);
        };
    }

    private void add(CellView cell) {
        cells.add(cell);
        widgets.addAll(cell.widgets());
    }

    @Override
    public List<? extends GuiEventListener> widgets() {
        return widgets;
    }

    private Optional<TwinView> view() {
        return menu.twinView();
    }

    private Optional<DeviceView> deviceView() {
        return menu.deviceView();
    }

    @Override
    public Optional<Component> titleNote() {
        return synced != null && !synced.details() ? Optional.of(Component.translatable("mineqtt.device.general.read_only.short")) : Optional.empty();
    }

    /**
     * The source the cycle shows: a bound device's own, else what the player stepped it to (M3-17). A viewer without
     * details only ever sees the bound source.
     */
    private TwinTab.SourceMode mode(TwinView view) {
        TwinTab.SourceMode bound = TwinTab.SourceMode.of(view);
        if (view.bound() || !view.details()) return bound;
        return menu.sourceChoice().orElse(TwinTab.SourceMode.THIS_BLOCK);
    }

    // A new view: every cell shows it, except a text field the player is typing into; a place shows the source's cell.
    private void sync(TwinView view) {
        synced = view;
        syncedPresets = menu.presetResults().orElse(null);
        TwinTab.SourceMode mode = mode(view);
        syncedMode = mode;
        cells.forEach(cell -> cell.show(view));
        boolean device = mode == TwinTab.SourceMode.MQTT_DEVICE;
        for (CellView[] place : bySource) {
            place[0].setShown(!device);
            place[1].setShown(device);
        }
        if (resume != null) {
            resume.visible = view.paused();
            resume.active = view.details();
        }
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        Optional<TwinView> current = view();
        if (current.isPresent() && (current.get() != synced || menu.presetResults().orElse(null) != syncedPresets || mode(current.get()) != syncedMode)) {
            sync(current.get());
        }
        // The Configurator's Bind mode asked for the picker (M3 plan D19).
        menu.takePickRequest().ifPresent(this::openPickerLater);
        if (synced == null) {
            GuiText.drawTruncated(g, Component.translatable("mineqtt.twin.loading"), left, top + TwinTab.rowY(0) + TEXT_Y, TwinTab.CONTENT_W, MUTED_COLOR);
            return;
        }
        askPresets(synced);
        cells.forEach(cell -> {
            if (cell.shown) cell.draw(g, mouseX, mouseY);
        });
    }

    /**
     * Asks the server for the presets the "MQTT device" source cycles through, once it shows or the device is bound to
     * one, for a viewer who may bind; again after a second while no answer came (a search over the rate is dropped), a few
     * times at most.
     */
    private void askPresets(TwinView view) {
        if (menu.presetResults().isPresent() || !view.details() || !view.mayBind() || !tab.adapter().hasSource()) return;
        if (mode(view) != TwinTab.SourceMode.MQTT_DEVICE && !view.boundToDevice()) return;
        long now = Util.getMillis();
        if (presetTries >= PRESET_TRIES || presetTries > 0 && now - presetsAskedAt < PRESET_RESEND_MILLIS) return;
        presetsAskedAt = now;
        presetTries++;
        NetworkManager.sendToServer(new TwinSearchPayload(menu.blockPos, TwinPicker.SearchKind.PRESET, TwinPicker.PRESET_KEY, "", ""));
    }

    /** Enter commits the text field that has the focus; while one has it, it takes every other key but Escape. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_ESCAPE) return false;
        for (CellView cell : cells) {
            if (cell instanceof TextView text && text.shown && text.field.isFocused()) return text.keyPressed(keyCode, scanCode, modifiers);
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // A click elsewhere ends the typing and sends what was typed.
        for (CellView cell : cells) {
            if (cell instanceof TextView text && text.field.isFocused() && !text.field.isMouseOver(mouseX, mouseY)) {
                text.field.setFocused(false);
                text.commit();
            }
        }
        return false;
    }

    @Override
    public void removed() {
        // Typed but not sent: sent when the tab is left, as a click elsewhere would.
        if (Minecraft.getInstance().getConnection() == null) return;
        for (CellView cell : cells) {
            if (cell instanceof TextView text && text.shown) text.commit();
        }
    }

    /**
     * Opens the entity picker for {@code key} over the device's screen (client), after this frame or click: a screen
     * replaced while it draws or handles a click would go on without its tab.
     */
    private void openPickerLater(String key) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.schedule(() -> {
            if (Screens.current() == screen) Screens.open(new TwinPickerScreen(screen, menu, tab, key));
        });
    }

    // A change of one binding key, as the server takes it from the device's screen; the cell shows it with the next view.
    private void send(DeviceTabs.Control control, JsonElement value) {
        DeviceView view = deviceView().orElse(null);
        if (view == null || !control.editable(view.grants())) return;
        menu.forgetRefusal();
        NetworkManager.sendToServer(control.send(view.pos(), value));
    }

    // The presets the server listed for the "MQTT device" source (M3-17), best first; empty until it answered.
    private List<TwinPicker.Row> presets() {
        return menu.presetResults().map(TwinResultsPayload::rows).orElse(List.of());
    }

    // The preset the "MQTT device" source shows: the bound device's, else the player's choice, else the first listed.
    private Optional<String> currentPreset(TwinView view) {
        Optional<String> bound = view.device().map(TwinView.Device::preset).filter(id -> !id.isEmpty());
        if (bound.isPresent()) return bound;
        List<TwinPicker.Row> listed = presets();
        Optional<String> chosen = menu.presetChoice().filter(id -> listed.stream().anyMatch(row -> row.entityId().equals(id)));
        return chosen.or(() -> listed.stream().findFirst().map(TwinPicker.Row::entityId));
    }

    // The entity a key holds as a line: its name, id and area, or that the viewer may not see them.
    private Component entityLine(TwinView view, String key) {
        Optional<TwinView.Entity> entity = view.entity(key);
        if (entity.isEmpty()) {
            return Component.translatable(TwinPicker.target(tab.adapter(), key) ? "mineqtt.twin.target.none" : "mineqtt.twin.unbound");
        }
        if (!view.details()) return Component.translatable("mineqtt.twin.hidden");
        TwinView.Entity known = entity.get();
        MutableComponent line = Component.literal(known.name().isEmpty() ? known.id() : known.name() + " · " + known.id());
        if (!known.area().isEmpty()) line.append(" · " + known.area());
        return line;
    }

    /** What a target's name reads as: Target, Left, Right (M3-14). */
    public static Component targetLabel(String name) {
        return Component.translatableWithFallback("mineqtt.twin.target." + name, name);
    }

    /** What the health line says of a health. */
    public static Component health(TwinHealth health) {
        return Component.translatable("mineqtt.twin.health." + health.key());
    }

    /** What the source cycle reads for a source (M3-17). */
    public static Component sourceLabel(TwinTab.SourceMode mode) {
        return Component.translatable(switch (mode) {
            case THIS_BLOCK -> "mineqtt.twin.source.this_block";
            case HOME_ASSISTANT -> "mineqtt.twin.source.home_assistant";
            case MQTT_DEVICE -> "mineqtt.twin.source.mqtt_device";
        });
    }

    // Why a cell is read-only, or null when the player may change it.
    private static @Nullable Component readOnly(TwinView view, DeviceTabs.Control control) {
        if (!view.details()) return Component.translatable("mineqtt.device.general.read_only");
        if (control.gate() == LinkSetting.Gate.TWIN && !view.mayBind()) return Component.translatable("mineqtt.twin.no_bind");
        return null;
    }

    /** The widgets and text of one cell. */
    private abstract static class CellView {
        final int x;
        final int y;
        final int width;
        // Whether the cell shows: both cells of a place exist, the source cycle shows one (M3-17).
        boolean shown = true;

        CellView(int x, int y, int width) {
            this.x = x;
            this.y = y;
            this.width = width;
        }

        List<? extends AbstractWidget> widgets() {
            return List.of();
        }

        /** Shows a new view. */
        abstract void show(TwinView view);

        /** Shows or hides the cell; a hidden one takes no click and no key. */
        void setShown(boolean shown) {
            this.shown = shown;
            for (AbstractWidget widget : widgets()) {
                widget.visible = shown;
                if (!shown) widget.setFocused(false);
            }
        }

        void draw(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        }
    }

    /**
     * The source cycle (M3-17): a bound device's source, which a press unbinds; on an unbound device This block, Home
     * Assistant (the press that chooses it opens the picker) and MQTT device in turn.
     */
    private final class SourceView extends CellView {
        private final Button button;
        private TwinTab.SourceMode mode = TwinTab.SourceMode.THIS_BLOCK;
        private boolean bound;

        SourceView(int x, int y, int width) {
            super(x, y, width);
            this.button = Button.builder(Component.empty(), pressed -> press()).bounds(x, y, width, TwinTab.ROW_H).build();
        }

        private void press() {
            if (bound) {
                // This block: the source goes, whichever it is.
                String key = mode == TwinTab.SourceMode.MQTT_DEVICE ? LinkSetting.TWIN_DEVICE.key() : LinkSetting.TWIN_ENTITY.key();
                tab.control(key).ifPresent(control -> send(control, new JsonPrimitive("")));
                return;
            }
            TwinTab.SourceMode next = mode.next();
            menu.chooseSource(next);
            if (next == TwinTab.SourceMode.HOME_ASSISTANT) openPickerLater(LinkSetting.TWIN_ENTITY.key());
        }

        @Override
        List<Button> widgets() {
            return List.of(button);
        }

        @Override
        void show(TwinView view) {
            bound = view.bound();
            mode = mode(view);
            button.setMessage(sourceLabel(mode));
            Component why = tab.control(LinkSetting.TWIN_ENTITY.key()).map(control -> readOnly(view, control)).orElse(null);
            button.active = why == null;
            String tooltip = switch (mode) {
                case THIS_BLOCK -> "mineqtt.twin.source.bind.tooltip";
                case HOME_ASSISTANT -> bound ? "mineqtt.twin.source.unbind.tooltip" : "mineqtt.twin.source.choose_ha.tooltip";
                case MQTT_DEVICE -> bound ? "mineqtt.twin.source.unbind_device.tooltip" : "mineqtt.twin.source.choose_device.tooltip";
            };
            button.setTooltip(Tooltip.create(why != null ? why : Component.translatable(tooltip)));
        }
    }

    /** The entity a key holds; pressing it opens the picker. A target's reads "Left: Movie Night" (M3-14). */
    private final class PickView extends CellView {
        private final String key;
        private final Optional<String> target;
        private final Button button;

        PickView(String key, int x, int y, int width) {
            super(x, y, width);
            this.key = key;
            this.target = TwinPicker.targetName(tab.adapter(), key);
            this.button = Button.builder(Component.empty(), pressed -> openPickerLater(key)).bounds(x, y, width, TwinTab.ROW_H).build();
        }

        @Override
        List<Button> widgets() {
            return List.of(button);
        }

        @Override
        void show(TwinView view) {
            Optional<TwinView.Entity> entity = view.entity(key);
            Component label = entity.isEmpty() || !view.details() ? Component.translatable(target.isPresent() && entity.isPresent()
                    ? "mineqtt.twin.hidden.short" : "mineqtt.twin.pick") : Component.literal(entity.get().label());
            button.setMessage(target.map(name -> (Component) CommonComponents.optionNameValue(targetLabel(name), label)).orElse(label));
            button.active = view.details();
            Component tooltip = !view.details() ? Component.translatable("mineqtt.device.general.read_only")
                    : target.map(name -> (Component) Component.translatable("mineqtt.twin.pick.target.tooltip", targetLabel(name)))
                    .orElseGet(() -> Component.translatable("mineqtt.twin.pick.tooltip"));
            button.setTooltip(Tooltip.create(tooltip));
        }
    }

    /** Removes the entity a target holds (M3-14): the target then switches nothing. */
    private final class ClearView extends CellView {
        private final String key;
        private final Button button;

        ClearView(String key, int x, int y, int width) {
            super(x, y, width);
            this.key = key;
            this.button = Button.builder(Component.literal("×"), pressed -> tab.control(key).ifPresent(control -> send(control, new JsonPrimitive(""))))
                    .bounds(x, y, width, TwinTab.ROW_H).build();
        }

        @Override
        List<Button> widgets() {
            return List.of(button);
        }

        @Override
        void show(TwinView view) {
            Component why = tab.control(key).map(control -> readOnly(view, control)).orElse(null);
            boolean set = view.entity(key).isPresent();
            button.active = why == null && set;
            String name = key.substring(LinkSetting.TWIN_PREFIX.length());
            button.setTooltip(Tooltip.create(why != null ? why : Component.translatable("mineqtt.twin.clear.tooltip", targetLabel(name))));
        }
    }

    /** The name, id and area of the entity a key holds. */
    private final class InfoView extends CellView {
        private final String key;
        private Component line = Component.empty();

        InfoView(String key, int x, int y, int width) {
            super(x, y, width);
            this.key = key;
        }

        @Override
        void show(TwinView view) {
            line = entityLine(view, key);
        }

        @Override
        void draw(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            GuiText.drawTruncated(g, line, x, y + TEXT_Y, width, MUTED_COLOR, mouseX, mouseY);
        }
    }

    /**
     * The preset of the "MQTT device" source (M3-17): a cycle of the presets the server lists for the type. On a bound
     * device a step sends {@code twin.preset}; before it is bound the step only chooses the preset its name binds with.
     */
    private final class PresetView extends CellView {
        private final Button button;
        private @Nullable String current;
        private boolean bound;

        PresetView(int x, int y, int width) {
            super(x, y, width);
            this.button = Button.builder(Component.empty(), pressed -> step()).bounds(x, y, width, TwinTab.ROW_H).build();
        }

        private void step() {
            List<TwinPicker.Row> listed = presets();
            if (listed.isEmpty()) return;
            int at = -1;
            for (int i = 0; i < listed.size(); i++) {
                if (listed.get(i).entityId().equals(current)) at = i;
            }
            String next = listed.get((at + 1) % listed.size()).entityId();
            if (next.equals(current)) return;
            if (bound) {
                tab.control(LinkSetting.TWIN_PRESET.key()).ifPresent(control -> send(control, new JsonPrimitive(next)));
            } else {
                menu.choosePreset(next);
                // The cell shows the choice at once; the device's name field binds with it.
                view().ifPresent(TwinTabView.this::sync);
            }
        }

        @Override
        List<Button> widgets() {
            return List.of(button);
        }

        @Override
        void show(TwinView view) {
            bound = view.boundToDevice();
            current = currentPreset(view).orElse(null);
            List<TwinPicker.Row> listed = presets();
            Optional<TwinPicker.Row> row = listed.stream().filter(candidate -> candidate.entityId().equals(current)).findFirst();
            Component label;
            if (!view.details()) {
                label = Component.translatable("mineqtt.twin.hidden.short");
            } else if (current != null) {
                String name = row.map(TwinPicker.Row::label).orElseGet(() -> view.device().map(TwinView.Device::presetLabel).orElse(current));
                label = Component.literal(name);
            } else {
                label = Component.translatable(menu.presetResults().isPresent() ? "mineqtt.twin.preset.none" : "mineqtt.twin.loading");
            }
            button.setMessage(label);
            Component why = tab.control(LinkSetting.TWIN_PRESET.key()).map(control -> readOnly(view, control)).orElse(null);
            if (why == null && current == null) why = Component.translatable("mineqtt.twin.preset.none.tooltip");
            button.active = why == null && listed.size() > (bound ? 1 : 0);
            if (why != null) {
                button.setTooltip(Tooltip.create(why));
                return;
            }
            String kind = row.map(TwinPicker.Row::value).orElseGet(() -> view.device().map(TwinView.Device::kind).orElse(""));
            boolean sends = row.map(TwinPicker.Row::control).orElse(false);
            MutableComponent tooltip = Component.translatable("mineqtt.twin.preset.tooltip", label, current,
                    Component.translatableWithFallback("mineqtt.twin.domain." + kind, kind));
            if (row.isPresent()) tooltip.append("\n").append(Component.translatable(sends ? "mineqtt.twin.preset.sends" : "mineqtt.twin.preset.read_only"));
            if (listed.size() > 1) tooltip.append("\n").append(Component.translatable("mineqtt.twin.preset.next"));
            button.setTooltip(Tooltip.create(tooltip));
        }
    }

    /**
     * A text cell: Enter or a click elsewhere sends it, never typing alone; the field shows the server's text unless the
     * player changed it since, and what they typed is blue until it is sent.
     */
    private abstract class TextView extends CellView {
        final EditBox field;
        String syncedText = "";

        TextView(int x, int y, int width, Component label, int maxLength) {
            super(x, y, width);
            this.field = new EditBox(GuiText.font(), x, y, width, TwinTab.ROW_H, label);
            field.setMaxLength(maxLength);
            field.setTextColorUneditable(MUTED_COLOR);
            field.setHint(label.copy().withStyle(EditBox.DEFAULT_HINT_STYLE));
        }

        @Override
        List<EditBox> widgets() {
            return List.of(field);
        }

        // The server's text: shown unless the player changed the field since the last one.
        void showText(String text) {
            if (field.getValue().equals(syncedText) && !field.getValue().equals(text)) {
                field.setValue(text);
                field.moveCursorToStart(false);
            }
            syncedText = text;
        }

        void editable(@Nullable Component why, Component tooltip) {
            boolean editable = why == null;
            field.setEditable(editable);
            field.active = editable;
            field.setTooltip(Tooltip.create(why != null ? why : tooltip));
        }

        @Override
        void draw(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            field.setTextColor(field.getValue().strip().equals(syncedText) ? EditBox.DEFAULT_TEXT_COLOR : PREVIEW_COLOR);
        }

        boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
                commit();
                field.setFocused(false);
                return true;
            }
            return Inputs.keyPressed(field, keyCode, scanCode, modifiers) || field.canConsumeInput();
        }

        // Sends what was typed, if it differs from what the server keeps; the field keeps it until the view answers.
        void commit() {
            String text = field.getValue().strip();
            if (text.equals(syncedText) || !field.active) return;
            field.setValue(text);
            if (sendText(text)) syncedText = text;
        }

        /** Sends a typed text; false when nothing went out. */
        abstract boolean sendText(String text);
    }

    /**
     * The name of the "MQTT device" source (M3-17): Enter binds {@code twin.device "<preset> <name>"} with the preset the
     * cell beside the source shows. An empty name sends nothing: the source cycle unbinds.
     */
    private final class DeviceNameView extends TextView {
        private @Nullable String preset;

        DeviceNameView(int x, int y, int width) {
            super(x, y, width, Component.translatable("mineqtt.twin.short.twin.device"), TwinView.MAX_DEVICE_LENGTH);
        }

        @Override
        void show(TwinView view) {
            preset = view.details() ? currentPreset(view).orElse(null) : null;
            showText(view.device().map(TwinView.Device::device).orElse(""));
            if (!view.details() && view.boundToDevice()) {
                field.setHint(Component.translatable("mineqtt.twin.hidden.device").withStyle(EditBox.DEFAULT_HINT_STYLE));
            }
            Component why = tab.control(LinkSetting.TWIN_DEVICE.key()).map(control -> readOnly(view, control)).orElse(null);
            if (why == null && preset == null) why = Component.translatable("mineqtt.twin.preset.none.tooltip");
            MutableComponent tooltip = Component.translatable("mineqtt.twin.tooltip.twin.device");
            view.device().map(TwinView.Device::topic).filter(topic -> !topic.isEmpty())
                    .ifPresent(topic -> tooltip.append("\n").append(Component.translatable("mineqtt.twin.device.topic", topic)));
            editable(why, tooltip);
        }

        @Override
        boolean sendText(String text) {
            if (text.isEmpty() || preset == null) return false;
            String value = preset + " " + text;
            tab.control(LinkSetting.TWIN_DEVICE.key()).ifPresent(control -> send(control, new JsonPrimitive(value)));
            return true;
        }
    }

    /** A switch of a binding: control or invert. It needs a source first. */
    private final class ToggleView extends CellView {
        private final DeviceTabs.Control control;
        private final Button button;
        private boolean on;

        ToggleView(DeviceTabs.Control control, int x, int y, int width) {
            super(x, y, width);
            this.control = control;
            this.button = Button.builder(Component.empty(), pressed -> send(control, control.toggled(!on))).bounds(x, y, width, TwinTab.ROW_H).build();
        }

        @Override
        List<Button> widgets() {
            return List.of(button);
        }

        @Override
        void show(TwinView view) {
            boolean controlKey = control.key().equals(LinkSetting.TWIN_CONTROL.key());
            on = controlKey ? view.control() : view.settings().invert();
            button.setMessage(CommonComponents.optionNameValue(shortLabel(control), CommonComponents.optionStatus(on)));
            Component why = readOnly(view, control);
            if (why == null && !view.bound()) why = Component.translatable("mineqtt.twin.needs_source");
            // Turning control on needs mineqtt:twin.control; turning it off needs none (D13).
            if (why == null && controlKey && !on && !view.mayControl()) why = Component.translatable("mineqtt.twin.no_control");
            button.active = why == null;
            button.setTooltip(Tooltip.create(why != null ? why : controlKey ? controlWarning(view) : tooltip(control)));
        }

        // What turning control on means (D6, D13 #4): who switches the entity or the MQTT device, and the wires that will.
        private Component controlWarning(TwinView view) {
            String access = deviceView().map(device -> device.access().getSerializedName()).orElse("");
            Optional<TwinView.Device> device = view.device();
            String source = view.source().map(TwinView.Entity::label).or(() -> device.map(TwinView.Device::device)).orElse("");
            MutableComponent warning = device.isPresent() ? Component.translatable("mineqtt.twin.control.warning.device", source, access)
                    : Component.translatable("mineqtt.twin.control.warning", source, access);
            if (view.wires() > 0) warning.append("\n").append(Component.translatable("mineqtt.twin.control.wires", view.wires(), source));
            return warning;
        }
    }

    /** A choice of a binding that steps through its values: unavailable, decimals. */
    private final class CycleView extends CellView {
        private final DeviceTabs.Control control;
        private final Button button;
        private String selected = "";

        CycleView(DeviceTabs.Control control, int x, int y, int width) {
            super(x, y, width);
            this.control = control;
            this.button = Button.builder(Component.empty(), pressed -> send(control, new JsonPrimitive(control.next(selected))))
                    .bounds(x, y, width, TwinTab.ROW_H).build();
        }

        @Override
        List<Button> widgets() {
            return List.of(button);
        }

        @Override
        void show(TwinView view) {
            selected = control.key().equals(LinkSetting.TWIN_DECIMALS.key()) ? view.settings().decimals() : view.settings().unavailable();
            Component value = Component.translatableWithFallback("mineqtt.twin.value." + control.key() + "." + selected, selected);
            // A narrow cell shows the value alone; its tooltip names the setting.
            button.setMessage(width < NARROW_W ? value : CommonComponents.optionNameValue(shortLabel(control), value));
            Component why = readOnly(view, control);
            if (why == null && !view.bound()) why = Component.translatable("mineqtt.twin.needs_source");
            button.active = why == null;
            button.setTooltip(Tooltip.create(why != null ? why : tooltip(control)));
        }
    }

    /** A text of a binding: the path, the range. */
    private final class FieldView extends TextView {
        private final DeviceTabs.Control control;

        FieldView(DeviceTabs.Control control, int x, int y, int width) {
            super(x, y, width, shortLabel(control), control.maxLength());
            this.control = control;
        }

        @Override
        void show(TwinView view) {
            showText(switch (control.key()) {
                case "twin.path" -> view.settings().path();
                case "twin.min" -> view.settings().min();
                case "twin.max" -> view.settings().max();
                default -> "";
            });
            Component why = readOnly(view, control);
            if (why == null && !view.bound()) why = Component.translatable("mineqtt.twin.needs_source");
            editable(why, tooltip(control));
        }

        @Override
        boolean sendText(String text) {
            send(control, new JsonPrimitive(text));
            return true;
        }
    }

    /**
     * How the binding is doing (D6): its source's health, a request waiting, the control blueprint's last answer, or why
     * the twin is off; a change the server refused from this tab in its place until the next change.
     */
    private final class HealthView extends CellView {
        private Component line = Component.empty();
        private int color = MUTED_COLOR;
        private @Nullable Component hover;

        HealthView(int x, int y, int width) {
            super(x, y, width);
        }

        @Override
        void show(TwinView view) {
            hover = null;
            // The Home Assistant twin being off does not stop an MQTT device (M3-17 decision 2).
            boolean off = !view.off().isEmpty() && mode(view) != TwinTab.SourceMode.MQTT_DEVICE;
            if (view.health().isEmpty() && (off || view.targets().isEmpty())) {
                line = off ? Component.translatable("mineqtt.twin.off", view.off()) : Component.translatable("mineqtt.twin.health.none");
                color = off ? WARN_COLOR : MUTED_COLOR;
                return;
            }
            // A device with working targets only has no health of its own (M3-14): it switches its entities.
            TwinHealth health = view.health().orElse(null);
            MutableComponent text = health == null ? Component.translatable("mineqtt.twin.health.targets")
                    : view.boundToDevice() ? deviceHealth(health).copy() : health(health).copy();
            if (view.pending()) text.append(" · ").append(Component.translatable("mineqtt.twin.pending"));
            view.answer().filter(status -> !status.equals("accepted") && !status.equals("pong"))
                    .ifPresent(status -> text.append(" · ").append(Component.translatable("mineqtt.twin.last_answer", status)));
            // The requests the rates refused, the last one's target and reason on hover for CONFIGURE.
            // A device with targets only leads with the count: its own words say nothing new and would cut it off.
            MutableComponent shown = text;
            if (view.rateRefused() > 0) {
                Component rate = Component.translatable("mineqtt.twin.rate_refused", view.rateRefused());
                if (health == null) {
                    shown = Component.empty().append(rate).append(" · ").append(text);
                } else {
                    text.append(" · ").append(rate);
                }
            }
            line = shown;
            color = health == null ? GOOD_COLOR : switch (health) {
                case LIVE -> GOOD_COLOR;
                case RETAINED, WAITING -> WARN_COLOR;
                case UNAVAILABLE, UNKNOWN, NOT_SHARED, REFUSED, CONTROL_PAUSED -> BAD_COLOR;
            };
            MutableComponent details = Component.empty();
            if (!view.refused().isEmpty()) details.append(view.refused());
            if (!view.rateLast().isEmpty()) {
                if (!view.refused().isEmpty()) details.append("\n");
                details.append(Component.translatable("mineqtt.twin.rate_refused.last", view.rateLast()));
            }
            if (!details.getSiblings().isEmpty()) hover = details;
        }

        @Override
        void draw(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            int textWidth = width - (resume != null && resume.visible ? resume.getWidth() + TwinTab.GAP : 0);
            Optional<DeviceMenu.Refusal> refusal = menu.refusal().filter(refused -> refused.key().startsWith(LinkSetting.TWIN_PREFIX));
            if (refusal.isPresent()) {
                GuiText.drawTruncated(g, refusal.get().reason(), x, y + TEXT_Y, textWidth, BAD_COLOR, mouseX, mouseY);
                return;
            }
            GuiText.drawTruncated(g, line, x, y + TEXT_Y, textWidth, color, mouseX, mouseY);
            if (hover != null && mouseX >= x && mouseX < x + textWidth && mouseY >= y && mouseY < y + TwinTab.ROW_H) {
                g.setTooltipForNextFrame(GuiText.font(), GuiText.font().split(hover, TwinTab.CONTENT_W), mouseX, mouseY);
            }
        }
    }

    /** What the health line says of an MQTT device's health (M3-17): its own words where Home Assistant's would mislead. */
    public static Component deviceHealth(TwinHealth health) {
        return Component.translatableWithFallback("mineqtt.twin.health.device." + health.key(), health(health).getString());
    }

    private static Component shortLabel(DeviceTabs.Control control) {
        return Component.translatableWithFallback("mineqtt.twin.short." + control.key(), control.key());
    }

    private static Component tooltip(DeviceTabs.Control control) {
        return Component.translatableWithFallback("mineqtt.twin.tooltip." + control.key(), "");
    }
}

package art.rehra.mineqtt.ui.device;

import art.rehra.mineqtt.blocks.entities.DeviceBlockEntity;
import art.rehra.mineqtt.device.DeviceGroups;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLinks;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.LinkSetting;
import art.rehra.mineqtt.device.TwinView;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceConfigPayload;
import art.rehra.mineqtt.network.MineqttNetworking.TwinResultsPayload;
import art.rehra.mineqtt.security.C2SGuard;
import art.rehra.mineqtt.security.DeviceAccess;
import art.rehra.mineqtt.ui.MineqttMenuTypes;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import dev.architectury.networking.NetworkManager;
import dev.architectury.registry.menu.MenuRegistry;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * The menu of a device's screen (M2 plan §4.2 M2-8): the shared {@link MineqttMenuTypes#MQTT_TABBED_MENU} with the
 * generated tabs of the device's type ({@link DeviceTabs#forType}). It belongs to the device's block entity, which has
 * no container (M2-12), so the menu has no slots, not even the player's inventory, and its screen is only as tall as the
 * tabs need ({@link #guiHeight()}). The screen's title is the device's name. What its tabs show comes from the device's
 * {@link DeviceView} in the open data and the views the server sends while it is open, never from the client's block
 * entity; its links ({@link DeviceLinks}) and its group page ({@link DeviceGroups}) come right after the open data and
 * whenever they change. A player who may not build (P2) gets the Controls tab alone, with a view that shows nothing else
 * ({@link DeviceView#redacted()}), and neither links nor group page.
 */
public class DeviceMenu extends TabbedMqttMenu {

    // Container.stillValidBlockEntity's slack: a screen stays open a few blocks beyond the reach it was opened in.
    private static final double SCREEN_SLACK = 4.0;

    private final BlockEntity device;
    private final boolean controlsOnly;
    private @Nullable Refusal refusal;
    private @Nullable DeviceLinks links;
    private @Nullable DeviceGroups groups;
    private @Nullable TwinView twinView;
    private @Nullable TwinResultsPayload twinResults;
    // The last answer to the Twin tab's preset search (client, M3-17): the presets its "MQTT device" source cycles through.
    private @Nullable TwinResultsPayload presetResults;
    // The binding key the Configurator's Bind mode asked the picker to open for, until the Twin tab takes it (client).
    private @Nullable String pickRequest;
    // The name the block gets once the server shows the entity picked for it bound (client, "Name the block after it").
    private @Nullable PendingRename pendingRename;
    // The Twin tab's source cycle as the player stepped it while the device is unbound (client, M3-17), kept across the
    // tab's views (the picker replaces the screen); forgotten when the device's source changes.
    private @Nullable TwinTab.SourceMode sourceChoice;
    // The preset the Twin tab's "MQTT device" source shows before the device is bound to one (client, M3-17).
    private @Nullable String presetChoice;
    // When the current view arrived (client, Util.getMillis): the ages it gives grow from there.
    private long viewArrived;

    /**
     * A change the player sent from this screen that the server refused (client).
     *
     * @param key    the setting, field or action it was for
     * @param reason why, as the server said it
     * @param link   the device's Raw Link when it was refused, as the view the server sent just before it showed it
     */
    public record Refusal(String key, String reason, Optional<DeviceView.Raw> link) {

        /** Whether it still answers for a view: one with another Raw Link shows a change made since, by any path. */
        public boolean answers(DeviceView view) {
            return link.equals(view.raw());
        }
    }

    /**
     * A rename the picker asked for (client): sent once the next {@link TwinView} shows {@code entity} bound under
     * {@code key}, so a refused bind leaves the name alone; dropped when the bind is refused.
     */
    public record PendingRename(String key, String entity, String name) {
    }

    /**
     * @param device       a {@link DeviceHost} block entity, on the side the menu is built
     * @param initialTabId the tab to show first; null or unknown for the first one
     * @param configurable whether the player may configure the device, which the server judged when it opened
     * @param controlsOnly whether the player may only use the device, so the screen has its Controls tab alone
     */
    public DeviceMenu(int containerId, Inventory playerInventory, BlockEntity device, @Nullable String initialTabId, boolean configurable,
                      boolean controlsOnly) {
        super(MineqttMenuTypes.MQTT_TABBED_MENU.get(), containerId, playerInventory, new SimpleContainer(0),
                DeviceTabs.forViewer(((DeviceHost) device).deviceType(), controlsOnly), playerInventory.player, device.getBlockPos(), initialTabId,
                configurable, null, false);
        this.device = device;
        this.controlsOnly = controlsOnly;
    }

    /**
     * Opens a device's screen for a player who may use it: its view goes into the open data, built for them, and both
     * sides open on the tab they last had open on this device. The screen is titled with the device's name. A player who
     * may only use the device ({@link DeviceView#controlsOnly()}) gets its Controls tab alone; the caller made sure it
     * has one.
     */
    public static void open(ServerPlayer player, DeviceBlockEntity device) {
        open(player, device, device.getLastTabId(), null);
    }

    /**
     * {@link #open(ServerPlayer, DeviceBlockEntity)} on the tab {@code tab} when the player's screen has it (else the first),
     * and with the entity picker opening for the binding key {@code pick} (the Configurator's Bind mode, M3 plan D19):
     * the Twin tab opens it when its first view arrives, which comes right after the screen.
     *
     * @param pick a binding key of the device's type, {@code ""} to ask which target first, or null for no picker
     */
    public static void open(ServerPlayer player, DeviceBlockEntity device, @Nullable String tab, @Nullable String pick) {
        boolean configurable = DeviceAccess.check(player, device, MineqttPermission.CONFIGURE);
        DeviceView view = DeviceRuntime.view(player, device);
        boolean controlsOnly = view.controlsOnly();
        String tabId = initialTabId(DeviceTabs.forViewer(device.deviceType(), controlsOnly), tab);
        MenuRegistry.openExtendedMenu(player,
                new SimpleMenuProvider((containerId, inventory, viewer) -> new DeviceMenu(containerId, inventory, device, tabId, configurable, controlsOnly),
                        title(view, device)),
                buf -> writeOpenData(buf, device.getBlockPos(), configurable, tabId, null, view));
        // A controls-only screen gets neither: MineqttNetworking drops both for it.
        DeviceRuntime.sendLinks(player, device);
        DeviceRuntime.sendGroups(player, device);
        DeviceRuntime.sendTwin(player, device, pick);
    }

    /** Whether the screen is a player's who may only use the device: the Controls tab alone, no links, no group page. */
    public boolean controlsOnly() {
        return controlsOnly;
    }

    /**
     * Whether the player's open screen shows only a device's Controls tab (P2), so nothing beyond its view may reach it:
     * no status line, links or group page.
     */
    public static boolean controlsOnly(Player player) {
        return player.containerMenu instanceof DeviceMenu menu && menu.controlsOnly;
    }

    /** The screen's title: the device's name as the last view has it, so a rename shows at once (client). */
    public Component title() {
        return title(deviceView().orElse(null), device);
    }

    // The device's name; the block's for a view with a blank name, or before the first view.
    private static Component title(@Nullable DeviceView view, BlockEntity device) {
        return view == null || view.name().isBlank() ? device.getBlockState().getBlock().getName() : Component.literal(view.name());
    }

    /**
     * The last change the player sent from this screen that the server refused (client): until the tab that shows it
     * sends or drops it ({@link #forgetRefusal}), or a view arrives with another Raw Link than the one it was refused
     * against, as the link changed since (a slot, a command, another player, the ACL).
     */
    public Optional<Refusal> refusal() {
        return Optional.ofNullable(refusal);
    }

    /** Client: the server refused a change from this screen, against the view it sent just before. */
    public void refused(String key, String reason) {
        this.refusal = new Refusal(key, reason, deviceView().flatMap(DeviceView::raw));
        // A refused bind keeps the block's name.
        if (pendingRename != null && pendingRename.key().equals(key)) pendingRename = null;
    }

    /** Client: forgets the last refusal, as a new change answers for itself. */
    public void forgetRefusal() {
        this.refusal = null;
    }

    /** The device's links as the server last sent them (client); empty until the first list arrives. */
    public Optional<DeviceLinks> links() {
        return Optional.ofNullable(links);
    }

    /** Client: the server sent the device's links. */
    public void setLinks(DeviceLinks links) {
        this.links = links;
    }

    /** The device's group page as the server last sent it (client); empty until the first one arrives. */
    public Optional<DeviceGroups> groups() {
        return Optional.ofNullable(groups);
    }

    /** Client: the server sent the device's group page. */
    public void setGroups(DeviceGroups groups) {
        this.groups = groups;
    }

    /** What the Twin tab shows, as the server last sent it (client); empty until the first one arrives. */
    public Optional<TwinView> twinView() {
        return Optional.ofNullable(twinView);
    }

    /**
     * Client: the server sent what the Twin tab shows. A picker request in it is kept for the tab ({@link #takePickRequest});
     * a rename the picker asked for goes out once the view shows its entity bound.
     */
    public void setTwinView(TwinView view) {
        view.pick().ifPresent(key -> pickRequest = key);
        // A new source (bound, unbound, another kind) shows itself: the player's choice was for the one before.
        if (twinView != null && TwinTab.SourceMode.of(twinView) != TwinTab.SourceMode.of(view)) {
            sourceChoice = null;
            presetChoice = null;
        }
        this.twinView = view.withoutPick();
        PendingRename rename = pendingRename;
        if (rename != null && view.entity(rename.key()).map(TwinView.Entity::id).filter(rename.entity()::equals).isPresent()) {
            pendingRename = null;
            NetworkManager.sendToServer(new DeviceConfigPayload(blockPos, LinkSetting.NAME.key(), StringTag.valueOf(rename.name())));
        }
    }

    /** The source the Twin tab's cycle shows while the device is unbound, as the player stepped it (client, M3-17). */
    public Optional<TwinTab.SourceMode> sourceChoice() {
        return Optional.ofNullable(sourceChoice);
    }

    /** Client: the player stepped the Twin tab's source cycle of the unbound device. */
    public void chooseSource(TwinTab.SourceMode mode) {
        this.sourceChoice = mode;
    }

    /** The preset the Twin tab's "MQTT device" source shows before the device is bound to one (client, M3-17). */
    public Optional<String> presetChoice() {
        return Optional.ofNullable(presetChoice);
    }

    /** Client: the player stepped the preset of the unbound device's "MQTT device" source. */
    public void choosePreset(String preset) {
        this.presetChoice = preset;
    }

    /** Client: the binding key the picker should open for, once; empty when nothing asked for it since. */
    public Optional<String> takePickRequest() {
        String key = pickRequest;
        pickRequest = null;
        return Optional.ofNullable(key);
    }

    /** Client: the picker asks for the block to be named after the entity it binds, once the bind shows. */
    public void renameOnBind(@Nullable PendingRename rename) {
        this.pendingRename = rename;
    }

    /** The last answer to a picker search (client); empty until one arrives. */
    public Optional<TwinResultsPayload> twinResults() {
        return Optional.ofNullable(twinResults);
    }

    /** The last answer to the Twin tab's preset search (client, M3-17); empty until one arrives. */
    public Optional<TwinResultsPayload> presetResults() {
        return Optional.ofNullable(presetResults);
    }

    /** Client: the server answered a search: the picker's, or the Twin tab's preset search (M3-17), by its kind. */
    public void setTwinResults(TwinResultsPayload results) {
        if (results.kind() == TwinPicker.SearchKind.PRESET) {
            this.presetResults = results;
        } else {
            this.twinResults = results;
        }
    }

    /**
     * How long ago, on this client's clock, the current view arrived (client): a message's age in the view plus this is
     * its age now, whatever the server's clock says.
     */
    public long viewAgeMillis() {
        return Math.max(0, Util.getMillis() - viewArrived);
    }

    @Override
    public void setDeviceView(DeviceView view) {
        super.setDeviceView(view);
        viewArrived = Util.getMillis();
        if (refusal != null && !refusal.answers(view)) refusal = null;
    }

    @Override
    public boolean isFor(BlockEntity blockEntity) {
        return device == blockEntity;
    }

    /**
     * Valid while the device is where it was and the player reaches it, from afar while they hold a Configurator
     * ({@link C2SGuard#reaches}), with vanilla's slack for a container screen.
     */
    @Override
    public boolean stillValid(Player player) {
        Level level = device.getLevel();
        return level != null && level.getBlockEntity(device.getBlockPos()) == device && C2SGuard.reaches(player, level, device.getBlockPos(), SCREEN_SLACK);
    }
}

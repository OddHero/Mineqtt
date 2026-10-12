package art.rehra.mineqtt.network;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.blocks.entities.TerminalBlockEntity;
import art.rehra.mineqtt.device.DeviceConfig;
import art.rehra.mineqtt.device.DeviceGroups;
import art.rehra.mineqtt.device.DeviceLinks;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.GroupRules;
import art.rehra.mineqtt.device.TwinView;
import art.rehra.mineqtt.flow.core.Mapping;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.items.ConfiguratorGroup;
import art.rehra.mineqtt.items.ConfiguratorLink;
import art.rehra.mineqtt.items.ConfiguratorRoom;
import art.rehra.mineqtt.mqtt.CyberdeckSessionManager;
import art.rehra.mineqtt.rooms.RoomOffer;
import art.rehra.mineqtt.rooms.RoomRules;
import art.rehra.mineqtt.security.C2SGuard;
import art.rehra.mineqtt.ui.CyberdeckMenu;
import art.rehra.mineqtt.ui.console.BridgeAction;
import art.rehra.mineqtt.ui.console.BridgeConsole;
import art.rehra.mineqtt.ui.console.ConsoleMenu;
import art.rehra.mineqtt.ui.console.ConsoleView;
import art.rehra.mineqtt.ui.device.DeviceMenu;
import art.rehra.mineqtt.ui.device.TwinPicker;
import art.rehra.mineqtt.presence.PrivacyCommands;
import art.rehra.mineqtt.ui.explorer.DevicesTab;
import art.rehra.mineqtt.ui.explorer.ExplorerActions;
import art.rehra.mineqtt.ui.explorer.ExplorerDirectory;
import art.rehra.mineqtt.ui.explorer.ExplorerPage;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedScreenHost;
import dev.architectury.networking.NetworkManager;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

public final class MineqttNetworking {
    public static final Identifier CYBERDECK_PUBLISH_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "cyberdeck_publish");
    public static final Identifier CYBERDECK_LISTEN_TOGGLE_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "cyberdeck_listen_toggle");
    public static final Identifier CYBERDECK_TOPIC_UPDATE_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "cyberdeck_topic_update");
    public static final Identifier SET_ACTIVE_TAB_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "set_active_tab");
    public static final Identifier SET_PRIVATE_MODE_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "set_private_mode");
    public static final Identifier DEVICE_STATUS_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "device_status");
    public static final Identifier DEVICE_CONFIG_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "device_config");
    public static final Identifier DEVICE_ACTION_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "device_action");
    public static final Identifier DEVICE_VIEW_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "device_view");
    public static final Identifier DEVICE_REFUSED_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "device_refused");
    public static final Identifier DEVICE_CLAIM_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "device_claim");
    public static final Identifier DEVICE_RETRY_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "device_retry");
    public static final Identifier DEVICE_EJECT_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "device_eject");
    public static final Identifier DEVICE_LINKS_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "device_links");
    public static final Identifier DEVICE_UNLINK_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "device_unlink");
    public static final Identifier LINK_PICKER_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "link_picker");
    public static final Identifier LINK_CHOICE_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "link_choice");
    public static final Identifier DEVICE_GROUPS_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "device_groups");
    public static final Identifier DEVICE_GROUP_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "device_group");
    public static final Identifier GROUP_PROMPT_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "group_prompt");
    public static final Identifier GROUP_NAME_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "group_name");
    public static final Identifier CONSOLE_VIEW_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "console_view");
    public static final Identifier BRIDGE_ACTION_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "bridge_action");
    public static final Identifier TWIN_VIEW_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "twin_view");
    public static final Identifier TWIN_SEARCH_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "twin_search");
    public static final Identifier TWIN_RESULTS_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "twin_results");
    public static final Identifier ROOM_PROMPT_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "room_prompt");
    public static final Identifier ROOM_NAME_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "room_name");
    public static final Identifier EXPLORER_QUERY_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "explorer_query");
    public static final Identifier EXPLORER_PAGE_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "explorer_page");
    public static final Identifier EXPLORER_ACTION_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "explorer_action");
    public static final Identifier PRIVACY_CHOICE_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "privacy_choice");

    @Deprecated
    public static final Identifier CYBERDECK_PUBLISH = CYBERDECK_PUBLISH_ID;
    @Deprecated
    public static final Identifier CYBERDECK_LISTEN_TOGGLE = CYBERDECK_LISTEN_TOGGLE_ID;
    @Deprecated
    public static final Identifier CYBERDECK_TOPIC_UPDATE = CYBERDECK_TOPIC_UPDATE_ID;

    private MineqttNetworking() {
    }

    /**
     * Registers the payloads. Every client-to-server payload is handled on the server thread and passes
     * {@link C2SGuard} first; the client is never trusted (design §8.5).
     */
    public static void init() {
        // Explorer manual publish (topic, payload): full topic ACL, rate-limited, never retained.
        receive(CyberdeckPublishPayload.TYPE, CyberdeckPublishPayload.CODEC, (player, payload) ->
                C2SGuard.requireCyberdeck(player, payload).ifPresent(menu ->
                        CyberdeckSessionManager.publish(player, payload.topic(), payload.payload())));

        // Explorer listening on/off; it resumes when the player joins again while the server runs.
        receive(CyberdeckListenTogglePayload.TYPE, CyberdeckListenTogglePayload.CODEC, (player, payload) ->
                C2SGuard.requireCyberdeck(player, payload).ifPresent(menu -> {
                    CyberdeckSessionManager.toggleListening(player, payload.listening());
                    menu.setListening(payload.listening());
                    MineQTT.LOGGER.debug("[Cyberdeck] Player {} is now {}listening", player.getGameProfile().name(), payload.listening() ? "" : "not ");
                }));

        // Remember the last tab opened in a tabbed screen; only tabs the screen has are accepted.
        receive(SetActiveTabPayload.TYPE, SetActiveTabPayload.CODEC, (player, payload) -> {
            if (payload.pos().isPresent()) {
                C2SGuard.require(player, payload, payload.pos().get(), TabbedScreenHost.class, MineqttPermission.USE).ifPresent(host -> {
                    TabbedMqttMenu menu = (TabbedMqttMenu) player.containerMenu;
                    if (menu.tabs.stream().noneMatch(tab -> tab.id().equals(payload.tabId()))) return;
                    menu.setActiveTab(payload.tabId());
                    host.setLastTabId(payload.tabId());
                });
            } else {
                C2SGuard.requireCyberdeck(player, payload).ifPresent(menu -> menu.setActiveTab(payload.tabId()));
            }
        });

        // Private mode (topic prefixed with the owner's username) of a block that is no device, or of the explorer.
        // A device's private channel is a setting of its Raw Link (DeviceConfigPayload, raw.private).
        receive(SetPrivateModePayload.TYPE, SetPrivateModePayload.CODEC, (player, payload) -> {
            if (payload.pos().isPresent()) {
                C2SGuard.require(player, payload, payload.pos().get(), BaseMqttBlockEntity.class, MineqttPermission.CONFIGURE).ifPresent(mqtt -> {
                    // A block without a recorded owner (world generation, pre-feature blocks) becomes the player's.
                    if (mqtt.getOwnerName().isEmpty()) mqtt.setOwner(player.getUUID(), player.getGameProfile().name());
                    mqtt.setPrivateMode(payload.enabled());
                });
            } else {
                C2SGuard.requireCyberdeck(player, payload).ifPresent(menu -> {
                    CyberdeckSessionManager.setPrivate(player, payload.enabled());
                    menu.setPrivateMode(payload.enabled());
                });
            }
        });

        // A setting of a device's type or link (M2-6), or a command to one of its writable fields (M2-9): USE for a command,
        // CONFIGURE for the rest, then the key's own gate and bounds (DeviceConfig).
        receive(DeviceConfigPayload.TYPE, DeviceConfigPayload.CODEC, (player, payload) ->
                C2SGuard.requireDevice(player, payload, payload.pos(), type -> DeviceConfig.permission(type, payload.key()))
                        .ifPresent(device -> DeviceRuntime.configure(player, device, payload.key(), payload.value())));

        // An action the device's type declares (M2-6): USE.
        receive(DeviceActionPayload.TYPE, DeviceActionPayload.CODEC, (player, payload) ->
                C2SGuard.requireDevice(player, payload, payload.pos(), MineqttPermission.USE)
                        .ifPresent(device -> DeviceRuntime.action(player, device, payload.action(), payload.value())));

        // A player claims a device without an owner (M2-11): CONFIGURE, then the device must have no owner.
        receive(DeviceClaimPayload.TYPE, DeviceClaimPayload.CODEC, (player, payload) ->
                C2SGuard.requireDevice(player, payload, payload.pos(), MineqttPermission.CONFIGURE)
                        .ifPresent(device -> DeviceRuntime.claim(player, device)));

        // A player retries a faulted device (M2-11), as /mineqtt devices retry does: CONFIGURE.
        receive(DeviceRetryPayload.TYPE, DeviceRetryPayload.CODEC, (player, payload) ->
                C2SGuard.requireDevice(player, payload, payload.pos(), MineqttPermission.CONFIGURE)
                        .ifPresent(device -> DeviceRuntime.retry(player, device)));

        // A player takes the items of a device's old configuration slots (M2-12), as /mineqtt devices eject does: CONFIGURE,
        // as moving those slots' items needed.
        receive(DeviceEjectPayload.TYPE, DeviceEjectPayload.CODEC, (player, payload) ->
                C2SGuard.requireDevice(player, payload, payload.pos(), MineqttPermission.CONFIGURE)
                        .ifPresent(device -> DeviceRuntime.eject(player, device)));

        // A player removes one of the links of the device whose screen they have open (M2-16): CONFIGURE on that device,
        // then the wire must start or end there or end at its group.
        receive(DeviceUnlinkPayload.TYPE, DeviceUnlinkPayload.CODEC, (player, payload) ->
                C2SGuard.requireDevice(player, payload, payload.pos(), MineqttPermission.CONFIGURE)
                        .ifPresent(device -> DeviceRuntime.unlink(player, device, payload.wire())));

        // A wire picked in the Configurator's Link mode picker (M2-16): it must answer the player's open offer; the guard
        // and the link rules run in ConfiguratorLink, which knows the offer.
        receive(LinkChoicePayload.TYPE, LinkChoicePayload.CODEC, ConfiguratorLink::choose);

        // A change to the group of the device whose screen the player has open (M2-17): CONFIGURE on that device, then the
        // group rules (CONFIGURE on every member to join or rename a group, mayExpose to show its members).
        receive(DeviceGroupPayload.TYPE, DeviceGroupPayload.CODEC, (player, payload) ->
                C2SGuard.requireDevice(player, payload, payload.pos(), MineqttPermission.CONFIGURE)
                        .ifPresent(device -> DeviceRuntime.group(player, device, payload.change())));

        // The name of a new group, asked by the Configurator's Group mode (M2-17): it must answer the player's open prompt;
        // the guard runs in ConfiguratorGroup, which knows the prompt.
        receive(GroupNamePayload.TYPE, GroupNamePayload.CODEC, ConfiguratorGroup::named);

        // The answer to the Configurator's Room mode prompt (M4 plan D11): it must answer the player's open offer; the guard,
        // the room rules and the build rights run in ConfiguratorRoom, which knows the offer, as for the room commands.
        receive(RoomNamePayload.TYPE, RoomNamePayload.CODEC, ConfiguratorRoom::named);

        // An action from the Bridge Console's screen (M2-23): CONFIGURE on the console, which only operators hold, then the
        // command the action stands for.
        receive(BridgeActionPayload.TYPE, BridgeActionPayload.CODEC, (player, payload) ->
                C2SGuard.require(player, payload, payload.pos(), TerminalBlockEntity.class, MineqttPermission.CONFIGURE)
                        .ifPresent(terminal -> BridgeConsole.act(player, payload.action())));

        // A search of the Twin tab's entity picker (M3 plan D4): CONFIGURE on the device whose screen the player has open,
        // then the twin rights, the search rate and the read gate (DeviceRuntime.twinSearch); only a binder gets rows.
        receive(TwinSearchPayload.TYPE, TwinSearchPayload.CODEC, (player, payload) ->
                C2SGuard.requireDevice(player, payload, payload.pos(), MineqttPermission.CONFIGURE)
                        .ifPresent(device -> DeviceRuntime.twinSearch(player, device, payload)));

        // A search of the Configurator explorer's Devices tab (M4 plan D13): the explorer must be open, then the search
        // rate and the scope (ExplorerDirectory): only devices the player owns and may configure, all for an operator.
        receive(ExplorerQueryPayload.TYPE, ExplorerQueryPayload.CODEC, (player, payload) ->
                C2SGuard.requireCyberdeck(player, payload).ifPresent(menu -> ExplorerDirectory.query(player, menu, payload)));

        // An action of the Devices tab on one device (M4 plan D13): the explorer must be open, then the pace of its actions,
        // and afresh whether the player finds the device (ExplorerActions): its use, locate or open.
        receive(ExplorerActionPayload.TYPE, ExplorerActionPayload.CODEC, (player, payload) ->
                C2SGuard.requireCyberdeck(player, payload).ifPresent(menu -> ExplorerActions.act(player, menu, payload)));

        // A choice of the Devices tab's You row (M4 plan D9): the explorer must be open, then the pace of its actions, then
        // the code path of /mineqtt privacy share for the player who sent it.
        receive(PrivacyChoicePayload.TYPE, PrivacyChoicePayload.CODEC, (player, payload) ->
                C2SGuard.requireCyberdeck(player, payload).ifPresent(menu -> ExplorerActions.choose(player, menu, payload)));

        // S2C: Cyberdeck topic update (topic, payload)
        // Architectury 17+ on Fabric requires the codec to be registered for S2C on the server
        // so that sendToPlayer can find it. registerReceiver with Side.S2C on the server
        // is NOT supported on Fabric (throws AbstractMethodError).
        // We use registerS2CPayloadType on the server (which is safe on all loaders)
        // and registerReceiver only on the client.
        if (dev.architectury.platform.Platform.getEnv() == net.fabricmc.api.EnvType.CLIENT) {
            NetworkManager.registerReceiver(NetworkManager.Side.S2C, CyberdeckTopicUpdatePayload.TYPE, CyberdeckTopicUpdatePayload.CODEC, ClientPacketHandler::handleTopicUpdate);
            NetworkManager.registerReceiver(NetworkManager.Side.S2C, DeviceStatusPayload.TYPE, DeviceStatusPayload.CODEC, ClientPacketHandler::handleDeviceStatus);
            NetworkManager.registerReceiver(NetworkManager.Side.S2C, DeviceViewPayload.TYPE, DeviceViewPayload.CODEC, ClientPacketHandler::handleDeviceView);
            NetworkManager.registerReceiver(NetworkManager.Side.S2C, DeviceRefusedPayload.TYPE, DeviceRefusedPayload.CODEC, ClientPacketHandler::handleDeviceRefused);
            NetworkManager.registerReceiver(NetworkManager.Side.S2C, DeviceLinksPayload.TYPE, DeviceLinksPayload.CODEC, ClientPacketHandler::handleDeviceLinks);
            NetworkManager.registerReceiver(NetworkManager.Side.S2C, LinkPickerPayload.TYPE, LinkPickerPayload.CODEC, ClientPacketHandler::handleLinkPicker);
            NetworkManager.registerReceiver(NetworkManager.Side.S2C, DeviceGroupsPayload.TYPE, DeviceGroupsPayload.CODEC, ClientPacketHandler::handleDeviceGroups);
            NetworkManager.registerReceiver(NetworkManager.Side.S2C, GroupPromptPayload.TYPE, GroupPromptPayload.CODEC, ClientPacketHandler::handleGroupPrompt);
            NetworkManager.registerReceiver(NetworkManager.Side.S2C, ConsoleViewPayload.TYPE, ConsoleViewPayload.CODEC, ClientPacketHandler::handleConsoleView);
            NetworkManager.registerReceiver(NetworkManager.Side.S2C, TwinViewPayload.TYPE, TwinViewPayload.CODEC, ClientPacketHandler::handleTwinView);
            NetworkManager.registerReceiver(NetworkManager.Side.S2C, TwinResultsPayload.TYPE, TwinResultsPayload.CODEC, ClientPacketHandler::handleTwinResults);
            NetworkManager.registerReceiver(NetworkManager.Side.S2C, RoomPromptPayload.TYPE, RoomPromptPayload.CODEC, ClientPacketHandler::handleRoomPrompt);
            NetworkManager.registerReceiver(NetworkManager.Side.S2C, ExplorerPagePayload.TYPE, ExplorerPagePayload.CODEC, ClientPacketHandler::handleExplorerPage);
        } else {
            NetworkManager.registerS2CPayloadType(CyberdeckTopicUpdatePayload.TYPE, CyberdeckTopicUpdatePayload.CODEC);
            NetworkManager.registerS2CPayloadType(DeviceStatusPayload.TYPE, DeviceStatusPayload.CODEC);
            NetworkManager.registerS2CPayloadType(DeviceViewPayload.TYPE, DeviceViewPayload.CODEC);
            NetworkManager.registerS2CPayloadType(DeviceRefusedPayload.TYPE, DeviceRefusedPayload.CODEC);
            NetworkManager.registerS2CPayloadType(DeviceLinksPayload.TYPE, DeviceLinksPayload.CODEC);
            NetworkManager.registerS2CPayloadType(LinkPickerPayload.TYPE, LinkPickerPayload.CODEC);
            NetworkManager.registerS2CPayloadType(DeviceGroupsPayload.TYPE, DeviceGroupsPayload.CODEC);
            NetworkManager.registerS2CPayloadType(GroupPromptPayload.TYPE, GroupPromptPayload.CODEC);
            NetworkManager.registerS2CPayloadType(ConsoleViewPayload.TYPE, ConsoleViewPayload.CODEC);
            NetworkManager.registerS2CPayloadType(TwinViewPayload.TYPE, TwinViewPayload.CODEC);
            NetworkManager.registerS2CPayloadType(TwinResultsPayload.TYPE, TwinResultsPayload.CODEC);
            NetworkManager.registerS2CPayloadType(RoomPromptPayload.TYPE, RoomPromptPayload.CODEC);
            NetworkManager.registerS2CPayloadType(ExplorerPagePayload.TYPE, ExplorerPagePayload.CODEC);
        }
    }

    /**
     * Refreshes the status line of every open screen of a device block entity (M2 plan D1): only players whose open menu
     * is that block entity's get it, and of those not the ones who see its Controls tab alone (P2), as the status line and
     * the problem name its topics.
     *
     * @param problem why the device's Raw Link does nothing, or null while it works
     */
    public static void sendDeviceStatus(BlockEntity blockEntity, String status, @Nullable String problem) {
        sendDeviceStatus(blockEntity, player -> status, problem);
    }

    /**
     * {@link #sendDeviceStatus(BlockEntity, String, String)} with a status line per viewer: a device's twin names its
     * entities only to those who may configure it (M3 plan D13 #7).
     */
    public static void sendDeviceStatus(BlockEntity blockEntity, Function<ServerPlayer, String> status, @Nullable String problem) {
        for (ServerPlayer player : viewers(blockEntity)) {
            if (DeviceMenu.controlsOnly(player)) continue;
            NetworkManager.sendToPlayer(player, DeviceStatusPayload.of(blockEntity.getBlockPos(), status.apply(player), problem));
        }
    }

    /**
     * Sends a player with a device's screen open ({@link #viewers}) the device's new view (M2-6), built for them: what a
     * player may change differs. The first view comes with the screen's open data (M2-7).
     */
    public static void sendDeviceView(ServerPlayer player, DeviceView view) {
        NetworkManager.sendToPlayer(player, new DeviceViewPayload(view));
    }

    /**
     * Tells a player at a device's screen that the server refused a change they sent from it, and why (M2-10): the screen
     * shows the reason where the player made the change, which the chat line under the screen does not.
     */
    public static void sendDeviceRefused(ServerPlayer player, BlockPos pos, String key, String reason) {
        NetworkManager.sendToPlayer(player, DeviceRefusedPayload.of(pos, key, reason));
    }

    /**
     * Sends a player with a device's screen open the device's links (M2-16): when the screen opens and whenever one of
     * them changed. A screen with the Controls tab alone (P2) gets none.
     */
    public static void sendDeviceLinks(ServerPlayer player, DeviceLinks links) {
        if (DeviceMenu.controlsOnly(player)) return;
        NetworkManager.sendToPlayer(player, new DeviceLinksPayload(links));
    }

    /** Opens the Configurator's Link mode port picker for a player (M2-16), with the wires the server offers them. */
    public static void sendLinkPicker(ServerPlayer player, LinkPickerPayload picker) {
        NetworkManager.sendToPlayer(player, picker);
    }

    /**
     * Sends a player with a device's screen open the device's group page (M2-17): when the screen opens and whenever a
     * group changed. A screen with the Controls tab alone (P2) gets none.
     */
    public static void sendDeviceGroups(ServerPlayer player, DeviceGroups groups) {
        if (DeviceMenu.controlsOnly(player)) return;
        NetworkManager.sendToPlayer(player, new DeviceGroupsPayload(groups));
    }

    /** Asks a player in the Configurator's Group mode for the name of the group their two devices make (M2-17). */
    public static void sendGroupPrompt(ServerPlayer player, GroupPromptPayload prompt) {
        NetworkManager.sendToPlayer(player, prompt);
    }

    /** Asks a player in the Configurator's Room mode to name the room of their two corners, or to rename or remove one (M4 plan D11). */
    public static void sendRoomPrompt(ServerPlayer player, RoomPromptPayload prompt) {
        NetworkManager.sendToPlayer(player, prompt);
    }

    /** Sends a player with a Bridge Console's screen open their view of it. */
    public static void sendConsoleView(ServerPlayer player, ConsoleView view) {
        NetworkManager.sendToPlayer(player, new ConsoleViewPayload(view));
    }

    /**
     * Sends a player with a device's screen open what its Twin tab shows them (M3 plan D19): when the screen opens and with
     * every new view. A screen with the Controls tab alone (P2) gets none.
     */
    public static void sendTwinView(ServerPlayer player, TwinView view) {
        if (DeviceMenu.controlsOnly(player)) return;
        NetworkManager.sendToPlayer(player, new TwinViewPayload(view));
    }

    /** Answers a player's entity picker search ({@link TwinSearchPayload}). */
    public static void sendTwinResults(ServerPlayer player, TwinResultsPayload results) {
        NetworkManager.sendToPlayer(player, results);
    }

    /** Answers a search of a player's Devices tab ({@link ExplorerQueryPayload}). */
    public static void sendExplorerPage(ServerPlayer player, ExplorerPage page) {
        NetworkManager.sendToPlayer(player, new ExplorerPagePayload(page));
    }

    /** The players whose open menu is the block entity's. */
    public static List<ServerPlayer> viewers(BlockEntity blockEntity) {
        if (!(blockEntity.getLevel() instanceof ServerLevel level)) return List.of();
        return level.getServer().getPlayerList().getPlayers().stream()
                .filter(player -> player.containerMenu instanceof TabbedMqttMenu menu && menu.isFor(blockEntity)).toList();
    }

    /** A client-to-server payload, handled on the server thread for a player of this server. */
    private static <T extends CustomPacketPayload> void receive(CustomPacketPayload.Type<T> type, StreamCodec<RegistryFriendlyByteBuf, T> codec,
                                                                BiConsumer<ServerPlayer, T> handler) {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, type, codec, (payload, context) -> context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer player) handler.accept(player, payload);
        }));
    }

    public record CyberdeckPublishPayload(String topic, String payload) implements CustomPacketPayload {
        public static final Type<CyberdeckPublishPayload> TYPE = new Type<>(CYBERDECK_PUBLISH_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, CyberdeckPublishPayload> CODEC = StreamCodec.composite(
                net.minecraft.network.codec.ByteBufCodecs.stringUtf8(512), CyberdeckPublishPayload::topic,
                net.minecraft.network.codec.ByteBufCodecs.stringUtf8(2048), CyberdeckPublishPayload::payload,
                CyberdeckPublishPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record CyberdeckListenTogglePayload(boolean listening) implements CustomPacketPayload {
        public static final Type<CyberdeckListenTogglePayload> TYPE = new Type<>(CYBERDECK_LISTEN_TOGGLE_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, CyberdeckListenTogglePayload> CODEC = StreamCodec.composite(
                net.minecraft.network.codec.ByteBufCodecs.BOOL, CyberdeckListenTogglePayload::listening,
                CyberdeckListenTogglePayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record CyberdeckTopicUpdatePayload(String topic, String payload) implements CustomPacketPayload {
        /** Longer topics cannot be sent; the server skips them. */
        public static final int MAX_TOPIC_CHARS = 512;
        /** Longer payloads are cut by the server. */
        public static final int MAX_PAYLOAD_CHARS = 2048;
        public static final Type<CyberdeckTopicUpdatePayload> TYPE = new Type<>(CYBERDECK_TOPIC_UPDATE_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, CyberdeckTopicUpdatePayload> CODEC = StreamCodec.composite(
                net.minecraft.network.codec.ByteBufCodecs.stringUtf8(MAX_TOPIC_CHARS), CyberdeckTopicUpdatePayload::topic,
                net.minecraft.network.codec.ByteBufCodecs.stringUtf8(MAX_PAYLOAD_CHARS), CyberdeckTopicUpdatePayload::payload,
                CyberdeckTopicUpdatePayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * A device's status line and its Raw Link problem for the device's open screen ({@link #sendDeviceStatus}), as the
     * view in {@link TabbedMqttMenu#writeOpenData} carries them when the screen opens.
     *
     * @param problem why the Raw Link does nothing; empty while it works
     */
    public record DeviceStatusPayload(BlockPos pos, String status, Optional<String> problem) implements CustomPacketPayload {
        /** Longer status lines and problems are cut by the server, so the payload always encodes. */
        public static final int MAX_STATUS_CHARS = 512;
        public static final Type<DeviceStatusPayload> TYPE = new Type<>(DEVICE_STATUS_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, DeviceStatusPayload> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, DeviceStatusPayload::pos,
                ByteBufCodecs.stringUtf8(MAX_STATUS_CHARS), DeviceStatusPayload::status,
                ByteBufCodecs.optional(ByteBufCodecs.stringUtf8(MAX_STATUS_CHARS)), DeviceStatusPayload::problem,
                DeviceStatusPayload::new
        );

        /** The payload with both texts cut to {@link #MAX_STATUS_CHARS}. */
        public static DeviceStatusPayload of(BlockPos pos, String status, @Nullable String problem) {
            return new DeviceStatusPayload(pos, cap(status), Optional.ofNullable(problem).map(DeviceStatusPayload::cap));
        }

        /** The text, or its start with an ellipsis when it is longer than {@link #MAX_STATUS_CHARS}; never splits a surrogate pair. */
        public static String cap(String text) {
            if (text.length() <= MAX_STATUS_CHARS) return text;
            int end = MAX_STATUS_CHARS - 1;
            if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
            return text.substring(0, end) + "…";
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * A player changes one setting of the device whose screen they have open (M2-6): a setting of its type, one of its
     * link such as {@code name} or {@code raw.topic}, or the value of one of its writable fields, as the Controls tab's
     * level slider and light picker send it (M2-9). The server checks the key, the player's rights and the value's kind
     * and bounds ({@code DeviceConfig}); the value is NBT within {@link C2SGuard#valueQuota()}.
     */
    public record DeviceConfigPayload(BlockPos pos, String key, Tag value) implements CustomPacketPayload {
        public static final int MAX_KEY_CHARS = 64;
        public static final Type<DeviceConfigPayload> TYPE = new Type<>(DEVICE_CONFIG_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, DeviceConfigPayload> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, DeviceConfigPayload::pos,
                ByteBufCodecs.stringUtf8(MAX_KEY_CHARS), DeviceConfigPayload::key,
                ByteBufCodecs.tagCodec(C2SGuard::valueQuota), DeviceConfigPayload::value,
                DeviceConfigPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * A player performs an action of the device whose screen they have open (M2-6): only one its type declares, with a
     * value of at most {@link C2SGuard#MAX_PAYLOAD_BYTES} bytes ({@code ON}, {@code TOGGLE}, {@code PRESS}, …).
     */
    public record DeviceActionPayload(BlockPos pos, String action, String value) implements CustomPacketPayload {
        public static final int MAX_ACTION_CHARS = 64;
        public static final Type<DeviceActionPayload> TYPE = new Type<>(DEVICE_ACTION_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, DeviceActionPayload> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, DeviceActionPayload::pos,
                ByteBufCodecs.stringUtf8(MAX_ACTION_CHARS), DeviceActionPayload::action,
                ByteBufCodecs.stringUtf8(C2SGuard.MAX_PAYLOAD_BYTES), DeviceActionPayload::value,
                DeviceActionPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * A player claims the device whose screen they have open (M2-11): one without an owner becomes theirs. Not a
     * setting: it changes who owns the device, which only the server knows the player as.
     */
    public record DeviceClaimPayload(BlockPos pos) implements CustomPacketPayload {
        public static final Type<DeviceClaimPayload> TYPE = new Type<>(DEVICE_CLAIM_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, DeviceClaimPayload> CODEC = BlockPos.STREAM_CODEC.<RegistryFriendlyByteBuf>cast()
                .map(DeviceClaimPayload::new, DeviceClaimPayload::pos);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * A player retries the faulted device whose screen they have open (M2-11, M2 plan D7): the screen's
     * {@code /mineqtt devices retry}. Not a setting or an action: a faulted device does not run.
     */
    public record DeviceRetryPayload(BlockPos pos) implements CustomPacketPayload {
        public static final Type<DeviceRetryPayload> TYPE = new Type<>(DEVICE_RETRY_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, DeviceRetryPayload> CODEC = BlockPos.STREAM_CODEC.<RegistryFriendlyByteBuf>cast()
                .map(DeviceRetryPayload::new, DeviceRetryPayload::pos);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * A player takes the items the old configuration slots of the device whose screen they have open held (M2-12): the
     * screen's {@code /mineqtt devices eject}. Not a setting or an action: the items are the block's, not the device's.
     */
    public record DeviceEjectPayload(BlockPos pos) implements CustomPacketPayload {
        public static final Type<DeviceEjectPayload> TYPE = new Type<>(DEVICE_EJECT_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, DeviceEjectPayload> CODEC = BlockPos.STREAM_CODEC.<RegistryFriendlyByteBuf>cast()
                .map(DeviceEjectPayload::new, DeviceEjectPayload::pos);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A device's links for the players with its screen open ({@link #sendDeviceLinks}). */
    public record DeviceLinksPayload(DeviceLinks links) implements CustomPacketPayload {
        public static final Type<DeviceLinksPayload> TYPE = new Type<>(DEVICE_LINKS_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, DeviceLinksPayload> CODEC = DeviceLinks.STREAM_CODEC.<RegistryFriendlyByteBuf>cast()
                .map(DeviceLinksPayload::new, DeviceLinksPayload::links);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * A player removes a link of the device whose screen they have open (M2-16): the wire, which must start or end at that
     * device or end at its group.
     */
    public record DeviceUnlinkPayload(BlockPos pos, UUID wire) implements CustomPacketPayload {
        public static final Type<DeviceUnlinkPayload> TYPE = new Type<>(DEVICE_UNLINK_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, DeviceUnlinkPayload> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, DeviceUnlinkPayload::pos,
                UUIDUtil.STREAM_CODEC, DeviceUnlinkPayload::wire,
                DeviceUnlinkPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * The Configurator's Link mode port picker (M2-16): the wires the server offers from the device a player clicked first
     * to the one they clicked second, the default first. The player's {@link LinkChoicePayload} names one by its index.
     *
     * @param offer   the offer this answers, which the choice repeats
     * @param source  the name of the device clicked first
     * @param target  the name of the device clicked second
     * @param choices at most {@value #MAX_CHOICES}
     */
    public record LinkPickerPayload(int offer, String source, String target, List<Choice> choices) implements CustomPacketPayload {
        public static final int MAX_CHOICES = 128;
        // A port's kind as it reads, such as "enum previous|off|on"; a longer one is cut.
        private static final int MAX_KIND_CHARS = 128;
        public static final Type<LinkPickerPayload> TYPE = new Type<>(LINK_PICKER_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, LinkPickerPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, LinkPickerPayload::offer,
                ByteBufCodecs.stringUtf8(DeviceLinks.MAX_LABEL_BYTES), LinkPickerPayload::source,
                ByteBufCodecs.stringUtf8(DeviceLinks.MAX_LABEL_BYTES), LinkPickerPayload::target,
                ByteBufCodecs.collection(ArrayList::new, Choice.STREAM_CODEC.<RegistryFriendlyByteBuf>cast(), MAX_CHOICES), LinkPickerPayload::choices,
                LinkPickerPayload::new);

        public LinkPickerPayload {
            source = DeviceView.cap(source, DeviceLinks.MAX_LABEL_BYTES);
            target = DeviceView.cap(target, DeviceLinks.MAX_LABEL_BYTES);
            choices = List.copyOf(choices);
        }

        /**
         * A wire on offer: from the source's out-port {@code from} to the in-port {@code to} of the target, or of the
         * target's group when {@code group} names it.
         *
         * @param mappings  the mappings that can join the ports, the one to offer first at the front
         * @param wired     whether these ports are wired already, so the pick removes the wire
         * @param isDefault whether it is the pair the two devices most likely mean ({@code DefaultWiring})
         */
        public record Choice(String from, String fromKind, String to, String toKind, String group, List<Mapping> mappings, boolean wired,
                             boolean isDefault) {
            public static final StreamCodec<io.netty.buffer.ByteBuf, Choice> STREAM_CODEC = StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(DeviceLinks.MAX_PORT_CHARS), Choice::from,
                    ByteBufCodecs.stringUtf8(MAX_KIND_CHARS), Choice::fromKind,
                    ByteBufCodecs.stringUtf8(DeviceLinks.MAX_PORT_CHARS), Choice::to,
                    ByteBufCodecs.stringUtf8(MAX_KIND_CHARS), Choice::toKind,
                    ByteBufCodecs.stringUtf8(DeviceLinks.MAX_LABEL_BYTES), Choice::group,
                    ByteBufCodecs.collection(ArrayList::new, DeviceLinks.MAPPING_CODEC, Mapping.values().length), Choice::mappings,
                    ByteBufCodecs.BOOL, Choice::wired,
                    ByteBufCodecs.BOOL, Choice::isDefault,
                    Choice::new);

            /** A choice with its texts cut to what the stream codec carries. */
            public static Choice of(String from, String fromKind, String to, String toKind, String group, List<Mapping> mappings, boolean wired,
                                    boolean isDefault) {
                return new Choice(DeviceView.cap(from, DeviceLinks.MAX_PORT_CHARS), DeviceView.cap(fromKind, MAX_KIND_CHARS),
                        DeviceView.cap(to, DeviceLinks.MAX_PORT_CHARS), DeviceView.cap(toKind, MAX_KIND_CHARS),
                        DeviceView.cap(group, DeviceLinks.MAX_LABEL_BYTES), List.copyOf(mappings), wired, isDefault);
            }
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * A wire a player picked in the Link mode picker ({@link LinkPickerPayload}): the choice by its index in the offer,
     * the mapping (one the choice lists), and whether to remove the wire the choice has instead of making one.
     */
    public record LinkChoicePayload(int offer, int option, Mapping mapping, boolean unlink) implements CustomPacketPayload {
        public static final Type<LinkChoicePayload> TYPE = new Type<>(LINK_CHOICE_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, LinkChoicePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, LinkChoicePayload::offer,
                ByteBufCodecs.VAR_INT, LinkChoicePayload::option,
                DeviceLinks.MAPPING_CODEC.cast(), LinkChoicePayload::mapping,
                ByteBufCodecs.BOOL, LinkChoicePayload::unlink,
                LinkChoicePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A device's group page for one player with its screen open ({@link #sendDeviceGroups}). */
    public record DeviceGroupsPayload(DeviceGroups groups) implements CustomPacketPayload {
        public static final Type<DeviceGroupsPayload> TYPE = new Type<>(DEVICE_GROUPS_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, DeviceGroupsPayload> CODEC = DeviceGroups.STREAM_CODEC.<RegistryFriendlyByteBuf>cast()
                .map(DeviceGroupsPayload::new, DeviceGroupsPayload::groups);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * A player changes the group of the device whose screen they have open (M2-17): joins it to a group, takes it out,
     * makes a new group with it, renames its group or shows or hides that group's members.
     */
    public record DeviceGroupPayload(BlockPos pos, GroupRules.Change change) implements CustomPacketPayload {
        public static final Type<DeviceGroupPayload> TYPE = new Type<>(DEVICE_GROUP_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, DeviceGroupPayload> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, DeviceGroupPayload::pos,
                GroupRules.Change.STREAM_CODEC.cast(), DeviceGroupPayload::change,
                DeviceGroupPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * The Configurator's Group mode asks for the name of a new group (M2-17): the device a player clicked first is in no
     * group, so it and the device they clicked second make one. The player's {@link GroupNamePayload} answers it.
     *
     * @param offer  what the answer names, so a late answer to an earlier prompt does nothing
     * @param source the name of the device clicked first
     * @param target the name of the device clicked second
     */
    public record GroupPromptPayload(int offer, String source, String target) implements CustomPacketPayload {
        public static final Type<GroupPromptPayload> TYPE = new Type<>(GROUP_PROMPT_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, GroupPromptPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, GroupPromptPayload::offer,
                ByteBufCodecs.stringUtf8(DeviceLinks.MAX_LABEL_BYTES), GroupPromptPayload::source,
                ByteBufCodecs.stringUtf8(DeviceLinks.MAX_LABEL_BYTES), GroupPromptPayload::target,
                GroupPromptPayload::new);

        public GroupPromptPayload {
            source = DeviceView.cap(source, DeviceLinks.MAX_LABEL_BYTES);
            target = DeviceView.cap(target, DeviceLinks.MAX_LABEL_BYTES);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The name a player gave the new group of the Group mode prompt {@code offer} ({@link GroupPromptPayload}). */
    public record GroupNamePayload(int offer, String name) implements CustomPacketPayload {
        // Room for a name the server refuses as too long, so the player hears why.
        public static final int MAX_NAME_BYTES = GroupRules.MAX_NAME_LENGTH * 4 + 4;
        public static final Type<GroupNamePayload> TYPE = new Type<>(GROUP_NAME_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, GroupNamePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, GroupNamePayload::offer,
                ByteBufCodecs.stringUtf8(MAX_NAME_BYTES), GroupNamePayload::name,
                GroupNamePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * The Configurator's Room mode asks a player to name a room (M4 plan D11): the box of their two corners, which a name
     * makes a room, or, with an existing name, a room of theirs they picked, which they rename or remove. The player's
     * {@link RoomNamePayload} answers it; the server judges the answer against its own offer and the room rules.
     *
     * @param offer        what the answer names, so a late answer to an earlier prompt does nothing
     * @param sizeX        the box's size in blocks along x, both corners included
     * @param existingName the picked room's name; empty for a box to make a room of
     * @param suggestions  names to offer ({@code ConfiguratorRoom.suggestions}): the rooms the player sees but does not own (rooms
     *                     for everyone, and for an operator every room), without the names of their own rooms, which are taken,
     *                     then up to 16 Home Assistant area names for a player who may bind devices to it; at most
     *                     {@value #MAX_SUGGESTIONS}
     * @param mayForAll    whether the player may make a room for every device in it
     */
    public record RoomPromptPayload(int offer, int sizeX, int sizeY, int sizeZ, String existingName, List<String> suggestions,
                                    boolean mayForAll) implements CustomPacketPayload {
        public static final int MAX_SUGGESTIONS = 32;
        public static final int MAX_NAME_BYTES = RoomRules.MAX_NAME_LENGTH * 4;
        public static final Type<RoomPromptPayload> TYPE = new Type<>(ROOM_PROMPT_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, RoomPromptPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, RoomPromptPayload::offer,
                ByteBufCodecs.VAR_INT, RoomPromptPayload::sizeX,
                ByteBufCodecs.VAR_INT, RoomPromptPayload::sizeY,
                ByteBufCodecs.VAR_INT, RoomPromptPayload::sizeZ,
                ByteBufCodecs.stringUtf8(MAX_NAME_BYTES), RoomPromptPayload::existingName,
                ByteBufCodecs.stringUtf8(MAX_NAME_BYTES).apply(ByteBufCodecs.list(MAX_SUGGESTIONS)), RoomPromptPayload::suggestions,
                ByteBufCodecs.BOOL, RoomPromptPayload::mayForAll,
                RoomPromptPayload::new);

        public RoomPromptPayload {
            if (sizeX < 1 || sizeY < 1 || sizeZ < 1) throw new IllegalArgumentException("A room prompt's box has no size");
            existingName = DeviceView.cap(existingName, MAX_NAME_BYTES);
            suggestions = suggestions.stream().limit(MAX_SUGGESTIONS).map(name -> DeviceView.cap(name, MAX_NAME_BYTES)).toList();
        }

        /** Whether it offers a room to rename or remove rather than a box. */
        public boolean edits() {
            return !existingName.isEmpty();
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * A player's answer to the Room mode prompt {@code offer} ({@link RoomPromptPayload}): make the offered box a room of
     * that name, for every device in it when {@code forAll}; rename the picked room; or remove it.
     */
    public record RoomNamePayload(int offer, RoomOffer.Op op, String name, boolean forAll) implements CustomPacketPayload {
        // Room for a name the server refuses as too long, so the player hears why.
        public static final int MAX_NAME_BYTES = RoomRules.MAX_NAME_LENGTH * 4 + 4;
        private static final StreamCodec<RegistryFriendlyByteBuf, RoomOffer.Op> OP_CODEC = StreamCodec.of(
                (buf, op) -> VarInt.write(buf, op.ordinal()),
                buf -> {
                    int ordinal = VarInt.read(buf);
                    if (ordinal < 0 || ordinal >= RoomOffer.Op.values().length) throw new DecoderException("Unknown room answer " + ordinal);
                    return RoomOffer.Op.values()[ordinal];
                });
        public static final Type<RoomNamePayload> TYPE = new Type<>(ROOM_NAME_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, RoomNamePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, RoomNamePayload::offer,
                OP_CODEC, RoomNamePayload::op,
                ByteBufCodecs.stringUtf8(MAX_NAME_BYTES), RoomNamePayload::name,
                ByteBufCodecs.BOOL, RoomNamePayload::forAll,
                RoomNamePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * A device's view for one player with its screen open ({@link #sendDeviceView}); at most {@link DeviceView#MAX_BYTES}.
     * It carries the type's Controls tab status line (M4 plan D5) of at most {@value art.rehra.mineqtt.device.StatusLine#MAX_LENGTH}
     * characters and the count of throttled events (D3), so no payload of its own is needed for either.
     */
    public record DeviceViewPayload(DeviceView view) implements CustomPacketPayload {
        public static final Type<DeviceViewPayload> TYPE = new Type<>(DEVICE_VIEW_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, DeviceViewPayload> CODEC = DeviceView.STREAM_CODEC.<RegistryFriendlyByteBuf>cast()
                .map(DeviceViewPayload::new, DeviceViewPayload::view);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * A change sent from a device's screen that the server refused ({@link #sendDeviceRefused}).
     *
     * @param key    the setting, field or action the change was for
     * @param reason why, cut to {@link DeviceStatusPayload#MAX_STATUS_CHARS}
     */
    public record DeviceRefusedPayload(BlockPos pos, String key, String reason) implements CustomPacketPayload {
        public static final Type<DeviceRefusedPayload> TYPE = new Type<>(DEVICE_REFUSED_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, DeviceRefusedPayload> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, DeviceRefusedPayload::pos,
                ByteBufCodecs.stringUtf8(DeviceConfigPayload.MAX_KEY_CHARS), DeviceRefusedPayload::key,
                ByteBufCodecs.stringUtf8(DeviceStatusPayload.MAX_STATUS_CHARS), DeviceRefusedPayload::reason,
                DeviceRefusedPayload::new
        );

        /** The payload with the key and the reason cut so it always encodes. */
        public static DeviceRefusedPayload of(BlockPos pos, String key, String reason) {
            String shortKey = key.length() <= DeviceConfigPayload.MAX_KEY_CHARS ? key : key.substring(0, DeviceConfigPayload.MAX_KEY_CHARS);
            return new DeviceRefusedPayload(pos, shortKey, DeviceStatusPayload.cap(reason));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SetActiveTabPayload(java.util.Optional<net.minecraft.core.BlockPos> pos,
                                      String tabId) implements CustomPacketPayload {
        public static final Type<SetActiveTabPayload> TYPE = new Type<>(SET_ACTIVE_TAB_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SetActiveTabPayload> CODEC = StreamCodec.composite(
                net.minecraft.network.codec.ByteBufCodecs.optional(net.minecraft.core.BlockPos.STREAM_CODEC), SetActiveTabPayload::pos,
                net.minecraft.network.codec.ByteBufCodecs.stringUtf8(64), SetActiveTabPayload::tabId,
                SetActiveTabPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SetPrivateModePayload(java.util.Optional<net.minecraft.core.BlockPos> pos,
                                        boolean enabled) implements CustomPacketPayload {
        public static final Type<SetPrivateModePayload> TYPE = new Type<>(SET_PRIVATE_MODE_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SetPrivateModePayload> CODEC = StreamCodec.composite(
                net.minecraft.network.codec.ByteBufCodecs.optional(net.minecraft.core.BlockPos.STREAM_CODEC), SetPrivateModePayload::pos,
                net.minecraft.network.codec.ByteBufCodecs.BOOL, SetPrivateModePayload::enabled,
                SetPrivateModePayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A Bridge Console as one player with its screen open sees it ({@link #sendConsoleView}); at most {@link ConsoleView#MAX_BYTES}. */
    public record ConsoleViewPayload(ConsoleView view) implements CustomPacketPayload {
        public static final Type<ConsoleViewPayload> TYPE = new Type<>(CONSOLE_VIEW_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, ConsoleViewPayload> CODEC = ConsoleView.STREAM_CODEC.<RegistryFriendlyByteBuf>cast()
                .map(ConsoleViewPayload::new, ConsoleViewPayload::view);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * An operator runs one of the Bridge Console's actions from its screen (M2-23). Only the declared actions decode; an
     * unknown one is refused, never read as another.
     */
    public record BridgeActionPayload(BlockPos pos, BridgeAction action) implements CustomPacketPayload {
        public static final Type<BridgeActionPayload> TYPE = new Type<>(BRIDGE_ACTION_ID);
        private static final StreamCodec<RegistryFriendlyByteBuf, BridgeAction> ACTION_CODEC = StreamCodec.of(
                (buf, action) -> VarInt.write(buf, action.ordinal()),
                buf -> {
                    int ordinal = VarInt.read(buf);
                    BridgeAction[] actions = BridgeAction.values();
                    if (ordinal < 0 || ordinal >= actions.length) throw new DecoderException("Unknown Bridge Console action " + ordinal);
                    return actions[ordinal];
                });
        public static final StreamCodec<RegistryFriendlyByteBuf, BridgeActionPayload> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, BridgeActionPayload::pos,
                ACTION_CODEC, BridgeActionPayload::action,
                BridgeActionPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** What a device's Twin tab shows one player with its screen open ({@link #sendTwinView}). */
    public record TwinViewPayload(TwinView view) implements CustomPacketPayload {
        public static final Type<TwinViewPayload> TYPE = new Type<>(TWIN_VIEW_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, TwinViewPayload> CODEC = TwinView.STREAM_CODEC.<RegistryFriendlyByteBuf>cast()
                .map(TwinViewPayload::new, TwinViewPayload::view);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * The Twin tab's entity picker searches (M3 plan D4): for the binding key {@code key} of the device whose screen the
     * player has open, the entities matching {@code query} (at most {@value TwinPicker#MAX_QUERY_LENGTH} characters), of
     * one domain or, with {@code ""}, of every domain the key picks from. The client debounces it to
     * {@value TwinPicker#DEBOUNCE_MILLIS} ms; the server answers with a {@link TwinResultsPayload}. With the kind
     * {@link TwinPicker.SearchKind#PRESET} it asks instead for the MQTT device presets of the "MQTT device" source (M3-17,
     * key {@code twin.device}, {@code domain} a preset kind), the same payload under the same guard.
     */
    public record TwinSearchPayload(BlockPos pos, TwinPicker.SearchKind kind, String key, String query, String domain) implements CustomPacketPayload {
        public static final Type<TwinSearchPayload> TYPE = new Type<>(TWIN_SEARCH_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, TwinSearchPayload> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, TwinSearchPayload::pos,
                TwinPicker.SearchKind.STREAM_CODEC.cast(), TwinSearchPayload::kind,
                ByteBufCodecs.stringUtf8(DeviceConfigPayload.MAX_KEY_CHARS), TwinSearchPayload::key,
                ByteBufCodecs.stringUtf8(TwinPicker.MAX_QUERY_LENGTH), TwinSearchPayload::query,
                TwinPicker.DOMAIN_CODEC.cast(), TwinSearchPayload::domain,
                TwinSearchPayload::new);

        /** An entity search, as the entity picker sends it. */
        public TwinSearchPayload(BlockPos pos, String key, String query, String domain) {
            this(pos, TwinPicker.SearchKind.ENTITY, key, query, domain);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * The answer to a picker search ({@link TwinSearchPayload}): at most {@value TwinPicker#MAX_ROWS} entities the player
     * may bind, best first and grouped by area, and why there are none or what else to know.
     *
     * @param kind   what was searched, repeated: entities, or MQTT device presets (M3-17)
     * @param key    the binding key searched for, repeated
     * @param query  the query answered, repeated, so the picker drops an answer to an older one
     * @param domain the domain filter answered (a chip, {@code ""} for all), repeated, so the picker drops an answer to a
     *               chip it has left
     * @param detail what the notice names: why the twin is off; {@code ""} otherwise
     */
    public record TwinResultsPayload(BlockPos pos, TwinPicker.SearchKind kind, String key, String query, String domain, List<TwinPicker.Row> rows,
                                     TwinPicker.Notice notice, String detail)
            implements CustomPacketPayload {
        public static final Type<TwinResultsPayload> TYPE = new Type<>(TWIN_RESULTS_ID);
        private static final StreamCodec<RegistryFriendlyByteBuf, TwinPicker.Notice> NOTICE_CODEC = StreamCodec.of(
                (buf, notice) -> VarInt.write(buf, notice.ordinal()),
                buf -> {
                    int ordinal = VarInt.read(buf);
                    TwinPicker.Notice[] notices = TwinPicker.Notice.values();
                    if (ordinal < 0 || ordinal >= notices.length) throw new DecoderException("Unknown picker notice " + ordinal);
                    return notices[ordinal];
                });
        public static final StreamCodec<RegistryFriendlyByteBuf, TwinResultsPayload> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, TwinResultsPayload::pos,
                TwinPicker.SearchKind.STREAM_CODEC.cast(), TwinResultsPayload::kind,
                ByteBufCodecs.stringUtf8(DeviceConfigPayload.MAX_KEY_CHARS), TwinResultsPayload::key,
                ByteBufCodecs.stringUtf8(TwinPicker.MAX_QUERY_LENGTH), TwinResultsPayload::query,
                TwinPicker.DOMAIN_CODEC.cast(), TwinResultsPayload::domain,
                TwinPicker.ROWS_CODEC.cast(), TwinResultsPayload::rows,
                NOTICE_CODEC, TwinResultsPayload::notice,
                ByteBufCodecs.stringUtf8(TwinView.MAX_REASON_LENGTH), TwinResultsPayload::detail,
                TwinResultsPayload::new);

        /** The answer with its texts cut to what the codec carries. */
        public TwinResultsPayload {
            rows = List.copyOf(rows);
            if (rows.size() > TwinPicker.MAX_ROWS) throw new IllegalArgumentException("An answer has more than " + TwinPicker.MAX_ROWS + " rows");
            key = key.length() <= DeviceConfigPayload.MAX_KEY_CHARS ? key : key.substring(0, DeviceConfigPayload.MAX_KEY_CHARS);
            query = query.length() <= TwinPicker.MAX_QUERY_LENGTH ? query : query.substring(0, TwinPicker.MAX_QUERY_LENGTH);
            domain = domain.length() <= TwinPicker.MAX_DOMAIN_LENGTH ? domain : domain.substring(0, TwinPicker.MAX_DOMAIN_LENGTH);
            detail = detail.length() <= TwinView.MAX_REASON_LENGTH ? detail : detail.substring(0, TwinView.MAX_REASON_LENGTH);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * A search of the Configurator explorer's Devices tab (M4 plan D13): the devices whose name, slug or model contains
     * {@code query} (at most {@value ExplorerPage#MAX_QUERY_LENGTH} characters), in the room {@code room} ({@code ""} for
     * every room), page {@code page} of {@value ExplorerPage#PAGE_SIZE} rows. The client debounces it to
     * {@value DevicesTab#DEBOUNCE_MILLIS} ms and sends one the server dropped over its rate again; the server answers
     * with an {@link ExplorerPagePayload}.
     */
    public record ExplorerQueryPayload(String query, String room, int page) implements CustomPacketPayload {
        public static final Type<ExplorerQueryPayload> TYPE = new Type<>(EXPLORER_QUERY_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, ExplorerQueryPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(ExplorerPage.MAX_QUERY_LENGTH), ExplorerQueryPayload::query,
                ByteBufCodecs.stringUtf8(ExplorerPage.MAX_ROOM_LENGTH), ExplorerQueryPayload::room,
                ByteBufCodecs.VAR_INT, ExplorerQueryPayload::page,
                ExplorerQueryPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * An action of the Devices tab on the device {@code target} (M4 plan D13): its use, locate or open. The server judges
     * afresh whether the player finds the device ({@code ExplorerActions}); an unknown kind does not decode.
     */
    public record ExplorerActionPayload(UUID target, ExplorerActions.Kind kind) implements CustomPacketPayload {
        private static final StreamCodec<RegistryFriendlyByteBuf, ExplorerActions.Kind> KIND_CODEC = StreamCodec.of(
                (buf, kind) -> VarInt.write(buf, kind.ordinal()),
                buf -> {
                    int ordinal = VarInt.read(buf);
                    if (ordinal < 0 || ordinal >= ExplorerActions.Kind.values().length) throw new DecoderException("Unknown explorer action " + ordinal);
                    return ExplorerActions.Kind.values()[ordinal];
                });
        public static final Type<ExplorerActionPayload> TYPE = new Type<>(EXPLORER_ACTION_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, ExplorerActionPayload> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, ExplorerActionPayload::target,
                KIND_CODEC, ExplorerActionPayload::kind,
                ExplorerActionPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * A choice of the Devices tab's You row (M4 plan D9): what the player shares about themselves, as
     * {@code /mineqtt privacy share on|full|off} chooses it; an unknown choice does not decode.
     */
    public record PrivacyChoicePayload(PrivacyCommands.Choice choice) implements CustomPacketPayload {
        private static final StreamCodec<RegistryFriendlyByteBuf, PrivacyCommands.Choice> CHOICE_CODEC = StreamCodec.of(
                (buf, choice) -> VarInt.write(buf, choice.ordinal()),
                buf -> {
                    int ordinal = VarInt.read(buf);
                    if (ordinal < 0 || ordinal >= PrivacyCommands.Choice.values().length) throw new DecoderException("Unknown privacy choice " + ordinal);
                    return PrivacyCommands.Choice.values()[ordinal];
                });
        public static final Type<PrivacyChoicePayload> TYPE = new Type<>(PRIVACY_CHOICE_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, PrivacyChoicePayload> CODEC = CHOICE_CODEC.map(PrivacyChoicePayload::new, PrivacyChoicePayload::choice);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A page of a player's Devices tab ({@link #sendExplorerPage}); at most {@link ExplorerPage#MAX_BYTES}. */
    public record ExplorerPagePayload(ExplorerPage page) implements CustomPacketPayload {
        public static final Type<ExplorerPagePayload> TYPE = new Type<>(EXPLORER_PAGE_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, ExplorerPagePayload> CODEC = ExplorerPage.STREAM_CODEC.<RegistryFriendlyByteBuf>cast()
                .map(ExplorerPagePayload::new, ExplorerPagePayload::page);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static class ClientPacketHandler {
        public static void handleTopicUpdate(CyberdeckTopicUpdatePayload payload, NetworkManager.PacketContext context) {
            String topic = payload.topic();
            String payloadStr = payload.payload();
            context.queue(() -> {
                art.rehra.mineqtt.ui.CyberdeckScreen.updateTopic(topic, payloadStr);
            });
        }

        // Only the screen of that block takes it; a screen closed or replaced in the meantime ignores it.
        public static void handleDeviceStatus(DeviceStatusPayload payload, NetworkManager.PacketContext context) {
            context.queue(() -> {
                if (context.getPlayer().containerMenu instanceof TabbedMqttMenu menu && payload.pos().equals(menu.blockPos)) {
                    menu.setDeviceStatus(payload.status(), payload.problem().orElse(null));
                }
            });
        }

        // Like the status: only the screen of that device takes it.
        public static void handleDeviceView(DeviceViewPayload payload, NetworkManager.PacketContext context) {
            context.queue(() -> {
                if (context.getPlayer().containerMenu instanceof TabbedMqttMenu menu && payload.view().pos().equals(menu.blockPos)) {
                    menu.setDeviceView(payload.view());
                }
            });
        }

        // Only the screen of that device takes it.
        public static void handleDeviceLinks(DeviceLinksPayload payload, NetworkManager.PacketContext context) {
            context.queue(() -> {
                if (context.getPlayer().containerMenu instanceof DeviceMenu menu && payload.links().pos().equals(menu.blockPos)) {
                    menu.setLinks(payload.links());
                }
            });
        }

        public static void handleLinkPicker(LinkPickerPayload payload, NetworkManager.PacketContext context) {
            context.queue(() -> art.rehra.mineqtt.ui.LinkPickerScreen.open(payload));
        }

        // Only the screen of that device takes it.
        public static void handleDeviceGroups(DeviceGroupsPayload payload, NetworkManager.PacketContext context) {
            context.queue(() -> {
                if (context.getPlayer().containerMenu instanceof DeviceMenu menu && payload.groups().pos().equals(menu.blockPos)) {
                    menu.setGroups(payload.groups());
                }
            });
        }

        public static void handleGroupPrompt(GroupPromptPayload payload, NetworkManager.PacketContext context) {
            context.queue(() -> art.rehra.mineqtt.ui.GroupNameScreen.open(payload));
        }

        public static void handleRoomPrompt(RoomPromptPayload payload, NetworkManager.PacketContext context) {
            context.queue(() -> art.rehra.mineqtt.ui.RoomNameScreen.open(payload));
        }

        // Only the screen of that console takes it.
        public static void handleConsoleView(ConsoleViewPayload payload, NetworkManager.PacketContext context) {
            context.queue(() -> {
                if (context.getPlayer().containerMenu instanceof ConsoleMenu menu && payload.view().pos().equals(menu.blockPos)) {
                    menu.setView(payload.view());
                }
            });
        }

        // Only the screen of that device takes it, also while its entity picker covers it.
        public static void handleTwinView(TwinViewPayload payload, NetworkManager.PacketContext context) {
            context.queue(() -> {
                if (context.getPlayer().containerMenu instanceof DeviceMenu menu && payload.view().pos().equals(menu.blockPos)) {
                    menu.setTwinView(payload.view());
                }
            });
        }

        // Only the screen of that device takes it; its picker reads it from there.
        public static void handleTwinResults(TwinResultsPayload payload, NetworkManager.PacketContext context) {
            context.queue(() -> {
                if (context.getPlayer().containerMenu instanceof DeviceMenu menu && payload.pos().equals(menu.blockPos)) {
                    menu.setTwinResults(payload);
                }
            });
        }

        // Only an open explorer takes it; its Devices tab drops an answer to a search it has moved on from.
        public static void handleExplorerPage(ExplorerPagePayload payload, NetworkManager.PacketContext context) {
            context.queue(() -> {
                if (context.getPlayer().containerMenu instanceof CyberdeckMenu menu) menu.setExplorerPage(payload.page());
            });
        }

        // Only the device screen the change came from takes it.
        public static void handleDeviceRefused(DeviceRefusedPayload payload, NetworkManager.PacketContext context) {
            context.queue(() -> {
                if (context.getPlayer().containerMenu instanceof DeviceMenu menu && payload.pos().equals(menu.blockPos)) {
                    menu.refused(payload.key(), payload.reason());
                }
            });
        }
    }
}

package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.compat.Screens;
import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.integrations.PermissionManager;
import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceConfigPayload;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceEjectPayload;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceRetryPayload;
import art.rehra.mineqtt.security.AccessMode;
import art.rehra.mineqtt.ui.device.AccessTab;
import art.rehra.mineqtt.ui.device.DeviceMenu;
import art.rehra.mineqtt.ui.device.DiagnosticsTab;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The generated device screen's Access and Diagnostics tabs from a real client (M2 plan §4.2 M2-11). The non-op owner
 * of a Redstone Output steps its access mode from claim to public and private; on another owner's output, which the
 * player may configure, the mode button is read-only and a mode change sent anyway is refused on the tab and in chat,
 * while the twin {@code /mineqtt devices access} sets it private and closes the player's screen. A device placed without
 * an owner shows nobody and is claimed with one click. Diagnostics shows the device's UUID (copied to the clipboard),
 * that it is online, the last Home Assistant command it received with its payload, a refused one counted as dropped,
 * and the state it published; for a player who may only use a device the payloads are hidden. A retry of a device that
 * is not faulted is refused on the tab. The items of an M1 device's old slots are ejected into the inventory, drop when
 * another is broken and where a third stands with the command twin (M2-12).
 */
public final class AccessDiagnosticsGameTest implements FabricClientGameTest {
    private static final BlockPos STAND = new BlockPos(0, GameTestSession.GROUND_Y + 1, 0);
    private static final BlockPos GROUND = new BlockPos(2, GameTestSession.GROUND_Y, 0);
    private static final BlockPos OTHERS = new BlockPos(-2, GameTestSession.GROUND_Y + 1, 0);
    private static final BlockPos UNOWNED = new BlockPos(0, GameTestSession.GROUND_Y + 1, -2);
    private static final BlockPos LEGACY = new BlockPos(3, GameTestSession.GROUND_Y + 1, -2);
    private static final BlockPos BROKEN = new BlockPos(-3, GameTestSession.GROUND_Y + 1, -2);
    // Two papers and five stone in an M1 output's slots.
    private static final int LEGACY_COUNT = 7;
    private static final UUID OTHER_OWNER = UUID.fromString("2b7c8d9e-0f1a-4b2c-8d3e-4f5a6b7c8d9e");
    private static final int ANSWER_TICKS = 40;
    // Ample for a dropped payload to have done something if it had not been dropped.
    private static final int DROP_TICKS = 20;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "access-diagnostics", AccessDiagnosticsGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        BlockPos output = session.place(MineqttBlocks.REDSTONE_EMITTER_BLOCK.get(), GROUND);
        session.awaitServer(s -> s.overworld().getBlockEntity(output) instanceof DeviceHost host && host.deviceLink().attached());
        ownerSetsMode(session, output);
        othersDevice(session);
        claim(session);
        diagnostics(session, output);
        redacted(session);
        legacyItems(session);
    }

    /** The owner steps the access mode of their output: claim, the default, then public and private. */
    private static void ownerSetsMode(GameTestSession session, BlockPos output) {
        session.openBlock(output);
        session.check(session.tabIds().containsAll(List.of(AccessTab.ID, DiagnosticsTab.ID)), "the device's screen has Access and Diagnostics tabs: " + session.tabIds());
        session.check(session.clickTab(AccessTab.ID), "the Access tab opens");
        DeviceView view = session.screenView().orElseThrow();
        session.check(view.ownership().owner() == DeviceView.Owner.KNOWN && view.ownerName().equals(session.playerName()) && view.grants().access(),
                "the view names the player as the known owner, who may change the mode (" + view.ownership() + ", " + view.grants() + ")");
        session.check(!view.ownership().claimMod(), "without a claim mod the tab says the claim trusts everyone");
        session.check(modeLabel(session).equals("Claim") && modeActive(session), "the mode button shows claim, the default, and is active for the owner");
        session.check(!claimVisible(session), "an owned device offers no claim");
        session.check(session.awaitTitleNote(Optional.empty(), ANSWER_TICKS), "the owner sees no note in the title row (" + session.titleNote() + ")");
        session.screenshot("access_owner");

        session.clickWidget(AccessDiagnosticsGameTest::isMode, "the access mode button");
        session.check(awaitView(session, v -> v.access() == AccessMode.PUBLIC) && session.device(output, link -> link.access()) == AccessMode.PUBLIC,
                "one click makes the output public");
        session.clickWidget(AccessDiagnosticsGameTest::isMode, "the access mode button");
        session.check(awaitView(session, v -> v.access() == AccessMode.PRIVATE) && session.device(output, link -> link.access()) == AccessMode.PRIVATE,
                "the next makes it private: the owner may set private");
        session.check(session.awaitClient(mc -> label(mc, AccessDiagnosticsGameTest::isMode).equals("Private"), ANSWER_TICKS), "the button shows private");
        session.screenshot("access_private");
        session.closeScreen();
    }

    /**
     * Another owner's output, which the player may configure (no claim mod): the mode is read-only, a change sent anyway
     * is refused with the reason on the tab and in chat. The command twin sets it private, which closes the screen of a
     * player who may no longer use it, and claim again.
     */
    private static void othersDevice(GameTestSession session) {
        session.consoleCommand("setblock " + OTHERS.getX() + " " + OTHERS.getY() + " " + OTHERS.getZ() + " mineqtt:redstone_emitter_block{mineqtt:{v:2,id:"
                + intArray(UUID.randomUUID()) + ",type:\"mineqtt:redstone_output\",name:\"Neighbour Output\",owner:" + intArray(OTHER_OWNER)
                + ",ownerName:\"Someone\",access:\"claim\"}}");
        session.awaitServer(s -> s.overworld().getBlockEntity(OTHERS) instanceof DeviceHost host && host.deviceLink().attached());
        session.openBlock(OTHERS);
        session.check(session.clickTab(AccessTab.ID), "the Access tab of the neighbour's output opens");
        DeviceView view = session.screenView().orElseThrow();
        session.check(view.grants().equals(new DeviceView.Grants(true, true, false, false)) && view.ownerName().equals("Someone"),
                "the player may use and configure the neighbour's output but not change its access (" + view.grants() + ")");
        session.check(!modeActive(session) && !claimVisible(session), "the mode button is read-only and there is nothing to claim");
        session.check(session.awaitTitleNote(Optional.of("Read only"), ANSWER_TICKS), "the title row says Read only (" + session.titleNote() + ")");
        session.screenshot("access_read_only");

        int mark = ChatLog.mark();
        session.send(new DeviceConfigPayload(OTHERS, "access", StringTag.valueOf("private")));
        session.check(session.awaitChat(mark, line -> !line.actionBar() && line.text().startsWith("Not changed:") && line.text().contains("Only the owner or an operator"),
                ANSWER_TICKS).isPresent(), "a mode change sent anyway is refused in chat (messages: " + ChatLog.since(mark) + ")");
        session.check(session.awaitClient(mc -> menu(mc).refusal().filter(refusal -> refusal.key().equals("access")).isPresent(), ANSWER_TICKS),
                "and on the tab");
        session.check(session.device(OTHERS, link -> link.access()) == AccessMode.CLAIM, "a non-owner cannot set private: the output stays claim");
        session.screenshot("access_refused");

        String slug = session.device(OTHERS, link -> link.slug());
        session.consoleCommand("mineqtt devices access " + slug + " private");
        session.check(session.device(OTHERS, link -> link.access()) == AccessMode.PRIVATE, "devices access, the twin, sets it private");
        session.check(session.awaitClient(mc -> !(Screens.current() instanceof TabbedMqttScreen), ANSWER_TICKS),
                "the player, who may no longer use it, has its screen closed");
        session.consoleCommand("mineqtt devices access " + slug + " claim");
        session.check(session.device(OTHERS, link -> link.access()) == AccessMode.CLAIM, "and claim again");
    }

    /** A device placed without an owner shows nobody; the player claims it and may then set its mode. */
    private static void claim(GameTestSession session) {
        session.consoleCommand("setblock " + UNOWNED.getX() + " " + UNOWNED.getY() + " " + UNOWNED.getZ() + " mineqtt:redstone_emitter_block");
        session.awaitServer(s -> s.overworld().getBlockEntity(UNOWNED) instanceof DeviceHost host && host.deviceLink().attached());
        session.check(session.device(UNOWNED, link -> link.owner() == null && link.ownerName().isEmpty()), "a block set by command has no owner");
        session.openBlock(UNOWNED);
        session.check(session.clickTab(AccessTab.ID), "the Access tab of the ownerless output opens");
        DeviceView view = session.screenView().orElseThrow();
        session.check(view.ownership().owner() == DeviceView.Owner.NOBODY && AccessTab.claimable(view) && !view.grants().access(),
                "nobody owns it; the player may claim it but not yet change its mode");
        session.check(claimVisible(session) && claimActive(session) && !modeActive(session), "the claim button is active, the mode button is not");
        session.check(session.awaitTitleNote(Optional.empty(), ANSWER_TICKS), "a device the player may claim is not read only (" + session.titleNote() + ")");
        session.screenshot("access_unowned");
        session.clickWidget(AccessDiagnosticsGameTest::isClaim, "the claim button");
        session.check(awaitView(session, v -> v.ownership().owner() == DeviceView.Owner.KNOWN && v.ownerName().equals(session.playerName()) && v.grants().access()),
                "one click makes the player its owner, who may now change the mode");
        session.check(session.playerId().equals(session.device(UNOWNED, link -> link.owner())), "the server's device belongs to the player");
        session.check(session.awaitClient(mc -> !isVisible(mc, AccessDiagnosticsGameTest::isClaim) && active(mc, AccessDiagnosticsGameTest::isMode), ANSWER_TICKS),
                "the claim button is gone and the mode button is active");
        session.screenshot("access_claimed");
        session.closeScreen();
    }

    /**
     * The player's output on the Diagnostics tab: its UUID (copied), online or without a broker, the state it published
     * when it attached, the Home Assistant command it gets with its payload, a refused one counted as dropped, and a retry
     * of a device that is not faulted, refused on the tab.
     */
    private static void diagnostics(GameTestSession session, BlockPos output) {
        session.openBlock(output);
        session.check(session.clickTab(DiagnosticsTab.ID), "the Diagnostics tab opens");
        DeviceView.Diagnostics diagnostics = session.screenView().orElseThrow().diagnostics();
        UUID id = session.device(output, link -> link.id());
        boolean broker = session.broker().isPresent();
        session.check(diagnostics.id().equals(Optional.of(id)), "the tab shows the device's UUID " + id);
        session.check(diagnostics.availability() == (broker ? DeviceView.Availability.ONLINE : DeviceView.Availability.DISCONNECTED),
                "the device is " + diagnostics.availability() + " " + diagnostics.detail());
        session.check(!isVisible(session, AccessDiagnosticsGameTest::isRetry), "a device that is not faulted has no Retry button");
        String root = MineQTTConfig.baseTopic + "/" + GameTestSession.BRIDGE_ID;
        String slug = session.device(output, link -> link.slug());
        session.check(diagnostics.sent() >= 1 && diagnostics.lastOut().map(DeviceView.Traffic::topic).equals(Optional.of(root + "/" + slug))
                        && diagnostics.lastOut().flatMap(DeviceView.Traffic::payload).filter(payload -> payload.contains("\"state\"")).isPresent(),
                "the last message out is the state it published, payload shown to its owner (" + diagnostics.lastOut() + ")");

        String clipboard = session.onClient(mc -> mc.keyboardHandler.getClipboard());
        try {
            session.clickWidget(widget -> widget instanceof Button && widget.getMessage().getString().equals("Copy"), "Copy");
            session.check(session.onClient(mc -> mc.keyboardHandler.getClipboard()).equals(id.toString()), "Copy puts the UUID on the clipboard");
        } finally {
            session.context().runOnClient(mc -> mc.keyboardHandler.setClipboard(clipboard));
        }

        Optional<BrokerConfig> config = session.broker();
        if (config.isEmpty()) {
            session.skip("the last Home Assistant command shows on Diagnostics", "no broker (" + GameTestSession.BROKER_ENV + " is not set)");
        } else {
            try (BrokerProbe probe = BrokerProbe.connect(config.get())) {
                probe.publish(root + "/" + slug + "/set", "{\"power\":7}", false);
                session.check(awaitView(session, v -> v.diagnostics().lastIn().flatMap(DeviceView.Traffic::payload).equals(Optional.of("{\"power\":7}"))
                                && v.diagnostics().received() == 1 && v.diagnostics().dropped() == 0),
                        "Diagnostics shows the last Home Assistant command, its payload and size: " + session.screenView().map(DeviceView::diagnostics));
                session.check(session.onServer(s -> s.overworld().getBlockState(output).getValue(BlockStateProperties.POWER)) == 7, "and the command was applied");
                probe.publish(root + "/" + slug + "/set", "{\"mode\":\"bogus\"}", false);
                session.check(awaitView(session, v -> v.diagnostics().received() == 2 && v.diagnostics().dropped() == 1),
                        "a command the device refuses counts as received and dropped");
            }
            session.screenshot("diagnostics_in");
        }

        int mark = ChatLog.mark();
        session.send(new DeviceRetryPayload(output));
        session.check(session.awaitChat(mark, line -> line.text().startsWith("Not changed:") && line.text().contains(": Not faulted"), ANSWER_TICKS).isPresent(),
                "a retry of a device that is not faulted is refused in chat (messages: " + ChatLog.since(mark) + ")");
        session.check(session.awaitClient(mc -> menu(mc).refusal().filter(refusal -> refusal.key().equals("retry")).isPresent(), ANSWER_TICKS),
                "and on the tab");
        session.screenshot("diagnostics_retry_refused");
        session.closeScreen();
    }

    /**
     * A player whose claim lets them use but not configure the neighbour's output (a stand-in for a claim mod) sees its
     * traffic without payloads, and the guard drops a retry from them.
     */
    private static void redacted(GameTestSession session) {
        PermissionManager installed = MineQTT.permissionManager;
        MineQTT.permissionManager = new PermissionManager() {
            @Override
            public boolean canInteract(ServerPlayer player, BlockPos pos, MineqttPermission permission) {
                return permission == MineqttPermission.USE;
            }
        };
        try {
            session.openBlock(OTHERS);
            session.check(session.clickTab(DiagnosticsTab.ID), "the neighbour's Diagnostics tab opens");
            DeviceView view = session.screenView().orElseThrow();
            DeviceView.Traffic out = view.diagnostics().lastOut().orElseThrow();
            session.check(view.grants().equals(new DeviceView.Grants(true, false, false, false)) && out.payload().isEmpty() && out.bytes() > 0,
                    "a player who may only use the output sees the size of its last message, not its payload (" + out + ")");
            session.screenshot("diagnostics_redacted");
            int mark = ChatLog.mark();
            session.send(new DeviceRetryPayload(OTHERS));
            session.context().waitTicks(DROP_TICKS);
            session.check(ChatLog.since(mark).isEmpty(), "the guard drops a retry from a player who may not configure the device");
            session.closeScreen();
        } finally {
            MineQTT.permissionManager = installed;
        }
    }

    /**
     * The items of an M1 device's old slots (M2-12): the Diagnostics tab offers to eject them, and Eject puts them into
     * the player's inventory; breaking another one drops them with the block, and the command twin drops a third's where
     * it stands. A player who may only use a device cannot eject, and the guard drops an eject sent anyway.
     */
    private static void legacyItems(GameTestSession session) {
        placeM1Output(session, LEGACY);
        session.openBlock(LEGACY);
        session.check(session.clickTab(DiagnosticsTab.ID), "the M1 output's Diagnostics tab opens");
        DeviceView view = session.screenView().orElseThrow();
        session.check(view.legacyItems() == LEGACY_COUNT && session.onClient(mc -> label(mc, AccessDiagnosticsGameTest::isEject)).equals("Eject " + LEGACY_COUNT)
                        && session.onClient(mc -> active(mc, AccessDiagnosticsGameTest::isEject)),
                "the tab offers the " + LEGACY_COUNT + " items of its old slots (" + view.legacyItems() + ")");
        session.screenshot("diagnostics_legacy_items");
        int stoneBefore = session.onServer(s -> session.player(s).getInventory().countItem(Items.STONE));
        int paperBefore = session.onServer(s -> session.player(s).getInventory().countItem(Items.PAPER));
        int mark = ChatLog.mark();
        session.clickWidget(AccessDiagnosticsGameTest::isEject, "Eject");
        session.check(awaitView(session, v -> v.legacyItems() == 0) && session.awaitClient(mc -> !isVisible(mc, AccessDiagnosticsGameTest::isEject), ANSWER_TICKS),
                "Eject empties the device's legacy items, and the button goes");
        session.check(session.onServer(s -> session.player(s).getInventory().countItem(Items.STONE)) == stoneBefore + 5
                        && session.onServer(s -> session.player(s).getInventory().countItem(Items.PAPER)) == paperBefore + 2,
                "the player's inventory has the stone and both papers");
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().contains(Integer.toString(LEGACY_COUNT)), ANSWER_TICKS).isPresent(),
                "the action bar says how many items were taken (messages: " + ChatLog.since(mark) + ")");
        session.check(Boolean.TRUE.equals(session.device(LEGACY, link -> link.legacyItems().isEmpty())), "the server's device keeps none");
        session.screenshot("diagnostics_ejected");
        session.closeScreen();

        // Broken, a device drops what its old slots held where it stood, besides itself.
        placeM1Output(session, BROKEN);
        session.consoleCommand("setblock " + BROKEN.getX() + " " + BROKEN.getY() + " " + BROKEN.getZ() + " minecraft:air destroy");
        session.check(session.await(() -> droppedNear(session, BROKEN, Items.STONE) == 5 && droppedNear(session, BROKEN, Items.PAPER) == 2, ANSWER_TICKS),
                "breaking the device drops its five stone and two papers (" + droppedNear(session, BROKEN, Items.STONE) + " stone, "
                        + droppedNear(session, BROKEN, Items.PAPER) + " papers)");
        session.consoleCommand("kill @e[type=item]");

        // The command twin drops them at the device.
        placeM1Output(session, BROKEN);
        String slug = session.device(BROKEN, link -> link.slug());
        session.consoleCommand("mineqtt devices eject " + slug);
        session.check(session.await(() -> droppedNear(session, BROKEN, Items.STONE) == 5 && droppedNear(session, BROKEN, Items.PAPER) == 2, ANSWER_TICKS)
                && Boolean.TRUE.equals(session.device(BROKEN, link -> link.legacyItems().isEmpty())), "devices eject drops the items where the device stands");
        session.consoleCommand("kill @e[type=item]");
        session.consoleCommand("setblock " + BROKEN.getX() + " " + BROKEN.getY() + " " + BROKEN.getZ() + " minecraft:air");

        PermissionManager installed = MineQTT.permissionManager;
        MineQTT.permissionManager = new PermissionManager() {
            @Override
            public boolean canInteract(ServerPlayer player, BlockPos pos, MineqttPermission permission) {
                return permission == MineqttPermission.USE;
            }
        };
        try {
            placeM1Output(session, BROKEN);
            // The ejected paper may sit in the main hand, and a renamed paper fills a channel instead of opening the screen.
            session.hold(Items.AIR);
            session.openBlock(BROKEN);
            session.check(session.clickTab(DiagnosticsTab.ID), "the Diagnostics tab of a device the player may only use opens");
            session.check(isVisible(session, AccessDiagnosticsGameTest::isEject) && !session.onClient(mc -> active(mc, AccessDiagnosticsGameTest::isEject)),
                    "it shows the items but the player may not eject them");
            session.send(new DeviceEjectPayload(BROKEN));
            session.context().waitTicks(DROP_TICKS);
            session.check(Boolean.TRUE.equals(session.device(BROKEN, link -> link.legacyItems().size() == 3)), "the guard drops an eject sent anyway");
            session.closeScreen();
        } finally {
            MineQTT.permissionManager = installed;
        }
        session.consoleCommand("setblock " + BROKEN.getX() + " " + BROKEN.getY() + " " + BROKEN.getZ() + " minecraft:air");
    }

    // A Redstone Output with M1's data: a version 2 compound and its three slots beside it, which load as legacy items.
    private static void placeM1Output(GameTestSession session, BlockPos pos) {
        session.consoleCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " mineqtt:redstone_emitter_block{mineqtt:{v:2,id:"
                + intArray(UUID.randomUUID()) + ",type:\"mineqtt:redstone_output\",name:\"M1 Output\",access:\"claim\"},Items:["
                + "{Slot:0b,id:\"minecraft:paper\",count:1,components:{\"minecraft:custom_name\":\"m1/door\"}},"
                + "{Slot:2b,id:\"minecraft:paper\",count:1,components:{\"minecraft:custom_name\":\"ON\"}},"
                + "{Slot:3b,id:\"minecraft:stone\",count:5}],topic:\"m1/door\",privateMode:0b,lastTabId:\"\"}");
        session.awaitServer(s -> s.overworld().getBlockEntity(pos) instanceof DeviceHost host && host.deviceLink().attached());
    }

    // Item entities of this item within two blocks of pos, by count.
    private static int droppedNear(GameTestSession session, BlockPos pos, Item item) {
        return session.onServer(s -> s.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2), entity -> entity.getItem().is(item))
                .stream().mapToInt(entity -> entity.getItem().getCount()).sum());
    }

    private static boolean isEject(AbstractWidget widget) {
        return widget instanceof Button && widget.getMessage().getString().startsWith("Eject");
    }

    private static boolean awaitView(GameTestSession session, Predicate<DeviceView> matches) {
        return session.awaitClient(mc -> {
            DeviceView view = menu(mc).deviceView().orElse(null);
            return view != null && matches.test(view);
        }, ANSWER_TICKS);
    }

    private static boolean isMode(AbstractWidget widget) {
        return widget instanceof Button && List.of("Private", "Claim", "Public").contains(widget.getMessage().getString());
    }

    private static boolean isClaim(AbstractWidget widget) {
        return widget instanceof Button && widget.getMessage().getString().equals("Make mine");
    }

    private static boolean isRetry(AbstractWidget widget) {
        return widget instanceof Button && widget.getMessage().getString().startsWith("Retry");
    }

    private static String modeLabel(GameTestSession session) {
        return session.onClient(mc -> label(mc, AccessDiagnosticsGameTest::isMode));
    }

    private static boolean modeActive(GameTestSession session) {
        return session.onClient(mc -> active(mc, AccessDiagnosticsGameTest::isMode));
    }

    private static boolean claimVisible(GameTestSession session) {
        return isVisible(session, AccessDiagnosticsGameTest::isClaim);
    }

    private static boolean claimActive(GameTestSession session) {
        return session.onClient(mc -> active(mc, AccessDiagnosticsGameTest::isClaim));
    }

    private static boolean isVisible(GameTestSession session, Predicate<AbstractWidget> which) {
        return session.onClient(mc -> isVisible(mc, which));
    }

    private static boolean isVisible(Minecraft mc, Predicate<AbstractWidget> which) {
        return widget(mc, which).map(widget -> widget.visible).orElse(false);
    }

    private static boolean active(Minecraft mc, Predicate<AbstractWidget> which) {
        return widget(mc, which).orElseThrow().active;
    }

    private static String label(Minecraft mc, Predicate<AbstractWidget> which) {
        return widget(mc, which).orElseThrow().getMessage().getString();
    }

    private static Optional<AbstractWidget> widget(Minecraft mc, Predicate<AbstractWidget> which) {
        return screen(mc).children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).filter(which).findFirst();
    }

    private static DeviceMenu menu(Minecraft mc) {
        return (DeviceMenu) screen(mc).getMenu();
    }

    private static TabbedMqttScreen screen(Minecraft mc) {
        if (Screens.current() instanceof TabbedMqttScreen screen) return screen;
        throw new AssertionError("No MineQTT screen is open: " + Screens.current());
    }

    /** A UUID as an SNBT int array, the way block entity data stores it. */
    private static String intArray(UUID id) {
        return Arrays.toString(UUIDUtil.uuidToIntArray(id)).replace("[", "[I;").replace(" ", "");
    }
}

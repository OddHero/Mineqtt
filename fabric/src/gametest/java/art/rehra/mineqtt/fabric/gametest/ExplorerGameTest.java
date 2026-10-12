package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.items.MineqttItems;
import art.rehra.mineqtt.network.MineqttNetworking.ExplorerActionPayload;
import art.rehra.mineqtt.presence.Consent;
import art.rehra.mineqtt.presence.PrivacyPolicy;
import art.rehra.mineqtt.rooms.Room;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.ui.CyberdeckMenu;
import art.rehra.mineqtt.ui.CyberdeckScreen;
import art.rehra.mineqtt.ui.device.DeviceMenu;
import art.rehra.mineqtt.ui.explorer.DevicesTab;
import art.rehra.mineqtt.ui.explorer.DevicesTabView;
import art.rehra.mineqtt.ui.explorer.ExplorerActions;
import art.rehra.mineqtt.ui.explorer.ExplorerDirectory;
import art.rehra.mineqtt.ui.explorer.ExplorerPage;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import art.rehra.mineqtt.ui.framework.tabs.CyberdeckExplorerTab;
import art.rehra.mineqtt.ui.framework.tabs.CyberdeckPublishTab;
import art.rehra.mineqtt.ui.framework.tabs.SettingsTab;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The Configurator explorer's Devices tab (M4 plan D13). Pointing at nothing, the Configurator opens on Devices, before
 * Settings, Traffic (the topic explorer, its id unchanged) and Publish. The non-op player finds their own lamp and
 * output, each with how it is available and how far away it is in which direction, but neither another player's public
 * lamp nor a device of nobody. Their You row shows what the server shares about them; its buttons store the choices of
 * {@code /mineqtt privacy share} (D9). A search narrows the list and one that matches nothing says so; a room filter
 * narrows it to the devices of one room. A selected device offers Use, Locate and Open: from 38 blocks away Use toggles
 * the lamp, Locate marks it with particles, Open refuses and says how far it is; another player's lamp is never acted
 * on; from close by Open opens the lamp's screen; Use of a lamp in an unloaded chunk queues and lands when the chunk
 * loads. In adventure mode the tab lists nothing. As an operator the player finds every device, 59 of them on two pages
 * of 50, and the page buttons step between the pages.
 */
public final class ExplorerGameTest implements FabricClientGameTest {
    private static final int Y = GameTestSession.GROUND_Y + 1;
    private static final BlockPos STAND = new BlockPos(0, Y, 0);
    // 38 blocks from the lamp: beyond the Configurator's 16, within Locate's 128.
    private static final BlockPos FAR_STAND = new BlockPos(40, Y, 0);
    private static final BlockPos LAMP_GROUND = new BlockPos(2, GameTestSession.GROUND_Y, 0);
    private static final BlockPos OUTPUT_GROUND = new BlockPos(0, GameTestSession.GROUND_Y, -3);
    private static final BlockPos FOREIGN_LAMP = new BlockPos(-2, Y, 2);
    private static final BlockPos NOBODYS_INPUT = new BlockPos(-3, Y, -2);
    // Far beyond any view distance, so its chunk unloads without its forceload ticket.
    private static final BlockPos UNLOADED_LAMP = new BlockPos(4096, Y, 4096);
    // A field of ownerless Redstone Inputs, 5 by 11, east of the player: the operator's second page.
    private static final BlockPos FIELD_FROM = new BlockPos(6, Y, -5);
    private static final BlockPos FIELD_TO = new BlockPos(10, Y, 5);
    private static final int FIELD_SIZE = 55;
    private static final UUID STRANGER = UUID.fromString("22222222-3333-4444-5555-666666666666");
    private static final int TELEPORT_RESEND_TICKS = 25;
    // The debounce, the round trip and a resend.
    private static final int ANSWER_TICKS = 80;
    private static final int PARTICLE_TICKS = 40;
    private static final int UNLOAD_TICKS = 30 * 20;
    private static final Predicate<AbstractWidget> SEARCH = widget -> widget instanceof EditBox;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "explorer", ExplorerGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        session.context().waitTicks(TELEPORT_RESEND_TICKS);
        BlockPos lamp = session.place(MineqttBlocks.RGB_LED_BLOCK.get(), LAMP_GROUND);
        BlockPos output = session.place(MineqttBlocks.REDSTONE_EMITTER_BLOCK.get(), OUTPUT_GROUND);
        session.consoleCommand("setblock " + FOREIGN_LAMP.getX() + " " + FOREIGN_LAMP.getY() + " " + FOREIGN_LAMP.getZ()
                + " mineqtt:rgb_led_block{mineqtt:{v:2,id:" + intArray(UUID.randomUUID()) + ",type:\"mineqtt:smart_lamp\",owner:" + intArray(STRANGER)
                + ",ownerName:\"Stranger\",access:\"public\"}}");
        session.consoleCommand("setblock " + NOBODYS_INPUT.getX() + " " + NOBODYS_INPUT.getY() + " " + NOBODYS_INPUT.getZ() + " mineqtt:redstone_publisher_block");
        for (BlockPos device : List.of(lamp, output, FOREIGN_LAMP, NOBODYS_INPUT)) {
            session.awaitServer(s -> slug(s, device) != null);
        }
        session.consoleCommand("mineqtt devices rename " + session.onServer(s -> slug(s, lamp)) + " Explorer Lamp");
        session.consoleCommand("mineqtt devices rename " + session.onServer(s -> slug(s, output)) + " Explorer Output");
        session.awaitServer(s -> "explorer_lamp".equals(slug(s, lamp)) && "explorer_output".equals(slug(s, output)));
        String foreign = session.onServer(s -> slug(s, FOREIGN_LAMP));
        String nobodys = session.onServer(s -> slug(s, NOBODYS_INPUT));

        ownDevices(session, lamp, foreign, nobodys);
        youRow(session);
        search(session);
        roomFilter(session, lamp);
        actionsFromAfar(session, lamp);
        openFromClose(session, lamp);
        roomSwitches(session, lamp, output);
        unloadedUse(session);
        adventure(session);
        operator(session, foreign, nobodys);
        session.consoleCommand("deop " + session.playerName());
    }

    /** The explorer opens on Devices, which lists the player's own devices alone, with their availability, distance and bearing. */
    private static void ownDevices(GameTestSession session, BlockPos lamp, String foreign, String nobodys) {
        session.hold(MineqttItems.Cyberdeck.get());
        session.check(session.useHeldItem() instanceof CyberdeckScreen, "pointing at nothing, the Configurator opens its explorer");
        session.check(session.tabIds().equals(List.of(DevicesTab.ID, SettingsTab.ID, CyberdeckExplorerTab.ID, CyberdeckPublishTab.ID)),
                "the explorer has the Devices, Settings, Traffic and Publish tabs (" + session.tabIds() + ")");
        session.check(session.onClient(mc -> GameTestSession.openScreen(mc).getMenu().getActiveTabId()).equals(DevicesTab.ID), "and opens on Devices");
        session.check(session.onClient(mc -> GameTestSession.openScreen(mc).getMenu().tabs.get(2).title().getString()).equals("Traffic"),
                "the topic explorer is labelled Traffic");
        ExplorerPage page = awaitPage(session, answer -> answer.query().isEmpty());
        session.check(page.scope() == ExplorerPage.Scope.OWN && page.total() == 2 && slugs(page).equals(List.of("explorer_lamp", "explorer_output")),
                "the Devices tab lists the player's own lamp and output, by name (" + slugs(page) + ", " + page.scope() + ")");
        session.check(!slugs(page).contains(foreign) && !slugs(page).contains(nobodys),
                "neither the other player's public lamp nor the device of nobody (" + foreign + ", " + nobodys + ")");
        ExplorerPage.Row row = page.rows().getFirst();
        int distance = session.onServer(s -> ExplorerDirectory.distance(session.player(s).blockPosition(), lamp));
        session.check(row.near() && row.distance() == distance && row.bearing() == ExplorerPage.Bearing.EAST,
                "the lamp lies " + distance + " blocks east (" + row.distance() + " " + row.bearing() + ")");
        ExplorerPage.Availability expected = session.broker().isPresent() ? ExplorerPage.Availability.ONLINE : ExplorerPage.Availability.NO_BROKER;
        session.check(row.model().equals("Smart Lamp") && row.availability() == expected && !row.values().isEmpty() && row.usable(),
                "with its model, availability, values and a use (" + row + ")");
        session.screenshot("explorer_devices_own");
        session.check(session.clickTab(CyberdeckExplorerTab.ID), "the Traffic tab opens by its id");
        session.check(session.clickTab(DevicesTab.ID), "and the Devices tab again");
    }

    /**
     * The You row (D9) shows what the server shares about the player, who has not chosen: nothing. Its buttons are the
     * prompt's ([Share all] only where the server shares full data) and store what {@code /mineqtt privacy share} stores,
     * with the command's answer in the chat; the choice made is pressed.
     */
    private static void youRow(GameTestSession session) {
        ExplorerPage page = awaitPage(session, answer -> answer.query().isEmpty() && answer.room().isEmpty());
        ExplorerPage.You you = page.you();
        session.check(you.choice() == Consent.UNSET && you.shared() == PrivacyPolicy.Shared.NOTHING && you.offered() == PrivacyPolicy.Shared.PRESENCE
                        && !you.host() && !you.report().isEmpty() && you.report().getFirst().startsWith("Your choice: not made") && you.presence().isEmpty(),
                "the You row says the player chose nothing and nothing is shared, on a server that shares presence (" + you + ")");
        session.check(session.onClient(mc -> visibleButton(mc, "Presence") && visibleButton(mc, "Off") && !visibleButton(mc, "All")),
                "its buttons are Presence and Off, not All, on a presence server");
        session.screenshot("explorer_you_row");
        int mark = ChatLog.mark();
        clickButton(session, "Presence");
        page = awaitPage(session, answer -> answer.you().choice() == Consent.PRESENCE);
        session.check(page.you().shared() == PrivacyPolicy.Shared.PRESENCE
                        && session.onRuntime(runtime -> runtime.privacy().entry(session.playerId()).consent()) == Consent.PRESENCE,
                "Presence stores share presence, and the row says the player's presence is shared");
        session.check(session.awaitChat(mark, line -> !line.actionBar() && line.text().startsWith("Saved: share presence."), ANSWER_TICKS).isPresent(),
                "the chat answers as /mineqtt privacy share on does");
        session.check(session.awaitClient(mc -> buttonState(mc, "Presence").equals(Optional.of(false)), ANSWER_TICKS), "the choice made is pressed");
        clickButton(session, "Off");
        page = awaitPage(session, answer -> answer.you().choice() == Consent.OFF);
        session.check(page.you().shared() == PrivacyPolicy.Shared.NOTHING
                        && session.onRuntime(runtime -> runtime.privacy().entry(session.playerId()).consent()) == Consent.OFF,
                "Off stores don't share, and the row says nothing is shared");
    }

    /** A search narrows the list to what matches, and says so when nothing does. */
    private static void search(GameTestSession session) {
        session.typeInto(SEARCH, "the search field", "LAMP");
        ExplorerPage page = awaitPage(session, answer -> answer.query().equals("LAMP"));
        session.check(slugs(page).equals(List.of("explorer_lamp")) && page.total() == 1,
                "a search for LAMP finds the player's lamp alone, not the other player's (" + slugs(page) + ")");
        session.typeInto(SEARCH, "the search field", "nothing like it");
        page = awaitPage(session, answer -> answer.query().equals("nothing like it"));
        session.check(page.rows().isEmpty() && page.total() == 0, "a search that matches nothing lists nothing (" + slugs(page) + ")");
        session.screenshot("explorer_devices_no_match");
        session.closeScreen();
    }

    /** With a room of the player's around the lamp, the lamp shows under it first, and the room filter lists it alone. */
    private static void roomFilter(GameTestSession session, BlockPos lamp) {
        session.playerCommand("mineqtt rooms create Kitchen " + (lamp.getX() - 1) + " " + (lamp.getY() - 1) + " " + (lamp.getZ() - 1) + " "
                + (lamp.getX() + 1) + " " + (lamp.getY() + 1) + " " + (lamp.getZ() + 1));
        session.awaitServer(s -> MineqttRuntime.of(s).registry().rooms().size() == 1);
        UUID kitchen = session.onRuntime(runtime -> runtime.registry().rooms().stream().map(Room::id).findFirst().orElseThrow());
        session.useHeldItem();
        ExplorerPage page = awaitPage(session, answer -> answer.query().isEmpty() && answer.room().isEmpty() && !answer.rooms().isEmpty());
        session.check(page.rooms().equals(List.of(new ExplorerPage.RoomRef(kitchen, "Kitchen", 1))) && slugs(page).equals(List.of("explorer_lamp", "explorer_output"))
                        && page.rows().getFirst().room() == 0 && page.rows().get(1).room() == -1,
                "the lamp shows first, in the Kitchen, the output after it in no room (" + page.rooms() + ", " + slugs(page) + ")");
        session.screenshot("explorer_devices_rooms");
        clickButton(session, "All rooms");
        page = awaitPage(session, answer -> answer.room().equals("Kitchen"));
        session.check(slugs(page).equals(List.of("explorer_lamp")) && page.total() == 1, "the room filter Kitchen lists the lamp alone (" + slugs(page) + ")");
        session.check(session.onClient(mc -> visibleButton(mc, "Kitchen")), "and the room button names the room");
        clickButton(session, "Kitchen");
        page = awaitPage(session, answer -> answer.room().isEmpty() && answer.total() == 2);
        session.check(slugs(page).equals(List.of("explorer_lamp", "explorer_output")), "after the last room, every room again");
        session.closeScreen();
    }

    /**
     * From 38 blocks away: Use toggles the lamp, Locate marks it with particles for the player, Open refuses and says how
     * far away it is. An action on another player's lamp, sent by hand, reaches nothing.
     */
    private static void actionsFromAfar(GameTestSession session, BlockPos lamp) {
        session.teleport(FAR_STAND);
        session.context().waitTicks(TELEPORT_RESEND_TICKS);
        UUID lampId = deviceId(session, lamp);
        session.useHeldItem();
        awaitPage(session, answer -> answer.rows().stream().anyMatch(row -> row.id().equals(lampId)));
        select(session, lampId);
        session.check(session.onClient(mc -> visibleButton(mc, "Use") && visibleButton(mc, "Locate") && visibleButton(mc, "Open")),
                "the selected lamp offers Use, Locate and Open");
        session.screenshot("explorer_devices_selected");

        String before = state(session, lamp);
        int mark = ChatLog.mark();
        clickButton(session, "Use");
        session.check(session.await(() -> !state(session, lamp).equals(before), ANSWER_TICKS), "Use toggles the lamp 38 blocks away (" + before + ")");
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Used Explorer Lamp"), ANSWER_TICKS).isPresent(),
                "and the action bar says so");

        session.context().runOnClient(mc -> mc.particleEngine.clearParticles());
        mark = ChatLog.mark();
        clickButton(session, "Locate");
        GlobalPos at = GlobalPos.of(Level.OVERWORLD, lamp);
        session.check(session.onServer(s -> ExplorerActions.marked(session.playerId(), at)), "Locate marks the lamp for the player");
        session.check(session.awaitClient(mc -> particles(mc) > 0, PARTICLE_TICKS), "with particles the client shows");
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().startsWith("Explorer Lamp: 38 m W, marked"), ANSWER_TICKS).isPresent(),
                "and how far and in which direction it is");

        mark = ChatLog.mark();
        clickButton(session, "Open");
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().contains("blocks away; its screen opens within 16"), ANSWER_TICKS).isPresent(),
                "Open from 38 blocks away is refused with the distance");
        session.check(session.onClient(mc -> mc.gui.screen() instanceof CyberdeckScreen), "and the explorer stays open");

        UUID foreignId = deviceId(session, FOREIGN_LAMP);
        String foreignBefore = state(session, FOREIGN_LAMP);
        // The screen's actions are paced at two a second after a burst of four; let the pace refill.
        session.context().waitTicks(40);
        mark = ChatLog.mark();
        session.send(new ExplorerActionPayload(foreignId, ExplorerActions.Kind.LOCATE));
        session.send(new ExplorerActionPayload(foreignId, ExplorerActions.Kind.USE));
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("That is no device of yours"), ANSWER_TICKS).isPresent(),
                "an action on another player's public lamp is refused as no device of the player's");
        session.check(!session.onServer(s -> ExplorerActions.marked(session.playerId(), GlobalPos.of(Level.OVERWORLD, FOREIGN_LAMP)))
                && state(session, FOREIGN_LAMP).equals(foreignBefore), "it is neither marked nor used");
        session.closeScreen();
    }

    /** From close by, Open opens the lamp's own screen. */
    private static void openFromClose(GameTestSession session, BlockPos lamp) {
        session.teleport(STAND);
        session.context().waitTicks(TELEPORT_RESEND_TICKS);
        UUID lampId = deviceId(session, lamp);
        session.useHeldItem();
        awaitPage(session, answer -> answer.rows().stream().anyMatch(row -> row.id().equals(lampId)));
        select(session, lampId);
        clickButton(session, "Open");
        session.check(session.awaitClient(mc -> mc.gui.screen() instanceof TabbedMqttScreen screen && screen.getMenu() instanceof DeviceMenu menu
                && lamp.equals(menu.blockPos), ANSWER_TICKS), "Open within 16 blocks opens the lamp's screen");
        session.closeScreen();
    }

    /**
     * A room's All on and All off (D1) switch the devices of it the player finds: in a room for everyone over their output
     * and another player's lamp, the output alone; a second switch within a second is refused. The command twin switches
     * the player's Kitchen.
     */
    private static void roomSwitches(GameTestSession session, BlockPos lamp, BlockPos output) {
        session.consoleCommand("mineqtt rooms create Hall -3 " + (Y - 1) + " -4 1 " + (Y + 1) + " 3 for-all");
        session.awaitServer(s -> MineqttRuntime.of(s).registry().rooms().size() == 2);
        session.useHeldItem();
        ExplorerPage page = awaitPage(session, answer -> answer.query().isEmpty() && answer.room().isEmpty() && answer.rooms().size() == 2);
        session.check(page.rooms().stream().map(ExplorerPage.RoomRef::name).toList().equals(List.of("Hall", "Kitchen"))
                        && slugs(page).equals(List.of("explorer_output", "explorer_lamp")),
                "the output shows under the Hall, the lamp under the Kitchen; the other player's lamp in the Hall is not listed (" + slugs(page) + ")");
        session.check(session.onClient(mc -> visibleButton(mc, "All on") && visibleButton(mc, "All off")), "the Hall's header offers All on and All off");
        session.screenshot("explorer_room_switch");
        String foreignBefore = state(session, FOREIGN_LAMP);
        int mark = ChatLog.mark();
        // Both clicks right after another, so the second reaches the server within the second however loaded the host is.
        clickButton(session, "All on");
        clickButton(session, "All off");
        session.check(session.await(() -> state(session, output).equals("ON"), ANSWER_TICKS), "All on switches the player's output on");
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Hall: 1 switched on"), ANSWER_TICKS).isPresent(),
                "and says so, counting the output alone");
        session.check(state(session, FOREIGN_LAMP).equals(foreignBefore), "the other player's lamp in the room for everyone is skipped (" + foreignBefore + ")");
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("One room switch a second; try Hall again in a moment"),
                ANSWER_TICKS).isPresent() && state(session, output).equals("ON"), "a second switch within a second is refused");
        session.context().waitTicks(25);
        clickButton(session, "All off");
        session.check(session.await(() -> state(session, output).equals("OFF"), ANSWER_TICKS), "a second later All off switches the output off");
        session.closeScreen();
        session.context().waitTicks(25);
        String lampBefore = state(session, lamp);
        String wanted = lampBefore.equals("ON") ? "off" : "on";
        mark = ChatLog.mark();
        session.playerCommand("mineqtt rooms switch Kitchen " + wanted);
        session.check(session.await(() -> state(session, lamp).equals(wanted.toUpperCase(Locale.ROOT)), ANSWER_TICKS)
                        && session.awaitChat(mark, line -> line.text().equals("Kitchen: 1 switched " + wanted), ANSWER_TICKS).isPresent(),
                "/mineqtt rooms switch Kitchen " + wanted + " switches the lamp (" + lampBefore + ")");
    }

    /** Use of a lamp in an unloaded chunk queues the toggle, which lands when the chunk loads. */
    private static void unloadedUse(GameTestSession session) {
        String chunk = UNLOADED_LAMP.getX() + " " + UNLOADED_LAMP.getZ();
        session.consoleCommand("forceload add " + chunk);
        session.consoleCommand("setblock " + UNLOADED_LAMP.getX() + " " + UNLOADED_LAMP.getY() + " " + UNLOADED_LAMP.getZ()
                + " mineqtt:rgb_led_block{mineqtt:{v:2,id:" + intArray(UUID.randomUUID()) + ",type:\"mineqtt:smart_lamp\",owner:" + intArray(session.playerId())
                + ",ownerName:\"" + session.playerName() + "\",access:\"private\"}}");
        session.awaitServer(s -> s.overworld().getBlockEntity(UNLOADED_LAMP) instanceof DeviceHost host && host.deviceLink().attached());
        session.consoleCommand("mineqtt devices rename " + session.onServer(s -> slug(s, UNLOADED_LAMP)) + " Far Lamp");
        session.awaitServer(s -> "far_lamp".equals(slug(s, UNLOADED_LAMP)));
        UUID farId = deviceId(session, UNLOADED_LAMP);
        String before = state(session, UNLOADED_LAMP);
        session.consoleCommand("forceload remove " + chunk);
        // A game test's server never has time left in a tick, and the chunk map unloads chunks only with time left, so each
        // wait gives it one pass of the chunk source with time left, as an idle server would (WiresGameTest).
        session.check(session.await(() -> session.onServer(s -> {
            s.overworld().getChunkSource().tick(() -> true, false);
            return !MineqttRuntime.of(s).devices().isAttached(farId);
        }), UNLOAD_TICKS), "the far lamp unloads with its chunk");
        session.useHeldItem();
        session.typeInto(SEARCH, "the search field", "Far");
        ExplorerPage page = awaitPage(session, answer -> answer.query().equals("Far") && answer.rows().size() == 1);
        session.check(page.rows().getFirst().availability() == ExplorerPage.Availability.QUEUED, "the unloaded lamp shows as unloaded (" + page.rows() + ")");
        select(session, farId);
        int mark = ChatLog.mark();
        clickButton(session, "Use");
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Far Lamp is not loaded; it follows when its chunk loads"),
                ANSWER_TICKS).isPresent(), "Use of the unloaded lamp queues the toggle");
        session.check(session.onRuntime(runtime -> runtime.registry().record(farId).pending()) != null, "which waits in its record");
        session.closeScreen();
        session.consoleCommand("forceload add " + chunk);
        session.check(session.await(() -> session.onServer(s -> MineqttRuntime.of(s).devices().isAttached(farId)) && !state(session, UNLOADED_LAMP).equals(before),
                UNLOAD_TICKS), "loading the chunk lands the toggle (" + before + ")");
        session.consoleCommand("setblock " + UNLOADED_LAMP.getX() + " " + UNLOADED_LAMP.getY() + " " + UNLOADED_LAMP.getZ() + " minecraft:air");
        session.awaitServer(s -> MineqttRuntime.of(s).registry().record(farId) == null);
        session.consoleCommand("forceload remove " + chunk);
    }

    /** A player who may not build finds nothing, not even their own devices. */
    private static void adventure(GameTestSession session) {
        gameMode(session, GameType.ADVENTURE);
        session.useHeldItem();
        ExplorerPage page = awaitPage(session, answer -> answer.query().isEmpty());
        session.check(page.scope() == ExplorerPage.Scope.NONE && page.rows().isEmpty() && page.total() == 0,
                "in adventure mode the Devices tab lists nothing (" + page.scope() + ", " + slugs(page) + ")");
        session.screenshot("explorer_devices_adventure");
        session.closeScreen();
        gameMode(session, GameType.SURVIVAL);
    }

    /** An operator finds every device, on pages of 50 the page buttons step through. */
    private static void operator(GameTestSession session, String foreign, String nobodys) {
        session.consoleCommand("op " + session.playerName());
        session.consoleCommand("fill " + FIELD_FROM.getX() + " " + FIELD_FROM.getY() + " " + FIELD_FROM.getZ() + " "
                + FIELD_TO.getX() + " " + FIELD_TO.getY() + " " + FIELD_TO.getZ() + " mineqtt:redstone_publisher_block");
        int total = 4 + FIELD_SIZE;
        session.awaitServer(s -> MineqttRuntime.of(s).registry().records().size() == total);
        session.useHeldItem();
        ExplorerPage first = awaitPage(session, answer -> answer.query().isEmpty() && answer.total() == total);
        session.check(first.scope() == ExplorerPage.Scope.ALL && first.page() == 0 && first.rows().size() == ExplorerPage.PAGE_SIZE && first.pages() == 2,
                "the operator finds all " + total + " devices, the first 50 on page 1 of 2 (" + first.rows().size() + ", " + first.scope() + ")");
        session.check(!session.onClient(ExplorerGameTest::previousActive) && session.onClient(mc -> buttonState(mc, ">").equals(Optional.of(true))),
                "the previous page button is off on the first page, the next one on");
        session.clickWidget(pageButton(">"), "the next page button");
        ExplorerPage second = awaitPage(session, answer -> answer.query().isEmpty() && answer.page() == 1);
        session.check(second.rows().size() == total - ExplorerPage.PAGE_SIZE, "the second page holds the other " + second.rows().size());
        List<String> all = new ArrayList<>(slugs(first));
        all.addAll(slugs(second));
        session.check(all.contains(foreign) && all.contains(nobodys) && all.contains("explorer_lamp"),
                "the operator finds the other player's lamp and the device of nobody too");
        session.screenshot("explorer_devices_operator_page2");
        session.clickWidget(pageButton("<"), "the previous page button");
        session.check(awaitPage(session, answer -> answer.query().isEmpty() && answer.page() == 0).rows().size() == ExplorerPage.PAGE_SIZE,
                "and back to the first");
        session.closeScreen();
    }

    /**
     * The page the Devices tab shows, once one that matches is drawn; fails after the timeout. It waits for what the tab
     * shows, never for the page that merely arrived: the client handles the server's packets in the task phase of a frame,
     * the test thread runs right after it, and the tab takes the page (and turns its page buttons on for it) only when that
     * frame draws. A click in between landed on buttons still set for the page before, so the next page button stayed off
     * and swallowed the click. Under load, when the client draws about one frame per tick, that happened often enough to
     * fail the paging step now and then.
     */
    private static ExplorerPage awaitPage(GameTestSession session, Predicate<ExplorerPage> matches) {
        boolean shown = session.awaitClient(mc -> view(mc).flatMap(DevicesTabView::shown).filter(matches).isPresent(), ANSWER_TICKS);
        Optional<ExplorerPage> page = session.onClient(mc -> view(mc).flatMap(DevicesTabView::shown));
        if (!shown || page.isEmpty()) throw new AssertionError("The Devices tab shows no matching page; it shows " + page + ", the last to arrive was " + arrived(session));
        return page.get();
    }

    // The page the server sent last, whether or not the tab drew it yet.
    private static Optional<ExplorerPage> arrived(GameTestSession session) {
        return session.onClient(mc -> mc.gui.screen() instanceof CyberdeckScreen screen ? ((CyberdeckMenu) screen.getMenu()).explorerPage() : Optional.empty());
    }

    private static Optional<DevicesTabView> view(Minecraft mc) {
        return mc.gui.screen() instanceof CyberdeckScreen screen && screen.activeView().orElse(null) instanceof DevicesTabView view ? Optional.of(view) : Optional.empty();
    }

    // Selects a device's row with a click on its name, and waits until its actions show.
    private static void select(GameTestSession session, UUID id) {
        double[] cursor = session.onClient(mc -> {
            DevicesTabView view = view(mc).orElseThrow(() -> new AssertionError("The Devices tab is not open"));
            OptionalInt slot = view.slotOf(id);
            if (slot.isEmpty()) throw new AssertionError("The device " + id + " is not in view");
            TabbedMqttScreen screen = GameTestSession.openScreen(mc);
            double x = screen.getLeftPos() + 8 + 10;
            double y = screen.getTopPos() + DevicesTab.LIST_Y + slot.getAsInt() * DevicesTab.ROW_PITCH + 4;
            Window window = mc.getWindow();
            return new double[]{x * window.getScreenWidth() / window.getGuiScaledWidth(), y * window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        session.context().getInput().setCursorPos(cursor[0], cursor[1]);
        session.context().getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        session.check(session.awaitClient(mc -> view(mc).flatMap(DevicesTabView::selected).filter(id::equals).isPresent() && visibleButton(mc, "Locate"),
                ANSWER_TICKS), "a click selects the device " + id);
    }

    // Clicks the button of that label once it shows and may be pressed.
    private static void clickButton(GameTestSession session, String label) {
        session.check(session.awaitClient(mc -> buttonState(mc, label).equals(Optional.of(true)), ANSWER_TICKS), "the " + label + " button shows");
        session.clickWidget(widget -> widget instanceof Button && widget.active && widget.getMessage().getString().equals(label), "the " + label + " button");
    }

    // Whether a visible button of that label may be pressed; empty without one.
    private static Optional<Boolean> buttonState(Minecraft mc, String label) {
        return mc.gui.screen() == null ? Optional.empty() : mc.gui.screen().children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.visible && button.getMessage().getString().equals(label)).map(button -> button.active)
                .reduce(Boolean::logicalOr);
    }

    private static boolean visibleButton(Minecraft mc, String label) {
        return buttonState(mc, label).isPresent();
    }

    // The particles the client shows: the debug overlay's count ends in T and the total.
    private static int particles(Minecraft mc) {
        String count = mc.particleEngine.countParticles();
        try {
            return Integer.parseInt(count.substring(count.lastIndexOf('T') + 1).strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // A lamp's switch state as its block entity reports it.
    private static String state(GameTestSession session, BlockPos pos) {
        return session.onServer(s -> {
            StateWriter state = new StateWriter();
            if (s.overworld().getBlockEntity(pos) instanceof DeviceHost host) host.writeState(state);
            return state.json().has("state") ? state.json().get("state").getAsString() : "";
        });
    }

    private static UUID deviceId(GameTestSession session, BlockPos pos) {
        UUID id = session.device(pos, link -> link.id());
        if (id == null) throw new AssertionError("No device at " + pos);
        return id;
    }

    private static List<String> slugs(ExplorerPage page) {
        return page.rows().stream().map(ExplorerPage.Row::slug).toList();
    }

    private static Predicate<AbstractWidget> pageButton(String label) {
        return widget -> widget instanceof Button && widget.getMessage().getString().equals(label);
    }

    private static boolean previousActive(Minecraft mc) {
        return mc.gui.screen() != null && mc.gui.screen().children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                .anyMatch(widget -> pageButton("<").test(widget) && widget.active);
    }

    // Sets the player's game mode and waits until both sides know whether they may build.
    private static void gameMode(GameTestSession session, GameType mode) {
        session.consoleCommand("gamemode " + mode.getSerializedName() + " " + session.playerName());
        boolean mayBuild = mode != GameType.ADVENTURE && mode != GameType.SPECTATOR;
        session.awaitServer(s -> session.player(s).gameMode() == mode);
        session.context().waitFor(mc -> mc.player.getAbilities().mayBuild == mayBuild);
    }

    // The slug of the device at a position, once it is registered; null before.
    private static @Nullable String slug(MinecraftServer server, BlockPos pos) {
        if (!(server.overworld().getBlockEntity(pos) instanceof DeviceHost host) || host.deviceLink().id() == null) return null;
        DeviceRecord record = MineqttRuntime.of(server).registry().record(host.deviceLink().id());
        return record == null ? null : record.slug();
    }

    private static String intArray(UUID id) {
        return Arrays.toString(UUIDUtil.uuidToIntArray(id)).replace("[", "[I;").replace(" ", "");
    }
}

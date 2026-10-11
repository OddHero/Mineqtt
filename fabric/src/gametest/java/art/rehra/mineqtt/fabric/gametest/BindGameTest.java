package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.TwinView;
import art.rehra.mineqtt.device.ValueCodecs.Rgb;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.integrations.PermissionManager;
import art.rehra.mineqtt.items.ConfiguratorMode;
import art.rehra.mineqtt.items.CyberdeckDataUtil;
import art.rehra.mineqtt.items.MineqttItems;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceConfigPayload;
import art.rehra.mineqtt.network.MineqttNetworking.TwinSearchPayload;
import art.rehra.mineqtt.twin.core.TwinHealth;
import art.rehra.mineqtt.ui.TwinPickerScreen;
import art.rehra.mineqtt.ui.device.DeviceMenu;
import art.rehra.mineqtt.ui.device.LightControl;
import art.rehra.mineqtt.ui.device.TwinPicker;
import art.rehra.mineqtt.ui.device.TwinTab;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Binding from a real client (M3 plan M3-13, the {@code bind} suite, D4, D13, D19), against Home Assistant's catalog as a
 * broker probe publishes it. The player steps the Configurator to Bind and clicks a Smart Lamp: its screen opens on the
 * Twin tab with the entity picker. As a dedicated server's player who may not bind, the picker says so and lists
 * nothing. Made an operator (the test server is dedicated, so the integrated AUTO rule cannot be had), the player types
 * "m3", sees the labelled entities ranked and grouped by area with their control badges, and binds the first with Enter:
 * the lamp is named after the entity and follows it; the health line reads Live, then Unavailable; the source cycle
 * unbinds it. Copy-Paste carries a binding onto a second lamp. Without any entity the picker shows D4's help, with
 * {@code twin.allowedEntities} empty it says the twin is off and a bind is refused. A player who may only use a lamp sees
 * its Twin tab read-only and gets no answer to a search. Targets (M3-14): Bind on a Redstone Input opens the picker for
 * its target, which lists only entities Minecraft may control; the pick gives the Twin tab a target row, and the Input's
 * edges reach the probe as requests; on a Wall Switch the picker first asks Left or Right, each bound separately, and a
 * press of each button reaches the probe as its own request. Screenshots {@code bind_picker}, {@code bind_picker_empty},
 * {@code bind_picker_off}, {@code twin_tab_live}, {@code twin_tab_unavailable}, {@code twin_tab_read_only},
 * {@code bind_picker_target}, {@code bind_picker_step}, {@code twin_tab_target}, {@code twin_tab_wall_switch}, each read
 * for clipping. Needs a broker; without one everything is skipped.
 */
public final class BindGameTest implements FabricClientGameTest {
    private static final BlockPos STAND = new BlockPos(40, GameTestSession.GROUND_Y + 1, 0);
    private static final BlockPos LAMP_GROUND = new BlockPos(38, GameTestSession.GROUND_Y, 2);
    private static final BlockPos SECOND_LAMP_GROUND = new BlockPos(42, GameTestSession.GROUND_Y, 2);
    private static final BlockPos FOREIGN = new BlockPos(40, GameTestSession.GROUND_Y + 1, -2);
    private static final BlockPos INPUT_GROUND = new BlockPos(37, GameTestSession.GROUND_Y, -1);
    private static final BlockPos WALL = new BlockPos(40, GameTestSession.GROUND_Y + 2, 3);
    private static final String FLAG = "input_boolean.m3_flag";
    private static final String MOVIE = "scene.m3_movie";
    private static final String BEDTIME = "script.m3_bedtime";
    private static final java.util.UUID OTHER_OWNER = java.util.UUID.fromString("7e3f4a5b-6c7d-4e8f-9a0b-c1d2e3f4a5b7");
    private static final String KITCHEN = "light.m3_kitchen";
    private static final List<String> ALLOWED = List.of("light.*", "switch.*", "binary_sensor.*", "input_boolean.*", "scene.*", "script.*");
    private static final int ANSWER_TICKS = TwinFixture.ANSWER_TICKS;
    // Ample for a dropped payload to have been answered had it not been dropped.
    private static final int DROP_TICKS = 30;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "bind", BindGameTest::run);
    }

    private static void run(GameTestSession session) {
        if (session.broker().isEmpty()) {
            session.skip("the bind suite", GameTestSession.BROKER_ENV + " is not set");
            return;
        }
        session.teleport(STAND);
        try (TwinFixture twin = new TwinFixture(session, ALLOWED)) {
            BlockPos lamp = place(session, MineqttBlocks.RGB_LED_BLOCK.get(), LAMP_GROUND);
            BlockPos second = place(session, MineqttBlocks.RGB_LED_BLOCK.get(), SECOND_LAMP_GROUND);
            catalog(session, twin);
            notBinder(session, twin, lamp);
            bindWithPicker(session, twin, lamp);
            domainChips(session, lamp);
            commandTwins(session, twin, lamp);
            copyPaste(session, twin, lamp, second);
            emptyAndOff(session, twin, second);
            useOnly(session, twin);
            inputTarget(session, twin);
            wallSwitchTargets(session, twin);
        }
    }

    /** Home Assistant's catalog: labelled lights, a switch and a door contact in three areas, one light not for control. */
    private static void catalog(GameTestSession session, TwinFixture twin) {
        twin.catalog("light", "[" + entry(KITCHEN, "M3 Kitchen Ceiling", "Kitchen", true, "on", "{\"brightness\":200,\"rgb_color\":[255,180,90]}") + ","
                + entry("light.m3_porch", "M3 Porch", "Outside", false, "off", "{}") + ","
                + entry("light.hall", "Hall", "Hallway", true, "on", "{\"brightness\":255}") + "]");
        twin.catalog("switch", "[" + entry("switch.m3_fan", "M3 Fan", "Kitchen", true, "off", "{}") + "]");
        twin.catalog("binary_sensor", "[" + entry("binary_sensor.m3_door", "M3 Door", "Hallway", false, "off", "{}") + "]");
        // The targets' (M3-14): a flag, a scene and a script for control, a scene that is not.
        twin.catalog("input_boolean", "[" + entry(FLAG, "M3 Flag", "Hallway", true, "off", "{}") + "]");
        twin.catalog("scene", "[" + entry(MOVIE, "M3 Movie", "Living Room", true, "2026-10-11T08:00:00+00:00", "{}") + ","
                + entry("scene.m3_party", "M3 Party", "Living Room", false, "2026-10-11T08:00:00+00:00", "{}") + "]");
        twin.catalog("script", "[" + entry(BEDTIME, "M3 Bedtime", "Bedroom", true, "off", "{}") + "]");
        twin.catalogStatus(9);
        session.check(twin.awaitCatalog(), "the twin reads the catalog, which decides what may be bound");
    }

    /** A dedicated server's player, who may not bind: Bind mode opens the picker, which lists nothing and says why. */
    private static void notBinder(GameTestSession session, TwinFixture twin, BlockPos lamp) {
        twin.deop();
        session.hold(MineqttItems.Cyberdeck.get());
        setMode(session, ConfiguratorMode.BIND);
        TwinPickerScreen picker = clickToPick(session, lamp);
        session.check(picker.key().equals("twin.entity"), "Bind mode opens the lamp's picker for its source: " + picker.key());
        session.check(awaitResults(session, ""), "the picker searches at once");
        session.check(session.onClient(mc -> TwinPickerScreen.open(mc).flatMap(TwinPickerScreen::shownResults)
                        .map(results -> results.notice() == TwinPicker.Notice.NO_BIND && results.rows().isEmpty()).orElse(false)),
                "a player without mineqtt:twin.bind gets no rows, only why");
        session.context().getInput().pressKey(InputConstants.KEY_ESCAPE);
        session.check(session.awaitClient(mc -> mc.gui.screen() instanceof TabbedMqttScreen screen && TwinTab.ID.equals(screen.getMenu().getActiveTabId()),
                ANSWER_TICKS), "Escape goes back to the lamp's Twin tab");
        session.closeScreen();
        twin.op();
    }

    /** Type "m3", see the ranked rows, bind with Enter; the lamp is renamed and follows; Live, Unavailable, Unbind. */
    private static void bindWithPicker(GameTestSession session, TwinFixture twin, BlockPos lamp) {
        String defaultName = session.device(lamp, link -> link.name());
        clickToPick(session, lamp);
        session.check(awaitResults(session, ""), "the picker lists the catalog");
        session.check(session.onClient(mc -> TwinPickerScreen.open(mc).map(TwinPickerScreen::renames).orElse(false)),
                "a lamp with its default name is named after what it binds by default");
        session.clickWidget(widget -> widget instanceof EditBox, "the search field");
        session.context().getInput().typeChars("m3");
        session.check(awaitResults(session, "m3"), "typing m3 searches for it after the debounce");
        List<String> shown = session.onClient(mc -> TwinPickerScreen.open(mc).map(TwinPickerScreen::shownEntities).orElse(List.of()));
        // D4: the lamp's domains in order (light first), prefix matches; then grouped by area in the order of each area's best
        // row; scenes and scripts are no lamp domains.
        session.check(shown.equals(List.of(KITCHEN, "switch.m3_fan", "light.m3_porch", FLAG, "binary_sensor.m3_door")),
                "the labelled entities matching m3, ranked and grouped by area, the hall left out: " + shown);
        session.check(session.onClient(mc -> TwinPickerScreen.open(mc).flatMap(TwinPickerScreen::shownResults)
                        .map(results -> results.rows().stream().filter(row -> row.entityId().equals(KITCHEN)).findFirst()
                                .map(row -> row.control() && row.area().equals("Kitchen") && row.rgb().isPresent() && row.name().equals("M3 Kitchen Ceiling"))
                                .orElse(false)
                                && results.rows().stream().filter(row -> row.entityId().equals("light.m3_porch")).findFirst().map(row -> !row.control()).orElse(false))
                        .orElse(false)),
                "a row carries its name, area, colour and control badge; the porch light is not for control");
        session.screenshot("bind_picker");

        session.context().getInput().pressKey(InputConstants.KEY_RETURN);
        session.check(session.awaitClient(mc -> mc.gui.screen() instanceof TabbedMqttScreen, ANSWER_TICKS), "Enter binds and goes back to the Twin tab");
        session.check(session.await(() -> twin.source(lamp).equals(Optional.of(KITCHEN)), ANSWER_TICKS), "the lamp is bound to " + KITCHEN);
        session.check(session.await(() -> "M3 Kitchen Ceiling".equals(session.device(lamp, link -> link.name())), ANSWER_TICKS),
                "and named after it (was " + defaultName + "): " + session.device(lamp, link -> link.name()));
        session.check(session.await(() -> LightControl.light(twin.state(lamp)).on() && LightControl.light(twin.state(lamp)).brightness() == 200
                        && LightControl.light(twin.state(lamp)).color().equals(new Rgb(255, 180, 90)), ANSWER_TICKS),
                "the lamp follows the catalog's light: on, 200, warm white (" + twin.state(lamp) + ")");

        twin.live(KITCHEN, "on");
        session.check(awaitTwinView(session, view -> view.health().equals(Optional.of(TwinHealth.LIVE))), "a live value: the health line reads Live");
        session.check(session.awaitClient(mc -> label(mc, IS_SOURCE).equals("Home Assistant")
                && label(mc, isButton("M3 Kitchen")).equals("M3 Kitchen Ceiling"), ANSWER_TICKS), "the tab names the source and its entity: " + labels(session));
        session.screenshot("twin_tab_live");
        twin.live(KITCHEN, "unavailable");
        session.check(awaitTwinView(session, view -> view.health().equals(Optional.of(TwinHealth.UNAVAILABLE))), "unavailable: the health line says so");
        session.check(LightControl.light(twin.state(lamp)).on(), "and the lamp holds its last value");
        session.screenshot("twin_tab_unavailable");
        twin.live(KITCHEN, "on");

        session.clickWidget(isButton("Home Assistant"), "the source cycle");
        session.check(session.await(() -> twin.source(lamp).isEmpty(), ANSWER_TICKS), "the source cycle's This block unbinds the lamp");
        session.check(session.awaitClient(mc -> label(mc, IS_SOURCE).equals("This block"), ANSWER_TICKS), "and the tab shows it unbound");
        session.closeScreen();
    }

    /**
     * W6 follow-up (4): the domain chip searches after the debounce, as typing does. Two steps within the debounce send one
     * search, for the chip the picker rests on, and the list shows that answer alone (its domain, its rows); stepping back
     * to All shows every domain again.
     */
    private static void domainChips(GameTestSession session, BlockPos lamp) {
        clickToPick(session, lamp);
        session.check(awaitResults(session, ""), "the picker lists the catalog");
        int before = session.onClient(mc -> TwinPickerScreen.open(mc).orElseThrow().searchesSent());
        // Both steps in one client frame, so they surely land within the debounce, however slow the frames.
        String rests = session.onClient(mc -> {
            stepChip(mc);
            stepChip(mc);
            return TwinPickerScreen.open(mc).orElseThrow().domain();
        });
        session.check(!rests.isEmpty(), "two steps leave the chip on a domain");
        session.check(awaitDomain(session, rests), "the picker shows the answer for the chip it rests on (" + rests + ")");
        session.context().waitTicks(DROP_TICKS);
        int sent = session.onClient(mc -> TwinPickerScreen.open(mc).orElseThrow().searchesSent()) - before;
        session.check(sent == 1, "two chip steps within the debounce send exactly one search, not one per step: " + sent);
        List<String> shown = session.onClient(mc -> TwinPickerScreen.open(mc).map(TwinPickerScreen::shownEntities).orElse(List.of()));
        session.check(!shown.isEmpty() && shown.stream().allMatch(entity -> entity.startsWith(rests + ".")),
                "the list shows only " + rests + " entities: " + shown);
        // Around the chips back to All: every domain again.
        for (int step = 0; step < 8 && !session.onClient(mc -> TwinPickerScreen.open(mc).orElseThrow().domain().isEmpty()); step++) {
            session.onClient(mc -> {
                stepChip(mc);
                return null;
            });
        }
        session.check(awaitDomain(session, ""), "back on All the picker shows the answer for every domain");
        List<String> all = session.onClient(mc -> TwinPickerScreen.open(mc).map(TwinPickerScreen::shownEntities).orElse(List.of()));
        session.check(all.stream().anyMatch(entity -> !entity.startsWith(rests + ".")), "and lists more than " + rests + ": " + all);
        session.context().getInput().pressKey(InputConstants.KEY_ESCAPE);
        session.check(session.awaitClient(mc -> mc.gui.screen() instanceof TabbedMqttScreen, ANSWER_TICKS), "Escape goes back to the Twin tab");
        session.closeScreen();
    }

    // One click on the open picker's domain chip, the button on the search field's row (client thread).
    private static void stepChip(Minecraft mc) {
        TwinPickerScreen picker = TwinPickerScreen.open(mc).orElseThrow();
        int row = picker.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow().getY();
        Button chip = picker.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.visible && button.getY() == row).findFirst().orElseThrow(() -> new AssertionError("No domain chip"));
        if (!chip.active) throw new AssertionError("The domain chip is inactive: the lamp has one domain");
        MouseButtonEvent click = new MouseButtonEvent(chip.getX() + chip.getWidth() / 2.0, chip.getY() + chip.getHeight() / 2.0,
                new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
        picker.mouseClicked(click, false);
        picker.mouseReleased(click);
    }

    // Waits until the open picker shows the answer to its current query for this domain chip.
    private static boolean awaitDomain(GameTestSession session, String domain) {
        return session.awaitClient(mc -> TwinPickerScreen.open(mc)
                .filter(picker -> picker.domain().equals(domain))
                .flatMap(TwinPickerScreen::shownResults)
                .map(results -> results.domain().equals(domain)).orElse(false), ANSWER_TICKS);
    }

    /**
     * The command twins do what the tab and the picker do (the plan's H step): {@code /mineqtt twin entities} lists what
     * the picker lists, {@code devices setting <lamp> twin.entity} binds and {@code ""} unbinds, and
     * {@code devices setting <lamp> twin.resume press} is Resume, typed by the player (an operator here).
     */
    private static void commandTwins(GameTestSession session, TwinFixture twin, BlockPos lamp) {
        String slug = twin.slug(lamp);
        int mark = ChatLog.mark();
        session.playerCommand("mineqtt twin entities m3");
        session.check(session.awaitChat(mark, line -> line.text().contains(KITCHEN) && line.text().contains("M3 Kitchen Ceiling"), ANSWER_TICKS).isPresent(),
                "/mineqtt twin entities m3 lists what the picker lists (messages: " + ChatLog.since(mark) + ")");
        session.playerCommand("mineqtt devices setting " + slug + " twin.entity light.m3_porch");
        session.check(session.await(() -> twin.source(lamp).equals(Optional.of("light.m3_porch")), ANSWER_TICKS), "devices setting twin.entity binds as a pick does");
        mark = ChatLog.mark();
        session.playerCommand("mineqtt devices setting " + slug + " twin.resume press");
        session.check(session.awaitChat(mark, line -> line.text().contains("not paused"), ANSWER_TICKS).isPresent(),
                "devices setting twin.resume press is Resume (messages: " + ChatLog.since(mark) + ")");
        session.playerCommand("mineqtt devices setting " + slug + " twin.entity \"\"");
        session.check(session.await(() -> twin.source(lamp).isEmpty(), ANSWER_TICKS), "and \"\" unbinds as This block does");
    }

    /** Copy-Paste carries the binding from one lamp onto another, judged for the player. */
    private static void copyPaste(GameTestSession session, TwinFixture twin, BlockPos lamp, BlockPos second) {
        session.consoleCommand("mineqtt devices bind " + twin.slug(lamp) + " " + KITCHEN);
        session.check(twin.source(lamp).equals(Optional.of(KITCHEN)), "the first lamp is bound again");
        setMode(session, ConfiguratorMode.COPY_PASTE);
        session.aim(lamp);
        session.pressUse();
        session.context().waitTicks(5);
        session.aim(second);
        session.pressUse();
        session.check(session.await(() -> twin.source(second).equals(Optional.of(KITCHEN)), ANSWER_TICKS), "Copy-Paste binds the second lamp to " + KITCHEN);
        session.aimAtSky();
        session.pressUse();
        session.consoleCommand("mineqtt devices bind " + twin.slug(lamp) + " none");
        session.consoleCommand("mineqtt devices bind " + twin.slug(second) + " none");
        session.check(twin.source(lamp).isEmpty() && twin.source(second).isEmpty(), "both unbound again");
    }

    /** Without any entity the picker shows D4's help; with twin.allowedEntities empty the twin is off and a bind is refused. */
    private static void emptyAndOff(GameTestSession session, TwinFixture twin, BlockPos lamp) {
        setMode(session, ConfiguratorMode.BIND);
        twin.allow(List.of("light.nothing_*"));
        clickToPick(session, lamp);
        session.check(awaitResults(session, ""), "the picker answers");
        session.check(session.onClient(mc -> TwinPickerScreen.open(mc).flatMap(TwinPickerScreen::shownResults)
                .map(results -> results.notice() == TwinPicker.Notice.EMPTY && results.rows().isEmpty()).orElse(false)), "no entity at all: D4's help");
        session.screenshot("bind_picker_empty");
        session.context().getInput().pressKey(InputConstants.KEY_ESCAPE);
        session.closeScreen();

        twin.allow(List.of());
        clickToPick(session, lamp);
        session.check(awaitResults(session, ""), "the picker answers");
        session.check(session.onClient(mc -> TwinPickerScreen.open(mc).flatMap(TwinPickerScreen::shownResults)
                        .map(results -> results.notice() == TwinPicker.Notice.OFF && results.detail().contains("twin.allowedEntities")).orElse(false)),
                "with twin.allowedEntities empty the picker says the twin is off and why");
        session.screenshot("bind_picker_off");
        session.context().getInput().pressKey(InputConstants.KEY_ESCAPE);
        session.check(session.awaitClient(mc -> mc.gui.screen() instanceof TabbedMqttScreen, ANSWER_TICKS), "back on the Twin tab");
        int mark = ChatLog.mark();
        session.send(new DeviceConfigPayload(lamp, "twin.entity", StringTag.valueOf(KITCHEN)));
        session.check(session.awaitChat(mark, line -> !line.actionBar() && line.text().contains("twin.allowedEntities"), ANSWER_TICKS).isPresent(),
                "a bind sent anyway is refused, naming the reason (messages: " + ChatLog.since(mark) + ")");
        session.check(session.awaitClient(mc -> menu(mc).refusal().filter(refusal -> refusal.key().equals("twin.entity")).isPresent(), ANSWER_TICKS),
                "and on the tab");
        session.check(twin.source(lamp).isEmpty(), "the lamp stays unbound");
        session.closeScreen();
        twin.allow(ALLOWED);
    }

    /**
     * A player who may only use another owner's lamp (a claim that trusts them to use it, not to configure it) sees its
     * Twin tab read-only, and a search sent anyway is dropped by the guard. (A foreign lamp cannot be bound here: its owner
     * is no operator of this dedicated server; a bound view without details is checked by verifySecurityGuards.)
     */
    private static void useOnly(GameTestSession session, TwinFixture twin) {
        BlockPos lamp = FOREIGN;
        session.consoleCommand("setblock " + lamp.getX() + " " + lamp.getY() + " " + lamp.getZ() + " mineqtt:rgb_led_block{mineqtt:{v:2,id:"
                + intArray(java.util.UUID.randomUUID()) + ",type:\"mineqtt:smart_lamp\",name:\"Neighbour Lamp\",owner:" + intArray(OTHER_OWNER)
                + ",ownerName:\"Someone\",access:\"claim\"}}");
        session.awaitServer(s -> s.overworld().getBlockEntity(lamp) instanceof DeviceHost host && host.deviceLink().attached());
        twin.deop();
        PermissionManager installed = MineQTT.permissionManager;
        MineQTT.permissionManager = new PermissionManager() {
            @Override
            public boolean canInteract(ServerPlayer player, BlockPos pos, MineqttPermission permission) {
                return permission == MineqttPermission.USE;
            }
        };
        try {
            session.hold(Items.AIR);
            session.openBlock(lamp);
            session.check(session.screenView().map(view -> !view.grants().configure() && view.grants().use()).orElse(false),
                    "the player may only use the lamp");
            session.check(session.clickTab(TwinTab.ID), "its Twin tab opens");
            session.check(awaitTwinView(session, view -> !view.details() && !view.mayBind()), "the view grants no details and no binding");
            session.check(session.awaitTitleNote(Optional.of("Read only"), ANSWER_TICKS), "the title row says Read only");
            session.check(session.onClient(mc -> !active(mc, IS_SOURCE) && !active(mc, isButton("Pick"))), "the source and the pick are read-only");
            session.screenshot("twin_tab_read_only");
            session.send(new TwinSearchPayload(lamp, "twin.entity", "", ""));
            session.context().waitTicks(DROP_TICKS);
            session.check(session.onClient(mc -> menu(mc).twinResults().isEmpty()), "a search sent anyway gets no answer: the guard drops it");
            session.closeScreen();
        } finally {
            MineQTT.permissionManager = installed;
        }
        twin.op();
    }

    private static String intArray(java.util.UUID id) {
        long most = id.getMostSignificantBits();
        long least = id.getLeastSignificantBits();
        return "[I;" + (int) (most >> 32) + "," + (int) most + "," + (int) (least >> 32) + "," + (int) least + "]";
    }

    /**
     * M3-14: Bind on a Redstone Input opens its target's picker, which lists only what Minecraft may control; the pick
     * gives the Twin tab its target row; the Input's edges reach the probe as requests.
     */
    private static void inputTarget(GameTestSession session, TwinFixture twin) {
        BlockPos input = place(session, MineqttBlocks.PUBLISHER_BLOCK.get(), INPUT_GROUND);
        session.hold(MineqttItems.Cyberdeck.get());
        setMode(session, ConfiguratorMode.BIND);
        TwinPickerScreen picker = clickToPick(session, input);
        session.check(picker.key().equals("twin.target") && !picker.choosesTarget(), "Bind on the Input opens the picker for its one target");
        session.check(awaitResults(session, ""), "the picker lists the catalog");
        List<String> shown = session.onClient(mc -> TwinPickerScreen.open(mc).map(TwinPickerScreen::shownEntities).orElse(List.of()));
        session.check(shown.containsAll(List.of(KITCHEN, "light.hall", "switch.m3_fan", FLAG, MOVIE, BEDTIME))
                        && !shown.contains("light.m3_porch") && !shown.contains("scene.m3_party") && !shown.contains("binary_sensor.m3_door"),
                "a target lists only the entities Home Assistant lets Minecraft control (the control filter), no sensor: " + shown);
        session.check(!session.onClient(mc -> TwinPickerScreen.open(mc).map(TwinPickerScreen::renames).orElse(true)), "a target renames nothing");
        session.screenshot("bind_picker_target");
        session.clickWidget(widget -> widget instanceof EditBox, "the search field");
        session.context().getInput().typeChars("flag");
        session.check(awaitResults(session, "flag"), "typing flag searches for it");
        session.check(session.onClient(mc -> TwinPickerScreen.open(mc).map(TwinPickerScreen::shownEntities).orElse(List.of())).equals(List.of(FLAG)),
                "only the flag matches");
        session.context().getInput().pressKey(InputConstants.KEY_RETURN);
        session.check(session.awaitClient(mc -> mc.gui.screen() instanceof TabbedMqttScreen, ANSWER_TICKS), "Enter binds and goes back to the Twin tab");
        session.check(session.await(() -> FLAG.equals(target(session, input, "powered")), ANSWER_TICKS), "the Input's target switches " + FLAG);
        session.check(session.awaitClient(mc -> label(mc, isButton("Target:")).equals("Target: M3 Flag"), ANSWER_TICKS),
                "the Twin tab shows the target row: " + labels(session));
        session.screenshot("twin_tab_target");
        session.closeScreen();

        try (BrokerProbe.Listener requests = twin.requests()) {
            BlockPos power = input.west();
            session.consoleCommand("setblock " + power.getX() + " " + power.getY() + " " + power.getZ() + " minecraft:redstone_block");
            Optional<com.google.gson.JsonObject> on = twin.awaitRequest(requests, ANSWER_TICKS);
            session.check(on.map(json -> json.get("action").getAsString()).equals(Optional.of("input_boolean.turn_on"))
                            && on.map(json -> json.getAsJsonObject("target").get("entity_id").getAsString()).equals(Optional.of(FLAG)),
                    "a rising edge asks Home Assistant to turn the flag on: " + on);
            session.check(on.map(json -> !json.getAsJsonObject("origin").has("player")).orElse(false), "naming no player (M3-14 decision 5): " + on);
            twin.answer(on.orElseThrow(), "accepted");
            twin.live(FLAG, "on");
            session.context().waitTicks(10);
            session.consoleCommand("setblock " + power.getX() + " " + power.getY() + " " + power.getZ() + " minecraft:air");
            Optional<com.google.gson.JsonObject> off = twin.awaitRequest(requests, ANSWER_TICKS);
            session.check(off.map(json -> json.get("action").getAsString()).equals(Optional.of("input_boolean.turn_off")), "a falling edge turns it off: " + off);
        }
        session.consoleCommand("mineqtt devices bind " + twin.slug(input) + " target target none");
        session.check(target(session, input, "powered") == null, "the command twin removes the target");
    }

    /**
     * M3-14: on a Wall Switch the picker asks Left or Right first; each button is bound separately, and a press of each
     * reaches the probe as its own request.
     */
    private static void wallSwitchTargets(GameTestSession session, TwinFixture twin) {
        session.consoleCommand("setblock " + WALL.getX() + " " + WALL.getY() + " " + WALL.getZ() + " minecraft:stone");
        session.hold(MineqttBlocks.LIGHT_REMOTE_BLOCK.get().asItem());
        session.aim(WALL, net.minecraft.core.Direction.NORTH);
        session.pressUse();
        BlockPos wall = WALL.north();
        session.awaitServer(s -> s.overworld().getBlockEntity(wall) instanceof DeviceHost host && host.deviceLink().attached());
        session.hold(MineqttItems.Cyberdeck.get());
        setMode(session, ConfiguratorMode.BIND);

        TwinPickerScreen picker = clickToPick(session, wall);
        session.check(picker.choosesTarget(), "Bind on the Wall Switch first asks which button");
        session.screenshot("bind_picker_step");
        session.clickWidget(isButton("Left"), "the Left button");
        session.check(session.awaitClient(mc -> TwinPickerScreen.open(mc).map(open -> open.key().equals("twin.left")).orElse(false), ANSWER_TICKS),
                "Left picks for twin.left");
        session.check(awaitResults(session, ""), "the picker lists the catalog");
        session.clickWidget(widget -> widget instanceof EditBox, "the search field");
        session.context().getInput().typeChars("movie");
        session.check(awaitResults(session, "movie"), "typing movie searches for it");
        session.context().getInput().pressKey(InputConstants.KEY_RETURN);
        session.check(session.await(() -> MOVIE.equals(target(session, wall, "left")), ANSWER_TICKS), "the left button switches " + MOVIE);

        session.closeScreen();
        picker = clickToPick(session, wall);
        session.check(picker.choosesTarget(), "again it asks which button");
        session.clickWidget(isButton("Right"), "the Right button");
        session.check(awaitResults(session, ""), "the picker lists the catalog");
        session.clickWidget(widget -> widget instanceof EditBox, "the search field");
        session.context().getInput().typeChars("bedtime");
        session.check(awaitResults(session, "bedtime"), "typing bedtime searches for it");
        session.context().getInput().pressKey(InputConstants.KEY_RETURN);
        session.check(session.await(() -> BEDTIME.equals(target(session, wall, "right")), ANSWER_TICKS), "the right button switches " + BEDTIME);
        session.check(MOVIE.equals(target(session, wall, "left")), "and the left one still " + MOVIE + ": bound separately");
        session.check(session.awaitClient(mc -> label(mc, isButton("Left:")).equals("Left: M3 Movie") && label(mc, isButton("Right:")).equals("Right: M3 Bedtime"),
                ANSWER_TICKS), "the Twin tab shows both target rows: " + labels(session));
        session.screenshot("twin_tab_wall_switch");
        session.closeScreen();

        try (BrokerProbe.Listener requests = twin.requests()) {
            String slug = twin.slug(wall);
            session.consoleCommand("mineqtt devices action " + slug + " press_left PRESS");
            Optional<com.google.gson.JsonObject> left = twin.awaitRequest(requests, ANSWER_TICKS);
            session.check(left.map(json -> json.get("action").getAsString() + " " + json.getAsJsonObject("target").get("entity_id").getAsString())
                    .equals(Optional.of("scene.turn_on " + MOVIE)), "a press of the left button activates the scene: " + left);
            session.consoleCommand("mineqtt devices action " + slug + " press_right PRESS");
            Optional<com.google.gson.JsonObject> right = twin.awaitRequest(requests, ANSWER_TICKS);
            session.check(right.map(json -> json.get("action").getAsString() + " " + json.getAsJsonObject("target").get("entity_id").getAsString())
                    .equals(Optional.of("script.turn_on " + BEDTIME)), "a press of the right button runs the script: " + right);
            session.check(twin.awaitRequest(requests, 20).isEmpty(), "one request each");

            // Wave 6 open finding 2: presses beyond the device's rate (4 a second) are refused, never held (a scene is no
            // state); the open Twin tab counts them for everyone and names the last one's entity for its owner.
            session.hold(Items.AIR);
            session.openBlock(wall);
            session.check(session.clickTab(TwinTab.ID), "the Wall Switch's Twin tab opens");
            session.check(awaitTwinView(session, view -> view.rateRefused() == 0), "no request was refused by the rate yet");
            for (int i = 0; i < 5; i++) session.consoleCommand("mineqtt devices action " + slug + " press_left PRESS");
            session.check(awaitTwinView(session, view -> view.rateRefused() >= 1 && view.rateLast().contains(MOVIE)),
                    "the open tab counts the presses the rate refused and names the last one's entity: "
                            + session.onClient(mc -> menu(mc).twinView().map(view -> view.rateRefused() + " " + view.rateLast()).orElse("")));
            session.screenshot("twin_tab_rate");
            session.closeScreen();
            session.hold(MineqttItems.Cyberdeck.get());
            session.consoleCommand("mineqtt devices bind " + slug + " target left none");
            session.consoleCommand("mineqtt devices bind " + slug + " target right none");
        }
    }

    // The entity the device at pos switches from an out-port, or null.
    private static String target(GameTestSession session, BlockPos pos, String port) {
        return session.device(pos, link -> link.twin() == null ? null
                : link.twin().targets().get(port) instanceof art.rehra.mineqtt.flow.core.Endpoint.HaEntityRef end ? end.entityId() : null);
    }

    // ===== Helpers =====

    private static String entry(String id, String name, String area, boolean control, String state, String attributes) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"area\":\"" + area + "\",\"control\":" + control + ",\"state\":\"" + state
                + "\",\"last_updated\":\"" + java.time.Instant.now() + "\",\"attributes\":" + attributes + "}";
    }

    private static BlockPos place(GameTestSession session, Block block, BlockPos ground) {
        BlockPos pos = session.place(block, ground);
        session.awaitServer(s -> s.overworld().getBlockEntity(pos) instanceof DeviceHost host && host.deviceLink().attached());
        return pos;
    }

    /** A Bind mode click on the device at {@code pos} within reach; waits for its picker. */
    private static TwinPickerScreen clickToPick(GameTestSession session, BlockPos pos) {
        session.aim(pos);
        session.pressUse();
        session.context().waitForScreen(TwinPickerScreen.class);
        return session.onClient(mc -> TwinPickerScreen.open(mc).orElseThrow());
    }

    private static void setMode(GameTestSession session, ConfiguratorMode mode) {
        session.onServer(s -> {
            CyberdeckDataUtil.setMode(session.player(s).getMainHandItem(), mode);
            return null;
        });
        session.context().waitFor(mc -> CyberdeckDataUtil.mode(mc.player.getMainHandItem()) == mode);
    }

    // Waits until the open picker shows the answer to this query.
    private static boolean awaitResults(GameTestSession session, String query) {
        return session.awaitClient(mc -> TwinPickerScreen.open(mc).flatMap(TwinPickerScreen::shownResults)
                .map(results -> results.query().equals(query)).orElse(false), ANSWER_TICKS);
    }

    private static boolean awaitTwinView(GameTestSession session, Predicate<TwinView> matches) {
        return session.awaitClient(mc -> mc.gui.screen() instanceof TabbedMqttScreen screen && screen.getMenu() instanceof DeviceMenu menu
                && menu.twinView().filter(matches).isPresent(), ANSWER_TICKS);
    }

    private static DeviceMenu menu(Minecraft mc) {
        return (DeviceMenu) GameTestSession.openScreen(mc).getMenu();
    }

    // The Twin tab's source cycle: This block, Home Assistant or MQTT device.
    private static final Predicate<AbstractWidget> IS_SOURCE = widget -> widget instanceof Button
            && List.of("This block", "Home Assistant", "MQTT device").contains(widget.getMessage().getString());

    private static Predicate<AbstractWidget> isButton(String prefix) {
        return widget -> widget instanceof Button && widget.getMessage().getString().startsWith(prefix);
    }

    private static boolean active(Minecraft mc, Predicate<AbstractWidget> which) {
        return GameTestSession.openScreen(mc).children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                .filter(widget -> widget.visible && which.test(widget)).findFirst().map(widget -> widget.active).orElse(false);
    }

    private static String label(Minecraft mc, Predicate<AbstractWidget> which) {
        return GameTestSession.openScreen(mc).children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                .filter(widget -> widget.visible && which.test(widget)).findFirst().map(widget -> widget.getMessage().getString()).orElse("");
    }

    private static List<String> labels(GameTestSession session) {
        return session.onClient(mc -> GameTestSession.openScreen(mc).children().stream().filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast).filter(widget -> widget.visible).map(widget -> widget.getMessage().getString()).toList());
    }

}

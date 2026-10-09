package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.RgbLedBlock;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.DeviceGroup;
import art.rehra.mineqtt.device.DeviceGroups;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.GroupRules;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.integrations.PermissionManager;
import art.rehra.mineqtt.items.MineqttItems;
import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceGroupPayload;
import art.rehra.mineqtt.runtime.TopicScheme;
import art.rehra.mineqtt.security.AccessMode;
import art.rehra.mineqtt.ui.GroupNameScreen;
import art.rehra.mineqtt.ui.device.DeviceMenu;
import art.rehra.mineqtt.ui.device.GeneralTab;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The Configurator's Group and Copy-Paste modes and the General tab's group pages from a real client (M2 plan §4.2
 * M2-17). The non-op player steps the Configurator to Group mode, clicks a Smart Lamp that is on, then a second lamp: the
 * prompt asks for the new group's name, and Create makes it with both lamps, the second taking the first's state; a click
 * on a third lamp adds it too. With a broker, Home Assistant sees one light: the group's discovery is retained and the
 * exposed first lamp's is gone. Clicking the first lamp again, or into the air, stops; another owner's private lamp and an
 * output are refused. On the first lamp's General tab the group button opens the group page, where the group is renamed
 * (its state moves to the new topic) and, opped, its members are shown to Home Assistant and hidden again. The third lamp
 * leaves, joins again from the join page, makes a group of its own, which Home Assistant does not discover under OPT_IN
 * as nobody exposed that lamp, and moves back, which removes the emptied group. For a
 * player who may only use the lamps the page is read-only and a change sent anyway is dropped. In Copy-Paste mode an
 * output's channel, payload, settings, exposure and access mode are copied and pasted onto another output: the non-op
 * player gets the channel and settings but not the exposure and the access mode, which an op then pastes too; a lamp is
 * refused as of another type.
 */
public final class GroupGameTest implements FabricClientGameTest {
    private static final int Y = GameTestSession.GROUND_Y + 1;
    private static final BlockPos STAND = new BlockPos(0, Y, 0);
    private static final BlockPos KITCHEN = new BlockPos(3, Y, 0);
    private static final BlockPos HALL = new BlockPos(3, Y, 3);
    private static final BlockPos PORCH = new BlockPos(0, Y, 3);
    private static final BlockPos PRIVATE = new BlockPos(-3, Y, 3);
    private static final BlockPos SOURCE = new BlockPos(-3, Y, 0);
    private static final BlockPos TARGET = new BlockPos(0, Y, -3);
    private static final UUID OTHER_OWNER = UUID.fromString("7e2d3c4b-5a69-4b78-8c9d-0e1f2a3b4c5d");
    private static final String CHANNEL = "group_gametest";
    private static final int ANSWER_TICKS = 40;
    // Ample for a dropped payload to have done something if it had not been dropped.
    private static final int DROP_TICKS = 20;
    private static final int BROKER_ATTEMPTS = 5;
    private static final int DISCOVERY_TICKS = 400;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "group", GroupGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        setblock(session, KITCHEN, "mineqtt:rgb_led_block");
        setblock(session, HALL, "mineqtt:rgb_led_block");
        setblock(session, PORCH, "mineqtt:rgb_led_block");
        setblock(session, PRIVATE, "mineqtt:rgb_led_block{mineqtt:{v:2,id:" + intArray(UUID.randomUUID())
                + ",type:\"mineqtt:smart_lamp\",owner:" + intArray(OTHER_OWNER) + ",ownerName:\"Someone\",access:\"private\"}}");
        setblock(session, SOURCE, "mineqtt:redstone_emitter_block");
        setblock(session, TARGET, "mineqtt:redstone_emitter_block");
        for (BlockPos pos : List.of(KITCHEN, HALL, PORCH, PRIVATE, SOURCE, TARGET)) {
            session.awaitServer(s -> s.overworld().getBlockEntity(pos) instanceof DeviceHost host && host.deviceLink().attached());
        }
        rename(session, KITCHEN, "Kitchen Lamp");
        rename(session, HALL, "Hall Lamp");
        rename(session, PORCH, "Porch Lamp");
        rename(session, PRIVATE, "Neighbour Lamp");
        rename(session, SOURCE, "Source Output");
        rename(session, TARGET, "Target Output");
        // The kitchen lamp is on at half brightness, the others off; it is exposed, so Home Assistant has it until it is grouped.
        session.consoleCommand("mineqtt devices set " + slug(session, KITCHEN) + " {\"state\":\"ON\",\"brightness\":128}");
        session.consoleCommand("mineqtt devices expose " + slug(session, KITCHEN) + " on");
        session.check(session.await(() -> lampLevel(session, KITCHEN) > 0 && lampLevel(session, HALL) == 0 && lampLevel(session, PORCH) == 0, ANSWER_TICKS),
                "the kitchen lamp is on, the hall and porch lamps are off");
        // Discovery goes out once the bridge is connected, which a fresh world may not be yet.
        if (session.broker().isPresent()) {
            session.check(session.await(() -> record(session, KITCHEN).discoveryHash() != null, DISCOVERY_TICKS), "the kitchen lamp's discovery is published");
        }
        brokerCheck(session, discoveryTopic(record(session, KITCHEN).objectId()), Optional::isPresent,
                "the exposed kitchen lamp is discovered before it is grouped");

        session.hold(MineqttItems.Cyberdeck.get());
        session.aimAtSky();
        for (String mode : List.of("Link", "Group")) {
            int mark = ChatLog.mark();
            session.sneakUse();
            session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Configurator mode: " + mode), ANSWER_TICKS).isPresent(),
                    "sneak + right-click into the air steps the Configurator to " + mode + " mode (messages: " + ChatLog.since(mark) + ")");
        }
        groupMode(session);
        groupPage(session);
        membersAndRename(session);
        leaveJoinCreate(session);
        readOnly(session);
        copyPaste(session);
    }

    /**
     * Kitchen lamp, then hall lamp: the prompt asks for a name and Create makes the group with both, the hall lamp taking
     * the kitchen lamp's state; the porch lamp joins with a third click. Home Assistant gets the group, not the members.
     */
    private static void groupMode(GameTestSession session) {
        int mark = ChatLog.mark();
        click(session, KITCHEN);
        session.check(session.awaitChat(mark, line -> line.actionBar()
                        && line.text().equals("Grouping with Kitchen Lamp: click the devices to add, or Kitchen Lamp again to stop"), ANSWER_TICKS).isPresent(),
                "a click on the kitchen lamp picks it to group with (messages: " + ChatLog.since(mark) + ")");
        click(session, HALL);
        session.check(session.awaitClient(mc -> mc.gui.screen() instanceof GroupNameScreen, ANSWER_TICKS), "a click on the hall lamp asks for the group's name");
        session.check(session.onClient(mc -> ((GroupNameScreen) mc.gui.screen()).prompt().source().equals("Kitchen Lamp")
                && ((GroupNameScreen) mc.gui.screen()).prompt().target().equals("Hall Lamp")), "the prompt names both lamps");
        session.check(!buttonActive(session, "Create"), "Create waits for a name");
        session.context().getInput().typeChars("Living Room");
        session.check(session.awaitClient(mc -> mc.gui.screen() instanceof GroupNameScreen screen && screen.name().equals("Living Room"), ANSWER_TICKS),
                "the name field has the focus and takes the typed name");
        session.screenshot("group_name_prompt");
        mark = ChatLog.mark();
        session.clickWidget(isButton("Create"), "the prompt's Create button");
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Created group Living Room with Kitchen Lamp and Hall Lamp"),
                ANSWER_TICKS).isPresent(), "Create makes the group with both lamps (messages: " + ChatLog.since(mark) + ")");
        session.check(session.awaitClient(mc -> mc.gui.screen() == null, ANSWER_TICKS), "the prompt closes");
        DeviceGroup group = group(session, "living_room").orElse(null);
        session.check(group != null && group.members().equals(Set.of(id(session, KITCHEN), id(session, HALL))) && group.name().equals("Living Room"),
                "group living_room holds the kitchen and hall lamps");
        session.check(session.await(() -> lampLevel(session, HALL) == lampLevel(session, KITCHEN) && lampLevel(session, HALL) > 0, ANSWER_TICKS),
                "the hall lamp took the kitchen lamp's state: on at its brightness");

        mark = ChatLog.mark();
        click(session, PORCH);
        session.check(session.awaitChat(mark, line -> line.actionBar()
                        && line.text().equals("Grouped: Porch Lamp joined Living Room and took over its state"), ANSWER_TICKS).isPresent(),
                "a third click puts the porch lamp into the group (messages: " + ChatLog.since(mark) + ")");
        session.check(session.await(() -> lampLevel(session, PORCH) == lampLevel(session, KITCHEN), ANSWER_TICKS), "the porch lamp took the group's state too");
        session.check(sameBrightness(session, KITCHEN, HALL, PORCH), "all three lamps report the same light");
        session.screenshot("group_three_lamps");

        // Home Assistant shows one light: the group is discovered, the kitchen lamp, exposed on its own, is hidden as a member.
        brokerCheck(session, discoveryTopic(groupObjectId(session)), payload -> payload.filter(config -> config.contains("Living Room")).isPresent(),
                "Home Assistant discovers the group as one light named Living Room");
        brokerCheck(session, discoveryTopic(record(session, KITCHEN).objectId()), Optional::isEmpty,
                "and no longer the kitchen lamp, a hidden member");

        mark = ChatLog.mark();
        click(session, KITCHEN);
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Stopped grouping with Kitchen Lamp"), ANSWER_TICKS).isPresent(),
                "a click on the kitchen lamp again stops grouping (messages: " + ChatLog.since(mark) + ")");

        click(session, KITCHEN);
        mark = ChatLog.mark();
        click(session, PRIVATE);
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Not changed: You may not configure this device"), ANSWER_TICKS).isPresent(),
                "another owner's private lamp is refused (messages: " + ChatLog.since(mark) + ")");
        mark = ChatLog.mark();
        click(session, SOURCE);
        session.check(session.awaitChat(mark, line -> line.actionBar()
                        && line.text().equals("Source Output is not of the type of Kitchen Lamp: a group holds devices of one type"), ANSWER_TICKS).isPresent(),
                "an output is refused: a group holds one type (messages: " + ChatLog.since(mark) + ")");
        mark = ChatLog.mark();
        session.aimAtSky();
        session.pressUse();
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Stopped grouping with Kitchen Lamp"), ANSWER_TICKS).isPresent(),
                "a click into the air stops grouping too (messages: " + ChatLog.since(mark) + ")");
        session.check(group(session, "living_room").map(found -> found.members().size()).orElse(0) == 3, "the group still holds three lamps");
    }

    /** The kitchen lamp's General tab: its group button opens the group page, which names the group and its members. */
    private static void groupPage(GameTestSession session) {
        openGeneral(session, KITCHEN);
        session.check(buttonShown(session, "Living Room >"), "the General tab's group row is a button naming the group");
        session.screenshot("group_general_tab");
        session.clickWidget(isButton("Living Room >"), "the group button");
        session.check(session.awaitClient(mc -> groups(mc).flatMap(DeviceGroups::own).map(own -> own.members() == 3).orElse(false)
                && buttonVisible(mc, "Rename") && buttonVisible(mc, "Leave"), ANSWER_TICKS), "the group page shows the group of three, with Rename and Leave");
        session.check(session.onClient(mc -> groups(mc).flatMap(DeviceGroups::own).map(own -> own.manages() && !own.exposeMembers()
                && own.topic().endsWith("/group/living_room")).orElse(false)), "the player may change it; its members are hidden; its topic follows its slug");
        session.check(session.onClient(mc -> !buttonVisible(mc, "Save") && !buttonVisible(mc, "Copy")), "the General page's buttons are hidden");
        session.check(!buttonActive(session, "Members:"), "the members button is inactive for a player who may not expose devices");
        session.screenshot("group_page");
        session.clickWidget(isButton("< General"), "the back button");
        session.check(buttonShown(session, "Living Room >"), "the back button returns to the General page");
        session.closeScreen();
    }

    /** Renaming moves the group's state to its new topic; opped, the player shows the members to Home Assistant and hides them again. */
    private static void membersAndRename(GameTestSession session) {
        String oldTopic = session.onRuntime(runtime -> runtime.topics().group("living_room"));
        brokerCheck(session, oldTopic, Optional::isPresent, "the group's state is retained on its topic");
        openGroupPage(session, KITCHEN);
        session.typeInto(GroupGameTest::isNameField, "the group name field", "Lounge");
        session.check(buttonActive(session, "Rename"), "a changed name can be saved");
        session.clickWidget(isButton("Rename"), "the Rename button");
        session.check(session.await(() -> group(session, "lounge").isPresent() && group(session, "living_room").isEmpty(), ANSWER_TICKS),
                "Rename gives the group the name Lounge and the slug lounge");
        session.check(session.awaitClient(mc -> groups(mc).flatMap(DeviceGroups::own).map(own -> own.name().equals("Lounge")).orElse(false)
                && screen(mc).getMenu().deviceView().flatMap(view -> view.registration().group()).equals(Optional.of("Lounge")), ANSWER_TICKS),
                "the page and the device's view follow the new name");
        session.screenshot("group_renamed");
        session.closeScreen();
        brokerCheck(session, oldTopic, Optional::isEmpty, "the old state topic is cleared");
        brokerCheck(session, session.onRuntime(runtime -> runtime.topics().group("lounge")), Optional::isPresent, "the state is retained on the new topic");
        brokerCheck(session, discoveryTopic(groupObjectId(session)), payload -> payload.filter(config -> config.contains("Lounge")).isPresent(),
                "Home Assistant's device of the group is renamed in place");

        session.consoleCommand("op " + session.playerName());
        try {
            openGroupPage(session, KITCHEN);
            session.check(buttonShown(session, "Members: hidden") && buttonActive(session, "Members:"), "opped, the members button is active");
            session.clickWidget(isButton("Members:"), "the members button");
            session.check(session.await(() -> group(session, "lounge").map(DeviceGroup::exposeMembers).orElse(false), ANSWER_TICKS)
                    && buttonShown(session, "Members: shown"), "a click shows the members to Home Assistant");
            brokerCheck(session, discoveryTopic(record(session, KITCHEN).objectId()), Optional::isPresent,
                    "the exposed kitchen lamp is discovered again beside the group");
            session.screenshot("group_members_shown");
            session.clickWidget(isButton("Members:"), "the members button");
            session.check(session.await(() -> !group(session, "lounge").map(DeviceGroup::exposeMembers).orElse(true), ANSWER_TICKS),
                    "a second click hides them again");
            brokerCheck(session, discoveryTopic(record(session, KITCHEN).objectId()), Optional::isEmpty, "and the kitchen lamp's discovery is cleared");
            session.closeScreen();
        } finally {
            session.consoleCommand("deop " + session.playerName());
        }
    }

    /**
     * The porch lamp leaves the group, joins it again from the join page, makes a group of its own and joins Lounge again
     * from there, which removes its emptied group.
     */
    private static void leaveJoinCreate(GameTestSession session) {
        openGroupPage(session, PORCH);
        session.clickWidget(isButton("Leave"), "the Leave button");
        session.check(session.await(() -> record(session, PORCH).group() == null, ANSWER_TICKS)
                        && group(session, "lounge").map(found -> found.members().size()).orElse(0) == 2, "Leave takes the porch lamp out of the group");
        session.check(session.awaitClient(mc -> groups(mc).map(groups -> groups.own().isEmpty() && groups.total() == 1).orElse(false)
                && buttonVisible(mc, "Create") && !buttonVisible(mc, "Leave"), ANSWER_TICKS), "the page shows no group, Create, and one group to join");
        session.screenshot("group_page_none");
        session.clickWidget(isButton("Join a group"), "the join page button");
        session.check(session.awaitClient(mc -> buttonVisible(mc, "Join") && buttonVisible(mc, "< Group"), ANSWER_TICKS), "the join page lists a group with a Join button");
        session.screenshot("group_join_page");
        session.clickWidget(isButton("Join"), "the Join button");
        session.check(session.await(() -> Objects.equals(record(session, PORCH).group(), groupId(session, "lounge")), ANSWER_TICKS),
                "Join puts the porch lamp back into Lounge");
        session.check(session.awaitClient(mc -> groups(mc).flatMap(DeviceGroups::own).map(own -> own.name().equals("Lounge")).orElse(false)
                && buttonVisible(mc, "Rename"), ANSWER_TICKS), "and the page returns to the group page, which shows Lounge");

        session.clickWidget(isButton("Leave"), "the Leave button");
        session.check(session.await(() -> record(session, PORCH).group() == null, ANSWER_TICKS), "the porch lamp leaves again");
        session.typeInto(GroupGameTest::isNameField, "the group name field", "Porch");
        session.clickWidget(isButton("Create"), "the Create button");
        session.check(session.await(() -> group(session, "porch").map(found -> found.members().equals(Set.of(id(session, PORCH)))).orElse(false), ANSWER_TICKS),
                "Create makes group Porch with the porch lamp");
        session.check(!session.onRuntime(runtime -> runtime.discovery().exposed(runtime.registry().groupBySlug("porch"))),
                "under OPT_IN Home Assistant does not discover it: nobody exposed the porch lamp");
        session.check(session.awaitClient(mc -> groups(mc).flatMap(DeviceGroups::own).map(own -> own.name().equals("Porch") && own.members() == 1).orElse(false),
                ANSWER_TICKS), "the page shows the new group");
        session.clickWidget(isButton("Join another group"), "the join page button");
        session.clickWidget(isButton("Join"), "the Join button");
        session.check(session.await(() -> Objects.equals(record(session, PORCH).group(), groupId(session, "lounge")) && group(session, "porch").isEmpty(),
                ANSWER_TICKS), "joining Lounge removes the emptied group Porch");
        session.closeScreen();
    }

    /** A player who may only use the lamps sees the group page read-only; a change sent anyway is dropped. */
    private static void readOnly(GameTestSession session) {
        PermissionManager installed = MineQTT.permissionManager;
        MineQTT.permissionManager = new PermissionManager() {
            @Override
            public boolean canInteract(ServerPlayer player, BlockPos pos, MineqttPermission permission) {
                return permission == MineqttPermission.USE;
            }
        };
        try {
            openGroupPage(session, HALL);
            session.check(session.awaitClient(mc -> buttonVisible(mc, "Rename") && !buttonActiveNow(mc, "Rename") && !buttonActiveNow(mc, "Leave")
                    && !buttonActiveNow(mc, "Members:") && !buttonActiveNow(mc, "Join another group"), ANSWER_TICKS), "every button of the page is inactive");
            session.check(session.awaitTitleNote(Optional.of("Read only"), ANSWER_TICKS), "the title row says Read only (" + session.titleNote() + ")");
            session.screenshot("group_page_read_only");
            session.send(new DeviceGroupPayload(HALL, new GroupRules.Leave()));
            session.context().waitTicks(DROP_TICKS);
            session.check(record(session, HALL).group() != null, "a Leave sent anyway is dropped by the guard");
            session.closeScreen();
        } finally {
            MineQTT.permissionManager = installed;
        }
    }

    /**
     * Copy-Paste mode: the source output's settings pasted onto the target output, each checked for the paster; a lamp is
     * of another type; the source again empties the clipboard.
     */
    private static void copyPaste(GameTestSession session) {
        String source = slug(session, SOURCE);
        session.consoleCommand("mineqtt devices raw " + source + " channel " + CHANNEL);
        session.consoleCommand("mineqtt devices raw " + source + " on OPEN");
        session.consoleCommand("mineqtt devices setting " + source + " mode switch");
        session.consoleCommand("mineqtt devices setting " + source + " power_on_behaviour on");
        session.consoleCommand("mineqtt devices access " + source + " public");
        session.consoleCommand("mineqtt devices expose " + source + " on");
        session.check(session.await(() -> Optional.ofNullable(session.device(SOURCE, DeviceLink::raw)).map(raw -> "OPEN".equals(raw.on())).orElse(false), ANSWER_TICKS),
                "the source output has its channel and payload");

        session.aimAtSky();
        int mark = ChatLog.mark();
        session.sneakUse();
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Configurator mode: Copy-Paste"), ANSWER_TICKS).isPresent(),
                "the Configurator steps on to Copy-Paste mode (messages: " + ChatLog.since(mark) + ")");
        mark = ChatLog.mark();
        click(session, SOURCE);
        session.check(session.awaitChat(mark, line -> line.actionBar()
                        && line.text().equals("Copied Source Output: click devices of its type to paste, or Source Output again to stop"),
                ANSWER_TICKS).isPresent(), "a click on the source output copies its settings (messages: " + ChatLog.since(mark) + ")");
        mark = ChatLog.mark();
        click(session, TARGET);
        session.check(session.awaitChat(mark, line -> line.actionBar()
                        && line.text().equals("Pasted onto Target Output: 4 changed, 2 refused (see chat)"), ANSWER_TICKS).isPresent(),
                "a click on the target pastes them: four changed, two refused (messages: " + ChatLog.since(mark) + ")");
        List<ChatLog.Line> refused = ChatLog.since(mark).stream().filter(line -> !line.actionBar() && line.text().startsWith("Not pasted: ")).toList();
        session.check(refused.size() == 2 && refused.stream().anyMatch(line -> line.text().contains("operator rights or mineqtt:ha.expose"))
                && refused.stream().anyMatch(line -> line.text().contains("Only the owner or an operator")),
                "chat names the refused exposure and access mode: " + refused);
        session.check(session.await(() -> {
            JsonObject state = record(session, TARGET).lastState();
            return state != null && "switch".equals(state.get("mode").getAsString()) && "on".equals(state.get("power_on_behaviour").getAsString());
        }, ANSWER_TICKS), "the target takes the source's mode and power-on behaviour");
        session.check(Optional.ofNullable(session.device(TARGET, DeviceLink::raw)).map(raw -> CHANNEL.equals(raw.channel()) && "OPEN".equals(raw.on())
                && raw.topic().equals("mineqtt/shared/" + CHANNEL)).orElse(false), "and its channel, resolved for the target, with the on payload");
        session.check(record(session, TARGET).expose() == null && session.device(TARGET, DeviceLink::access) == AccessMode.CLAIM,
                "but not the exposure or the access mode");
        session.screenshot("group_copy_pasted");

        session.consoleCommand("op " + session.playerName());
        try {
            mark = ChatLog.mark();
            click(session, TARGET);
            session.check(session.awaitChat(mark, line -> line.actionBar()
                            && line.text().equals("Pasted onto Target Output: 2 changed, 5 already so"), ANSWER_TICKS).isPresent(),
                    "an op pastes the exposure and the access mode too (messages: " + ChatLog.since(mark) + ")");
            session.check(Boolean.TRUE.equals(record(session, TARGET).expose()) && session.device(TARGET, DeviceLink::access) == AccessMode.PUBLIC,
                    "the target is exposed and public now");
        } finally {
            session.consoleCommand("deop " + session.playerName());
        }

        mark = ChatLog.mark();
        click(session, KITCHEN);
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Kitchen Lamp is not of the type of Source Output"), ANSWER_TICKS).isPresent(),
                "a lamp is refused: the clipboard holds an output's settings (messages: " + ChatLog.since(mark) + ")");
        mark = ChatLog.mark();
        click(session, SOURCE);
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Stopped pasting Source Output"), ANSWER_TICKS).isPresent(),
                "a click on the source again empties the clipboard (messages: " + ChatLog.since(mark) + ")");
        mark = ChatLog.mark();
        session.aimAtSky();
        session.pressUse();
        session.check(session.awaitChat(mark, line -> line.actionBar()
                        && line.text().equals("Copy-Paste mode: click a device to copy its settings, then devices of its type to paste them onto"), ANSWER_TICKS).isPresent(),
                "a click into the air with an empty clipboard says how the mode works (messages: " + ChatLog.since(mark) + ")");
    }

    // A plain right-click with the Configurator on a device within reach.
    private static void click(GameTestSession session, BlockPos pos) {
        session.aim(pos);
        session.pressUse();
    }

    // Opens a device's screen with the Configurator in hand (sneak + right-click in any mode but Configure) on General.
    private static void openGeneral(GameTestSession session, BlockPos pos) {
        session.aim(pos);
        session.sneakUse();
        session.context().waitForScreen(TabbedMqttScreen.class);
        session.check(session.clickTab(GeneralTab.ID), "the General tab opens");
    }

    private static void openGroupPage(GameTestSession session, BlockPos pos) {
        openGeneral(session, pos);
        session.check(session.awaitClient(mc -> groups(mc).isPresent(), ANSWER_TICKS), "the group page's data arrives with the screen");
        session.clickWidget(widget -> widget instanceof Button && widget.getMessage().getString().endsWith(" >"), "the group button");
        session.check(session.awaitClient(mc -> buttonVisible(mc, "< General"), ANSWER_TICKS), "the group page opens");
    }

    private static TabbedMqttScreen screen(Minecraft mc) {
        return (TabbedMqttScreen) mc.gui.screen();
    }

    private static Optional<DeviceGroups> groups(Minecraft mc) {
        return mc.gui.screen() instanceof TabbedMqttScreen screen && screen.getMenu() instanceof DeviceMenu menu ? menu.groups() : Optional.empty();
    }

    private static boolean isNameField(AbstractWidget widget) {
        return widget instanceof EditBox field && field.visible && field.getMessage().getString().equals("Group name");
    }

    private static boolean buttonShown(GameTestSession session, String label) {
        return session.awaitClient(mc -> buttonVisible(mc, label), ANSWER_TICKS);
    }

    private static boolean buttonVisible(Minecraft mc, String labelStart) {
        return mc.gui.screen() != null && mc.gui.screen().children().stream().anyMatch(child -> child instanceof Button button
                && button.visible && button.getMessage().getString().startsWith(labelStart));
    }

    private static boolean buttonActive(GameTestSession session, String labelStart) {
        return session.onClient(mc -> buttonActiveNow(mc, labelStart));
    }

    private static boolean buttonActiveNow(Minecraft mc, String labelStart) {
        return mc.gui.screen() != null && mc.gui.screen().children().stream().anyMatch(child -> child instanceof Button button
                && button.visible && button.active && button.getMessage().getString().startsWith(labelStart));
    }

    private static Predicate<AbstractWidget> isButton(String labelStart) {
        return widget -> widget instanceof Button && widget.getMessage().getString().startsWith(labelStart);
    }

    private static boolean sameBrightness(GameTestSession session, BlockPos... lamps) {
        List<String> lights = Arrays.stream(lamps).map(pos -> {
            JsonObject state = record(session, pos).lastState();
            return state == null ? "" : state.get("state").getAsString() + "/" + state.get("brightness").getAsString();
        }).toList();
        return lights.stream().distinct().count() == 1 && lights.getFirst().startsWith("ON/");
    }

    // Reads a retained message until it matches; checks the last read. Skipped without a broker.
    private static void brokerCheck(GameTestSession session, String topic, Predicate<Optional<String>> matches, String what) {
        Optional<BrokerConfig> broker = session.broker();
        if (broker.isEmpty()) {
            session.skip(what, "no broker (" + GameTestSession.BROKER_ENV + " is not set)");
            return;
        }
        try (BrokerProbe probe = BrokerProbe.connect(broker.get())) {
            Optional<String> payload = probe.retained(topic);
            for (int attempt = 1; attempt < BROKER_ATTEMPTS && !matches.test(payload); attempt++) {
                payload = probe.retained(topic);
            }
            session.check(matches.test(payload), what + " (" + topic + ": " + payload.orElse("nothing retained") + ")");
        }
    }

    private static String discoveryTopic(String objectId) {
        return TopicScheme.discoveryConfig(MineQTTConfig.discoveryPrefix, objectId);
    }

    // The object id of the kitchen lamp's group, which a rename keeps.
    private static String groupObjectId(GameTestSession session) {
        UUID group = record(session, KITCHEN).group();
        return session.onRuntime(runtime -> runtime.registry().group(group).objectId());
    }

    private static Optional<DeviceGroup> group(GameTestSession session, String slug) {
        return session.onRuntime(runtime -> Optional.ofNullable(runtime.registry().groupBySlug(slug)));
    }

    private static UUID groupId(GameTestSession session, String slug) {
        return group(session, slug).map(DeviceGroup::id).orElse(null);
    }

    private static int lampLevel(GameTestSession session, BlockPos pos) {
        return session.onServer(s -> s.overworld().getBlockState(pos).getValue(RgbLedBlock.LIGHT_LEVEL));
    }

    private static void rename(GameTestSession session, BlockPos pos, String name) {
        session.consoleCommand("mineqtt devices rename " + slug(session, pos) + " " + name);
    }

    private static void setblock(GameTestSession session, BlockPos pos, String block) {
        session.consoleCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block);
    }

    private static String slug(GameTestSession session, BlockPos pos) {
        return record(session, pos).slug();
    }

    private static UUID id(GameTestSession session, BlockPos pos) {
        return record(session, pos).id();
    }

    private static DeviceRecord record(GameTestSession session, BlockPos pos) {
        return session.onRuntime(runtime -> runtime.registry().records().stream().filter(record -> record.pos().pos().equals(pos))
                .findFirst().orElseThrow(() -> new AssertionError("No device at " + pos)));
    }

    private static String intArray(UUID id) {
        return Arrays.toString(UUIDUtil.uuidToIntArray(id)).replace("[", "[I;").replace(" ", "");
    }
}

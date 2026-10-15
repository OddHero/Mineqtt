package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.compat.Screens;
import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.RedstoneEmitterBlock;
import art.rehra.mineqtt.blocks.RedstonePublisherBlock;
import art.rehra.mineqtt.blocks.RgbLedBlock;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.config.StaticDefaults;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLinks;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.flow.core.Mapping;
import art.rehra.mineqtt.flow.core.Wire;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.integrations.PermissionManager;
import art.rehra.mineqtt.items.MineqttItems;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceUnlinkPayload;
import art.rehra.mineqtt.network.MineqttNetworking.LinkPickerPayload;
import art.rehra.mineqtt.ui.LinkPickerScreen;
import art.rehra.mineqtt.ui.device.AdvancedTab;
import art.rehra.mineqtt.ui.device.DeviceMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.LeverBlock;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The Configurator's Link mode from a real client (M2 plan §4.2 M2-16). The non-op player steps the Configurator to
 * Link mode, clicks a Redstone Input whose lever is on, then a Smart Lamp: the port picker offers the input's
 * {@code powered} into the lamp's {@code state} first, and Link makes the wire, which the action bar names with the
 * devices' names and ports and which switches the lamp on at once, as the input is on; the lever then drives the lamp
 * through the server's message bus. Clicking the input and the lamp again offers Unlink for the pair, which removes the
 * wire. The mapping can be changed in the picker (an inverted output), a member's group is offered after the member
 * (both group lamps follow), clicking the source twice stops linking, and a device the player may not configure is
 * refused. With the cap at the player's wire count a Link mode wire and a {@code /mineqtt wires add} of the (then
 * opped) player are refused, while the console's wire is not. The input's Advanced tab lists its links with remove
 * buttons, scrolls, and a removal takes the wire away; a lamp of the group lists the wire into its group; for a player
 * who may only use a device the remove buttons are inactive and a removal sent anyway is dropped.
 */
public final class LinkGameTest implements FabricClientGameTest {
    private static final int Y = GameTestSession.GROUND_Y + 1;
    private static final BlockPos STAND = new BlockPos(0, Y, 0);
    private static final BlockPos INPUT = new BlockPos(3, Y, 0);
    private static final BlockPos LEVER = INPUT.above();
    private static final BlockPos LAMP = new BlockPos(3, Y, 3);
    private static final BlockPos OUTPUT = new BlockPos(0, Y, 3);
    private static final BlockPos GROUP_LAMP = new BlockPos(-3, Y, 3);
    private static final BlockPos GROUP_LAMP_2 = new BlockPos(-3, Y, 0);
    private static final BlockPos PRIVATE = new BlockPos(0, Y, -3);
    private static final UUID OTHER_OWNER = UUID.fromString("5d1c2e3f-4a5b-4c6d-8e7f-8091a2b3c4d5");
    private static final int ANSWER_TICKS = 40;
    // Ample for a dropped payload to have done something if it had not been dropped.
    private static final int DROP_TICKS = 20;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "link", LinkGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        setblock(session, INPUT, "mineqtt:redstone_publisher_block");
        setblock(session, LEVER, "minecraft:lever[face=floor,facing=north,powered=true]");
        setblock(session, LAMP, "mineqtt:rgb_led_block");
        setblock(session, OUTPUT, "mineqtt:redstone_emitter_block");
        setblock(session, GROUP_LAMP, "mineqtt:rgb_led_block");
        setblock(session, GROUP_LAMP_2, "mineqtt:rgb_led_block");
        setblock(session, PRIVATE, "mineqtt:redstone_emitter_block{mineqtt:{v:2,id:" + intArray(UUID.randomUUID())
                + ",type:\"mineqtt:redstone_output\",owner:" + intArray(OTHER_OWNER) + ",ownerName:\"Someone\",access:\"private\"}}");
        for (BlockPos pos : List.of(INPUT, LAMP, OUTPUT, GROUP_LAMP, GROUP_LAMP_2, PRIVATE)) {
            session.awaitServer(s -> s.overworld().getBlockEntity(pos) instanceof DeviceHost host && host.deviceLink().attached());
        }
        rename(session, INPUT, "Garage Input");
        rename(session, LAMP, "Porch Lamp");
        rename(session, OUTPUT, "Door Output");
        rename(session, GROUP_LAMP, "Hall Lamp");
        rename(session, GROUP_LAMP_2, "Stair Lamp");
        rename(session, PRIVATE, "Neighbour Output");
        session.consoleCommand("mineqtt group create Lamps");
        session.consoleCommand("mineqtt group add lamps " + slug(session, GROUP_LAMP));
        session.consoleCommand("mineqtt group add lamps " + slug(session, GROUP_LAMP_2));
        session.check(session.await(() -> session.onServer(s -> s.overworld().getBlockState(INPUT).getValue(RedstonePublisherBlock.POWERED)), ANSWER_TICKS),
                "the lever powers the input before anything is linked");

        session.hold(MineqttItems.Cyberdeck.get());
        int mark = ChatLog.mark();
        session.aimAtSky();
        session.sneakUse();
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Configurator mode: Link"), ANSWER_TICKS).isPresent(),
                "sneak + right-click into the air steps the Configurator to Link mode (messages: " + ChatLog.since(mark) + ")");

        linkAndUnlink(session);
        mapping(session);
        group(session);
        stopAndRefuse(session);
        cap(session);
        linksList(session);
        readOnlyList(session);
    }

    /**
     * Input, then lamp: the picker offers powered into state first; Link wires them, the lamp follows the input's current
     * level at once and the lever from then on. The same two clicks again offer Unlink, which removes the wire.
     */
    private static void linkAndUnlink(GameTestSession session) {
        int mark = ChatLog.mark();
        click(session, INPUT);
        session.check(session.awaitChat(mark, line -> line.actionBar()
                && line.text().equals("Linking from Garage Input: click the device it drives, or Garage Input again to stop"), ANSWER_TICKS).isPresent(),
                "a click on the input picks it as the source (messages: " + ChatLog.since(mark) + ")");
        click(session, LAMP);
        session.check(awaitPicker(session), "a click on the lamp opens the port picker");
        LinkPickerPayload picker = picker(session);
        LinkPickerPayload.Choice first = picker.choices().getFirst();
        session.check(picker.source().equals("Garage Input") && picker.target().equals("Porch Lamp") && first.from().equals("powered")
                && first.to().equals("state") && first.group().isEmpty() && first.isDefault() && !first.wired() && selected(session) == 0
                && first.mappings().getFirst() == Mapping.FOLLOW, "it offers the input's powered into the lamp's state first, selected and following: " + first);
        session.check(picker.choices().stream().noneMatch(choice -> choice.from().equals("location")), "the location, which no lamp port takes, is not offered");
        session.screenshot("link_picker");

        mark = ChatLog.mark();
        session.clickWidget(isButton("Link"), "the picker's Link button");
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Linked Garage Input.powered → Porch Lamp.state"), ANSWER_TICKS).isPresent(),
                "Link wires them and the action bar names the ports (messages: " + ChatLog.since(mark) + ")");
        session.check(session.awaitClient(mc -> Screens.current() == null, ANSWER_TICKS), "the picker closes");
        List<Wire> wires = wires(session);
        session.check(wires.size() == 1 && wires.getFirst().mapping() == Mapping.FOLLOW && wires.getFirst().owner().equals(session.playerId())
                && wires.getFirst().from().equals(Endpoint.port(id(session, INPUT), "powered")) && wires.getFirst().to().equals(Endpoint.port(id(session, LAMP), "state")),
                "the player owns one following wire from the input's powered to the lamp's state: " + wires);
        session.check(session.await(() -> lampLevel(session, LAMP) > 0, ANSWER_TICKS), "the lamp takes the input's current level at once: on");
        session.screenshot("link_linked");
        lever(session, false);
        session.check(session.await(() -> lampLevel(session, LAMP) == 0, ANSWER_TICKS), "the lever off switches the lamp off over the wire");
        lever(session, true);
        session.check(session.await(() -> lampLevel(session, LAMP) > 0, ANSWER_TICKS), "and on again");

        click(session, INPUT);
        click(session, LAMP);
        session.check(awaitPicker(session), "the same two clicks open the picker again");
        LinkPickerPayload.Choice linked = picker(session).choices().getFirst();
        session.check(linked.wired() && buttonShown(session, "Unlink"), "the wired pair is marked and offers Unlink");
        session.screenshot("link_picker_unlink");
        mark = ChatLog.mark();
        session.clickWidget(isButton("Unlink"), "the picker's Unlink button");
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Unlinked Garage Input.powered → Porch Lamp.state"), ANSWER_TICKS).isPresent(),
                "Unlink removes the wire (messages: " + ChatLog.since(mark) + ")");
        session.check(wires(session).isEmpty(), "no wire is left");
        lever(session, false);
        session.context().waitTicks(DROP_TICKS);
        session.check(lampLevel(session, LAMP) > 0, "the lever no longer reaches the lamp");
        lever(session, true);
    }

    /** The picker's mapping button: the input inverted into the output's state, which the action bar says. */
    private static void mapping(GameTestSession session) {
        click(session, INPUT);
        click(session, OUTPUT);
        session.check(awaitPicker(session), "input, then output: the picker opens");
        session.check(buttonShown(session, "Mapping: follows"), "the default pair follows");
        session.clickWidget(isButton("Mapping:"), "the mapping button");
        session.check(buttonShown(session, "Mapping: inverted"), "one click inverts it");
        int mark = ChatLog.mark();
        session.context().getInput().pressKey(InputConstants.KEY_RETURN);
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Linked Garage Input.powered → Door Output.state, inverted"),
                ANSWER_TICKS).isPresent(), "Enter links it, inverted (messages: " + ChatLog.since(mark) + ")");
        session.check(wires(session).stream().anyMatch(wire -> wire.mapping() == Mapping.INVERT), "the wire inverts");
        session.check(session.await(() -> power(session, OUTPUT) == 0, ANSWER_TICKS), "the output stays off while the input is on");
        lever(session, false);
        session.check(session.await(() -> power(session, OUTPUT) == 15, ANSWER_TICKS), "and turns on when the lever goes off");
        lever(session, true);
        session.check(session.await(() -> power(session, OUTPUT) == 0, ANSWER_TICKS), "and off again");
    }

    /** A member's group is offered after the member: the input into the group's state switches both group lamps. */
    private static void group(GameTestSession session) {
        click(session, INPUT);
        click(session, GROUP_LAMP);
        session.check(awaitPicker(session), "input, then a lamp of the group: the picker opens");
        List<LinkPickerPayload.Choice> choices = picker(session).choices();
        int groupState = indexOf(choices, choice -> choice.group().equals("Lamps") && choice.from().equals("powered") && choice.to().equals("state"));
        int firstGroup = indexOf(choices, choice -> !choice.group().isEmpty());
        session.check(groupState > 0 && firstGroup > 0 && choices.subList(firstGroup, choices.size()).stream().allMatch(choice -> choice.group().equals("Lamps")),
                "the group Lamps is offered after the lamp's own ports (" + choices.size() + " choices)");
        for (int i = 0; i < groupState; i++) session.context().getInput().pressKey(InputConstants.KEY_DOWN);
        session.check(session.awaitClient(mc -> Screens.current() instanceof LinkPickerScreen screen && screen.selected() == groupState, ANSWER_TICKS),
                "the arrow keys select the group's state");
        session.screenshot("link_picker_group");
        int mark = ChatLog.mark();
        session.clickWidget(isButton("Link"), "the picker's Link button");
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Linked Garage Input.powered → Lamps.state"), ANSWER_TICKS).isPresent(),
                "the input is linked into the group (messages: " + ChatLog.since(mark) + ")");
        session.check(session.await(() -> lampLevel(session, GROUP_LAMP) > 0 && lampLevel(session, GROUP_LAMP_2) > 0, ANSWER_TICKS),
                "both lamps of the group take the input's level at once");
        lever(session, false);
        session.check(session.await(() -> lampLevel(session, GROUP_LAMP) == 0 && lampLevel(session, GROUP_LAMP_2) == 0, ANSWER_TICKS),
                "and follow the lever");
        lever(session, true);
    }

    /** The source clicked twice stops linking; a device the player may not use or configure is refused. */
    private static void stopAndRefuse(GameTestSession session) {
        click(session, INPUT);
        int mark = ChatLog.mark();
        click(session, INPUT);
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Stopped linking from Garage Input"), ANSWER_TICKS).isPresent(),
                "a second click on the source stops linking (messages: " + ChatLog.since(mark) + ")");
        session.context().waitTicks(DROP_TICKS);
        session.check(session.onClient(mc -> Screens.current() == null), "and opens nothing");

        click(session, INPUT);
        mark = ChatLog.mark();
        click(session, PRIVATE);
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Not changed: You may not configure this device"), ANSWER_TICKS).isPresent(),
                "another owner's private output is refused (messages: " + ChatLog.since(mark) + ")");
        session.context().waitTicks(DROP_TICKS);
        session.check(session.onClient(mc -> Screens.current() == null), "and offers no picker");
        mark = ChatLog.mark();
        session.aimAtSky();
        session.pressUse();
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Stopped linking from Garage Input"), ANSWER_TICKS).isPresent(),
                "a click into the air stops linking too (messages: " + ChatLog.since(mark) + ")");
    }

    /**
     * {@code limits.maxWiresPerPlayer} at the two wires the player owns: a third from Link mode is refused, and so is the
     * opped player's {@code /mineqtt wires add} (the plan's cap check), while the console's wire counts for nobody.
     */
    private static void cap(GameTestSession session) {
        int owned = (int) wires(session).stream().filter(wire -> wire.owner().equals(session.playerId())).count();
        session.check(owned == 2, "the player owns two wires (" + owned + ")");
        session.onServer(s -> MineQTTConfig.limitsMaxWiresPerPlayer = owned);
        try {
            click(session, INPUT);
            click(session, LAMP);
            session.check(awaitPicker(session), "the picker opens at the cap");
            int mark = ChatLog.mark();
            session.clickWidget(isButton("Link"), "the picker's Link button");
            session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().startsWith("Not linked: you own 2 wires")
                    && line.text().contains("limits.maxWiresPerPlayer"), ANSWER_TICKS).isPresent(), "a third wire is refused at the cap (messages: " + ChatLog.since(mark) + ")");
            session.check(wires(session).size() == 2, "nothing was wired");

            String input = slug(session, INPUT);
            String lamp = slug(session, LAMP);
            session.consoleCommand("op " + session.playerName());
            mark = ChatLog.mark();
            session.playerCommand("mineqtt wires add " + input + ".signal " + lamp + ".state");
            session.check(session.awaitChat(mark, line -> line.text().startsWith("Not wired") && line.text().contains("limits.maxWiresPerPlayer"), ANSWER_TICKS).isPresent(),
                    "the opped player's wires add is refused at the cap too (messages: " + ChatLog.since(mark) + ")");
            session.consoleCommand("deop " + session.playerName());
            session.consoleCommand("mineqtt wires add " + input + ".signal " + lamp + ".state");
            session.check(wires(session).size() == 3 && wires(session).stream().anyMatch(wire -> wire.owner().equals(Util.NIL_UUID)),
                    "the console's wire counts against nobody's cap");
        } finally {
            session.onServer(s -> MineQTTConfig.limitsMaxWiresPerPlayer = StaticDefaults.DEFAULT_LIMITS_MAX_WIRES_PER_PLAYER);
        }
    }

    /**
     * The input's Advanced tab lists its links, the mapping and group on hover, and scrolls once there are more than four;
     * a remove button takes a wire away. A lamp of the group lists the wire into its group.
     */
    private static void linksList(GameTestSession session) {
        // Two more console wires, so the input has five links and its list scrolls.
        String input = slug(session, INPUT);
        session.consoleCommand("mineqtt wires add " + input + ".signal " + slug(session, OUTPUT) + ".power");
        session.consoleCommand("mineqtt wires add " + input + ".pulse " + slug(session, LAMP) + ".state");
        openAdvanced(session, INPUT);
        session.check(buttonShown(session, "Links: 5"), "the foot of the Raw Link page counts five links");
        session.clickWidget(isButton("Links:"), "the Links button");
        session.check(session.awaitClient(mc -> visibleRemoveButtons(mc) == 4, ANSWER_TICKS), "the links page shows four rows, each with a remove button");
        DeviceLinks links = menuLinks(session).orElseThrow();
        session.check(links.total() == 5 && links.rows().stream().allMatch(DeviceLinks.Row::outgoing)
                && links.rows().getFirst().port().equals("powered") && links.rows().getFirst().peer().equals("Door Output.state")
                && links.rows().get(1).peer().equals("Lamps.state") && links.rows().get(1).group().equals("Lamps"),
                "the links name the input's port and the other end's device and port: " + links.rows());
        session.screenshot("link_advanced_links");
        session.scrollAt(isButton("×"), "the links list", -1);
        session.screenshot("link_advanced_links_scrolled");

        session.scrollAt(isButton("×"), "the links list", 1);
        UUID removed = links.rows().getFirst().wire();
        session.clickWidget(isButton("×"), "the first remove button");
        session.check(session.await(() -> session.onRuntime(runtime -> runtime.registry().wire(removed)) == null, ANSWER_TICKS), "the remove button removes the wire");
        session.check(session.awaitClient(mc -> Screens.current() instanceof TabbedMqttScreen screen && screen.getMenu() instanceof DeviceMenu menu
                && menu.links().map(DeviceLinks::total).orElse(0) == 4, ANSWER_TICKS), "and the list shows the four left");
        session.closeScreen();

        openAdvanced(session, GROUP_LAMP);
        session.clickWidget(isButton("Links:"), "the Links button");
        Optional<DeviceLinks> lampLinks = menuLinks(session);
        session.check(lampLinks.isPresent() && lampLinks.get().rows().size() == 1 && !lampLinks.get().rows().getFirst().outgoing()
                && lampLinks.get().rows().getFirst().peer().equals("Garage Input.powered") && lampLinks.get().rows().getFirst().group().equals("Lamps"),
                "a lamp of the group lists the wire into its group: " + lampLinks);
        session.screenshot("link_advanced_links_group");
        session.closeScreen();
    }

    /** A player who may only use the device: its links list is read-only, and a removal sent anyway is dropped. */
    private static void readOnlyList(GameTestSession session) {
        PermissionManager installed = MineQTT.permissionManager;
        MineQTT.permissionManager = new PermissionManager() {
            @Override
            public boolean canInteract(ServerPlayer player, BlockPos pos, MineqttPermission permission) {
                return permission == MineqttPermission.USE;
            }
        };
        try {
            openAdvanced(session, INPUT);
            session.clickWidget(isButton("Links:"), "the Links button");
            session.check(session.awaitClient(mc -> visibleRemoveButtons(mc) > 0 && removeButtons(mc).stream().noneMatch(button -> button.active), ANSWER_TICKS),
                    "the remove buttons are inactive for a player who may only use the input");
            session.check(session.awaitTitleNote(Optional.of("Read only"), ANSWER_TICKS), "the title row says Read only (" + session.titleNote() + ")");
            session.screenshot("link_advanced_links_read_only");
            UUID wire = menuLinks(session).orElseThrow().rows().getFirst().wire();
            session.send(new DeviceUnlinkPayload(INPUT, wire));
            session.context().waitTicks(DROP_TICKS);
            session.check(session.onRuntime(runtime -> runtime.registry().wire(wire)) != null, "a removal sent anyway is dropped by the guard");
            session.closeScreen();
        } finally {
            MineQTT.permissionManager = installed;
        }
        setblock(session, INPUT, "minecraft:air");
        session.check(session.await(() -> wires(session).isEmpty(), ANSWER_TICKS), "breaking the input removes its wires");
    }

    // A plain right-click with the Configurator in Link mode on a device within reach.
    private static void click(GameTestSession session, BlockPos pos) {
        session.aim(pos);
        session.pressUse();
    }

    private static boolean awaitPicker(GameTestSession session) {
        return session.awaitClient(mc -> Screens.current() instanceof LinkPickerScreen, ANSWER_TICKS);
    }

    private static LinkPickerPayload picker(GameTestSession session) {
        return session.onClient(mc -> ((LinkPickerScreen) Screens.current()).picker());
    }

    private static int selected(GameTestSession session) {
        return session.onClient(mc -> ((LinkPickerScreen) Screens.current()).selected());
    }

    private static int indexOf(List<LinkPickerPayload.Choice> choices, Predicate<LinkPickerPayload.Choice> which) {
        for (int i = 0; i < choices.size(); i++) {
            if (which.test(choices.get(i))) return i;
        }
        return -1;
    }

    // Opens a device's screen with the Configurator (sneak + right-click in Link mode) on its Advanced tab.
    private static void openAdvanced(GameTestSession session, BlockPos pos) {
        session.aim(pos);
        session.sneakUse();
        session.context().waitForScreen(TabbedMqttScreen.class);
        session.check(session.clickTab(AdvancedTab.ID), "the Advanced tab opens");
    }

    private static Optional<DeviceLinks> menuLinks(GameTestSession session) {
        return session.onClient(mc -> Screens.current() instanceof TabbedMqttScreen screen && screen.getMenu() instanceof DeviceMenu menu
                ? menu.links() : Optional.empty());
    }

    private static List<Button> removeButtons(Minecraft mc) {
        return Screens.current().children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.visible && button.getMessage().getString().equals("×")).toList();
    }

    private static int visibleRemoveButtons(Minecraft mc) {
        return Screens.current() instanceof TabbedMqttScreen ? removeButtons(mc).size() : 0;
    }

    private static boolean buttonShown(GameTestSession session, String label) {
        return session.awaitClient(mc -> Screens.current() != null && Screens.current().children().stream().anyMatch(child -> child instanceof Button button
                && button.visible && button.getMessage().getString().equals(label)), ANSWER_TICKS);
    }

    private static Predicate<AbstractWidget> isButton(String labelStart) {
        return widget -> widget instanceof Button && widget.getMessage().getString().startsWith(labelStart);
    }

    private static void lever(GameTestSession session, boolean on) {
        setblock(session, LEVER, "minecraft:lever[face=floor,facing=north,powered=" + on + "]");
        session.awaitServer(s -> s.overworld().getBlockState(LEVER).getValue(LeverBlock.POWERED) == on);
    }

    private static void rename(GameTestSession session, BlockPos pos, String name) {
        session.consoleCommand("mineqtt devices rename " + slug(session, pos) + " " + name);
    }

    private static void setblock(GameTestSession session, BlockPos pos, String block) {
        session.consoleCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block);
    }

    private static List<Wire> wires(GameTestSession session) {
        return session.onRuntime(runtime -> List.copyOf(runtime.registry().wires()));
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

    private static int lampLevel(GameTestSession session, BlockPos pos) {
        return session.onServer(s -> s.overworld().getBlockState(pos).getValue(RgbLedBlock.LIGHT_LEVEL));
    }

    private static int power(GameTestSession session, BlockPos pos) {
        return session.onServer(s -> s.overworld().getBlockState(pos).getValue(RedstoneEmitterBlock.POWER));
    }

    private static String intArray(UUID id) {
        return Arrays.toString(UUIDUtil.uuidToIntArray(id)).replace("[", "[I;").replace(" ", "");
    }
}

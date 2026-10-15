package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.compat.Screens;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.items.ConfiguratorMode;
import art.rehra.mineqtt.items.CyberdeckDataUtil;
import art.rehra.mineqtt.items.MineqttItems;
import art.rehra.mineqtt.network.MineqttNetworking.RoomNamePayload;
import art.rehra.mineqtt.rooms.Room;
import art.rehra.mineqtt.rooms.RoomOffer;
import art.rehra.mineqtt.ui.RoomNameScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.LeverBlock;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The Configurator's Room mode from a real client (M4 plan D11, M4-15). The non-op player steps the Configurator to Room
 * mode and clicks two opposite corners: the first is named in the action bar and the box is outlined with particles for
 * them, the second opens the name prompt with the box's size, the names of other rooms to reuse and no "everyone's
 * devices" choice, as they may not make one. Create makes the room for their own devices, and the lamp inside shows it
 * on its General tab; the same answer sent again is dropped. A sneak-click inside the room picks it: the prompt renames it,
 * a second pick removes it. A click into the air cancels a corner; a box larger than {@code rooms.maxSize} and a click in
 * adventure mode are refused in the action bar; the command's {@code for-all} is refused to the non-op player, and in adventure
 * mode the command renames and removes none of their rooms. With Home Assistant's catalog on the test broker, an operator's
 * prompt suggests its area names after the rooms, and a room typed in another case names Home Assistant's spelling.
 */
public final class RoomsGameTest implements FabricClientGameTest {
    private static final int Y = GameTestSession.GROUND_Y + 1;
    private static final BlockPos STAND = new BlockPos(1, Y, 1);
    private static final BlockPos LAMP = new BlockPos(2, Y, 2);
    // Corner A on the ground, corner B a block two above the ground: a box of 6 x 4 x 6 around the lamp.
    private static final BlockPos CORNER_A = new BlockPos(-1, GameTestSession.GROUND_Y, -1);
    private static final BlockPos CORNER_B = new BlockPos(4, Y + 2, 4);
    private static final BlockPos INSIDE = new BlockPos(1, GameTestSession.GROUND_Y, 1);
    // A block 13 away from corner A along x, beyond the hand's reach: too far for rooms of at most 8 per side.
    private static final BlockPos FAR_CORNER = new BlockPos(12, Y, -1);
    // A small box around the lamp for the room named after a Home Assistant area: smaller than the Study, so it wins.
    private static final BlockPos AREA_CORNER = new BlockPos(3, Y, 3);
    // A chest and a lever within reach: in Room mode an ordinary click still opens and flips them.
    private static final BlockPos CHEST = new BlockPos(0, Y, 2);
    private static final BlockPos LEVER = new BlockPos(2, Y, 0);
    private static final int ANSWER_TICKS = 40;
    private static final int DROP_TICKS = 20;
    private static final int PARTICLE_TICKS = 40;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "rooms", RoomsGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        session.consoleCommand("setblock " + LAMP.getX() + " " + LAMP.getY() + " " + LAMP.getZ() + " mineqtt:rgb_led_block{mineqtt:{v:2,id:"
                + intArray(UUID.randomUUID()) + ",type:\"mineqtt:smart_lamp\",owner:" + intArray(session.playerId()) + ",ownerName:\""
                + session.playerName() + "\"}}");
        session.consoleCommand("setblock " + CORNER_B.getX() + " " + CORNER_B.getY() + " " + CORNER_B.getZ() + " minecraft:stone");
        session.consoleCommand("setblock " + FAR_CORNER.getX() + " " + FAR_CORNER.getY() + " " + FAR_CORNER.getZ() + " minecraft:stone");
        session.consoleCommand("setblock " + AREA_CORNER.getX() + " " + AREA_CORNER.getY() + " " + AREA_CORNER.getZ() + " minecraft:stone");
        session.consoleCommand("setblock " + CHEST.getX() + " " + CHEST.getY() + " " + CHEST.getZ() + " minecraft:chest");
        session.consoleCommand("setblock " + LEVER.getX() + " " + LEVER.getY() + " " + LEVER.getZ() + " minecraft:lever[face=floor]");
        session.awaitServer(s -> s.overworld().getBlockEntity(LAMP) instanceof DeviceHost host && host.deviceLink().attached());
        // A room for everyone elsewhere, made by the server: its name is one the prompt offers.
        session.consoleCommand("mineqtt rooms create \"Great Hall\" 40 -61 40 45 -58 45");
        session.check(session.onRuntime(runtime -> runtime.registry().rooms().stream().anyMatch(room -> room.name().equals("Great Hall") && room.forAll())),
                "the console makes a room for everyone");

        session.hold(MineqttItems.Cyberdeck.get());
        session.onServer(s -> {
            CyberdeckDataUtil.setMode(session.player(s).getMainHandItem(), ConfiguratorMode.ROOM);
            return null;
        });
        session.context().waitFor(mc -> CyberdeckDataUtil.mode(mc.player.getMainHandItem()) == ConfiguratorMode.ROOM);

        createRoom(session);
        generalTab(session);
        pickRenameRemove(session);
        refusals(session);
        areaNames(session);
        ordinaryClicks(session);
    }

    /** Corner A, the outline, corner B, the prompt and Create; a replay of the answer is dropped. */
    private static void createRoom(GameTestSession session) {
        int mark = ChatLog.mark();
        click(session, CORNER_A);
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Corner A at -1 -61 -1, click the opposite corner"), ANSWER_TICKS)
                .isPresent(), "the first click sets corner A (messages: " + ChatLog.since(mark) + ")");
        // clearParticles is private before 1.21.9; setting the level clears them too.
        //? if >=1.21.9 {
        session.context().runOnClient(mc -> mc.particleEngine.clearParticles());
        //?} else {
        /*session.context().runOnClient(mc -> mc.particleEngine.setLevel(mc.level));
        *///?}
        session.check(session.awaitClient(mc -> particles(mc) > 0, PARTICLE_TICKS), "the box being chosen is outlined with particles for the player");

        click(session, CORNER_B);
        session.check(session.awaitClient(mc -> Screens.current() instanceof RoomNameScreen, ANSWER_TICKS), "the opposite corner opens the name prompt");
        RoomNameScreen prompt = session.onClient(mc -> (RoomNameScreen) Screens.current());
        session.check(prompt.prompt().sizeX() == 6 && prompt.prompt().sizeY() == 4 && prompt.prompt().sizeZ() == 6 && !prompt.prompt().edits(),
                "the prompt offers the box of 6 x 4 x 6 blocks to make a room of");
        session.check(prompt.prompt().suggestions().equals(List.of("Great Hall")), "it suggests the name of the room for everyone: " + prompt.prompt().suggestions());
        session.check(!prompt.prompt().mayForAll() && !hasButton(session, "Everyone's devices") && !hasButton(session, "Your devices"),
                "a player who is no operator gets no choice of a room for everyone");
        session.check(!buttonActive(session, "Create"), "Create waits for a name");
        session.screenshot("rooms_prompt");
        session.clickWidget(isButton("Use: Great Hall"), "the suggestion");
        session.check(session.onClient(mc -> ((RoomNameScreen) Screens.current()).name().equals("Great Hall")), "the suggestion fills in its name");
        retype(session, "Kitchen");
        int offer = prompt.prompt().offer();
        mark = ChatLog.mark();
        session.clickWidget(isButton("Create"), "Create");
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().startsWith("Made room Kitchen (6 x 4 x 6, for its owner's devices)"), ANSWER_TICKS)
                .isPresent(), "Create makes the room (messages: " + ChatLog.since(mark) + ")");
        Optional<Room> kitchen = kitchen(session);
        session.check(kitchen.isPresent() && kitchen.get().owner().equals(session.playerId()) && !kitchen.get().forAll()
                && kitchen.get().min().equals(CORNER_A) && kitchen.get().max().equals(CORNER_B), "the room is the player's, for their devices, between the corners");
        session.check(session.onRuntime(runtime -> "Kitchen".equals(runtime.discovery().area(record(session)))), "the lamp in it names Kitchen as its area");

        session.send(new RoomNamePayload(offer, RoomOffer.Op.CREATE, "Pantry", false));
        session.context().waitTicks(DROP_TICKS);
        session.check(session.onRuntime(runtime -> runtime.registry().rooms().stream().noneMatch(room -> room.name().equals("Pantry"))),
                "the same answer sent again is dropped: the offer is used up");
    }

    /** The lamp's General tab names the room. */
    private static void generalTab(GameTestSession session) {
        session.hold(Items.AIR);
        session.openBlock(LAMP);
        session.check(session.screenView().map(DeviceView::registration).flatMap(DeviceView.Registration::room).equals(Optional.of("Kitchen")),
                "the lamp's General tab shows the room: " + session.screenView().map(DeviceView::registration));
        session.screenshot("rooms_general_tab");
        session.closeScreen();
        session.hold(MineqttItems.Cyberdeck.get());
        session.onServer(s -> {
            CyberdeckDataUtil.setMode(session.player(s).getMainHandItem(), ConfiguratorMode.ROOM);
            return null;
        });
        session.context().waitFor(mc -> CyberdeckDataUtil.mode(mc.player.getMainHandItem()) == ConfiguratorMode.ROOM);
    }

    /** A sneak-click inside the room picks it: the prompt renames it, and a second pick removes it. */
    private static void pickRenameRemove(GameTestSession session) {
        session.aim(INSIDE, Direction.UP);
        session.sneakUse(INSIDE);
        session.check(session.awaitClient(mc -> Screens.current() instanceof RoomNameScreen screen && screen.prompt().edits(), ANSWER_TICKS),
                "a sneak-click inside the room picks it");
        session.check(session.onClient(mc -> ((RoomNameScreen) Screens.current()).prompt().existingName().equals("Kitchen")
                && ((RoomNameScreen) Screens.current()).name().equals("Kitchen")), "the prompt shows its name");
        session.check(!buttonActive(session, "Rename") && buttonActive(session, "Remove"), "Rename waits for another name; Remove is ready");
        retype(session, "Cookhouse");
        int mark = ChatLog.mark();
        session.clickWidget(isButton("Rename"), "Rename");
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().startsWith("Renamed room Kitchen to Cookhouse"), ANSWER_TICKS).isPresent(),
                "Rename renames the room (messages: " + ChatLog.since(mark) + ")");
        session.check(session.onRuntime(runtime -> "Cookhouse".equals(runtime.discovery().area(record(session)))), "the lamp follows the new name");

        session.aim(INSIDE, Direction.UP);
        session.sneakUse(INSIDE);
        session.check(session.awaitClient(mc -> Screens.current() instanceof RoomNameScreen screen && screen.prompt().existingName().equals("Cookhouse"), ANSWER_TICKS),
                "a second pick offers the renamed room");
        mark = ChatLog.mark();
        session.clickWidget(isButton("Remove"), "Remove");
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().startsWith("Removed room Cookhouse"), ANSWER_TICKS).isPresent(),
                "Remove removes the room (messages: " + ChatLog.since(mark) + ")");
        session.check(session.onRuntime(runtime -> runtime.registry().rooms().stream().noneMatch(room -> room.owner().equals(session.playerId()))
                && runtime.discovery().area(record(session)) == null), "the room is gone and the lamp names no area");

        mark = ChatLog.mark();
        session.aim(INSIDE, Direction.UP);
        session.sneakUse(INSIDE);
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("No room of yours here to rename or remove"), ANSWER_TICKS).isPresent(),
                "with no room there a sneak-click says so (messages: " + ChatLog.since(mark) + ")");
    }

    /** Cancel by clicking into the air; a box too large and adventure mode are refused in the action bar; for-all needs rights. */
    private static void refusals(GameTestSession session) {
        int mark = ChatLog.mark();
        click(session, CORNER_A);
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().startsWith("Corner A at"), ANSWER_TICKS).isPresent(), "corner A again");
        mark = ChatLog.mark();
        session.aimAtSky();
        session.pressUse();
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Room selection cancelled"), ANSWER_TICKS).isPresent(),
                "a click into the air cancels the corner (messages: " + ChatLog.since(mark) + ")");
        session.check(session.onClient(mc -> !(Screens.current() instanceof RoomNameScreen)), "and opens nothing");

        int maxSize = MineQTTConfig.roomsMaxSize;
        MineQTTConfig.roomsMaxSize = 8;
        try {
            click(session, CORNER_A);
            mark = ChatLog.mark();
            // Beyond the hand's reach: the Configurator's range takes the click.
            session.aimFar(FAR_CORNER);
            session.pressUse();
            session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals(
                            "Not done: A room is at most 8 blocks on each side; this one is 14 x 2 x 1"), ANSWER_TICKS).isPresent(),
                    "a box larger than rooms.maxSize is refused in the action bar (messages: " + ChatLog.since(mark) + ")");
            session.check(session.onClient(mc -> !(Screens.current() instanceof RoomNameScreen)), "and asks for no name");
        } finally {
            MineQTTConfig.roomsMaxSize = maxSize;
        }

        GameType before = session.onServer(s -> art.rehra.mineqtt.compat.Players.gameMode(session.player(s)));
        gameMode(session, GameType.ADVENTURE);
        try {
            mark = ChatLog.mark();
            click(session, CORNER_A);
            session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Rooms cannot be made in this game mode"), ANSWER_TICKS)
                    .isPresent(), "in adventure mode a click is refused in the action bar (messages: " + ChatLog.since(mark) + ")");
        } finally {
            gameMode(session, before);
        }

        mark = ChatLog.mark();
        session.playerCommand("mineqtt rooms create Shared -1 -61 -1 4 -58 4 for-all");
        session.check(session.awaitChat(mark, line -> !line.actionBar() && line.text().contains("needs operator rights or mineqtt:rooms.admin"), ANSWER_TICKS)
                .isPresent(), "the command's for-all is refused to a player who is no operator (messages: " + ChatLog.since(mark) + ")");
        mark = ChatLog.mark();
        session.playerCommand("mineqtt rooms create Study -1 -61 -1 4 -58 4");
        session.check(session.awaitChat(mark, line -> line.text().startsWith("Made room Study"), ANSWER_TICKS).isPresent(),
                "without for-all the player makes a room by command (messages: " + ChatLog.since(mark) + ")");
        session.check(session.onRuntime(runtime -> "Study".equals(runtime.discovery().area(record(session)))), "which the lamp names as its area");

        gameMode(session, GameType.ADVENTURE);
        try {
            mark = ChatLog.mark();
            session.playerCommand("mineqtt rooms rename Study Den");
            session.check(session.awaitChat(mark, line -> !line.actionBar() && line.text().contains("Rooms cannot be changed in this game mode"), ANSWER_TICKS)
                    .isPresent(), "in adventure mode the player's own room is not renamed by command (messages: " + ChatLog.since(mark) + ")");
            mark = ChatLog.mark();
            session.playerCommand("mineqtt rooms remove Study");
            session.check(session.awaitChat(mark, line -> !line.actionBar() && line.text().contains("Rooms cannot be changed in this game mode"), ANSWER_TICKS)
                    .isPresent(), "nor removed (messages: " + ChatLog.since(mark) + ")");
            session.check(session.onRuntime(runtime -> runtime.registry().rooms().stream().anyMatch(room -> room.name().equals("Study"))), "the room stays");
        } finally {
            gameMode(session, before);
        }
    }

    /**
     * M4-23: Home Assistant's catalog names the areas Living Room and Hallway. A player who may bind devices to it (an
     * operator on this dedicated server) gets them as suggestions after the rooms; a room typed {@code living room} names
     * Home Assistant's spelling for the lamp in it.
     */
    private static void areaNames(GameTestSession session) {
        if (session.broker().isEmpty()) {
            session.skip("the rooms suite's Home Assistant area names", GameTestSession.BROKER_ENV + " is not set");
            return;
        }
        try (TwinFixture twin = new TwinFixture(session, List.of("light.*", "switch.*"))) {
            twin.catalog("light", "[{\"id\":\"light.rooms_sofa\",\"name\":\"Sofa\",\"area\":\"Living Room\",\"control\":true,\"state\":\"on\"}]");
            twin.catalog("switch", "[{\"id\":\"switch.rooms_fan\",\"name\":\"Fan\",\"area\":\"Hallway\",\"control\":true,\"state\":\"off\"}]");
            twin.catalogStatus(2);
            session.check(twin.awaitCatalog(), "the twin reads the catalog");
            session.check(session.onRuntime(runtime -> runtime.twin().areas()).equals(List.of("Hallway", "Living Room")),
                    "the catalog names two areas: " + session.onRuntime(runtime -> runtime.twin().areas()));

            // The size refusal kept a corner A: a click into the air drops it first.
            int mark = ChatLog.mark();
            session.aimAtSky();
            session.pressUse();
            session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Room selection cancelled"), ANSWER_TICKS).isPresent(),
                    "the kept corner is cancelled (messages: " + ChatLog.since(mark) + ")");
            mark = ChatLog.mark();
            click(session, INSIDE);
            session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().startsWith("Corner A at"), ANSWER_TICKS).isPresent(),
                    "corner A (messages: " + ChatLog.since(mark) + ")");
            click(session, AREA_CORNER);
            session.check(session.awaitClient(mc -> Screens.current() instanceof RoomNameScreen, ANSWER_TICKS), "the opposite corner opens the name prompt");
            List<String> suggestions = session.onClient(mc -> ((RoomNameScreen) Screens.current()).prompt().suggestions());
            session.check(suggestions.equals(List.of("Great Hall", "Hallway", "Living Room")),
                    "the operator gets the room names, then Home Assistant's area names: " + suggestions);
            session.clickWidget(isButton("Use: Great Hall"), "the first suggestion");
            session.check(hasButton(session, "Use: Hallway"), "the suggestion steps on to the first area name");
            session.clickWidget(isButton("Use: Hallway"), "the area name");
            session.check(session.onClient(mc -> ((RoomNameScreen) Screens.current()).name().equals("Hallway")), "which fills in its name");
            session.screenshot("rooms_area_suggestions");
            retype(session, "living room");
            mark = ChatLog.mark();
            session.clickWidget(isButton("Create"), "Create");
            session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().startsWith("Made room living room"), ANSWER_TICKS).isPresent(),
                    "Create makes the room as typed (messages: " + ChatLog.since(mark) + ")");
            session.check(session.onRuntime(runtime -> "living room".equals(runtime.registry().roomOf(record(session)).map(Room::name).orElse(null))
                    && "Living Room".equals(runtime.discovery().area(record(session)))),
                    "the lamp is in the room and names Home Assistant's spelling of the area: " + session.onRuntime(runtime -> runtime.discovery().area(record(session))));
        }
    }

    /**
     * In Room mode an ordinary click within reach goes to the block first: a chest opens and a lever flips, also while a
     * corner A waits, which the click leaves as it is; only a block that does nothing with the click is a corner.
     */
    private static void ordinaryClicks(GameTestSession session) {
        session.hold(MineqttItems.Cyberdeck.get());
        session.onServer(s -> {
            CyberdeckDataUtil.setMode(session.player(s).getMainHandItem(), ConfiguratorMode.ROOM);
            return null;
        });
        session.context().waitFor(mc -> CyberdeckDataUtil.mode(mc.player.getMainHandItem()) == ConfiguratorMode.ROOM);
        int mark = ChatLog.mark();
        session.aim(CHEST);
        session.pressUse();
        session.check(session.awaitClient(mc -> Screens.current() instanceof ContainerScreen, ANSWER_TICKS), "a click on a chest opens it");
        session.closeScreen();
        session.check(ChatLog.since(mark).stream().noneMatch(line -> line.actionBar() && line.text().startsWith("Corner A")), "and sets no corner");

        mark = ChatLog.mark();
        click(session, INSIDE);
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Corner A at 1 -61 1, click the opposite corner"), ANSWER_TICKS)
                .isPresent(), "a block that does nothing with the click is corner A (messages: " + ChatLog.since(mark) + ")");
        mark = ChatLog.mark();
        session.aim(LEVER);
        session.pressUse();
        session.check(session.await(() -> session.onServer(s -> s.overworld().getBlockState(LEVER).getValue(LeverBlock.POWERED)), ANSWER_TICKS),
                "while corner A waits, a click on a lever flips it");
        session.aim(CHEST);
        session.pressUse();
        session.check(session.awaitClient(mc -> Screens.current() instanceof ContainerScreen, ANSWER_TICKS), "and one on the chest opens it");
        session.closeScreen();
        session.check(ChatLog.since(mark).stream().noneMatch(line -> line.actionBar() && (line.text().startsWith("Corner A") || line.text().startsWith("Not done")))
                && session.onClient(mc -> !(Screens.current() instanceof RoomNameScreen)), "neither is a corner: " + ChatLog.since(mark));
        mark = ChatLog.mark();
        session.aimAtSky();
        session.pressUse();
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Room selection cancelled"), ANSWER_TICKS).isPresent(),
                "corner A waited all along: a click into the air cancels it (messages: " + ChatLog.since(mark) + ")");
    }

    private static Optional<Room> kitchen(GameTestSession session) {
        return session.onRuntime(runtime -> runtime.registry().rooms().stream().filter(room -> room.name().equals("Kitchen")).findFirst());
    }

    private static DeviceRecord record(GameTestSession session) {
        return session.onRuntime(runtime -> runtime.registry().at(net.minecraft.core.GlobalPos.of(net.minecraft.world.level.Level.OVERWORLD, LAMP)));
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

    // A ground block is aimed at on its top face, which no nearer ground block hides.
    private static void click(GameTestSession session, BlockPos pos) {
        if (pos.getY() == GameTestSession.GROUND_Y) {
            session.aim(pos, Direction.UP);
        } else {
            session.aim(pos);
        }
        session.pressUse();
    }

    // Erases the prompt's name field and types a new name, as a player would.
    private static void retype(GameTestSession session, String name) {
        session.clickWidget(widget -> widget instanceof EditBox, "the name field");
        session.context().getInput().pressKey(InputConstants.KEY_END);
        int length = session.onClient(mc -> ((RoomNameScreen) Screens.current()).name().length());
        for (int i = 0; i < length; i++) {
            session.context().getInput().pressKey(InputConstants.KEY_BACKSPACE);
        }
        session.context().getInput().typeChars(name);
        session.context().waitTick();
        session.check(session.onClient(mc -> ((RoomNameScreen) Screens.current()).name().equals(name)), "the name field takes " + name);
    }

    private static void gameMode(GameTestSession session, GameType mode) {
        session.consoleCommand("gamemode " + mode.getSerializedName() + " " + session.playerName());
        boolean mayBuild = mode != GameType.ADVENTURE && mode != GameType.SPECTATOR;
        session.awaitServer(s -> art.rehra.mineqtt.compat.Players.gameMode(session.player(s)) == mode);
        session.context().waitFor(mc -> mc.player.getAbilities().mayBuild == mayBuild);
    }

    private static boolean hasButton(GameTestSession session, String labelStart) {
        return session.onClient(mc -> Screens.current() != null && Screens.current().children().stream()
                .anyMatch(child -> child instanceof Button button && button.visible && button.getMessage().getString().startsWith(labelStart)));
    }

    private static boolean buttonActive(GameTestSession session, String labelStart) {
        return session.onClient(mc -> Screens.current() != null && Screens.current().children().stream()
                .anyMatch(child -> child instanceof Button button && button.visible && button.active && button.getMessage().getString().startsWith(labelStart)));
    }

    private static Predicate<AbstractWidget> isButton(String labelStart) {
        return widget -> widget instanceof Button && widget.getMessage().getString().startsWith(labelStart);
    }

    private static String intArray(UUID id) {
        return Arrays.toString(UUIDUtil.uuidToIntArray(id)).replace("[", "[I;").replace(" ", "");
    }
}

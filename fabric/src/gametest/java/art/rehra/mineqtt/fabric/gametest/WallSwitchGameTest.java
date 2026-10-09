package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.blocks.LightRemoteBlock;
import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.blocks.RgbLedBlock;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.RawLink;
import art.rehra.mineqtt.device.types.WallSwitch;
import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.flow.core.Mapping;
import art.rehra.mineqtt.flow.core.Wire;
import art.rehra.mineqtt.legacy.LegacyNbtV1;
import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import art.rehra.mineqtt.ui.device.AdvancedTab;
import art.rehra.mineqtt.ui.device.ControlsTab;
import art.rehra.mineqtt.ui.device.GeneralTab;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The Wall Switch (M2-22). The non-op player places one against a wall, and real right-clicks on its two buttons make
 * its events: a click is a press of the button it hits, two quick clicks a double press, and the use key held a hold,
 * once, with nothing when it is let go; the block shows the held button pressed. Each event reaches the {@code action}
 * topic and, as {@code press_left}-style names, its Raw Link, never retained. A wire from its {@code press} toggles a
 * lamp. Its screen has a button per press action, and its Advanced tab no retain. A v1 Light Remote next to a v1 lamp of
 * the same owner on its topic becomes a Wall Switch wired to that lamp (M2 plan D15), not named after the topic; one of
 * another owner is wired to nothing.
 */
public final class WallSwitchGameTest implements FabricClientGameTest {
    private static final int Y = GameTestSession.GROUND_Y + 1;
    private static final BlockPos STAND = new BlockPos(0, Y, 0);
    private static final BlockPos WALL = new BlockPos(0, Y + 1, 3);
    private static final BlockPos SWITCH = WALL.north();
    private static final BlockPos LAMP = new BlockPos(2, Y, 2);
    private static final BlockPos V1_LAMP = new BlockPos(5, Y, 2);
    private static final BlockPos V1_REMOTE = new BlockPos(5, Y + 1, 3);
    private static final BlockPos FOREIGN_REMOTE = new BlockPos(7, Y + 1, 3);
    private static final String CHANNEL = "wall_switch_gametest";
    private static final String RAW_TOPIC = "mineqtt/shared/" + CHANNEL;
    private static final String V1_TOPIC = "wall_switch_gametest/v1lamp";
    private static final int ANSWER_TICKS = 60;
    // Long enough for an event to make the round trip through the broker, and for one that should not come.
    private static final int SETTLE_TICKS = 30;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "wall_switch", WallSwitchGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        // The server resends an unconfirmed teleport after 20 ticks, with its rotation, which would undo an aim (M2-21).
        session.context().waitTicks(25);
        setblock(session, WALL, "minecraft:stone");
        String slug = placed(session);
        Optional<BrokerConfig> broker = session.broker();
        if (broker.isEmpty()) {
            session.skip("each gesture's event and Raw Link payload reach the broker", "no broker (" + GameTestSession.BROKER_ENV + " is not set)");
            gesturesShown(session);
        } else {
            session.clearAtClose(RAW_TOPIC);
            session.consoleCommand("mineqtt devices raw " + slug + " channel " + CHANNEL);
            session.check(RAW_TOPIC.equals(session.device(SWITCH, link -> link.raw() == null ? null : link.raw().topic())),
                    "its Raw Link channel resolves to " + RAW_TOPIC);
            try (BrokerProbe probe = BrokerProbe.connect(broker.get());
                 BrokerProbe.Listener actions = probe.listen("mineqtt/" + GameTestSession.BRIDGE_ID + "/" + slug + "/action");
                 BrokerProbe.Listener raw = probe.listen(RAW_TOPIC)) {
                gestures(session, actions, raw);
                session.check(probe.retained(RAW_TOPIC).isEmpty(), "nothing is retained on its Raw Link topic");
                screen(session, actions);
            }
        }
        wired(session, slug);
        migrated(session);
    }

    /** Placed against a wall by hand, it faces away from it, belongs to the player and attaches. */
    private static String placed(GameTestSession session) {
        session.hold(MineqttBlocks.LIGHT_REMOTE_BLOCK.get().asItem());
        session.aim(WALL, Direction.NORTH);
        session.pressUse();
        session.awaitServer(s -> s.overworld().getBlockEntity(SWITCH) instanceof DeviceHost host && host.deviceLink().attached());
        BlockState state = session.onServer(s -> s.overworld().getBlockState(SWITCH));
        session.check(state.is(MineqttBlocks.LIGHT_REMOTE_BLOCK.get()) && state.getValue(LightRemoteBlock.FACING) == Direction.NORTH
                && !state.getValue(LightRemoteBlock.LEFT) && !state.getValue(LightRemoteBlock.RIGHT), "the Wall Switch is on the wall, facing away from it, both buttons up");
        session.check(session.playerName().equals(session.device(SWITCH, DeviceLink::ownerName))
                && WallSwitch.TYPE.equals(session.device(SWITCH, DeviceLink::type)), "it is the player's Wall Switch");
        session.hold(Items.AIR);
        session.aim(SWITCH);
        session.screenshot("wall_switch_placed");
        return slug(session, SWITCH);
    }

    /** Without a broker: the clicks still show on the block. */
    private static void gesturesShown(GameTestSession session) {
        aimButton(session, WallSwitch.Button.LEFT);
        session.pressUse();
        session.check(session.await(() -> pressed(session, WallSwitch.Button.LEFT), ANSWER_TICKS), "a click shows the left button pressed");
        session.check(session.await(() -> !pressed(session, WallSwitch.Button.LEFT), ANSWER_TICKS), "and lets it go");
    }

    /** Clicks, a double click and a held use key on each button, as events and Raw Link payloads. */
    private static void gestures(GameTestSession session, BrokerProbe.Listener actions, BrokerProbe.Listener raw) {
        List<String> events = new ArrayList<>();
        List<String> payloads = new ArrayList<>();

        aimButton(session, WallSwitch.Button.LEFT);
        session.pressUse();
        session.check(session.await(() -> pressed(session, WallSwitch.Button.LEFT), ANSWER_TICKS), "a click shows the left button pressed");
        settle(session, actions, events, raw, payloads);
        session.check(events.equals(List.of(event("press", "left"))) && payloads.equals(List.of("press_left")),
                "a click on the left half is a press of the left button (" + events + ", " + payloads + ")");
        session.check(!pressed(session, WallSwitch.Button.LEFT), "the button is up again");

        aimButton(session, WallSwitch.Button.RIGHT);
        events.clear();
        payloads.clear();
        session.pressUse();
        settle(session, actions, events, raw, payloads);
        session.check(events.equals(List.of(event("press", "right"))) && payloads.equals(List.of("press_right")),
                "a click on the right half is a press of the right button (" + events + ", " + payloads + ")");

        events.clear();
        payloads.clear();
        session.pressUse();
        session.context().waitTicks(3);
        session.pressUse();
        settle(session, actions, events, raw, payloads);
        session.check(events.equals(List.of(event("double_press", "right"))) && payloads.equals(List.of("double_press_right")),
                "two quick clicks are a double press (" + events + ", " + payloads + ")");

        aimButton(session, WallSwitch.Button.LEFT);
        events.clear();
        payloads.clear();
        session.context().getInput().holdKey(options -> options.keyUse);
        try {
            session.check(session.await(() -> pressed(session, WallSwitch.Button.LEFT), ANSWER_TICKS), "the held button shows pressed");
            session.context().waitTicks(30);
            session.check(pressed(session, WallSwitch.Button.LEFT), "and stays pressed while the use key is held");
            session.screenshot("wall_switch_held");
        } finally {
            session.context().getInput().releaseKey(options -> options.keyUse);
        }
        settle(session, actions, events, raw, payloads);
        session.check(events.equals(List.of(event("hold", "left"))) && payloads.equals(List.of("hold_left")),
                "holding the use key is one hold, and letting go fires nothing (" + events + ", " + payloads + ")");
        session.check(session.await(() -> !pressed(session, WallSwitch.Button.LEFT), ANSWER_TICKS), "let go, the button is up");
    }

    /** Its screen: a button per press action on Controls, which presses; no retain on the Advanced tab. */
    private static void screen(GameTestSession session, BrokerProbe.Listener actions) {
        session.openBlock(SWITCH);
        session.check(session.tabIds().equals(List.of(GeneralTab.ID, ControlsTab.ID, AdvancedTab.ID, "access", "diagnostics")),
                "its screen has the five device tabs (" + session.tabIds() + ")");
        session.screenshot("wall_switch_general");
        session.check(session.clickTab(ControlsTab.ID), "it shows its Controls tab");
        session.screenshot("wall_switch_controls");
        List<String> events = new ArrayList<>();
        session.clickWidget(widget -> widget.getMessage().getString().equals("Press right button"), "the Press right button button");
        session.check(session.await(() -> {
            Optional<String> message;
            while ((message = actions.nextLive()).isPresent()) events.add(message.get());
            return !events.isEmpty();
        }, ANSWER_TICKS) && events.equals(List.of(event("press", "right"))), "the Controls tab presses the right button at once (" + events + ")");
        session.check(session.clickTab(AdvancedTab.ID), "it shows its Advanced tab");
        Optional<DeviceView.Raw> raw = session.screenView().flatMap(DeviceView::raw);
        session.check(raw.map(link -> link.topic().equals(RAW_TOPIC) && !link.retains()).orElse(false), "its Raw Link is shown, not retained (" + raw + ")");
        session.check(session.onClient(mc -> GameTestSession.openScreen(mc).children().stream()
                        .noneMatch(widget -> widget instanceof net.minecraft.client.gui.components.AbstractWidget button && button.getMessage().getString().startsWith("Retain"))),
                "the Advanced tab has no retain switch for a Raw Link of events");
        session.screenshot("wall_switch_advanced");
        session.closeScreen();
    }

    /** A wire from its press toggles a lamp, a click at a time. */
    private static void wired(GameTestSession session, String slug) {
        setblock(session, LAMP, "mineqtt:rgb_led_block");
        session.awaitServer(s -> s.overworld().getBlockEntity(LAMP) instanceof DeviceHost host && host.deviceLink().attached());
        String lamp = slug(session, LAMP);
        session.consoleCommand("mineqtt wires add " + slug + ".press " + lamp + ".state");
        List<Wire> wires = wiresFrom(session, SWITCH);
        session.check(wires.size() == 1 && wires.getFirst().mapping() == Mapping.PULSE_TOGGLE, "press is wired to the lamp's state, toggling (" + wires + ")");
        aimButton(session, WallSwitch.Button.LEFT);
        session.pressUse();
        session.check(session.await(() -> lampLevel(session, LAMP) > 0, ANSWER_TICKS), "a press of the left button switches the lamp on");
        aimButton(session, WallSwitch.Button.RIGHT);
        session.pressUse();
        session.check(session.await(() -> lampLevel(session, LAMP) == 0, ANSWER_TICKS), "a press of the right button switches it off again");
    }

    /**
     * M2 plan D15: a v1 Light Remote on a v1 lamp's topic, both the player's, becomes a Wall Switch wired to the lamp; one
     * of another owner on the same topic is wired to nothing. Neither is named after the topic.
     */
    private static void migrated(GameTestSession session) {
        String player = session.playerName();
        // The v1 lamp's discovery migration (design §9.3) publishes to v1's light config, outside the world's discovery.
        session.clearAtClose("homeassistant/light/" + LegacyNbtV1.legacyUniqueId(V1_TOPIC) + "/config");
        setblock(session, V1_LAMP, "mineqtt:rgb_led_block{topic:\"" + V1_TOPIC + "\",lastTabId:\"settings\",ownerName:\"" + player + "\",privateMode:0b,"
                + paper(V1_TOPIC) + ",Red:15,Green:0,Blue:0,TargetRed:15,TargetGreen:0,TargetBlue:0,Brightness:255,Lit:1b}");
        session.awaitServer(s -> s.overworld().getBlockEntity(V1_LAMP) instanceof DeviceHost host && host.deviceLink().attached());
        session.check(session.await(() -> lampLevel(session, V1_LAMP) > 0, ANSWER_TICKS), "the v1 lamp is adopted, on");
        setblock(session, V1_REMOTE.south(), "minecraft:stone");
        setblock(session, V1_REMOTE, "mineqtt:light_remote_block{topic:\"" + V1_TOPIC + "\",lastTabId:\"light_remote\",ownerName:\"" + player
                + "\",privateMode:0b," + paper(V1_TOPIC) + "}");
        setblock(session, FOREIGN_REMOTE, "mineqtt:light_remote_block{topic:\"" + V1_TOPIC + "\",lastTabId:\"light_remote\",ownerName:\"Someone\","
                + "privateMode:0b," + paper(V1_TOPIC) + "}");
        for (BlockPos pos : List.of(V1_REMOTE, FOREIGN_REMOTE)) {
            session.awaitServer(s -> s.overworld().getBlockEntity(pos) instanceof DeviceHost host && host.deviceLink().attached());
        }
        UUID lamp = session.device(V1_LAMP, DeviceLink::id);
        session.check(session.await(() -> wiresFrom(session, V1_REMOTE).size() == 1, ANSWER_TICKS)
                        && wiresFrom(session, V1_REMOTE).getFirst().to().equals(Endpoint.port(lamp, "state"))
                        && wiresFrom(session, V1_REMOTE).getFirst().mapping() == Mapping.PULSE_TOGGLE,
                "the player's v1 remote is wired to the lamp its topic commanded (" + wiresFrom(session, V1_REMOTE) + ")");
        RawLink raw = session.device(V1_REMOTE, DeviceLink::raw);
        String name = session.device(V1_REMOTE, DeviceLink::name);
        session.check(raw == null && name != null && name.startsWith("Wall Switch") && Integer.valueOf(1).equals(session.device(V1_REMOTE, link -> link.legacyItems().size())),
                "its topic is no Raw Link and not its name (" + name + ", " + raw + "), and its paper is a legacy item");
        session.consoleCommand("mineqtt devices action " + slug(session, V1_REMOTE) + " press_left");
        session.check(session.await(() -> lampLevel(session, V1_LAMP) == 0, ANSWER_TICKS), "pressing the migrated switch toggles the lamp off");
        session.teleport(new BlockPos(V1_REMOTE.getX(), Y, 0));
        session.context().waitTicks(25);
        session.aim(V1_REMOTE);
        // The item in the hotbar and in hand, beside the migrated switch on its wall.
        session.hold(MineqttBlocks.LIGHT_REMOTE_BLOCK.get().asItem());
        session.screenshot("wall_switch_migrated");
        // Another owner's remote waits for a lamp of its owner a while, then drops its v1 commands.
        session.context().waitTicks(20);
        session.check(wiresFrom(session, FOREIGN_REMOTE).isEmpty(), "another owner's v1 remote on the same topic is wired to nothing");
    }

    // Aims at the middle of a button of the switch at SWITCH, which faces north, and waits until the server's view of the
    // player hits that button too: the server finds the button along the player's view.
    private static void aimButton(GameTestSession session, WallSwitch.Button button) {
        // Facing north, the player looks south: the left button is east of the plate's middle.
        double x = button == WallSwitch.Button.LEFT ? 10.5 / 16 : 5.5 / 16;
        Vec3 target = new Vec3(SWITCH.getX() + x, SWITCH.getY() + 0.5, SWITCH.getZ() + 12.2 / 16);
        session.context().runOnClient(mc -> mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, target));
        session.context().waitFor(mc -> mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(SWITCH));
        session.awaitServer(s -> session.player(s).pick(5, 1.0F, false) instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(SWITCH) && LightRemoteBlock.buttonAt(Direction.NORTH, hit.getLocation().x - Vec3.atCenterOf(SWITCH).x,
                hit.getLocation().z - Vec3.atCenterOf(SWITCH).z) == button);
    }

    private static boolean pressed(GameTestSession session, WallSwitch.Button button) {
        return session.onServer(s -> s.overworld().getBlockState(SWITCH).getValue(button == WallSwitch.Button.LEFT ? LightRemoteBlock.LEFT : LightRemoteBlock.RIGHT));
    }

    // Collects what arrives live on both topics while the game ticks.
    private static void settle(GameTestSession session, BrokerProbe.Listener actions, List<String> events, BrokerProbe.Listener raw, List<String> payloads) {
        for (int tick = 0; tick < SETTLE_TICKS; tick += 5) {
            session.context().waitTicks(5);
            Optional<String> message;
            while ((message = actions.nextLive()).isPresent()) events.add(message.get());
            while ((message = raw.nextLive()).isPresent()) payloads.add(message.get());
        }
    }

    private static String event(String type, String button) {
        return "{\"event_type\":\"" + type + "\",\"button\":\"" + button + "\"}";
    }

    private static String paper(String name) {
        return "Items:[{Slot:0b,id:\"minecraft:paper\",count:1,components:{\"minecraft:custom_name\":\"" + name + "\"}}]";
    }

    private static List<Wire> wiresFrom(GameTestSession session, BlockPos pos) {
        UUID id = session.device(pos, DeviceLink::id);
        return session.onRuntime(runtime -> runtime.registry().wires().stream()
                .filter(wire -> wire.from() instanceof Endpoint.PortRef from && from.node().equals(id)).toList());
    }

    private static int lampLevel(GameTestSession session, BlockPos pos) {
        return session.onServer(s -> s.overworld().getBlockState(pos).getValue(RgbLedBlock.LIGHT_LEVEL));
    }

    private static void setblock(GameTestSession session, BlockPos pos, String block) {
        session.consoleCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block);
    }

    private static String slug(GameTestSession session, BlockPos pos) {
        return session.onRuntime(runtime -> runtime.registry().records().stream().filter(record -> record.pos().pos().equals(pos))
                .map(DeviceRecord::slug).findFirst().orElseThrow(() -> new AssertionError("No device at " + pos)));
    }
}

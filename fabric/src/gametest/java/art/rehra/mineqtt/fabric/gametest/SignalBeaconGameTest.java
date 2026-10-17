package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.blocks.SignalBeaconBlock;
import art.rehra.mineqtt.blocks.entities.AlarmPanelBlockEntity;
import art.rehra.mineqtt.blocks.entities.SignalBeaconBlockEntity;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.SignalBeacon;
import art.rehra.mineqtt.device.types.SignalBeacon.Beam;
import art.rehra.mineqtt.device.types.SignalBeacon.Effect;
import art.rehra.mineqtt.device.types.SignalBeacon.Status;
import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import art.rehra.mineqtt.network.MineqttNetworking.AlarmKeypadPayload;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * The Signal Beacon. A vanilla beacon on a 3 x 3 iron base with the Signal Beacon on top: set by command it is off and
 * white, and the beacon below is found ({@code beacon} is {@code beam}). Switched on in red, the beacon's beam sections
 * (on the server and in the client's own beacon) end in the red dye's beam colour; a dye name is that dye, Home
 * Assistant's pure green the lime dye; off, the beacon shows no beam. With {@code blink} the beam comes and goes. A
 * missing base block makes the beacon {@code inactive}, a missing beacon {@code no_beacon}. An Alarm Panel's state wired
 * to the {@code alarm} in-port shows green (lime) disarmed, a blinking orange while arming, red armed and a blinking red
 * when triggered, and green again disarmed. With a broker: Home Assistant's light JSON and an alarm state on /set, and
 * the retained state Home Assistant reads (rgb mode, full brightness, the beam colour).
 */
public final class SignalBeaconGameTest implements FabricClientGameTest {
    private static final int Y = GameTestSession.GROUND_Y + 1;
    private static final BlockPos BEACON = new BlockPos(6, Y + 1, 26);
    private static final BlockPos LENS = BEACON.above();
    private static final BlockPos BASE_CORNER = BEACON.below().north().west();
    private static final BlockPos STAND = BEACON.south(6).below();
    private static final BlockPos PANEL = BEACON.east(5).below();
    private static final String CODE = "52817364";
    // Vanilla works a beacon's level out every 80 ticks, and shows no beam before.
    private static final int BEAM_TICKS = 200;
    private static final int ANSWER_TICKS = 80;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "signal_beacon", SignalBeaconGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        session.context().waitTicks(25);
        pyramid(session, "minecraft:iron_block");
        setblock(session, BEACON, "minecraft:beacon");
        String slug = beacon(session);
        colours(session, slug);
        blink(session, slug);
        beaconBelow(session);
        alarm(session, slug);
        Optional<BrokerConfig> broker = session.broker();
        if (broker.isEmpty()) {
            session.skip("Home Assistant's light JSON and an alarm state on the broker", "no broker (" + GameTestSession.BROKER_ENV + " is not set)");
        } else {
            published(session, slug, broker.get());
        }
    }

    /** Set by command on the beacon: a Signal Beacon device, off and white, that finds the beacon on its pyramid. */
    private static String beacon(GameTestSession session) {
        setblock(session, LENS, "mineqtt:signal_beacon");
        session.awaitServer(s -> s.overworld().getBlockEntity(LENS) instanceof DeviceHost host && host.deviceLink().attached());
        session.check(session.onServer(s -> {
            var blockEntity = s.overworld().getBlockEntity(LENS);
            return ((DeviceHost) blockEntity).deviceLink().claim(blockEntity, session.playerId(), session.playerName());
        }), "the player claims the Signal Beacon");
        session.check(SignalBeacon.TYPE.equals(session.device(LENS, DeviceLink::type)), "a placed Signal Beacon block is a Signal Beacon device");
        JsonObject state = state(session);
        session.check(state.get("state").getAsString().equals("OFF") && state.get("dye").getAsString().equals("white") && state.get("effect").getAsString().equals("off")
                        && state.get("color_mode").getAsString().equals("rgb") && state.get("brightness").getAsInt() == 255,
                "it is off and white, a steady rgb light at full brightness (" + state + ")");
        session.check(session.await(() -> status(session) == Status.BEAM, ANSWER_TICKS), "it finds the beacon on its pyramid (" + state(session) + ")");
        session.check(serverBeam(session).isEmpty(), "off, it stops the beam: the beacon shows none (" + serverBeam(session) + ")");
        return session.device(LENS, DeviceLink::slug);
    }

    /** Red, a dye by name, Home Assistant's pure green; off again. The client's own beacon draws the same beam. */
    private static void colours(GameTestSession session, String slug) {
        session.consoleCommand("mineqtt devices set " + slug + " {\"state\":\"ON\",\"color\":{\"r\":255,\"g\":0,\"b\":0}}");
        session.check(session.await(() -> beam(session).equals(new Beam(true, DyeColor.RED, Effect.OFF)), ANSWER_TICKS), "on in red (" + state(session) + ")");
        JsonObject state = state(session);
        session.check(state.get("dye").getAsString().equals("red") && state.get("color").equals(rgb(DyeColor.RED)),
                "the state names the red dye and its beam colour, not the colour asked for (" + state + ")");
        session.check(blockState(session).getValue(SignalBeaconBlock.COLOR) == DyeColor.RED && blockState(session).getValue(SignalBeaconBlock.LIT),
                "the block shows red, lit");
        session.check(session.await(() -> serverBeam(session).equals(Optional.of(beamColor(DyeColor.RED))), BEAM_TICKS),
                "the beacon's beam is red on the server (" + serverBeam(session) + ")");
        session.check(session.awaitClient(mc -> clientBeam(mc.level).equals(Optional.of(beamColor(DyeColor.RED))), BEAM_TICKS),
                "and in the client's beacon, from the synced block state (" + session.onClient(mc -> clientBeam(mc.level)) + ")");
        lookAtBeam(session);
        session.screenshot("signal_beacon_red");

        session.consoleCommand("mineqtt devices set " + slug + " {\"color\":\"light_blue\"}");
        session.check(session.await(() -> serverBeam(session).equals(Optional.of(beamColor(DyeColor.LIGHT_BLUE))), ANSWER_TICKS),
                "a dye name is that dye (" + serverBeam(session) + ")");
        session.consoleCommand("mineqtt devices set " + slug + " {\"color\":[0,255,0]}");
        session.check(session.await(() -> beam(session).dye() == DyeColor.LIME && serverBeam(session).equals(Optional.of(beamColor(DyeColor.LIME))), ANSWER_TICKS),
                "pure green is the lime dye, the green of the beams (" + state(session) + ")");

        session.consoleCommand("mineqtt devices set " + slug + " {\"state\":\"OFF\"}");
        session.check(session.await(() -> serverBeam(session).isEmpty() && !blockState(session).getValue(SignalBeaconBlock.LIT), ANSWER_TICKS),
                "off, the beam is gone and the block not lit (" + serverBeam(session) + ")");
        session.check(beam(session).dye() == DyeColor.LIME, "off keeps the dye for the next on");
    }

    /** With blink the beam shows and hides; a steady beam again without it. */
    private static void blink(GameTestSession session, String slug) {
        session.consoleCommand("mineqtt devices set " + slug + " {\"state\":\"ON\",\"color\":\"red\",\"effect\":\"blink\"}");
        session.check(session.await(() -> beam(session).equals(new Beam(true, DyeColor.RED, Effect.BLINK)), ANSWER_TICKS), "on, red, blinking");
        int shown = 0, hidden = 0, lit = 0, dark = 0;
        for (int tick = 0; tick < 4 * SignalBeacon.BLINK_HALF_PERIOD_TICKS; tick++) {
            session.context().waitTicks(1);
            if (serverBeam(session).isPresent()) shown++;
            else hidden++;
            if (blockState(session).getValue(SignalBeaconBlock.LIT)) lit++;
            else dark++;
        }
        session.check(shown > 0 && hidden > 0 && lit > 0 && dark > 0,
                "the beam comes and goes with the block's light (" + shown + " ticks shown, " + hidden + " hidden; lit " + lit + ", dark " + dark + ")");
        session.consoleCommand("mineqtt devices set " + slug + " {\"effect\":\"off\"}");
        session.check(session.await(() -> beam(session).effect() == Effect.OFF, ANSWER_TICKS), "the effect is off");
        session.context().waitTicks(SignalBeacon.BLINK_HALF_PERIOD_TICKS * 2);
        boolean steady = true;
        for (int tick = 0; tick < 2 * SignalBeacon.BLINK_HALF_PERIOD_TICKS; tick++) {
            session.context().waitTicks(1);
            steady &= serverBeam(session).equals(Optional.of(beamColor(DyeColor.RED)));
        }
        session.check(steady, "a steady red beam again");
    }

    /** A base block missing: inactive; the beacon missing: no beacon; both back: the beam. */
    private static void beaconBelow(GameTestSession session) {
        setblock(session, BASE_CORNER, "minecraft:dirt");
        session.check(session.await(() -> status(session) == Status.INACTIVE, ANSWER_TICKS), "without a whole base the beacon below is inactive (" + state(session) + ")");
        setblock(session, BASE_CORNER, "minecraft:iron_block");
        session.check(session.await(() -> status(session) == Status.BEAM, ANSWER_TICKS), "with its base again, the beam");
        setblock(session, BEACON, "minecraft:air");
        session.check(session.await(() -> status(session) == Status.NO_BEACON, ANSWER_TICKS), "without the beacon, no beacon (" + state(session) + ")");
        setblock(session, BEACON, "minecraft:beacon");
        session.check(session.await(() -> status(session) == Status.BEAM, ANSWER_TICKS), "the beacon back, the beam");
        session.check(session.await(() -> serverBeam(session).equals(Optional.of(beamColor(DyeColor.RED))), BEAM_TICKS),
                "and the new beacon's beam is red (" + serverBeam(session) + ")");
    }

    /**
     * An Alarm Panel's state wired to the alarm in-port: lime disarmed (the wire's seed), a blinking orange while arming,
     * red armed away, a blinking red triggered, lime again disarmed.
     */
    private static void alarm(GameTestSession session, String slug) {
        session.consoleCommand("mineqtt devices set " + slug + " {\"state\":\"OFF\"}");
        session.consoleCommand("setblock " + PANEL.getX() + " " + PANEL.getY() + " " + PANEL.getZ() + " mineqtt:alarm_panel[facing=north]");
        session.awaitServer(s -> s.overworld().getBlockEntity(PANEL) instanceof DeviceHost host && host.deviceLink().attached());
        session.check(session.onServer(s -> {
            var blockEntity = s.overworld().getBlockEntity(PANEL);
            return ((DeviceHost) blockEntity).deviceLink().claim(blockEntity, session.playerId(), session.playerName());
        }), "the player claims the Alarm Panel");
        String panel = session.device(PANEL, DeviceLink::slug);
        session.consoleCommand("mineqtt devices setting " + panel + " exit_delay 2");
        keypad(session, AlarmKeypadPayload.Op.SET_CODE, CODE);
        session.check(session.await(() -> session.onServer(s -> s.overworld().getBlockEntity(PANEL) instanceof AlarmPanelBlockEntity alarm && alarm.codeSet()),
                ANSWER_TICKS), "the panel has a code");

        session.consoleCommand("mineqtt wires add " + panel + ".state " + slug + ".alarm");
        session.check(session.await(() -> beam(session).equals(new Beam(true, SignalBeacon.DISARMED_DYE, Effect.OFF)), ANSWER_TICKS),
                "the wire's seed shows the disarmed panel: a steady lime beam (" + state(session) + ")");
        keypad(session, AlarmKeypadPayload.Op.ARM_AWAY, "");
        session.check(session.await(() -> beam(session).equals(new Beam(true, SignalBeacon.PENDING_DYE, Effect.BLINK)), ANSWER_TICKS),
                "arming, it blinks orange (" + state(session) + ")");
        session.check(session.await(() -> beam(session).equals(new Beam(true, SignalBeacon.ARMED_DYE, Effect.OFF)), ANSWER_TICKS),
                "armed away, a steady red beam (" + state(session) + ")");
        session.check(session.await(() -> serverBeam(session).equals(Optional.of(beamColor(DyeColor.RED))), BEAM_TICKS), "the beacon's beam is red");
        session.consoleCommand("mineqtt devices set " + panel + " {\"action\":\"TRIGGER\"}");
        session.check(session.await(() -> beam(session).equals(new Beam(true, SignalBeacon.ARMED_DYE, Effect.BLINK)), ANSWER_TICKS),
                "triggered, it blinks red (" + state(session) + ")");
        lookAtBeam(session);
        session.screenshot("signal_beacon_triggered");
        keypad(session, AlarmKeypadPayload.Op.DISARM, CODE);
        session.check(session.await(() -> beam(session).equals(new Beam(true, SignalBeacon.DISARMED_DYE, Effect.OFF)), ANSWER_TICKS),
                "disarmed with the code, a steady lime beam again (" + state(session) + ")");
    }

    /** With a broker: Home Assistant's light JSON and an alarm state on /set, and the retained state it reads. */
    private static void published(GameTestSession session, String slug, BrokerConfig broker) {
        String stateTopic = "mineqtt/" + GameTestSession.BRIDGE_ID + "/" + slug;
        try (BrokerProbe probe = BrokerProbe.connect(broker)) {
            probe.publish(stateTopic + "/set", "{\"state\":\"ON\",\"color\":{\"r\":0,\"g\":0,\"b\":255},\"effect\":\"off\"}", false);
            session.check(session.await(() -> beam(session).equals(new Beam(true, DyeColor.BLUE, Effect.OFF)), ANSWER_TICKS),
                    "Home Assistant's light JSON on /set makes it a steady blue (" + state(session) + ")");
            session.context().waitTicks(30);
            Optional<JsonObject> retained = probe.retained(stateTopic).map(text -> JsonParser.parseString(text).getAsJsonObject());
            session.check(retained.filter(json -> json.get("state").getAsString().equals("ON") && json.get("color_mode").getAsString().equals("rgb")
                            && json.get("brightness").getAsInt() == 255 && json.get("color").equals(rgb(DyeColor.BLUE)) && json.get("effect").getAsString().equals("off")
                            && json.get("dye").getAsString().equals("blue") && json.get("beacon").getAsString().equals("beam")).isPresent(),
                    "the retained state is the light Home Assistant reads: rgb, full brightness, the blue beam's colour (" + retained + ")");
            probe.publish(stateTopic + "/set/alarm", "triggered", false);
            session.check(session.await(() -> beam(session).equals(new Beam(true, DyeColor.RED, Effect.BLINK)), ANSWER_TICKS),
                    "an alarm state on /set/alarm shows it: triggered blinks red (" + state(session) + ")");
            probe.publish(stateTopic + "/set", "{\"state\":\"OFF\"}", false);
            session.check(session.await(() -> !beam(session).on(), ANSWER_TICKS), "OFF on /set turns it off");
        }
    }

    // The first layer of a pyramid under the beacon.
    private static void pyramid(GameTestSession session, String block) {
        BlockPos base = BEACON.below();
        session.consoleCommand("fill " + (base.getX() - 1) + " " + base.getY() + " " + (base.getZ() - 1) + " " + (base.getX() + 1) + " " + base.getY() + " "
                + (base.getZ() + 1) + " " + block);
    }

    // Turns the player towards the beam above the lens, beyond reach, so a screenshot shows it (no block to aim at there).
    private static void lookAtBeam(GameTestSession session) {
        session.context().runOnClient(mc -> mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(LENS.above(4))));
        session.context().waitTicks(5);
    }

    private static void keypad(GameTestSession session, AlarmKeypadPayload.Op op, String digits) {
        session.onServer(s -> {
            if (s.overworld().getBlockEntity(PANEL) instanceof AlarmPanelBlockEntity panel) panel.keypad(session.player(s), op, digits);
            return null;
        });
    }

    // The colour the last beam section shows, on the server; empty while the beacon shows no beam.
    private static Optional<Integer> serverBeam(GameTestSession session) {
        return session.onServer(s -> lastSection(s.overworld()));
    }

    private static Optional<Integer> clientBeam(Level level) {
        return level == null ? Optional.empty() : lastSection(level);
    }

    private static Optional<Integer> lastSection(Level level) {
        if (!(level.getBlockEntity(BEACON) instanceof BeaconBlockEntity beacon)) return Optional.empty();
        // BeaconBeamOwner.Section from 1.21.5, BeaconBlockEntity.BeaconBeamSection before; both give their colour.
        var sections = beacon.getBeamSections();
        return sections.isEmpty() ? Optional.empty() : Optional.of(sections.getLast().getColor() & 0xFFFFFF);
    }

    private static int beamColor(DyeColor dye) {
        return SignalBeacon.beamColor(dye);
    }

    private static JsonObject rgb(DyeColor dye) {
        int color = SignalBeacon.beamColor(dye);
        return JsonParser.parseString("{\"r\":" + ((color >> 16) & 0xFF) + ",\"g\":" + ((color >> 8) & 0xFF) + ",\"b\":" + (color & 0xFF) + "}").getAsJsonObject();
    }

    private static Beam beam(GameTestSession session) {
        return session.onServer(s -> s.overworld().getBlockEntity(LENS) instanceof SignalBeaconBlockEntity beacon ? beacon.beam() : Beam.DEFAULT);
    }

    private static Status status(GameTestSession session) {
        return session.onServer(s -> s.overworld().getBlockEntity(LENS) instanceof SignalBeaconBlockEntity beacon ? beacon.beaconStatus() : Status.NO_BEACON);
    }

    private static BlockState blockState(GameTestSession session) {
        return session.onServer(s -> s.overworld().getBlockState(LENS));
    }

    private static void setblock(GameTestSession session, BlockPos pos, String block) {
        session.consoleCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block);
    }

    private static JsonObject state(GameTestSession session) {
        return session.onServer(s -> {
            StateWriter state = new StateWriter();
            if (s.overworld().getBlockEntity(LENS) instanceof DeviceHost host) host.writeState(state);
            return state.json();
        });
    }
}

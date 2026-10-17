package art.rehra.mineqtt.device;

import art.rehra.mineqtt.blocks.SignalBeaconBlock;
import art.rehra.mineqtt.device.types.SignalBeacon;
import art.rehra.mineqtt.device.types.SignalBeacon.Beam;
import art.rehra.mineqtt.device.types.SignalBeacon.Effect;
import art.rehra.mineqtt.device.types.SignalBeacon.Status;
import art.rehra.mineqtt.flow.core.Colors;
import art.rehra.mineqtt.ha.HaEntity;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BeaconBeamBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dependency-free regression checks of the Signal Beacon, run by Gradle's verifySignalBeacon task: the beam palette
 * against the game (and the texture generator's table), which dye a colour becomes (Home Assistant's pure colours, dimmed
 * colours, dye names, colour temperatures), the light commands, the alarm states and what each shows, the blink's timing,
 * the state JSON Home Assistant reads and the saved form, the queued preview, and the stand-ins the beacon's beam scan
 * sees.
 */
public final class SignalBeaconCheck {
    /** The beam colours of the dyes as PythonToolbox/generate_signal_beacon_texture.py draws them. */
    private static final Map<DyeColor, Integer> PALETTE = palette();

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        palette_();
        nearest();
        commands();
        alarm();
        blink();
        stateJson();
        saved();
        preview();
        standIns();
        System.out.println("Signal Beacon checks passed.");
    }

    /** Vanilla's beam colour of every dye is the table the textures are drawn from, and maps back onto its own dye. */
    private static void palette_() {
        for (DyeColor dye : DyeColor.values()) {
            check(SignalBeacon.beamColor(dye) == PALETTE.get(dye), dye + ": the beam colour is " + Integer.toHexString(SignalBeacon.beamColor(dye))
                    + ", the texture generator draws " + Integer.toHexString(PALETTE.get(dye)));
            check(SignalBeacon.nearest(SignalBeacon.beamColor(dye)) == dye, dye + ": its own beam colour is that dye");
        }
    }

    /** Home Assistant's colour picker sends full-value colours; each finds the dye of its hue, greys go by brightness. */
    private static void nearest() {
        Map<Integer, DyeColor> expected = new LinkedHashMap<>();
        expected.put(0xFF0000, DyeColor.RED);
        expected.put(0x00FF00, DyeColor.LIME);
        expected.put(0x0000FF, DyeColor.BLUE);
        expected.put(0xFFFF00, DyeColor.YELLOW);
        expected.put(0x00FFFF, DyeColor.CYAN);
        expected.put(0xFF00FF, DyeColor.MAGENTA);
        expected.put(0xFFA500, DyeColor.ORANGE);
        expected.put(0xFF8000, DyeColor.ORANGE);
        expected.put(0xFF69B4, DyeColor.PINK);
        expected.put(0xA020F0, DyeColor.PURPLE);
        expected.put(0x8B4513, DyeColor.BROWN);
        expected.put(Colors.hs(195, 75), DyeColor.LIGHT_BLUE);
        expected.put(0xFFFFFF, DyeColor.WHITE);
        expected.put(0x808080, DyeColor.LIGHT_GRAY);
        expected.put(0x555555, DyeColor.GRAY);
        expected.put(0x000000, DyeColor.BLACK);
        // Home Assistant dims a light without brightness by scaling its colour: a dim red stays red.
        expected.put(0x800000, DyeColor.RED);
        expected.put(0x330000, DyeColor.RED);
        expected.put(0x0A0000, DyeColor.BLACK);
        expected.forEach((rgb, dye) -> check(SignalBeacon.nearest(rgb) == dye,
                "#" + String.format("%06x", rgb) + " is " + dye + ", not " + SignalBeacon.nearest(rgb)));
    }

    private static void commands() {
        Beam off = Beam.DEFAULT;
        check(off.equals(new Beam(false, DyeColor.WHITE, Effect.OFF)), "A new beacon is off, white, steady");
        check(off.merge(json("{\"state\":\"ON\"}")).on() && !off.merge(json("{\"state\":\"OFF\"}")).on() && off.merge(json("{\"state\":\"TOGGLE\"}")).on()
                        && off.merge(json("{\"state\":true}")).on() && !off.merge(json("{\"state\":\"maybe\"}")).on(),
                "state: ON, OFF, TOGGLE and booleans; anything else keeps it");
        check(off.merge(json("{\"color\":{\"r\":255,\"g\":0,\"b\":0}}")).equals(new Beam(false, DyeColor.RED, Effect.OFF)),
                "A colour alone sets the dye and leaves the beacon off, as a lamp keeps its state");
        check(off.merge(json("{\"color\":\"cyan\"}")).dye() == DyeColor.CYAN && off.merge(json("{\"color\":\"Light Blue\"}")).dye() == DyeColor.LIGHT_BLUE
                        && off.merge(json("{\"color\":\"black\"}")).dye() == DyeColor.BLACK,
                "A dye name is that dye exactly, not the nearest to its text colour");
        check(off.merge(json("{\"color\":\"#3c44aa\"}")).dye() == DyeColor.BLUE && off.merge(json("{\"color\":[0,255,0]}")).dye() == DyeColor.LIME
                        && off.merge(json("{\"color\":{\"h\":0,\"s\":100}}")).dye() == DyeColor.RED,
                "Hex, arrays and hue/saturation read like the lamp reads them");
        check(off.merge(json("{\"color\":{\"r\":255,\"g\":255,\"b\":255},\"brightness\":128}")).dye() == DyeColor.LIGHT_GRAY
                        && off.merge(json("{\"color\":{\"r\":255,\"g\":0,\"b\":0},\"brightness\":128}")).dye() == DyeColor.RED,
                "A brightness beside a colour scales it first: a dimmed white is a grey, a dimmed red still red");
        Beam red = new Beam(true, DyeColor.RED, Effect.OFF);
        check(red.merge(json("{\"brightness\":10}")).equals(red) && red.merge(json("{\"transition\":3}")).equals(red),
                "A brightness alone and a transition change nothing: the beam has one brightness and no fade");
        check(off.merge(json("{\"color_temp_kelvin\":2000}")).dye() == DyeColor.ORANGE && off.merge(json("{\"color_temp_kelvin\":6500}")).dye() == DyeColor.WHITE
                        && off.merge(json("{\"color_temp\":500}")).dye() == DyeColor.ORANGE,
                "A colour temperature (kelvin, or mireds as v1 sent them) is the dye of its colour");
        check(red.merge(json("{\"effect\":\"blink\"}")).effect() == Effect.BLINK && red.merge(json("{\"effect\":\"BLINK\"}")).effect() == Effect.BLINK
                        && new Beam(true, DyeColor.RED, Effect.BLINK).merge(json("{\"effect\":\"none\"}")).effect() == Effect.OFF
                        && red.merge(json("{\"effect\":\"candle\"}")).effect() == Effect.OFF,
                "effect: off (or none) and blink; an effect the beacon has not keeps its own");
        check(red.merge(json("{\"color\":{\"r\":1}, \"state\":\"OFF\"}")).equals(new Beam(false, DyeColor.BLACK, Effect.OFF)),
                "Missing colour parts are 0, and a nearly black red is black");
        check(red.merge(json("{\"color\":\"nonsense\"}")).equals(red) && red.merge(json("{\"color\":{\"x\":\"y\"}}")).equals(red), "An unreadable colour is ignored");
        check(red.placed().equals(new Beam(false, DyeColor.RED, Effect.OFF)), "A placed copy keeps its dye and effect, off");
        check(SignalBeacon.knows(new CommandReader(json("{\"state\":\"ON\"}"))) && SignalBeacon.knows(new CommandReader(json("{\"alarm\":\"armed_away\"}")))
                        && SignalBeacon.knows(new CommandReader(json("{\"color_temp\":300}"))) && !SignalBeacon.knows(new CommandReader(json("{\"power\":3}"))),
                "Light keys and alarm states are signal beacon commands; nothing else is");
    }

    private static void alarm() {
        Map<String, Beam> expected = new LinkedHashMap<>();
        expected.put("disarmed", new Beam(true, DyeColor.LIME, Effect.OFF));
        expected.put("arming", new Beam(true, DyeColor.ORANGE, Effect.BLINK));
        expected.put("pending", new Beam(true, DyeColor.ORANGE, Effect.BLINK));
        expected.put("disarming", new Beam(true, DyeColor.ORANGE, Effect.BLINK));
        expected.put("armed_home", new Beam(true, DyeColor.RED, Effect.OFF));
        expected.put("armed_away", new Beam(true, DyeColor.RED, Effect.OFF));
        expected.put("armed_night", new Beam(true, DyeColor.RED, Effect.OFF));
        expected.put("armed_vacation", new Beam(true, DyeColor.RED, Effect.OFF));
        expected.put("armed_custom_bypass", new Beam(true, DyeColor.RED, Effect.OFF));
        expected.put("triggered", new Beam(true, DyeColor.RED, Effect.BLINK));
        check(expected.keySet().equals(new java.util.LinkedHashSet<>(HaEntity.AlarmControlPanel.STATES)), "Every Home Assistant alarm state has its beam");
        expected.forEach((state, beam) -> check(SignalBeacon.alarm(new JsonPrimitive(state)).equals(java.util.Optional.of(beam)), state + " shows " + beam));
        check(SignalBeacon.alarm(new JsonPrimitive(" Triggered ")).isPresent(), "Alarm states read without regard to case or spaces");
        check(SignalBeacon.alarm(new JsonPrimitive("armed")).isEmpty() && SignalBeacon.alarm(new JsonPrimitive(3)).isEmpty() && SignalBeacon.alarm(null).isEmpty()
                        && SignalBeacon.alarm(json("{}")).isEmpty(),
                "Anything else is no alarm state");
        Beam off = Beam.DEFAULT;
        check(SignalBeacon.apply(off, json("{\"alarm\":\"triggered\",\"effect\":\"off\"}")).equals(new Beam(true, DyeColor.RED, Effect.OFF)),
                "The light keys of a command override what its alarm state shows");
        check(SignalBeacon.apply(off, json("{\"alarm\":\"burgled\"}")).equals(off), "An unreadable alarm state changes nothing");
        // The Alarm Panel's states are all among the in-port's names, so its state out-port wires to it.
        for (var state : art.rehra.mineqtt.device.types.AlarmPanel.AlarmState.values()) {
            check(SignalBeacon.ALARM_ACTION.kind().problem(new JsonPrimitive(state.getSerializedName())).isEmpty(), state + " fits the alarm in-port");
        }
    }

    /** A blink shows the beam for half a second and hides it as long, every beacon at the same game time alike. */
    private static void blink() {
        Beam blinking = new Beam(true, DyeColor.RED, Effect.BLINK);
        int half = SignalBeacon.BLINK_HALF_PERIOD_TICKS;
        check(half == 10, "Blinks once a second");
        for (long t = 0; t < 4L * half; t++) {
            boolean lit = (t / half) % 2 == 0;
            check(blinking.lit(t) == lit, "At tick " + t + " the blink is " + (lit ? "on" : "off"));
        }
        check(blinking.lit(-1) == blinking.lit(2L * half - 1) && blinking.lit(-half) == blinking.lit(half), "Negative game times blink in the same rhythm");
        Beam steady = new Beam(true, DyeColor.RED, Effect.OFF);
        Beam dark = new Beam(false, DyeColor.RED, Effect.BLINK);
        for (long t = 0; t < 2L * half; t++) {
            check(steady.lit(t) && !dark.lit(t), "A steady beam always shows, one that is off never");
        }
    }

    /** What Home Assistant's JSON light reads, and that it reads back as the same beam. */
    private static void stateJson() {
        JsonObject state = SignalBeacon.state(new Beam(true, DyeColor.LIGHT_BLUE, Effect.BLINK), Status.BEAM, GlobalPos.of(Level.OVERWORLD, new BlockPos(1, 2, 3)));
        check(state.get("state").getAsString().equals("ON") && state.get("brightness").getAsInt() == 255 && state.get("color_mode").getAsString().equals("rgb")
                        && state.get("color").equals(json("{\"r\":58,\"g\":179,\"b\":218}")) && state.get("effect").getAsString().equals("blink")
                        && state.get("dye").getAsString().equals("light_blue") && state.get("beacon").getAsString().equals("beam") && state.has("location"),
                "The state carries the light's keys with the beam colour, the dye and the beacon: " + state);
        for (DyeColor dye : DyeColor.values()) {
            for (boolean on : new boolean[]{false, true}) {
                for (Effect effect : Effect.values()) {
                    Beam beam = new Beam(on, dye, effect);
                    JsonObject written = new StateWriter().put(SignalBeacon.LIGHT, beam).json();
                    check(SignalBeacon.LIGHT.parse(written).equals(java.util.Optional.of(beam)), beam + " reads back from its state " + written);
                }
            }
        }
        check(SignalBeacon.LIGHT.parse(json("{}")).equals(java.util.Optional.of(Beam.DEFAULT)), "An empty state reads as the default");
        check(SignalBeacon.LIGHT.parse(json("{\"state\":\"ON\",\"brightness\":1,\"color\":{\"r\":176,\"g\":46,\"b\":38}}")).orElseThrow().dye() == DyeColor.RED,
                "A state's brightness never dims the colour it is read with");
        check(Status.NO_BEACON.getSerializedName().equals("no_beacon") && Effect.names().equals(List.of("off", "blink")), "The names Home Assistant and wires see");
    }

    private static void saved() {
        for (DyeColor dye : DyeColor.values()) {
            Beam beam = new Beam(true, dye, Effect.BLINK);
            Tag tag = Beam.CODEC.encodeStart(NbtOps.INSTANCE, beam).getOrThrow();
            check(Beam.CODEC.parse(NbtOps.INSTANCE, tag).result().equals(java.util.Optional.of(beam)), beam + " survives a save");
        }
        JsonElement broken = json("{\"on\":\"maybe\",\"dye\":\"plaid\",\"effect\":7}");
        check(Beam.CODEC.parse(JsonOps.INSTANCE, broken).result().equals(java.util.Optional.of(Beam.DEFAULT)), "Unreadable saved values keep their default");
    }

    /** An unloaded beacon's queued command changes its state as a loaded one would. */
    private static void preview() {
        JsonObject state = SignalBeacon.state(Beam.DEFAULT, Status.NO_BEACON, GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO));
        JsonObject after = SignalBeacon.TYPE.preview(state, json("{\"alarm\":\"armed_away\"}"));
        check(after.get("state").getAsString().equals("ON") && after.get("dye").getAsString().equals("red") && after.get("effect").getAsString().equals("off")
                        && after.get("beacon").getAsString().equals("no_beacon") && after.has("location"),
                "A queued alarm state previews as the red beam, the rest of the state kept: " + after);
        JsonObject toggled = SignalBeacon.TYPE.preview(after, json("{\"state\":\"TOGGLE\",\"color\":\"lime\"}"));
        check(toggled.get("state").getAsString().equals("OFF") && toggled.get("dye").getAsString().equals("lime"), "A queued toggle and colour preview: " + toggled);
    }

    /**
     * The beam scan sees stained glass of the dye while lit and tinted glass, which stops a beam, while not ({@code
     * SignalBeaconBlock#beamStandIn}; the block itself is checked in the game test, as no block can be made once the
     * registries are frozen). Any other block is itself.
     */
    private static void standIns() {
        for (DyeColor dye : DyeColor.values()) {
            check(SignalBeaconBlock.stainedGlass(dye) instanceof BeaconBeamBlock glass && glass.getColor() == dye, dye + ": its stand-in is " + dye + " stained glass");
        }
        for (BlockState other : List.of(Blocks.STONE.defaultBlockState(), SignalBeaconBlock.stainedGlass(DyeColor.RED).defaultBlockState(), Blocks.BEACON.defaultBlockState())) {
            check(SignalBeaconBlock.beamStandIn(other) == other, other + " is itself in the beam scan");
        }
    }

    private static Map<DyeColor, Integer> palette() {
        Map<DyeColor, Integer> palette = new LinkedHashMap<>();
        palette.put(DyeColor.WHITE, 0xF9FFFE);
        palette.put(DyeColor.ORANGE, 0xF9801D);
        palette.put(DyeColor.MAGENTA, 0xC74EBD);
        palette.put(DyeColor.LIGHT_BLUE, 0x3AB3DA);
        palette.put(DyeColor.YELLOW, 0xFED83D);
        palette.put(DyeColor.LIME, 0x80C71F);
        palette.put(DyeColor.PINK, 0xF38BAA);
        palette.put(DyeColor.GRAY, 0x474F52);
        palette.put(DyeColor.LIGHT_GRAY, 0x9D9D97);
        palette.put(DyeColor.CYAN, 0x169C9C);
        palette.put(DyeColor.PURPLE, 0x8932B8);
        palette.put(DyeColor.BLUE, 0x3C44AA);
        palette.put(DyeColor.BROWN, 0x835432);
        palette.put(DyeColor.GREEN, 0x5E7C16);
        palette.put(DyeColor.RED, 0xB02E26);
        palette.put(DyeColor.BLACK, 0x1D1D21);
        return palette;
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}

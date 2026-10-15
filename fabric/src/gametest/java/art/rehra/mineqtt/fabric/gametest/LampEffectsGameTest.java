package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.compat.NbtTags;
import art.rehra.mineqtt.compat.ChunkPositions;
import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.blocks.RgbLedBlock;
import art.rehra.mineqtt.client.RgbLedBlockColor;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.RenderTag;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Lamp effects and transitions (M2-25) from a real client. The player places a Smart Lamp. A command with a transition
 * of 2 seconds raises its block light step by step over 40 ticks on the server and on the client, and the client's tint
 * with it, while the lamp publishes one state, the target; a fade back down works the same way. A command naming a
 * fade's own target ends it without a transition and shortens it with a shorter one. A candle and a pulse animate the
 * tint on the client alone, the block light stays, and the update tag carries the effect; {@code off} ends it. A lamp in
 * a loaded chunk outside the simulation distance, where its ticker stands still, takes a transition's target at once.
 */
public final class LampEffectsGameTest implements FabricClientGameTest {
    private static final BlockPos LAMP_GROUND = new BlockPos(0, GameTestSession.GROUND_Y, 3);
    private static final BlockPos STAND = new BlockPos(0, GameTestSession.GROUND_Y + 1, 0);
    // Six chunks east of the lamp: within the view distance below, outside the simulation distance.
    private static final BlockPos FAR = new BlockPos(6 * 16, GameTestSession.GROUND_Y + 1, 0);
    private static final int FAR_SIMULATION_DISTANCE = 2;
    private static final int FAR_VIEW_DISTANCE = 10;
    private static final int ANSWER_TICKS = 40;
    // The transition of the fade up, 2 s, and a few ticks more to see the end.
    private static final int FADE_TICKS = 40;
    private static final int SETTLE_TICKS = 10;
    // A command the block shows at once, not as a fade: within these few ticks.
    private static final int AT_ONCE_TICKS = 3;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "lamp-effects", LampEffectsGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        // The server resends an unconfirmed teleport after 20 ticks, with its rotation, which would undo an aim (M2-21).
        session.context().waitTicks(25);
        BlockPos lamp = session.place(MineqttBlocks.RGB_LED_BLOCK.get(), LAMP_GROUND);
        session.awaitServer(s -> s.overworld().getBlockEntity(lamp) instanceof DeviceHost host && host.deviceLink().attached());
        String slug = session.device(lamp, DeviceLink::slug);
        session.aim(lamp);

        Optional<BrokerConfig> broker = session.broker();
        if (broker.isEmpty()) {
            session.skip("the fade publishes one state, the target", "no broker (" + GameTestSession.BROKER_ENV + " is not set)");
            fades(session, lamp, slug, null);
        } else {
            String stateTopic = "mineqtt/" + GameTestSession.BRIDGE_ID + "/" + slug;
            try (BrokerProbe probe = BrokerProbe.connect(broker.get());
                 BrokerProbe.Listener states = probe.listen(stateTopic)) {
                // The placement's state may still be on its way; only the fade's count.
                session.context().waitTicks(SETTLE_TICKS);
                while (states.nextLive().isPresent()) {
                    session.info("before the fade: a state of the placement");
                }
                fades(session, lamp, slug, states);
                Optional<String> retained = probe.retained(stateTopic);
                session.check(retained.map(json -> JsonParser.parseString(json).getAsJsonObject().get("brightness").getAsInt() == 200).orElse(false),
                        "the retained state is the target: " + retained.orElse("nothing"));
            }
        }
        effects(session, lamp, slug);
        outsideSimulation(session, lamp, slug);
    }

    /** A fade up over 2 s and down over 1 s, sampled every tick on the server and the client; states without a broker. */
    private static void fades(GameTestSession session, BlockPos lamp, String slug, @Nullable BrokerProbe.Listener states) {
        session.consoleCommand("mineqtt devices set " + slug + " {\"state\":\"ON\",\"brightness\":200,\"color\":{\"r\":255,\"g\":255,\"b\":255},\"transition\":2}");
        List<Integer> serverLevels = new ArrayList<>();
        List<Integer> clientLevels = new ArrayList<>();
        Set<Integer> tints = new HashSet<>();
        for (int tick = 0; tick < FADE_TICKS + SETTLE_TICKS; tick++) {
            serverLevels.add(session.onServer(s -> s.overworld().getBlockState(lamp).getValue(RgbLedBlock.LIGHT_LEVEL)));
            clientLevels.add(session.onClient(mc -> mc.level.getBlockState(lamp).getValue(RgbLedBlock.LIGHT_LEVEL)));
            tints.add(session.onClient(mc -> tint(mc, lamp)));
            if (tick == FADE_TICKS / 2) session.screenshot("lamp_effects_fade_half");
            session.context().waitTick();
        }
        session.check(serverLevels.getFirst() <= 1 && rising(serverLevels) && serverLevels.getLast() == 12,
                "the server's block light rises from dark to 12 and never falls: " + serverLevels);
        int reached = serverLevels.indexOf(12);
        session.check(reached >= FADE_TICKS - 6 && reached <= FADE_TICKS + 2 && serverLevels.stream().distinct().count() >= 8,
                "in steps over the 40 ticks of the transition, reaching 12 at tick " + reached);
        session.check(rising(clientLevels) && clientLevels.getLast() == 12 && clientLevels.stream().distinct().count() >= 8,
                "the client's block light follows: " + clientLevels);
        session.check(tints.size() >= 8, "and so does the client's tint, in " + tints.size() + " shades");
        session.check(stateOf(session, lamp).get("brightness").getAsInt() == 200, "the lamp's state is the target");
        if (states != null) {
            List<JsonObject> published = new ArrayList<>();
            Optional<String> message;
            while ((message = states.nextLive()).isPresent()) published.add(JsonParser.parseString(message.get()).getAsJsonObject());
            session.check(published.size() == 1 && published.getFirst().get("brightness").getAsInt() == 200
                            && published.getFirst().get("light_level").getAsInt() == 12 && published.getFirst().get("state").getAsString().equals("ON"),
                    "one state was published for the fade, the target with its light level: " + published);
        }
        session.screenshot("lamp_effects_fade_end");

        session.consoleCommand("mineqtt devices set " + slug + " {\"state\":\"OFF\",\"transition\":1}");
        List<Integer> down = new ArrayList<>();
        for (int tick = 0; tick < FADE_TICKS / 2 + SETTLE_TICKS; tick++) {
            down.add(session.onServer(s -> s.overworld().getBlockState(lamp).getValue(RgbLedBlock.LIGHT_LEVEL)));
            session.context().waitTick();
        }
        session.check(down.getFirst() >= 11 && falling(down) && down.getLast() == 0 && down.stream().distinct().count() >= 6,
                "a transition of 1 s fades it back down to dark: " + down);

        // A minute's fade up, then its own target without a transition: the block shows it at once.
        session.consoleCommand("mineqtt devices set " + slug + " {\"state\":\"ON\",\"brightness\":200,\"transition\":60}");
        session.context().waitTicks(SETTLE_TICKS);
        int slow = serverLevel(session, lamp);
        session.consoleCommand("mineqtt devices set " + slug + " {\"state\":\"ON\",\"brightness\":200}");
        boolean ended = session.await(() -> serverLevel(session, lamp) == 12, AT_ONCE_TICKS);
        session.check(slow < 12 && ended, "the same target without a transition ends a fade at once: from " + slow + " to " + serverLevel(session, lamp));
        session.check(session.awaitClient(mc -> mc.level.getBlockState(lamp).getValue(RgbLedBlock.LIGHT_LEVEL) == 12, ANSWER_TICKS),
                "and the client shows the target");
        // A minute's fade down, then its own target with a transition of 1 s: dark after about 20 ticks, not 1200.
        session.consoleCommand("mineqtt devices set " + slug + " {\"state\":\"OFF\",\"transition\":60}");
        session.context().waitTicks(SETTLE_TICKS);
        session.consoleCommand("mineqtt devices set " + slug + " {\"state\":\"OFF\",\"transition\":1}");
        int still = serverLevel(session, lamp);
        boolean dark = session.await(() -> serverLevel(session, lamp) == 0, FADE_TICKS / 2 + SETTLE_TICKS);
        session.check(still > 0 && dark, "the same target with a shorter transition fades from where it stood, from " + still + " to dark within 1 s");
    }

    /** A candle and a pulse on the client alone, then off. */
    private static void effects(GameTestSession session, BlockPos lamp, String slug) {
        session.consoleCommand("mineqtt devices set " + slug + " {\"state\":\"ON\",\"brightness\":255,\"color\":{\"r\":255,\"g\":150,\"b\":40},\"effect\":\"candle\"}");
        session.check(session.awaitClient(mc -> mc.level.getBlockState(lamp).getValue(RgbLedBlock.LIGHT_LEVEL) == 15, ANSWER_TICKS), "the candle lamp is lit");
        CompoundTag render = NbtTags.getCompoundOrEmpty(session.onServer(s -> s.overworld().getBlockEntity(lamp).getUpdateTag(s.registryAccess())), RenderTag.KEY);
        session.check(NbtTags.getStringOr(render, "effect", "").equals("candle"), "its update tag carries the effect: " + render);
        int full = 0xFF000000 | 0xFF9628;
        Set<Integer> flame = sampleTints(session, lamp, 60);
        Set<Integer> serverLevels = new HashSet<>();
        for (int tick = 0; tick < 10; tick++) {
            serverLevels.add(session.onServer(s -> s.overworld().getBlockState(lamp).getValue(RgbLedBlock.LIGHT_LEVEL)));
            session.context().waitTick();
        }
        session.check(flame.size() >= 3 && flame.stream().allMatch(tint -> channel(tint, 16) <= 255 && channel(tint, 16) >= 255 / 2),
                "a candle flickers the client's tint between half and full: " + hex(flame));
        session.check(serverLevels.equals(Set.of(15)), "while the block light stays at 15: " + serverLevels);
        session.screenshot("lamp_effects_candle");
        session.context().waitTicks(3);
        session.screenshot("lamp_effects_candle_later");

        session.consoleCommand("mineqtt devices set " + slug + " {\"effect\":\"pulse\"}");
        session.check(session.awaitClient(mc -> tint(mc, lamp) == full, ANSWER_TICKS), "a pulse comes back to the full tint");
        session.screenshot("lamp_effects_pulse_bright");
        session.check(session.awaitClient(mc -> channel(tint(mc, lamp), 16) <= 255 * 0.35, ANSWER_TICKS), "and dims to about a third");
        session.screenshot("lamp_effects_pulse_dim");
        Set<Integer> pulse = sampleTints(session, lamp, 40);
        session.check(pulse.size() >= 5, "a pulse moves through the shades between: " + hex(pulse));

        session.consoleCommand("mineqtt devices set " + slug + " {\"effect\":\"off\"}");
        session.check(session.awaitClient(mc -> tint(mc, lamp) == full, ANSWER_TICKS), "off brings the full tint back");
        Set<Integer> steady = sampleTints(session, lamp, 20);
        session.check(steady.equals(Set.of(full)), "and keeps it: " + hex(steady));
        session.check(stateOf(session, lamp).get("effect").getAsString().equals("off"), "the state says the effect is off");
    }

    /**
     * The player moves six chunks away and the simulation distance drops to 2: the lamp's chunk stays loaded and its
     * device attached, but no block ticks there. A transition's target shows at once instead of a fade that stands still.
     */
    private static void outsideSimulation(GameTestSession session, BlockPos lamp, String slug) {
        int simulation = session.onServer(s -> s.getPlayerList().getSimulationDistance());
        int view = session.onClient(mc -> mc.options.renderDistance().get());
        session.context().runOnClient(mc -> mc.options.renderDistance().set(Math.max(view, FAR_VIEW_DISTANCE)));
        session.onServer(s -> {
            s.getPlayerList().setSimulationDistance(FAR_SIMULATION_DISTANCE);
            return null;
        });
        session.context().waitTicks(SETTLE_TICKS);
        session.teleport(FAR);
        long chunk = ChunkPositions.pack(lamp);
        boolean loadedNotTicking = session.await(() -> session.onServer(s -> s.overworld().getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) != null
                && !ChunkPositions.isEntityTicking(s.overworld(), lamp)), ANSWER_TICKS);
        session.check(loadedNotTicking && Boolean.TRUE.equals(session.device(lamp, DeviceLink::attached)),
                "far away, the lamp's chunk is loaded and its device attached, but its blocks do not tick");
        if (loadedNotTicking) {
            session.consoleCommand("mineqtt devices set " + slug + " {\"state\":\"ON\",\"brightness\":200,\"color\":{\"r\":255,\"g\":255,\"b\":255},\"transition\":2}");
            boolean shown = session.await(() -> serverLevel(session, lamp) == 12, AT_ONCE_TICKS);
            session.check(shown, "a transition outside the simulation distance shows its target at once: light level " + serverLevel(session, lamp));
            // White at light level 12: each channel at 12/15.
            session.check(session.awaitClient(mc -> mc.level.getBlockState(lamp).getValue(RgbLedBlock.LIGHT_LEVEL) == 12 && tint(mc, lamp) == 0xFFCCCCCC, ANSWER_TICKS),
                    "and the client, which has the chunk, draws it");
            session.check(stateOf(session, lamp).get("brightness").getAsInt() == 200, "the lamp's state is the target");
        }
        session.onServer(s -> {
            s.getPlayerList().setSimulationDistance(simulation);
            return null;
        });
        session.context().runOnClient(mc -> mc.options.renderDistance().set(view));
        session.teleport(STAND);
    }

    private static int serverLevel(GameTestSession session, BlockPos lamp) {
        return session.onServer(s -> s.overworld().getBlockState(lamp).getValue(RgbLedBlock.LIGHT_LEVEL));
    }

    private static Set<Integer> sampleTints(GameTestSession session, BlockPos lamp, int ticks) {
        Set<Integer> tints = new HashSet<>();
        for (int tick = 0; tick < ticks; tick++) {
            tints.add(session.onClient(mc -> tint(mc, lamp)));
            session.context().waitTick();
        }
        return tints;
    }

    private static JsonObject stateOf(GameTestSession session, BlockPos lamp) {
        return session.onServer(s -> {
            StateWriter writer = new StateWriter();
            ((DeviceHost) s.overworld().getBlockEntity(lamp)).writeState(writer);
            return writer.json();
        });
    }

    private static boolean rising(List<Integer> levels) {
        for (int i = 1; i < levels.size(); i++) if (levels.get(i) < levels.get(i - 1)) return false;
        return true;
    }

    private static boolean falling(List<Integer> levels) {
        for (int i = 1; i < levels.size(); i++) if (levels.get(i) > levels.get(i - 1)) return false;
        return true;
    }

    // The tint the lamp's block colour gives on the client, as the renderer asks for it.
    private static int tint(Minecraft mc, BlockPos pos) {
        return new RgbLedBlockColor().colorInWorld(mc.level.getBlockState(pos), mc.level, pos);
    }

    private static int channel(int argb, int shift) {
        return (argb >> shift) & 0xFF;
    }

    private static List<String> hex(Set<Integer> tints) {
        return tints.stream().sorted().map(Integer::toHexString).toList();
    }
}

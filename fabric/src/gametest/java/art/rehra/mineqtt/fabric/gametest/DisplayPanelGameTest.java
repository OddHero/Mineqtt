package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.blocks.DisplayPanelBlock;
import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.blocks.entities.DisplayPanelBlockEntity;
import art.rehra.mineqtt.client.DisplayPanelRenderState;
import art.rehra.mineqtt.client.DisplayPanelRenderer;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.types.DisplayPanel;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.twin.core.DisplayText;
import art.rehra.mineqtt.twin.core.TwinHealth;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
//? if >=1.21.9 {
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
//?}
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.phys.Vec3;

import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;

/**
 * The Display Panel on a real client (M3 plan M3-16): the non-op player places one against a wall and it faces out;
 * messages from the console and from Home Assistant's {@code /set} reach the client as finished lines in each size, one
 * to four of them, in a colour, glowing or not; a comparator behind a panel lights a redstone lamp for a message and not
 * for "off"; a point in time is worded by the client against its own clock, so it moves on without a render update. With
 * a broker, a panel bound to a statestream entity waits, then shows its name over the live value, follows a live change,
 * shows "Unavailable" while the entity has no value (its comparator 0), and its client block entity never holds the
 * entity. Screenshots {@code panel_*}, read by eye: the text, the large size, four lines, glow at night, the mirrored
 * value live and unavailable, a point in time, and readability at 3 and 8 blocks.
 */
public final class DisplayPanelGameTest implements FabricClientGameTest {
    private static final int Y = GameTestSession.GROUND_Y + 2;
    // A wall of stone at head height; the panels hang on its south face, one block apart.
    private static final int WALL_Z = 6;
    private static final BlockPos HAND = new BlockPos(-3, Y, WALL_Z + 1);
    private static final BlockPos LARGE = new BlockPos(0, Y, WALL_Z + 1);
    private static final BlockPos FOUR = new BlockPos(3, Y, WALL_Z + 1);
    private static final BlockPos MIRROR = new BlockPos(6, Y, WALL_Z + 1);
    // On the ground, so a comparator and a lamp can stand beside it: comparator east of it reading west, lamp beyond.
    private static final BlockPos GAUGE = new BlockPos(10, GameTestSession.GROUND_Y + 1, WALL_Z + 1);
    private static final BlockPos COMPARATOR = GAUGE.east();
    private static final BlockPos LAMP = GAUGE.east(2);
    private static final int ANSWER_TICKS = 100;
    private static final int TELEPORT_TICKS = 25;
    private static final int SETTLE_TICKS = 10;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "display-panel", DisplayPanelGameTest::run);
    }

    private static void run(GameTestSession session) {
        for (int x = -5; x <= 8; x++) setblock(session, new BlockPos(x, Y, WALL_Z), "minecraft:stone");
        setblock(session, GAUGE.north(), "minecraft:stone");
        session.consoleCommand("time set noon");

        placedByHand(session);
        sizesAndLines(session);
        colourAndGlow(session);
        comparator(session);
        time(session);
        if (session.broker().isPresent()) {
            fromHomeAssistant(session);
            mirror(session);
        } else {
            session.skip("Home Assistant's /set and a mirrored panel", GameTestSession.BROKER_ENV + " is not set");
        }
        readability(session);
    }

    /** The player places a panel against the wall's south face: it faces south, away from the wall, and is theirs. */
    private static void placedByHand(GameTestSession session) {
        session.teleport(HAND.south(3).below());
        session.context().waitTicks(TELEPORT_TICKS);
        session.hold(MineqttBlocks.DISPLAY_PANEL.get().asItem());
        session.aim(HAND.north(), Direction.SOUTH);
        session.pressUse();
        session.awaitServer(s -> s.overworld().getBlockEntity(HAND) instanceof DeviceHost host && host.deviceLink().attached());
        session.check(session.awaitClient(mc -> mc.level.getBlockState(HAND).is(MineqttBlocks.DISPLAY_PANEL.get()), ANSWER_TICKS),
                "the panel the player placed is on the client");
        session.check(session.onServer(s -> s.overworld().getBlockState(HAND).getValue(DisplayPanelBlock.FACING)) == Direction.SOUTH,
                "placed on a wall it faces away from it");
        session.check(session.playerName().equals(session.device(HAND, DeviceLink::ownerName)), "the panel is the player's");
        session.hold(Items.AIR);
    }

    /** The server's finished lines reach the client: auto picks a size, each size its lines, line breaks start lines. */
    private static void sizesAndLines(GameTestSession session) {
        set(session, HAND, "{\"message\":\"Washer done!\"}");
        awaitShown(session, HAND, render -> render.lines().equals(List.of("Washer", "done!")) && render.layout() == DisplayText.Size.LARGE,
                "Washer done! fits two large lines");
        look(session, HAND.south(3), HAND);
        session.screenshot("panel_text");
        devicePanel(session, LARGE);
        set(session, LARGE, "{\"message\":\"21.4 °C\",\"size\":\"large\"}");
        awaitShown(session, LARGE, render -> render.lines().equals(List.of("21.4 °C")) && render.layout() == DisplayText.Size.LARGE, "one large line");
        devicePanel(session, FOUR);
        set(session, FOUR, "{\"message\":\"Front door open\\nGarage closed\\nWasher done\\n21.4 °C outside\",\"size\":\"small\"}");
        awaitShown(session, FOUR, render -> render.lines().size() == 4 && render.lines().getFirst().equals("Front door open")
                && render.layout() == DisplayText.Size.SMALL, "four small lines, one per line break");
        set(session, FOUR, "{\"size\":\"medium\"}");
        awaitShown(session, FOUR, render -> render.layout() == DisplayText.Size.MEDIUM && render.lines().size() == 3
                && render.lines().getLast().endsWith(DisplayText.ELLIPSIS), "medium shows three lines and cuts the rest");
        set(session, FOUR, "{\"size\":\"small\"}");
        awaitShown(session, FOUR, render -> render.layout() == DisplayText.Size.SMALL && render.lines().size() == 4, "small again");
        DisplayPanelRenderState state = extract(session, FOUR, System.currentTimeMillis());
        session.check(state.lines.size() == 4 && state.title == null && state.facing == Direction.SOUTH, "the renderer draws the four lines facing south");
    }

    /** Colour and glow reach the renderer: the dye's text colour, full bright while it glows, the block's light otherwise. */
    private static void colourAndGlow(GameTestSession session) {
        set(session, LARGE, "{\"color\":\"red\",\"glow\":false}");
        awaitShown(session, LARGE, render -> render.color() == DyeColor.RED && !render.glow(), "red, not glowing");
        DisplayPanelRenderState dull = extract(session, LARGE, System.currentTimeMillis());
        session.check(dull.color == ARGB.opaque(DyeColor.RED.getTextColor()) && dull.textLight == dull.lightCoords && dull.textLight != LightCoordsUtil.FULL_BRIGHT,
                "a panel that does not glow is drawn red in its block's light");
        DisplayPanelRenderState bright = extract(session, HAND, System.currentTimeMillis());
        session.check(bright.textLight == LightCoordsUtil.FULL_BRIGHT && bright.color == ARGB.opaque(DyeColor.CYAN.getTextColor()),
                "a new panel glows cyan at full brightness");
        session.consoleCommand("time set midnight");
        look(session, LARGE.south(4), HAND.east());
        session.screenshot("panel_glow_night");
        session.consoleCommand("time set noon");
        set(session, LARGE, "{\"color\":\"cyan\",\"glow\":true}");
        awaitShown(session, LARGE, render -> render.color() == DyeColor.CYAN && render.glow(), "cyan and glowing again");
    }

    /** A comparator behind a panel lights a redstone lamp while the message is a word for on or any text, not for "off". */
    private static void comparator(GameTestSession session) {
        devicePanel(session, GAUGE);
        setblock(session, COMPARATOR, "minecraft:comparator[facing=west]");
        setblock(session, LAMP, "minecraft:redstone_lamp");
        set(session, GAUGE, "{\"message\":\"Washer done!\"}");
        session.check(session.await(() -> lit(session), ANSWER_TICKS), "text lights the lamp behind the comparator");
        session.check(session.onServer(s -> signal(s, GAUGE)) == 15,
                "the panel gives 15 for text");
        set(session, GAUGE, "{\"message\":\"7\"}");
        session.check(session.await(() -> session.onServer(s -> signal(s, GAUGE)) == 7,
                ANSWER_TICKS), "\"7\" gives 7");
        set(session, GAUGE, "{\"message\":\"off\"}");
        session.check(session.await(() -> !lit(session), ANSWER_TICKS), "off puts the lamp out");
    }

    private static boolean lit(GameTestSession session) {
        return session.onServer(s -> s.overworld().getBlockState(LAMP).getValue(RedstoneLampBlock.LIT));
    }

    /**
     * A message that names a point in time travels as that point; the client words it against its own clock, so later it
     * reads differently with no render update in between.
     */
    private static void time(GameTestSession session) {
        long now = System.currentTimeMillis();
        String at = Instant.ofEpochMilli(now + (2 * 60 + 14) * 60_000L + 30_000L).toString();
        set(session, LARGE, "{\"message\":\"" + at + "\",\"size\":\"auto\"}");
        awaitShown(session, LARGE, render -> render.at().isPresent() && render.lines().isEmpty(), "a point in time travels as epoch millis");
        DisplayPanel.Render sent = session.onClient(mc -> panel(mc, LARGE).shown());
        DisplayPanelRenderState first = extract(session, LARGE, now);
        DisplayPanelRenderState later = extract(session, LARGE, now + 5 * 60_000L);
        session.check(String.join(" ", first.lines).equals("in 2 h 14 min"), "the client words it: " + first.lines);
        session.check(String.join(" ", later.lines).equals("in 2 h 9 min"), "five minutes later it reads anew: " + later.lines);
        session.check(session.onClient(mc -> panel(mc, LARGE).shown()) == sent, "with no render update in between");
        look(session, LARGE.south(3), LARGE);
        session.screenshot("panel_time");
        set(session, LARGE, "{\"message\":\"21.4 °C\",\"size\":\"large\"}");
        awaitShown(session, LARGE, render -> render.lines().equals(List.of("21.4 °C")), "the large panel shows 21.4 °C again");
    }

    /** Home Assistant's /set of the message changes the lines the client draws. */
    private static void fromHomeAssistant(GameTestSession session) {
        String topic = MineQTTConfig.baseTopic + "/" + GameTestSession.BRIDGE_ID + "/" + slug(session, HAND) + "/set";
        try (BrokerProbe probe = BrokerProbe.connect(session.broker().orElseThrow())) {
            probe.publish(topic, "{\"message\":\"Dinner is ready\"}", false);
        }
        awaitShown(session, HAND, render -> render.lines().equals(List.of("Dinner is", "ready")), "Home Assistant's /set reaches the client's lines");
        look(session, HAND.south(3), HAND);
        session.screenshot("panel_from_ha");
    }

    /**
     * A panel bound to a statestream entity (the twin opened for it, the player made an operator for the binding):
     * waiting, then the retained value under its name, a live change, unavailable with its comparator 0; the client
     * block entity never holds the binding or the entity.
     */
    private static void mirror(GameTestSession session) {
        String object = ("dp_" + GameTestSession.BRIDGE_ID).replace('-', '_') + "_temperature";
        String entity = "sensor." + object;
        String leaves = MineQTTConfig.twinStatestreamBase + "/sensor/" + object + "/";
        List<String> savedAllowed = MineQTTConfig.twinAllowedEntities;
        session.consoleCommand("op " + session.playerName());
        try (BrokerProbe probe = BrokerProbe.connect(session.broker().orElseThrow())) {
            for (String leaf : List.of("state", "unit_of_measurement", "last_updated")) session.clearAtClose(leaves + leaf);
            MineQTTConfig.twinAllowedEntities = List.of("sensor.dp_*");
            MineqttRuntime.configChanged();
            session.context().waitTicks(SETTLE_TICKS);
            devicePanel(session, MIRROR);
            session.consoleCommand("mineqtt devices setting " + slug(session, MIRROR) + " name Outdoor");
            session.consoleCommand("mineqtt devices bind outdoor " + entity);
            session.check(session.await(() -> session.onRuntime(runtime -> runtime.devices().twinHealth(recordAt(runtime, MIRROR).id())
                    .filter(health -> health == TwinHealth.WAITING).isPresent()), ANSWER_TICKS), "bound, it waits for the entity");
            awaitShown(session, MIRROR, render -> render.waiting() && "Outdoor".equals(render.title()), "the client shows that it waits, under its name");
            DisplayPanelRenderState waiting = extract(session, MIRROR, System.currentTimeMillis());
            session.check(String.join(" ", waiting.lines).equals("Waiting for HA…") && "Outdoor".equals(waiting.title), "Waiting for HA… under its name: " + waiting.lines);

            probe.publish(leaves + "unit_of_measurement", "\"°C\"", true);
            probe.publish(leaves + "state", "12.5", true);
            awaitShown(session, MIRROR, render -> !render.waiting() && render.lines().equals(List.of("12.5 °C")) && "Outdoor".equals(render.title()),
                    "the retained value with its unit under the panel's name");
            probe.publish(leaves + "state", "14.5", false);
            awaitShown(session, MIRROR, render -> render.lines().equals(List.of("14.5 °C")), "a live change reaches the client");
            session.check(session.onClient(mc -> panel(mc, MIRROR).deviceLink().twin() == null && !panel(mc, MIRROR).shown().toString().contains(entity)),
                    "the client block entity holds neither the binding nor the entity id");
            session.check(session.onServer(s -> signal(s, MIRROR)) == 15,
                    "14.5 over 0..15 gives 15");
            look(session, MIRROR.south(3), MIRROR);
            session.screenshot("panel_mirror_live");

            probe.publish(leaves + "state", "unavailable", false);
            session.check(session.awaitClient(mc -> panel(mc, MIRROR).twinHint() == TwinHealth.Hint.UNAVAILABLE, ANSWER_TICKS), "the client learns it is unavailable");
            DisplayPanelRenderState unavailable = extract(session, MIRROR, System.currentTimeMillis());
            session.check(unavailable.lines.equals(List.of("Unavailable")) && "Outdoor".equals(unavailable.title), "it shows Unavailable under its name");
            session.check(session.onServer(s -> signal(s, MIRROR)) == 0,
                    "its comparator reads 0 while the entity has no value");
            session.screenshot("panel_mirror_unavailable");
            session.consoleCommand("mineqtt devices bind outdoor none");
        } finally {
            MineQTTConfig.twinAllowedEntities = savedAllowed;
            MineqttRuntime.configChanged();
            session.consoleCommand("deop " + session.playerName());
        }
    }

    /** The row of panels from 3 and from 8 blocks, at noon. */
    private static void readability(GameTestSession session) {
        look(session, LARGE.south(3), LARGE);
        session.screenshot("panel_large");
        look(session, FOUR.south(3), FOUR);
        session.screenshot("panel_four_lines");
        look(session, LARGE.south(3).west(), LARGE.west());
        session.screenshot("panel_distance_3");
        look(session, LARGE.south(8).east(), LARGE.east());
        session.screenshot("panel_distance_8");
    }

    // ===== Helpers =====

    /** A panel set by the console at {@code pos}, facing south, owned by the player; waits until it is a device. */
    private static void devicePanel(GameTestSession session, BlockPos pos) {
        int[] id = uuidInts(session);
        setblock(session, pos, "mineqtt:display_panel[facing=south]{mineqtt:{owner:[I;" + id[0] + "," + id[1] + "," + id[2] + "," + id[3]
                + "],ownerName:\"" + session.playerName() + "\"}}");
        session.awaitServer(s -> s.overworld().getBlockEntity(pos) instanceof DeviceHost host && host.deviceLink().attached());
        session.context().waitFor(mc -> mc.level.getBlockEntity(pos) instanceof DisplayPanelBlockEntity);
    }

    private static int[] uuidInts(GameTestSession session) {
        long most = session.playerId().getMostSignificantBits();
        long least = session.playerId().getLeastSignificantBits();
        return new int[]{(int) (most >> 32), (int) most, (int) (least >> 32), (int) least};
    }

    private static void set(GameTestSession session, BlockPos pos, String command) {
        session.consoleCommand("mineqtt devices set " + slug(session, pos) + " " + command);
    }

    /** Waits until the client's render data of the panel at {@code pos} matches. */
    private static void awaitShown(GameTestSession session, BlockPos pos, Predicate<DisplayPanel.Render> matches, String what) {
        session.check(session.awaitClient(mc -> mc.level.getBlockEntity(pos) instanceof DisplayPanelBlockEntity panel && matches.test(panel.shown()), ANSWER_TICKS),
                what + " (" + session.onClient(mc -> mc.level.getBlockEntity(pos) instanceof DisplayPanelBlockEntity panel ? panel.shown().toString() : "no panel") + ")");
    }

    /** What the renderer would draw of the panel at {@code pos} with the client's clock at {@code now}. */
    private static DisplayPanelRenderState extract(GameTestSession session, BlockPos pos, long now) {
        return session.onClient(mc -> {
            DisplayPanelBlockEntity panel = panel(mc, pos);
            DisplayPanelRenderState state = new DisplayPanelRenderState();
            //? if >=1.21.9 {
            BlockEntityRenderState.extractBase(panel, state, null);
            //?} else {
            /*state.lightCoords = net.minecraft.client.renderer.LevelRenderer.getLightColor(mc.level, pos);
            *///?}
            DisplayPanelRenderer.extractText(panel, state, now);
            return state;
        });
    }

    private static DisplayPanelBlockEntity panel(Minecraft mc, BlockPos pos) {
        if (mc.level.getBlockEntity(pos) instanceof DisplayPanelBlockEntity panel) return panel;
        throw new AssertionError("No Display Panel on the client at " + pos.toShortString());
    }

    // Stands with the eyes at `eyes` and looks a little below the middle of `at`, so the crosshair stays off the text; the
    // chat (the console's command answers) is cleared so it covers nothing.
    private static void look(GameTestSession session, BlockPos eyes, BlockPos at) {
        session.teleport(eyes.below());
        session.context().waitTicks(TELEPORT_TICKS);
        session.context().runOnClient(mc -> {
            mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(at).subtract(0, 0.75, 0));
            //? if >=26.2 {
            mc.gui.hud.getChat().clearMessages(false);
            //?} else {
            /*mc.gui.getChat().clearMessages(false);
            *///?}
        });
        session.context().waitTicks(SETTLE_TICKS);
    }

    private static String slug(GameTestSession session, BlockPos pos) {
        return session.onRuntime(runtime -> recordAt(runtime, pos).slug());
    }

    private static DeviceRecord recordAt(MineqttRuntime runtime, BlockPos pos) {
        return runtime.registry().records().stream().filter(record -> record.pos().pos().equals(pos)).findFirst()
                .orElseThrow(() -> new AssertionError("No device at " + pos.toShortString()));
    }

    private static void setblock(GameTestSession session, BlockPos pos, String block) {
        session.consoleCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block);
    }

    // The comparator output of the panel at pos, read from the east as the comparator behind it reads it (the side counts
    // from 1.21.9 on).
    private static int signal(net.minecraft.server.MinecraftServer server, BlockPos pos) {
        //? if >=1.21.9 {
        return server.overworld().getBlockState(pos).getAnalogOutputSignal(server.overworld(), pos, Direction.EAST);
        //?} else {
        /*return server.overworld().getBlockState(pos).getAnalogOutputSignal(server.overworld(), pos);
        *///?}
    }

}

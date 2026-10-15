package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.compat.Screens;
import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.ValueCodecs.LightState;
import art.rehra.mineqtt.device.types.RedstoneOutput.Output;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.integrations.PermissionManager;
import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceConfigPayload;
import art.rehra.mineqtt.security.C2SGuard;
import art.rehra.mineqtt.ui.device.AccessTab;
import art.rehra.mineqtt.ui.device.AdvancedTab;
import art.rehra.mineqtt.ui.device.ControlsTab;
import art.rehra.mineqtt.ui.device.DiagnosticsTab;
import art.rehra.mineqtt.ui.device.GeneralTab;
import art.rehra.mineqtt.ui.device.LightControl;
import art.rehra.mineqtt.ui.device.TwinTab;
import art.rehra.mineqtt.ui.device.LightControl.HueSaturation;
import art.rehra.mineqtt.ui.device.widgets.GradientSlider;
import art.rehra.mineqtt.ui.device.widgets.IntSlider;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/**
 * The generated device screen's Controls tab from a real client (M2 plan §4.2 M2-9). The non-op owner switches a
 * Redstone Output on, drags its level slider (the server's level follows while the slider moves), flings it to and fro
 * many times within one tick as a fast mouse does (the tab sends within the C2S guard's rate, so the last level still
 * arrives and a click right after still counts) and steps its mode. On a Smart Lamp the
 * light picker's saturation, hue, white and brightness reach the lamp, and its Home Assistant state follows. A Presence
 * Sensor's range slider changes the setting {@code /mineqtt devices info} shows, and its mob filter takes typed ids
 * (M2-12), refusing an unknown mob. A player whose claim lets them use but
 * not configure another owner's output may switch it and set its level, while its settings are read-only and a setting
 * sent anyway is dropped.
 */
public final class ControlsGameTest implements FabricClientGameTest {
    private static final BlockPos STAND = new BlockPos(0, GameTestSession.GROUND_Y + 1, 0);
    private static final BlockPos OUTPUT_GROUND = new BlockPos(2, GameTestSession.GROUND_Y, 0);
    private static final BlockPos LAMP_GROUND = new BlockPos(-2, GameTestSession.GROUND_Y, 0);
    private static final BlockPos SENSOR_GROUND = new BlockPos(0, GameTestSession.GROUND_Y, 2);
    private static final BlockPos OTHERS = new BlockPos(0, GameTestSession.GROUND_Y + 1, -2);
    private static final UUID OTHER_OWNER = UUID.fromString("7e3f4a5b-6c7d-4e8f-9a0b-c1d2e3f4a5b6");
    private static final int ANSWER_TICKS = 40;
    // Ample for a dropped payload to have done something if it had not been dropped.
    private static final int DROP_TICKS = 20;
    // Reads of a retained state, each up to a second, before a broker check gives up.
    private static final int BROKER_ATTEMPTS = 10;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "controls", ControlsGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        output(session);
        lamp(session);
        presence(session);
        readOnly(session);
    }

    /** The output's switch, its level slider dragged with the real mouse, and its mode. */
    private static void output(GameTestSession session) {
        BlockPos output = place(session, MineqttBlocks.REDSTONE_EMITTER_BLOCK.get(), OUTPUT_GROUND);
        session.openBlock(output);
        session.check(session.tabIds().equals(List.of(GeneralTab.ID, ControlsTab.ID, TwinTab.ID, AdvancedTab.ID, AccessTab.ID, DiagnosticsTab.ID)),
                "the output's screen has General, Controls, Twin (M3-13), Advanced, Access and Diagnostics, no slot tabs (M2-12): " + session.tabIds());
        session.check(session.clickTab(ControlsTab.ID), "the Controls tab opens");
        session.check(label(session, isButton("Switch:")).equals("Switch: OFF") && label(session, isSlider("Level:")).equals("Level: 0")
                        && label(session, isButton("Mode:")).equals("Mode: Any level") && label(session, isButton("After a restart:")).equals("After a restart: As before"),
                "the output shows its switch, level, mode and power-on behaviour: " + labels(session));
        session.screenshot("controls_output");

        session.clickWidget(isButton("Switch:"), "the switch");
        session.check(awaitPower(session, output, 15) && session.awaitClient(mc -> label(mc, isButton("Switch:")).equals("Switch: ON"), ANSWER_TICKS),
                "the switch turns the output on at its last level, 15, and shows ON");

        // Down at 0, across to 15 a tick a step, back to 7 and up: the server's level follows the slider while it moves.
        List<Double> fractions = IntStream.of(0, 2, 4, 6, 8, 10, 12, 14, 15, 15, 13, 11, 9, 7).mapToObj(level -> level / 15.0).toList();
        Set<Integer> seen = new LinkedHashSet<>();
        session.dragSlider(isSlider("Level:"), "the level slider", fractions, () -> seen.add(power(session, output)));
        session.check(awaitPower(session, output, 7), "after the drag the output emits the level the slider stopped at, 7");
        session.check(seen.size() >= 4, "the level followed the slider while it was dragged, not only at the end: " + seen);
        session.check(session.awaitClient(mc -> label(mc, isSlider("Level:")).equals("Level: 7"), ANSWER_TICKS), "the slider stays at 7 once the views arrive");

        // 15 and 0 in turn, then 3, a move a frame within one tick: twice the payloads the guard lets a player send in a
        // second. Sent as picked, the guard would drop the last ones, 3 among them.
        List<Double> fling = IntStream.concat(IntStream.range(0, 2 * C2SGuard.PACKETS_PER_SECOND).map(i -> i % 2 == 0 ? 15 : 0), IntStream.of(3))
                .mapToObj(level -> level / 15.0).toList();
        session.flingSlider(isSlider("Level:"), "the level slider", fling);
        session.check(awaitPower(session, output, 3), "a burst of slider moves ends at the level the slider stopped at, 3: the tab kept within the guard's rate");
        session.check(session.awaitClient(mc -> label(mc, isSlider("Level:")).equals("Level: 3"), ANSWER_TICKS), "the slider stays at 3 once the views arrive");
        session.clickWidget(isButton("Switch:"), "the switch");
        session.check(awaitPower(session, output, 0), "a click right after the burst still switches the output off");
        session.clickWidget(isButton("Switch:"), "the switch");
        session.check(awaitPower(session, output, 3), "switched on again it comes back at 3, the last level");

        session.clickWidget(isButton("Mode:"), "the mode button");
        session.check(session.await(() -> mode(session, output).equals("switch"), ANSWER_TICKS)
                        && session.awaitClient(mc -> label(mc, isButton("Mode:")).equals("Mode: On or off"), ANSWER_TICKS),
                "the mode button steps the setting to switch mode");
        session.check(awaitPower(session, output, 15), "in switch mode the output emits full power");
        session.clickWidget(isButton("Mode:"), "the mode button");
        session.check(session.awaitClient(mc -> label(mc, isButton("Mode:")).equals("Mode: Any level"), ANSWER_TICKS), "and back to any level");
        session.closeScreen();
    }

    /** The light picker: saturation, then hue for a colour, then white, then brightness; Home Assistant's state follows. */
    private static void lamp(GameTestSession session) {
        BlockPos lamp = place(session, MineqttBlocks.RGB_LED_BLOCK.get(), LAMP_GROUND);
        session.openBlock(lamp);
        session.check(session.tabIds().equals(List.of(GeneralTab.ID, ControlsTab.ID, TwinTab.ID, AdvancedTab.ID, AccessTab.ID, DiagnosticsTab.ID)),
                "the lamp's screen has General, Controls, Twin, Advanced, Access and Diagnostics: " + session.tabIds());
        session.check(session.clickTab(ControlsTab.ID), "the Controls tab opens");
        session.screenshot("controls_lamp_off");

        session.dragSlider(isPicker("Saturation"), "the saturation slider", List.of(1.0), () -> {
        });
        session.check(awaitLight(session, lamp, light -> light.on() && light.kelvin() == null && HueSaturation.of(light.color()).saturation() == 100),
                "full saturation turns the lamp on in a saturated colour: " + light(session, lamp));
        session.dragSlider(isPicker("Hue"), "the hue slider", List.of(0.1, 0.4, 240.0 / LightControl.MAX_HUE), () -> {
        });
        session.check(awaitLight(session, lamp, light -> light.kelvin() == null && Math.abs(HueSaturation.of(light.color()).hue() - 240) <= 3),
                "dragging the hue to 240 makes the lamp blue: " + light(session, lamp));
        brokerCheck(session, lamp, state -> state.get("color_mode").getAsString().equals("rgb")
                && Math.abs(HueSaturation.of(LightControl.light(state).color()).hue() - 240) <= 3, "Home Assistant's state is the blue in rgb mode");
        session.screenshot("controls_lamp_blue");

        session.dragSlider(isPicker("White"), "the white slider", List.of(1.0), () -> {
        });
        session.check(awaitLight(session, lamp, light -> light.kelvin() != null && light.kelvin() == 6500), "the white slider's end sets 6500 K: " + light(session, lamp));
        brokerCheck(session, lamp, state -> state.get("color_mode").getAsString().equals("color_temp") && state.get("color_temp_kelvin").getAsInt() == 6500,
                "Home Assistant's state is 6500 K in color_temp mode");
        session.dragSlider(isPicker("Brightness"), "the brightness slider", List.of(0.5), () -> {
        });
        session.check(awaitLight(session, lamp, light -> Math.abs(light.brightness() - 128) <= 2 && light.kelvin() != null && light.kelvin() == 6500),
                "half brightness keeps the white: " + light(session, lamp));
        brokerCheck(session, lamp, state -> Math.abs(state.get("brightness").getAsInt() - 128) <= 2, "Home Assistant's brightness follows");
        session.screenshot("controls_lamp_white");

        session.clickWidget(isButton("Switch:"), "the lamp's switch");
        session.check(awaitLight(session, lamp, light -> !light.on()), "the switch turns the lamp off");
        session.closeScreen();
    }

    /** The range slider changes the sensor's setting, which /mineqtt devices info shows as its last state. */
    private static void presence(GameTestSession session) {
        BlockPos sensor = place(session, MineqttBlocks.MOTION_SENSOR_BLOCK.get(), SENSOR_GROUND);
        session.openBlock(sensor);
        session.check(session.clickTab(ControlsTab.ID), "the Controls tab opens");
        session.check(label(session, isButton("Detects:")).startsWith("Detects: ") && label(session, isSlider("Range:")).endsWith(" blocks")
                        && label(session, isSlider("Clear delay:")).endsWith(" s"), "the sensor shows what it detects, its range and its clear delay: " + labels(session));
        session.screenshot("controls_presence");
        // 1 to 8 blocks, from the default 5: 2/7 of the way is 3.
        session.dragSlider(isSlider("Range:"), "the range slider", List.of(2 / 7.0), () -> {
        });
        UUID id = session.device(sensor, link -> link.id());
        session.check(session.await(() -> {
            JsonObject state = session.onRuntime(runtime -> runtime.registry().record(id).lastState());
            return state != null && state.has("range") && state.get("range").getAsInt() == 3;
        }, ANSWER_TICKS), "the range slider sets 3 blocks, the last state devices info shows");
        session.check(session.awaitClient(mc -> label(mc, isSlider("Range:")).equals("Range: 3 blocks"), ANSWER_TICKS), "the slider shows 3 blocks");

        // The mob filter (M2-12), the spawn-egg slots' successor: typed ids, kept as the server writes them.
        session.check(session.fieldText(isField("Mobs")).isEmpty(), "a new sensor's filter is empty: every mob counts");
        session.typeInto(isField("Mobs"), "the mob filter", "zombie pig");
        session.context().waitTicks(DROP_TICKS);
        session.check(state(session, sensor).get("filter").getAsString().isEmpty(), "typing sends nothing yet");
        session.context().getInput().pressKey(InputConstants.KEY_RETURN);
        session.check(session.await(() -> "zombie, pig".equals(state(session, sensor).get("filter").getAsString()), ANSWER_TICKS),
                "Enter sets the filter, which the server keeps as ids");
        session.check(session.awaitClient(mc -> fieldText(mc, isField("Mobs")).equals("zombie, pig"), ANSWER_TICKS),
                "the field shows the filter as the server keeps it");
        session.screenshot("controls_presence_filter");
        int mark = ChatLog.mark();
        session.typeInto(isField("Mobs"), "the mob filter", "ghost");
        session.clickWidget(isButton("Set"), "Set");
        session.check(session.awaitChat(mark, line -> !line.actionBar() && line.text().startsWith("Not changed:") && line.text().contains("ghost"), ANSWER_TICKS).isPresent(),
                "an unknown mob is refused in chat (messages: " + ChatLog.since(mark) + ")");
        session.check(session.awaitClient(mc -> fieldText(mc, isField("Mobs")).equals("zombie, pig"), ANSWER_TICKS)
                && "zombie, pig".equals(state(session, sensor).get("filter").getAsString()), "and the field snaps back to the filter the sensor kept");
        session.closeScreen();
    }

    /**
     * A player whose claim trusts them to use another owner's output but not to configure it: the switch and the level
     * slider work, the mode and power-on buttons are read-only, and a mode change sent anyway is dropped by the guard.
     */
    private static void readOnly(GameTestSession session) {
        PermissionManager installed = MineQTT.permissionManager;
        MineQTT.permissionManager = new PermissionManager() {
            @Override
            public boolean canInteract(ServerPlayer player, BlockPos pos, MineqttPermission permission) {
                return permission == MineqttPermission.USE;
            }
        };
        try {
            session.consoleCommand("setblock " + OTHERS.getX() + " " + OTHERS.getY() + " " + OTHERS.getZ()
                    + " mineqtt:redstone_emitter_block{mineqtt:{v:2,id:" + intArray(UUID.randomUUID())
                    + ",type:\"mineqtt:redstone_output\",name:\"Gate\",owner:" + intArray(OTHER_OWNER) + ",ownerName:\"Someone\",access:\"claim\"}}");
            session.awaitServer(s -> s.overworld().getBlockEntity(OTHERS) instanceof DeviceHost host && host.deviceLink().attached());
            session.openBlock(OTHERS);
            session.check(session.clickTab(ControlsTab.ID), "the Controls tab opens");
            DeviceView view = session.screenView().orElseThrow();
            session.check(view.grants().equals(new DeviceView.Grants(true, false, false, false)), "the player may use the output but not configure it (" + view.grants() + ")");
            session.check(active(session, isButton("Switch:")) && active(session, isSlider("Level:"))
                    && !active(session, isButton("Mode:")) && !active(session, isButton("After a restart:")),
                    "the switch and the level slider are active, mode and power-on behaviour read-only");
            session.screenshot("controls_read_only");

            session.dragSlider(isSlider("Level:"), "the level slider", List.of(0.2, 4 / 15.0), () -> {
            });
            session.check(awaitPower(session, OTHERS, 4), "a player who may only use the output sets its level");
            session.clickWidget(isButton("Mode:"), "the read-only mode button");
            session.send(new DeviceConfigPayload(OTHERS, "mode", StringTag.valueOf("switch")));
            session.context().waitTicks(DROP_TICKS);
            session.check(mode(session, OTHERS).equals("level"), "the mode stays: the inactive button sends nothing and a payload sent anyway is dropped");
            session.closeScreen();
        } finally {
            MineQTT.permissionManager = installed;
        }
    }

    private static BlockPos place(GameTestSession session, Block block, BlockPos ground) {
        BlockPos pos = session.place(block, ground);
        session.awaitServer(s -> s.overworld().getBlockEntity(pos) instanceof DeviceHost host && host.deviceLink().attached());
        return pos;
    }

    // The state of the device at pos on the server, as it publishes it; empty when there is none.
    private static JsonObject state(GameTestSession session, BlockPos pos) {
        return session.onServer(s -> {
            StateWriter state = new StateWriter();
            if (s.overworld().getBlockEntity(pos) instanceof DeviceHost host) host.writeState(state);
            return state.json();
        });
    }

    private static int power(GameTestSession session, BlockPos output) {
        return Output.of(state(session, output)).power();
    }

    private static String mode(GameTestSession session, BlockPos output) {
        return Output.of(state(session, output)).mode().getSerializedName();
    }

    private static boolean awaitPower(GameTestSession session, BlockPos output, int power) {
        return session.await(() -> power(session, output) == power, ANSWER_TICKS);
    }

    private static LightState light(GameTestSession session, BlockPos lamp) {
        return LightControl.light(state(session, lamp));
    }

    private static boolean awaitLight(GameTestSession session, BlockPos lamp, Predicate<LightState> matches) {
        return session.await(() -> matches.test(light(session, lamp)), ANSWER_TICKS);
    }

    // Reads the lamp's retained state topic until it matches; checks the last read.
    private static void brokerCheck(GameTestSession session, BlockPos lamp, Predicate<JsonObject> matches, String what) {
        Optional<BrokerConfig> broker = session.broker();
        if (broker.isEmpty()) {
            session.skip(what, "no broker (" + GameTestSession.BROKER_ENV + " is not set)");
            return;
        }
        DeviceView view = session.screenView().orElseThrow();
        String topic = view.registration().topic(view.slug()).orElseThrow();
        Predicate<Optional<String>> state = payload -> payload.map(json -> matches.test(JsonParser.parseString(json).getAsJsonObject())).orElse(false);
        try (BrokerProbe probe = BrokerProbe.connect(broker.get())) {
            Optional<String> payload = probe.retained(topic);
            for (int attempt = 1; attempt < BROKER_ATTEMPTS && !state.test(payload); attempt++) {
                payload = probe.retained(topic);
            }
            session.check(state.test(payload), what + " (" + topic + ": " + payload.orElse("nothing retained") + ")");
        }
    }

    private static Predicate<AbstractWidget> isButton(String prefix) {
        return widget -> widget instanceof Button && widget.getMessage().getString().startsWith(prefix);
    }

    private static Predicate<AbstractWidget> isSlider(String prefix) {
        return widget -> widget instanceof IntSlider && !(widget instanceof GradientSlider) && widget.getMessage().getString().startsWith(prefix);
    }

    private static Predicate<AbstractWidget> isPicker(String label) {
        return widget -> widget instanceof GradientSlider && widget.getMessage().getString().equals(label);
    }

    private static Predicate<AbstractWidget> isField(String name) {
        return widget -> widget instanceof EditBox && widget.getMessage().getString().equals(name);
    }

    private static String fieldText(Minecraft mc, Predicate<AbstractWidget> which) {
        return widget(mc, which).map(field -> ((EditBox) field).getValue()).orElseThrow();
    }

    private static String label(GameTestSession session, Predicate<AbstractWidget> which) {
        return session.onClient(mc -> label(mc, which));
    }

    private static String label(Minecraft mc, Predicate<AbstractWidget> which) {
        return widget(mc, which).map(widget -> widget.getMessage().getString()).orElse("");
    }

    private static boolean active(GameTestSession session, Predicate<AbstractWidget> which) {
        return session.onClient(mc -> widget(mc, which).orElseThrow().active);
    }

    private static List<String> labels(GameTestSession session) {
        return session.onClient(mc -> screen(mc).children().stream().filter(AbstractWidget.class::isInstance)
                .map(widget -> ((AbstractWidget) widget).getMessage().getString()).toList());
    }

    private static Optional<AbstractWidget> widget(Minecraft mc, Predicate<AbstractWidget> which) {
        return screen(mc).children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).filter(which).findFirst();
    }

    private static TabbedMqttScreen screen(Minecraft mc) {
        if (Screens.current() instanceof TabbedMqttScreen screen) return screen;
        throw new AssertionError("No MineQTT screen is open: " + Screens.current());
    }

    /** A UUID as an SNBT int array, the way block entity data stores it. */
    private static String intArray(UUID id) {
        return Arrays.toString(UUIDUtil.uuidToIntArray(id)).replace("[", "[I;").replace(" ", "");
    }
}

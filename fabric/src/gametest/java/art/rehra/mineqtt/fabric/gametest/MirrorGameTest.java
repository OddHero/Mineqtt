package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.blocks.entities.DeviceBlockEntity;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.items.ConfiguratorMode;
import art.rehra.mineqtt.items.CyberdeckDataUtil;
import art.rehra.mineqtt.items.MineqttItems;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.ui.TwinPickerScreen;
import art.rehra.mineqtt.ui.device.TwinPicker;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.TwinView;
import art.rehra.mineqtt.device.ValueCodecs.LightState;
import art.rehra.mineqtt.device.ValueCodecs.Rgb;
import art.rehra.mineqtt.runtime.TopicScheme;
import art.rehra.mineqtt.twin.core.TwinHealth;
import art.rehra.mineqtt.ui.device.AdvancedTab;
import art.rehra.mineqtt.ui.device.DeviceMenu;
import art.rehra.mineqtt.ui.device.LightControl;
import art.rehra.mineqtt.ui.device.TwinTab;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Mirror mode on a real client (M3 plan M3-13, the {@code mirror} suite, D6, D11, D19), against Home Assistant as a
 * broker probe plays it: statestream leaves in, and the control blueprint answering requests. A Smart Lamp, a Redstone
 * Output and a Smart Plug the player placed are bound with the command twin and follow their entities; the lamp's
 * control is turned on on its Twin tab, and the Output's range is typed into its Twin tab. A right-click on the mirrored
 * lamp sends exactly one request, naming the player, and changes nothing until Home Assistant reports the change; the
 * client sees the request pending meanwhile. A {@code not_labelled} answer reaches the player's action bar and changes
 * nothing. A mirrored plug joins no group, and its Advanced tab says its Raw Link is paused; unbinding republishes its
 * full discovery.
 * <p>
 * The preset step (M3-17): with the Home Assistant twin off, the probe plays a zigbee2mqtt colour bulb; a fourth
 * lamp is bound to it on its Twin tab (the source cycle's MQTT device, the preset cycle, the device's name) and follows
 * it. Control is refused while {@code acl.rawAllow} lacks the bulb's {@code /set}, and taken once it has it; a right-click
 * then publishes the preset's command (TOGGLE while the state is only retained, then the explicit opposite) and changes
 * nothing until the bulb answers; with the allowlist entry gone a click publishes nothing and the action bar says why.
 * Bind mode's picker warns that it would replace the MQTT device, and the source cycle unbinds it. Screenshots
 * {@code mirror_*}. Needs a broker; without one everything is skipped.
 */
public final class MirrorGameTest implements FabricClientGameTest {
    private static final BlockPos STAND = new BlockPos(20, GameTestSession.GROUND_Y + 1, 0);
    private static final BlockPos LAMP_GROUND = new BlockPos(18, GameTestSession.GROUND_Y, 2);
    private static final BlockPos OUTPUT_GROUND = new BlockPos(20, GameTestSession.GROUND_Y, 2);
    private static final BlockPos PLUG_GROUND = new BlockPos(22, GameTestSession.GROUND_Y, 2);
    private static final BlockPos BULB_GROUND = new BlockPos(18, GameTestSession.GROUND_Y, -2);
    private static final String PRESET = "mineqtt:zigbee2mqtt_light";
    private static final String PRESET_NAME = "zigbee2mqtt light";
    private static final String LAMP = "light.mirror_lamp";
    private static final String LEVEL = "input_number.mirror_level";
    private static final String PLUG = "switch.mirror_plug";
    private static final int ANSWER_TICKS = TwinFixture.ANSWER_TICKS;
    // Long enough for a local change to have shown, had the click applied one.
    private static final int HOLD_TICKS = 30;
    private static final int SETTLE_TICKS = 20;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "mirror", MirrorGameTest::run);
    }

    private static void run(GameTestSession session) {
        if (session.broker().isEmpty()) {
            session.skip("the mirror suite", GameTestSession.BROKER_ENV + " is not set");
            return;
        }
        session.teleport(STAND);
        try (TwinFixture twin = new TwinFixture(session, List.of("light.mirror_*", "input_number.mirror_*", "switch.mirror_*"));
             BrokerProbe.Listener requests = twin.requests()) {
            BlockPos lamp = place(session, MineqttBlocks.RGB_LED_BLOCK.get(), LAMP_GROUND);
            BlockPos output = place(session, MineqttBlocks.REDSTONE_EMITTER_BLOCK.get(), OUTPUT_GROUND);
            BlockPos plug = place(session, MineqttBlocks.SMART_PLUG_BLOCK.get(), PLUG_GROUND);
            session.check(twin.awaitScan(), "without a catalog the twin scans what statestream publishes");
            lamp(session, twin, lamp);
            control(session, twin, requests, lamp);
            output(session, twin, output);
            plug(session, twin, plug);
            preset(session, twin);
        }
    }

    /** The lamp follows a light: on, brightness and colour in one step, off keeping its colour. */
    private static void lamp(GameTestSession session, TwinFixture twin, BlockPos lamp) {
        twin.entity(LAMP, "on", Map.of("brightness", "128", "rgb_color", "[255, 0, 0]", "friendly_name", "\"Mirror Lamp\""));
        // The scan's subscription carries the publishes live; once they passed, the binding's own subscriptions get the
        // broker's retained copies, as after a restart.
        session.context().waitTicks(SETTLE_TICKS);
        session.consoleCommand("mineqtt devices bind " + twin.slug(lamp) + " " + LAMP);
        session.check(twin.source(lamp).equals(Optional.of(LAMP)), "the command twin binds the lamp to " + LAMP);
        session.check(session.await(() -> light(twin, lamp).on() && light(twin, lamp).brightness() == 128 && light(twin, lamp).color().equals(new Rgb(255, 0, 0)),
                ANSWER_TICKS), "the lamp follows the light: on, 128, red (" + twin.state(lamp) + ")");
        session.check(twin.awaitHealth(lamp, TwinHealth.RETAINED), "a retained value shows as retained: " + twin.health(lamp));
        twin.live(LAMP, "off");
        session.check(session.await(() -> !light(twin, lamp).on() && light(twin, lamp).color().equals(new Rgb(255, 0, 0)), ANSWER_TICKS),
                "a live off turns it off and keeps its colour (" + twin.state(lamp) + ")");
        session.check(twin.awaitHealth(lamp, TwinHealth.LIVE), "a live value shows as live: " + twin.health(lamp));
        twin.live(LAMP, "on");
        session.check(session.await(() -> light(twin, lamp).on(), ANSWER_TICKS), "and on again");
    }

    /**
     * Control on through the lamp's Twin tab; a right-click asks Home Assistant once and changes nothing until Home
     * Assistant reports it; a refusal reaches the action bar.
     */
    private static void control(GameTestSession session, TwinFixture twin, BrokerProbe.Listener requests, BlockPos lamp) {
        session.hold(Items.AIR);
        session.openBlock(lamp);
        session.check(session.clickTab(TwinTab.ID), "the lamp's Twin tab opens");
        session.check(session.awaitClient(mc -> menu(mc).twinView().map(view -> view.bound() && view.details() && view.mayControl()).orElse(false), ANSWER_TICKS),
                "it shows the binding to the player, who may configure and control it");
        session.check(session.awaitClient(mc -> label(mc, IS_SOURCE).equals("Home Assistant")
                        && label(mc, isButton("Mirror Lamp")).equals("Mirror Lamp"), ANSWER_TICKS),
                "the source row shows Home Assistant and the entity's name: " + labels(session));
        session.clickWidget(isButton("Control:"), "the control switch");
        session.check(session.await(() -> Boolean.TRUE.equals(session.device(lamp, link -> link.twin().control())), ANSWER_TICKS),
                "the control switch turns control on");
        session.check(session.awaitClient(mc -> label(mc, isButton("Control:")).equals("Control: ON"), ANSWER_TICKS), "and shows it on");
        session.screenshot("mirror_twin_tab_lamp");
        session.closeScreen();

        // A right-click: one request with the player's name; nothing changes locally.
        session.aim(lamp);
        session.pressUse();
        Optional<JsonObject> request = twin.awaitRequest(requests, ANSWER_TICKS);
        session.check(request.map(json -> json.get("action").getAsString()).equals(Optional.of("light.turn_off"))
                        && request.map(json -> json.getAsJsonObject("target").get("entity_id").getAsString()).equals(Optional.of(LAMP)),
                "a right-click on the mirrored lamp asks Home Assistant to turn the light off: " + request);
        session.check(request.map(json -> json.getAsJsonObject("origin").has("player")
                        && json.getAsJsonObject("origin").get("player").getAsString().equals(session.playerName())).orElse(false),
                "the request names the player: " + request);
        session.check(session.awaitClient(mc -> mc.level.getBlockEntity(lamp) instanceof DeviceBlockEntity device && device.twinPending(), ANSWER_TICKS),
                "the client sees the request pending (the sparkle)");
        session.context().waitTicks(HOLD_TICKS);
        session.check(light(twin, lamp).on() && twin.awaitRequest(requests, 0).isEmpty(), "nothing changes locally and no second request goes out");
        session.screenshot("mirror_lamp_pending");
        twin.answer(request.orElseThrow(), "accepted");
        twin.live(LAMP, "off");
        session.check(session.await(() -> !light(twin, lamp).on(), ANSWER_TICKS), "the lamp turns off when Home Assistant reports it");
        session.check(session.awaitClient(mc -> mc.level.getBlockEntity(lamp) instanceof DeviceBlockEntity device && !device.twinPending(), ANSWER_TICKS),
                "and the pending sparkle ends");

        // A refusal: the action bar names it, and the lamp stays as it is.
        int mark = ChatLog.mark();
        session.pressUse();
        Optional<JsonObject> second = twin.awaitRequest(requests, ANSWER_TICKS);
        session.check(second.map(json -> json.get("action").getAsString()).equals(Optional.of("light.turn_on")), "the next click asks to turn it on: " + second);
        twin.answer(second.orElseThrow(), "not_labelled");
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().contains("not labelled for MineQTT control"), ANSWER_TICKS).isPresent(),
                "a not_labelled answer reaches the action bar (messages: " + ChatLog.since(mark) + ")");
        session.context().waitTicks(HOLD_TICKS);
        session.check(!light(twin, lamp).on(), "and the lamp stays off");
    }

    /** The Output follows a number; its range typed into the Twin tab maps it anew. */
    private static void output(GameTestSession session, TwinFixture twin, BlockPos output) {
        twin.entity(LEVEL, "42.5", Map.of("min", "0.0", "max", "100.0", "step", "0.5", "friendly_name", "\"Mirror Level\""));
        session.consoleCommand("mineqtt devices bind " + twin.slug(output) + " " + LEVEL);
        session.check(session.await(() -> power(twin, output) == 6, ANSWER_TICKS), "42.5 of 0..100 gives the output 6 (" + twin.state(output) + ")");
        session.openBlock(output);
        session.check(session.clickTab(TwinTab.ID), "the output's Twin tab opens");
        session.check(session.awaitClient(mc -> menu(mc).twinView().map(TwinView::bound).orElse(false), ANSWER_TICKS), "it shows the binding");
        session.typeInto(isField("Min"), "the min field", "40");
        session.context().getInput().pressKey(InputConstants.KEY_RETURN);
        session.typeInto(isField("Max"), "the max field", "50");
        session.context().getInput().pressKey(InputConstants.KEY_RETURN);
        session.check(session.await(() -> power(twin, output) == 4, ANSWER_TICKS), "with 40..50 typed into the tab, 42.5 gives 4 (" + twin.state(output) + ")");
        session.check(session.device(output, link -> link.twin().settings().min().toPlainString() + ".." + link.twin().settings().max().toPlainString())
                .equals("40..50"), "the binding keeps the range");
        session.screenshot("mirror_twin_tab_output");
        session.closeScreen();
        twin.live(LEVEL, "47.5");
        session.check(session.await(() -> power(twin, output) == 11, ANSWER_TICKS), "a live 47.5 gives 11 (" + twin.state(output) + ")");
    }

    /**
     * The plug follows a switch; a mirrored plug joins no group and its Raw Link pauses; unbinding brings its full
     * discovery back.
     */
    private static void plug(GameTestSession session, TwinFixture twin, BlockPos plug) {
        String slug = twin.slug(plug);
        session.consoleCommand("mineqtt devices expose " + slug + " on");
        session.consoleCommand("mineqtt devices setting " + slug + " raw.channel mirror_plug_" + GameTestSession.BRIDGE_ID.replace('-', '_'));
        String discovery = TopicScheme.discoveryConfig(MineQTTConfig.discoveryPrefix, session.onRuntime(runtime -> TwinFixture.recordAt(runtime, plug).objectId()));
        session.check(awaitDiscovery(session, twin, discovery, config -> config.has("cmps") && !config.getAsJsonObject("cmps").has("binding")),
                "an exposed plug is discovered in full");
        twin.entity(PLUG, "on", Map.of("friendly_name", "\"Mirror Plug\""));
        session.consoleCommand("mineqtt devices bind " + slug + " " + PLUG);
        session.check(session.await(() -> power(twin, plug) == 15, ANSWER_TICKS), "the plug follows the switch on (" + twin.state(plug) + ")");
        session.check(awaitDiscovery(session, twin, discovery, config -> config.getAsJsonObject("cmps").has("binding")),
                "bound, its discovery is reduced to the Binding entity");

        session.consoleCommand("mineqtt group create Mirror Plugs");
        session.consoleCommand("mineqtt group add mirror_plugs " + slug);
        session.check(session.onRuntime(runtime -> TwinFixture.recordAt(runtime, plug).group() == null), "a mirrored plug joins no group");

        session.openBlock(plug);
        session.check(session.clickTab(AdvancedTab.ID), "the plug's Advanced tab opens");
        session.check(session.awaitClient(mc -> GameTestSession.openScreen(mc).getMenu().deviceStatus() != null
                        && GameTestSession.openScreen(mc).getMenu().deviceStatus().contains("paused while this device mirrors " + PLUG), ANSWER_TICKS),
                "its status says the Raw Link is paused while it mirrors the switch: " + session.screenStatus());
        session.screenshot("mirror_plug_advanced");
        session.closeScreen();

        session.consoleCommand("mineqtt devices bind " + slug + " none");
        session.check(twin.source(plug).isEmpty(), "the command twin unbinds the plug");
        session.check(awaitDiscovery(session, twin, discovery, config -> config.has("cmps") && !config.getAsJsonObject("cmps").has("binding")
                        && config.getAsJsonObject("cmps").size() > 1), "unbinding republishes its full discovery");
        session.consoleCommand("mineqtt devices expose " + slug + " off");
    }

    /**
     * M3-17's preset step: a lamp bound to a zigbee2mqtt bulb on its Twin tab while the Home Assistant twin is off; its
     * control and right-clicks go to the bulb's {@code /set} only while {@code acl.rawAllow} allows it.
     */
    private static void preset(GameTestSession session, TwinFixture twin) {
        String device = "m3bulb_" + GameTestSession.BRIDGE_ID.replace('-', '_');
        String state = "zigbee2mqtt/" + device;
        String red = "{\"state\":\"ON\",\"brightness\":200,\"color\":{\"x\":0.62,\"y\":0.366}}";
        session.clearAtClose(state);
        twin.probe().publish(state, red, true);
        // A dedicated server's defaults: no entity allowed, so the Home Assistant twin is off; presets do not need it.
        twin.allow(List.of());
        BlockPos bulb = place(session, MineqttBlocks.RGB_LED_BLOCK.get(), BULB_GROUND);

        session.hold(Items.AIR);
        session.openBlock(bulb);
        session.check(session.clickTab(TwinTab.ID), "the bulb lamp's Twin tab opens");
        session.check(session.awaitClient(mc -> menu(mc).twinView().map(view -> !view.bound() && view.details() && !view.off().isEmpty()).orElse(false),
                ANSWER_TICKS), "it shows the unbound lamp to its owner, with the Home Assistant twin off");
        // The source cycle: This block opens the entity picker for Home Assistant; back on the tab the cycle keeps it.
        session.clickWidget(isButton("This block"), "the source cycle");
        session.context().waitForScreen(TwinPickerScreen.class);
        session.context().getInput().pressKey(InputConstants.KEY_ESCAPE);
        session.check(session.awaitClient(mc -> mc.gui.screen() instanceof TabbedMqttScreen && label(mc, IS_SOURCE).equals("Home Assistant"), ANSWER_TICKS),
                "choosing Home Assistant opened the picker, and the cycle still shows it after Escape: " + labels(session));
        session.clickWidget(isButton("Home Assistant"), "the source cycle");
        session.check(session.awaitClient(mc -> label(mc, IS_SOURCE).equals("MQTT device") && menu(mc).presetResults().isPresent(), ANSWER_TICKS),
                "the next step is MQTT device, which lists the presets: " + labels(session));
        List<String> presets = session.onClient(mc -> menu(mc).presetResults().map(results -> results.rows().stream().map(TwinPicker.Row::label).toList())
                .orElse(List.of()));
        session.check(presets.contains(PRESET_NAME) && presets.stream().noneMatch(name -> name.contains("sensor")),
                "the server lists the presets a lamp may follow, no sensor: " + presets);
        // The tab shows the answer with its next frame.
        session.check(session.awaitClient(mc -> label(mc, isButton("Pick")).isEmpty() && label(mc, isButton(presets.getFirst())).equals(presets.getFirst()),
                ANSWER_TICKS), "the preset cycle takes the pick's place, starting at the first preset: " + labels(session));
        for (int step = 0; step < presets.size() && !session.onClient(mc -> label(mc, isButton(PRESET_NAME)).equals(PRESET_NAME)); step++) {
            session.clickWidget(widget -> widget instanceof Button && presets.contains(widget.getMessage().getString()), "the preset cycle");
        }
        session.check(session.awaitClient(mc -> label(mc, isButton(PRESET_NAME)).equals(PRESET_NAME), ANSWER_TICKS), "the preset cycle steps to " + PRESET_NAME);
        session.typeInto(isField("Device name"), "the device name field", device);
        session.context().getInput().pressKey(InputConstants.KEY_RETURN);
        session.check(session.await(() -> twin.preset(bulb).map(ref -> ref.id().equals(PRESET) && ref.device().equals(device)).orElse(false), ANSWER_TICKS),
                "Enter binds the lamp to the MQTT device " + device + " through " + PRESET + ": " + twin.preset(bulb));
        session.check(session.await(() -> light(twin, bulb).on() && light(twin, bulb).brightness() == 200 && light(twin, bulb).color().r() == 255
                        && light(twin, bulb).color().b() == 0, ANSWER_TICKS),
                "the lamp follows the bulb: on, 200, red-orange (" + twin.state(bulb) + ")");
        session.check(session.awaitClient(mc -> menu(mc).twinView().map(view -> view.boundToDevice() && view.device().orElseThrow().device().equals(device)
                        && view.device().orElseThrow().topic().equals(state) && view.health().isPresent()).orElse(false)
                        && label(mc, IS_SOURCE).equals("MQTT device") && label(mc, isButton(PRESET_NAME)).equals(PRESET_NAME), ANSWER_TICKS),
                "the tab shows the MQTT device, its preset, topic and health while the Home Assistant twin is off: " + labels(session));
        session.screenshot("mirror_twin_tab_preset");

        // Control needs the bulb's command topic in acl.rawAllow (M3-17 decision 3).
        session.clickWidget(isButton("Control:"), "the control switch");
        session.check(session.awaitClient(mc -> menu(mc).refusal().map(refusal -> refusal.reason().contains("acl.rawAllow")).orElse(false), ANSWER_TICKS),
                "without zigbee2mqtt/+/set allowlisted control is refused, naming acl.rawAllow: " + session.onClient(mc -> menu(mc).refusal()));
        session.check(!Boolean.TRUE.equals(session.device(bulb, link -> link.twin().control())), "and stays off");
        List<String> rawAllow = session.onServer(s -> List.copyOf(MineQTTConfig.aclRawAllow));
        List<String> allowed = new java.util.ArrayList<>(rawAllow);
        allowed.add("zigbee2mqtt/+/set");
        reloadRawAllow(session, allowed);
        session.clickWidget(isButton("Control:"), "the control switch");
        session.check(session.await(() -> Boolean.TRUE.equals(session.device(bulb, link -> link.twin().control())), ANSWER_TICKS),
                "with zigbee2mqtt/+/set allowlisted the control switch turns control on");
        session.closeScreen();

        try (BrokerProbe.Listener commands = twin.probe().listen(state + "/set")) {
            session.aim(bulb);
            session.pressUse();
            Optional<String> toggle = awaitMessage(session, commands, ANSWER_TICKS);
            session.check(toggle.equals(Optional.of("{\"state\":\"TOGGLE\"}")), "a right-click on the retained bulb publishes the preset's toggle: " + toggle);
            session.context().waitTicks(HOLD_TICKS);
            session.check(light(twin, bulb).on() && commands.nextLive().isEmpty(), "nothing changes locally and nothing more is published");
            twin.probe().publish(state, red.replace("\"ON\"", "\"OFF\""), false);
            session.check(session.await(() -> !light(twin, bulb).on(), ANSWER_TICKS), "the lamp turns off when the bulb reports it");
            session.pressUse();
            Optional<String> on = awaitMessage(session, commands, ANSWER_TICKS);
            session.check(on.equals(Optional.of("{\"state\":\"ON\"}")), "a click on the live bulb publishes the explicit opposite: " + on);
            twin.probe().publish(state, red, false);
            session.check(session.await(() -> light(twin, bulb).on(), ANSWER_TICKS), "and the lamp follows the bulb on");

            // The allowlist entry gone: a click publishes nothing, and the action bar says why.
            reloadRawAllow(session, rawAllow);
            int mark = ChatLog.mark();
            session.pressUse();
            session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().contains("acl.rawAllow"), ANSWER_TICKS).isPresent(),
                    "without the allowlist entry a click is refused in the action bar (messages: " + ChatLog.since(mark) + ")");
            session.context().waitTicks(HOLD_TICKS);
            session.check(commands.nextLive().isEmpty(), "and nothing is published on " + state + "/set");
        }

        // Bind mode's picker would replace the MQTT device: it says so.
        session.hold(MineqttItems.Cyberdeck.get());
        session.onServer(s -> {
            CyberdeckDataUtil.setMode(session.player(s).getMainHandItem(), ConfiguratorMode.BIND);
            return null;
        });
        session.context().waitFor(mc -> CyberdeckDataUtil.mode(mc.player.getMainHandItem()) == ConfiguratorMode.BIND);
        session.aim(bulb);
        session.pressUse();
        session.context().waitForScreen(TwinPickerScreen.class);
        session.check(session.awaitClient(mc -> TwinPickerScreen.open(mc).flatMap(TwinPickerScreen::warningText)
                        .map(text -> text.contains("replaces its MQTT device " + device)).orElse(false), ANSWER_TICKS),
                "the entity picker warns that binding replaces the MQTT device: " + session.onClient(mc -> TwinPickerScreen.open(mc).flatMap(TwinPickerScreen::warningText)));
        session.context().getInput().pressKey(InputConstants.KEY_ESCAPE);
        session.check(session.awaitClient(mc -> mc.gui.screen() instanceof TabbedMqttScreen && label(mc, IS_SOURCE).equals("MQTT device"), ANSWER_TICKS),
                "Escape goes back to the Twin tab, still on the MQTT device");
        session.check(twin.preset(bulb).isPresent(), "and nothing was replaced");
        session.clickWidget(isButton("MQTT device"), "the source cycle");
        session.check(session.await(() -> twin.preset(bulb).isEmpty() && session.device(bulb, link -> link.twin()) == null, ANSWER_TICKS),
                "the source cycle unbinds the MQTT device");
        session.check(session.awaitClient(mc -> label(mc, IS_SOURCE).equals("This block"), ANSWER_TICKS), "and the tab shows This block");
        session.closeScreen();
        session.hold(Items.AIR);
    }

    /** Sets {@code acl.rawAllow} and reloads the config on the server thread, as a config screen or file reload does. */
    private static void reloadRawAllow(GameTestSession session, List<String> rawAllow) {
        session.onServer(s -> {
            MineQTTConfig.aclRawAllow = rawAllow;
            MineqttRuntime.configChanged();
            return null;
        });
        session.context().waitTicks(SETTLE_TICKS);
    }

    // The next message published live on the listener's topic within the timeout.
    private static Optional<String> awaitMessage(GameTestSession session, BrokerProbe.Listener listener, int timeoutTicks) {
        for (int tick = 0; tick <= timeoutTicks; tick++) {
            Optional<String> next = listener.nextLive();
            if (next.isPresent()) return next;
            session.context().waitTick();
        }
        return Optional.empty();
    }

    // ===== Helpers =====

    private static BlockPos place(GameTestSession session, Block block, BlockPos ground) {
        BlockPos pos = session.place(block, ground);
        session.awaitServer(s -> s.overworld().getBlockEntity(pos) instanceof DeviceHost host && host.deviceLink().attached());
        return pos;
    }

    private static LightState light(TwinFixture twin, BlockPos lamp) {
        return LightControl.light(twin.state(lamp));
    }

    private static int power(TwinFixture twin, BlockPos pos) {
        JsonObject state = twin.state(pos);
        return state.has("power") ? state.get("power").getAsInt() : -1;
    }

    // Reads the retained discovery message a few times, a second each, until it matches.
    private static boolean awaitDiscovery(GameTestSession session, TwinFixture twin, String topic, Predicate<JsonObject> matches) {
        for (int attempt = 0; attempt < 10; attempt++) {
            Optional<String> config = twin.probe().retained(topic);
            if (config.isPresent() && !config.get().isEmpty()
                    && matches.test(com.google.gson.JsonParser.parseString(config.get()).getAsJsonObject())) return true;
            session.context().waitTicks(10);
        }
        return false;
    }

    private static DeviceMenu menu(net.minecraft.client.Minecraft mc) {
        return (DeviceMenu) GameTestSession.openScreen(mc).getMenu();
    }

    // The Twin tab's source cycle: This block, Home Assistant or MQTT device.
    private static final Predicate<AbstractWidget> IS_SOURCE = widget -> widget instanceof Button
            && List.of("This block", "Home Assistant", "MQTT device").contains(widget.getMessage().getString());

    private static Predicate<AbstractWidget> isButton(String prefix) {
        return widget -> widget instanceof Button && widget.getMessage().getString().startsWith(prefix);
    }

    private static Predicate<AbstractWidget> isField(String label) {
        return widget -> widget instanceof EditBox && widget.getMessage().getString().equals(label);
    }

    private static String label(net.minecraft.client.Minecraft mc, Predicate<AbstractWidget> which) {
        return GameTestSession.openScreen(mc).children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                .filter(widget -> widget.visible && which.test(widget)).findFirst().map(widget -> widget.getMessage().getString()).orElse("");
    }

    private static List<String> labels(GameTestSession session) {
        return session.onClient(mc -> GameTestSession.openScreen(mc).children().stream().filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast).filter(widget -> widget.visible).map(widget -> widget.getMessage().getString()).toList());
    }
}

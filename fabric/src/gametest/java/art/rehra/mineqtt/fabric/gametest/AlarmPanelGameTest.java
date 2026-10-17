package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.blocks.AlarmPanelBlock;
import art.rehra.mineqtt.blocks.entities.AlarmPanelBlockEntity;
import art.rehra.mineqtt.blocks.entities.SpeakerBlockEntity;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.AlarmPanel;
import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import art.rehra.mineqtt.ui.device.AlarmKeypadTab;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Alarm Panel. Set by command it is disarmed, private to its owner and has no code, so it does not arm. Its screen
 * opens on the keypad, where the owner types a code and sets it. A Vibration Sensor's door event is wired to
 * {@code trigger_away} and {@code triggered} to a Speaker's {@code state}. Armed away (from Home Assistant's /set with the
 * code when a broker runs, else on the keypad), a door that redstone opens triggers it after the entry delay and the
 * Speaker sounds; the trigger source is the Vibration Sensor's model. Three wrong codes lock it out (from Home Assistant
 * with a broker, publishing anonymous wrong_code events, else on the keypad); the right code on the keypad is refused
 * during the lockout, and after it disarms the panel and stops the siren. {@code devices action disarm} without a code
 * is refused; a Redstone Input wired to {@code disarm} disarms it without one. The code is in no state, retained message,
 * event, screen view, saved block data or log line.
 */
public final class AlarmPanelGameTest implements FabricClientGameTest {
    private static final int Y = GameTestSession.GROUND_Y + 1;
    private static final BlockPos PANEL = new BlockPos(4, Y, 22);
    private static final BlockPos STAND = PANEL.south(2);
    private static final BlockPos SENSOR = PANEL.east(6);
    private static final BlockPos DOOR = SENSOR.east(4);
    private static final BlockPos DOOR_POWER = DOOR.east();
    private static final BlockPos SPEAKER = PANEL.west(4);
    private static final BlockPos INPUT = PANEL.west(8);
    private static final BlockPos INPUT_POWER = INPUT.south();
    // Eight digits nothing else in a run prints.
    private static final String CODE = "48213907";
    private static final String WRONG = "11112222";
    private static final int ANSWER_TICKS = 80;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "alarm_panel", AlarmPanelGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        session.context().waitTicks(25);
        String slug = panel(session);
        setCode(session, slug);
        String sensor = sensor(session);
        String speaker = speaker(session);
        session.consoleCommand("mineqtt wires add " + sensor + ".door " + slug + ".trigger_away");
        session.consoleCommand("mineqtt wires add " + slug + ".triggered " + speaker + ".state");
        session.check(session.onRuntime(runtime -> runtime.registry().wires().size()) >= 2, "the sensor's door event and the siren are wired");
        Optional<BrokerConfig> broker = session.broker();
        if (broker.isPresent()) {
            withBroker(session, slug, broker.get());
        } else {
            session.skip("arming, wrong codes and events through Home Assistant's /set", "no broker (" + GameTestSession.BROKER_ENV + " is not set)");
            withoutBroker(session);
        }
        disarmAfterLockout(session);
        wireDisarm(session, slug);
        secrecy(session, slug);
    }

    /** Set by command: a disarmed panel without a code, owned by the player, private; it does not arm without a code. */
    private static String panel(GameTestSession session) {
        setblock(session, PANEL, "mineqtt:alarm_panel[facing=south]");
        session.awaitServer(s -> s.overworld().getBlockEntity(PANEL) instanceof DeviceHost host && host.deviceLink().attached());
        session.check(session.onServer(s -> {
            BlockEntity blockEntity = s.overworld().getBlockEntity(PANEL);
            return ((DeviceHost) blockEntity).deviceLink().claim(blockEntity, session.playerId(), session.playerName());
        }), "the player claims the panel");
        session.check(AlarmPanel.TYPE.equals(session.device(PANEL, DeviceLink::type)), "a placed Alarm Panel block is an Alarm Panel device");
        String slug = session.device(PANEL, DeviceLink::slug);
        session.consoleCommand("mineqtt devices setting " + slug + " exit_delay 1");
        session.consoleCommand("mineqtt devices setting " + slug + " entry_delay 2");
        session.consoleCommand("mineqtt devices setting " + slug + " siren_duration 0");
        session.check(session.await(() -> state(session).get("exit_delay").getAsInt() == 1 && state(session).get("entry_delay").getAsInt() == 2
                && state(session).get("siren_duration").getAsInt() == 0, ANSWER_TICKS), "the delays are set: exit 1 s, entry 2 s, siren until disarmed");
        JsonObject state = state(session);
        session.check(state.get("state").getAsString().equals("disarmed") && !state.get("code_set").getAsBoolean() && state.get("wrong_codes").getAsInt() == 0,
                "it is disarmed and has no code (" + state + ")");
        session.consoleCommand("mineqtt devices set " + slug + " {\"action\":\"ARM_AWAY\"}");
        session.context().waitTicks(10);
        session.check(state(session).get("state").getAsString().equals("disarmed"), "without a code it does not arm");
        return slug;
    }

    /** The screen opens on the keypad; the owner types the code and sets it. */
    private static void setCode(GameTestSession session, String slug) {
        session.openBlock(PANEL);
        session.check(session.onClient(mc -> GameTestSession.openScreen(mc).getMenu().getActiveTabId()).equals(AlarmKeypadTab.ID),
                "a right-click opens the panel on its keypad");
        type(session, CODE);
        session.clickButton("Set code");
        session.check(session.await(() -> state(session).get("code_set").getAsBoolean(), ANSWER_TICKS), "Set code sets the typed code");
        session.check(session.onServer(s -> s.overworld().getBlockEntity(PANEL) instanceof AlarmPanelBlockEntity panel && panel.codeSet()), "the block has a code");
        session.screenshot("alarm_panel_keypad");
        session.closeScreen();
    }

    private static String sensor(GameTestSession session) {
        setblock(session, SENSOR, "mineqtt:vibration_sensor");
        session.awaitServer(s -> s.overworld().getBlockEntity(SENSOR) instanceof DeviceHost host && host.deviceLink().attached());
        setblock(session, DOOR, "minecraft:oak_door[half=lower,facing=west]");
        setblock(session, DOOR.above(), "minecraft:oak_door[half=upper,facing=west]");
        // Placing the door is a vibration too; the sensor rests before the next one counts.
        session.context().waitTicks(30);
        return session.device(SENSOR, DeviceLink::slug);
    }

    private static String speaker(GameTestSession session) {
        setblock(session, SPEAKER, "mineqtt:speaker");
        session.awaitServer(s -> s.overworld().getBlockEntity(SPEAKER) instanceof DeviceHost host && host.deviceLink().attached());
        String slug = session.device(SPEAKER, DeviceLink::slug);
        session.consoleCommand("mineqtt devices setting " + slug + " duration 600");
        session.check(session.await(() -> speakerState(session).get("duration").getAsInt() == 600, ANSWER_TICKS), "the Speaker sounds up to 10 minutes a run");
        return slug;
    }

    /**
     * Home Assistant arms it away with the code; the door triggers it after the entry delay and the Speaker sounds. Three
     * wrong codes on /set lock it out, each an anonymous wrong_code event; the retained state never holds the code.
     */
    private static void withBroker(GameTestSession session, String slug, BrokerConfig broker) {
        String stateTopic = "mineqtt/" + GameTestSession.BRIDGE_ID + "/" + slug;
        try (BrokerProbe probe = BrokerProbe.connect(broker);
             BrokerProbe eventProbe = BrokerProbe.connect(broker);
             BrokerProbe.Listener actions = eventProbe.listen(stateTopic + "/action")) {
            probe.publish(stateTopic + "/set", "{\"action\":\"ARM_AWAY\",\"code\":\"" + CODE + "\"}", false);
            session.check(session.await(() -> alarm(session).equals("arming"), ANSWER_TICKS), "Home Assistant's ARM_AWAY starts the exit delay (" + state(session) + ")");
            session.check(session.await(() -> alarm(session).equals("armed_away"), ANSWER_TICKS), "after 1 s it is armed away");
            session.check(blockStatus(session) == AlarmPanel.Light.ARMED, "the light is red");
            triggerByDoor(session);
            for (int i = 0; i < AlarmPanel.TRIES_BEFORE_LOCKOUT; i++) {
                probe.publish(stateTopic + "/set", "{\"action\":\"DISARM\",\"code\":\"" + WRONG + "\"}", false);
                session.context().waitTicks(25);
            }
            session.check(session.await(() -> state(session).get("wrong_codes").getAsInt() == 3 && state(session).get("lockout").getAsInt() > 0, ANSWER_TICKS),
                    "three wrong codes from Home Assistant are counted and lock it out (" + state(session) + ")");
            session.check(alarm(session).equals("triggered") && sounding(session), "it stays triggered, the siren sounds");
            probe.publish(stateTopic + "/set", "{\"action\":\"DISARM\",\"code\":\"" + CODE + "\"}", false);
            session.context().waitTicks(20);
            session.check(alarm(session).equals("triggered") && state(session).get("wrong_codes").getAsInt() == 3,
                    "during the lockout even the right code from Home Assistant is not taken, nor counted");
            List<String> events = new ArrayList<>();
            session.await(() -> {
                actions.nextLive().ifPresent(events::add);
                return events.stream().anyMatch(event -> event.contains("wrong_code"));
            }, ANSWER_TICKS);
            Optional<String> next;
            while ((next = actions.nextLive()).isPresent()) events.add(next.get());
            session.check(events.stream().anyMatch(event -> event.equals("{\"event_type\":\"wrong_code\"}")), "wrong_code events reach the action topic, bare (" + events + ")");
            session.check(events.stream().anyMatch(event -> event.contains("\"event_type\":\"arm\"") && event.contains("\"mode\":\"away\""))
                    && events.stream().anyMatch(event -> event.contains("\"event_type\":\"trigger\"") && event.contains("\"source\":\"Vibration Sensor\"")),
                    "the arm and trigger events name the mode and the kind of device (" + events + ")");
            session.check(events.stream().noneMatch(event -> event.contains(CODE) || event.contains(WRONG) || event.contains(session.playerName())),
                    "no event holds a code or the player's name");
            session.context().waitTicks(25);
            Optional<String> retained = probe.retained(stateTopic);
            session.check(retained.filter(text -> text.contains("\"state\":\"triggered\"")).isPresent(), "the retained state reads triggered (" + retained.map(String::length) + " chars)");
            session.check(retained.filter(text -> !text.contains(CODE) && !text.contains(WRONG)).isPresent(), "the retained state holds no code");
        }
    }

    /** Without a broker: armed on the keypad, triggered by the door, three wrong codes on the keypad lock it out. */
    private static void withoutBroker(GameTestSession session) {
        session.openBlock(PANEL);
        session.clickButton("Arm away");
        session.check(session.await(() -> alarm(session).equals("armed_away"), ANSWER_TICKS), "Arm away on the keypad arms it away after the exit delay");
        session.closeScreen();
        triggerByDoor(session);
        session.openBlock(PANEL);
        for (int i = 0; i < AlarmPanel.TRIES_BEFORE_LOCKOUT; i++) {
            type(session, WRONG);
            session.clickButton("Disarm");
            int count = i + 1;
            session.check(session.await(() -> state(session).get("wrong_codes").getAsInt() == count, ANSWER_TICKS), "wrong code " + count + " is counted");
        }
        session.check(state(session).get("lockout").getAsInt() > 0 && alarm(session).equals("triggered"), "the third locks it out; it stays triggered");
        session.closeScreen();
    }

    // Opens the door with redstone: the sensor's door event reaches trigger_away, pending for 2 s, then triggered.
    private static void triggerByDoor(GameTestSession session) {
        int before = sounds(session);
        setblock(session, DOOR_POWER, "minecraft:redstone_block");
        session.check(session.await(() -> alarm(session).equals("pending"), ANSWER_TICKS), "the door's vibration makes it pending (" + state(session) + ")");
        session.check(blockStatus(session) == AlarmPanel.Light.PENDING, "the light blinks amber");
        session.check(session.await(() -> alarm(session).equals("triggered"), ANSWER_TICKS), "after the entry delay it is triggered");
        JsonObject state = state(session);
        session.check(state.get("triggered").getAsBoolean() && state.get("trigger_source").getAsString().equals("Vibration Sensor"),
                "the trigger source is the device's kind (" + state + ")");
        session.check(session.await(() -> sounding(session) && sounds(session) > before, ANSWER_TICKS), "triggered wired to the Speaker's state sounds the siren");
        session.aim(PANEL);
        session.screenshot("alarm_panel_triggered");
        setblock(session, DOOR_POWER, "minecraft:air");
    }

    /** The right code on the keypad is refused while locked out; once the lockout is over it disarms and the siren stops. */
    private static void disarmAfterLockout(GameTestSession session) {
        session.openBlock(PANEL);
        type(session, CODE);
        session.clickButton("Disarm");
        session.context().waitTicks(10);
        session.check(alarm(session).equals("triggered") && state(session).get("wrong_codes").getAsInt() == 3, "the keypad's right code is refused during the lockout");
        int lockout = state(session).get("lockout").getAsInt();
        session.closeScreen();
        session.context().waitTicks(lockout * 20 + 25);
        session.check(state(session).get("lockout").getAsInt() == 0, "the lockout ends");
        session.openBlock(PANEL);
        type(session, CODE);
        session.clickButton("Disarm");
        session.check(session.await(() -> alarm(session).equals("disarmed"), ANSWER_TICKS), "after the lockout the right code disarms it (" + state(session) + ")");
        session.check(session.await(() -> !sounding(session), ANSWER_TICKS), "and the siren stops");
        session.check(blockStatus(session) == AlarmPanel.Light.DISARMED, "the light is green");
        session.closeScreen();
    }

    /** A command without the code does not disarm it; a wire does. */
    private static void wireDisarm(GameTestSession session, String slug) {
        session.openBlock(PANEL);
        session.clickButton("Arm home");
        session.check(session.await(() -> alarm(session).equals("armed_home"), ANSWER_TICKS), "Arm home on the keypad arms it home");
        session.closeScreen();
        session.consoleCommand("mineqtt devices action " + slug + " disarm ON");
        session.context().waitTicks(10);
        session.check(alarm(session).equals("armed_home"), "the disarm action without a code is refused");
        setblock(session, INPUT, "mineqtt:redstone_publisher_block");
        session.awaitServer(s -> s.overworld().getBlockEntity(INPUT) instanceof DeviceHost host && host.deviceLink().attached());
        String input = session.device(INPUT, DeviceLink::slug);
        session.consoleCommand("mineqtt wires add " + input + ".powered " + slug + ".disarm");
        setblock(session, INPUT_POWER, "minecraft:redstone_block");
        session.check(session.await(() -> alarm(session).equals("disarmed"), ANSWER_TICKS), "a Redstone Input wired to disarm disarms it without a code");
        setblock(session, INPUT_POWER, "minecraft:air");
    }

    /** The code is nowhere: not in the state, the registry, the owner's screen view (Diagnostics included), the block's data or the logs. */
    private static void secrecy(GameTestSession session, String slug) {
        session.check(!state(session).toString().contains(CODE), "the state holds no code");
        session.check(session.onRuntime(runtime -> {
            DeviceRecord record = runtime.registry().bySlug(slug);
            return record != null && (record.lastState() == null || !record.lastState().toString().contains(CODE))
                    && (record.pending() == null || !record.pending().toString().contains(CODE));
        }), "the registry record holds no code");
        session.check(session.onServer(s -> !DeviceRuntime.view(session.player(s), s.overworld().getBlockEntity(PANEL)).toString().contains(CODE)
                && !DeviceRuntime.view(session.player(s), s.overworld().getBlockEntity(PANEL)).toString().contains(WRONG)),
                "the owner's screen view, its Diagnostics' last message included, holds no code");
        session.check(session.onServer(s -> {
            BlockEntity panel = s.overworld().getBlockEntity(PANEL);
            String saved = panel.saveWithoutMetadata(s.registryAccess()).toString();
            return saved.contains("\"code\"") || saved.contains("code:") ? !saved.contains(CODE) : false;
        }), "the block's saved data has the code's hash, not the code");
        List<String> logs = new ArrayList<>();
        for (String name : List.of("latest.log", "debug.log")) {
            Path log = FabricLoader.getInstance().getGameDir().resolve("logs").resolve(name);
            if (!Files.exists(log)) continue;
            try {
                String text = Files.readString(log, StandardCharsets.UTF_8);
                logs.add(name);
                session.check(!text.contains(CODE) && !text.contains(WRONG), name + " holds no code");
            } catch (IOException e) {
                session.check(false, name + " cannot be read: " + e.getMessage());
            }
        }
        session.check(!logs.isEmpty(), "a log was read (" + logs + ")");
    }

    // Types digits on the open keypad, one key each.
    private static void type(GameTestSession session, String digits) {
        for (char digit : digits.toCharArray()) session.clickButton(String.valueOf(digit));
    }

    private static String alarm(GameTestSession session) {
        return state(session).get("state").getAsString();
    }

    private static AlarmPanel.Light blockStatus(GameTestSession session) {
        return session.onServer(s -> s.overworld().getBlockState(PANEL).getValue(AlarmPanelBlock.STATUS));
    }

    private static boolean sounding(GameTestSession session) {
        return session.onServer(s -> s.overworld().getBlockEntity(SPEAKER) instanceof SpeakerBlockEntity speaker && speaker.sounding());
    }

    private static int sounds(GameTestSession session) {
        return session.onServer(s -> s.overworld().getBlockEntity(SPEAKER) instanceof SpeakerBlockEntity speaker ? speaker.soundsPlayed() : -1);
    }

    private static void setblock(GameTestSession session, BlockPos pos, String block) {
        session.consoleCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block);
    }

    private static JsonObject state(GameTestSession session) {
        return stateAt(session, PANEL);
    }

    private static JsonObject speakerState(GameTestSession session) {
        return stateAt(session, SPEAKER);
    }

    private static JsonObject stateAt(GameTestSession session, BlockPos pos) {
        return session.onServer(s -> {
            StateWriter state = new StateWriter();
            if (s.overworld().getBlockEntity(pos) instanceof DeviceHost host) host.writeState(state);
            return state.json();
        });
    }
}

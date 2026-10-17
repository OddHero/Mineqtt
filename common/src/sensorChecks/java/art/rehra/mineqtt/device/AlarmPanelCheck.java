package art.rehra.mineqtt.device;

import art.rehra.mineqtt.device.types.AlarmPanel;
import art.rehra.mineqtt.device.types.AlarmPanel.Alarm;
import art.rehra.mineqtt.device.types.AlarmPanel.AlarmState;
import art.rehra.mineqtt.device.types.AlarmPanel.CodeHash;
import art.rehra.mineqtt.device.types.AlarmPanel.Guard;
import art.rehra.mineqtt.device.types.AlarmPanel.Mode;
import art.rehra.mineqtt.device.types.AlarmPanel.Settings;
import art.rehra.mineqtt.device.types.AlarmPanel.Verdict;
import art.rehra.mineqtt.device.types.AlarmPanel.Zone;
import art.rehra.mineqtt.flow.core.Mapping;
import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.ha.HaEntity;
import art.rehra.mineqtt.network.MineqttNetworking.AlarmKeypadPayload;
import art.rehra.mineqtt.ui.device.AlarmKeypadTab;
import art.rehra.mineqtt.ui.device.DeviceTabs;
import art.rehra.mineqtt.ui.framework.MqttTab;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Dependency-free regression checks of the Alarm Panel, run by Gradle's verifyAlarmPanel task: the state machine with its
 * exit delay, entry delay and siren time, which zone triggers which armed mode, the panic, the code's format, hash and
 * constant-time check, the wrong-code guard and its growing lockout, what each command source may ask (a wire disarms
 * without a code, nobody else does), the events, the saved form, and that the code is in no state, saved hash, log form
 * or Diagnostics payload: the secret key is masked wherever the runtime logs or keeps a command.
 */
public final class AlarmPanelCheck {
    private static final String CODE = "48213907";
    private static final int TPS = AlarmPanel.TICKS_PER_SECOND;

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        declaration();
        arming();
        triggers();
        siren();
        panic();
        codes();
        guard();
        requests();
        events();
        saved();
        secrecy();
        keypad();
        wires();
        System.out.println("Alarm Panel checks passed.");
    }

    /** Never queued (the queue is saved), private by default, the code a secret, the alarm entity first and primary. */
    private static void declaration() {
        DeviceType type = AlarmPanel.TYPE;
        check(type.unloadedPolicy() == UnloadedPolicy.UNAVAILABLE && type.secrets().equals(java.util.Set.of("code")) && type.noV1Topic()
                        && type.rawLink().isEmpty() && type.use().isEmpty() && !type.groupable() && type.status().isPresent()
                        && type.defaultAccess() == art.rehra.mineqtt.security.AccessMode.PRIVATE,
                "The panel drops commands while unloaded, keeps its code a secret, has no Raw Link, no hand action, a status line and private access");
        for (String key : List.of(AlarmPanel.ARM_AWAY, AlarmPanel.ARM_HOME, AlarmPanel.DISARM, AlarmPanel.TRIGGER_AWAY, AlarmPanel.TRIGGER_HOME)) {
            check(!type.hasControl(key), key + " has no Controls row: the keypad and wires fire it");
        }
        check(type.fields().stream().noneMatch(field -> field.key().equals("code")), "The code is no field");
        HaEntity.AlarmControlPanel panel = (HaEntity.AlarmControlPanel) type.entities().getFirst();
        check(panel.meta().name() == null && panel.state() == AlarmPanel.STATE && panel.fields().isEmpty() && panel.commands()
                        && panel.states().equals(List.of("disarmed", "arming", "armed_home", "armed_away", "pending", "triggered"))
                        && HaEntity.AlarmControlPanel.STATES.containsAll(panel.states()),
                "The alarm panel is the primary entity over the six Home Assistant states it uses");
        check(panel.features().equals(List.of("arm_home", "arm_away", "trigger")) && !panel.codeArm() && panel.codeDisarm() && !panel.codeTrigger(),
                "Arm home, arm away and trigger; Home Assistant asks for the code to disarm only");
        check(HaEntity.AlarmControlPanel.commandTemplate().equals("{\"action\":\"{{ action }}\",\"code\":{{ code | tojson }}}"),
                "The command template sends the action and the code as JSON: " + HaEntity.AlarmControlPanel.commandTemplate());
        // A type with an alarm panel must keep the code a secret, and a type with secrets never queues.
        Field<AlarmState> state = Field.enumOf("state", AlarmState.class, Access.READ);
        failsToBuild(DeviceType.builder(id("a")).model("A").unloaded(UnloadedPolicy.UNAVAILABLE).field(state)
                .entity(art.rehra.mineqtt.ha.Ha.alarmControlPanel("alarm", state)), "secret", "An alarm panel without its code declared a secret");
        failsToBuild(DeviceType.builder(id("b")).model("B").field(state).secret("code")
                .entity(art.rehra.mineqtt.ha.Ha.alarmControlPanel("alarm", state)), "queues", "A type with secrets that queues commands");
        Field<Mode> notStates = Field.enumOf("state", Mode.class, Access.READ);
        failsToBuild(DeviceType.builder(id("c")).model("C").unloaded(UnloadedPolicy.UNAVAILABLE).field(notStates).secret("code")
                .entity(art.rehra.mineqtt.ha.Ha.alarmControlPanel("alarm", notStates)), "no alarm states", "An alarm panel over a field that is no alarm state");
        failsToBuild(DeviceType.builder(id("d")).model("D").unloaded(UnloadedPolicy.UNAVAILABLE).field(Field.text("code", Access.READ)).secret("code"),
                "also a field", "A secret that is also a field");
    }

    /** Only a disarmed panel arms: arming for the exit delay, then armed; without a delay armed at once. */
    private static void arming() {
        Settings settings = new Settings(2, 3, 5);
        Alarm arming = Alarm.DISARMED.arm(Mode.AWAY, settings).orElseThrow();
        check(arming.state() == AlarmState.ARMING && arming.mode() == Mode.AWAY && arming.left() == 2 * TPS && arming.secondsLeft() == 2 && !arming.armed(),
                "Arming away for the 2 s exit delay is not armed yet: " + arming);
        check(arming.arm(Mode.HOME, settings).isEmpty(), "A panel that is arming does not arm again (no switch to home)");
        Alarm armed = ticks(arming, settings, 2 * TPS);
        check(armed.state() == AlarmState.ARMED_AWAY && armed.armed() && armed.left() == 0, "After the exit delay it is armed away: " + armed);
        check(armed.arm(Mode.HOME, settings).isEmpty(), "Armed away does not become armed home without a disarm");
        check(ticks(arming, settings, 2 * TPS - 1).state() == AlarmState.ARMING, "One tick before the end it still arms");
        Alarm home = Alarm.DISARMED.arm(Mode.HOME, new Settings(0, 3, 5)).orElseThrow();
        check(home.state() == AlarmState.ARMED_HOME && home.armed(), "Without an exit delay it is armed at once");
        check(Alarm.DISARMED.arm(Mode.NONE, settings).isEmpty(), "Mode none arms nothing");
        check(armed.disarm().equals(new Alarm(AlarmState.DISARMED, Mode.NONE, 0, armed.source(), armed.zone())), "Disarm from any state is disarmed");
    }

    /** The perimeter (home zone) triggers both armed modes, the inside (away zone) only armed away; a delay is not restarted. */
    private static void triggers() {
        Settings settings = new Settings(0, 3, 5);
        Alarm away = Alarm.DISARMED.arm(Mode.AWAY, settings).orElseThrow();
        Alarm home = Alarm.DISARMED.arm(Mode.HOME, settings).orElseThrow();
        check(home.trigger(Zone.AWAY, "Vibration Sensor", settings).isEmpty(), "An inside sensor does not trigger a panel armed home");
        Alarm pending = home.trigger(Zone.HOME, "Smart Lock", settings).orElseThrow();
        check(pending.state() == AlarmState.PENDING && pending.left() == 3 * TPS && pending.source().equals("Smart Lock") && pending.zone() == Zone.HOME
                        && pending.armed() && !pending.triggered(),
                "A perimeter sensor makes armed home pending for the entry delay: " + pending);
        Alarm awayPending = away.trigger(Zone.AWAY, "Vibration Sensor", settings).orElseThrow();
        check(awayPending.state() == AlarmState.PENDING && awayPending.mode() == Mode.AWAY, "An inside sensor makes armed away pending");
        check(away.trigger(Zone.HOME, "Smart Lock", settings).isPresent(), "A perimeter sensor triggers armed away too");
        Alarm later = ticks(awayPending, settings, TPS);
        check(later.trigger(Zone.AWAY, "Other", settings).isEmpty(), "A second trigger while pending does not restart the entry delay");
        check(Alarm.DISARMED.trigger(Zone.HOME, "x", settings).isEmpty(), "A disarmed panel ignores triggers");
        Alarm arming = Alarm.DISARMED.arm(Mode.AWAY, new Settings(5, 3, 5)).orElseThrow();
        check(arming.trigger(Zone.HOME, "x", settings).isEmpty(), "A panel still arming ignores triggers: the exit delay is for leaving");
        Alarm triggered = ticks(awayPending, settings, 3 * TPS);
        check(triggered.state() == AlarmState.TRIGGERED && triggered.left() == 5 * TPS && triggered.triggered() && triggered.armed(),
                "After the entry delay it is triggered for the siren's 5 s: " + triggered);
        check(triggered.trigger(Zone.HOME, "x", settings).isEmpty(), "A triggered panel ignores further triggers");
        Alarm instant = away.trigger(Zone.AWAY, "x", new Settings(0, 0, 5)).orElseThrow();
        check(instant.state() == AlarmState.TRIGGERED, "Without an entry delay it is triggered at once");
        check(triggered.disarm().state() == AlarmState.DISARMED && triggered.disarm().source().equals("Vibration Sensor"),
                "Disarming a triggered panel stops it and keeps the last source");
    }

    /** After the siren's time it is armed again as before; a siren time of 0 keeps it triggered until disarmed. */
    private static void siren() {
        Settings settings = new Settings(0, 0, 2);
        Alarm triggered = Alarm.DISARMED.arm(Mode.HOME, settings).orElseThrow().trigger(Zone.HOME, "x", settings).orElseThrow();
        check(ticks(triggered, settings, 2 * TPS - 1).triggered(), "The siren runs its 2 s");
        Alarm after = ticks(triggered, settings, 2 * TPS);
        check(after.state() == AlarmState.ARMED_HOME && !after.triggered() && after.armed(), "Then it is armed home again: " + after);
        Settings forever = new Settings(0, 0, 0);
        Alarm held = Alarm.DISARMED.arm(Mode.AWAY, forever).orElseThrow().trigger(Zone.AWAY, "x", forever).orElseThrow();
        check(ticks(held, forever, 100 * TPS).triggered(), "A siren time of 0 keeps it triggered until disarmed");
    }

    /** Home Assistant's panic triggers at once from any state but triggered, and returns to the mode it was armed in. */
    private static void panic() {
        Settings settings = new Settings(5, 5, 2);
        Alarm fromDisarmed = Alarm.DISARMED.panic(AlarmPanel.SOURCE_HOME_ASSISTANT, settings).orElseThrow();
        check(fromDisarmed.triggered() && fromDisarmed.mode() == Mode.NONE && !fromDisarmed.armed() && fromDisarmed.zone() == Zone.PANIC,
                "A panic while disarmed is triggered and not armed: " + fromDisarmed);
        check(ticks(fromDisarmed, settings, 2 * TPS).state() == AlarmState.DISARMED, "and disarmed again after the siren");
        Alarm armed = ticks(Alarm.DISARMED.arm(Mode.HOME, settings).orElseThrow(), settings, 5 * TPS);
        Alarm fromArmed = armed.panic("Home Assistant", settings).orElseThrow();
        check(ticks(fromArmed, settings, 2 * TPS).state() == AlarmState.ARMED_HOME, "A panic while armed home returns to armed home");
        Alarm arming = Alarm.DISARMED.arm(Mode.AWAY, settings).orElseThrow();
        check(arming.panic("x", settings).orElseThrow().mode() == Mode.NONE, "A panic while arming returns to disarmed");
        check(fromArmed.panic("x", settings).isEmpty(), "A triggered panel takes no second panic");
    }

    /** 4 to 8 digits; a salted hash that matches only the code, differs per code even for the same digits. */
    private static void codes() {
        for (String good : List.of("0000", "1234", "48213907")) check(AlarmPanel.codeProblem(good).isEmpty(), good + " is a code");
        for (String bad : List.of("", "123", "123456789", "12a4", " 1234", "１２３４", "12.4")) check(AlarmPanel.codeProblem(bad).isPresent(), "'" + bad + "' is no code");
        long start = System.nanoTime();
        CodeHash hash = CodeHash.of(CODE);
        long hashMillis = (System.nanoTime() - start) / 1_000_000;
        check(hash.iterations() == CodeHash.ITERATIONS && hash.salt().length == CodeHash.SALT_BYTES && hash.hash().length == 32,
                "A code hash: " + CodeHash.ITERATIONS + " rounds, a 16-byte salt, 32 bytes");
        start = System.nanoTime();
        check(hash.matches(CODE), "The code matches its hash");
        long checkMillis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("PBKDF2 with " + CodeHash.ITERATIONS + " rounds: hash " + hashMillis + " ms, check " + checkMillis + " ms");
        check(!hash.matches("48213908") && !hash.matches("4821390") && !hash.matches("482139070") && !hash.matches("") && !hash.matches(null),
                "Nothing else matches, also not a code one digit longer or shorter");
        CodeHash again = CodeHash.of(CODE);
        check(!java.util.Arrays.equals(hash.salt(), again.salt()) && !java.util.Arrays.equals(hash.hash(), again.hash()) && !hash.equals(again),
                "The same code hashes differently with a fresh salt");
        check(!hash.toString().contains(CODE) && !hash.toString().contains(java.util.Base64.getEncoder().encodeToString(hash.hash())),
                "A code hash's text names neither the code nor the hash: " + hash);
        boolean refused;
        try {
            CodeHash.of("12");
            refused = false;
        } catch (IllegalArgumentException e) {
            refused = !e.getMessage().contains("12");
        }
        check(refused, "A malformed code is not hashed, and the refusal does not repeat it");
    }

    /** Three wrong codes lock it out for 30 s, the next three for 60 s, doubling to 15 minutes; a right code ends it. */
    private static void guard() {
        check(Guard.lockoutSeconds(1) == 30 && Guard.lockoutSeconds(2) == 60 && Guard.lockoutSeconds(3) == 120 && Guard.lockoutSeconds(5) == 480
                && Guard.lockoutSeconds(6) == 900 && Guard.lockoutSeconds(40) == 900, "The lockouts: 30, 60, 120 s, ... at most 900 s");
        Optional<CodeHash> code = Optional.of(CodeHash.of(CODE));
        Guard guard = Guard.NONE;
        check(AlarmPanel.check(code, guard, Optional.of("1111")) == Verdict.WRONG, "A wrong code is wrong");
        guard = guard.wrong().wrong();
        check(!guard.lockedOut() && guard.wrongCodes() == 2, "Two wrong codes do not lock it out");
        guard = guard.wrong();
        check(guard.lockedOut() && guard.lockoutLeft() == 30 * TPS && guard.lockoutSeconds() == 30 && guard.wrongCodes() == 3, "The third locks it out for 30 s: " + guard);
        check(AlarmPanel.check(code, guard, Optional.of(CODE)) == Verdict.LOCKED_OUT, "While locked out even the right code is not checked");
        Guard after = guard;
        for (int i = 0; i < 30 * TPS; i++) after = after.tick();
        check(!after.lockedOut() && AlarmPanel.check(code, after, Optional.of(CODE)) == Verdict.RIGHT, "After 30 s it takes the code again");
        Guard second = after.wrong().wrong().wrong();
        check(second.lockoutSeconds() == 60 && second.lockouts() == 2, "The next three wrong codes lock it out for 60 s");
        Guard right = after.wrong().right();
        check(right.streak() == 0 && right.lockouts() == 0 && right.wrongCodes() == 4 && !right.lockedOut(), "A right code ends the streak and the doubling, not the count");
        check(AlarmPanel.check(Optional.empty(), Guard.NONE, Optional.of(CODE)) == Verdict.NO_CODE, "Without a code there is nothing to check");
        check(AlarmPanel.check(code, Guard.NONE, Optional.empty()) == Verdict.MISSING && AlarmPanel.check(code, Guard.NONE, Optional.of("")) == Verdict.MISSING,
                "No code given is missing, not wrong");
        check(AlarmPanel.entry(new CommandReader(json("{\"code\":null}"))).isEmpty() && AlarmPanel.entry(new CommandReader(json("{\"code\":\" 1234 \"}"))).equals(Optional.of("1234"))
                        && AlarmPanel.entry(new CommandReader(json("{\"code\":1234}"))).equals(Optional.of("1234")),
                "Home Assistant's null is no code; a code is read as text, trimmed");
        Guard capped = new Guard(0, 0, 0, AlarmPanel.MAX_COUNT).wrong();
        check(capped.wrongCodes() == AlarmPanel.MAX_COUNT, "The count stops at its maximum");
    }

    /** What each source may ask: Home Assistant's action grammar, the in-ports, triggers from wires only. */
    private static void requests() {
        check(request("{\"action\":\"ARM_AWAY\",\"code\":null}", CommandSource.HA).equals(Optional.of(new AlarmPanel.Arm(Mode.AWAY))), "HA arms away");
        check(request("{\"action\":\"arm_home\"}", CommandSource.HA).equals(Optional.of(new AlarmPanel.Arm(Mode.HOME))), "HA arms home, any case");
        check(request("{\"action\":\"DISARM\",\"code\":\"1234\"}", CommandSource.HA).equals(Optional.of(new AlarmPanel.Disarm())), "HA disarms");
        check(request("{\"action\":\"TRIGGER\",\"code\":null}", CommandSource.HA).equals(Optional.of(new AlarmPanel.Panic())), "HA panics");
        check(request("{\"action\":\"ARM_NIGHT\"}", CommandSource.HA).orElseThrow() instanceof AlarmPanel.Refused, "An action it does not offer is refused");
        check(request("{\"action\":\"DISARM\"}", CommandSource.BINDING).orElseThrow() instanceof AlarmPanel.Refused, "A wire does not send the action grammar");
        check(request("{\"action\":\"DISARM\"}", CommandSource.GROUP).orElseThrow() instanceof AlarmPanel.Refused, "Nor a group");
        check(request("{\"disarm\":\"TOGGLE\"}", CommandSource.BINDING).equals(Optional.of(new AlarmPanel.Disarm())), "A wire's event disarms");
        check(request("{\"disarm\":true}", CommandSource.BINDING).equals(Optional.of(new AlarmPanel.Disarm())), "A wire's rising bool disarms");
        check(request("{\"disarm\":false}", CommandSource.BINDING).isEmpty() && request("{\"arm_away\":\"OFF\"}", CommandSource.BINDING).isEmpty(),
                "A falling bool does nothing");
        check(request("{\"arm_home\":\"PRESS\"}", CommandSource.PLAYER).equals(Optional.of(new AlarmPanel.Arm(Mode.HOME))), "The keypad arms home");
        check(request("{\"disarm\":\"PRESS\",\"code\":\"1\"}", CommandSource.PLAYER).equals(Optional.of(new AlarmPanel.Disarm())), "The keypad disarms (with a code to check)");
        check(request("{\"trigger_home\":true}", CommandSource.BINDING).equals(Optional.of(new AlarmPanel.Trigger(Zone.HOME))), "A wire triggers the perimeter");
        check(request("{\"trigger_away\":\"TOGGLE\"}", CommandSource.BINDING).equals(Optional.of(new AlarmPanel.Trigger(Zone.AWAY))), "and the inside");
        check(request("{\"trigger_home\":true}", CommandSource.HA).orElseThrow() instanceof AlarmPanel.Refused
                && request("{\"trigger_away\":\"PRESS\"}", CommandSource.PLAYER).orElseThrow() instanceof AlarmPanel.Refused,
                "Only wires fire the triggers: Home Assistant panics with TRIGGER, players do not trigger it");
        check(request("{\"exit_delay\":5}", CommandSource.HA).isEmpty() && AlarmPanel.known(new CommandReader(json("{\"exit_delay\":5}"))),
                "A setting asks nothing of the alarm but is known");
        check(!AlarmPanel.known(new CommandReader(json("{\"code\":\"1234\"}"))) && !AlarmPanel.known(new CommandReader(json("{\"state\":\"ON\"}"))),
                "A code alone or a switch command is no alarm panel command");
        Settings set = Settings.DEFAULT.apply(new CommandReader(json("{\"exit_delay\":9999,\"entry_delay\":-4,\"siren_duration\":60}")));
        check(set.equals(new Settings(AlarmPanel.MAX_DELAY, 0, 60)), "Settings are clamped: " + set);
        check(Action.fires(json("{\"v\":\"TOGGLE\"}").get("v")) && Action.fires(json("{\"v\":\"PRESS\"}").get("v")) && Action.fires(json("{\"v\":\"on\"}").get("v"))
                && !Action.fires(json("{\"v\":\"OFF\"}").get("v")) && !Action.fires(json("{\"v\":{}}").get("v")) && !Action.fires(null),
                "An edge fires on true, ON, TOGGLE and PRESS");
    }

    /** The four events, never naming who: arm with the mode, trigger with the zone and the device kind, wrong_code bare. */
    private static void events() {
        DeviceType type = AlarmPanel.TYPE;
        check(type.events().stream().map(EventSpec::type).toList().equals(List.of("arm", "disarm", "trigger", "wrong_code")), "Four events");
        check(AlarmPanel.WRONG_CODE.minInterval().equals(java.time.Duration.ofSeconds(1)), "wrong_code at most once a second on the action topic");
        JsonObject arm = AlarmPanel.armEvent(Mode.AWAY);
        check(type.eventProblem(arm).isEmpty() && arm.toString().equals("{\"event_type\":\"arm\",\"mode\":\"away\"}"), "arm: " + arm);
        JsonObject trigger = AlarmPanel.triggerEvent(Zone.HOME, "Smart Lock");
        check(type.eventProblem(trigger).isEmpty() && trigger.toString().equals("{\"event_type\":\"trigger\",\"zone\":\"home\",\"source\":\"Smart Lock\"}"), "trigger: " + trigger);
        check(type.eventProblem(AlarmPanel.WRONG_CODE.payload()).isEmpty() && AlarmPanel.WRONG_CODE.payload().keySet().equals(java.util.Set.of("event_type")),
                "wrong_code carries nothing but its type: " + AlarmPanel.WRONG_CODE.payload());
        check(AlarmPanel.triggerEvent(Zone.AWAY, "x".repeat(100)).get("source").getAsString().length() == AlarmPanel.MAX_SOURCE_LENGTH, "A source is cut");
    }

    /** The saved forms read back; an impossible saved state is repaired; a damaged code hash is no code. */
    private static void saved() {
        Settings settings = new Settings(10, 20, 30);
        check(Settings.CODEC.parse(NbtOps.INSTANCE, Settings.CODEC.encodeStart(NbtOps.INSTANCE, settings).getOrThrow()).getOrThrow().equals(settings), "Settings round trip");
        Alarm alarm = new Alarm(AlarmState.PENDING, Mode.HOME, 55, "Smart Lock", Zone.HOME);
        check(Alarm.CODEC.parse(NbtOps.INSTANCE, Alarm.CODEC.encodeStart(NbtOps.INSTANCE, alarm).getOrThrow()).getOrThrow().equals(alarm), "Alarm round trip");
        Guard guard = new Guard(2, 1, 99, 7);
        check(Guard.CODEC.parse(NbtOps.INSTANCE, Guard.CODEC.encodeStart(NbtOps.INSTANCE, guard).getOrThrow()).getOrThrow().equals(guard), "Guard round trip");
        CodeHash code = CodeHash.of(CODE);
        Tag tag = CodeHash.CODEC.encodeStart(NbtOps.INSTANCE, code).getOrThrow();
        CodeHash read = CodeHash.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
        check(read.equals(code) && read.matches(CODE), "The code hash reads back and still matches");
        check(!tag.toString().contains(CODE), "The saved hash does not hold the code: " + tag);
        check(CodeHash.CODEC.parse(JsonOps.INSTANCE, json("{\"salt\":\"AAAA\",\"hash\":\"AAAA\",\"iterations\":60000}")).error().isPresent()
                        && CodeHash.CODEC.parse(JsonOps.INSTANCE, json("{\"salt\":\"" + b64(16) + "\",\"hash\":\"" + b64(32) + "\",\"iterations\":99999999}")).error().isPresent()
                        && CodeHash.CODEC.parse(JsonOps.INSTANCE, json("{\"salt\":\"!!\",\"hash\":\"" + b64(32) + "\",\"iterations\":60000}")).error().isPresent(),
                "A hash of the wrong size, too many rounds or no base64 does not load");
        Alarm repaired = new Alarm(AlarmState.ARMED_AWAY, Mode.NONE, 77, "", Zone.HOME);
        check(repaired.mode() == Mode.AWAY && repaired.left() == 0, "An armed state takes its own mode and no countdown: " + repaired);
        check(new Alarm(AlarmState.DISARMED, Mode.HOME, 5, "", Zone.HOME).equals(new Alarm(AlarmState.DISARMED, Mode.NONE, 0, "", Zone.HOME)), "Disarmed has no mode");
        check(Alarm.CODEC.parse(JsonOps.INSTANCE, json("{}")).getOrThrow().equals(Alarm.DISARMED) && Guard.CODEC.parse(JsonOps.INSTANCE, json("{}")).getOrThrow().equals(Guard.NONE),
                "Missing keys read as a disarmed panel without a lockout");
        check(alarm.placed().equals(Alarm.DISARMED) && guard.placed().equals(Guard.NONE), "A placed copy is disarmed and counted nothing");
    }

    /** The code never shows: not in the state, not in a logged or kept command, not in the Diagnostics payload or the keypad payload. */
    private static void secrecy() {
        StateWriter out = AlarmPanel.write(new StateWriter(), new Alarm(AlarmState.ARMED_AWAY, Mode.AWAY, 0, "Smart Lock", Zone.HOME), Settings.DEFAULT,
                new Guard(1, 0, 0, 1), true);
        JsonObject state = out.json();
        check(state.keySet().equals(java.util.Set.of("state", "armed", "triggered", "time_left", "trigger_source", "wrong_codes", "lockout", "code_set",
                "exit_delay", "entry_delay", "siren_duration")) && !state.has("code") && state.get("code_set").getAsBoolean(),
                "The state says whether a code is set, never the code: " + state);
        JsonObject command = json("{\"action\":\"DISARM\",\"code\":\"" + CODE + "\"}");
        JsonObject redacted = AlarmPanel.TYPE.redact(command);
        check(!redacted.toString().contains(CODE) && redacted.get("code").getAsString().equals(DeviceType.REDACTED) && redacted.get("action").getAsString().equals("DISARM")
                        && command.get("code").getAsString().equals(CODE),
                "A logged command masks the code and keeps the rest; the command itself is untouched: " + redacted);
        check(art.rehra.mineqtt.device.types.SmartLock.TYPE.redact(command) == command, "A type without secrets logs its commands as they are");
        String kept = new String(DeviceRuntime.redactedPayload(AlarmPanel.TYPE, "mineqtt/w/panel/set", command.toString().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        check(!kept.contains(CODE) && kept.contains("DISARM"), "The Diagnostics tab keeps the /set payload with the code masked: " + kept);
        String plain = new String(DeviceRuntime.redactedPayload(AlarmPanel.TYPE, "mineqtt/w/panel/set/code", CODE.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        String broken = new String(DeviceRuntime.redactedPayload(AlarmPanel.TYPE, "mineqtt/w/panel/set", ("{\"code\":\"" + CODE + "\"").getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        check(plain.equals(DeviceType.REDACTED) && broken.equals(DeviceType.REDACTED), "A plain value or broken JSON is masked whole: " + plain + ", " + broken);
        byte[] other = "{\"state\":\"ON\"}".getBytes(StandardCharsets.UTF_8);
        check(DeviceRuntime.redactedPayload(art.rehra.mineqtt.device.types.Speaker.TYPE, "x/set", other) == other, "Other types keep their payloads");
        AlarmKeypadPayload payload = new AlarmKeypadPayload(new BlockPos(1, 2, 3), AlarmKeypadPayload.Op.DISARM, CODE);
        check(!payload.toString().contains(CODE) && payload.toString().contains("DISARM"), "The keypad payload's text leaves the digits out: " + payload);
        check(AlarmKeypadPayload.Op.DISARM.permission() == art.rehra.mineqtt.integrations.MineqttPermission.USE
                        && AlarmKeypadPayload.Op.ARM_AWAY.permission() == art.rehra.mineqtt.integrations.MineqttPermission.USE
                        && AlarmKeypadPayload.Op.SET_CODE.permission() == art.rehra.mineqtt.integrations.MineqttPermission.CONFIGURE
                        && AlarmKeypadPayload.Op.CLEAR_CODE.permission() == art.rehra.mineqtt.integrations.MineqttPermission.CONFIGURE,
                "Arming and disarming need USE, the code CONFIGURE");
        boolean unknownOp;
        try {
            AlarmKeypadPayload.Op.of(9);
            unknownOp = false;
        } catch (RuntimeException e) {
            unknownOp = true;
        }
        check(unknownOp, "An unknown keypad op does not decode");
    }

    /** The keypad tab: first for the panel, also for players who may only use it; typing, masking and which keys work. */
    private static void keypad() {
        List<String> tabs = DeviceTabs.forType(AlarmPanel.TYPE).stream().map(MqttTab::id).toList();
        check(tabs.getFirst().equals(AlarmKeypadTab.ID) && tabs.contains("general") && tabs.contains("controls"), "The keypad is the panel's first tab: " + tabs);
        List<String> usesOnly = DeviceTabs.forViewer(AlarmPanel.TYPE, true).stream().map(MqttTab::id).toList();
        check(usesOnly.equals(List.of(AlarmKeypadTab.ID, "controls")), "A player who may only use the panel gets the keypad and the controls: " + usesOnly);
        check(DeviceTabs.forType(art.rehra.mineqtt.device.types.Speaker.TYPE).stream().noneMatch(tab -> tab.id().equals(AlarmKeypadTab.ID)), "No other device has a keypad");
        String entry = "";
        for (char c : "12a34567890".toCharArray()) entry = AlarmKeypadTab.typed(entry, c);
        check(entry.equals("12345678"), "Digits only, at most eight: " + entry);
        check(AlarmKeypadTab.masked("1234").equals("••••"), "The entry shows as dots");
        DeviceView.Grants user = new DeviceView.Grants(true, false, false, false);
        DeviceView.Grants owner = new DeviceView.Grants(true, true, true, true);
        JsonObject disarmed = json("{\"state\":\"disarmed\",\"code_set\":true,\"lockout\":0}");
        JsonObject armed = json("{\"state\":\"armed_away\",\"code_set\":true,\"lockout\":0}");
        JsonObject locked = json("{\"state\":\"armed_away\",\"code_set\":true,\"lockout\":20}");
        check(AlarmKeypadTab.Keys.of(user, disarmed, "").equals(new AlarmKeypadTab.Keys(true, false, false, false)), "A user arms a disarmed panel with a code set");
        check(AlarmKeypadTab.Keys.of(user, json("{\"state\":\"disarmed\",\"code_set\":false}"), "").arm() == false, "Without a code it does not arm");
        check(AlarmKeypadTab.Keys.of(user, armed, "12").equals(new AlarmKeypadTab.Keys(false, true, false, false)), "With an entry a user disarms an armed panel");
        check(!AlarmKeypadTab.Keys.of(user, armed, "").disarm() && !AlarmKeypadTab.Keys.of(user, locked, "1234").disarm(), "Not without an entry, nor locked out");
        check(AlarmKeypadTab.Keys.of(owner, disarmed, "1234").equals(new AlarmKeypadTab.Keys(true, false, true, true)), "A configurer sets and removes the code while disarmed");
        check(!AlarmKeypadTab.Keys.of(owner, disarmed, "123").setCode() && !AlarmKeypadTab.Keys.of(owner, armed, "1234").setCode()
                && !AlarmKeypadTab.Keys.of(owner, armed, "").removeCode(), "Not a short code, nor while armed");
    }

    /** Wires: sensors' bools and events reach the triggers; triggered follows into a Speaker's state. */
    private static void wires() {
        Port trigger = AlarmPanel.TYPE.port(Port.Direction.IN, AlarmPanel.TRIGGER_HOME).orElseThrow();
        Port vibration = art.rehra.mineqtt.device.types.VibrationSensor.TYPE.port(Port.Direction.OUT, "vibration").orElseThrow();
        Port attempt = art.rehra.mineqtt.device.types.SmartLock.TYPE.port(Port.Direction.OUT, "attempt").orElseThrow();
        Port door = art.rehra.mineqtt.device.types.VibrationSensor.TYPE.port(Port.Direction.OUT, "door").orElseThrow();
        check(trigger.toggles() && trigger.kind() instanceof PortType.Bool, "A trigger is an edge in-port: " + trigger);
        check(Mapping.FOLLOW.compatible(vibration.kind(), trigger.kind(), trigger.toggles()), "A Vibration Sensor's vibration follows into trigger_home");
        check(Mapping.FOLLOW.compatible(attempt.kind(), trigger.kind(), trigger.toggles()) && Mapping.PULSE_TOGGLE.compatible(attempt.kind(), trigger.kind(), trigger.toggles()),
                "A Smart Lock's attempt reaches trigger_home");
        check(Mapping.FOLLOW.compatible(door.kind(), trigger.kind(), trigger.toggles()), "A Vibration Sensor's door event reaches it");
        check(DefaultWiring.mapping(attempt, trigger) == Mapping.PULSE_TOGGLE && DefaultWiring.mapping(vibration, trigger) == Mapping.FOLLOW,
                "An event pulses it, a bool follows");
        Optional<com.google.gson.JsonElement> rising = Mapping.FOLLOW.apply(new com.google.gson.JsonPrimitive(true), null, vibration.kind(), trigger.kind(), trigger.toggles());
        Optional<com.google.gson.JsonElement> pulse = Mapping.PULSE_TOGGLE.apply(json("{\"event_type\":\"attempt\",\"method\":\"hand\",\"target\":\"door\"}"), null,
                attempt.kind(), trigger.kind(), trigger.toggles());
        check(rising.map(Action::fires).orElse(false) && pulse.map(Action::fires).orElse(false), "Both arrive as values that fire: " + rising + ", " + pulse);
        Port triggered = AlarmPanel.TYPE.port(Port.Direction.OUT, "triggered").orElseThrow();
        Port speaker = art.rehra.mineqtt.device.types.Speaker.TYPE.port(Port.Direction.IN, Action.STATE).orElseThrow();
        check(Mapping.FOLLOW.compatible(triggered.kind(), speaker.kind(), speaker.toggles()), "triggered follows into a Speaker's state");
    }

    private static Alarm ticks(Alarm alarm, Settings settings, int ticks) {
        Alarm current = alarm;
        for (int i = 0; i < ticks; i++) current = current.tick(settings);
        return current;
    }

    private static Optional<AlarmPanel.Request> request(String command, CommandSource source) {
        return AlarmPanel.request(new CommandReader(json(command)), source);
    }

    private static String b64(int bytes) {
        return java.util.Base64.getEncoder().encodeToString(new byte[bytes]);
    }

    private static net.minecraft.resources.Identifier id(String path) {
        return net.minecraft.resources.Identifier.fromNamespaceAndPath("mineqtt_check", path);
    }

    private static void failsToBuild(DeviceType.Builder builder, String expected, String what) {
        try {
            builder.build();
        } catch (IllegalStateException e) {
            check(e.getMessage().contains(expected), what + ": " + e.getMessage());
            return;
        }
        throw new AssertionError(what + " builds");
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}

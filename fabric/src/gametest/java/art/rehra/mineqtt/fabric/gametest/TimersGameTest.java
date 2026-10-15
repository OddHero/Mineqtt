package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.runtime.WorldClock;
import art.rehra.mineqtt.compat.Screens;
import art.rehra.mineqtt.blocks.RgbLedBlock;
import art.rehra.mineqtt.blocks.TimerBlock;
import art.rehra.mineqtt.blocks.entities.TimerBlockEntity;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.timer.core.NextFire;
import art.rehra.mineqtt.device.timer.core.TimerState;
import art.rehra.mineqtt.device.types.Timers;
import art.rehra.mineqtt.flow.core.Mapping;
import art.rehra.mineqtt.items.MineqttItems;
import art.rehra.mineqtt.network.MineqttNetworking.LinkPickerPayload;
import art.rehra.mineqtt.ui.LinkPickerScreen;
import art.rehra.mineqtt.ui.device.AccessTab;
import art.rehra.mineqtt.ui.device.AdvancedTab;
import art.rehra.mineqtt.ui.device.ControlsTab;
import art.rehra.mineqtt.ui.device.CronScheduleTab;
import art.rehra.mineqtt.ui.device.DiagnosticsTab;
import art.rehra.mineqtt.ui.device.GeneralTab;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ComparatorBlock;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The timers as devices (M4 plan D2, D5, D15) from a real client. Each of the four opens the generated device screen,
 * whose Controls tab shows Trigger now, the Enabled toggle with the switch's state row folded into it, its schedule and
 * a status line. A standing overworld clock ({@code gamerule advance_time false}, {@code /time pause}) clears the next
 * fire of the Daylight Timer and the Alarm Clock, and a slowed one ({@code /time rate}) keeps it. The Enabled toggle
 * disarms the Interval Timer; Trigger now pulses the Alarm Clock, which a comparator
 * behind it reads; the Cron Timer refuses an expression that does not parse and takes one that does, and its Schedule tab
 * edits the expression field by field, fills it from a preset and closes Set while a field does not parse. In Link mode the
 * picker offers a timer's {@code fired} into a lamp's {@code state} (toggle) first, and a Wall Switch's {@code press}
 * into a timer's {@code trigger}; the wires then fire and toggle through the message bus. A v1 alarm clock placed with
 * its v1 data becomes a device with its settings, owner and name, and the items of its old slots eject.
 */
public final class TimersGameTest implements FabricClientGameTest {
    private static final int Y = GameTestSession.GROUND_Y + 1;
    private static final BlockPos STAND = new BlockPos(0, Y, 0);
    private static final BlockPos INTERVAL = new BlockPos(3, Y, 0);
    private static final BlockPos DAYLIGHT = new BlockPos(3, Y, 3);
    private static final BlockPos ALARM = new BlockPos(0, Y, 3);
    // Behind the Alarm Clock, reading its power: a comparator's input is the side it faces.
    private static final BlockPos COMPARATOR = ALARM.south();
    private static final BlockPos CRON = new BlockPos(-3, Y, 3);
    private static final BlockPos LAMP = new BlockPos(-3, Y, 0);
    private static final BlockPos SWITCH = new BlockPos(0, Y, -3);
    private static final BlockPos V1_ALARM = new BlockPos(3, Y, -3);
    private static final List<String> TABS = List.of(GeneralTab.ID, ControlsTab.ID, AdvancedTab.ID, AccessTab.ID, DiagnosticsTab.ID);
    private static final int ANSWER_TICKS = 40;
    // Ample for a refused payload to have done something if it had not been refused.
    private static final int DROP_TICKS = 20;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "timers", TimersGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        setblock(session, INTERVAL, "mineqtt:interval_timer");
        setblock(session, DAYLIGHT, "mineqtt:game_time_timer");
        setblock(session, ALARM, "mineqtt:alarm_clock");
        setblock(session, COMPARATOR, "minecraft:comparator[facing=north]");
        setblock(session, CRON, "mineqtt:cron_timer");
        setblock(session, LAMP, "mineqtt:rgb_led_block");
        setblock(session, SWITCH, "mineqtt:light_remote_block[facing=south]");
        for (BlockPos pos : List.of(INTERVAL, DAYLIGHT, ALARM, CRON, LAMP, SWITCH)) {
            session.awaitServer(s -> s.overworld().getBlockEntity(pos) instanceof DeviceHost host && host.deviceLink().attached());
        }
        // Noon: the Daylight Timer and the Alarm Clock fire at 06:00, and the v1 alarm below at midnight, never on their own here.
        session.consoleCommand("time set 6000");

        screens(session);
        standingClock(session);
        enabledToggle(session);
        triggerNow(session);
        cronField(session);
        scheduleTab(session);
        link(session);
        v1Alarm(session);
    }

    /** Each kind's screen: the five generated tabs, its controls and its status line. */
    private static void screens(GameTestSession session) {
        Map<BlockPos, List<String>> labels = Map.of(
                INTERVAL, List.of("Trigger now", "Enabled: ON", "Unit: Ticks"),
                DAYLIGHT, List.of("Trigger now", "Enabled: ON", "Fires at: Sunrise"),
                ALARM, List.of("Trigger now", "Enabled: ON"),
                CRON, List.of("Trigger now", "Enabled: ON"));
        Map<BlockPos, String> fields = Map.of(INTERVAL, "Interval", DAYLIGHT, "Time", ALARM, "Time", CRON, "Cron");
        Map<BlockPos, Predicate<String>> status = Map.of(
                INTERVAL, line -> line.equals("Every 20 ticks"),
                DAYLIGHT, line -> line.startsWith("Next 06:00"),
                ALARM, line -> line.startsWith("Next 06:00"),
                CRON, line -> line.equals("No schedule"));
        for (BlockPos timer : List.of(INTERVAL, DAYLIGHT, ALARM, CRON)) {
            String name = session.onServer(s -> s.overworld().getBlockState(timer).getBlock().getName().getString());
            session.openBlock(timer);
            List<String> tabs = timer.equals(CRON) ? List.of(GeneralTab.ID, ControlsTab.ID, CronScheduleTab.ID, AdvancedTab.ID, AccessTab.ID, DiagnosticsTab.ID) : TABS;
            session.check(session.tabIds().equals(tabs), name + ": the generated tabs " + session.tabIds() + ", the Cron Timer's with its Schedule tab");
            session.check(session.clickTab(ControlsTab.ID), name + ": the Controls tab opens");
            for (String label : labels.get(timer)) {
                session.check(session.awaitClient(mc -> buttonLabels(mc).contains(label), ANSWER_TICKS), name + " shows " + label + " (" + session.onClient(TimersGameTest::buttonLabels) + ")");
            }
            session.check(session.onClient(mc -> buttonLabels(mc).stream().noneMatch(label -> label.startsWith("Switch:"))),
                    name + ": no switch row besides Enabled, the state action is folded into it");
            session.check(session.onClient(mc -> widget(mc, isField(fields.get(timer))).isPresent()), name + " has its " + fields.get(timer) + " field");
            String line = session.screenView().map(DeviceView::statusLine).orElse("");
            session.check(status.get(timer).test(line), name + "'s status line: " + line);
            session.screenshot("timers_" + session.onServer(s -> ((TimerBlockEntity) s.overworld().getBlockEntity(timer)).kind().id()));
            session.closeScreen();
        }
    }

    /**
     * The runtime's clock watch (D2) in a real server: with {@code gamerule advance_time false} or {@code /time pause} the
     * Daylight Timer and the Alarm Clock publish no next fire within a few ticks and none at the next resync, and it
     * comes back when the clock runs again. A clock slowed by {@code /time rate 0.5}, which stands still on every other
     * tick, keeps running: the next fire stays, twice as far away.
     */
    private static void standingClock(GameTestSession session) {
        List<BlockPos> dayTimers = List.of(DAYLIGHT, ALARM);
        // A test world starts with the day cycle off; this step runs it and leaves it off again.
        session.check(!session.onServer(WorldClock::advances)
                        && session.await(() -> dayTimers.stream().allMatch(timer -> nextFire(session, timer).isEmpty()), ANSWER_TICKS),
                "the test world's clock stands, so the Daylight Timer and the Alarm Clock have no next fire");
        session.consoleCommand("gamerule advance_time true");
        session.check(session.await(() -> dayTimers.stream().allMatch(timer -> !nextFire(session, timer).isEmpty()), ANSWER_TICKS),
                "with the clock running, the Daylight Timer and the Alarm Clock have a next fire");
        OffsetDateTime atRateOne = NextFire.parse(nextFire(session, DAYLIGHT)).orElseThrow();

        session.consoleCommand("gamerule advance_time false");
        session.check(session.await(() -> dayTimers.stream().allMatch(timer -> nextFire(session, timer).isEmpty()), DROP_TICKS),
                "gamerule advance_time false: their next fire goes within a few ticks (" + nextFire(session, DAYLIGHT) + ", " + nextFire(session, ALARM) + ")");
        session.consoleCommand("gamerule advance_time true");
        session.check(session.await(() -> dayTimers.stream().allMatch(timer -> !nextFire(session, timer).isEmpty()), DROP_TICKS),
                "gamerule advance_time true: it comes back");

        session.consoleCommand("time of minecraft:overworld pause");
        session.check(session.await(() -> dayTimers.stream().allMatch(timer -> nextFire(session, timer).isEmpty()), DROP_TICKS),
                "/time pause: their next fire goes (" + nextFire(session, DAYLIGHT) + ")");
        session.consoleCommand("time of minecraft:overworld resume");
        session.check(session.await(() -> dayTimers.stream().allMatch(timer -> !nextFire(session, timer).isEmpty()), DROP_TICKS),
                "/time resume: it comes back");

        session.consoleCommand("time of minecraft:overworld rate 0.5");
        session.check(session.await(() -> NextFire.parse(nextFire(session, DAYLIGHT))
                        .map(next -> Duration.between(atRateOne, next).compareTo(Duration.ofMinutes(10)) > 0).orElse(false), DROP_TICKS),
                "/time rate 0.5: the Daylight Timer's next fire moves out to twice as far (" + atRateOne + " -> " + nextFire(session, DAYLIGHT) + ")");
        boolean kept = true;
        for (int sample = 0; sample < 20; sample++) {
            session.context().waitTicks(2);
            kept &= dayTimers.stream().noneMatch(timer -> nextFire(session, timer).isEmpty());
        }
        session.check(kept, "and with the clock standing still on every other tick it stays: the clock still runs");
        session.consoleCommand("time of minecraft:overworld rate 1");
        session.check(session.await(() -> NextFire.parse(nextFire(session, DAYLIGHT))
                        .map(next -> Duration.between(atRateOne, next).abs().compareTo(Duration.ofMinutes(2)) < 0).orElse(false), DROP_TICKS),
                "/time rate 1: the next fire is back where it was (" + nextFire(session, DAYLIGHT) + ")");
        session.consoleCommand("gamerule advance_time false");
        session.check(session.await(() -> dayTimers.stream().allMatch(timer -> nextFire(session, timer).isEmpty()), DROP_TICKS),
                "and with the day cycle off again their next fire goes");
    }

    /** The Enabled toggle disarms the Interval Timer: it stops firing, its next fire goes and its status line says so. */
    private static void enabledToggle(GameTestSession session) {
        session.openBlock(INTERVAL);
        session.check(session.clickTab(ControlsTab.ID), "the Interval Timer's Controls tab opens");
        session.clickWidget(isButton("Enabled:"), "the Enabled toggle");
        session.check(session.await(() -> !settings(session, INTERVAL).enabled(), ANSWER_TICKS)
                        && session.awaitClient(mc -> buttonLabels(mc).contains("Enabled: OFF"), ANSWER_TICKS),
                "the toggle disables the timer, and shows it");
        session.check(session.await(() -> session.screenView().map(view -> view.statusLine().equals("Disabled")).orElse(false), ANSWER_TICKS)
                && "".equals(state(session, INTERVAL).get("next_fire").getAsString()), "its status line says Disabled and its next fire is gone");
        long counted = counters(session, INTERVAL).lastTriggerTime();
        session.context().waitTicks(DROP_TICKS * 2);
        session.check(counters(session, INTERVAL).lastTriggerTime() == counted, "a disabled interval fires no more (every 20 ticks before)");
        session.screenshot("timers_disabled");
        session.clickWidget(isButton("Enabled:"), "the Enabled toggle");
        session.check(session.await(() -> settings(session, INTERVAL).enabled(), ANSWER_TICKS), "and enables it again");
        session.closeScreen();
    }

    /** Trigger now pulses the Alarm Clock for 30 ticks, which the comparator behind it reads; it fires as manual. */
    private static void triggerNow(GameTestSession session) {
        session.openBlock(ALARM);
        session.check(session.clickTab(ControlsTab.ID), "the Alarm Clock's Controls tab opens");
        session.check(!comparatorPowered(session), "the comparator is off before");
        session.clickWidget(isButton("Trigger now"), "Trigger now");
        session.check(session.await(() -> comparatorPowered(session), ANSWER_TICKS), "Trigger now pulses the Alarm Clock: the comparator behind it turns on");
        session.check(session.await(() -> !comparatorPowered(session), ANSWER_TICKS)
                && session.onServer(s -> s.overworld().getBlockState(ALARM).getValue(TimerBlock.POWER)) == 0, "and off when the 30-tick pulse ends");
        session.closeScreen();
    }

    /** The Cron Timer refuses an expression that does not parse, with the reason, and takes one that does. */
    private static void cronField(GameTestSession session) {
        session.openBlock(CRON);
        session.check(session.clickTab(ControlsTab.ID), "the Cron Timer's Controls tab opens");
        int mark = ChatLog.mark();
        session.typeInto(isField("Cron"), "the cron field", "every minute");
        session.clickWidget(isButton("Set"), "Set");
        session.check(session.awaitChat(mark, line -> !line.actionBar() && line.text().startsWith("Not changed:") && line.text().contains("7 fields"), ANSWER_TICKS).isPresent(),
                "an expression that does not parse is refused in chat (messages: " + ChatLog.since(mark) + ")");
        session.check(settings(session, CRON).cron().isEmpty(), "and the timer keeps no schedule");
        session.typeInto(isField("Cron"), "the cron field", "0 0 0 12 * * *");
        session.clickWidget(isButton("Set"), "Set");
        session.check(session.await(() -> settings(session, CRON).cron().equals("0 0 0 12 * * *"), ANSWER_TICKS), "a valid expression is taken");
        session.check(session.await(() -> session.screenView().map(view -> view.statusLine().startsWith("Next ")).orElse(false), ANSWER_TICKS)
                        && !state(session, CRON).get("next_fire").getAsString().isEmpty(),
                "its status line and its state name the next fire (" + session.screenView().map(DeviceView::statusLine).orElse("") + ")");
        session.screenshot("timers_cron_set");
        session.closeScreen();
    }

    /**
     * The Cron Timer's Schedule tab (D5): a box per field shows the timer's expression; a preset fills them; a box whose
     * token does not parse closes Set; Set sends the expression, which the timer takes.
     */
    private static void scheduleTab(GameTestSession session) {
        session.openBlock(CRON);
        session.check(session.clickTab(CronScheduleTab.ID), "the Cron Timer's Schedule tab opens");
        session.check(session.awaitClient(mc -> fieldValue(mc, "Hour").equals("12") && fieldValue(mc, "Second").equals("0"), ANSWER_TICKS),
                "its boxes show the timer's expression, 0 0 0 12 * * *, a field each");
        session.clickWidget(isButton("Presets"), "the preset button");
        session.check(session.awaitClient(mc -> buttonLabels(mc).contains("Preset: Every 5 seconds") && fieldValue(mc, "Second").equals("*/5")
                && fieldValue(mc, "Hour").equals("*"), ANSWER_TICKS), "the first preset fills the boxes: every 5 seconds");
        session.typeInto(isField("Hour"), "the hour box", "25");
        session.check(session.awaitClient(mc -> !active(mc, isButton("Set")), ANSWER_TICKS), "an hour of 25 does not parse: Set closes");
        session.screenshot("timers_schedule_invalid");
        session.clickWidget(isButton("Preset:"), "the preset button");
        session.check(session.awaitClient(mc -> buttonLabels(mc).contains("Preset: Every minute") && active(mc, isButton("Set")), ANSWER_TICKS),
                "the next preset, every minute, opens Set again");
        session.screenshot("timers_schedule");
        session.clickWidget(isButton("Set"), "Set");
        session.check(session.await(() -> settings(session, CRON).cron().equals("0 0 * * * * *"), ANSWER_TICKS), "Set sends the expression, which the timer takes");
        session.check(session.awaitClient(mc -> !active(mc, isButton("Set")), ANSWER_TICKS), "and Set closes once the boxes show the timer's expression");
        session.closeScreen();
    }

    /**
     * Link mode: a timer into a lamp is fired into state, toggling, first; a Wall Switch into a timer is press into
     * trigger first. Linked, the switch fires the Alarm Clock and the Daylight Timer's fire toggles the lamp.
     */
    private static void link(GameTestSession session) {
        session.hold(MineqttItems.Cyberdeck.get());
        int mark = ChatLog.mark();
        session.aimAtSky();
        session.sneakUse();
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals("Configurator mode: Link"), ANSWER_TICKS).isPresent(),
                "sneak + right-click into the air steps the Configurator to Link mode (messages: " + ChatLog.since(mark) + ")");

        LinkPickerPayload.Choice fired = pick(session, DAYLIGHT, LAMP);
        session.check(fired.from().equals(Timers.FIRED.type()) && fired.to().equals("state") && fired.isDefault() && fired.mappings().getFirst() == Mapping.PULSE_TOGGLE,
                "a timer into a lamp: fired into state, toggling, first (" + fired + ")");
        session.clickWidget(isButton("Link"), "the picker's Link button");
        session.check(session.awaitClient(mc -> Screens.current() == null, ANSWER_TICKS), "the picker closes");

        LinkPickerPayload.Choice press = pick(session, SWITCH, ALARM);
        session.check(press.from().equals("press") && press.to().equals(Timers.TRIGGER.key()) && press.isDefault() && press.mappings().getFirst() == Mapping.FOLLOW,
                "a Wall Switch into a timer: press into trigger first, not into its state (" + press + ")");
        session.clickWidget(isButton("Link"), "the picker's Link button");
        session.check(session.awaitClient(mc -> Screens.current() == null, ANSWER_TICKS), "the picker closes");
        session.hold(Items.AIR);

        boolean lit = lampLevel(session) > 0;
        session.consoleCommand("mineqtt devices action " + slug(session, DAYLIGHT) + " trigger");
        session.check(session.await(() -> (lampLevel(session) > 0) != lit, ANSWER_TICKS), "the Daylight Timer's fire toggles the lamp through the wire");
        session.consoleCommand("mineqtt devices action " + slug(session, SWITCH) + " press_left");
        session.check(session.await(() -> session.onServer(s -> s.overworld().getBlockState(ALARM).getValue(TimerBlock.POWER)) == 15, ANSWER_TICKS),
                "a Wall Switch press fires the Alarm Clock: Trigger now through the wire");
        session.screenshot("timers_linked");
    }

    /**
     * A v1 alarm clock with its v1 data becomes a device: its time, its day lock, its owner by name, its anvil name, no
     * Raw Link; the items of its old slots eject from its Diagnostics tab.
     */
    private static void v1Alarm(GameTestSession session) {
        String items = "Items:[{Slot:0b,id:\"minecraft:paper\",count:1,components:{\"minecraft:custom_name\":\"kitchen/alarm\"}},"
                + "{Slot:1b,id:\"minecraft:stone\",count:3}]";
        setblock(session, V1_ALARM, "mineqtt:alarm_clock{alarmTime:18000,lastAlarmDay:3L,lastTriggerTime:0L,ownerName:\"" + session.playerName()
                + "\",privateMode:0b,topic:\"\",lastTabId:\"alarm_clock\",CustomName:\"Old Alarm\"," + items + "}");
        session.awaitServer(s -> s.overworld().getBlockEntity(V1_ALARM) instanceof DeviceHost host && host.deviceLink().attached() && host.deviceLink().owner() != null);
        Timers.Settings alarm = settings(session, V1_ALARM);
        TimerState counted = counters(session, V1_ALARM);
        session.check(alarm.enabled() && alarm.time().equals("00:00") && counted.lastTriggerDay() == 3,
                "the v1 alarm keeps its time and its day lock: " + alarm + " " + counted);
        session.check(session.playerId().equals(session.device(V1_ALARM, link -> link.owner())) && "Old Alarm".equals(session.device(V1_ALARM, link -> link.name()))
                && session.device(V1_ALARM, link -> link.raw()) == null && Integer.valueOf(2).equals(session.device(V1_ALARM, link -> link.legacyItems().size())),
                "its owner by name, its anvil name, no Raw Link from its slots, and the two slot items as legacy items");

        session.openBlock(V1_ALARM);
        session.check(session.clickTab(DiagnosticsTab.ID), "its Diagnostics tab opens");
        session.check(session.awaitClient(mc -> buttonLabels(mc).contains("Eject 4"), ANSWER_TICKS), "it offers the four items of its old slots (" + session.onClient(TimersGameTest::buttonLabels) + ")");
        int paper = session.onServer(s -> session.player(s).getInventory().countItem(Items.PAPER));
        int stone = session.onServer(s -> session.player(s).getInventory().countItem(Items.STONE));
        session.clickWidget(isButton("Eject"), "Eject");
        session.check(session.await(() -> session.onServer(s -> session.player(s).getInventory().countItem(Items.PAPER)) == paper + 1
                        && session.onServer(s -> session.player(s).getInventory().countItem(Items.STONE)) == stone + 3, ANSWER_TICKS)
                        && Boolean.TRUE.equals(session.device(V1_ALARM, link -> link.legacyItems().isEmpty())),
                "Eject puts the paper and the stone into the inventory, and the device keeps none");
        session.screenshot("timers_v1_ejected");
        session.closeScreen();
    }

    // Link mode: a click on the source, then on the target, opens the picker; returns its first choice.
    private static LinkPickerPayload.Choice pick(GameTestSession session, BlockPos source, BlockPos target) {
        String sourceName = session.device(source, link -> link.name());
        int mark = ChatLog.mark();
        session.aim(source);
        session.pressUse();
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().startsWith("Linking from " + sourceName), ANSWER_TICKS).isPresent(),
                "a click on " + sourceName + " picks it as the source");
        session.aim(target);
        session.pressUse();
        session.check(session.awaitClient(mc -> Screens.current() instanceof LinkPickerScreen, ANSWER_TICKS), "a click on the target opens the port picker");
        return session.onClient(mc -> ((LinkPickerScreen) Screens.current()).picker()).choices().getFirst();
    }

    private static Timers.Settings settings(GameTestSession session, BlockPos pos) {
        return session.onServer(s -> ((TimerBlockEntity) s.overworld().getBlockEntity(pos)).settings());
    }

    private static TimerState counters(GameTestSession session, BlockPos pos) {
        return session.onServer(s -> ((TimerBlockEntity) s.overworld().getBlockEntity(pos)).counters());
    }

    private static JsonObject state(GameTestSession session, BlockPos pos) {
        UUID id = session.device(pos, link -> link.id());
        return session.onRuntime(runtime -> {
            DeviceRecord record = runtime.registry().record(id);
            return record == null || record.lastState() == null ? new JsonObject() : record.lastState().deepCopy();
        });
    }

    private static String nextFire(GameTestSession session, BlockPos pos) {
        JsonObject state = state(session, pos);
        return state.has("next_fire") ? state.get("next_fire").getAsString() : "";
    }

    private static String slug(GameTestSession session, BlockPos pos) {
        return session.device(pos, link -> link.slug());
    }

    private static boolean comparatorPowered(GameTestSession session) {
        return session.onServer(s -> s.overworld().getBlockState(COMPARATOR).getValue(ComparatorBlock.POWERED));
    }

    private static int lampLevel(GameTestSession session) {
        return session.onServer(s -> s.overworld().getBlockState(LAMP).getValue(RgbLedBlock.LIGHT_LEVEL));
    }

    private static List<String> buttonLabels(Minecraft mc) {
        return Screens.current() == null ? List.of() : Screens.current().children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.visible).map(button -> button.getMessage().getString()).toList();
    }

    private static Optional<AbstractWidget> widget(Minecraft mc, Predicate<AbstractWidget> which) {
        return Screens.current() == null ? Optional.empty() : Screens.current().children().stream().filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast).filter(widget -> widget.visible && which.test(widget)).findFirst();
    }

    private static String fieldValue(Minecraft mc, String name) {
        return widget(mc, isField(name)).map(field -> ((EditBox) field).getValue()).orElse("");
    }

    private static boolean active(Minecraft mc, Predicate<AbstractWidget> which) {
        return widget(mc, which).map(widget -> widget.active).orElse(false);
    }

    private static Predicate<AbstractWidget> isButton(String labelStart) {
        return widget -> widget instanceof Button && widget.getMessage().getString().startsWith(labelStart);
    }

    private static Predicate<AbstractWidget> isField(String name) {
        return widget -> widget instanceof EditBox && widget.getMessage().getString().equals(name);
    }

    /** Sets a block as the console, and waits until the client has it too. */
    private static void setblock(GameTestSession session, BlockPos pos, String block) {
        session.consoleCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block);
        session.context().waitFor(mc -> !mc.level.getBlockState(pos).isAir());
    }
}

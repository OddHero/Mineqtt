package art.rehra.mineqtt.device.timer;

import art.rehra.mineqtt.blocks.entities.TimerBlockEntity;
import art.rehra.mineqtt.device.Action;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.LinkAdoptionCheck;
import art.rehra.mineqtt.device.Port;
import art.rehra.mineqtt.device.DeviceTraffic;
import art.rehra.mineqtt.device.RawLinkSpec;
import art.rehra.mineqtt.device.timer.core.ClockWatch;
import art.rehra.mineqtt.device.timer.core.CronTimerSchedule;
import art.rehra.mineqtt.device.timer.core.DayTimeSchedule;
import art.rehra.mineqtt.device.timer.core.IntervalSchedule;
import art.rehra.mineqtt.device.timer.core.NextFire;
import art.rehra.mineqtt.device.timer.core.TimerKind;
import art.rehra.mineqtt.device.timer.core.TimerNow;
import art.rehra.mineqtt.device.timer.core.TimerSchedule;
import art.rehra.mineqtt.device.timer.core.TimerState;
import art.rehra.mineqtt.device.timer.core.TimerStatus;
import art.rehra.mineqtt.device.timer.core.TimerStep;
import art.rehra.mineqtt.device.types.Timers;
import art.rehra.mineqtt.device.types.Timers.Settings;
import art.rehra.mineqtt.flow.core.MessageBus;
import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.ha.HaEntity;
import art.rehra.mineqtt.legacy.LegacyNbtV1;
import art.rehra.mineqtt.util.CronSchedule;
import art.rehra.mineqtt.util.GameClock;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The timers (M4 plan D2), run by Gradle's verifyTimers task (bootstrapped for the declarations): the pure schedules
 * tick by tick, the pulse and the manual trigger, the next fire and its estimate, the status line, and the four device
 * types as Home Assistant and the flows see them.
 */
public final class TimersCheck {
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    // A Sunday in October, 12:00:00 in Berlin (CEST, +02:00).
    private static final Instant NOON = ZonedDateTime.of(2026, 10, 11, 12, 0, 0, 0, ZONE).toInstant();
    private static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
    private static HolderLookup.Provider REGISTRIES;

    static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        kinds();
        interval();
        dayTime();
        pulses();
        cron();
        cronSearch();
        estimates();
        clockStopped();
        nextFireText();
        statusLines();
        declarations();
        v1Data();
        publishing();
        System.out.println("Timer checks passed: " + TimerKind.values().length + " kinds.");
    }

    private static void kinds() {
        check(TimerKind.INTERVAL.pulseTicks() == 1 && TimerKind.CRON.pulseTicks() == 1 && TimerKind.GAME_TIME.pulseTicks() == 30
                && TimerKind.ALARM.pulseTicks() == 30, "v1's pulses: 1 tick for the interval and cron timers, 30 for the day timers");
        for (TimerKind kind : TimerKind.values()) {
            check(TimerKind.byId(kind.id()).equals(Optional.of(kind)) && Timers.type(kind).id().getPath().equals(kind.id()),
                    kind + ": block, block entity and device type share the id path " + kind.id());
        }
        check(TimerKind.byId("timer").isEmpty(), "No other id is a timer");
    }

    /** Interval: counted ticks, the unit, the off-tick of a pulse uncounted, restarts. */
    private static void interval() {
        check(Timers.Unit.TICKS.ticks(7) == 7 && Timers.Unit.SECONDS.ticks(5) == 100 && Timers.Unit.MINUTES.ticks(2) == 2_400
                && Timers.Unit.HOURS.ticks(1) == 72_000 && Timers.Unit.HOURS.ticks(Timers.MAX_INTERVAL) == 72_000_000_000L, "Units in ticks, without overflow");
        check(Timers.Unit.ofV1(0) == Timers.Unit.TICKS && Timers.Unit.ofV1(3) == Timers.Unit.HOURS && Timers.Unit.ofV1(7) == Timers.Unit.TICKS,
                "v1's unit index, ticks for anything else");
        IntervalSchedule five = new IntervalSchedule(5);
        TimerState state = TimerState.NEW;
        List<Long> fires = run(TimerKind.INTERVAL, five, true, state, 0, 30);
        // Fires on the 5th counted tick; the next tick only ends the 1-tick pulse, so the period is 6 ticks, as in v1.
        check(fires.equals(List.of(4L, 10L, 16L, 22L, 28L)), "A 5-tick interval fires every 6 game ticks, the pulse's off tick uncounted: " + fires);
        List<Long> everyOther = run(TimerKind.INTERVAL, new IntervalSchedule(1), true, state, 0, 10);
        check(everyOther.equals(List.of(0L, 2L, 4L, 6L, 8L)), "A 1-tick interval fires every other tick (10 Hz at most): " + everyOther);
        check(run(TimerKind.INTERVAL, five, false, state, 0, 30).isEmpty(), "A disabled interval neither counts nor fires");

        TimerState counted = new TimerState(3, -1, -1, 0);
        check(IntervalSchedule.restarted(counted).tickCounter() == 0 && five.armed(now(0, 0), counted).tickCounter() == 0,
                "A settings change and enabling restart the count");
        check(five.remainingTicks(counted) == 2 && five.remainingTicks(TimerState.NEW) == 5, "The ticks left until the next fire");
        expectFailure(() -> new IntervalSchedule(0), "An interval of no ticks");
    }

    /** The Daylight Timer and the Alarm Clock: once per day within +-20 ticks, the sunrise boundary, /time set, enabling. */
    private static void dayTime() {
        DayTimeSchedule sunset = new DayTimeSchedule(GameClock.Moment.SUNSET.ticks());
        long day3 = 3L * GameClock.TICKS_PER_DAY;
        check(!sunset.due(now(0, day3 + 12_000 - 21), TimerState.NEW) && sunset.due(now(0, day3 + 12_000 - 20), TimerState.NEW)
                && sunset.due(now(0, day3 + 12_000 + 20), TimerState.NEW) && !sunset.due(now(0, day3 + 12_000 + 21), TimerState.NEW),
                "The window is 20 ticks either side of the moment");
        List<Long> fires = runClock(TimerKind.GAME_TIME, sunset, true, TimerState.NEW, day3 + 11_900, day3 + 12_100);
        check(fires.equals(List.of(day3 + 11_980)), "Fires once at the window's opening tick, not again within it: " + fires);
        TimerState fired = sunset.ticked(now(100, day3 + 11_980), TimerState.NEW, true);
        check(fired.lastTriggerDay() == 3 && fired.lastTriggerTime() == 100, "The fire marks day 3 and starts the pulse at the game time");
        // /time set takes the clock back over the moment on the same day: it does not fire again.
        check(!sunset.due(now(500, day3 + 11_995), fired), "Set back over the moment the same day, it stays quiet");
        check(sunset.due(now(500, day3 + GameClock.TICKS_PER_DAY + 12_000), fired), "The next day it fires again");

        // Sunrise is tick 0, where the day number changes: one fire for the moment, whichever side of the boundary.
        DayTimeSchedule sunrise = new DayTimeSchedule(GameClock.Moment.SUNRISE.ticks());
        long boundary = 5L * GameClock.TICKS_PER_DAY;
        List<Long> dawn = runClock(TimerKind.GAME_TIME, sunrise, true, TimerState.NEW, boundary - 100, boundary + 100);
        check(dawn.equals(List.of(boundary - 20)), "Sunrise fires once, 20 ticks before the day turns, and not again after it: " + dawn);
        check(sunrise.momentDay(boundary - 20) == 5 && sunrise.momentDay(boundary + 20) == 5 && sunrise.momentDay(boundary + 12_000) == 6,
                "The moment's day is the day it turns to, from either side");
        // A v1 timer saved after the second fire of its double sunrise holds the new day: it does not fire again.
        check(!sunrise.due(now(0, boundary + 10), new TimerState(0, 5, -1, 0)), "A v1 lock of the new day holds");
        // Midnight is tick 18000, no day boundary: the same rule.
        DayTimeSchedule midnight = new DayTimeSchedule(GameClock.Moment.MIDNIGHT.ticks());
        check(runClock(TimerKind.ALARM, midnight, true, TimerState.NEW, boundary + 17_900, boundary + 18_100).equals(List.of(boundary + 17_980)),
                "Midnight fires once");

        // Enabling inside the window marks the moment handled, so it never fires at once; outside it changes nothing.
        TimerState armedInside = sunset.armed(now(0, day3 + 12_005), TimerState.NEW);
        check(armedInside.lastTriggerDay() == 3 && !sunset.due(now(0, day3 + 12_006), armedInside), "Enabled within the window, it does not fire");
        check(sunset.armed(now(0, day3 + 11_000), TimerState.NEW).equals(TimerState.NEW), "Enabled before the window, it fires when it opens");
        check(run(TimerKind.GAME_TIME, sunset, false, TimerState.NEW, 0, 5).isEmpty(), "A disabled day timer does not fire");

        check(sunset.remainingTicks(day3 + 11_000, TimerState.NEW) == 1_000 && sunset.remainingTicks(day3 + 13_000, fired) == 23_000,
                "The ticks to the coming moment");
        check(sunset.remainingTicks(day3 + 11_990, fired) == 10 + GameClock.TICKS_PER_DAY, "Today's moment handled, the next is tomorrow's");
        expectFailure(() -> new DayTimeSchedule(24_000), "A time of day beyond a day");
    }

    /** A pulse lasts its kind's ticks, drops a fire, and a manual trigger fires also while disabled. */
    private static void pulses() {
        TimerState pulsing = TimerState.NEW.withLastTriggerTime(1_000);
        DayTimeSchedule noon = new DayTimeSchedule(GameClock.Moment.NOON.ticks());
        // Due by the clock, but the pulse is on: it only waits for it to end.
        TimerStep waiting = TimerStep.tick(TimerKind.ALARM, noon, true, true, now(1_029, 6_000), pulsing);
        check(waiting.action() == TimerStep.Action.NONE && waiting.state().equals(pulsing), "A fire while the pulse is on is dropped, nothing changes");
        check(TimerStep.tick(TimerKind.ALARM, noon, true, true, now(1_030, 6_000), pulsing).action() == TimerStep.Action.PULSE_OFF,
                "The day timers' pulse ends after 30 ticks");
        check(TimerStep.tick(TimerKind.CRON, CronTimerSchedule.of(""), true, true, now(1_001, 0), pulsing).action() == TimerStep.Action.PULSE_OFF
                && TimerStep.tick(TimerKind.INTERVAL, new IntervalSchedule(5), false, true, now(1_001, 0), pulsing).action() == TimerStep.Action.PULSE_OFF,
                "The interval and cron pulses end after one tick, also while disabled");
        check(TimerStep.pulseOver(TimerKind.ALARM, now(10, 0), pulsing), "A pulse that starts ahead of the game time (another world's data) ends at once");

        TimerState counted = new TimerState(3, 7, 42, 0);
        Optional<TimerState> manual = TimerStep.trigger(false, now(2_000, 0), counted);
        check(manual.equals(Optional.of(new TimerState(3, 7, 42, 2_000))), "Trigger now starts a pulse and leaves the count and the day's lock: " + manual);
        check(TimerStep.trigger(true, now(2_000, 0), counted).isEmpty(), "A trigger while the pulse is on is dropped, as in v1");
    }

    /** Cron: the wall clock and the tick within the second, the next match, the 400-day limit, invalid expressions kept. */
    private static void cron() {
        CronTimerSchedule everyFive = CronTimerSchedule.of(" 0 */5 * * * * * ");
        check(everyFive.valid() && everyFive.expression().equals("0 */5 * * * * *"), "An expression parses, trimmed");
        Instant second10 = NOON.plusSeconds(10);
        check(everyFive.due(new TimerNow(40, 0, second10, ZONE, 20, false), TimerState.NEW),
                "Fires at second 10, tick 0 of the second");
        check(!everyFive.due(new TimerNow(41, 0, second10, ZONE, 20, false), TimerState.NEW),
                "Not at tick 1 of that second");
        check(!everyFive.due(new TimerNow(40, 0, NOON.plusSeconds(11), ZONE, 20, false), TimerState.NEW),
                "Not at second 11");
        check(!everyFive.due(new TimerNow(40, 0, second10, ZONE, 20, false), new TimerState(0, -1, 40, 0)),
                "Never twice in one game tick");
        check(everyFive.next(new TimerNow(40, 0, second10, ZONE, 20, false), TimerState.NEW)
                .equals(Optional.of(ZonedDateTime.of(2026, 10, 11, 12, 0, 15, 0, ZONE))), "The next match after now, to the second");
        // The cron matches in the server's zone: 09:00 in Berlin is 07:00 UTC.
        CronTimerSchedule nine = CronTimerSchedule.of("0 0 0 9 * * *");
        Instant berlinNine = ZonedDateTime.of(2026, 10, 12, 9, 0, 0, 0, ZONE).toInstant();
        check(nine.due(new TimerNow(0, 0, berlinNine, ZONE, 20, false), TimerState.NEW)
                && !nine.due(new TimerNow(0, 0, berlinNine, ZoneId.of("UTC"), 20, false), TimerState.NEW),
                "Read in the server's zone");
        // Steps: */5 is every fifth second from 0 (v1 took every second), 5/20 every twentieth from 5, 10-30/10 within the range.
        check(seconds(everyFive).equals(List.of(0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55)), "*/5 fires every fifth second: " + seconds(everyFive));
        check(seconds(CronTimerSchedule.of("0 5/20 * * * * *")).equals(List.of(5, 25, 45)), "5/20 fires every twentieth second from 5");
        check(seconds(CronTimerSchedule.of("0 10-30/10 * * * * *")).equals(List.of(10, 20, 30)), "10-30/10 fires every tenth second within the range");
        check(CronTimerSchedule.of("0 0 0 0 31 2 *").next(new TimerNow(0, 0, NOON, ZONE, 20, false), TimerState.NEW).isEmpty(),
                "No match within 400 days (31 February) gives no next fire");
        CronTimerSchedule broken = CronTimerSchedule.of("0 0 0 25 * * *");
        check(!broken.valid() && broken.expression().equals("0 0 0 25 * * *") && !broken.due(now(0, 0), TimerState.NEW)
                && broken.next(now(0, 0), TimerState.NEW).isEmpty(), "An invalid expression is kept, never fires and has no next fire");
        check(!CronTimerSchedule.of("").valid() && !CronTimerSchedule.of("").due(now(0, 0), TimerState.NEW), "An empty expression has no schedule");
        check(CronTimerSchedule.problem("").isEmpty() && CronTimerSchedule.problem("0 */5 * * * * *").isEmpty() && CronTimerSchedule.problem("* * * *").isPresent()
                && CronTimerSchedule.problem("0 0 0 25 * * *").isPresent() && CronTimerSchedule.problem("0 " + "1,".repeat(32) + "1 * * * * *").isPresent(),
                "A player's expression: empty or one that parses, at most 64 characters");
        String longV1 = "0 0 0 " + "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23" + " * * *";
        check(longV1.length() > CronTimerSchedule.MAX_LENGTH && CronTimerSchedule.of(longV1).valid(), "A longer saved v1 expression still fires when it parses");
        check(CronTimerSchedule.of("0 0 0 9 * * *").armed(now(0, 0), TimerState.NEW).equals(TimerState.NEW), "Enabling changes nothing of a cron timer");
    }

    /**
     * The next-match search: the same answer as a second-by-second walk, the date checked before the time of day, and a
     * rare or never matching expression answered at once rather than by walking every second of 400 days.
     */
    private static void cronSearch() {
        LocalDateTime noon = LocalDateTime.of(2026, 10, 11, 12, 0, 0);
        List<LocalDateTime> starts = List.of(noon, noon.withNano(500_000_000), LocalDateTime.of(2026, 10, 11, 23, 59, 59),
                LocalDateTime.of(2026, 10, 25, 1, 30, 0), LocalDateTime.of(2026, 12, 31, 23, 59, 30), LocalDateTime.of(2027, 2, 28, 23, 0, 0));
        List<String> expressions = List.of("0 */5 * * * * *", "0 0 30 7 * * 1-5", "0 59 59 23 * * *", "0 30 0 0 * * *", "0 0,30 */2 1-3 * * *",
                "0 15 45 22 11-14 10 *", "0 5/20 10-20/5 * * * 0,6", "0 0 0 0 1 * *", "0 59 59 * * * *", "0 0 0 12 * * *");
        for (String expression : expressions) {
            CronSchedule schedule = CronSchedule.parse(expression);
            for (LocalDateTime start : starts) {
                LocalDateTime expected = walk(schedule, start, Duration.ofDays(3));
                LocalDateTime found = CronSchedule.findNextTrigger(schedule, start, ZONE);
                check(expected != null ? expected.equals(found) : found == null || found.isAfter(start.plusDays(3)),
                        "'" + expression + "' from " + start + ": " + found + ", a walk gives " + expected);
            }
        }
        CronSchedule newYear = CronSchedule.parse("0 59 59 23 31 12 *");
        CronSchedule never = CronSchedule.parse("0 59 59 23 31 2 *");
        check(LocalDateTime.of(2026, 12, 31, 23, 59, 59).equals(CronSchedule.findNextTrigger(newYear, noon, ZONE)), "A yearly expression finds its day");
        check(CronSchedule.findNextTrigger(never, noon, ZONE) == null, "31 February never comes");
        check(LocalDateTime.of(2027, 10, 15, 10, 0, 0).equals(CronSchedule.findNextTrigger(CronSchedule.parse("0 0 0 10 15 10 5"), noon, ZONE)),
                "Day of month and weekday both match (a Friday the 15th of October)");
        check(CronSchedule.findNextTrigger(CronSchedule.parse("0 0 0 10 29 2 *"), noon, ZONE) == null
                && CronSchedule.findNextTrigger(CronSchedule.parse("0 0 0 10 29 2 *"), LocalDateTime.of(2027, 3, 1, 0, 0), ZONE)
                .equals(LocalDateTime.of(2028, 2, 29, 10, 0, 0)), "A day more than 400 days away is none; within 400 days it is found");
        // A walk over every second of 400 days took about 0.3 s a call; a day at a time takes microseconds.
        long began = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            CronSchedule.findNextTrigger(never, noon.plusMinutes(i), ZONE);
            CronSchedule.findNextTrigger(newYear, noon.plusMinutes(i), ZONE);
        }
        Duration took = Duration.ofNanos(System.nanoTime() - began);
        check(took.compareTo(Duration.ofSeconds(1)) < 0, "400 searches of rare expressions take well under a second: " + took);
    }

    // The first second after start that the expression matches, walked second by second up to a window; null past it.
    private static LocalDateTime walk(CronSchedule schedule, LocalDateTime start, Duration window) {
        LocalDateTime limit = start.plus(window);
        for (LocalDateTime candidate = start.plusSeconds(1); !candidate.isAfter(limit); candidate = candidate.plusSeconds(1)) {
            if (schedule.matchesTime(candidate)) return candidate;
        }
        return null;
    }

    /** The tick-based next fire: game time at the tick rate, nothing under a minute or while frozen. */
    private static void estimates() {
        IntervalSchedule hour = new IntervalSchedule(Timers.Unit.HOURS.ticks(1));
        check(hour.next(now(0, 0), TimerState.NEW).equals(Optional.of(ZonedDateTime.of(2026, 10, 11, 13, 0, 0, 0, ZONE))),
                "An hour of ticks at 20 per second is an hour from now");
        check(hour.next(new TimerNow(0, 0, NOON, ZONE, 10, false), TimerState.NEW)
                .equals(Optional.of(ZonedDateTime.of(2026, 10, 11, 14, 0, 0, 0, ZONE))), "At /tick rate 10 it takes twice as long");
        check(hour.next(new TimerNow(0, 0, NOON, ZONE, 20, true), TimerState.NEW).isEmpty(),
                "Frozen ticks give no next fire");
        check(new IntervalSchedule(1_199).next(now(0, 0), TimerState.NEW).isEmpty() && new IntervalSchedule(1_200).next(now(0, 0), TimerState.NEW).isPresent(),
                "Under 60 s away there is none; from 60 s on there is one");
        DayTimeSchedule sunset = new DayTimeSchedule(GameClock.Moment.SUNSET.ticks());
        check(sunset.next(now(0, 6_000), TimerState.NEW).equals(Optional.of(ZonedDateTime.of(2026, 10, 11, 12, 5, 0, 0, ZONE))),
                "Noon to sunset is 6000 ticks, five minutes");
        check(sunset.next(now(0, 10_000), TimerState.NEW).isPresent() && sunset.next(now(0, 11_000), TimerState.NEW).isEmpty()
                && sunset.next(now(0, 12_000 - 100), TimerState.NEW).isEmpty(), "Under a minute before its moment there is none");
    }

    /**
     * With the overworld clock standing ({@code gamerule advance_time false}, {@code /time pause}) or the ticks frozen, a
     * Daylight Timer and an Alarm Clock know no next fire, so the 60 s resync publishes none instead of moving one
     * forward. The watch that tells them reads the clock's state once per real tick instead of guessing from its
     * movement, so a slowed clock ({@code /time rate} below 1, which stands still on some ticks) keeps running, and its
     * rate stretches the estimate of a next fire.
     */
    private static void clockStopped() {
        ClockWatch watch = new ClockWatch();
        check(watch.running() && watch.rate() == 1 && !watch.tick(true, false, false, 1) && watch.running(),
                "The clock runs at rate 1 until a tick says otherwise");
        check(watch.tick(false, false, false, 1) && !watch.running(), "gamerule advance_time false stops it");
        check(!watch.tick(false, false, false, 1) && !watch.running(), "and it stays stopped, without telling again");
        check(watch.tick(true, false, false, 1) && watch.running(), "gamerule advance_time true starts it at once");
        check(watch.tick(true, true, false, 1) && !watch.running(), "/time pause stops it");
        check(watch.tick(true, false, false, 1) && watch.running(), "and /time resume starts it");
        check(watch.tick(true, false, true, 1) && !watch.running(), "Frozen ticks stop it (/tick freeze), also while /tick step moves the clock");
        check(watch.tick(true, false, false, 1) && watch.running(), "and after the thaw it runs again");
        check(ClockWatch.runs(true, false, false) && !ClockWatch.runs(false, false, false) && !ClockWatch.runs(true, true, false)
                && !ClockWatch.runs(true, false, true), "It runs only with advance_time on, not paused and not frozen");

        // /time rate 0.5 advances the clock on every other tick and /time rate 0.9 stands still on one tick in ten: the
        // clock runs all along, and only the change of rate is told, once.
        check(watch.tick(true, false, false, 0.5f) && watch.running() && watch.rate() == 0.5f, "A new rate is told once");
        int told = 0;
        for (int tick = 0; tick < 200; tick++) {
            if (watch.tick(true, false, false, 0.5f)) told++;
        }
        check(told == 0 && watch.running(), "A clock at rate 0.5 keeps running and tells nothing more in 200 ticks: " + told);
        check(watch.tick(true, false, false, 0.9f) && watch.rate() == 0.9f, "rate 0.9 is told once");
        told = 0;
        for (int tick = 0; tick < 200; tick++) {
            if (watch.tick(true, false, false, 0.9f)) told++;
        }
        check(told == 0 && watch.running(), "and then nothing for 200 ticks: " + told);
        check(watch.tick(true, false, false, 0) && watch.rate() == 1 && !watch.tick(true, false, false, Float.NaN),
                "A rate that is no positive number counts as 1");

        DayTimeSchedule sunset = new DayTimeSchedule(GameClock.Moment.SUNSET.ticks());
        IntervalSchedule hour = new IntervalSchedule(Timers.Unit.HOURS.ticks(1));
        TimerNow standing = new TimerNow(0, 6_000, NOON, ZONE, 20, false, false);
        check(sunset.next(standing, TimerState.NEW).isEmpty(), "A stopped clock gives a Daylight Timer or an Alarm Clock no next fire");
        check(hour.next(standing, TimerState.NEW).isPresent(), "An Interval Timer counts its own ticks, which a stopped clock does not stop");
        check(CronTimerSchedule.of("0 0 0 13 * * *").next(standing, TimerState.NEW).isPresent(), "A cron timer follows the wall clock");
        TimerNow frozen = new TimerNow(0, 6_000, NOON, ZONE, 20, true);
        check(!frozen.clockRunning() && new TimerNow(0, 6_000, NOON, ZONE, 20, false).clockRunning(), "The short reading runs the clock unless frozen");
        check(sunset.next(frozen, TimerState.NEW).isEmpty() && hour.next(frozen, TimerState.NEW).isEmpty(), "Frozen ticks give the tick-based kinds none");

        // At rate 0.5 the 6000 clock ticks from noon to sunset take 12000 server ticks, ten minutes; at rate 2 two and a
        // half. A resync a minute later at rate 0.5 (600 clock ticks on) lands on the same moment, so nothing is published.
        TimerNow half = new TimerNow(0, 6_000, NOON, ZONE, 20, false, true, 0.5f);
        check(sunset.next(half, TimerState.NEW).equals(Optional.of(ZonedDateTime.of(2026, 10, 11, 12, 10, 0, 0, ZONE))),
                "At rate 0.5 noon to sunset is ten minutes");
        check(sunset.next(new TimerNow(0, 6_000, NOON, ZONE, 20, false, true, 2), TimerState.NEW)
                .equals(Optional.of(ZonedDateTime.of(2026, 10, 11, 12, 2, 30, 0, ZONE))), "At rate 2 it is two and a half minutes");
        String slow = NextFire.format(sunset.next(half, TimerState.NEW));
        String minuteLater = NextFire.format(sunset.next(new TimerNow(1_200, 6_600, NOON.plusSeconds(60), ZONE, 20, false, true, 0.5f), TimerState.NEW));
        check(!slow.isEmpty() && !NextFire.moved(slow, minuteLater), "A resync a minute later at rate 0.5 moves nothing: " + slow + " / " + minuteLater);
        check(hour.next(half, TimerState.NEW).equals(hour.next(now(0, 6_000), TimerState.NEW)), "The clock's rate does not touch an Interval Timer");
        check(half.serverTicksFor(1) == 2 && new TimerNow(0, 0, NOON, ZONE, 20, false, true, 3).serverTicksFor(10) == 4
                && new TimerNow(0, 0, NOON, ZONE, 20, false).serverTicksFor(7) == 7, "Clock ticks become server ticks, rounded up");
        boolean refused = false;
        try {
            new TimerNow(0, 0, NOON, ZONE, 20, false, true, 0);
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check(refused, "A reading refuses a clock rate that is not positive");

        // The resync every minute while the clock stands: the next fire goes once, then nothing moves.
        String shown = NextFire.format(sunset.next(now(0, 6_000), TimerState.NEW));
        check(!shown.isEmpty(), "Running, sunset has a next fire");
        String stopped = NextFire.format(sunset.next(standing, TimerState.NEW));
        check(NextFire.moved(shown, stopped), "The clock stops: the next fire is cleared once (Home Assistant shows unknown)");
        int republished = 0;
        for (int minute = 1; minute <= 10; minute++) {
            String computed = NextFire.format(sunset.next(new TimerNow(minute * 1_200L, 6_000, NOON.plusSeconds(60L * minute), ZONE, 20, false, false), TimerState.NEW));
            if (NextFire.moved(stopped, computed)) republished++;
        }
        check(republished == 0, "Ten resyncs with the clock standing publish nothing: " + republished);
        check(NextFire.moved(stopped, NextFire.format(sunset.next(now(0, 6_000), TimerState.NEW))), "The clock runs again: the next fire comes back");
    }

    private static void nextFireText() {
        check(NextFire.format(Optional.of(ZonedDateTime.of(2026, 10, 11, 18, 0, 0, 500, ZONE))).equals("2026-10-11T18:00:00+02:00"),
                "ISO-8601 with the offset, to the second");
        check(NextFire.format(Optional.of(ZonedDateTime.of(2026, 12, 24, 18, 0, 0, 0, ZONE))).equals("2026-12-24T18:00:00+01:00"), "Winter time has +01:00");
        check(NextFire.format(Optional.empty()).isEmpty() && NextFire.NONE.isEmpty(), "None is the empty text");
        check(NextFire.parse("2026-10-11T18:00:00+02:00").map(time -> time.toInstant().equals(ZonedDateTime.of(2026, 10, 11, 16, 0, 0, 0, ZoneId.of("UTC")).toInstant()))
                .orElse(false) && NextFire.parse("").isEmpty() && NextFire.parse("soon").isEmpty(), "It reads back, the empty text and garbage as none");
    }

    /** The Controls tab's status line per kind (D5). */
    private static void statusLines() {
        check(TimerStatus.every(5, "s", "", NOON).equals("Every 5 s") && TimerStatus.every(20, "ticks", "", NOON).equals("Every 20 ticks"),
                "An interval says how often");
        check(TimerStatus.every(2, "h", "2026-10-11T13:00:00+02:00", NOON).equals("Every 2 h, next in 1 h"), "and when, a minute or more ahead");
        check(TimerStatus.moment("06:00", "2026-10-11T12:03:00+02:00", NOON).equals("Next 06:00, in 3 min")
                && TimerStatus.moment("18:00", "", NOON).equals("Next 18:00"), "A moment with the game time it fires at");
        check(TimerStatus.cron("0 0 0 13 * * *", true, "2026-10-11T13:00:00+02:00", NOON, ZONE).equals("Next 13:00:00, in 1 h")
                        && TimerStatus.cron("0 0 0 9 * * *", true, "2026-10-12T09:00:00+02:00", NOON, ZONE).equals("Next Mon 09:00, in 21 h")
                        && TimerStatus.cron("0 0 0 9 * * *", true, "2026-10-15T09:00:00+02:00", NOON, ZONE).equals("Next Thu 09:00, in 3 d"),
                "A cron timer's next fire in the server's zone, today to the second, later with its day");
        check(TimerStatus.cron("", false, "", NOON, ZONE).equals("No schedule") && TimerStatus.cron("bad", false, "", NOON, ZONE).equals("Invalid cron")
                && TimerStatus.cron("0 0 0 0 31 2 *", true, "", NOON, ZONE).equals("No fire within 400 days"), "An empty, an invalid and a never matching expression");
        check(TimerStatus.in(NOON, NextFire.parse("2026-10-11T12:00:42+02:00").orElseThrow()).equals("in 42 s")
                && TimerStatus.in(NOON, NextFire.parse("2026-10-11T11:00:00+02:00").orElseThrow()).equals("due now"), "Seconds, and a time that has come");

        // The types read their line from the state.
        Instant now = Instant.now();
        check(Timers.INTERVAL_TIMER.statusLine(json("{\"enabled\":true,\"next_fire\":\"\",\"interval\":5,\"unit\":\"seconds\"}"), now).equals("Every 5 s")
                && Timers.INTERVAL_TIMER.statusLine(json("{\"enabled\":true,\"next_fire\":\"\",\"interval\":1,\"unit\":\"ticks\"}"), now).equals("Every 1 tick"),
                "The interval timer's line");
        check(Timers.INTERVAL_TIMER.statusLine(json("{\"enabled\":false,\"interval\":5,\"unit\":\"seconds\"}"), now).equals("Disabled"), "A disabled timer says so");
        check(Timers.DAYLIGHT_TIMER.statusLine(json("{\"enabled\":true,\"next_fire\":\"\",\"trigger_at\":\"sunset\",\"time\":\"06:00\"}"), now).equals("Next 18:00")
                && Timers.DAYLIGHT_TIMER.statusLine(json("{\"enabled\":true,\"next_fire\":\"\",\"trigger_at\":\"custom\",\"time\":\"07:30\"}"), now).equals("Next 07:30"),
                "The Daylight Timer names its moment's time, or its own");
        check(Timers.ALARM_CLOCK.statusLine(json("{\"enabled\":true,\"next_fire\":\"\",\"time\":\"05:15\"}"), now).equals("Next 05:15"), "The Alarm Clock's line");
        check(Timers.CRON_TIMER.statusLine(json("{\"enabled\":false,\"next_fire\":\"\",\"cron\":\"0 0 0 25 * * *\"}"), now).equals("Invalid cron")
                && Timers.CRON_TIMER.statusLine(json("{\"enabled\":true,\"next_fire\":\"\",\"cron\":\"\"}"), now).equals("No schedule")
                && Timers.CRON_TIMER.statusLine(json("{\"enabled\":false,\"next_fire\":\"\",\"cron\":\"0 0 0 9 * * *\"}"), now).equals("Disabled"),
                "A broken or empty expression is told also while disabled");
    }

    /** The four types: ids, models, the shared ports and entities, each kind's schedule settings, the HA entities. */
    private static void declarations() {
        check(List.of(Timers.INTERVAL_TIMER, Timers.DAYLIGHT_TIMER, Timers.ALARM_CLOCK, Timers.CRON_TIMER).stream().map(type -> type.id() + " " + type.model()).toList()
                .equals(List.of("mineqtt:interval_timer Interval Timer", "mineqtt:game_time_timer Daylight Timer", "mineqtt:alarm_clock Alarm Clock",
                        "mineqtt:cron_timer Cron Timer")), "Ids and models (D2)");
        for (DeviceType type : Timers.all()) {
            check(type.action(Action.STATE).map(Action::toggles).orElse(false) && type.action("trigger").map(action -> action.kind() instanceof PortType.Event).orElse(false),
                    type + ": the state action (Home Assistant's switch arms it) and the trigger action");
            check(type.event("fired").equals(Optional.of(Timers.FIRED)) && Timers.FIRED.minInterval().equals(Duration.ofMillis(250)), type + ": fired, at most every 250 ms");
            check(type.noV1Topic() && type.status().isPresent() && type.rawLink().map(spec -> spec.direction() == RawLinkSpec.Direction.OUT && !spec.level()).orElse(false),
                    type + ": no v1 topic, a status line, an events Raw Link (M4-4)");
            check(type.port(Port.Direction.IN, "enabled").isPresent() && type.port(Port.Direction.OUT, "next_fire").isPresent()
                    && type.port(Port.Direction.IN, "next_fire").isEmpty(), type + ": enabled in and out, next_fire out only");
            Set<String> entities = type.entities().stream().map(HaEntity::key).collect(Collectors.toSet());
            check(entities.containsAll(Set.of("enabled", "fired", "trigger", "next_fire", "location")), type + ": the shared entities: " + entities);
            HaEntity.Switch<?> enabled = (HaEntity.Switch<?>) type.entities().stream().filter(entity -> entity.key().equals("enabled")).findFirst().orElseThrow();
            check(enabled.meta().name() == null && enabled.field().equals(Timers.ENABLED), type + ": the enable switch is the primary entity");
            HaEntity.Button trigger = (HaEntity.Button) type.entities().stream().filter(entity -> entity.key().equals("trigger")).findFirst().orElseThrow();
            check("Trigger now".equals(trigger.meta().name()), type + ": the button's key is the action's, its name Trigger now");
            HaEntity.Sensor nextFire = (HaEntity.Sensor) type.entities().stream().filter(entity -> entity.key().equals("next_fire")).findFirst().orElseThrow();
            check("timestamp".equals(nextFire.deviceClass()) && "{{ value_json.next_fire if value_json.next_fire else None }}".equals(nextFire.valueTemplate()),
                    type + ": next_fire is a timestamp, empty as None");
        }
        check(Timers.DAYLIGHT_TIMER.port(Port.Direction.IN, "trigger_at").isPresent() && Timers.DAYLIGHT_TIMER.port(Port.Direction.IN, "trigger")
                .map(port -> port.origin() == Port.Origin.ACTION).orElse(false), "The Daylight Timer's moment is trigger_at, so trigger stays the action");
        HaEntity.Text time = (HaEntity.Text) Timers.ALARM_CLOCK.entities().stream().filter(entity -> entity.key().equals("time")).findFirst().orElseThrow();
        check(Timers.TIME_PATTERN.equals(time.pattern()) && time.maxLength() == 5 && time.meta().category() == HaEntity.Category.CONFIG,
                "The time is a config text with the HH:MM pattern");
        check(Timers.TIME.parse(new JsonPrimitive("07:15")).equals(Optional.of("07:15")) && Timers.TIME.parse(new JsonPrimitive("25:00")).isEmpty()
                && Timers.TIME.parse(new JsonPrimitive("7:15")).isEmpty(), "MineQTT refuses what the pattern refuses");
        check(Timers.CRON.parse(new JsonPrimitive("0 */5 * * * * *")).isPresent() && Timers.CRON.parse(new JsonPrimitive("nope")).isEmpty(),
                "A cron command must parse");
        check(settingKeys(Timers.INTERVAL_TIMER).equals(List.of("interval", "unit")) && settingKeys(Timers.DAYLIGHT_TIMER).equals(List.of("trigger_at", "time"))
                && settingKeys(Timers.ALARM_CLOCK).equals(List.of("time")) && settingKeys(Timers.CRON_TIMER).equals(List.of("cron")), "Each kind's schedule settings");
        check(Timers.fired(Timers.Source.MANUAL).equals(json("{\"event_type\":\"fired\",\"source\":\"manual\"}"))
                && Timers.INTERVAL_TIMER.eventProblem(Timers.fired(Timers.Source.SCHEDULE)).isEmpty(), "The fired event the type declares");
    }

    // The seconds of the minute starting at NOON a cron schedule fires in, at tick 0 of each.
    private static List<Integer> seconds(CronTimerSchedule schedule) {
        List<Integer> seconds = new ArrayList<>();
        for (int second = 0; second < 60; second++) {
            if (schedule.due(new TimerNow(0, 0, NOON.plusSeconds(second), ZONE, 20, false), TimerState.NEW)) seconds.add(second);
        }
        return seconds;
    }

    /**
     * M4-3 (plan D4): timer data as v1, 0.6.0 and 0.7.0 wrote it, read through {@code TagValueInput} by the block entity:
     * the type keys become settings and counters, the slot items legacy items, the owner and anvil name the device's; it
     * is saved back as v1 data until the attach adopts it, which makes no Raw Link and drops v1's private mode and tab.
     * Adopted, it saves its settings and counters in the {@code mineqtt} compound and reads them back.
     */
    private static void v1Data() {
        REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        Items.PAPER.builtInRegistryHolder().bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
        for (Era era : Era.values()) {
            // interval: 7 seconds, counted to 3, last fired at game time 400.
            CompoundTag interval = era.device(TimerKind.INTERVAL);
            interval.putLong("intervalValue", 7L);
            interval.putInt("unitIndex", 1);
            interval.putLong("tickCounter", 3L);
            interval.putLong("lastTriggerTime", 400L);
            expectV1(era, TimerKind.INTERVAL, interval, Settings.DEFAULT.withInterval(7, Timers.Unit.SECONDS), new TimerState(3, -1, -1, 400));
            // Daylight Timer: a custom time off a minute boundary, fired on day 12.
            CompoundTag daylight = era.device(TimerKind.GAME_TIME);
            daylight.putInt("triggerModeIndex", 4);
            daylight.putInt("customTime", 4321);
            daylight.putLong("lastTriggerDay", 12L);
            daylight.putLong("lastTriggerTime", 900L);
            Settings custom = Settings.DEFAULT.withTriggerAt(Timers.TriggerAt.CUSTOM).withTimeTicks(4321);
            expectV1(era, TimerKind.GAME_TIME, daylight, custom, new TimerState(0, 12, -1, 900));
            check(custom.time().equals(GameClock.format(4321)) && custom.schedule(TimerKind.GAME_TIME).equals(new DayTimeSchedule(4321)),
                    era + ": the off-minute custom time stays exact: it fires at tick 4321, shown as " + custom.time());
            // Alarm Clock: 12:00, fired on day 5.
            CompoundTag alarm = era.device(TimerKind.ALARM);
            alarm.putInt("alarmTime", 6000);
            alarm.putLong("lastAlarmDay", 5L);
            alarm.putLong("lastTriggerTime", 77L);
            expectV1(era, TimerKind.ALARM, alarm, Settings.DEFAULT.withTimeTicks(6000), new TimerState(0, 5, -1, 77));
            // Cron Timer: v1 wrote no lastTriggerTime.
            CompoundTag cron = era.device(TimerKind.CRON);
            cron.putString("cronExpression", "0 0 0 12 * * *");
            cron.putLong("lastTriggerTick", 1234L);
            expectV1(era, TimerKind.CRON, cron, Settings.DEFAULT.withCron("0 0 0 12 * * *"), new TimerState(0, -1, 1234, 0));
        }
        // An alarm clock without an alarm is disabled at 06:00.
        CompoundTag unset = Era.V1.device(TimerKind.ALARM);
        unset.putInt("alarmTime", -1);
        unset.putLong("lastAlarmDay", -1L);
        unset.putLong("lastTriggerTime", 0L);
        expectV1(Era.V1, TimerKind.ALARM, unset, Settings.DEFAULT.withEnabled(false).withTimeTicks(0), TimerState.NEW);
        // A larger interval than 1 000 000 is clamped, and the block entity logs what it read.
        CompoundTag huge = Era.V070.device(TimerKind.INTERVAL);
        huge.putLong("intervalValue", 5_000_000L);
        LegacyNbtV1.Timer clamped = TimerBlockEntity.loadTimer(new DeviceLink(Timers.INTERVAL_TIMER), TimerKind.INTERVAL, input(huge));
        check(clamped.settings().interval() == Timers.MAX_INTERVAL && clamped.intervalValue() == 5_000_000L, "An interval over 1 000 000 is clamped: " + clamped);
        // An invalid v1 cron expression is kept; it never fires.
        CompoundTag broken = Era.V060.device(TimerKind.CRON);
        broken.putString("cronExpression", "0 0 0 25 * * *");
        LegacyNbtV1.Timer kept = TimerBlockEntity.loadTimer(new DeviceLink(Timers.CRON_TIMER), TimerKind.CRON, input(broken));
        check(kept.settings().cron().equals("0 0 0 25 * * *") && !((CronTimerSchedule) kept.settings().schedule(TimerKind.CRON)).valid(),
                "An invalid v1 expression is kept and never fires");
        check(Timers.CRON_TIMER.statusLine(json("{\"enabled\":true,\"next_fire\":\"\",\"cron\":\"0 0 0 25 * * *\"}"), NOON).equals("Invalid cron"), "and says so");
    }

    /**
     * M4-4 (plan D2, D3): the next fire's deadband and resync, the {@code fired} throttle on the action topic and the Raw
     * Link while the bus carries every fire, and a manual trigger while disabled.
     */
    private static void publishing() {
        String shown = "2026-10-11T18:00:00+02:00";
        check(!NextFire.moved(shown, shown) && !NextFire.moved(shown, "2026-10-11T18:00:05+02:00") && !NextFire.moved(shown, "2026-10-11T17:59:55+02:00")
                && NextFire.moved(shown, "2026-10-11T18:00:06+02:00") && NextFire.moved(shown, "2026-10-11T17:59:54+02:00"),
                "A next fire moved by 5 s or less stays as shown, more is shown");
        check(!NextFire.moved(shown, "2026-10-11T16:00:03Z") && NextFire.moved(shown, "2026-10-11T16:00:06Z"), "The deadband compares instants, whatever the offset");
        check(NextFire.moved(shown, NextFire.NONE) && NextFire.moved(NextFire.NONE, shown) && !NextFire.moved(NextFire.NONE, NextFire.NONE) && NextFire.moved("soon", shown)
                && NextFire.moved("soon", NextFire.NONE), "A next fire that appears or goes is always shown, and so is anything after an unreadable one");
        check(NextFire.DEADBAND.equals(Duration.ofSeconds(5)) && NextFire.RESYNC.equals(Duration.ofSeconds(60)), "Deadband 5 s, resync every 60 s (D2)");
        // A cron timer every 5 s moves its next fire 5 s per fire: the state shows every other one, never one older than 10 s.
        CronTimerSchedule everyFive = CronTimerSchedule.of("0 */5 * * * * *");
        String shownFire = NextFire.format(everyFive.next(new TimerNow(0, 0, NOON, ZONE, 20, false), TimerState.NEW));
        int republished = 0;
        for (int fire = 1; fire <= 12; fire++) {
            String computed = NextFire.format(everyFive.next(new TimerNow(0, 0, NOON.plusSeconds(5L * fire), ZONE, 20, false), TimerState.NEW));
            if (NextFire.moved(shownFire, computed)) {
                shownFire = computed;
                republished++;
            }
        }
        check(republished == 6, "A 5 s cron republishes its next fire with every other fire: " + republished);

        // The fastest timer fires every other tick, 10 times a second: the action topic and the Raw Link get at most 4 of
        // them a second (250 ms), the bus every one, as a source port may emit 20 a second.
        DeviceTraffic traffic = new DeviceTraffic();
        int published = 0;
        for (long millis = 0; millis < 1_000; millis += 100) {
            if (traffic.admitEvent(Timers.FIRED, 10_000 + millis)) published++;
        }
        check(published == 4 && traffic.throttled() == 6, "10 fires in a second publish 4 on MQTT, 6 are held back and counted: " + published);
        int fastestPerSecond = TimerNow.TICKS_PER_SECOND / (1 + TimerKind.INTERVAL.pulseTicks());
        check(fastestPerSecond == 10 && MessageBus.RATE_PER_SECOND >= fastestPerSecond && !Timers.FIRED.busOnly(),
                "The bus carries every fire of the fastest timer (" + fastestPerSecond + " a second, the bus takes " + MessageBus.RATE_PER_SECOND + ")");

        // Trigger now works while disabled: the step asks nothing of the enable, a disabled tick never fires on schedule.
        IntervalSchedule one = new IntervalSchedule(1);
        check(TimerStep.tick(TimerKind.INTERVAL, one, false, false, now(5, 0), TimerState.NEW).action() == TimerStep.Action.NONE
                && TimerStep.trigger(false, now(5, 0), TimerState.NEW).isPresent(), "A disabled timer fires on Trigger now, never on schedule");
    }

    /** The eras whose timers wrote v1 data: v1 itself, and 0.6.0 and 0.7.0, which also saved the owner's UUID (F11). */
    private enum Era {
        V1(false), V060(true), V070(true);

        private final boolean ownerUuid;

        Era(boolean ownerUuid) {
            this.ownerUuid = ownerUuid;
        }

        /** The keys every v1-shaped timer wrote (BaseMqttBlockEntity): its slots, topic, last tab, owner, private mode, anvil name. */
        CompoundTag device(TimerKind kind) {
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, REGISTRIES);
            output.putString("topic", "");
            output.putString("lastTabId", kind.id());
            output.putString("ownerName", "Steve");
            if (ownerUuid) output.storeNullable("ownerUuid", UUIDUtil.CODEC, STEVE);
            output.putBoolean("privateMode", true);
            ContainerHelper.saveAllItems(output, NonNullList.of(ItemStack.EMPTY, paper("kitchen"), paper("timer"), ItemStack.EMPTY, ItemStack.EMPTY));
            CompoundTag tag = output.buildResult();
            tag.putString("CustomName", "Kitchen " + Timers.type(kind).model());
            return tag;
        }
    }

    // A v1-shaped timer loads into these settings and counters, is saved back as the same v1 data, and adopts as D4 says.
    private static void expectV1(Era era, TimerKind kind, CompoundTag tag, Settings settings, TimerState counters) {
        String what = era + " " + kind;
        check(LegacyNbtV1.hasTimerKeys(input(tag)), what + ": its type keys tell v1 data");
        DeviceLink link = new DeviceLink(Timers.type(kind));
        LegacyNbtV1.Timer loaded = TimerBlockEntity.loadTimer(link, kind, input(tag));
        check(loaded.settings().equals(settings) && loaded.counters().equals(counters), what + ": settings and counters " + loaded);
        check(link.awaitingAdoption() && link.legacyItems().size() == 2, what + ": waits for its attach with its two slot items");

        // Saved before its attach, it is the same v1 timer when it loads again.
        TagValueOutput saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, REGISTRIES);
        TimerBlockEntity.saveTimer(link, kind, loaded.settings(), loaded.counters(), saved);
        CompoundTag v1Again = saved.buildResult();
        check(!v1Again.contains(DeviceLink.KEY), what + ": saved as v1 data until adopted");
        DeviceLink reloaded = new DeviceLink(Timers.type(kind));
        LegacyNbtV1.Timer again = TimerBlockEntity.loadTimer(reloaded, kind, input(v1Again));
        check(again.settings().equals(settings) && again.counters().equals(counters) && reloaded.awaitingAdoption() && reloaded.legacyItems().size() == 2,
                what + ": and reads back as it was: " + again);

        // The attach adopts it on chunk load (trusted): owner, anvil name and slot items, no Raw Link, no private mode or tab.
        LinkAdoptionCheck.adopt(link, true);
        check(link.raw() == null, what + ": no Raw Link from the slots' literal (noV1Topic): " + link.raw());
        check("Steve".equals(link.ownerName()) && (era.ownerUuid ? STEVE.equals(link.owner()) : link.owner() == null),
                what + ": its owner, by the saved UUID from 0.6.0 on, else by name once it resolves: " + link.owner());
        check(link.name().equals("Kitchen " + Timers.type(kind).model()) && link.legacyItems().size() == 2 && !link.privateMode() && link.lastTab().isEmpty(),
                what + ": its anvil name and legacy items, without v1's private mode and tab");

        // Adopted, it saves its settings and counters in the mineqtt compound, without v1 keys, and reads them back.
        TagValueOutput adopted = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, REGISTRIES);
        TimerBlockEntity.saveTimer(link, kind, loaded.settings(), loaded.counters(), adopted);
        CompoundTag v2 = adopted.buildResult();
        check(v2.contains(DeviceLink.KEY) && !LegacyNbtV1.hasTimerKeys(input(v2)), what + ": v2 data has no v1 type keys: " + v2);
        DeviceLink v2Link = new DeviceLink(Timers.type(kind));
        LegacyNbtV1.Timer read = TimerBlockEntity.loadTimer(v2Link, kind, input(v2));
        check(read.settings().equals(settings) && read.counters().equals(counters) && !v2Link.awaitingAdoption() && v2Link.legacyItems().size() == 2,
                what + ": and reads it back: " + read);
        check(read.intervalValue() == read.settings().interval(), what + ": v2 data has nothing clamped to log");
    }

    private static ItemStack paper(String name) {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    private static ValueInput input(CompoundTag tag) {
        return TagValueInput.create(ProblemReporter.DISCARDING, REGISTRIES, tag);
    }

    private static List<String> settingKeys(DeviceType type) {
        return type.settings().stream().map(setting -> setting.key()).toList();
    }

    // Runs an enabled or disabled timer for game ticks [from, to) with a fixed clock; returns the game times it fired at.
    private static List<Long> run(TimerKind kind, TimerSchedule schedule, boolean enabled, TimerState state, long from, long to) {
        List<Long> fires = new ArrayList<>();
        boolean pulsing = false;
        for (long gameTime = from; gameTime < to; gameTime++) {
            TimerStep step = TimerStep.tick(kind, schedule, enabled, pulsing, now(gameTime, 0), state);
            state = step.state();
            if (step.action() == TimerStep.Action.FIRE) {
                fires.add(gameTime);
                pulsing = true;
            } else if (step.action() == TimerStep.Action.PULSE_OFF) {
                pulsing = false;
            }
        }
        return fires;
    }

    // Runs a timer over overworld clock ticks [from, to), the game time moving with it; returns the clock ticks it fired at.
    private static List<Long> runClock(TimerKind kind, TimerSchedule schedule, boolean enabled, TimerState state, long from, long to) {
        List<Long> fires = new ArrayList<>();
        boolean pulsing = false;
        for (long clock = from; clock < to; clock++) {
            TimerStep step = TimerStep.tick(kind, schedule, enabled, pulsing, now(clock, clock), state);
            state = step.state();
            if (step.action() == TimerStep.Action.FIRE) {
                fires.add(clock);
                pulsing = true;
            } else if (step.action() == TimerStep.Action.PULSE_OFF) {
                pulsing = false;
            }
        }
        return fires;
    }

    private static TimerNow now(long gameTime, long clockTicks) {
        return new TimerNow(gameTime, clockTicks, NOON, ZONE, 20, false);
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static void expectFailure(Runnable action, String what) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(what + " must be refused");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}

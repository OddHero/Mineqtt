package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.TimerBlock;
import art.rehra.mineqtt.device.Action;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommandResult;
import art.rehra.mineqtt.device.CommandSource;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.Field;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.timer.core.CronTimerSchedule;
import art.rehra.mineqtt.device.timer.core.IntervalSchedule;
import art.rehra.mineqtt.device.timer.core.NextFire;
import art.rehra.mineqtt.device.timer.core.TimerKind;
import art.rehra.mineqtt.device.timer.core.TimerNow;
import art.rehra.mineqtt.device.timer.core.TimerSchedule;
import art.rehra.mineqtt.device.timer.core.TimerState;
import art.rehra.mineqtt.device.timer.core.TimerStep;
import art.rehra.mineqtt.device.types.Timers;
import art.rehra.mineqtt.device.types.Timers.Settings;
import art.rehra.mineqtt.flow.core.Coerce;
import art.rehra.mineqtt.legacy.LegacyNbtV1;
import art.rehra.mineqtt.runtime.WorldClock;
import art.rehra.mineqtt.util.GameClock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;

import static art.rehra.mineqtt.blocks.TimerBlock.POWER;

/**
 * The block entity of all four timers (M4 plan D2), registered under their four v1 block entity ids so saved chunks load;
 * the kind comes with the type it is created for. Each tick it asks its schedule ({@link TimerStep}): a fire gives a
 * redstone pulse of its kind's length and a {@code fired} event, and the end of a pulse turns the redstone off. A press
 * on {@code trigger} fires at once with the source {@code manual}, also while disabled, and is dropped while a pulse is on.
 * <p>
 * It ticks while its chunk is loaded, whether or not its device is attached, so its redstone works with MineQTT's broker
 * down; what it publishes waits for the attach. Its settings and counters are in the {@code mineqtt} compound
 * ({@code state} and {@code timer}); v1 data, whose type keys every 0.6.0 and 0.7.0 timer still wrote, is read on load
 * and adopted when the device attaches, and saved as v1 data until then (D4). Clients get no render data: the block
 * state shows the pulse.
 */
public class TimerBlockEntity extends DeviceBlockEntity {
    private final TimerKind kind;
    private final DeviceType type;
    private final DeviceLink link;
    private Settings settings = Settings.DEFAULT;
    private TimerState counters = TimerState.NEW;
    private TimerSchedule schedule;
    // The next fire the state shows; null until it is computed in a level. Never saved.
    private @Nullable String nextFire;
    // Whether the ticker saw the device attached; the next fire is computed again at each attach. Never saved.
    private boolean attachSeen;
    // The wall-clock time the next fire was last computed, for the resync. Never saved.
    private long nextFireComputed;

    public TimerBlockEntity(BlockEntityType<?> blockEntityType, TimerKind kind, BlockPos pos, BlockState blockState) {
        super(blockEntityType, pos, blockState);
        this.kind = kind;
        this.type = Timers.type(kind);
        this.link = new DeviceLink(type);
        this.schedule = settings.schedule(kind);
    }

    /** The block entity of a kind of timer, under that kind's block entity type. */
    public static TimerBlockEntity create(TimerKind kind, BlockPos pos, BlockState state) {
        BlockEntityType<TimerBlockEntity> type = switch (kind) {
            case INTERVAL -> MineqttBlockEntityTypes.INTERVAL_TIMER_BLOCK.get();
            case GAME_TIME -> MineqttBlockEntityTypes.GAME_TIME_TIMER_BLOCK.get();
            case ALARM -> MineqttBlockEntityTypes.ALARM_CLOCK_BLOCK.get();
            case CRON -> MineqttBlockEntityTypes.CRON_TIMER_BLOCK.get();
        };
        return new TimerBlockEntity(type, kind, pos, state);
    }

    public TimerKind kind() {
        return kind;
    }

    /** What the timer is set to. */
    public Settings settings() {
        return settings;
    }

    /** What the timer counted. */
    public TimerState counters() {
        return counters;
    }

    @Override
    public DeviceType deviceType() {
        return type;
    }

    @Override
    public DeviceLink deviceLink() {
        return link;
    }

    @Override
    public void writeState(StateWriter out) {
        settings.write(kind, nextFire(), out).put(CommonFields.LOCATION, GlobalPos.of(level.dimension(), worldPosition));
    }

    /**
     * {@code state} (ON, OFF, TOGGLE or a boolean spelling) and {@code enabled} arm or disarm the schedule; enabling never
     * fires at once. {@code trigger} (PRESS, or empty) fires now. The schedule's settings change what they name; an
     * interval or unit change starts the count again. A value that does not fit, such as a time that is no HH:MM or a cron
     * expression that does not parse, rejects the whole command.
     */
    @Override
    public CommandResult applyCommand(CommandReader in, CommandSource source) {
        Set<String> keys = in.keys();
        if (keys.isEmpty()) return CommandResult.rejected("not a " + type.model() + " command: " + keys);
        Settings next = settings;
        boolean trigger = false;
        for (String key : keys) {
            if (key.equals(Action.STATE)) {
                boolean enabledNow = next.enabled();
                Optional<String> value = in.text(key).map(String::strip);
                Optional<Boolean> on = value.flatMap(text -> text.equalsIgnoreCase(Coerce.TOGGLE) ? Optional.of(!enabledNow) : Coerce.bool(text));
                if (on.isEmpty()) return CommandResult.rejected("not ON, OFF or TOGGLE: " + value.orElse("?"));
                next = next.withEnabled(on.get());
            } else if (key.equals(Timers.ENABLED.key())) {
                Optional<Boolean> on = in.get(Timers.ENABLED);
                if (on.isEmpty()) return CommandResult.rejected("not on or off: enabled");
                next = next.withEnabled(on.get());
            } else if (key.equals(Timers.TRIGGER.key())) {
                String value = in.text(key).map(String::strip).orElse(null);
                if (value == null || !(value.isEmpty() || value.equalsIgnoreCase(Coerce.PRESS))) return CommandResult.rejected("trigger takes PRESS");
                trigger = true;
            } else {
                Optional<Settings> changed = setting(next, key, in);
                if (changed.isEmpty()) return CommandResult.rejected(problem(key, in));
                next = changed.get();
            }
        }
        TimerNow now = now();
        boolean changed = !next.equals(settings);
        if (changed) changeSettings(next, now);
        boolean fired = trigger && fire(Timers.Source.MANUAL, now);
        // A trigger during a pulse is dropped, as v1 ignored it; the command twin and Diagnostics say why.
        if (trigger && !fired && !changed) return CommandResult.rejected("dropped: the pulse of the last fire is still on");
        return changed || fired ? CommandResult.applied() : CommandResult.unchanged();
    }

    // The settings with one of the kind's schedule settings changed; empty for a key the kind has not or a value that does not fit.
    private Optional<Settings> setting(Settings current, String key, CommandReader in) {
        if (type.setting(key).isEmpty()) return Optional.empty();
        return switch (key) {
            case "interval" -> in.get(Timers.INTERVAL).map(interval -> current.withInterval(interval, current.unit()));
            case "unit" -> in.get(Timers.UNIT).map(unit -> current.withInterval(current.interval(), unit));
            case "trigger_at" -> in.get(Timers.TRIGGER_AT).map(current::withTriggerAt);
            // The same HH:MM keeps the exact tick a v1 timer had; another one is converted.
            case "time" -> in.get(Timers.TIME).map(time -> time.equals(current.time()) ? current
                    : current.withTimeTicks(GameClock.parse(time).orElseThrow()));
            case "cron" -> in.get(Timers.CRON).map(current::withCron);
            default -> Optional.<Settings>empty();
        };
    }

    // Why a key of a command was rejected.
    private String problem(String key, CommandReader in) {
        if (type.setting(key).isEmpty()) return "not a " + type.model() + " command: " + key;
        if (key.equals(Timers.TIME.key())) return "not a time as HH:MM: " + in.text(key).orElse("?");
        if (key.equals(Timers.CRON.key())) return in.text(key).flatMap(CronTimerSchedule::problem).orElse("not a cron expression");
        Field<?> field = type.setting(key).orElseThrow().field();
        return "not a " + field.kind().describe() + ": " + key;
    }

    // New settings: enabling arms the schedule without a fire, an interval or unit change starts the count again.
    private void changeSettings(Settings next, TimerNow now) {
        boolean armed = !settings.enabled() && next.enabled();
        boolean restarted = next.interval() != settings.interval() || next.unit() != settings.unit();
        settings = next;
        schedule = settings.schedule(kind);
        if (restarted) counters = IntervalSchedule.restarted(counters);
        if (armed) counters = schedule.armed(now, counters);
        setChanged();
        refreshNextFire(now);
    }

    /**
     * Fires: a pulse of the kind's length starts, and {@code fired} is published with its source. A manual fire is
     * dropped while a pulse is on; a scheduled one comes only when none is ({@link TimerStep}).
     *
     * @return whether it fired
     */
    private boolean fire(Timers.Source source, TimerNow now) {
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof TimerBlock block)) return false;
        if (source == Timers.Source.MANUAL) {
            Optional<TimerState> triggered = TimerStep.trigger(state.getValue(POWER) > 0, now, counters);
            if (triggered.isEmpty()) return false;
            counters = triggered.get();
        }
        setPower(block, state, 15);
        setChanged();
        // The event and the Raw Link's payload under one throttle decision (D3): at most every 250 ms, the bus gets each.
        link.publishEvent(this, Timers.fired(source), Timers.RAW_PAYLOAD);
        if (refreshNextFire(now)) link.stateChanged(this);
        return true;
    }

    private void setPower(TimerBlock block, BlockState state, int power) {
        BlockState powered = state.setValue(POWER, power);
        level.setBlock(worldPosition, powered, Block.UPDATE_ALL);
        block.updateNeighbors(level, worldPosition, powered);
    }

    /**
     * What the timer reads now: the game time, the overworld clock, whether it runs and how fast, the wall clock in the
     * server's zone and the tick rate.
     */
    private TimerNow now() {
        if (level == null) return new TimerNow(0, 0, Instant.now(), ZoneId.systemDefault(), 20, true);
        return new TimerNow(level.getGameTime(), WorldClock.overworldTicks(level), Instant.now(), ZoneId.systemDefault(),
                level.tickRateManager().tickrate(), level.tickRateManager().isFrozen(), WorldClock.running(level), WorldClock.rate(level));
    }

    /**
     * The overworld clock stopped, started or changed its rate, or the server's ticks froze or thawed (the runtime's watch,
     * which runs while no block entity ticks): the next fire is computed again at once, so a Daylight Timer or Alarm Clock
     * shows none while the clock stands and every tick-based kind none while frozen, and shows it again when the clock
     * runs.
     */
    public void clockChanged() {
        if (level != null && refreshNextFire(now())) link.stateChanged(this);
    }

    // The next fire the state shows: computed in a level, empty before.
    private String nextFire() {
        if (nextFire == null) {
            if (level == null) return NextFire.NONE;
            refreshNextFire(now());
        }
        return nextFire;
    }

    /**
     * Computes the next fire: none while disabled (D2), else the schedule's. Recomputed on a fire, a settings or enable
     * change, each attach and every {@link NextFire#RESYNC}; the state shows a new one only when it moved by more than
     * {@link NextFire#DEADBAND}, or appears or goes.
     *
     * @return whether the state's next fire changed
     */
    private boolean refreshNextFire(TimerNow now) {
        String next = settings.enabled() ? NextFire.format(schedule.next(now, counters)) : NextFire.NONE;
        nextFireComputed = now.wallClock().toEpochMilli();
        if (nextFire != null && !NextFire.moved(nextFire, next)) return false;
        nextFire = next;
        return true;
    }

    @Override
    protected void saveDevice(ValueOutput output) {
        saveTimer(link, kind, settings, counters, output);
    }

    @Override
    protected void loadDevice(ValueInput input) {
        LegacyNbtV1.Timer loaded = loadTimer(link, kind, input);
        if (loaded.intervalValue() != loaded.settings().interval() && kind == TimerKind.INTERVAL) {
            MineQTT.LOGGER.warn("[Timer] The interval {} at {} is clamped to {}", loaded.intervalValue(), worldPosition, loaded.settings().interval());
        }
        settings = loaded.settings();
        counters = loaded.counters();
        schedule = settings.schedule(kind);
        nextFire = null;
    }

    /**
     * A placed copy keeps its settings and counts afresh: its count starts at 0, and a moment whose window is open as it
     * is placed counts as handled, so a copy does not fire at once.
     */
    @Override
    protected void placedCopy() {
        TimerNow now = now();
        counters = settings.enabled() ? schedule.armed(now, TimerState.NEW) : TimerState.NEW;
        nextFire = null;
    }

    /**
     * A timer's data: the link with the settings as its {@code state} and the counters as its {@code timer}, or v1's keys
     * while its v1 data waits for adoption, so it is still v1 data when it loads again.
     */
    public static void saveTimer(DeviceLink link, TimerKind kind, Settings settings, TimerState counters, ValueOutput output) {
        if (link.awaitingAdoption()) {
            link.saveV1(output);
            LegacyNbtV1.writeTimer(output, kind, settings, counters);
        } else {
            ValueOutput data = link.save(output);
            data.store("state", Settings.CODEC, settings);
            data.store("timer", Timers.COUNTERS_CODEC, counters);
        }
    }

    /**
     * Loads a timer's link and returns its settings and counters; v1 data, also a placed v1 item's, is kept for adoption
     * and read from the keys of its kind ({@link LegacyNbtV1#timerState}).
     */
    public static LegacyNbtV1.Timer loadTimer(DeviceLink link, TimerKind kind, ValueInput input) {
        return link.load(input, LegacyNbtV1.hasTimerKeys(input))
                .map(data -> {
                    Settings settings = data.read("state", Settings.CODEC).orElse(Settings.DEFAULT);
                    return new LegacyNbtV1.Timer(settings, data.read("timer", Timers.COUNTERS_CODEC).orElse(TimerState.NEW), settings.interval());
                })
                .orElseGet(() -> LegacyNbtV1.timerState(input, kind));
    }

    /** Counts, fires and ends pulses each tick (D2); computes the next fire again at each attach. */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (!(blockEntity instanceof TimerBlockEntity timer) || level.isClientSide() || !(state.getBlock() instanceof TimerBlock block)) return;
            TimerNow now = timer.now();
            TimerStep step = TimerStep.tick(timer.kind, timer.schedule, timer.settings.enabled(), state.getValue(POWER) > 0, now, timer.counters);
            boolean counted = !step.state().equals(timer.counters);
            timer.counters = step.state();
            switch (step.action()) {
                case FIRE -> timer.fire(Timers.Source.SCHEDULE, now);
                case PULSE_OFF -> timer.setPower(block, state, 0);
                // The count is saved with the chunk, without telling comparators of every tick.
                case NONE -> {
                    if (counted) level.blockEntityChanged(pos);
                }
            }
            // A lagging server or /time set moves a tick-based estimate: it is computed again at the attach and every minute.
            boolean attached = timer.link.attached();
            boolean resync = now.wallClock().toEpochMilli() - timer.nextFireComputed >= NextFire.RESYNC.toMillis();
            if (attached && (!timer.attachSeen || resync) && timer.refreshNextFire(now)) timer.link.stateChanged(timer);
            timer.attachSeen = attached;
        }
    }
}

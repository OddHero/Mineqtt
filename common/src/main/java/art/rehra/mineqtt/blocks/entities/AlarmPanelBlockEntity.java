package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.blocks.AlarmPanelBlock;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommandResult;
import art.rehra.mineqtt.device.CommandSource;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.AlarmPanel;
import art.rehra.mineqtt.device.types.AlarmPanel.Alarm;
import art.rehra.mineqtt.device.types.AlarmPanel.AlarmState;
import art.rehra.mineqtt.device.types.AlarmPanel.CodeHash;
import art.rehra.mineqtt.device.types.AlarmPanel.Guard;
import art.rehra.mineqtt.device.types.AlarmPanel.Settings;
import art.rehra.mineqtt.network.MineqttNetworking.AlarmKeypadPayload;
import art.rehra.mineqtt.ui.device.AlarmKeypadTab;
import art.rehra.mineqtt.ui.device.DeviceMenu;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * The Alarm Panel ({@link AlarmPanel}): its delays, where the alarm stands, the wrong-code guard and the hash of its code
 * are in the {@code mineqtt} compound, so an armed panel is armed again after an unload or a restart and a lockout goes on.
 * The code itself is never kept: only its salted hash ({@link CodeHash}), which no state, view, render update, registry
 * record or log line carries. A placed copy keeps the delays and nothing else: it is disarmed and has no code.
 * <p>
 * Every server tick it counts the exit delay, the entry delay, the siren and the lockout down; the block's
 * {@link AlarmPanelBlock#STATUS} follows the alarm. It beeps while a delay runs, and plays a short sound when it arms,
 * disarms or refuses a code, without a game event (a Vibration Sensor does not hear its own panel).
 */
public class AlarmPanelBlockEntity extends DeviceBlockEntity {
    private final DeviceLink link = new DeviceLink(AlarmPanel.TYPE);
    private Settings settings = Settings.DEFAULT;
    private Alarm alarm = Alarm.DISARMED;
    private Guard guard = Guard.NONE;
    private @Nullable CodeHash code;

    public AlarmPanelBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.ALARM_PANEL.get(), pos, blockState);
    }

    @Override
    public DeviceType deviceType() {
        return AlarmPanel.TYPE;
    }

    @Override
    public DeviceLink deviceLink() {
        return link;
    }

    @Override
    public void writeState(StateWriter out) {
        AlarmPanel.write(out, alarm, settings, guard, code != null).put(CommonFields.LOCATION, GlobalPos.of(level.dimension(), worldPosition));
    }

    @Override
    public CommandResult applyCommand(CommandReader in, CommandSource source) {
        return applyCommand(in, source, null);
    }

    /**
     * The delays ({@code exit_delay}, {@code entry_delay}, {@code siren_duration}), Home Assistant's
     * {@code {"action":...,"code":...}}, and the in-ports ({@link AlarmPanel#request}). A refusal names the reason, never
     * the code; a wrong code is counted, published as an anonymous {@code wrong_code} and refused.
     *
     * @param originModel the model of the device a wire's command came from, for {@code trigger_source}
     */
    @Override
    public CommandResult applyCommand(CommandReader in, CommandSource source, @Nullable String originModel) {
        // Only the keys: a command may carry a code.
        if (!AlarmPanel.known(in)) return CommandResult.rejected("not an alarm panel command: " + in.keys());
        Settings nextSettings = settings.apply(in);
        boolean changed = !nextSettings.equals(settings);
        settings = nextSettings;
        Optional<AlarmPanel.Request> request = AlarmPanel.request(in, source);
        CommandResult result = request.map(asked -> perform(asked, in, source, originModel)).orElse(CommandResult.unchanged());
        if (changed || result.outcome() == CommandResult.Outcome.APPLIED) {
            setChanged();
            if (level instanceof ServerLevel serverLevel) mirror(serverLevel);
            // A refusal is no applied command, whose state the runtime would publish: the delays it set still are news.
            if (result.outcome() == CommandResult.Outcome.REJECTED) link.stateChanged(this);
            return result.outcome() == CommandResult.Outcome.REJECTED ? result : CommandResult.applied();
        }
        return result;
    }

    private CommandResult perform(AlarmPanel.Request request, CommandReader in, CommandSource source, @Nullable String originModel) {
        return switch (request) {
            case AlarmPanel.Refused refused -> CommandResult.rejected(refused.reason());
            case AlarmPanel.Arm arm -> {
                if (code == null) yield CommandResult.rejected("no code is set: set one on its keypad first");
                Optional<Alarm> armed = alarm.arm(arm.mode(), settings);
                if (armed.isEmpty()) yield CommandResult.rejected("it is " + alarm.state().getSerializedName() + ": disarm it first");
                alarm = armed.get();
                link.publishEvent(this, AlarmPanel.armEvent(arm.mode()));
                sound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0F);
                yield CommandResult.applied();
            }
            case AlarmPanel.Disarm ignored -> disarm(in, source);
            case AlarmPanel.Trigger trigger -> {
                Optional<Alarm> next = alarm.trigger(trigger.zone(), originModel == null ? AlarmPanel.SOURCE_WIRE : originModel, settings);
                if (next.isEmpty()) yield CommandResult.unchanged();
                alarm = next.get();
                if (alarm.triggered()) triggered();
                yield CommandResult.applied();
            }
            case AlarmPanel.Panic ignored -> {
                if (code == null) yield CommandResult.rejected("no code is set: set one on its keypad first");
                Optional<Alarm> next = alarm.panic(AlarmPanel.SOURCE_HOME_ASSISTANT, settings);
                if (next.isEmpty()) yield CommandResult.unchanged();
                alarm = next.get();
                triggered();
                yield CommandResult.applied();
            }
        };
    }

    /**
     * Disarms: a wire without a code (its maker needed configure rights on the panel), everything else with the right
     * code under the guard. A wrong code is counted and published; a lockout and a missing code check nothing.
     */
    private CommandResult disarm(CommandReader in, CommandSource source) {
        if (source != CommandSource.BINDING) {
            AlarmPanel.Verdict verdict = AlarmPanel.check(Optional.ofNullable(code), guard, AlarmPanel.entry(in));
            switch (verdict) {
                case NO_CODE -> {
                    return alarm.state() == AlarmState.DISARMED ? CommandResult.unchanged() : CommandResult.rejected("no code is set");
                }
                case LOCKED_OUT -> {
                    return CommandResult.rejected("it takes no code for " + guard.lockoutSeconds() + " s after too many wrong codes");
                }
                case MISSING -> {
                    return CommandResult.rejected("disarming needs the code");
                }
                case WRONG -> {
                    guard = guard.wrong();
                    setChanged();
                    link.publishEvent(this, AlarmPanel.WRONG_CODE.payload());
                    sound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.6F);
                    link.stateChanged(this);
                    return CommandResult.rejected(guard.lockedOut() ? "wrong code; it takes no code for " + guard.lockoutSeconds() + " s" : "wrong code");
                }
                case RIGHT -> guard = guard.right();
            }
        }
        if (alarm.state() == AlarmState.DISARMED) {
            setChanged();
            return CommandResult.applied();
        }
        alarm = alarm.disarm();
        link.publishEvent(this, AlarmPanel.DISARMED_EVENT.payload());
        sound(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.0F);
        return CommandResult.applied();
    }

    // The alarm went off: its event names the zone and the kind of device, never who.
    private void triggered() {
        link.publishEvent(this, AlarmPanel.triggerEvent(alarm.zone(), alarm.source()));
    }

    /**
     * A keypad button from the screen of {@code player} ({@link AlarmKeypadPayload}), after the guard checked USE for the
     * entries and CONFIGURE for the code: arming and disarming go through the device's command path as the player's
     * command, so they are judged like any other; setting or clearing the code is the block's own, allowed while
     * disarmed only. Nothing here logs the digits.
     */
    public void keypad(ServerPlayer player, AlarmKeypadPayload.Op op, String digits) {
        switch (op) {
            case ARM_AWAY -> DeviceRuntime.keypad(player, this, single(AlarmPanel.ARM_AWAY));
            case ARM_HOME -> DeviceRuntime.keypad(player, this, single(AlarmPanel.ARM_HOME));
            case DISARM -> {
                JsonObject command = single(AlarmPanel.DISARM);
                command.addProperty(AlarmPanel.CODE, digits);
                DeviceRuntime.keypad(player, this, command);
            }
            case SET_CODE -> setCode(player, digits);
            case CLEAR_CODE -> clearCode(player);
        }
    }

    private static JsonObject single(String key) {
        JsonObject command = new JsonObject();
        command.addProperty(key, "PRESS");
        return command;
    }

    /** A new code, hashed with a fresh salt; only while disarmed. It ends a lockout, as the person who may set it is here. */
    private void setCode(ServerPlayer player, String digits) {
        if (alarm.state() != AlarmState.DISARMED) {
            player.sendSystemMessage(Component.translatable("mineqtt.alarm_panel.disarm_first"), true);
            return;
        }
        if (AlarmPanel.codeProblem(digits).isPresent()) {
            player.sendSystemMessage(Component.translatable("mineqtt.alarm_panel.code_format", AlarmPanel.MIN_CODE_LENGTH, AlarmPanel.MAX_CODE_LENGTH), true);
            return;
        }
        code = CodeHash.of(digits);
        guard = guard.right();
        setChanged();
        link.stateChanged(this);
        link.viewChanged(this);
        sound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.5F);
        player.sendSystemMessage(Component.translatable("mineqtt.alarm_panel.code_set"), true);
    }

    /** Removes the code; only while disarmed. Without one the panel does not arm. */
    private void clearCode(ServerPlayer player) {
        if (alarm.state() != AlarmState.DISARMED) {
            player.sendSystemMessage(Component.translatable("mineqtt.alarm_panel.disarm_first"), true);
            return;
        }
        if (code == null) return;
        code = null;
        guard = guard.right();
        setChanged();
        link.stateChanged(this);
        link.viewChanged(this);
        player.sendSystemMessage(Component.translatable("mineqtt.alarm_panel.code_cleared"), true);
    }

    /** Opens the screen on its keypad, whichever tab was open last: a right-click on a keypad is for its keys. */
    @Override
    public void openScreen(ServerPlayer player) {
        art.rehra.mineqtt.runtime.MineqttRuntime.current().filter(running -> running.server() == player.level().getServer())
                .ifPresent(running -> running.devices().deliverNotices(player));
        DeviceMenu.open(player, this, AlarmKeypadTab.ID, null);
    }

    /** Where the alarm stands; read by the game tests. */
    public AlarmState alarmState() {
        return alarm.state();
    }

    /** Whether a code is set; read by the game tests. Never the code. */
    public boolean codeSet() {
        return code != null;
    }

    // One server tick: the countdowns and the light; a beep each second of a delay.
    private void tick(ServerLevel level) {
        Alarm before = alarm;
        Guard guardBefore = guard;
        alarm = alarm.tick(settings);
        guard = guard.tick();
        if (alarm.state() != before.state() && alarm.triggered()) triggered();
        if ((alarm.state() == AlarmState.ARMING || alarm.state() == AlarmState.PENDING) && alarm.left() % AlarmPanel.TICKS_PER_SECOND == 0) {
            sound(SoundEvents.NOTE_BLOCK_HAT.value(), alarm.state() == AlarmState.PENDING ? 1.6F : 1.2F);
        }
        if (!alarm.equals(before) || !guard.equals(guardBefore)) setChanged();
        if (alarm.state() != before.state() || alarm.secondsLeft() != before.secondsLeft() || guard.lockoutSeconds() != guardBefore.lockoutSeconds()) {
            link.stateChanged(this);
        }
        mirror(level);
    }

    // The block's light follows the alarm.
    private void mirror(ServerLevel level) {
        BlockState state = getBlockState();
        AlarmPanel.Light light = AlarmPanel.Light.of(alarm.state());
        if (state.getBlock() instanceof AlarmPanelBlock && state.getValue(AlarmPanelBlock.STATUS) != light) {
            level.setBlock(worldPosition, state.setValue(AlarmPanelBlock.STATUS, light), Block.UPDATE_CLIENTS);
        }
    }

    // A sound at the panel, without a game event.
    private void sound(SoundEvent sound, float pitch) {
        if (level instanceof ServerLevel serverLevel) serverLevel.playSound(null, worldPosition, sound, SoundSource.BLOCKS, 0.6F, pitch);
    }

    @Override
    protected void saveDevice(ValueOutput output) {
        ValueOutput data = link.save(output);
        data.store("state", Settings.CODEC, settings);
        data.store("alarm", Alarm.CODEC, alarm);
        data.store("guard", Guard.CODEC, guard);
        if (code != null) data.store("code", CodeHash.CODEC, code);
    }

    @Override
    protected void loadDevice(ValueInput input) {
        Optional<ValueInput> data = link.load(input, false);
        settings = data.flatMap(device -> device.read("state", Settings.CODEC)).orElse(Settings.DEFAULT);
        alarm = data.flatMap(device -> device.read("alarm", Alarm.CODEC)).orElse(Alarm.DISARMED);
        guard = data.flatMap(device -> device.read("guard", Guard.CODEC)).orElse(Guard.NONE);
        code = data.flatMap(device -> device.read("code", CodeHash.CODEC)).orElse(null);
    }

    /** A placed copy keeps its delays; it is disarmed, has counted nothing and has no code, which its placer sets. */
    @Override
    protected void placedCopy() {
        alarm = alarm.placed();
        guard = guard.placed();
        code = null;
    }

    /** Each server tick: the delays, the siren and the lockout. */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (blockEntity instanceof AlarmPanelBlockEntity panel && level instanceof ServerLevel serverLevel) panel.tick(serverLevel);
        }
    }
}

package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommandResult;
import art.rehra.mineqtt.device.CommandSource;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.Speaker;
import art.rehra.mineqtt.device.types.Speaker.Settings;
import com.google.gson.JsonElement;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * The Speaker ({@link Speaker}): its settings and what its announcements did are in the {@code mineqtt} compound; a run
 * (on, ticks left, where in the tone's pattern) lives only here, so the siren stops when the chunk unloads and is off
 * after a load. The server plays every sound at the block, at most one every {@value Speaker#MIN_SOUND_GAP_TICKS} ticks;
 * clients get no render data. Announcements go to the players in this level within the effective range, as literal
 * text behind the Speaker's name; nobody who got one is stored, published or logged.
 */
public class SpeakerBlockEntity extends DeviceBlockEntity {
    private final DeviceLink link = new DeviceLink(Speaker.TYPE);
    private final Speaker.Announcer announcer = new Speaker.Announcer(() -> System.nanoTime() / 1_000_000L);
    private Settings settings = Settings.DEFAULT;
    private boolean on;
    private int ticksLeft;
    private long runTick;
    private long lastSound = Long.MIN_VALUE / 2;
    private int sounds;
    private boolean powered;
    private boolean poweredKnown;

    public SpeakerBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.SPEAKER.get(), pos, blockState);
    }

    @Override
    public DeviceType deviceType() {
        return Speaker.TYPE;
    }

    @Override
    public DeviceLink deviceLink() {
        return link;
    }

    @Override
    public void writeState(StateWriter out) {
        settings.write(out, on, ticksLeft).put(CommonFields.LOCATION, GlobalPos.of(level.dimension(), worldPosition));
    }

    /**
     * {@code state} ON, OFF or TOGGLE (a run of the command's {@code duration}, else the setting; on again, the run starts
     * over from its full length and the pattern goes on), {@code tone}, {@code volume} or {@code volume_level},
     * {@code duration}, {@code range}, and {@code announce}.
     */
    @Override
    public CommandResult applyCommand(CommandReader in, CommandSource source) {
        Optional<Boolean> switchTo = Speaker.switchTo(in, on);
        boolean turnsOn = switchTo.orElse(false);
        Settings next = settings.apply(in, turnsOn);
        boolean known = switchTo.isPresent() || in.has(Speaker.TONE.key()) || in.has(Speaker.VOLUME.key()) || in.has(Speaker.VOLUME_LEVEL)
                || in.has(Speaker.DURATION.key()) || in.has(Speaker.RANGE.key()) || in.has(Speaker.ANNOUNCE);
        if (!known) return CommandResult.rejected("not a speaker command: " + in.keys());
        boolean changed = !next.equals(settings);
        settings = next;
        if (switchTo.isPresent()) {
            if (turnsOn) {
                start(Speaker.runTicks(in.get(Speaker.DURATION), settings.duration()));
                changed = true;
            } else if (on) {
                stop();
                changed = true;
            }
        }
        Optional<String> refused = Optional.empty();
        JsonElement announcement = in.json().get(Speaker.ANNOUNCE);
        if (announcement != null) {
            Speaker.Announcement result = Speaker.announcementText(announcement)
                    .map(text -> announcer.announce(text, MineQTTConfig.speakerAllowAnnouncements))
                    .orElseGet(() -> new Speaker.Refused(Speaker.REFUSED_NOT_TEXT));
            if (result instanceof Speaker.Shown shown && level instanceof ServerLevel serverLevel) show(serverLevel, shown.text(), source);
            if (result instanceof Speaker.Refused refusal) refused = Optional.of(refusal.reason());
            settings = settings.announced(result);
            changed = true;
        }
        if (changed) setChanged();
        if (refused.isPresent()) {
            // The refusal is in last_result; the runtime publishes only an applied command's state.
            link.stateChanged(this);
            return CommandResult.rejected(refused.get());
        }
        return changed ? CommandResult.applied() : CommandResult.unchanged();
    }

    /** How many sounds the Speaker played since it was loaded; read by the game tests, as sound is not observable. */
    public int soundsPlayed() {
        return sounds;
    }

    /** Whether the siren sounds. */
    public boolean sounding() {
        return on;
    }

    /**
     * The redstone signal at the block: a rise turns the siren on for its duration. The first reading after a load or a
     * placement only learns the signal, so a powered Speaker does not sound again when its chunk loads.
     */
    public void signalChanged(boolean signal) {
        boolean rising = poweredKnown && signal && !powered;
        powered = signal;
        poweredKnown = true;
        if (!rising) return;
        start(Speaker.runTicks(Optional.empty(), settings.duration()));
        setChanged();
        link.stateChanged(this);
    }

    private void start(int ticks) {
        if (!on) runTick = 0;
        on = true;
        ticksLeft = ticks;
    }

    private void stop() {
        on = false;
        ticksLeft = 0;
        runTick = 0;
    }

    // One tick of a run: the notes due now (one every few ticks at most), the duration counted down, the state each second.
    private void tick(ServerLevel level) {
        if (!poweredKnown) signalChanged(level.hasNeighborSignal(worldPosition));
        if (!on) return;
        long now = level.getGameTime();
        for (Speaker.Note note : settings.tone().notesAt(runTick)) {
            if (Speaker.soundDue(now - lastSound)) play(level, note, now);
        }
        runTick++;
        int secondsBefore = Speaker.durationLeft(ticksLeft);
        ticksLeft--;
        if (ticksLeft <= 0) {
            stop();
            setChanged();
            link.stateChanged(this);
        } else if (Speaker.durationLeft(ticksLeft) != secondsBefore) {
            // Published once it drifted by the deadband; wires see every second.
            link.stateChanged(this);
        }
    }

    private void play(ServerLevel level, Speaker.Note note, long now) {
        float volume = Speaker.soundVolume(settings.volume(), effectiveRange());
        if (volume <= 0) return;
        double x = worldPosition.getX() + 0.5;
        double y = worldPosition.getY() + 0.5;
        double z = worldPosition.getZ() + 0.5;
        // Under the client's Jukebox/Note Blocks volume, as a note block's, so a player can turn Speakers down.
        level.playSound(null, x, y, z, soundEvent(note.instrument()), SoundSource.RECORDS, volume, note.pitch());
        lastSound = now;
        sounds++;
    }

    /** The vanilla sound of an instrument: the note block's, or the village bell's. */
    public static SoundEvent soundEvent(Speaker.Instrument instrument) {
        return switch (instrument) {
            case BIT -> SoundEvents.NOTE_BLOCK_BIT.value();
            case CHIME -> SoundEvents.NOTE_BLOCK_CHIME.value();
            case BELL -> SoundEvents.NOTE_BLOCK_BELL.value();
            case PLING -> SoundEvents.NOTE_BLOCK_PLING.value();
            case VILLAGE_BELL -> SoundEvents.BELL_BLOCK;
        };
    }

    private int effectiveRange() {
        return Speaker.effectiveRange(settings.range(), MineQTTConfig.speakerMaxRange);
    }

    /**
     * Shows an accepted announcement to every player in this level within the effective range: {@code [<name>] <text>},
     * both literal, in the action bar or, with {@code speaker.announceInChat}, the chat. The text is logged with the
     * Speaker and where it came from, never with who got it.
     */
    private void show(ServerLevel level, String text, CommandSource source) {
        Component message = Component.translatableWithFallback("mineqtt.speaker.announcement", "[%s] %s",
                Component.literal(link.name()).withStyle(ChatFormatting.DARK_AQUA), Component.literal(text));
        boolean overlay = !MineQTTConfig.speakerAnnounceInChat;
        Vec3 center = Vec3.atCenterOf(worldPosition);
        double range = effectiveRange();
        for (ServerPlayer player : level.players()) {
            if (player.position().distanceToSqr(center) <= range * range) player.sendSystemMessage(message, overlay);
        }
        MineQTT.LOGGER.info("Speaker {} announced from {}: {}", link.slug(), source, text);
    }

    @Override
    public void setRemoved() {
        // Unload, a replaced block or a broken one: the run ends with it and is never resumed.
        stop();
        super.setRemoved();
    }

    @Override
    protected void saveDevice(ValueOutput output) {
        link.save(output).store("state", Settings.CODEC, settings);
    }

    @Override
    protected void loadDevice(ValueInput input) {
        settings = link.load(input, false).flatMap(data -> data.read("state", Settings.CODEC)).orElse(Settings.DEFAULT);
    }

    /** A placed copy keeps its settings, is off and has announced nothing yet. */
    @Override
    protected void placedCopy() {
        settings = settings.placed();
        stop();
    }

    /** Each server tick: the siren's pattern and duration. */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (blockEntity instanceof SpeakerBlockEntity speaker && level instanceof ServerLevel serverLevel) speaker.tick(serverLevel);
        }
    }
}

package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.compat.ChunkPositions;
import art.rehra.mineqtt.blocks.RgbLedBlock;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommandResult;
import art.rehra.mineqtt.device.CommandSource;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.RenderTag;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.device.ValueCodecs.LightEffect;
import art.rehra.mineqtt.device.ValueCodecs.LightState;
import art.rehra.mineqtt.device.ValueCodecs.LightTransition;
import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.legacy.LegacyNbtV1;
import art.rehra.mineqtt.twin.core.TwinHealth;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * The Smart Lamp ({@link SmartLamp}): an 8-bit colour or colour temperature with a brightness, which also sets the
 * block light. Its state is in the {@code mineqtt} compound; v1 data is read on load and adopted when the device
 * attaches.
 * <p>
 * A command's {@code transition} fades the lamp: its state, saved and published, is the target at once, while the block
 * shows the way there ({@link LightTransition}), stepped by the server ticker at most every
 * {@value SmartLamp#TRANSITION_STEP_TICKS} ticks, which also keeps the {@code light_level} block state in step. Clients
 * get what the block shows alone ({@link RenderTag#lamp}), for the tint, the particles and the effect, which the client
 * ticker animates. Nothing here touches MQTT on the client.
 */
public class RgbLedBlockEntity extends DeviceBlockEntity {
    // A lamp that is off still shows its colour, dimmed to this share.
    private static final float OFF_TINT = 0.3f;
    // How far towards grey a mirrored lamp's tint goes while its source has no value.
    private static final float UNAVAILABLE_DESATURATION = 0.75f;
    private static final Set<String> LIGHT_KEYS = Set.of("state", "brightness", "color", "color_temp", "color_temp_kelvin", "effect", "transition");

    private final DeviceLink link = new DeviceLink(SmartLamp.TYPE);
    // The lamp's state: saved, published and shown to screens.
    private LightState light = LightState.DEFAULT;
    // What the block shows: the light, or a step of the transition towards it. Never saved: a lamp loads as its light.
    private LightState shown = LightState.DEFAULT;
    private @Nullable LightTransition transition;
    private long lastStep;
    // Client only: the share of the tint the effect shows now.
    private float effectFactor = 1;

    public RgbLedBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.RGB_LED_BLOCK.get(), pos, blockState);
    }

    /** The block light the lamp gives now; during a transition a step on the way to its state's. */
    public int getLightLevel() {
        return SmartLamp.lightLevel(shown);
    }

    /**
     * The colour the block is tinted with: the colour scaled by the emitted light level, so the tint changes in the
     * same 16 steps as the light, and by the effect's share on a client; dimmed while off.
     */
    public int tint() {
        float effect = shown.effect() == LightEffect.NONE ? 1 : effectFactor;
        float factor = shown.on() ? getLightLevel() / 15.0f * effect : OFF_TINT;
        int r = scale(shown.color().r(), factor), g = scale(shown.color().g(), factor), b = scale(shown.color().b(), factor);
        if (twinHint() != TwinHealth.Hint.UNAVAILABLE) return (r << 16) | (g << 8) | b;
        // A mirrored lamp whose source has no value holds it, desaturated (M3 plan D6, D19).
        int grey = Math.round(0.299f * r + 0.587f * g + 0.114f * b);
        return (desaturate(r, grey) << 16) | (desaturate(g, grey) << 8) | desaturate(b, grey);
    }

    // A channel moved most of the way to grey.
    private static int desaturate(int channel, int grey) {
        return Math.round(channel + (grey - channel) * UNAVAILABLE_DESATURATION);
    }

    /** One red particle above the bulb as the lamp's binding becomes refused (M3 plan D19). */
    @Override
    protected void twinHintChanged(TwinHealth.Hint before) {
        if (level != null && level.isClientSide() && twinHint() == TwinHealth.Hint.REFUSED) refusedParticle(level, RgbLedBlock.bulbCentre(getBlockState(), worldPosition));
    }

    @Override
    public DeviceType deviceType() {
        return SmartLamp.TYPE;
    }

    @Override
    public DeviceLink deviceLink() {
        return link;
    }

    @Override
    public void writeState(StateWriter out) {
        // The target's light level, also while the block fades towards it: one state per command.
        out.put(SmartLamp.LIGHT, light)
                .put(SmartLamp.LIGHT_LEVEL, SmartLamp.lightLevel(light))
                .put(CommonFields.LOCATION, GlobalPos.of(level.dimension(), worldPosition));
    }

    /**
     * Merges a light command. With a {@code transition} the block fades from what it shows now to the new light; a new
     * command during a fade starts its own from where the fade stands, and one without a transition ends it at once,
     * also when it names the light the fade is heading for (no state changes then, so nothing is published).
     */
    @Override
    public CommandResult applyCommand(CommandReader in, CommandSource source) {
        if (in.keys().stream().noneMatch(LIGHT_KEYS::contains)) return CommandResult.rejected("not a light command: " + in.keys());
        LightState next = light.merge(in.json());
        boolean changed = !next.equals(light);
        if (!changed && transition == null) return CommandResult.unchanged();
        light = next;
        fadeTo(ValueCodecs.transitionTicks(in.json()));
        if (!changed) return CommandResult.unchanged();
        setChanged();
        return CommandResult.applied();
    }

    @Override
    protected void saveDevice(ValueOutput output) {
        saveLamp(link, light, output);
    }

    @Override
    protected void loadDevice(ValueInput input) {
        light = loadLamp(link, input);
        showLight();
    }

    /** A client's render update: what the block shows. */
    @Override
    protected void loadRender(ValueInput input) {
        light = RenderTag.lamp(input);
        showLight();
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        // A new colour at the same light level changes no block state; the client redraws the tint itself.
        if (level != null && level.isClientSide()) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
    }

    /** Colour, brightness, on and effect as the block shows them: the tint, the particles and the animation. */
    @Override
    protected CompoundTag renderData() {
        return RenderTag.lamp(shown);
    }

    /** A placed copy keeps its colour, brightness and effect, switched off. */
    @Override
    protected void placedCopy() {
        light = light.placed();
        showLight();
        updateLightLevel();
    }

    // The block shows the light itself, without a transition.
    private void showLight() {
        shown = light;
        transition = null;
    }

    /**
     * Shows the light, over {@code ticks} from what the block shows now, or at once. Only where the ticker runs, in a
     * block-ticking chunk with its entities loaded (the rule of {@code LevelChunk#isTicking}): elsewhere, as between the
     * simulation and the view distance, a fade would stand still at its start, so the block shows the light at once.
     */
    private void fadeTo(int ticks) {
        if (ticks > 0 && level instanceof ServerLevel server && ChunkPositions.isEntityTicking(server, worldPosition)) {
            transition = new LightTransition(shown, light, server.getGameTime(), ticks);
            lastStep = transition.start();
            shown = transition.at(lastStep);
        } else {
            showLight();
        }
        updateLightLevel();
    }

    /**
     * Moves the transition on, at most every {@value SmartLamp#TRANSITION_STEP_TICKS} ticks; its last step shows the
     * light itself. Clients are sent a step only when they would draw it differently, and always the last.
     */
    private void stepTransition(long now) {
        if (transition == null || now - lastStep < SmartLamp.TRANSITION_STEP_TICKS && !transition.done(now)) return;
        lastStep = now;
        LightState next;
        boolean done = transition.done(now);
        if (done) {
            next = light;
            transition = null;
        } else {
            next = transition.at(now);
        }
        boolean redraw = done || drawnDifferently(next, shown);
        shown = next;
        if (redraw) updateLightLevel();
    }

    // The tint follows the light level, not every step of the brightness.
    private static boolean drawnDifferently(LightState a, LightState b) {
        return SmartLamp.lightLevel(a) != SmartLamp.lightLevel(b) || a.on() != b.on() || !a.color().equals(b.color()) || a.effect() != b.effect();
    }

    /**
     * On a client: moves the effect's share of the tint on every {@value SmartLamp#EFFECT_STEP_TICKS} ticks, and redraws
     * the block when it changed a step, since the tint is part of the chunk's mesh.
     */
    private void animateEffect(long time) {
        if (level == null || shown.effect() == LightEffect.NONE || getLightLevel() == 0 || time % SmartLamp.EFFECT_STEP_TICKS != 0) return;
        float factor = SmartLamp.effectFactor(shown.effect(), time, worldPosition.asLong());
        if (factor == effectFactor) return;
        effectFactor = factor;
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
    }

    /**
     * A lamp's data: the link with the light as its {@code state}, or the v1 keys while its v1 data waits for adoption,
     * so it is still v1 data when it loads again.
     */
    public static void saveLamp(DeviceLink link, LightState light, ValueOutput output) {
        if (link.awaitingAdoption()) {
            link.saveV1(output);
            LegacyNbtV1.writeSubscribed(output, link.pendingV1().combinedTopic());
            LegacyNbtV1.writeLamp(output, light);
        } else {
            link.save(output).store("state", SmartLamp.LIGHT.codec(), light);
        }
    }

    /** Loads a lamp's link and returns its light; v1 data, also a placed v1 item's, is kept for adoption. */
    public static LightState loadLamp(DeviceLink link, ValueInput input) {
        return link.load(input, LegacyNbtV1.hasLampKeys(input))
                .map(data -> data.read("state", SmartLamp.LIGHT.codec()).orElse(LightState.DEFAULT))
                .orElseGet(() -> LegacyNbtV1.lampState(input));
    }

    // The block light follows the brightness; changing it keeps this block entity.
    private void updateLightLevel() {
        if (level == null) return;
        BlockState state = getBlockState();
        int lightLevel = getLightLevel();
        if (state.getBlock() instanceof RgbLedBlock && state.getValue(RgbLedBlock.LIGHT_LEVEL) != lightLevel) {
            level.setBlock(worldPosition, state.setValue(RgbLedBlock.LIGHT_LEVEL, lightLevel), Block.UPDATE_ALL);
        } else {
            // Same light level: the clients still need the new tint.
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    private static int scale(int channel, float factor) {
        return Math.round(channel * factor);
    }

    /** The transition and the block light. */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (!(blockEntity instanceof RgbLedBlockEntity lamp) || level.isClientSide()) return;
            lamp.stepTransition(level.getGameTime());
            // v1 lamps derived the light level from the colour; the brightness decides it now.
            if (state.getValue(RgbLedBlock.LIGHT_LEVEL) != lamp.getLightLevel()) lamp.updateLightLevel();
        }
    }

    /** The effect, and coloured particles around a lit bulb; never MQTT. */
    public static class ClientTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (!(blockEntity instanceof RgbLedBlockEntity lamp)) return;
            lamp.animateEffect(level.getGameTime());
            twinParticles(level, RgbLedBlock.bulbCentre(state, pos), lamp.twinHint(), level.getGameTime());
            if (lamp.twinPending()) pendingParticles(level, RgbLedBlock.bulbCentre(state, pos), level.getGameTime());
            if (lamp.getLightLevel() == 0) return;
            // Every 10 ticks (0.5 seconds), unless the colour is black.
            int color = lamp.tint();
            if (level.getGameTime() % 10 != 0 || color == 0) return;
            Vec3 bulb = RgbLedBlock.bulbCentre(state, pos);
            int particleCount = level.getRandom().nextInt(2) + 2;
            for (int i = 0; i < particleCount; i++) {
                double offsetX = (level.getRandom().nextDouble() - 0.5) * 0.3;
                double offsetY = (level.getRandom().nextDouble() - 0.5) * 0.3;
                double offsetZ = (level.getRandom().nextDouble() - 0.5) * 0.3;
                // Small upward velocity for a glow effect.
                double velocityY = level.getRandom().nextDouble() * 0.02;
                level.addParticle(art.rehra.mineqtt.compat.Particles.dust(color, 0.8f), bulb.x + offsetX, bulb.y + offsetY, bulb.z + offsetZ, 0, velocityY, 0);
            }
        }
    }
}

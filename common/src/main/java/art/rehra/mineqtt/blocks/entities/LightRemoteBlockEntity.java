package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.blocks.LightRemoteBlock;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommandResult;
import art.rehra.mineqtt.device.CommandSource;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.WallSwitch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Wall Switch ({@link WallSwitch}), at the block entity id v1's Light Remote had: its two buttons fire events.
 * A hand's right-clicks go through {@link WallSwitch.Gestures}, which tells a press, a double press and a hold apart;
 * the {@code press_left}/{@code press_right} actions are a press at once. The block shows a button pressed while it is
 * held, and for a moment after an action pressed it. It keeps no state of its own; v1 data (the Light Remote's topic
 * slots) is adopted when it attaches, and its topic wires it to the lamps it commanded (M2 plan D15).
 */
public class LightRemoteBlockEntity extends DeviceBlockEntity {
    /** How long an action shows its button pressed: as long as a vanilla stone button. */
    public static final int FLASH_TICKS = 20;

    private final DeviceLink link = new DeviceLink(WallSwitch.TYPE);
    // What the hands are doing; never saved, as a gesture under way at an unload is no gesture.
    private final WallSwitch.Gestures gestures = new WallSwitch.Gestures();
    private final Map<WallSwitch.Button, Long> flashUntil = new EnumMap<>(WallSwitch.Button.class);

    public LightRemoteBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.LIGHT_REMOTE_BLOCK.get(), pos, blockState);
    }

    @Override
    public DeviceType deviceType() {
        return WallSwitch.TYPE;
    }

    @Override
    public DeviceLink deviceLink() {
        return link;
    }

    @Override
    public void writeState(StateWriter out) {
        out.put(CommonFields.LOCATION, GlobalPos.of(level.dimension(), worldPosition));
    }

    /** {@code press_left}, {@code press_right}: each button presses at once, as a single press. */
    @Override
    public CommandResult applyCommand(CommandReader in, CommandSource source) {
        Optional<List<WallSwitch.Button>> pressed = WallSwitch.presses(in);
        if (pressed.isEmpty()) return CommandResult.rejected("not a Wall Switch command: " + in.keys());
        for (WallSwitch.Button button : pressed.get()) {
            flashUntil.put(button, level.getGameTime() + FLASH_TICKS);
            click(button);
            fire(WallSwitch.Gesture.PRESS, button);
        }
        showPressed();
        return CommandResult.applied();
    }

    /** A player's right-click on a button; whoever may use the switch may press it. */
    public void usedByHand(WallSwitch.Button button) {
        if (level == null) return;
        long now = level.getGameTime();
        if (!gestures.down(button, now)) click(button);
        gestures.use(button, now).ifPresent(gesture -> fire(gesture, button));
        showPressed();
    }

    // The gesture's event and Raw Link payload, then its button's bus-only event (M3 plan D16), which only wires and the
    // button's twin target carry.
    private void fire(WallSwitch.Gesture gesture, WallSwitch.Button button) {
        link.publishEvent(this, WallSwitch.event(gesture, button));
        link.publishRaw(this, WallSwitch.rawPayload(gesture, button));
        link.publishEvent(this, WallSwitch.buttonEvent(gesture, button));
    }

    private void click(WallSwitch.Button button) {
        level.playSound(null, worldPosition, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.3F, button == WallSwitch.Button.LEFT ? 0.6F : 0.65F);
    }

    // The block shows each button pressed while it is held down or an action's moment lasts.
    private void showPressed() {
        long now = level.getGameTime();
        BlockState state = getBlockState();
        BlockState shown = state
                .setValue(LightRemoteBlock.LEFT, pressed(WallSwitch.Button.LEFT, now))
                .setValue(LightRemoteBlock.RIGHT, pressed(WallSwitch.Button.RIGHT, now));
        if (shown != state) level.setBlock(worldPosition, shown, Block.UPDATE_CLIENTS);
    }

    private boolean pressed(WallSwitch.Button button, long now) {
        return gestures.down(button, now) || now < flashUntil.getOrDefault(button, Long.MIN_VALUE);
    }

    @Override
    protected void saveDevice(ValueOutput output) {
        saveSwitch(link, output);
    }

    @Override
    protected void loadDevice(ValueInput input) {
        loadSwitch(link, input);
    }

    /** A placed copy starts with no gesture under way and both buttons up. */
    @Override
    protected void placedCopy() {
        gestures.clear();
        flashUntil.clear();
    }

    /**
     * A switch's data: the link, which is all it keeps. v1 Light Remotes wrote no keys of their own, so v1 data waiting
     * for adoption writes only the keys every v1 device wrote.
     */
    public static void saveSwitch(DeviceLink link, ValueOutput output) {
        if (link.awaitingAdoption()) {
            link.saveV1(output);
        } else {
            link.save(output);
        }
    }

    /**
     * Loads a switch's link; v1 data, also a placed v1 item's, is kept for adoption. A v1 Light Remote wrote no key of its
     * own, so a {@code mineqtt} compound without a device id is no device's.
     */
    public static void loadSwitch(DeviceLink link, ValueInput input) {
        link.load(input, true);
    }

    /** Tells the gestures whose time is over and lets go of buttons no longer pressed. */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (!(blockEntity instanceof LightRemoteBlockEntity wallSwitch) || level.isClientSide()) return;
            boolean shownPressed = state.getValue(LightRemoteBlock.LEFT) || state.getValue(LightRemoteBlock.RIGHT);
            if (wallSwitch.gestures.idle() && !shownPressed) return;
            wallSwitch.gestures.tick(level.getGameTime()).forEach((button, gesture) -> wallSwitch.fire(gesture, button));
            wallSwitch.showPressed();
        }
    }
}

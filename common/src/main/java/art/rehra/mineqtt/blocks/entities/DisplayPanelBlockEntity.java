package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.blocks.DisplayPanelBlock;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommandResult;
import art.rehra.mineqtt.device.CommandSource;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.RenderTag;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.DisplayPanel;
import art.rehra.mineqtt.device.types.DisplayPanel.Panel;
import art.rehra.mineqtt.twin.core.Binding;
import art.rehra.mineqtt.twin.core.TwinHealth;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * The Display Panel ({@link DisplayPanel}): a message, its look, and the comparator level of what it shows. Its state is
 * in the {@code mineqtt} compound. Clients get the finished lines alone ({@link DisplayPanel.Render},
 * {@link RenderTag#panel}), at most {@value #RENDER_INTERVAL_TICKS} ticks apart (4 a second, the last one always sent),
 * and never the entity it mirrors.
 * <p>
 * Mirrored, the panel shows its device name small over the entity's value, and its twin apply brings the level read
 * with the entity's range or options. While the source has no value (waiting, unavailable, unknown, no longer shared)
 * the level is 0 and clients show why; the level read last comes back with the source's next value.
 */
public class DisplayPanelBlockEntity extends DeviceBlockEntity {
    /** The fewest ticks between two render updates: at most 4 a second (M3 plan D18). */
    public static final int RENDER_INTERVAL_TICKS = 5;

    private final DeviceLink link = new DeviceLink(DisplayPanel.TYPE);
    private Panel panel = Panel.DEFAULT;
    // The twin health the runtime shows; null while unbound or before the attach. Never saved.
    private @Nullable TwinHealth health;
    // Server: when the last render update went out, and whether one waits for the interval.
    private long lastRender = -RENDER_INTERVAL_TICKS;
    private boolean renderPending;
    // Client: what the last render update carried.
    private DisplayPanel.Render shown = DisplayPanel.Render.of(Panel.DEFAULT, null, false);

    public DisplayPanelBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.DISPLAY_PANEL.get(), pos, blockState);
    }

    @Override
    public DeviceType deviceType() {
        return DisplayPanel.TYPE;
    }

    @Override
    public DeviceLink deviceLink() {
        return link;
    }

    /** The panel's message and look as saved and published. */
    public Panel panel() {
        return panel;
    }

    /** Client: what the server last sent to draw. */
    public DisplayPanel.Render shown() {
        return shown;
    }

    /**
     * The level a comparator reads, the {@code level} field: the panel's level, or 0 while it mirrors a source that has
     * no value now.
     */
    public int comparatorLevel() {
        return noValue(health) ? 0 : panel.level();
    }

    @Override
    public void writeState(StateWriter out) {
        panel.withLevel(comparatorLevel()).write(out).put(CommonFields.LOCATION, GlobalPos.of(level.dimension(), worldPosition));
    }

    /**
     * {@code message}, {@code color}, {@code glow}, {@code size}. Only the panel's own twin apply may bring the level it
     * read from the entity ({@link Panel#apply}); a command from anywhere else gets the level of its message.
     */
    @Override
    public CommandResult applyCommand(CommandReader in, CommandSource source) {
        Optional<Panel> next = panel.apply(in, source == CommandSource.TWIN);
        if (next.isEmpty()) return CommandResult.rejected("not a Display Panel command: " + in.keys());
        if (next.get().equals(panel)) return CommandResult.unchanged();
        setPanel(next.get());
        return CommandResult.applied();
    }

    /**
     * Its mirrored entity's health: the comparator, and what clients show, follow it. Bound, the panel shows its name over
     * the value; unbound again, it keeps the message and its level is the message's own again.
     */
    @Override
    protected void twinHealthChanged(@Nullable TwinHealth health) {
        int before = comparatorLevel();
        boolean waitingBefore = waiting();
        boolean boundBefore = this.health != null;
        this.health = health;
        if (boundBefore && health == null && panel.level() != DisplayPanel.level(panel.message())) {
            panel = panel.withLevel(DisplayPanel.level(panel.message()));
            setChanged();
        }
        if (comparatorLevel() != before) updateComparators();
        if (waiting() != waitingBefore || boundBefore != (health != null)) renderChanged();
    }

    /** A mirrored panel's title is its name. */
    @Override
    public void identityChanged() {
        if (mirrors()) renderChanged();
    }

    @Override
    protected void saveDevice(ValueOutput output) {
        link.save(output).store("state", Panel.CODEC, panel);
    }

    @Override
    protected void loadDevice(ValueInput input) {
        panel = link.load(input, false).flatMap(data -> data.read("state", Panel.CODEC)).orElse(Panel.DEFAULT);
    }

    /** A client's render update: what the panel draws. */
    @Override
    protected void loadRender(ValueInput input) {
        shown = RenderTag.panel(input);
    }

    @Override
    protected CompoundTag renderData() {
        return RenderTag.panel(DisplayPanel.Render.of(panel, mirrors() ? link.name() : null, waiting()));
    }

    /** One red particle in front of the screen as the panel's binding becomes refused (M3 plan D19). */
    @Override
    protected void twinHintChanged(TwinHealth.Hint before) {
        if (level != null && level.isClientSide() && twinHint() == TwinHealth.Hint.REFUSED) refusedParticle(level, screenCentre(getBlockState(), worldPosition));
    }

    /** A placed copy keeps its colour, glow and size, and shows nothing. */
    @Override
    protected void placedCopy() {
        setPanel(new Panel("", panel.color(), panel.glow(), panel.size(), 0));
    }

    /**
     * The middle of the panel's screen, a little in front of it: where clients show its twin's particles, and the point a
     * renderer draws the text around.
     */
    public static Vec3 screenCentre(BlockState state, BlockPos pos) {
        Direction facing = state.hasProperty(DisplayPanelBlock.FACING) ? state.getValue(DisplayPanelBlock.FACING) : Direction.NORTH;
        double offset = 0.5 - DisplayPanelBlock.THICKNESS / 16.0 - 0.05;
        return Vec3.atCenterOf(pos).add(facing.getStepX() * -offset, 0, facing.getStepZ() * -offset);
    }

    // Whether the panel mirrors an entity: it shows its name over the value then.
    private boolean mirrors() {
        Binding binding = link.twin();
        return binding != null && binding.bound();
    }

    // Whether it mirrors an entity of which it has no value yet.
    private boolean waiting() {
        return health == TwinHealth.WAITING;
    }

    // A health in which the source has no value: the level is 0 then.
    private static boolean noValue(@Nullable TwinHealth health) {
        return health != null && (health == TwinHealth.WAITING || health.holding());
    }

    private void setPanel(Panel next) {
        int before = comparatorLevel();
        boolean sameLook = panel.message().equals(next.message()) && panel.color() == next.color() && panel.glow() == next.glow() && panel.size() == next.size();
        panel = next;
        setChanged();
        if (comparatorLevel() != before) updateComparators();
        if (!sameLook) renderChanged();
    }

    // Comparators next to the panel read its level again.
    private void updateComparators() {
        if (level != null && !level.isClientSide()) level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
    }

    /** Server: clients get what the panel shows now, at once or when the interval since the last update is over. */
    private void renderChanged() {
        if (level == null || level.isClientSide()) return;
        if (level.getGameTime() - lastRender >= RENDER_INTERVAL_TICKS) {
            sendRender();
        } else {
            renderPending = true;
        }
    }

    private void sendRender() {
        lastRender = level.getGameTime();
        renderPending = false;
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    /** Sends the render update that waited for its interval. */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (!(blockEntity instanceof DisplayPanelBlockEntity panel) || level.isClientSide()) return;
            if (panel.renderPending && level.getGameTime() - panel.lastRender >= RENDER_INTERVAL_TICKS) panel.sendRender();
        }
    }

    /** The twin's particles in front of the screen; never MQTT. */
    public static class ClientTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (!(blockEntity instanceof DisplayPanelBlockEntity panel)) return;
            twinParticles(level, screenCentre(state, pos), panel.twinHint(), level.getGameTime());
        }
    }
}

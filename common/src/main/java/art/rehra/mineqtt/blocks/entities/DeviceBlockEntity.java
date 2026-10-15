package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.compat.BlockRemoval;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.RenderTag;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.network.MineqttNetworking;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.security.DeviceAccess;
import art.rehra.mineqtt.twin.core.TwinHealth;
import art.rehra.mineqtt.ui.device.ControlsTab;
import art.rehra.mineqtt.ui.device.DeviceMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedScreenHost;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The block entity of every device (design §11.2 item 2, M2 plan M2-12): a plain block entity, no container, that hosts
 * its device by composition. Its {@link DeviceLink} holds the identity, the Raw Link and the items the old
 * configuration slots held ({@link DeviceLink#legacyItems()}), which breaking the block drops and the Diagnostics tab
 * ejects; its screen is the generated one ({@link DeviceMenu}). The subclass keeps the device's state and reads and
 * writes it next to the link.
 * <p>
 * Lifecycle: {@link #setLevel} schedules the attach, {@link #setRemoved} detaches or destroys, and
 * {@link #preRemoveSideEffects} destroys (design §6.5). Clients get only what they draw ({@link RenderTag}, M2 plan D12),
 * never the owner, the Raw Link or the legacy items; a render update replaces nothing else ({@link #loadRender}).
 */
public abstract class DeviceBlockEntity extends BlockEntity implements DeviceHost, TabbedScreenHost, BlockRemoval.SideEffects {
    /** How long after an edit its player counts as the one who made a change: 30 seconds. */
    public static final int EDITOR_NOTICE_TICKS = 30 * 20;
    // The red of a refused or paused binding's particles.
    private static final int REFUSED_RED = 0xE02020;

    // The player who last changed the device from its screen or with a paper, and the game time of that edit; never saved.
    private @Nullable UUID lastEditor;
    private long lastEditTime;
    // What clients draw of the device's twin health (M3 plan D19); never saved, the runtime sets it from the attach on.
    private TwinHealth.Hint twinHint = TwinHealth.Hint.NONE;
    // Whether a request of the device waits for its Home Assistant entity to change (M3 plan D6); never saved.
    private boolean twinPending;

    protected DeviceBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState blockState) {
        super(type, pos, blockState);
    }

    /** Writes the device: the link with the state in it, or v1's keys while v1 data waits for adoption. */
    protected abstract void saveDevice(ValueOutput output);

    /** Reads the device: the link and the state, or v1 data for the attach to adopt. */
    protected abstract void loadDevice(ValueInput input);

    /** A client's render update ({@link RenderTag#dispatch}): reads what {@link #renderData()} sent; nothing by default. */
    protected void loadRender(ValueInput input) {
    }

    /** What a client draws of the device: the render compound of its update tag; nothing by default. */
    protected CompoundTag renderData() {
        return new CompoundTag();
    }

    /**
     * A player placed a copy of the device (M2 plan D14: pick-block, or an item with block entity data): it keeps its
     * settings, and what it did is reset to off.
     */
    protected abstract void placedCopy();

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        saveDevice(output);
    }

    /** A render update replaces only what it carries; anything else is the device's data, or v1 data to adopt. */
    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        if (RenderTag.dispatch(input) == RenderTag.Data.RENDER) {
            TwinHealth.Hint before = twinHint;
            twinHint = RenderTag.twinHint(input);
            twinPending = RenderTag.twinPending(input);
            loadRender(input);
            if (twinHint != before) twinHintChanged(before);
        } else {
            loadDevice(input);
        }
    }

    /** Placement, chunk load, {@code /clone}, structures: the device attaches on the next server tick. */
    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        deviceLink().onSetLevel(this, level);
    }

    /** Unload, or a block replaced without side effects ({@code /setblock}, {@code /fill}). */
    @Override
    public void setRemoved() {
        super.setRemoved();
        deviceLink().onSetRemoved(this);
    }

    /**
     * Broken by a player or an explosion: the legacy items drop where the block stood, as a container's contents would,
     * and the device is destroyed.
     */
    //? if >=1.21.5 {
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
    //?} else {
    /*@Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
    *///?}
        if (level != null) {
            for (ItemStackWithSlot item : deviceLink().takeLegacyItems()) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), item.stack().copy());
            }
        }
        deviceLink().onPreRemoveSideEffects(this);
    }

    /**
     * From {@code Block#setPlacedBy}: the item's block entity data is applied and untrusted (design §8.6). The link
     * drops the copied identity and legacy items, and the placed copy starts off ({@link #placedCopy}).
     */
    public void placedBy(@Nullable LivingEntity placer) {
        deviceLink().onPlacedBy(this, placer);
        placedCopy();
        setChanged();
    }

    /**
     * Takes the legacy items out of the device for a player or a command to hand out; none while its v1 data waits for
     * adoption, as those slots still are its topic. Open screens see the count change.
     */
    public List<ItemStack> ejectLegacyItems() {
        if (deviceLink().awaitingAdoption()) return List.of();
        List<ItemStack> taken = deviceLink().takeLegacyItems().stream().map(item -> item.stack().copy()).toList();
        if (!taken.isEmpty()) {
            setChanged();
            deviceLink().viewChanged(this);
        }
        return taken;
    }

    /**
     * A player changed the device through its screen or a paper; they hear what came of it (a refused or moved topic)
     * for {@link #EDITOR_NOTICE_TICKS}.
     */
    public void setLastEditor(ServerPlayer player) {
        lastEditor = player.getUUID();
        lastEditTime = player.level().getGameTime();
    }

    @Override
    public @Nullable UUID recentEditor() {
        if (lastEditor == null || level == null || level.getGameTime() - lastEditTime > EDITOR_NOTICE_TICKS) return null;
        return lastEditor;
    }

    @Override
    public String getLastTabId() {
        return deviceLink().lastTab();
    }

    @Override
    public void setLastTabId(String tabId) {
        if (tabId == null || tabId.equals(deviceLink().lastTab())) return;
        deviceLink().setLastTab(tabId);
        setChanged();
    }

    /**
     * Opens the device's generated screen ({@link DeviceMenu}), whose open data carries its view (M2-7). The player gets
     * the notices about their devices that waited. A player who may not build (P2) gets its Controls tab alone; for a type
     * without controls they get no screen, and the action bar tells them why.
     */
    @Override
    public void openScreen(ServerPlayer player) {
        if (!DeviceAccess.mayBuild(player) && ControlsTab.controls(deviceType()).isEmpty()) {
            player.sendSystemMessage(Component.translatable("mineqtt.game_mode.no_controls", getBlockState().getBlock().getName()), true);
            return;
        }
        MineqttRuntime.current().filter(running -> running.server() == player.level().getServer())
                .ifPresent(running -> running.devices().deliverNotices(player));
        DeviceMenu.open(player, this);
    }

    /**
     * The access to the device changed (M2-6). Every open screen was built with what its player could do then: a player
     * who may no longer use the device loses the screen, and one who may now configure it, or no longer may, gets it
     * again as they may now.
     */
    public void accessChanged() {
        for (ServerPlayer player : MineqttNetworking.viewers(this)) {
            if (!(player.containerMenu instanceof TabbedMqttMenu menu)) continue;
            if (!DeviceAccess.check(player, this, MineqttPermission.USE)) {
                player.closeContainer();
            } else if (DeviceAccess.check(player, this, MineqttPermission.CONFIGURE) != menu.configurable()) {
                openScreen(player);
            }
        }
    }

    /** Saves the device and sends its clients the render update. */
    public void markUpdated() {
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return RenderTag.updateTag(renderData(), twinHint, twinPending);
    }

    /**
     * Server: the device's twin health changed what its clients draw (M3 plan D6, D19); they get a render update. Only the
     * hint travels, never the entity (D13 #7).
     */
    public void setTwinHint(TwinHealth.Hint hint) {
        if (hint == twinHint) return;
        twinHint = hint;
        if (level != null && !level.isClientSide()) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    /**
     * Server: the device's twin health as its runtime shows it now, null for an unbound device (M3 plan D6): its clients'
     * hint follows ({@link #setTwinHint}), and the device may react ({@link #twinHealthChanged}). Only the hint travels,
     * never the entity (D13 #7).
     */
    public void setTwinHealth(@Nullable TwinHealth health) {
        setTwinHint(health == null ? TwinHealth.Hint.NONE : health.hint());
        twinHealthChanged(health);
    }

    /**
     * Server: the device's twin health changed ({@link #setTwinHealth}), e.g. a Display Panel that shows "Waiting for Home
     * Assistant" and gives its comparator 0 while its source has no value; nothing by default.
     */
    protected void twinHealthChanged(@Nullable TwinHealth health) {
    }

    /**
     * Server: a request of the device started or stopped waiting for its Home Assistant entity to change (M3 plan D6);
     * its clients get a render update, and show a faint sparkle while it waits.
     */
    public void setTwinPending(boolean pending) {
        if (pending == twinPending) return;
        twinPending = pending;
        if (level != null && !level.isClientSide()) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    /** Whether a request of the device waits for its Home Assistant entity to change. */
    public boolean twinPending() {
        return twinPending;
    }

    /** What the device's twin health makes clients draw: {@link TwinHealth.Hint#NONE} for an unbound or healthy device. */
    public TwinHealth.Hint twinHint() {
        return twinHint;
    }

    /**
     * Client: a render update changed the twin hint; by default one red particle above the block as the binding becomes
     * refused (D19).
     */
    protected void twinHintChanged(TwinHealth.Hint before) {
        if (level != null && level.isClientSide() && twinHint == TwinHealth.Hint.REFUSED) refusedParticle(level, aboveBlock(worldPosition));
    }

    /** The middle of the top face of the block at {@code pos}, where a device's twin particles show by default. */
    protected static Vec3 aboveBlock(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
    }

    /**
     * Client: the twin's particles above a device whose block draws nothing of its own (M3 plan D19): a smoke puff while its
     * source has no value, red dust while its control is paused, a faint sparkle while a request waits for Home Assistant.
     */
    public static class TwinClientTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (!(blockEntity instanceof DeviceBlockEntity device) || device.twinHint == TwinHealth.Hint.NONE && !device.twinPending) return;
            Vec3 at = aboveBlock(pos);
            twinParticles(level, at, device.twinHint, level.getGameTime());
            if (device.twinPending) pendingParticles(level, at, level.getGameTime());
        }
    }

    /**
     * Client: the particles of a twin hint at {@code at} (D19's visual language), for a block entity's client ticker: a
     * smoke puff every 3 s while the source has no value, a red dust particle every 5 s while the write-back is paused.
     */
    protected static void twinParticles(Level level, Vec3 at, TwinHealth.Hint hint, long time) {
        switch (hint) {
            case UNAVAILABLE -> {
                if (time % 60 == 0) level.addParticle(ParticleTypes.SMOKE, at.x, at.y + 0.2, at.z, 0, 0.02, 0);
            }
            case PAUSED -> {
                if (time % 100 == 0) level.addParticle(new DustParticleOptions(REFUSED_RED, 1.0f), at.x, at.y + 0.3, at.z, 0, 0, 0);
            }
            case NONE, REFUSED -> {
            }
        }
    }

    /** Client: a faint white sparkle at {@code at} every few ticks while a request waits for Home Assistant (D19). */
    protected static void pendingParticles(Level level, Vec3 at, long time) {
        if (time % 6 != 0) return;
        double dx = (level.getRandom().nextDouble() - 0.5) * 0.4;
        double dz = (level.getRandom().nextDouble() - 0.5) * 0.4;
        level.addParticle(ParticleTypes.WHITE_ASH, at.x + dx, at.y + 0.25, at.z + dz, 0, 0.01, 0);
    }

    /** Client: one red particle above {@code at}, as a binding becomes refused (D19). */
    protected static void refusedParticle(Level level, Vec3 at) {
        level.addParticle(new DustParticleOptions(REFUSED_RED, 1.5f), at.x, at.y + 0.4, at.z, 0, 0.01, 0);
    }
}

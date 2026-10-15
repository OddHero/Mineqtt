package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.compat.ContainerItems;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.legacy.LegacyNbtV1;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.security.DeviceAccess;
import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedScreenHost;
import art.rehra.mineqtt.ui.framework.tabs.SettingsTab;
import dev.architectury.registry.menu.MenuRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The block entity of the MineQTT blocks with configuration slots: since the timers, the Block Sensor and the Block
 * Emitter became devices (M4-3, M4-9, M4-10) only the v1 humidity sensor and Static Publisher, whose v1 data it holds
 * until they convert into their device on their server's next tick ({@link HumiditySensorBlockEntity},
 * {@link StaticPublisherBlockEntity}). Neither publishes nor subscribes. Devices have no container since M2-12
 * ({@link DeviceBlockEntity}). The slots are a {@link WorldlyContainer} with no automation faces (M2 plan D7): hoppers,
 * hopper minecarts, droppers and sided item wrappers (Fabric's {@code InventoryStorage}, NeoForge's
 * {@code WorldlyContainerWrapper}) see no slot on any side, so only a player in the screen moves items into or out of
 * them.
 */
public abstract class BaseMqttBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer, TabbedScreenHost {

    public static final int INVENTORY_SIZE = 4;
    private static final int[] NO_SLOTS = new int[0];
    protected String topic = "";
    protected NonNullList<ItemStack> items;
    /**
     * Last tab the player opened on this block; persisted via NBT and selected when the server opens the screen.
     */
    protected String lastTabId = "";
    /**
     * Username of the player that placed this block. Empty if unknown (e.g. world-gen / pre-feature blocks).
     * Used as the topic prefix when {@link #privateMode} is enabled.
     */
    protected String ownerName = "";
    /**
     * The UUID of the player that placed this block, which alone makes them its owner (M2 plan D7). Null on v1 data until
     * {@link #ownerName} is resolved after the block loads ({@link DeviceRuntime#resolveLegacyOwner}); v1 ignores the
     * key.
     */
    protected @Nullable UUID ownerUuid;
    /**
     * When {@code true}, {@link #getCombinedTopic()} is prefixed with the owner's username.
     * If no base item is set, the topic becomes just the owner's username.
     */
    protected boolean privateMode = false;

    public BaseMqttBlockEntity(BlockEntityType<?> blockEntityType, BlockPos pos, BlockState blockState) {
        super(blockEntityType, pos, blockState);
        this.items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
        this.topic = getDefaultTopic();
    }

    public static String parseItemStackTopic(ItemStack itemStack) {
        return LegacyNbtV1.itemTopic(itemStack);
    }

    protected abstract String getDefaultTopic();

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        if (!this.topic.equals(topic)) {
            this.topic = topic;
            markUpdated();
        }
    }

    /** The topic of the slots by v1's rules ({@link LegacyNbtV1#combinedTopic}). */
    public String getCombinedTopic() {
        return LegacyNbtV1.combinedTopic(getItem(0), getItem(1), privateMode, ownerName);
    }

    public boolean isEnabled() {
        // In private mode the owner name alone is a valid topic, so no base item is required.
        if (privateMode && !ownerName.isEmpty()) return true;
        return !getItem(0).isEmpty();
    }

    public String getOwnerName() {
        return ownerName;
    }

    /** The owner by UUID; null when there is none, or while a v1 owner name is not resolved yet. */
    public @Nullable UUID getOwnerUuid() {
        return ownerUuid;
    }

    /** Sets the owner: the placing player, or the player who claims a block that had none. */
    public void setOwner(@Nullable UUID ownerUuid, String ownerName) {
        if (!this.ownerName.equals(ownerName) || !Objects.equals(this.ownerUuid, ownerUuid)) {
            this.ownerName = ownerName;
            this.ownerUuid = ownerUuid;
            this.setChanged();
        }
    }

    /**
     * The v1 owner name resolved to this UUID. Ignored once the block has an owner UUID or a new owner name.
     *
     * @return whether the block took it
     */
    public boolean ownerResolved(String ownerName, UUID ownerUuid) {
        if (this.ownerUuid != null || !this.ownerName.equals(ownerName)) return false;
        this.ownerUuid = ownerUuid;
        this.setChanged();
        return true;
    }

    public boolean isPrivateMode() {
        return privateMode;
    }

    public void setPrivateMode(boolean privateMode) {
        if (this.privateMode != privateMode) {
            this.privateMode = privateMode;
            markUpdated();
        }
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level instanceof ServerLevel serverLevel) loadedOnServer(serverLevel);
    }

    /**
     * The block is in a server level now (chunk load, placement, {@code /clone}): its v1 owner name is resolved to an
     * owner UUID on the next tick, at every load until it is.
     */
    protected void loadedOnServer(ServerLevel level) {
        DeviceRuntime.scheduleOwnerLookup(level, this);
    }

    public void markUpdated() {
        this.setChanged();
        if (this.level != null) {
            this.level.sendBlockUpdated(this.getBlockPos(), this.getBlockState(), this.getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("topic", this.topic);
        output.putString("lastTabId", this.lastTabId);
        output.putString("ownerName", this.ownerName);
        output.storeNullable("ownerUuid", UUIDUtil.CODEC, this.ownerUuid);
        output.putBoolean("privateMode", this.privateMode);
        ContainerItems.save(output, this.items);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.topic = input.getString("topic").orElse(getDefaultTopic());
        this.lastTabId = input.getString("lastTabId").orElse("");
        this.ownerName = input.getString("ownerName").orElse("");
        this.ownerUuid = input.read("ownerUuid", UUIDUtil.CODEC).orElse(null);
        this.privateMode = input.getBooleanOr("privateMode", false);
        this.items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
        ContainerItems.load(input, this.items);
    }

    @Override
    public String getLastTabId() {
        return lastTabId;
    }

    @Override
    public void setLastTabId(String tabId) {
        if (tabId == null || tabId.equals(this.lastTabId)) return;
        this.lastTabId = tabId;
        this.setChanged();
    }

    /**
     * Returns the ordered list of tabs this block exposes in its tabbed GUI.
     * Subclasses should call {@code super.getTabs()} and append their own tab(s)
     * so that the inheritance chain naturally accumulates tabs.
     */
    public List<MqttTab> getTabs() {
        List<MqttTab> list = new ArrayList<>();
        list.add(new SettingsTab());
        return list;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block." + this.getBlockState().getBlock().getDescriptionId().substring("block.".length()));
    }

    /**
     * Opens the block's screen for a player who may use it; the slots only move if they may also configure it. The player
     * gets the notices about their devices that waited. No shipped block opens it: the converters' blocks are no
     * {@code BaseMqttBlock}, the Configurator opens no screen of a block that is no device, and the converters change into
     * devices on their first tick. Whatever opens it again must first refuse a player who may not build (P2,
     * {@link DeviceAccess#mayBuild}), since this screen only configures.
     */
    @Override
    public void openScreen(ServerPlayer player) {
        MineqttRuntime.current().filter(running -> running.server() == player.level().getServer())
                .ifPresent(running -> running.devices().deliverNotices(player));
        boolean configurable = mayConfigure(player);
        String tabId = TabbedMqttMenu.initialTabId(getTabs(), this.lastTabId);
        MenuRegistry.openExtendedMenu(player, this, buf -> TabbedMqttMenu.writeOpenData(buf, this.getBlockPos(), configurable, tabId, ownerStatus(player), null));
    }

    /** The status line of the block: only an owner name an online-mode server has not resolved yet. */
    private @Nullable String ownerStatus(ServerPlayer player) {
        if (ownerUuid != null || ownerName.isEmpty() || !player.level().getServer().usesAuthentication()) return null;
        return "Owner " + ownerName + " not verified yet";
    }

    @Nullable
    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new TabbedMqttMenu(containerId, inventory, inventory.player, this.getBlockPos(), this.lastTabId, mayConfigure(inventory.player));
    }

    private boolean mayConfigure(Player player) {
        return player instanceof ServerPlayer serverPlayer && DeviceAccess.check(serverPlayer, this, MineqttPermission.CONFIGURE);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** The block's screens read the client block entity, so its clients get all of its data. */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveWithFullMetadata(registries);
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return this.items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    public int getContainerSize() {
        return INVENTORY_SIZE;
    }

    /** No slot is reachable from any side; the screen's slots go through {@link #canPlaceItem}, which stays open. */
    @Override
    public final int[] getSlotsForFace(Direction side) {
        return NO_SLOTS;
    }

    /** Also refuses vanilla's side-less insert ({@code HopperBlockEntity.addItem} with no direction). */
    @Override
    public final boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
        return false;
    }

    @Override
    public final boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return false;
    }
}

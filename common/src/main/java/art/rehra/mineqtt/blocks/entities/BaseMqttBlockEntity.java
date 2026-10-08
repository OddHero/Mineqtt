package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.RawLink;
import art.rehra.mineqtt.device.RawLinkSpec;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.legacy.LegacyNbtV1;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.security.DeviceAccess;
import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.tabs.SettingsTab;
import dev.architectury.registry.menu.MenuRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ContainerHelper;
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
import java.util.Optional;
import java.util.UUID;

public abstract class BaseMqttBlockEntity extends BaseContainerBlockEntity {

    public static final int INVENTORY_SIZE = 4;
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

    /** The topic of the slots by v1's rules, which the item slots keep in M1 ({@link LegacyNbtV1#combinedTopic}). */
    public String getCombinedTopic() {
        return LegacyNbtV1.combinedTopic(getItem(0), getItem(1), privateMode, ownerName);
    }

    /** The custom on/off payloads of slots 2 and 3 by v1's rules; none on a block without those slots. */
    public ValueCodecs.CustomPayloads getCustomPayloads() {
        if (getContainerSize() < INVENTORY_SIZE) return ValueCodecs.CustomPayloads.NONE;
        return LegacyNbtV1.customPayloads(getItem(2), getItem(3));
    }

    public boolean isEnabled() {
        // In private mode the owner name alone is a valid topic, so no base item is required.
        if (privateMode && !ownerName.isEmpty()) return true;
        return !getItem(0).isEmpty();
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        if (ownerName == null) ownerName = "";
        if (!this.ownerName.equals(ownerName)) {
            this.ownerName = ownerName;
            this.setChanged();
        }
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

    /**
     * A device's server ticker: the slots edit its Raw Link (design §6.9). A new topic, or the private mode toggled,
     * makes the slots' topic the link; editing the topic drops a v1 grandfather flag (§8.2), private mode puts it into
     * the owner's private subtree, and no base item removes the link. Changed payload slots only change the link's
     * custom payloads, on a type whose link has them.
     *
     * @return whether the Raw Link changed
     */
    protected boolean followSlots(ServerLevel level, DeviceLink link) {
        String combined = getCombinedTopic();
        RawLink previous = link.raw();
        RawLinkSpec spec = link.type().rawLink().orElseThrow();
        ValueCodecs.CustomPayloads custom = spec.customPayloads() ? getCustomPayloads() : ValueCodecs.CustomPayloads.NONE;
        if (combined.equals(topic)) {
            if (previous == null || previous.customPayloads().equals(custom)) return false;
            link.setRaw(previous.withCustomPayloads(custom));
            setChanged();
            return true;
        }
        topic = combined;
        setChanged();
        RawLinkSpec.Direction direction = spec.direction();
        boolean echo = previous != null && previous.stateEcho();
        if (!isEnabled()) {
            link.setRaw(null);
        } else if (!privateMode) {
            link.setRaw(RawLink.of(combined, direction, echo).withCustomPayloads(custom));
        } else {
            String own = LegacyNbtV1.combinedTopic(getItem(0), getItem(1), false, "");
            link.setRaw(MineqttRuntime.current().filter(runtime -> runtime.server() == level.getServer())
                    .filter(runtime -> link.owner() != null)
                    .map(runtime -> RawLink.of(runtime.acl().privateTopic(link.owner(), own), direction, echo))
                    .orElseGet(() -> RawLink.of(combined, direction, echo).checked("private mode needs a known owner"))
                    .withCustomPayloads(custom));
        }
        link.rawChanged(this);
        return true;
    }

    /** Device blocks attach from here (chunk load, placement, /clone, structures); see {@link DeviceLink}. */
    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (this instanceof DeviceHost host) host.deviceLink().onSetLevel(this, level);
    }

    /** Unload, or a block replaced without side effects ({@code /setblock}, {@code /fill}). */
    @Override
    public void setRemoved() {
        super.setRemoved();
        if (this instanceof DeviceHost host) host.deviceLink().onSetRemoved(this);
    }

    /** Broken by a player or an explosion. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (this instanceof DeviceHost host) host.deviceLink().onPreRemoveSideEffects(this);
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
        output.putBoolean("privateMode", this.privateMode);
        ContainerHelper.saveAllItems(output, this.items);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.topic = input.getString("topic").orElse(getDefaultTopic());
        this.lastTabId = input.getString("lastTabId").orElse("");
        this.ownerName = input.getString("ownerName").orElse("");
        this.privateMode = input.getBooleanOr("privateMode", false);
        this.items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, this.items);
    }

    public String getLastTabId() {
        return lastTabId;
    }

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
     * Opens the block's screen for a player who may use it; the slots only move if they may also configure it. A device
     * sends its status line along (design §6.9).
     */
    public void openScreen(ServerPlayer player) {
        boolean configurable = mayConfigure(player);
        String status = this instanceof DeviceHost host ? deviceStatus(player, host) : null;
        MenuRegistry.openExtendedMenu(player, this, buf -> TabbedMqttMenu.writeOpenData(buf, this.getBlockPos(), configurable, status));
    }

    private static String deviceStatus(ServerPlayer player, DeviceHost host) {
        UUID id = host.deviceLink().id();
        return MineqttRuntime.current().filter(runtime -> runtime.server() == player.level().getServer())
                .flatMap(runtime -> id == null ? Optional.empty() : runtime.devices().status(id))
                .orElse("Not registered yet");
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
}

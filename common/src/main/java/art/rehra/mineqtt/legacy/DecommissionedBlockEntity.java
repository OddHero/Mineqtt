package art.rehra.mineqtt.legacy;

import art.rehra.mineqtt.device.DeviceLink;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.List;

/**
 * The block entity of a {@link DecommissionedBlock}: it keeps the items the v1 block's configuration slots held as its
 * legacy items and nothing else, and never subscribes, publishes or ticks. Breaking the block drops them where it stood.
 * It is no container, so hoppers, comparators and item pipes see nothing in it, and clients get none of its data.
 * <p>
 * v1 saved the slots as {@code Items} beside its other keys (topics, the relay's cached message, the dynamic publisher's
 * data type), which mean nothing any more; the first save writes the items as {@code legacyItems} alone.
 */
public class DecommissionedBlockEntity extends BlockEntity {
    private List<ItemStackWithSlot> legacyItems = List.of();

    public DecommissionedBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState blockState) {
        super(type, pos, blockState);
    }

    /** The items it keeps, each with the slot it had. */
    public List<ItemStackWithSlot> legacyItems() {
        return legacyItems;
    }

    /**
     * Drops what it keeps: a placed block's items came with the item's block entity data, which is untrusted (design
     * §8.6), and were never in this block.
     */
    void clearLegacyItems() {
        if (legacyItems.isEmpty()) return;
        legacyItems = List.of();
        setChanged();
    }

    /**
     * Broken by a player or an explosion: the legacy items drop where the block stood, as a container's contents would.
     * Vanilla drops contents only for a container, which this is not.
     */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level == null) return;
        for (ItemStackWithSlot item : legacyItems) {
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), item.stack().copy());
        }
        legacyItems = List.of();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        saveItems(output, legacyItems);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        legacyItems = loadItems(input);
    }

    /**
     * The legacy items of saved data: its own {@code legacyItems}, or the v1 slots ({@code Items}) of a block saved
     * before it was decommissioned. Empty stacks are left out.
     */
    private static List<ItemStackWithSlot> loadItems(ValueInput input) {
        List<ItemStackWithSlot> own = input.listOrEmpty(DeviceLink.LEGACY_ITEMS, ItemStackWithSlot.CODEC).stream()
                .filter(item -> !item.stack().isEmpty()).toList();
        return own.isEmpty() ? LegacyNbtV1.items(input) : own;
    }

    /** Writes the legacy items as {@code legacyItems}; nothing when there are none. */
    private static void saveItems(ValueOutput output, List<ItemStackWithSlot> items) {
        if (items.isEmpty()) return;
        ValueOutput.TypedOutputList<ItemStackWithSlot> list = output.list(DeviceLink.LEGACY_ITEMS, ItemStackWithSlot.CODEC);
        items.forEach(list::add);
    }
}

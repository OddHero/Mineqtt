package art.rehra.mineqtt.legacy;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.items.DeviceBlockItem;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.TagValueInput;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * A retiring block that converts into its successor (M2 plan D11, design §9.5), such as the v1 humidity sensor into the
 * Climate Sensor. It keeps the original's block state properties, so saved states stay valid, and its block entity
 * keeps the v1 data as it was saved until then.
 * <p>
 * Loaded on a server, the block entity asks {@link DeviceRuntime#scheduleConversion} for its replacement, which runs on
 * the first tick after its {@code setLevel}, before the attaches of that tick ({@link #replace}): the block becomes the
 * successor with every property both share, and the successor's block entity loads the data {@link #convert} made from
 * the legacy data, the name an anvil gave the block included. Such a device is v1 data adopted on chunk load, so its
 * topic stays grandfathered for its owner. A placed block converts at once instead ({@link #setPlacedBy}), and the
 * successor's placement rule treats the item's data as untrusted (design §8.6).
 * <p>
 * Its item is a {@link ConverterItem}: carried by a player or a mob, it turns into the successor's item at its next
 * inventory tick. Items in containers convert through a shapeless 1:1 recipe. Converter blocks are hidden from the
 * creative tab and are removed in v3.0.
 */
public abstract class LegacyConverterBlock extends BaseEntityBlock {
    private final Supplier<Block> successor;

    protected LegacyConverterBlock(Properties properties, Supplier<Block> successor) {
        super(properties);
        this.successor = successor;
    }

    @Override
    protected abstract MapCodec<? extends LegacyConverterBlock> codec();

    /** The block it becomes. */
    public Block successor() {
        return successor.get();
    }

    /**
     * The data of the successor's block entity, made from the legacy block entity's saved data (without its id and
     * position). Pure: the checks call it with v1 data.
     */
    public abstract CompoundTag convert(CompoundTag legacy);

    /** The successor in this block's state: every property the successor declares too keeps its value. */
    public BlockState successorState(BlockState state) {
        BlockState next = successor().defaultBlockState();
        for (Property<?> property : state.getProperties()) {
            if (next.hasProperty(property)) next = copy(state, next, property);
        }
        return next;
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState from, BlockState to, Property<T> property) {
        return to.setValue(property, from.getValue(property));
    }

    /** The successor's block entity type, which a converted item's block entity data names. */
    private BlockEntityType<?> successorType() {
        BlockState state = successor().defaultBlockState();
        return BuiltInRegistries.BLOCK_ENTITY_TYPE.stream().filter(type -> type.isValid(state)).findFirst()
                .orElseThrow(() -> new IllegalStateException(successor() + " has no block entity"));
    }

    /**
     * Replaces a legacy block by its successor ({@link DeviceRuntime} runs it once the block's chunk is fully loaded, a
     * placement at once). The legacy block entity's side effects are skipped: its slots move into the successor's data
     * instead of dropping.
     *
     * @return whether the block was converted; false when another block stands there by now
     */
    public static boolean replace(ServerLevel level, BlockEntity legacy) {
        BlockPos pos = legacy.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof LegacyConverterBlock converter) || level.getBlockEntity(pos) != legacy) return false;
        CompoundTag data = converter.convert(legacy.saveWithoutMetadata(level.registryAccess()));
        level.setBlock(pos, converter.successorState(state), Block.UPDATE_ALL | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS);
        BlockEntity successor = level.getBlockEntity(pos);
        if (successor == null) throw new IllegalStateException(converter.successor() + " at " + pos.toShortString() + " has no block entity to take the data");
        try (ProblemReporter.ScopedCollector problems = new ProblemReporter.ScopedCollector(successor.problemPath(), MineQTT.LOGGER)) {
            successor.loadWithComponents(TagValueInput.create(problems, level.registryAccess(), data));
        }
        successor.setChanged();
        MineQTT.LOGGER.info("Converted the {} at {} in {} into {}", BuiltInRegistries.BLOCK.getKey(converter), pos.toShortString(),
                level.dimension().identifier(), BuiltInRegistries.BLOCK.getKey(converter.successor()));
        return true;
    }

    /**
     * A legacy item placed before it converted (in the tick it got into an inventory, or by a placer whose inventory
     * never ticks) is the successor placed: the block converts now, with the item's data loaded, and the successor's
     * {@code setPlacedBy} applies its placement rule to it (design §8.6), as for the successor's own item. Left to the
     * scheduled conversion, the item's data would be adopted as trusted v1 data with its owner and grandfathered topic.
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel serverLevel) || !(level.getBlockEntity(pos) instanceof BlockEntity legacy)) return;
        if (replace(serverLevel, legacy)) {
            BlockState placed = level.getBlockState(pos);
            placed.getBlock().setPlacedBy(level, pos, placed, placer, stack);
        }
    }

    /**
     * A legacy item as its successor's item: the same count and components; block entity data is converted as the
     * block's would be.
     */
    public ItemStack convertItem(ItemStack stack) {
        ItemStack converted = stack.transmuteCopy(successor());
        TypedEntityData<BlockEntityType<?>> data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data != null) converted.set(DataComponents.BLOCK_ENTITY_DATA, TypedEntityData.of(successorType(), convert(data.copyTagWithoutId())));
        return converted;
    }

    /**
     * The item of a {@link LegacyConverterBlock}: it becomes the successor's item in any inventory that ticks it. Placed
     * before that, it is the successor placed ({@link #setPlacedBy}), so the successor's device limit applies to it.
     */
    public static class ConverterItem extends BlockItem {
        public ConverterItem(LegacyConverterBlock block, Properties properties) {
            super(block, properties);
        }

        @Override
        protected @Nullable BlockState getPlacementState(BlockPlaceContext context) {
            BlockState state = super.getPlacementState(context);
            boolean device = ((LegacyConverterBlock) getBlock()).successor().asItem() instanceof DeviceBlockItem;
            return state == null || (device && DeviceBlockItem.refused(context)) ? null : state;
        }

        @Override
        public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, @Nullable EquipmentSlot slot) {
            ItemStack converted = ((LegacyConverterBlock) getBlock()).convertItem(stack);
            if (slot != null && owner instanceof LivingEntity living && living.getItemBySlot(slot) == stack) {
                living.setItemSlot(slot, converted);
            } else if (owner instanceof Player player) {
                Inventory inventory = player.getInventory();
                for (int i = 0; i < inventory.getContainerSize(); i++) {
                    if (inventory.getItem(i) == stack) {
                        inventory.setItem(i, converted);
                        return;
                    }
                }
            }
        }
    }
}

package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

public class PublisherBlockEntity extends BaseContainerBlockEntity {

    public final int INVENTORY_SIZE = 27;
    private NonNullList<ItemStack> items;

    public PublisherBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.PUBLISHER_BLOCK.get(), pos, blockState);

        this.items = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
    }

    @Override
    protected @NotNull Component getDefaultName() {
        return Component.translatable("container.mineqtt.publisher");
    }

    @Override
    public NonNullList<ItemStack> getItems() {
        return this.items;
    }

    @Override
    public void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return ChestMenu.threeRows(containerId, inventory, this);
    }

    @Override
    public int getContainerSize() {
        return INVENTORY_SIZE;
    }

    public static class Ticker<T extends BlockEntity> implements BlockEntityTicker<T> {

        @Override
        public void tick(Level level, BlockPos blockPos, BlockState blockState, T blockEntity) {
            MineQTT.LOGGER.info("Ticking RedstonePublisherEntity at " + blockPos.toShortString() + " with state: " + blockState.toString() + " and entity type: " + blockEntity.getClass().getName());
        }
    }
}

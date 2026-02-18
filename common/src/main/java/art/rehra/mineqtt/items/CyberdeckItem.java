package art.rehra.mineqtt.items;

import art.rehra.mineqtt.ui.CyberdeckMenu;
import dev.architectury.registry.menu.ExtendedMenuProvider;
import dev.architectury.registry.menu.MenuRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public class CyberdeckItem extends Item {
    public CyberdeckItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            MenuRegistry.openExtendedMenu(serverPlayer, new ExtendedMenuProvider() {
                @Override
                public Component getDisplayName() {
                    return Component.literal("Cyberdeck");
                }

                @Nullable
                @Override
                public AbstractContainerMenu createMenu(int id, Inventory inventory, Player p) {
                    // Determine which hand holds the cyberdeck
                    ItemStack stack = inventory.player.getMainHandItem();
                    if (!(stack.getItem() instanceof CyberdeckItem)) {
                        stack = inventory.player.getOffhandItem();
                    }
                    return new CyberdeckMenu(id, inventory, stack);
                }

                @Override
                public void saveExtraData(FriendlyByteBuf buf) {
                    // No extra data needed for this menu
                }
            });
        }
        return InteractionResult.SUCCESS;
    }
}

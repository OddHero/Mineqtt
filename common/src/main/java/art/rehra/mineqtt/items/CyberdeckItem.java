package art.rehra.mineqtt.items;

import art.rehra.mineqtt.blocks.entities.DeviceBlockEntity;
import art.rehra.mineqtt.device.DeviceConfig;
import art.rehra.mineqtt.device.LinkSetting;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.mqtt.CyberdeckSessionManager;
import art.rehra.mineqtt.security.C2SGuard;
import art.rehra.mineqtt.security.DeviceAccess;
import art.rehra.mineqtt.ui.CyberdeckMenu;
import art.rehra.mineqtt.ui.framework.TabbedScreenHost;
import dev.architectury.registry.menu.ExtendedMenuProvider;
import dev.architectury.registry.menu.MenuRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

/**
 * The Configurator (registry id {@code cyberdeck}, design §7.4). In {@link ConfiguratorMode#CONFIGURE} mode a
 * right-click opens the screen of the device it points at, up to {@link C2SGuard#CONFIGURATOR_RANGE} blocks away (a
 * ceiling lamp, say); pointing at nothing it opens the topic explorer. In Link, Group, Copy-Paste and Bind mode a
 * right-click on a device within that range is a step of that mode ({@link ConfiguratorLink}, {@link ConfiguratorGroup},
 * {@link ConfiguratorCopy}, {@link ConfiguratorBind}), and one at nothing stops it or says how the mode works. In Room
 * mode a right-click on a block within that range is a corner ({@link ConfiguratorRoom}). Within reach of the hand an
 * ordinary click goes to the block first, so doors, chests and levers work and only a block that does nothing with the
 * click becomes a corner here; a device is always a corner, and a sneaking click within reach picks a room
 * ({@code BaseMqttBlock}'s click handlers). Sneak + right-click beyond the hand's reach steps to the next mode, which
 * forgets what the player began ({@link ConfiguratorSessions}). A device within reach is handled by its block
 * ({@code BaseMqttBlock#click}), which does the same for a Configurator.
 */
public class CyberdeckItem extends Item {
    public CyberdeckItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            ItemStack stack = player.getItemInHand(hand);
            ConfiguratorMode mode = CyberdeckDataUtil.mode(stack);
            if (player.isShiftKeyDown()) {
                ConfiguratorMode next = mode.next();
                CyberdeckDataUtil.setMode(stack, next);
                ConfiguratorSessions.forget(serverPlayer.getUUID());
                serverPlayer.sendSystemMessage(Component.translatable("mineqtt.configurator.mode", next.displayName()), true);
            } else if (mode == ConfiguratorMode.ROOM) {
                // A corner beyond the hand's reach, within the Configurator's; picking a room takes a sneak-click within reach.
                ConfiguratorRoom.clickAtRange(serverPlayer);
            } else if (mode != ConfiguratorMode.CONFIGURE) {
                stepAtRange(serverPlayer, mode);
            } else if (!configureAtRange(serverPlayer)) {
                openExplorer(serverPlayer);
            }
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Gives the player back the items an old Cyberdeck's topic slots held, and drops its other explorer data (design
     * §7.2), the first tick it is carried.
     */
    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity entity, @Nullable EquipmentSlot slot) {
        super.inventoryTick(stack, level, entity, slot);
        for (ItemStack item : CyberdeckDataUtil.stripDeprecated(stack)) {
            if (entity instanceof Player player) {
                player.getInventory().placeItemBackInInventory(item);
            } else {
                entity.spawnAtLocation(level, item);
            }
        }
    }

    /**
     * Opens the screen of the block the player points at within the Configurator's range, if it is a device they may use
     * where the level lets them interact with it: vanilla asks {@link Level#mayInteract} (spawn protection, the world
     * border) before a click within reach, while a right-click into the air never asks it. A player who may not build
     * (P2) does not configure from afar.
     *
     * @return false when it points at no MineQTT block, so the explorer opens instead
     */
    private static boolean configureAtRange(ServerPlayer player) {
        HitResult hit = player.pick(C2SGuard.CONFIGURATOR_RANGE, 1.0F, false);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) return false;
        BlockEntity blockEntity = player.level().getBlockEntity(blockHit.getBlockPos());
        if (!(blockEntity instanceof TabbedScreenHost)) return false;
        Component name = blockEntity.getBlockState().getBlock().getName();
        if (!DeviceAccess.mayBuild(player)) {
            player.sendSystemMessage(Component.translatable("mineqtt.game_mode.uses_only"), true);
        } else if (!(blockEntity instanceof DeviceBlockEntity device)) {
            // A block that is no device has a screen with slots, which stays open only within reach.
            player.sendSystemMessage(Component.translatable("mineqtt.configurator.too_far", name), true);
        } else if (!player.level().mayInteract(player, blockHit.getBlockPos())) {
            player.sendSystemMessage(Component.translatable("mineqtt.configurator.protected", name), true);
        } else if (!DeviceAccess.check(player, device, MineqttPermission.USE)) {
            player.sendSystemMessage(Component.translatable("mineqtt.device.refused", DeviceConfig.refusal(LinkSetting.Gate.USE)), true);
        } else {
            device.openScreen(player);
        }
        return true;
    }

    /**
     * A step of the Link, Group, Copy-Paste or Bind mode on the device the player points at within the Configurator's range,
     * under the same rules as opening its screen from afar; pointing at nothing, or at a block that is no device, stops
     * what the mode began. A player who may not build (P2) takes no step.
     */
    private static void stepAtRange(ServerPlayer player, ConfiguratorMode mode) {
        HitResult hit = player.pick(C2SGuard.CONFIGURATOR_RANGE, 1.0F, false);
        BlockEntity blockEntity = hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK
                ? player.level().getBlockEntity(blockHit.getBlockPos()) : null;
        if (!(blockEntity instanceof TabbedScreenHost)) {
            ConfiguratorSessions.clickAir(mode, player);
            return;
        }
        Component name = blockEntity.getBlockState().getBlock().getName();
        if (!DeviceAccess.mayBuild(player)) {
            player.sendSystemMessage(Component.translatable("mineqtt.game_mode.uses_only"), true);
        } else if (!(blockEntity instanceof DeviceBlockEntity device)) {
            player.sendSystemMessage(ConfiguratorSessions.notDevice(mode, name), true);
        } else if (!player.level().mayInteract(player, blockEntity.getBlockPos())) {
            player.sendSystemMessage(Component.translatable("mineqtt.configurator.protected", name), true);
        } else if (!DeviceAccess.check(player, device, MineqttPermission.USE)) {
            player.sendSystemMessage(Component.translatable("mineqtt.device.refused", DeviceConfig.refusal(LinkSetting.Gate.CONFIGURE)), true);
        } else {
            ConfiguratorSessions.click(mode, player, device);
        }
    }

    private static void openExplorer(ServerPlayer player) {
        boolean listening = CyberdeckSessionManager.isListening(player);
        boolean privateMode = CyberdeckSessionManager.isPrivate(player);
        MenuRegistry.openExtendedMenu(player, new ExtendedMenuProvider() {
            @Override
            public Component getDisplayName() {
                return Component.translatable("item.mineqtt.cyberdeck");
            }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inventory, Player p) {
                return new CyberdeckMenu(id, inventory, CyberdeckMenu.heldConfigurator(p), listening, privateMode);
            }

            @Override
            public void saveExtraData(FriendlyByteBuf buf) {
                CyberdeckMenu.writeOpenData(buf, listening, privateMode);
            }
        });
    }
}

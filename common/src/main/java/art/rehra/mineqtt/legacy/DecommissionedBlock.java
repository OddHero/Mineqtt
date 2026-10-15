package art.rehra.mineqtt.legacy;

import art.rehra.mineqtt.compat.BlockRemoval;
import art.rehra.mineqtt.MineQTT;
import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A block MineQTT 2 retired for good (design §7.2, §9.5, M2 plan M2-21), such as the Dynamic Publisher and the Topic
 * Relay: it keeps the original's block state properties and model, so saved states stay valid, and is inert. It neither
 * subscribes nor publishes, reacts to no redstone and emits none, and has no screen. Its block entity
 * ({@link DecommissionedBlockEntity}) keeps the items the v1 slots held.
 * <p>
 * Nothing disappears silently: breaking it drops one Network Chip (its loot table) and the items it kept, a right-click
 * tells the player what replaced it, and its item says so in its tooltip ({@link #lore}). No recipe makes or uses it, the
 * creative tab does not show it, and it is removed in v3.0.
 */
public abstract class DecommissionedBlock extends BaseEntityBlock {
    /**
     * Ticks between two uses of the same block by the same player that still count as one click: a held use key repeats
     * every 4 ticks, with some slack for a lagging server.
     */
    private static final int CLICK_BURST_TICKS = 10;
    /** Each player's last use of a decommissioned block. Server thread only; a player who left is dropped with it. */
    private static final Map<ServerPlayer, Use> lastUse = new WeakHashMap<>();

    protected DecommissionedBlock(Properties properties) {
        super(properties);
    }

    //? if >=26.3 {
    /*// Blocks have no codec from 26.3 on; the stand-ins keep the codec fields of the versions before compiling.
    protected static <B extends net.minecraft.world.level.block.Block> MapCodec<B> simpleCodec(java.util.function.Function<Properties, B> factory) {
        return art.rehra.mineqtt.compat.BlockCodecs.simpleCodec(factory);
    }

    protected static <B extends net.minecraft.world.level.block.Block> com.mojang.serialization.codecs.RecordCodecBuilder<B, Properties> propertiesCodec() {
        return art.rehra.mineqtt.compat.BlockCodecs.propertiesCodec();
    }
    *///?}

    // Not @Override: blocks have no codec from 26.3 on.
    protected abstract MapCodec<? extends DecommissionedBlock> codec();

    /**
     * The tooltip of a decommissioned block's item, a default component: that it is retired and gives its parts back
     * when broken, and what replaced it.
     *
     * @param path the block's id path, which names its replacement line in the lang file
     */
    public static ItemLore lore(String path) {
        return new ItemLore(List.of(
                Component.translatable("mineqtt.retired.tooltip").withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(false)),
                Component.translatable(replacementKey(path)).withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY).withItalic(false))));
    }

    /** The lang key of what replaced the block with this id path. */
    public static String replacementKey(String path) {
        return MineQTT.MOD_ID + ".retired." + path;
    }

    /**
     * A right-click tells the player that the block does nothing any more, what replaced it and how to get its parts;
     * once per click, not at every repeat of a held use key, which would flood the chat.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer) {
            Use use = new Use(pos.immutable(), serverPlayer.tickCount);
            Use last = lastUse.put(serverPlayer, use);
            if (last == null || !last.pos().equals(use.pos()) || use.tick() - last.tick() > CLICK_BURST_TICKS) {
                serverPlayer.sendSystemMessage(Component.translatable("mineqtt.retired.use", getName(),
                        Component.translatable(replacementKey(BuiltInRegistries.BLOCK.getKey(this).getPath()))));
            }
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * A placed block takes none of the items the item's block entity data names: that data is untrusted (design §8.6),
     * and breaking the block must not make items that were never in it.
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof DecommissionedBlockEntity retired) retired.clearLegacyItems();
    }

    /** Where and at which of the player's ticks a player last used a decommissioned block. */
    private record Use(BlockPos pos, int tick) {
    }

    //? if <1.21.5 {
    /*// The block entity's removal side effects, which Minecraft calls itself from 1.21.5 on.
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide() && !state.is(newState.getBlock()) && BlockRemoval.sideEffects()
                && level.getBlockEntity(pos) instanceof BlockRemoval.SideEffects removed) {
            removed.preRemoveSideEffects(pos, state);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
    *///?}
}

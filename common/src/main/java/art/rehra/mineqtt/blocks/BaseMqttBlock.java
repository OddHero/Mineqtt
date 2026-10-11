package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.blocks.entities.DeviceBlockEntity;
import art.rehra.mineqtt.device.DeviceConfig;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.LinkSetting;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.items.ConfiguratorMode;
import art.rehra.mineqtt.items.ConfiguratorSessions;
import art.rehra.mineqtt.security.DeviceAccess;
import art.rehra.mineqtt.ui.device.AdvancedTab;
import art.rehra.mineqtt.ui.framework.TabbedScreenHost;
import com.mojang.serialization.MapCodec;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.InteractionEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public abstract class BaseMqttBlock extends BaseEntityBlock implements InteractionEvent.RightClickBlock {

    private static boolean eventsRegistered = false;
    protected final MapCodec<? extends BaseMqttBlock> codec;

    protected BaseMqttBlock(Properties properties, MapCodec<? extends BaseMqttBlock> codec) {
        super(properties);
        this.codec = codec;
    }

    public static void registerInteractionEvents() {
        if (!eventsRegistered) {
            InteractionEvent.RIGHT_CLICK_BLOCK.register((player, hand, pos, face) -> {
                BlockState state = player.level().getBlockState(pos);
                if (state.getBlock() instanceof BaseMqttBlock block) {
                    return block.click(player, hand, pos, face);
                }
                return EventResult.pass();
            });
            eventsRegistered = true;
        }
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide()) return;
        BlockEntity be = level.getBlockEntity(pos);
        // The item's block entity data is applied by now and is untrusted (design §8.6).
        if (be instanceof DeviceBlockEntity device) {
            device.placedBy(placer);
        }
        if (be instanceof BaseMqttBlockEntity mqtt) {
            // An owner copied with the item's data is forged; only a placing player becomes the owner.
            Player player = placer instanceof Player p ? p : null;
            mqtt.setOwner(player == null ? null : player.getUUID(), player == null ? "" : player.getGameProfile().name());
        }
    }

    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity, ItemStack tool) {
        if (blockEntity instanceof Container container) {
            Containers.dropContents(level, pos, container);
            level.updateNeighbourForOutputSignal(pos, this);
        }
        super.playerDestroy(level, player, pos, state, blockEntity, tool);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return codec;
    }

    /**
     * A right-click on the block, before vanilla handles it: {@link Interaction#decide} says what it does. Each outcome
     * but {@link Interaction.Outcome#PASS} needs USE on the server, else the click passes as on any block; a paper, a
     * name tag or a Configurator mode step of a player who may not use the device is answered in the action bar, as one
     * who may not configure it.
     * <p>
     * A player who may not build (P2, {@link DeviceAccess#mayBuild}) only uses: a use and the screen go on, which shows
     * them a device's Controls tab alone and refuses any other screen ({@link TabbedScreenHost#openScreen}); a paper, a
     * name tag and a step of a Configurator mode are refused in the action bar.
     * <p>
     * A spectator only looks, as into a chest: whatever they hold, their click opens the screen, where they change
     * nothing ({@code C2SGuard}). The event comes before vanilla's spectator check on both loaders, and vanilla would then
     * open the block entity's menu without asking {@link DeviceAccess}, so a spectator's click never passes.
     */
    @Override
    public EventResult click(Player player, InteractionHand hand, BlockPos pos, Direction face) {
        BlockEntity blockEntity = player.level().getBlockEntity(pos);
        if (!(blockEntity instanceof TabbedScreenHost screenHost)) {
            return EventResult.pass();
        }
        ItemStack held = player.getItemInHand(hand);
        DeviceType type = blockEntity instanceof DeviceHost host ? host.deviceType() : null;
        boolean spectator = player.isSpectator();
        Interaction.Outcome outcome = spectator ? Interaction.Outcome.SCREEN
                : Interaction.decide(player.isShiftKeyDown(), Interaction.Held.of(player, hand), type);
        if (outcome == Interaction.Outcome.PASS) {
            return EventResult.pass();
        }
        if (player instanceof ServerPlayer serverPlayer) {
            if (!DeviceAccess.check(serverPlayer, blockEntity, MineqttPermission.USE)) {
                if (outcome != Interaction.Outcome.USE && outcome != Interaction.Outcome.SCREEN) {
                    serverPlayer.sendSystemMessage(Component.translatable("mineqtt.device.refused", DeviceConfig.refusal(LinkSetting.Gate.CONFIGURE)), true);
                }
                return spectator ? EventResult.interruptFalse() : EventResult.pass();
            }
            if (outcome != Interaction.Outcome.USE && outcome != Interaction.Outcome.SCREEN && !DeviceAccess.mayBuild(serverPlayer)) {
                serverPlayer.sendSystemMessage(Component.translatable("mineqtt.game_mode.uses_only"), true);
                return EventResult.interruptTrue();
            }
            switch (outcome) {
                case USE -> useDevice(serverPlayer, blockEntity);
                case SCREEN -> screenHost.openScreen(serverPlayer);
                // Neither the paper nor the name tag is used up.
                case PAPER -> DeviceRuntime.paperChannel(serverPlayer, blockEntity, AdvancedTab.paperChannel(held).orElseThrow());
                case RENAME -> DeviceRuntime.nameTag(serverPlayer, blockEntity, Interaction.nameTag(held).orElseThrow());
                case LINK -> step(serverPlayer, blockEntity, ConfiguratorMode.LINK);
                case GROUP -> step(serverPlayer, blockEntity, ConfiguratorMode.GROUP);
                case COPY -> step(serverPlayer, blockEntity, ConfiguratorMode.COPY_PASTE);
                case BIND -> step(serverPlayer, blockEntity, ConfiguratorMode.BIND);
            }
        }
        return EventResult.interruptTrue();
    }

    /**
     * A right-click uses the device ({@link Interaction.Outcome#USE}), after the USE check: its type's
     * {@link DeviceType#use() use} action. A block whose use depends on where the click hits (the Wall Switch's two
     * buttons) does it itself.
     */
    protected void useDevice(ServerPlayer player, BlockEntity blockEntity) {
        DeviceRuntime.use(player, blockEntity);
    }

    // A step of a Configurator mode on the block; a block that is no device cannot take part.
    private static void step(ServerPlayer player, BlockEntity blockEntity, ConfiguratorMode mode) {
        if (blockEntity instanceof DeviceBlockEntity device) {
            ConfiguratorSessions.click(mode, player, device);
        } else {
            player.sendSystemMessage(ConfiguratorSessions.notDevice(mode, blockEntity.getBlockState().getBlock().getName()), true);
        }
    }

    @Override
    protected @Nullable MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof MenuProvider ? (MenuProvider) blockEntity : null;
    }
}

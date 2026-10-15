package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.compat.Profiles;
import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.BlockEmitterBlock;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommandResult;
import art.rehra.mineqtt.device.CommandSource;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.PlacerRules;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.BlockPlacer;
import art.rehra.mineqtt.device.types.BlockPlacer.Placed;
import art.rehra.mineqtt.device.types.BlockPlacer.Settings;
import art.rehra.mineqtt.legacy.LegacyNbtV1;
import art.rehra.mineqtt.security.TokenBucket;
import com.mojang.serialization.DataResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * The Block Placer ({@link BlockPlacer}): places its block into the space in front of its face and clears the block it
 * placed, under the rules of M4 plan D12 ({@link PlacerRules}). Its setting, what it said last and what it placed are in
 * the {@code mineqtt} compound; v1 data is read on load and adopted when the device attaches. Clients get no render data.
 * <p>
 * While {@code blockPlacer.enabled} is off it is still a device: it follows no Raw Link ({@link #rawInputOpen()}) and
 * answers every action with {@value #DISABLED}.
 */
public class BlockEmitterBlockEntity extends DeviceBlockEntity {
    /** What a placer answers while the server has it switched off. */
    public static final String DISABLED = "disabled: blockPlacer.enabled is off on this server";
    /** What a placer answers when the chunk of the space in front of it is not loaded. */
    public static final String FRONT_NOT_LOADED = "the space in front is not loaded";
    /** What a placer that takes items answers when the chunk of the container behind it is not loaded. */
    public static final String BEHIND_NOT_LOADED = "the container behind it is not loaded";
    /** What a placer that takes items answers when the container behind it lies in a claim it may not open. */
    public static final String BEHIND_CLAIMED = "refused by the claim behind it: the placer and the container behind it share no claim, "
            + "and its owner is offline or may not open containers there";
    private static final String NOTHING_TO_CLEAR = "nothing to clear: it clears only the block it placed, while that stands";
    // How often the placer looks whether the block it placed still stands, besides the neighbour updates that tell it.
    private static final int CHECK_TICKS = 20;

    private final DeviceLink link = new DeviceLink(BlockPlacer.TYPE);
    private Settings settings = Settings.DEFAULT;
    private @Nullable Placed placed;
    // Runtime state, never saved: the rate of actions, the switch as last seen and a change in front to look at.
    private final TokenBucket actions = new TokenBucket(BlockPlacer.ACTIONS_PER_SECOND, BlockPlacer.ACTIONS_PER_SECOND, System::nanoTime);
    private boolean enabled = MineQTTConfig.blockPlacerEnabled;
    private boolean frontChanged;

    public BlockEmitterBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.BLOCK_EMITTER_BLOCK.get(), pos, blockState);
    }

    @Override
    public DeviceType deviceType() {
        return BlockPlacer.TYPE;
    }

    @Override
    public DeviceLink deviceLink() {
        return link;
    }

    @Override
    public void writeState(StateWriter out) {
        settings.write(out, placed != null).put(CommonFields.LOCATION, GlobalPos.of(level.dimension(), worldPosition));
    }

    /** Off while the server has the placer switched off: no message reaches it then (D12). */
    @Override
    public boolean rawInputOpen() {
        return MineQTTConfig.blockPlacerEnabled;
    }

    /**
     * A command: {@code block} sets the block it places (refused unless {@link PlacerRules#allowed}), {@code place}
     * places it, {@code clear} clears the block it placed; a Raw Link message may set the block and place it at once. A
     * refused action says why in {@code last_result}, which is published although the command is refused.
     */
    @Override
    public CommandResult applyCommand(CommandReader in, CommandSource source) {
        boolean place = in.has(BlockPlacer.PLACE);
        boolean clear = in.has(BlockPlacer.CLEAR);
        Optional<String> block = in.text(BlockPlacer.BLOCK.key());
        if (!place && !clear && block.isEmpty()) return CommandResult.rejected("not a block placer command: " + in.keys());
        // An action is gated before its block is read, so a refused one costs a token too: the rate bounds every attempt.
        if (place || clear) {
            Optional<String> gate = actionRefusal(MineQTTConfig.blockPlacerEnabled, actions);
            if (gate.isPresent()) return refused(gate.get());
        }
        boolean changed = false;
        if (block.isPresent()) {
            DataResult<PlacerRules.Request> request = allowed(block.get());
            if (request.error().isPresent()) return refused(request.error().get().message());
            String text = request.getOrThrow().text();
            if (!text.equals(settings.block())) {
                settings = settings.withBlock(text);
                changed = true;
            }
        }
        if (!place && !clear) {
            if (!changed) return CommandResult.unchanged();
            setChanged();
            return CommandResult.applied();
        }
        if (!(level instanceof ServerLevel serverLevel)) return refused("not in a world");
        return clear ? clear(serverLevel) : place(serverLevel);
    }

    /**
     * Why an action is refused before it is tried, or empty to go on: the server has the placer switched off (which costs
     * no token), or it acted {@value BlockPlacer#ACTIONS_PER_SECOND} times in the last second already.
     */
    static Optional<String> actionRefusal(boolean enabled, TokenBucket actions) {
        if (!enabled) return Optional.of(DISABLED);
        if (!actions.tryTake()) return Optional.of("too fast: at most " + BlockPlacer.ACTIONS_PER_SECOND + " actions a second");
        return Optional.empty();
    }

    // The block of a setting, checked against the code's denies and the tag as the server has it now.
    private static DataResult<PlacerRules.Request> allowed(String text) {
        return PlacerRules.allowed(text, BuiltInRegistries.BLOCK, PlacerRules::placeable);
    }

    /**
     * Places the block it is set to into the space in front: only into a replaceable space, or over the block it placed
     * itself, inside the world border, outside spawn protection and as the claim rule allows, with the item from the
     * container behind it while {@code blockPlacer.consumeItems} is on. Both must be in loaded chunks: an action never
     * loads one. Without properties given, the block takes the state a dispenser would give it (a lamp beside power is lit).
     */
    private CommandResult place(ServerLevel serverLevel) {
        if (settings.block().isEmpty()) return refused("no block chosen");
        DataResult<PlacerRules.Request> request = allowed(settings.block());
        if (request.error().isPresent()) return refused(request.error().get().message());
        Direction facing = getBlockState().getValue(BlockEmitterBlock.FACING);
        BlockPos target = worldPosition.relative(facing);
        BlockPos behindPos = worldPosition.relative(facing.getOpposite());
        // Broker input never loads a chunk: the space in front and the container behind must be loaded already.
        if (!serverLevel.hasChunkAt(target)) return refused(FRONT_NOT_LOADED);
        if (MineQTTConfig.blockPlacerConsumeItems && !serverLevel.hasChunkAt(behindPos)) return refused(BEHIND_NOT_LOADED);
        Optional<String> where = buildRefusal(serverLevel, target);
        if (where.isPresent()) return refused(where.get());
        if (MineQTTConfig.blockPlacerConsumeItems && !containerAllowed(serverLevel, behindPos)) return refused(BEHIND_CLAIMED);
        followPlaced();
        BlockState current = serverLevel.getBlockState(target);
        boolean own = placed != null && placed.pos().equals(target) && PlacerRules.ownBlock(placed.state(), current);
        BlockState state = placementState(serverLevel, request.getOrThrow(), target, facing);
        if (own && current == state) return result("already placed " + settings.block());
        if (!own && !current.canBeReplaced()) return refused("the space in front is taken by " + BuiltInRegistries.BLOCK.getKey(current.getBlock()));
        Item item = state.getBlock().asItem();
        Container behind = MineQTTConfig.blockPlacerConsumeItems ? HopperBlockEntity.getContainerAt(serverLevel, behindPos) : null;
        if (MineQTTConfig.blockPlacerConsumeItems && (behind == null || !PlacerRules.take(behind, facing, item))) {
            return refused("no material: put " + BuiltInRegistries.ITEM.getKey(item) + " into the container behind it");
        }
        BlockState replaced = own ? current : null;
        serverLevel.setBlock(target, state, Block.UPDATE_ALL);
        serverLevel.playSound(null, target, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0f, 0.8f);
        serverLevel.gameEvent(GameEvent.BLOCK_PLACE, target, GameEvent.Context.of(state));
        if (replaced != null && MineQTTConfig.blockPlacerConsumeItems) giveBack(serverLevel, behind, facing, target, replaced);
        placed = new Placed(target, serverLevel.getBlockState(target));
        return result("placed " + settings.block());
    }

    /**
     * Clears the block it placed, if that still stands (in any state of that block), and gives its item back: into the container
     * behind it while that is loaded and the claim rule lets it reach in ({@link #containerAllowed}), else it drops
     * where the block stood. Nothing is cleared while the block's chunk is
     * not loaded, as only a loaded chunk shows whether the block there is still the placer's.
     */
    private CommandResult clear(ServerLevel serverLevel) {
        if (placed != null && !serverLevel.hasChunkAt(placed.pos())) return refused(FRONT_NOT_LOADED);
        followPlaced();
        if (placed == null) return refused(NOTHING_TO_CLEAR);
        Optional<String> where = buildRefusal(serverLevel, placed.pos());
        if (where.isPresent()) return refused(where.get());
        Placed cleared = placed;
        if (!PlacerRules.ownBlock(cleared.state(), serverLevel.getBlockState(cleared.pos()))) return refused(NOTHING_TO_CLEAR);
        String id = BuiltInRegistries.BLOCK.getKey(cleared.state().getBlock()).toString();
        serverLevel.destroyBlock(cleared.pos(), false);
        placed = null;
        if (MineQTTConfig.blockPlacerConsumeItems) {
            Direction facing = getBlockState().getValue(BlockEmitterBlock.FACING);
            BlockPos behindPos = worldPosition.relative(facing.getOpposite());
            Container behind = serverLevel.hasChunkAt(behindPos) && containerAllowed(serverLevel, behindPos)
                    ? HopperBlockEntity.getContainerAt(serverLevel, behindPos) : null;
            giveBack(serverLevel, behind, facing, cleared.pos(), cleared.state());
        }
        return result("cleared " + id);
    }

    // The block's item goes back into the container behind the placer, or drops where the block stood.
    private static void giveBack(ServerLevel serverLevel, @Nullable Container behind, Direction facing, BlockPos where, BlockState state) {
        ItemStack left = PlacerRules.give(behind, facing, new ItemStack(state.getBlock().asItem()));
        if (!left.isEmpty()) Containers.dropItemStack(serverLevel, where.getX() + 0.5, where.getY() + 0.5, where.getZ() + 0.5, left);
    }

    // What the block placed now looks like: the request's state, or without properties the dispenser's.
    private static BlockState placementState(ServerLevel serverLevel, PlacerRules.Request request, BlockPos target, Direction facing) {
        if (request.givesProperties()) return request.state();
        Block block = request.state().getBlock();
        BlockState placement = block.getStateForPlacement(new DirectionalPlaceContext(serverLevel, target, facing, new ItemStack(block.asItem()), facing));
        return placement != null && placement.is(block) ? placement : request.state();
    }

    /**
     * Why the placer may not build or clear at {@code target}, or empty when it may: the world border, spawn protection
     * and the claim rule ({@link PlacerRules#claimAllows}), the last two judged for its owner.
     */
    private Optional<String> buildRefusal(ServerLevel serverLevel, BlockPos target) {
        if (!serverLevel.getWorldBorder().isWithinBounds(target)) return Optional.of("outside the world border");
        MinecraftServer server = serverLevel.getServer();
        UUID owner = link.owner();
        ServerPlayer online = owner == null ? null : server.getPlayerList().getPlayer(owner);
        if (spawnProtected(serverLevel, target, online)) return Optional.of("inside spawn protection");
        boolean claimMod = MineQTT.permissionManager.hasClaimMod();
        if (claimMod && !PlacerRules.claimAllows(true, MineQTT.permissionManager.sameClaim(serverLevel, worldPosition, target),
                online != null && online.level() == serverLevel && MineQTT.permissionManager.canPlace(online, target))) {
            return Optional.of("refused by the claim there: the placer and its target share no claim, and its owner is offline or may not build there");
        }
        return Optional.empty();
    }

    /**
     * Whether the placer may take from and give to the container at {@code behind}: the claim rule of the target
     * ({@link PlacerRules#claimAllows}), with the online owner's right to open a container there. A hopper pulls only
     * from the block above it, in its own claim; a placer reaches sideways, so a chest across a claim line stays shut.
     */
    private boolean containerAllowed(ServerLevel serverLevel, BlockPos behind) {
        if (!MineQTT.permissionManager.hasClaimMod()) return true;
        UUID owner = link.owner();
        ServerPlayer online = owner == null ? null : serverLevel.getServer().getPlayerList().getPlayer(owner);
        return PlacerRules.claimAllows(true, MineQTT.permissionManager.sameClaim(serverLevel, worldPosition, behind),
                online != null && online.level() == serverLevel && MineQTT.permissionManager.canOpenContainer(online, behind));
    }

    // Vanilla's spawn protection for the owner: asked of the server for an online owner, worked out for an offline one.
    private boolean spawnProtected(ServerLevel serverLevel, BlockPos target, @Nullable ServerPlayer online) {
        MinecraftServer server = serverLevel.getServer();
        if (online != null) return server.isUnderSpawnProtection(serverLevel, target, online);
        // Only a dedicated server protects its spawn.
        if (!(server instanceof DedicatedServer dedicated)) return false;
        UUID owner = link.owner();
        boolean ownerOp = owner != null && Profiles.isOp(server, new NameAndId(owner, link.ownerName()));
        // The world spawn may lie in another dimension from 1.21.9 on; before, it is the overworld's.
        //? if >=1.21.9 {
        LevelData.RespawnData respawn = serverLevel.getRespawnData();
        return PlacerRules.underSpawnProtection(dedicated.spawnProtectionRadius(), serverLevel.dimension() == respawn.dimension(), respawn.pos(), target,
                server.getPlayerList().getOps().isEmpty(), ownerOp);
        //?} else {
        /*return PlacerRules.underSpawnProtection(dedicated.getSpawnProtectionRadius(), serverLevel.dimension() == net.minecraft.world.level.Level.OVERWORLD,
                serverLevel.getSharedSpawnPos(), target, server.getPlayerList().getOps().isEmpty(), ownerOp);
        *///?}
    }

    /**
     * Follows the block it placed ({@link PlacerRules#follow}): a change of its own state (a lamp switched on by power)
     * stays its block, any other block there (broken, replaced, moved by a piston) is no longer its, and {@code placed}
     * turns false.
     */
    private void followPlaced() {
        if (placed == null || level == null || !level.hasChunkAt(placed.pos())) return;
        BlockState now = level.getBlockState(placed.pos());
        if (now == placed.state()) return;
        BlockState followed = PlacerRules.follow(placed.state(), now);
        if (followed != null) {
            placed = new Placed(placed.pos(), followed);
            setChanged();
            return;
        }
        placed = null;
        setChanged();
        link.stateChanged(this);
    }

    /** The block in front changed (a neighbour update of the block): the next tick looks whether its block still stands. */
    public void frontChanged() {
        frontChanged = true;
    }

    // An action that came off: its result is the state's last_result, which the runtime publishes with the state.
    private CommandResult result(String text) {
        settings = settings.withResult(text);
        setChanged();
        return CommandResult.applied();
    }

    // A refused command: the reason is the state's last_result, published here, as the runtime publishes nothing for it.
    private CommandResult refused(String reason) {
        settings = settings.withResult(reason);
        setChanged();
        link.stateChanged(this);
        return CommandResult.rejected(reason);
    }

    @Override
    protected void saveDevice(ValueOutput output) {
        savePlacer(link, settings, placed, output);
    }

    @Override
    protected void loadDevice(ValueInput input) {
        Loaded loaded = loadPlacer(link, input);
        settings = loaded.settings();
        placed = loaded.placed();
    }

    /**
     * A placed copy (D12): its block is reset, so a copy never builds what its source was set to, and it placed nothing;
     * its Raw Link is checked again for the placer as any copy's.
     */
    @Override
    protected void placedCopy() {
        settings = Settings.DEFAULT;
        placed = null;
    }

    /** What a placer's data holds: its setting and last result, and what it placed. */
    public record Loaded(Settings settings, @Nullable Placed placed) {
    }

    /**
     * A placer's data: the link with the setting as its {@code state} and the block it placed as {@code placed}, or v1's
     * key while its v1 data waits for adoption, so it is still v1 data when it loads again.
     */
    public static void savePlacer(DeviceLink link, Settings settings, @Nullable Placed placed, ValueOutput output) {
        if (link.awaitingAdoption()) {
            link.saveV1(output);
            LegacyNbtV1.writeSubscribed(output, link.pendingV1().combinedTopic());
        } else {
            ValueOutput data = link.save(output);
            data.store("state", Settings.CODEC, settings);
            data.storeNullable("placed", Placed.CODEC, placed);
        }
    }

    /**
     * Loads a placer's link and returns its setting and what it placed; v1 data, also a placed v1 item's, is kept for
     * adoption. A v1 emitter had no setting of its own: its blocks came with each message, and what it placed is none of
     * the placer's.
     */
    public static Loaded loadPlacer(DeviceLink link, ValueInput input) {
        return link.load(input, LegacyNbtV1.hasSubscribedKeys(input))
                .map(data -> new Loaded(data.read("state", Settings.CODEC).orElse(Settings.DEFAULT), data.read("placed", Placed.CODEC).orElse(null)))
                .orElse(new Loaded(Settings.DEFAULT, null));
    }

    /**
     * Once attached: follows {@code blockPlacer.enabled} (its Raw Link input opens or closes, and its last result says so)
     * and looks whether the block it placed still stands after a neighbour update, and once a second besides.
     */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (!(blockEntity instanceof BlockEmitterBlockEntity placer) || level.isClientSide() || !placer.link.attached()) return;
            if (placer.enabled != MineQTTConfig.blockPlacerEnabled) {
                placer.enabled = MineQTTConfig.blockPlacerEnabled;
                placer.link.rawInputChanged(placer);
                placer.settings = placer.settings.withResult(placer.enabled ? "ready" : DISABLED);
                placer.setChanged();
                placer.link.stateChanged(placer);
            }
            if (placer.frontChanged || level.getGameTime() % CHECK_TICKS == 0) {
                placer.frontChanged = false;
                placer.followPlaced();
            }
        }
    }
}

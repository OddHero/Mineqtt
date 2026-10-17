package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.device.types.SmartLock.TargetKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What a Smart Lock locks and where its plate goes, read through a {@link Lookup} so the rules run without a level.
 * <p>
 * Lockable: a block in {@code #mineqtt:lockable} (doors, trapdoors and fence gates by default) or one whose block entity
 * is a vanilla lockable container ({@link BaseContainerBlockEntity}: chests, barrels, shulker boxes, furnaces, hoppers,
 * dispensers, droppers, brewing stands, crafters and modded containers built on it). A target is a group of positions
 * ({@link #group}): a door is both halves, and the door next to it that forms a double door with it (same facing,
 * opposite hinge, on its free side) belongs to it with both halves; a double chest is both halves; anything else is its
 * one position. A plate is attached to one position of the group and aims at it ({@link SmartLockBlock#support}).
 */
public final class LockTargets {
    /** The blocks a Smart Lock locks besides containers: {@code #minecraft:doors}, {@code #minecraft:trapdoors}, {@code #minecraft:fence_gates}. */
    public static final TagKey<Block> LOCKABLE = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "lockable"));

    private LockTargets() {
    }

    /** The blocks and block entities around a target, from a level or a check's map. */
    public interface Lookup {
        BlockState state(BlockPos pos);

        @Nullable BlockEntity entity(BlockPos pos);

        /** Whether the block is in {@link #LOCKABLE}; a check without tags answers for itself. */
        boolean tagged(BlockState state);

        /** The target part a Smart Lock plate at {@code at} aims at; empty when no plate stands there. */
        default Optional<BlockPos> plateAt(BlockPos at) {
            BlockState state = state(at);
            return state.getBlock() instanceof SmartLockBlock ? Optional.of(SmartLockBlock.support(at, state)) : Optional.empty();
        }

        /** A level: the tag as the server or client has it. */
        static Lookup of(BlockGetter level) {
            return new Lookup() {
                @Override
                public BlockState state(BlockPos pos) {
                    return level.getBlockState(pos);
                }

                @Override
                public @Nullable BlockEntity entity(BlockPos pos) {
                    return level.getBlockEntity(pos);
                }

                @Override
                public boolean tagged(BlockState state) {
                    return state.is(LOCKABLE);
                }
            };
        }
    }

    /** What the block at {@code pos} is as a target; empty when a Smart Lock cannot lock it. */
    public static Optional<TargetKind> kind(Lookup lookup, BlockPos pos) {
        BlockState state = lookup.state(pos);
        if (lookup.entity(pos) instanceof BaseContainerBlockEntity) return Optional.of(TargetKind.CONTAINER);
        if (!lookup.tagged(state)) return Optional.empty();
        Block block = state.getBlock();
        if (block instanceof DoorBlock) return Optional.of(TargetKind.DOOR);
        if (block instanceof TrapDoorBlock) return Optional.of(TargetKind.TRAPDOOR);
        if (block instanceof FenceGateBlock) return Optional.of(TargetKind.FENCE_GATE);
        return Optional.of(TargetKind.OTHER);
    }

    public static boolean lockable(Lookup lookup, BlockPos pos) {
        return kind(lookup, pos).isPresent();
    }

    /**
     * Every position the target at {@code pos} covers, {@code pos}'s own part first: both halves of a door and of the
     * door that pairs with it, both halves of a double chest, else {@code pos} alone. Empty when nothing lockable is there.
     */
    public static List<BlockPos> group(Lookup lookup, BlockPos pos) {
        Optional<TargetKind> kind = kind(lookup, pos);
        if (kind.isEmpty()) return List.of();
        Set<BlockPos> group = new LinkedHashSet<>();
        BlockState state = lookup.state(pos);
        switch (kind.get()) {
            case DOOR -> {
                BlockPos lower = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
                group.add(pos);
                group.add(lower);
                group.add(lower.above());
                doorPartner(lookup, lower).ifPresent(partner -> {
                    group.add(partner);
                    group.add(partner.above());
                });
            }
            case CONTAINER -> {
                group.add(pos);
                chestPartner(lookup, pos).ifPresent(group::add);
            }
            default -> group.add(pos);
        }
        return List.copyOf(group);
    }

    /** Whether the target is a pair: a double door or a double chest. */
    public static boolean paired(Lookup lookup, BlockPos pos) {
        Optional<TargetKind> kind = kind(lookup, pos);
        if (kind.isEmpty()) return false;
        BlockState state = lookup.state(pos);
        return switch (kind.get()) {
            case DOOR -> doorPartner(lookup, state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos : pos.below()).isPresent();
            case CONTAINER -> chestPartner(lookup, pos).isPresent();
            default -> false;
        };
    }

    /**
     * The lower half of the door that forms a double door with the door whose lower half is at {@code lower}: vanilla
     * gives a door placed beside another the opposite hinge, so a door hinged left has its partner to its right
     * ({@code facing} turned clockwise) and one hinged right to its left; the partner faces the same way, has the other
     * hinge and is lockable too.
     */
    public static Optional<BlockPos> doorPartner(Lookup lookup, BlockPos lower) {
        BlockState state = lookup.state(lower);
        if (!(state.getBlock() instanceof DoorBlock) || state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) return Optional.empty();
        Direction facing = state.getValue(DoorBlock.FACING);
        DoorHingeSide hinge = state.getValue(DoorBlock.HINGE);
        BlockPos partner = lower.relative(hinge == DoorHingeSide.LEFT ? facing.getClockWise() : facing.getCounterClockWise());
        BlockState other = lookup.state(partner);
        boolean pairs = other.getBlock() instanceof DoorBlock && lookup.tagged(other) && other.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                && other.getValue(DoorBlock.FACING) == facing && other.getValue(DoorBlock.HINGE) != hinge;
        return pairs ? Optional.of(partner) : Optional.empty();
    }

    /** The other half of a double chest (any {@link ChestBlock}, copper chests included), if both halves are containers. */
    public static Optional<BlockPos> chestPartner(Lookup lookup, BlockPos pos) {
        BlockState state = lookup.state(pos);
        if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return Optional.empty();
        BlockPos partner = pos.relative(ChestBlock.getConnectedDirection(state));
        BlockState other = lookup.state(partner);
        boolean pairs = other.getBlock() instanceof ChestBlock && other.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                && partner.relative(ChestBlock.getConnectedDirection(other)).equals(pos) && lookup.entity(partner) instanceof BaseContainerBlockEntity;
        return pairs ? Optional.of(partner) : Optional.empty();
    }

    /**
     * The plates attached to any position of the target at {@code pos}, as plate positions: a plate stands next to the
     * position it aims at ({@link SmartLockBlock#support}).
     */
    public static List<BlockPos> plates(Lookup lookup, BlockPos pos) {
        List<BlockPos> plates = new ArrayList<>();
        for (BlockPos part : group(lookup, pos)) {
            for (Direction direction : Direction.values()) {
                BlockPos at = part.relative(direction);
                if (lookup.plateAt(at).filter(part::equals).isPresent() && !plates.contains(at)) plates.add(at);
            }
        }
        return plates;
    }

    /** A place for a plate: the position it stands at and the direction it points, away from the part it aims at. */
    public record Spot(BlockPos plate, Direction outward) {
        /** The part of the target the plate aims at. */
        public BlockPos part() {
            return plate.relative(outward.getOpposite());
        }
    }

    /**
     * Where a plate may go on the target, best first: the face the player clicked, then every face of every part of the
     * group (the clicked part first) in the order of {@code towardsPlayer} (the directions from the target towards the
     * player, nearest first). A door takes plates on its sides only, as above and below a door are its frame and floor.
     * The caller takes the first whose position is free and where the player may build.
     */
    public static List<Spot> spots(Lookup lookup, BlockPos clicked, Direction face, List<Direction> towardsPlayer) {
        Optional<TargetKind> kind = kind(lookup, clicked);
        if (kind.isEmpty()) return List.of();
        List<Spot> spots = new ArrayList<>();
        boolean door = kind.get() == TargetKind.DOOR;
        if (!door || face.getAxis().isHorizontal()) spots.add(new Spot(clicked.relative(face), face));
        for (BlockPos part : group(lookup, clicked)) {
            for (Direction direction : towardsPlayer) {
                if (door && !direction.getAxis().isHorizontal()) continue;
                Spot spot = new Spot(part.relative(direction), direction);
                if (!spots.contains(spot)) spots.add(spot);
            }
        }
        return spots;
    }

    /** The positions of the group whose blocks swing open (doors, trapdoors, fence gates): one per door, its lower half. */
    public static List<BlockPos> swinging(Lookup lookup, List<BlockPos> group) {
        List<BlockPos> swinging = new ArrayList<>();
        for (BlockPos part : group) {
            BlockState state = lookup.state(part);
            Block block = state.getBlock();
            // A barrel has an open state too, which only says somebody looks into it.
            if (!(block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock)) continue;
            if (!state.hasProperty(BlockStateProperties.OPEN)) continue;
            if (block instanceof DoorBlock && state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) continue;
            if (!swinging.contains(part)) swinging.add(part);
        }
        return swinging;
    }
}

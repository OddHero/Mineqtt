package art.rehra.mineqtt.device;

import art.rehra.mineqtt.blocks.LockTargets;
import art.rehra.mineqtt.compat.ContainerLocks;
import art.rehra.mineqtt.config.StaticDefaults;
import art.rehra.mineqtt.device.types.SmartLock;
import art.rehra.mineqtt.device.types.SmartLock.LockState;
import art.rehra.mineqtt.device.types.SmartLock.Method;
import art.rehra.mineqtt.device.types.SmartLock.Settings;
import art.rehra.mineqtt.device.types.SmartLock.Status;
import art.rehra.mineqtt.device.types.SmartLock.TargetKind;
import art.rehra.mineqtt.security.AccessMode;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.LockCode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Dependency-free regression checks of the Smart Lock, run by Gradle's verifySmartLock task: what is lockable and what a
 * target covers (a door with both halves and its double-door partner by facing and hinge, a double chest's halves),
 * which locked container a hopper below may not pull from (either half of a double chest), where the plate may go, which plates cover a target, who may pass (owner, operators under the key, the access mode,
 * the claim), the relock countdown, the commands, the state Home Assistant reads, the attempt event, the saved form,
 * an unloaded lock's queued preview and vanilla's container lock the Smart Lock uses.
 */
public final class SmartLockCheck {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        targets();
        doors();
        chests();
        spots();
        plates();
        access();
        relock();
        commands();
        states();
        declaration();
        saved();
        containerLock();
        System.out.println("Smart Lock checks passed.");
    }

    /** A map of blocks and block entities; the tag is doors, trapdoors and fence gates, as #mineqtt:lockable ships. */
    private static final class World implements LockTargets.Lookup {
        final Map<BlockPos, BlockState> states = new HashMap<>();
        final Map<BlockPos, BlockEntity> entities = new HashMap<>();
        // Plate position -> the part it aims at; the plate block itself needs a registered block.
        final Map<BlockPos, BlockPos> plates = new HashMap<>();

        World set(BlockPos pos, BlockState state) {
            states.put(pos, state);
            if (state.is(Blocks.CHEST)) entities.put(pos, new ChestBlockEntity(pos, state));
            if (state.is(Blocks.BARREL)) entities.put(pos, new BarrelBlockEntity(pos, state));
            return this;
        }

        World door(BlockPos lower, Block block, Direction facing, DoorHingeSide hinge) {
            BlockState state = block.defaultBlockState().setValue(DoorBlock.FACING, facing).setValue(DoorBlock.HINGE, hinge);
            set(lower, state.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
            return set(lower.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        }

        @Override
        public BlockState state(BlockPos pos) {
            return states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override
        public @Nullable BlockEntity entity(BlockPos pos) {
            return entities.get(pos);
        }

        @Override
        public boolean tagged(BlockState state) {
            Block block = state.getBlock();
            return block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock;
        }

        @Override
        public Optional<BlockPos> plateAt(BlockPos at) {
            return Optional.ofNullable(plates.get(at));
        }
    }

    private static final BlockPos O = new BlockPos(0, 64, 0);

    /** Doors, trapdoors, fence gates and vanilla containers are lockable; stone, air and a crafting table are not. */
    private static void targets() {
        World world = new World().door(O, Blocks.OAK_DOOR, Direction.NORTH, DoorHingeSide.LEFT)
                .set(O.east(5), Blocks.OAK_TRAPDOOR.defaultBlockState())
                .set(O.east(6), Blocks.OAK_FENCE_GATE.defaultBlockState())
                .set(O.east(7), Blocks.CHEST.defaultBlockState())
                .set(O.east(8), Blocks.BARREL.defaultBlockState())
                .set(O.east(9), Blocks.STONE.defaultBlockState())
                .set(O.east(10), Blocks.CRAFTING_TABLE.defaultBlockState())
                .door(O.east(11), Blocks.IRON_DOOR, Direction.NORTH, DoorHingeSide.LEFT);
        check(LockTargets.kind(world, O).equals(Optional.of(TargetKind.DOOR)) && LockTargets.kind(world, O.above()).equals(Optional.of(TargetKind.DOOR)),
                "Both halves of a door are a door");
        check(LockTargets.kind(world, O.east(5)).equals(Optional.of(TargetKind.TRAPDOOR)) && LockTargets.kind(world, O.east(6)).equals(Optional.of(TargetKind.FENCE_GATE))
                        && LockTargets.kind(world, O.east(7)).equals(Optional.of(TargetKind.CONTAINER))
                        && LockTargets.kind(world, O.east(8)).equals(Optional.of(TargetKind.CONTAINER))
                        && LockTargets.kind(world, O.east(11)).equals(Optional.of(TargetKind.DOOR)),
                "Trapdoors, fence gates, chests, barrels and iron doors are lockable");
        check(!LockTargets.lockable(world, O.east(9)) && !LockTargets.lockable(world, O.east(10)) && !LockTargets.lockable(world, O.west(3))
                        && LockTargets.group(world, O.east(9)).isEmpty(), "Stone, a crafting table and air are not");
        check(LockTargets.group(world, O.east(5)).equals(List.of(O.east(5))) && !LockTargets.paired(world, O.east(5)), "A trapdoor is its one position");
        check(TargetKind.DOOR.swings() && TargetKind.TRAPDOOR.swings() && TargetKind.FENCE_GATE.swings() && !TargetKind.CONTAINER.swings(),
                "Doors, trapdoors and gates swing; containers do not");
        check(LockTargets.swinging(world, List.of(O, O.above(), O.east(5), O.east(8))).equals(List.of(O, O.east(5))),
                "A door swings by its lower half, and a barrel's open state is no swing");
    }

    /**
     * A door is both halves; the door beside it with the same facing and the other hinge, on its free side, pairs with it
     * as vanilla hangs a double door, from either door and either half.
     */
    private static void doors() {
        // Placed facing north, the left door hinged left, the right door hinged right, as vanilla places them.
        World pair = new World().door(O, Blocks.OAK_DOOR, Direction.NORTH, DoorHingeSide.LEFT)
                .door(O.east(), Blocks.SPRUCE_DOOR, Direction.NORTH, DoorHingeSide.RIGHT);
        Set<BlockPos> all = Set.of(O, O.above(), O.east(), O.east().above());
        check(Set.copyOf(LockTargets.group(pair, O)).equals(all) && Set.copyOf(LockTargets.group(pair, O.east().above())).equals(all),
                "A double door covers both doors with both halves, from either door and either half");
        check(LockTargets.group(pair, O.above()).getFirst().equals(O.above()), "The clicked part comes first");
        check(LockTargets.paired(pair, O) && LockTargets.paired(pair, O.east().above()), "Both doors know the pair");
        check(LockTargets.doorPartner(pair, O).equals(Optional.of(O.east())) && LockTargets.doorPartner(pair, O.east()).equals(Optional.of(O)),
                "A door hinged left pairs to its clockwise side, one hinged right to its counter-clockwise side");
        for (Direction facing : List.of(Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            World turned = new World().door(O, Blocks.OAK_DOOR, facing, DoorHingeSide.LEFT).door(O.relative(facing.getClockWise()), Blocks.OAK_DOOR, facing, DoorHingeSide.RIGHT);
            check(LockTargets.paired(turned, O) && LockTargets.group(turned, O).size() == 4, "A double door facing " + facing + " pairs too");
        }
        World sameHinge = new World().door(O, Blocks.OAK_DOOR, Direction.NORTH, DoorHingeSide.LEFT).door(O.east(), Blocks.OAK_DOOR, Direction.NORTH, DoorHingeSide.LEFT);
        check(!LockTargets.paired(sameHinge, O) && LockTargets.group(sameHinge, O).size() == 2, "Two doors hinged alike are two doors");
        World facingApart = new World().door(O, Blocks.OAK_DOOR, Direction.NORTH, DoorHingeSide.LEFT).door(O.east(), Blocks.OAK_DOOR, Direction.SOUTH, DoorHingeSide.RIGHT);
        check(!LockTargets.paired(facingApart, O), "Doors facing apart are no pair");
        World wrongSide = new World().door(O, Blocks.OAK_DOOR, Direction.NORTH, DoorHingeSide.LEFT).door(O.west(), Blocks.OAK_DOOR, Direction.NORTH, DoorHingeSide.RIGHT);
        check(!LockTargets.paired(wrongSide, O), "A door on the hinge side is no partner");
        World lone = new World().door(O, Blocks.OAK_DOOR, Direction.NORTH, DoorHingeSide.RIGHT);
        check(LockTargets.group(lone, O.above()).equals(List.of(O.above(), O)) && !LockTargets.paired(lone, O), "A lone door is its two halves");
    }

    /** A double chest is both halves, also for a hopper below either half; a single chest and a barrel are one. */
    private static void chests() {
        BlockState left = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockPos partner = O.relative(ChestBlock.getConnectedDirection(left));
        BlockState right = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.RIGHT);
        World world = new World().set(O, left).set(partner, right);
        check(Set.copyOf(LockTargets.group(world, O)).equals(Set.of(O, partner)) && Set.copyOf(LockTargets.group(world, partner)).equals(Set.of(O, partner))
                && LockTargets.paired(world, O), "A double chest covers both halves");
        World half = new World().set(O, left);
        check(LockTargets.group(half, O).equals(List.of(O)) && !LockTargets.paired(half, O), "A half whose partner is gone is one chest");
        World single = new World().set(O, Blocks.CHEST.defaultBlockState());
        check(LockTargets.group(single, O).equals(List.of(O)), "A single chest is one");

        // What a hopper below asks: either half's lock closes the pair; a lone half, a barrel and stone answer for themselves.
        Set<BlockEntity> lockedHalves = Set.of(world.entity(partner));
        check(LockTargets.lockedContainer(world, O, lockedHalves::contains) && LockTargets.lockedContainer(world, partner, lockedHalves::contains),
                "A hopper under either half of a double chest meets the lock of the other half");
        check(!LockTargets.lockedContainer(world, O, container -> false), "An unlocked double chest lets hoppers pull");
        check(!LockTargets.lockedContainer(half, O, lockedHalves::contains), "A lone half is not locked by a chest that is gone");
        World barrels = new World().set(O, Blocks.BARREL.defaultBlockState()).set(O.east(), Blocks.BARREL.defaultBlockState())
                .set(O.west(), Blocks.STONE.defaultBlockState());
        Set<BlockEntity> lockedBarrel = Set.of(barrels.entity(O));
        check(LockTargets.lockedContainer(barrels, O, lockedBarrel::contains) && !LockTargets.lockedContainer(barrels, O.east(), lockedBarrel::contains),
                "A barrel's lock is its own, never its neighbour's");
        check(!LockTargets.lockedContainer(barrels, O.west(), container -> true) && !LockTargets.lockedContainer(barrels, O.above(), container -> true),
                "Stone and air are no locked container");
    }

    /**
     * The plate goes on the clicked face first, then on the faces towards the player of every part; a door takes plates
     * on its sides only.
     */
    private static void spots() {
        World world = new World().door(O, Blocks.OAK_DOOR, Direction.NORTH, DoorHingeSide.LEFT).set(O.east(5), Blocks.CHEST.defaultBlockState());
        List<Direction> towards = List.of(Direction.SOUTH, Direction.UP, Direction.EAST, Direction.WEST, Direction.DOWN, Direction.NORTH);
        List<LockTargets.Spot> door = LockTargets.spots(world, O, Direction.NORTH, towards);
        check(door.getFirst().equals(new LockTargets.Spot(O.north(), Direction.NORTH)) && door.getFirst().part().equals(O), "The clicked face of the door first");
        check(door.get(1).equals(new LockTargets.Spot(O.south(), Direction.SOUTH)), "then the face towards the player");
        check(door.stream().noneMatch(spot -> spot.outward().getAxis().isVertical()), "A door takes no plate above or below");
        check(door.stream().anyMatch(spot -> spot.part().equals(O.above())) && door.size() == door.stream().distinct().count(), "Both halves, every spot once");
        check(LockTargets.spots(world, O, Direction.UP, towards).getFirst().equals(new LockTargets.Spot(O.south(), Direction.SOUTH)),
                "A click on the door's top takes the side towards the player");
        List<LockTargets.Spot> chest = LockTargets.spots(world, O.east(5), Direction.UP, towards);
        check(chest.getFirst().equals(new LockTargets.Spot(O.east(5).above(), Direction.UP)) && chest.size() == 6, "A chest takes a plate on top and every side");
        check(LockTargets.spots(world, O.east(9), Direction.UP, towards).isEmpty(), "Nothing lockable, no spot");
    }

    /** A plate counts for a target when it aims at any part of its group, from any part. */
    private static void plates() {
        World world = new World().door(O, Blocks.OAK_DOOR, Direction.NORTH, DoorHingeSide.LEFT)
                .door(O.east(), Blocks.OAK_DOOR, Direction.NORTH, DoorHingeSide.RIGHT);
        check(LockTargets.plates(world, O).isEmpty(), "No plate yet");
        world.plates.put(O.above().south(), O.above());
        check(LockTargets.plates(world, O.east()).equals(List.of(O.above().south())) && LockTargets.plates(world, O).equals(List.of(O.above().south())),
                "A plate on the left door's upper half locks the right door too");
        World other = new World().door(O, Blocks.OAK_DOOR, Direction.NORTH, DoorHingeSide.LEFT);
        other.plates.put(O.south(), O.south().south());
        check(LockTargets.plates(other, O).isEmpty(), "A plate beside the door aimed elsewhere does not lock it");
    }

    /**
     * The owner always passes; an operator while the key is on; anybody else as the access mode lets them use the lock:
     * private nobody, claim whom the claim trusts, public everybody. The claim is asked only when it matters.
     */
    private static void access() {
        BooleanSupplier never = () -> {
            throw new AssertionError("the claim is asked although the answer is known");
        };
        check(StaticDefaults.DEFAULT_SMART_LOCK_OPS_BYPASS, "Operators pass by default");
        for (AccessMode mode : AccessMode.values()) {
            check(SmartLock.mayPass(true, false, false, mode, never), "The owner passes a " + mode + " lock");
            check(SmartLock.mayPass(false, true, true, mode, never), "An operator passes a " + mode + " lock while the key is on");
        }
        check(!SmartLock.mayPass(false, true, false, AccessMode.PRIVATE, never), "Without the key an operator does not pass a private lock");
        check(!SmartLock.mayPass(false, false, true, AccessMode.PRIVATE, never), "Nobody else passes a private lock");
        check(SmartLock.mayPass(false, false, true, AccessMode.CLAIM, () -> true) && !SmartLock.mayPass(false, false, true, AccessMode.CLAIM, () -> false)
                        && !SmartLock.mayPass(false, true, false, AccessMode.CLAIM, () -> false),
                "A claim lock lets in whom the claim trusts, an operator without the key included");
        check(SmartLock.mayPass(false, false, false, AccessMode.PUBLIC, never), "A public lock lets everybody in");
        check(SmartLock.TYPE.defaultAccess() == AccessMode.PRIVATE && new DeviceLink(SmartLock.TYPE).access() == AccessMode.PRIVATE,
                "A new Smart Lock is private: without a claim mod the claim mode would let everybody in");
        check(SmartLock.TYPE.defaultAccess() != new DeviceLink(art.rehra.mineqtt.device.types.Speaker.TYPE).access()
                && new DeviceLink(art.rehra.mineqtt.device.types.Speaker.TYPE).access() == AccessMode.DEFAULT, "Other devices keep the claim's mode");
    }

    /** An unlock starts the countdown, which locks again when it runs out; locking ends it; 0 never relocks. */
    private static void relock() {
        Settings settings = new Settings(true, 5, 0, "", false, 0);
        Settings unlocked = settings.lockedTo(false);
        check(unlocked.relockLeft() == 5 * SmartLock.TICKS_PER_SECOND && !unlocked.relockDue(), "An unlock with 5 s starts 100 ticks");
        Settings ticking = unlocked;
        for (int tick = 0; tick < 99; tick++) ticking = ticking.tickRelock();
        check(!ticking.relockDue() && ticking.relockLeft() == 1, "Not yet after 99 ticks");
        ticking = ticking.tickRelock();
        check(ticking.relockDue(), "Due after 100 ticks");
        check(ticking.lockedTo(true).relockLeft() == 0 && !ticking.lockedTo(true).relockDue() && ticking.lockedTo(true).locked(), "Locking ends the countdown");
        check(unlocked.tickRelock().lockedTo(false).relockLeft() == 99, "A second unlock does not restart it");
        Settings never = new Settings(true, 0, 0, "", false, 0).lockedTo(false);
        for (int tick = 0; tick < 10; tick++) never = never.tickRelock();
        check(!never.relockDue() && never.relockLeft() == 0, "Relock 0 never locks again");
        check(never.relockTo(2).relockLeft() == 40 && unlocked.relockTo(0).relockLeft() == 0 && settings.relockTo(9).relockLeft() == 0,
                "A new delay counts from now while unlocked, 0 stops it, a locked lock counts nothing");
        check(SmartLock.relockTicks(99999) == SmartLock.MAX_RELOCK * 20 && SmartLock.relockTicks(-3) == 0, "The delay is clamped to 0-3600 s");
    }

    /** locked true/false/LOCK/UNLOCK/ON/OFF/TOGGLE and the three actions; the actions win over locked. */
    private static void commands() {
        record Case(String command, boolean locked, Optional<Boolean> to) {
        }
        for (Case c : List.of(new Case("{\"locked\":true}", false, Optional.of(true)), new Case("{\"locked\":false}", true, Optional.of(false)),
                new Case("{\"locked\":\"LOCK\"}", false, Optional.of(true)), new Case("{\"locked\":\"unlock\"}", true, Optional.of(false)),
                new Case("{\"locked\":\"ON\"}", false, Optional.of(true)), new Case("{\"locked\":\"TOGGLE\"}", true, Optional.of(false)),
                new Case("{\"lock\":\"PRESS\"}", false, Optional.of(true)), new Case("{\"unlock\":\"PRESS\"}", true, Optional.of(false)),
                new Case("{\"toggle\":\"PRESS\"}", true, Optional.of(false)), new Case("{\"toggle\":\"PRESS\"}", false, Optional.of(true)),
                new Case("{\"locked\":true,\"unlock\":\"PRESS\"}", false, Optional.of(false)), new Case("{\"locked\":\"maybe\"}", true, Optional.empty()),
                new Case("{\"relock\":5}", true, Optional.empty()))) {
            check(SmartLock.lockTo(new CommandReader(json(c.command())), c.locked()).equals(c.to()), c.command() + " on " + (c.locked() ? "a locked" : "an unlocked")
                    + " lock gives " + c.to());
        }
        check(SmartLock.TYPE.action(SmartLock.TOGGLE).flatMap(Action::handValue).equals(Optional.of("PRESS")), "A hand presses toggle");
        check(SmartLock.TYPE.use().map(Action::key).equals(Optional.of(SmartLock.TOGGLE)), "A right-click on the plate toggles");
    }

    /** What Home Assistant reads; the attempt carries how and on what, never who. */
    private static void states() {
        check(SmartLock.state(true, Status.OK, false) == LockState.LOCKED && SmartLock.state(false, Status.OK, false) == LockState.UNLOCKED,
                "Locked and unlocked");
        check(SmartLock.state(true, Status.OK, true) == LockState.OPEN && SmartLock.state(false, Status.OK, true) == LockState.OPEN,
                "A door standing open reads open");
        check(SmartLock.state(true, Status.TARGET_MISSING, false) == LockState.JAMMED && SmartLock.state(true, Status.NO_TARGET, true) == LockState.JAMMED
                && SmartLock.state(false, Status.NO_TARGET, false) == LockState.UNLOCKED, "Locked without a target is jammed");
        check(SmartLock.status(true, "") == Status.OK && SmartLock.status(false, "") == Status.NO_TARGET && SmartLock.status(false, "minecraft:chest") == Status.TARGET_MISSING,
                "A lock that never had a target has none; one that lost it misses it");
        for (Method method : Method.values()) {
            for (TargetKind kind : TargetKind.values()) {
                JsonObject event = SmartLock.attempt(method, kind);
                check(event.keySet().equals(Set.of("event_type", "method", "target")) && event.get("event_type").getAsString().equals("attempt")
                        && SmartLock.TYPE.eventProblem(event).isEmpty(), "The attempt is declared and names nobody: " + event);
            }
        }
    }

    /** The lock entity's state names are Home Assistant lock states; the type queues while unloaded. */
    private static void declaration() {
        check(java.util.Arrays.stream(LockState.values()).map(LockState::getSerializedName).toList().equals(List.of("locked", "unlocked", "open", "jammed")),
                "The states are locked, unlocked, open and jammed");
        check(SmartLock.TYPE.unloadedPolicy() == UnloadedPolicy.QUEUE && !SmartLock.TYPE.editsWorld() && SmartLock.TYPE.noV1Topic(),
                "Queued while unloaded, no world-editing gate, no v1 topic");
        JsonObject state = new Settings(true, 0, 3, "minecraft:oak_door", true, 0).write(new StateWriter(), Status.OK, false).json();
        JsonObject unlocked = SmartLock.TYPE.preview(state, json("{\"unlock\":\"PRESS\"}"));
        check(!unlocked.get("locked").getAsBoolean() && unlocked.get("state").getAsString().equals("unlocked") && unlocked.get("target").getAsString().equals("minecraft:oak_door")
                && state.get("locked").getAsBoolean(), "An unloaded lock's queued unlock shows unlocked: " + unlocked);
        JsonObject missing = new Settings(false, 0, 0, "minecraft:chest", false, 0).write(new StateWriter(), Status.TARGET_MISSING, false).json();
        check(SmartLock.TYPE.preview(missing, json("{\"locked\":true}")).get("state").getAsString().equals("jammed"), "Locking without a target previews jammed");
        check(SmartLock.TYPE.preview(state, json("{\"relock\":30}")).get("relock").getAsInt() == 30, "A queued relock delay shows");
        String line = SmartLock.TYPE.status().orElseThrow().line(state, java.time.Instant.EPOCH);
        check(line.equals("Locks oak door (pair)"), "The status line names the target: " + line);
        check(SmartLock.TYPE.status().orElseThrow().line(missing, java.time.Instant.EPOCH).equals("Target missing: chest"), "or that it is missing");
    }

    /** Settings round trip, read leniently; a new lock and a placed copy are locked. */
    private static void saved() {
        Settings settings = new Settings(false, 30, 7, "minecraft:chest", true, 123);
        Tag tag = Settings.CODEC.encodeStart(NbtOps.INSTANCE, settings).getOrThrow();
        check(Settings.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow().equals(settings), "Saved and loaded unchanged");
        CompoundTag broken = new CompoundTag();
        broken.putString("locked", "perhaps");
        broken.putInt("relock", 99999);
        broken.putInt("attempts", -4);
        Settings lenient = Settings.CODEC.parse(NbtOps.INSTANCE, broken).getOrThrow();
        check(lenient.locked() && lenient.relock() == SmartLock.MAX_RELOCK && lenient.attempts() == 0, "Unreadable values take their defaults, numbers are clamped: " + lenient);
        check(Settings.DEFAULT.locked() && SmartLock.LOCKED.defaultValue(), "A new lock is locked");
        check(settings.placed().equals(new Settings(true, 30, 0, "", false, 0)), "A placed copy keeps its delay and is locked, with nothing counted");
        check(settings.attempted().attempts() == 8 && new Settings(true, 0, SmartLock.MAX_ATTEMPTS, "", false, 0).attempted().attempts() == SmartLock.MAX_ATTEMPTS,
                "Attempts count up and stop at the cap");
        check(settings.target(Optional.empty(), true).target().equals("minecraft:chest") && !settings.target(Optional.empty(), true).paired()
                && settings.target(Optional.of("minecraft:barrel"), false).target().equals("minecraft:barrel"), "A missing target keeps its last id, no pair");
    }

    /**
     * The Smart Lock's vanilla container lock is a lock (never NO_LOCK), opens for no item, and is told apart from a map
     * maker's lock and from none by its saved form.
     */
    private static void containerLock() {
        LockCode lock = ContainerLocks.SMART_LOCK;
        art.rehra.mineqtt.CheckItems.bindCommonComponents(Items.STONE, Items.TRIPWIRE_HOOK, Items.DIAMOND);
        check(!lock.equals(LockCode.NO_LOCK) && ContainerLocks.isSmartLock(lock) && !ContainerLocks.isSmartLock(LockCode.NO_LOCK), "It is a lock of its own");
        for (ItemStack stack : List.of(ItemStack.EMPTY, new ItemStack(Items.STONE), new ItemStack(Items.TRIPWIRE_HOOK), new ItemStack(Items.DIAMOND, 64))) {
            check(!ContainerLocks.opensWith(lock, stack), "No item opens it: " + stack);
        }
        LockCode copy = LockCode.CODEC.parse(JsonOps.INSTANCE, LockCode.CODEC.encodeStart(JsonOps.INSTANCE, lock).getOrThrow()).getOrThrow();
        check(ContainerLocks.isSmartLock(copy) && !ContainerLocks.foreign(copy), "Read back from its saved form it is still the Smart Lock's");
        //? if >=1.21.2 {
        LockCode mapMaker = LockCode.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"count\":{\"min\":2}}")).getOrThrow();
        //?} else {
        /*LockCode mapMaker = new LockCode("Gate key");
        *///?}
        check(ContainerLocks.foreign(mapMaker) && !ContainerLocks.isSmartLock(mapMaker) && !ContainerLocks.foreign(LockCode.NO_LOCK), "A map maker's lock is foreign");
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}

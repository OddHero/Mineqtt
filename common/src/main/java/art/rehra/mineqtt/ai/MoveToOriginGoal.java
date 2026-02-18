package art.rehra.mineqtt.ai;

import art.rehra.mineqtt.config.MineQTTConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;

import java.util.EnumSet;

public class MoveToOriginGoal extends Goal {
    private static final double GOAL_REACH_SQR = 4.0;
    private static final int MAX_RECURSION_DEPTH = 16;
    private static final int RETRY_INTERVAL_TICKS = 200; // ~10s at 20 tps

    private final PathfinderMob mob;
    private final double speedModifier;
    // Internal state
    private BlockPos currentTarget;
    private int retryCooldownTicks = 0;
    public MoveToOriginGoal(PathfinderMob mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    private static BlockPos halfway(BlockPos a, BlockPos b) {
        int x = (a.getX() + b.getX()) >> 1;
        int y = (a.getY() + b.getY()) >> 1;
        int z = (a.getZ() + b.getZ()) >> 1;
        return new BlockPos(x, y, z);
    }

    private BlockPos getFinalTarget() {
        return new BlockPos(MineQTTConfig.goalX, MineQTTConfig.goalY, MineQTTConfig.goalZ);
    }

    @Override
    public boolean canUse() {
        if (!MineQTTConfig.zombieGoalEnabled) return false;
        BlockPos finalTarget = getFinalTarget();
        // Run when there is no combat target and we're not already close to the final target
        return mob.getTarget() == null && distanceToSqr(finalTarget) > GOAL_REACH_SQR;
    }

    @Override
    public boolean canContinueToUse() {
        if (!MineQTTConfig.zombieGoalEnabled) return false;
        BlockPos finalTarget = getFinalTarget();
        // Keep this goal active until we get close to the final target or a combat target appears
        return mob.getTarget() == null && distanceToSqr(finalTarget) > GOAL_REACH_SQR;
    }

    @Override
    public void start() {
        BlockPos finalTarget = getFinalTarget();
        // First try the actual goal. If unreachable, pick a halfway reachable sub‑goal toward it.
        if (!tryMoveTo(finalTarget)) {
            BlockPos sub = computeReachableToward(finalTarget);
            if (sub != null) {
                tryMoveTo(sub);
            }
            // Set a cooldown before trying the final goal again to allow incremental progress
            this.retryCooldownTicks = RETRY_INTERVAL_TICKS;
        } else {
            this.retryCooldownTicks = RETRY_INTERVAL_TICKS;
        }
    }

    @Override
    public void tick() {
        if (mob.getTarget() != null) return; // Abort if combat target appears

        BlockPos finalTarget = getFinalTarget();
        // If we're close enough to the final target, stop
        if (distanceToSqr(finalTarget) <= GOAL_REACH_SQR) {
            mob.getNavigation().stop();
            return;
        }

        if (retryCooldownTicks > 0) retryCooldownTicks--;

        // If navigation finished or isn't making progress, pick a new (sub)target
        if (mob.getNavigation().isDone() || !mob.getNavigation().isInProgress()) {
            // Periodically reattempt the real final goal
            if (retryCooldownTicks <= 0 && tryMoveTo(finalTarget)) {
                retryCooldownTicks = RETRY_INTERVAL_TICKS;
                return;
            }

            // Otherwise, pick the best reachable sub‑goal toward the final target and move to it
            BlockPos sub = computeReachableToward(finalTarget);
            if (sub != null) {
                tryMoveTo(sub);
            }
        }
    }

    @Override
    public void stop() {
        this.mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    private boolean tryMoveTo(BlockPos pos) {
        Path path = this.mob.getNavigation().createPath(pos, 0);
        if (path != null) {
            this.mob.getNavigation().moveTo(path, this.speedModifier);
            this.currentTarget = pos;
            return true;
        }
        return false;
    }

    private BlockPos computeReachableToward(BlockPos desired) {
        BlockPos from = this.mob.blockPosition();
        return findReachableToward(from, desired, 0);
    }

    private BlockPos findReachableToward(BlockPos from, BlockPos to, int depth) {
        if (depth > MAX_RECURSION_DEPTH) return null;

        // If we can path to the desired pos, use it
        if (canPathTo(to)) return to;

        // If we're already essentially there, give up (no better sub‑goal)
        if (from.equals(to) || from.distManhattan(to) <= 1) return null;

        // Recurse toward the midpoint
        BlockPos mid = halfway(from, to);
        return findReachableToward(from, mid, depth + 1);
    }

    private boolean canPathTo(BlockPos pos) {
        Path path = this.mob.getNavigation().createPath(pos, 0);
        return path != null;
    }

    private double distanceToSqr(BlockPos pos) {
        return mob.distanceToSqr(pos.getX(), pos.getY(), pos.getZ());
    }
}

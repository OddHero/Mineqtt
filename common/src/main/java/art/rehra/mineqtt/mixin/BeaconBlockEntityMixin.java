package art.rehra.mineqtt.mixin;

import art.rehra.mineqtt.blocks.SignalBeaconBlock;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The Signal Beacon's beam colour: in the beacon's beam scan, the one {@code Level.getBlockState} call of
 * {@code BeaconBlockEntity.tick} (on 1.21.1 to 26.3, Fabric and NeoForge alike), a Signal Beacon reads as stained glass
 * of its colour while lit and as tinted glass while not ({@link SignalBeaconBlock#beamStandIn}). Vanilla then tints or
 * stops the beam itself: Fabric through {@code BeaconBeamBlock#getColor}, NeoForge through its patched
 * {@code getBeaconColorMultiplier}, both through the light dampening. It runs in the client's own beacon tick too, which
 * draws the beam from the synced block state. Any other block reads as itself, so beacons without a Signal Beacon above
 * them are untouched.
 * <p>
 * A MixinExtras wrap rather than a {@code @Redirect}: other mods that wrap the same call still apply, and MixinExtras
 * 0.5.3 (shipped with NeoForge 21.11) fails the server's start on a {@code @Redirect} here.
 */
@Mixin(BeaconBlockEntity.class)
public abstract class BeaconBlockEntityMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private static BlockState mineqtt$signalBeaconBeam(Level level, BlockPos pos, Operation<BlockState> original) {
        return SignalBeaconBlock.beamStandIn(original.call(level, pos));
    }
}

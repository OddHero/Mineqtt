package art.rehra.mineqtt.mixin;

import art.rehra.mineqtt.blocks.SmartLocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hoppers and hopper minecarts pull nothing out of a container a Smart Lock locks ({@link SmartLocks#refusesPull}).
 * Both pull through the static {@code HopperBlockEntity.suckInItems(Level, Hopper)}, the same method on 1.21.1 to 26.3,
 * Fabric and NeoForge alike; at its head the pull ends before any path takes an item: vanilla's slots, and NeoForge's
 * item handler of the container above, which 1.21.1 to 1.21.3 ask before the vanilla container. The container above is
 * found as vanilla finds it, one block above the hopper's own point. A locked container keeps whatever falls on top of
 * it, as vanilla never picks up items above a container. Pushing into a locked container, and a locked hopper's own
 * pulling and pushing, stay as they are.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {
    @Inject(method = "suckInItems", at = @At("HEAD"), cancellable = true)
    private static void mineqtt$lockedSource(Level level, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
        BlockPos source = BlockPos.containing(hopper.getLevelX(), hopper.getLevelY() + 1.0, hopper.getLevelZ());
        if (SmartLocks.refusesPull(level, source)) cir.setReturnValue(false);
    }
}

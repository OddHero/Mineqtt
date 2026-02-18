package art.rehra.mineqtt.mixin;

import art.rehra.mineqtt.config.MineQTTConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.Portal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "setAsInsidePortal", at = @At("HEAD"), cancellable = true)
    private void mineqtt$onSetAsInsidePortal(Portal portal, BlockPos pos, CallbackInfo ci) {
        if ((Object) this instanceof ItemEntity && !MineQTTConfig.allowItemNetherPortalTeleport) {
            if (portal instanceof NetherPortalBlock) {
                ci.cancel();
            }
        }
    }
}

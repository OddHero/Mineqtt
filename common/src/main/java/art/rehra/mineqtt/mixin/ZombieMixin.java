package art.rehra.mineqtt.mixin;

import art.rehra.mineqtt.ai.MoveToOriginGoal;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Zombie.class)
public abstract class ZombieMixin extends net.minecraft.world.entity.monster.Monster {

    protected ZombieMixin(EntityType<? extends net.minecraft.world.entity.monster.Monster> entityType, Level level) {
        super(entityType, level);
    }

    @Inject(method = "registerGoals", at = @At("TAIL"))
    protected void onRegisterGoals(CallbackInfo ci) {
        this.goalSelector.addGoal(5, new MoveToOriginGoal(this, 1.0D));
    }
}

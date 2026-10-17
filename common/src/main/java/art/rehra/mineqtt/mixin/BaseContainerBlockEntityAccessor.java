package art.rehra.mineqtt.mixin;

import net.minecraft.world.LockCode;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads and sets a container's vanilla lock ({@code lockKey}), which vanilla only takes from saved data and item
 * components; the Smart Lock sets and lifts its own lock through {@code compat/ContainerLocks}. An accessor only: no
 * behaviour of the container changes.
 */
@Mixin(BaseContainerBlockEntity.class)
public interface BaseContainerBlockEntityAccessor {
    @Accessor("lockKey")
    LockCode mineqtt$getLockKey();

    @Accessor("lockKey")
    void mineqtt$setLockKey(LockCode lockKey);
}

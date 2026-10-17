package art.rehra.mineqtt.compat;

import art.rehra.mineqtt.mixin.BaseContainerBlockEntityAccessor;
import com.google.gson.JsonElement;
//? if >=1.21.2 {
import com.google.gson.JsonParser;
//?}
import com.mojang.serialization.JsonOps;
import net.minecraft.world.LockCode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;

import java.util.Optional;

/**
 * The vanilla container lock ({@link LockCode}) the Smart Lock puts on the containers it locks, so vanilla refuses them
 * with its own "is locked" message and sound, also on paths that open a container's menu past the right-click.
 * <p>
 * The lock must open for no item, as the players the Smart Lock lets in never hold a key: before 1.21.2 a lock is a
 * name an item must carry, so it is a name no anvil can type (it starts with a formatting code, which the anvil strips);
 * from 1.21.2 on it is an item predicate, so it is one over an empty item list, which no item is in. It is recognised by
 * its saved form, so the Smart Lock lifts and clears only its own lock and never a map maker's.
 */
public final class ContainerLocks {
    //? if >=1.21.2 {
    /** The saved form of the Smart Lock's lock: an item predicate no item matches. */
    public static final String SAVED_FORM = "{\"items\":[]}";
    //?} else {
    /*// The Smart Lock's lock: a custom name no anvil can type, as it starts with a formatting code.
    public static final String LEGACY_KEY = "§rmineqtt:smart_lock";
    *///?}

    /** The Smart Lock's lock; never {@link LockCode#NO_LOCK}. */
    public static final LockCode SMART_LOCK = smartLock();

    private ContainerLocks() {
    }

    private static LockCode smartLock() {
        //? if >=1.21.2 {
        return LockCode.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(SAVED_FORM)).getOrThrow();
        //?} else {
        /*return new LockCode(LEGACY_KEY);
        *///?}
    }

    /**
     * Whether {@code code} is the Smart Lock's lock, by its saved form. Hoppers ask it on every pull, so no lock and the
     * Smart Lock's own instance are answered without encoding.
     */
    public static boolean isSmartLock(LockCode code) {
        if (code == SMART_LOCK) return true;
        if (code == LockCode.NO_LOCK || code.equals(LockCode.NO_LOCK)) return false;
        Optional<JsonElement> saved = LockCode.CODEC.encodeStart(JsonOps.INSTANCE, code).result();
        Optional<JsonElement> ours = LockCode.CODEC.encodeStart(JsonOps.INSTANCE, SMART_LOCK).result();
        return saved.isPresent() && saved.equals(ours);
    }

    /** Whether the lock opens for {@code stack} held in the main hand, as vanilla asks it. */
    public static boolean opensWith(LockCode code, ItemStack stack) {
        return code.unlocksWith(stack);
    }

    /** The container's vanilla lock. */
    public static LockCode lock(BaseContainerBlockEntity container) {
        return ((BaseContainerBlockEntityAccessor) container).mineqtt$getLockKey();
    }

    /** Whether the container carries a lock that is not the Smart Lock's (a map maker's, set with {@code /data}). */
    public static boolean foreign(BaseContainerBlockEntity container) {
        return foreign(lock(container));
    }

    /** Whether {@code lock} is a lock that is not the Smart Lock's. */
    public static boolean foreign(LockCode lock) {
        return !lock.equals(LockCode.NO_LOCK) && !isSmartLock(lock);
    }

    /**
     * Whether the container carries the Smart Lock's lock. A lock read back from saved data is swapped for the Smart
     * Lock's own instance, which saves the same, so the next question is answered without encoding.
     */
    public static boolean locked(BaseContainerBlockEntity container) {
        LockCode lock = lock(container);
        if (lock == SMART_LOCK) return true;
        if (!isSmartLock(lock)) return false;
        ((BaseContainerBlockEntityAccessor) container).mineqtt$setLockKey(SMART_LOCK);
        return true;
    }

    /**
     * Puts the Smart Lock's lock on the container, unless it carries another lock, which stays. Saved with the chunk.
     *
     * @return whether the container now carries the Smart Lock's lock
     */
    public static boolean apply(BaseContainerBlockEntity container) {
        LockCode lock = lock(container);
        if (isSmartLock(lock)) return true;
        if (!lock.equals(LockCode.NO_LOCK)) return false;
        set(container, SMART_LOCK);
        return true;
    }

    /**
     * Takes the Smart Lock's lock off the container; another lock stays.
     *
     * @return whether a Smart Lock's lock was taken off
     */
    public static boolean clear(BaseContainerBlockEntity container) {
        if (!isSmartLock(lock(container))) return false;
        set(container, LockCode.NO_LOCK);
        return true;
    }

    /** Sets the container's lock without asking what it was; for lifting the Smart Lock's lock around one use. */
    public static void set(BaseContainerBlockEntity container, LockCode lock) {
        ((BaseContainerBlockEntityAccessor) container).mineqtt$setLockKey(lock);
        container.setChanged();
    }
}

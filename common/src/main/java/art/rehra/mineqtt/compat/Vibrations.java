package art.rehra.mineqtt.compat;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.gameevent.GameEvent;
import org.jetbrains.annotations.Nullable;

/**
 * What the Vibration Sensor asks of a vibration. Vanilla's vibration system ({@code VibrationSystem}, its {@code User},
 * {@code Listener} and {@code Ticker}, {@code GameEventListener.Provider}, {@code EntityBlock#getListener}) and game events
 * as holders are the same from 1.21.1 to 26.3; the one difference, {@code minecraft:bounce} (from 1.21.5 on), is why game
 * events are told apart by their ids rather than their constants. The id is read through {@code ResourceKey#identifier},
 * which the build spells {@code location} before 1.21.11.
 */
public final class Vibrations {
    /** The id of a game event without a registry key, which no vibration has. */
    public static final String UNREGISTERED = "";

    private Vibrations() {
    }

    /** The game event's id, e.g. {@code minecraft:step}. */
    public static String id(Holder<GameEvent> event) {
        return event.unwrapKey().map(key -> key.identifier().toString()).orElse(UNREGISTERED);
    }

    /**
     * Whether a player caused what this entity did: the player, their projectile, an item they threw, or the mount they
     * steer. The answer is all the sensor keeps of the entity.
     */
    public static boolean causedByPlayer(@Nullable Entity entity) {
        if (entity == null) return false;
        if (entity instanceof Player) return true;
        if (entity instanceof Projectile projectile) return projectile.getOwner() instanceof Player;
        if (entity instanceof ItemEntity item) return item.getOwner() instanceof Player;
        return entity.getControllingPassenger() instanceof Player;
    }

    /** Whether a dropped item caused the vibration, e.g. by landing. */
    public static boolean item(@Nullable Entity entity) {
        return entity instanceof ItemEntity;
    }

    /** Whether a projectile caused the vibration, e.g. by landing. */
    public static boolean projectile(@Nullable Entity entity) {
        return entity instanceof Projectile;
    }
}

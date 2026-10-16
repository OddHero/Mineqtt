package art.rehra.mineqtt.compat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.item.DyeColor;
//? if >=1.21.6 {
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueOutput;
//?}
//? if >=1.21.5 {
import net.minecraft.world.entity.animal.sheep.Sheep;
//?} else {
/*import net.minecraft.world.entity.animal.Sheep;
*///?}
//? if >=1.21.11 {
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerDataHolder;
//?} else {
/*import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerDataHolder;
*///?}

import java.util.Optional;

/**
 * Entity accessors that moved or changed between versions: the saved data (a {@code ValueOutput} from 1.21.6 on), whether
 * an ownable entity has an owner (an entity reference from 1.21.5 on), the sheep (its own package from 1.21.5 on) and the
 * villager data (records from 1.21.5 on, their own package from 1.21.11 on).
 */
public final class Entities {

    private Entities() {
    }

    /** What the entity saves, without its type id; the data a {@code /data get entity} shows. */
    public static CompoundTag save(Entity entity) {
        //? if >=1.21.6 {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
        entity.saveWithoutId(output);
        return output.buildResult();
        //?} else {
        /*return entity.saveWithoutId(new CompoundTag());
        *///?}
    }

    /** Whether an entity that can have an owner (a tamed wolf, a horse) has one; who it is stays unasked. */
    public static boolean hasOwner(OwnableEntity entity) {
        //? if >=1.21.5 {
        return entity.getOwnerReference() != null;
        //?} else {
        /*return entity.getOwnerUUID() != null;
        *///?}
    }

    /** A sheep's wool colour; empty for any other entity. */
    public static Optional<DyeColor> sheepColor(Entity entity) {
        return entity instanceof Sheep sheep ? Optional.of(sheep.getColor()) : Optional.empty();
    }

    /** Whether a sheep is sheared; empty for any other entity. */
    public static Optional<Boolean> sheared(Entity entity) {
        return entity instanceof Sheep sheep ? Optional.of(sheep.isSheared()) : Optional.empty();
    }

    /** A villager's or zombie villager's profession id, such as {@code minecraft:farmer}; empty for any other entity. */
    public static Optional<String> villagerProfession(Entity entity) {
        if (!(entity instanceof VillagerDataHolder holder)) return Optional.empty();
        VillagerData data = holder.getVillagerData();
        //? if >=1.21.5 {
        return data.profession().unwrapKey().map(key -> key.identifier().toString());
        //?} else {
        /*return Optional.ofNullable(BuiltInRegistries.VILLAGER_PROFESSION.getKey(data.getProfession())).map(Object::toString);
        *///?}
    }

    /** A villager's or zombie villager's trade level, 1 to 5; empty for any other entity. */
    public static Optional<Integer> villagerLevel(Entity entity) {
        if (!(entity instanceof VillagerDataHolder holder)) return Optional.empty();
        //? if >=1.21.5 {
        return Optional.of(holder.getVillagerData().level());
        //?} else {
        /*return Optional.of(holder.getVillagerData().getLevel());
        *///?}
    }

    /** The id of an entity's type, such as {@code minecraft:cow}. */
    public static String typeId(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }
}

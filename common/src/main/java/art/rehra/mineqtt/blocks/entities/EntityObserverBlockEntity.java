package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.EntityObserverBlock;
import art.rehra.mineqtt.compat.ChunkPositions;
import art.rehra.mineqtt.compat.Entities;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommandResult;
import art.rehra.mineqtt.device.CommandSource;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.EntityObserver;
import art.rehra.mineqtt.device.types.EntityObserver.Observation;
import art.rehra.mineqtt.device.types.EntityObserver.Seen;
import art.rehra.mineqtt.presence.PrivacyPolicy;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Entity Observer ({@link EntityObserver}): looks at the space in front of its face every
 * {@value EntityObserver#SCAN_INTERVAL} ticks once attached. Its settings and what it saw last are in the {@code mineqtt}
 * compound. Clients get no render data.
 */
public class EntityObserverBlockEntity extends DeviceBlockEntity {
    private final DeviceLink link = new DeviceLink(EntityObserver.TYPE);
    private Observation observation = Observation.DEFAULT;
    // Whether the observer looked since the attach; until then what it saw is the observation saved at the unload.
    private boolean scanned;
    // The runtime id of the nearest entity at the last look since the attach; empty for none.
    private Optional<Integer> nearestId = Optional.empty();
    // The Raw Link payload of the last look, live once the first look is done.
    private @Nullable String rawPayload;
    // A setting changed what the Raw Link carries: the next look sends it.
    private boolean rawRequested;
    // The last entity data sampled for a full payload, and the entity it belongs to.
    private @Nullable JsonObject entityData;
    private int entityDataOf = -1;
    // An entity whose data cannot be read is logged once per observer.
    private boolean dataFailureLogged;

    public EntityObserverBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.ENTITY_OBSERVER.get(), pos, blockState);
    }

    @Override
    public DeviceType deviceType() {
        return EntityObserver.TYPE;
    }

    @Override
    public DeviceLink deviceLink() {
        return link;
    }

    @Override
    public void writeState(StateWriter out) {
        observation.write(out).put(CommonFields.LOCATION, GlobalPos.of(level.dimension(), worldPosition));
    }

    @Override
    public CommandResult applyCommand(CommandReader in, CommandSource source) {
        Optional<Observation> next = observation.apply(in);
        if (next.isEmpty()) return CommandResult.rejected("not an entity observer command: " + in.keys());
        if (next.get().equals(observation)) return CommandResult.unchanged();
        if (next.get().payload() != observation.payload()) rawRequested = true;
        observation = next.get();
        setChanged();
        return CommandResult.applied();
    }

    /**
     * The payload the Raw Link output carries, once the observer looked after the attach: until then what it saw is what
     * it saved at the unload, which a retained publish must never repeat.
     */
    @Override
    public @Nullable String rawValue() {
        return rawPayload;
    }

    /**
     * Looks at the space in front, unless the entities of any watched block space are not loaded: the observer never loads
     * them, nor reports unloaded entities as gone. The state follows the entities it sees; another nearest entity, or the
     * last one leaving, is a {@code changed} event, and the Raw Link payload goes out when it changed. Entity data for a
     * {@code full} payload is sampled every {@value EntityObserver#DATA_SAMPLE_TICKS} ticks, at a change, and when the
     * nearest entity is another one.
     */
    private void scan(ServerLevel level, BlockState state) {
        Direction facing = state.getValue(EntityObserverBlock.FACING);
        for (BlockPos space : EntityObserver.spaces(worldPosition, facing, observation.range())) {
            if (!level.areEntitiesLoaded(ChunkPositions.pack(space))) return;
        }
        AABB area = EntityObserver.area(worldPosition, facing, observation.range());
        Vec3 face = EntityObserver.face(worldPosition, facing);
        EntityObserver.Detect detect = observation.detect();
        List<Entity> found = level.getEntities((Entity) null, area,
                entity -> entity.isAlive() && !entity.isSpectator() && detect.counts(entity instanceof Player, entity instanceof LivingEntity));
        Entity nearest = found.stream().min(Comparator.comparingDouble((Entity entity) -> entity.distanceToSqr(face)).thenComparingInt(Entity::getId))
                .orElse(null);
        Seen seen = nearest == null ? Seen.NOTHING : seen(nearest, face);
        Optional<Integer> nearestNow = Optional.ofNullable(nearest).map(Entity::getId);

        // The first look after the attach compares with what the observer saw before it unloaded; a new observer has seen
        // nothing, so its first look is no change.
        boolean first = !scanned;
        boolean changed = first ? observation.looked() && !observation.entity().equals(seen.entity()) : EntityObserver.changed(nearestId, nearestNow);
        scanned = true;
        nearestId = nearestNow;

        Observation next = observation.looked(found.size(), seen);
        if (!next.equals(observation)) {
            observation = next;
            setChanged();
            link.stateChanged(this);
        }
        if (changed) link.publishEvent(this, EntityObserver.changedEvent(next.entity()));

        boolean full = observation.payload() == EntityObserver.Payload.FULL;
        boolean exports = full && nearest != null && link.raw() != null && EntityObserver.exportsEntityData(MineQTTConfig.entityObserverExportEntityData,
                nearest instanceof Player, MineQTT.permissionManager.sameClaim(level, worldPosition, nearest.blockPosition()));
        if (!exports) {
            entityData = null;
            entityDataOf = -1;
        } else if (entityData == null || entityDataOf != nearest.getId() || changed || first || rawRequested
                || level.getGameTime() % EntityObserver.DATA_SAMPLE_TICKS == 0) {
            entityData = entityData(nearest);
            entityDataOf = nearest.getId();
        }
        Vec3 position = nearest == null || nearest instanceof Player ? null : nearest.position();
        String payload = EntityObserver.payload(observation, position, level.dimension().identifier().toString(), exports ? entityData : null);
        if (changed || first || rawRequested || !payload.equals(rawPayload)) link.publishRaw(this, payload);
        rawPayload = payload;
        rawRequested = false;
    }

    // What the observer reports about one entity; a player only as their privacy choice and the server allow.
    private static Seen seen(Entity entity, Vec3 face) {
        if (entity instanceof Player player) {
            return Seen.player(shared(player), player.getType().getDescription().getString(), player.getGameProfile().name());
        }
        boolean living = entity instanceof LivingEntity;
        LivingEntity mob = living ? (LivingEntity) entity : null;
        return Seen.entity(Entities.typeId(entity), entity.getName().getString(), living, living ? mob.getHealth() : 0, living ? mob.getMaxHealth() : 0,
                Math.sqrt(entity.distanceToSqr(face)), details(entity, mob));
    }

    // What is shared about a player now: their effective consent within the server's settings (M4 plan D9).
    private static PrivacyPolicy.Shared shared(Player player) {
        return MineqttRuntime.current().map(runtime -> runtime.privacy().policy().shared(runtime.privacy().effective(player.getUUID())))
                .orElse(PrivacyPolicy.Shared.NOTHING);
    }

    // The type-specific details: only what the entity has, no owner, thrower or other player behind it.
    private static JsonObject details(Entity entity, @Nullable LivingEntity mob) {
        EntityObserver.Details details = new EntityObserver.Details()
                .text("custom_name", entity.hasCustomName() && entity.getCustomName() != null ? entity.getCustomName().getString() : null)
                .flag("baby", mob == null ? null : mob.isBaby())
                .flag("tamed", entity instanceof OwnableEntity ownable ? Entities.hasOwner(ownable) : null)
                .flag("sitting", entity instanceof TamableAnimal tamable ? tamable.isInSittingPose() : null)
                .number("armor", mob == null ? null : mob.getArmorValue())
                .flag("on_fire", entity.isOnFire())
                .flag("invisible", entity.isInvisible())
                .number("passengers", entity.getPassengers().size());
        Entity vehicle = entity.getVehicle();
        details.text("vehicle", vehicle == null ? null : vehicle instanceof Player ? EntityObserver.PLAYER : Entities.typeId(vehicle))
                .text("color", Entities.sheepColor(entity).map(DyeColor::getSerializedName).orElse(null))
                .flag("sheared", Entities.sheared(entity).orElse(null))
                .text("profession", Entities.villagerProfession(entity).orElse(null))
                .number("level", Entities.villagerLevel(entity).orElse(null));
        if (entity instanceof ItemEntity item) {
            ItemStack stack = item.getItem();
            details.text("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()).number("item_count", stack.getCount());
        }
        return details.build();
    }

    // The entity's data as a full payload may carry it: its type id and what it saves, without UUIDs that name others.
    private @Nullable JsonObject entityData(Entity entity) {
        try {
            JsonObject data = new JsonObject();
            data.addProperty("id", Entities.typeId(entity));
            JsonElement saved = NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, Entities.save(entity));
            if (saved instanceof JsonObject object) {
                for (Map.Entry<String, JsonElement> entry : object.entrySet()) data.add(entry.getKey(), entry.getValue());
            }
            return EntityObserver.withoutForeignUuids(data);
        } catch (RuntimeException e) {
            // A modded entity whose save fails must not stop the server tick; the payload then has no entity data.
            if (!dataFailureLogged) MineQTT.LOGGER.warn("[EntityObserver] Cannot read the data of {} at {}", Entities.typeId(entity), entity.blockPosition(), e);
            dataFailureLogged = true;
            return null;
        }
    }

    @Override
    protected void saveDevice(ValueOutput output) {
        link.save(output).store("state", Observation.CODEC, observation);
    }

    @Override
    protected void loadDevice(ValueInput input) {
        observation = link.load(input, false).flatMap(data -> data.read("state", Observation.CODEC)).orElse(Observation.DEFAULT);
        forget();
    }

    /** A placed copy keeps its settings, and has seen nothing yet. */
    @Override
    protected void placedCopy() {
        observation = observation.placed();
        forget();
    }

    private void forget() {
        scanned = false;
        nearestId = Optional.empty();
        rawPayload = null;
        entityData = null;
        entityDataOf = -1;
    }

    /** A look every {@value EntityObserver#SCAN_INTERVAL} ticks once the device is attached. */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (!(blockEntity instanceof EntityObserverBlockEntity observer) || !(level instanceof ServerLevel serverLevel)) return;
            if (observer.link.attached() && level.getGameTime() % EntityObserver.SCAN_INTERVAL == 0) observer.scan(serverLevel, state);
        }
    }
}

package art.rehra.mineqtt.device;

import art.rehra.mineqtt.legacy.LegacyNbtV1;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.security.AccessMode;
import art.rehra.mineqtt.security.AclContext;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * A device block entity's identity and lifecycle, by composition (design §6.5). It holds the device's copy of its
 * identity (id, name, slug; the registry is authoritative for name and slug), its owner and access mode, its Home
 * Assistant exposure and its Raw Link, all saved in the {@code mineqtt} compound.
 * <p>
 * Lifecycle: {@code BlockEntity#setLevel} schedules the attach on the next server tick, {@code setRemoved} detaches
 * (unload) or destroys (the block was replaced), {@code preRemoveSideEffects} destroys. Only the server runs any of
 * it; loading and saving only (de)serialise data.
 * <p>
 * Data without a {@code mineqtt} compound is v1 data, and so is a placed v1 item's ({@link #load}): loading keeps it as a
 * {@link LegacyNbtV1.Snapshot}, which the attach adopts (design §9), or the placement at once. Until then the block
 * entity saves its v1 keys, so it is still v1 data if it unloads first.
 */
public final class DeviceLink {
    public static final String KEY = "mineqtt";
    /** The version of the {@code mineqtt} compound. */
    public static final int VERSION = 2;

    /** What removing a block entity means for its device. */
    public enum Removal {
        /** The chunk unloads; the device stays registered. */
        DETACH,
        /** The block is gone; the device is forgotten. */
        DESTROY
    }

    private final DeviceType type;
    private @Nullable UUID id;
    private String name = "";
    private String slug = "";
    private @Nullable UUID owner;
    private String ownerName = "";
    private AccessMode access = AccessMode.DEFAULT;
    private @Nullable Boolean expose;
    private @Nullable RawLink raw;
    // Runtime state, never saved.
    private LegacyNbtV1.@Nullable Snapshot pendingV1;
    private boolean attached;
    private boolean placedByPlayer;
    private boolean removalHandled;

    public DeviceLink(DeviceType type) {
        this.type = type;
    }

    /** Only a block entity in a server level has a device; client copies never touch MQTT. */
    public static boolean shouldAttach(@Nullable Level level) {
        return level instanceof ServerLevel;
    }

    /**
     * The fallback in {@code setRemoved} for removals that skip {@code preRemoveSideEffects} ({@code /setblock},
     * {@code /fill}, {@code /clone}): a block replaced in a chunk that is still loaded destroys the device; anything
     * else, notably a chunk unloading, only detaches it.
     *
     * @param chunkLoaded   whether the chunk is still loaded ({@code getChunkNow} returned it)
     * @param blockReplaced whether a different block now stands at the position
     */
    public static Removal removal(boolean chunkLoaded, boolean blockReplaced) {
        return chunkLoaded && blockReplaced ? Removal.DESTROY : Removal.DETACH;
    }

    /**
     * Reads the {@code mineqtt} compound, or keeps v1 data for the attach to adopt.
     * <p>
     * Placing an item merges its block entity data into the save of the fresh block entity before loading the result
     * ({@code TypedEntityData.loadInto}), so v1 item data arrives next to the fresh block entity's own compound, which
     * has no id yet. The type's v1 keys, which v2 data never has, tell it apart.
     *
     * @param v1TypeKeys whether the input holds the type's v1 keys. A type whose v1 data had no keys of its own passes
     *                   true: only a compound with a device id is v2 data then, which every device saved after its
     *                   placement or attach has
     * @return the compound, for the block entity's own keys ({@code state}); empty for v1 data, whose type keys the
     * block entity reads itself
     */
    public Optional<ValueInput> load(ValueInput input, boolean v1TypeKeys) {
        Optional<ValueInput> compound = input.child(KEY).filter(data -> !v1TypeKeys || data.read("id", UUIDUtil.CODEC).isPresent());
        pendingV1 = compound.isPresent() ? null : LegacyNbtV1.read(input);
        compound.ifPresent(data -> {
            id = data.read("id", UUIDUtil.CODEC).orElse(null);
            name = data.getStringOr("name", "");
            slug = data.getStringOr("slug", "");
            owner = data.read("owner", UUIDUtil.CODEC).orElse(null);
            ownerName = data.getStringOr("ownerName", "");
            access = data.read("access", AccessMode.CODEC).orElse(AccessMode.DEFAULT);
            expose = data.read("expose", Codec.BOOL).orElse(null);
            raw = data.read("raw", RawLink.CODEC).orElse(null);
        });
        return compound;
    }

    /**
     * Writes the {@code mineqtt} compound and returns it, so the block entity can add its own keys. Not while
     * {@link #awaitingAdoption()}: the block entity then writes its v1 keys.
     */
    public ValueOutput save(ValueOutput output) {
        if (pendingV1 != null) throw new IllegalStateException("v1 data that is not adopted yet is saved as v1 data");
        ValueOutput data = output.child(KEY);
        data.putInt("v", VERSION);
        data.storeNullable("id", UUIDUtil.CODEC, id);
        data.putString("type", type.id().toString());
        if (!name.isEmpty()) data.putString("name", name);
        if (!slug.isEmpty()) data.putString("slug", slug);
        data.storeNullable("owner", UUIDUtil.CODEC, owner);
        if (!ownerName.isEmpty()) data.putString("ownerName", ownerName);
        data.store("access", AccessMode.CODEC, access);
        data.storeNullable("expose", Codec.BOOL, expose);
        data.storeNullable("raw", RawLink.CODEC, raw);
        return data;
    }

    /** From {@code BlockEntity#setLevel}: placement, chunk load, {@code /clone}, structures. */
    public void onSetLevel(BlockEntity blockEntity, Level level) {
        if (!shouldAttach(level)) return;
        removalHandled = false;
        DeviceRuntime.scheduleAttach((ServerLevel) level, blockEntity);
    }

    /**
     * From {@code Block#setPlacedBy}, after the item's block entity data was applied and before the attach: that data
     * is untrusted (§8.6), so the copied identity is dropped, the placer becomes the owner and the Raw Link is checked
     * against the placer's rights.
     */
    public void onPlacedBy(BlockEntity blockEntity, @Nullable LivingEntity placer) {
        if (!(blockEntity.getLevel() instanceof ServerLevel level)) return;
        ServerPlayer player = placer instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        TopicAcl.Subject subject = player == null ? AclContext.NOBODY : AclContext.of(player);
        Optional<TopicAcl> acl = MineqttRuntime.current().filter(running -> running.server() == level.getServer()).map(MineqttRuntime::acl);
        sanitizePlaced(player == null ? null : player.getUUID(), player == null ? "" : player.getGameProfile().name(), link -> acl
                .map(rules -> DeviceRuntime.refusal(rules.checkPublish(subject, link.topic(), null)))
                .orElse(Optional.of("MineQTT is not running")));
        blockEntity.setChanged();
    }

    /** From {@code BlockEntity#setRemoved}: unload, or the fallback destroy for a replaced block. */
    public void onSetRemoved(BlockEntity blockEntity) {
        if (!(blockEntity.getLevel() instanceof ServerLevel level) || removalHandled) return;
        removalHandled = true;
        BlockPos pos = blockEntity.getBlockPos();
        // getChunkNow never loads a chunk; during an unload it returns null or the chunk with the block still in it.
        LevelChunk chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
        boolean replaced = chunk != null && !chunk.getBlockState(pos).is(blockEntity.getBlockState().getBlock());
        DeviceRuntime.removed(level, blockEntity, removal(chunk != null, replaced));
    }

    /** From {@code BlockEntity#preRemoveSideEffects}: broken by a player or an explosion. */
    public void onPreRemoveSideEffects(BlockEntity blockEntity) {
        if (!(blockEntity.getLevel() instanceof ServerLevel level) || removalHandled) return;
        removalHandled = true;
        DeviceRuntime.removed(level, blockEntity, Removal.DESTROY);
    }

    /** Publishes the device's state after the block entity changed it; nothing happens while it is not attached. */
    public void stateChanged(BlockEntity blockEntity) {
        DeviceRuntime.ifAttached(blockEntity, (devices, host) -> devices.publishState(host, false));
    }

    /** Publishes an event on the device's {@code action} topic, e.g. {@code {"event_type":"pulse","signal":11}}. */
    public void publishEvent(BlockEntity blockEntity, JsonObject event) {
        DeviceRuntime.ifAttached(blockEntity, (devices, host) -> devices.publishEvent(host, event));
    }

    /** Opens the Raw Link again after the block entity changed it ({@link #setRaw}); attach opens it otherwise. */
    public void rawChanged(BlockEntity blockEntity) {
        DeviceRuntime.ifAttached(blockEntity, DeviceRuntime::rawChanged);
    }

    /** Publishes to the device's Raw Link output, if it has an active one. */
    public void publishRaw(BlockEntity blockEntity, String payload) {
        DeviceRuntime.ifAttached(blockEntity, (devices, host) -> devices.publishRaw(host, payload));
    }

    /**
     * The untrusted-data rule of a placement: no copied id (a fresh one is minted), the placer owns it, access follows
     * the claim, the exposure follows the policy default, grandfathering is dropped and the Raw Link is checked for the
     * placer.
     * <p>
     * The fresh id is minted here, not at the attach: data saved before the attach must not read as v1 data when it
     * loads again, which the Redstone Input tells by a missing id (its v1 data has no keys of its own), so a reload in
     * between is an unseen id that the attach checks again.
     *
     * @param check the refusal for a Raw Link topic, empty when allowed
     */
    void sanitizePlaced(@Nullable UUID placer, String placerName, Function<RawLink, Optional<String>> check) {
        // v1 data from an item is adopted now, as a placement: no grandfather flag, no v1 echo (§8.6).
        if (pendingV1 != null) adopt(takePendingV1(), false);
        id = UUID.randomUUID();
        name = "";
        slug = "";
        owner = placer;
        ownerName = placerName;
        access = AccessMode.DEFAULT;
        expose = null;
        if (raw != null) raw = raw.withoutGrandfather();
        revalidateRaw(check);
        placedByPlayer = true;
    }

    /**
     * The second check, at the first attach of an id the registry has never seen ({@code /clone}, structures, a lost
     * registry), for the owner in the NBT: an exposure the owner may not grant follows the policy default again (hiding
     * needs no right), and the Raw Link is checked.
     *
     * @param ownerMayExpose whether the owner holds the right to expose devices
     * @param check          the refusal for a Raw Link topic, empty when allowed
     */
    void revalidateUnseen(boolean ownerMayExpose, Function<RawLink, Optional<String>> check) {
        if (Boolean.TRUE.equals(expose) && !ownerMayExpose) expose = null;
        revalidateRaw(check);
    }

    private void revalidateRaw(Function<RawLink, Optional<String>> check) {
        if (raw != null) raw = raw.checked(check.apply(raw).orElse(null));
    }

    /**
     * Adopts v1 data (§9.4): the v1 owner name, resolved to a UUID later, and the v1 topic as the Raw Link with its
     * custom payloads, if the type has them. Only data adopted on chunk load is {@code trusted}: its topic is
     * grandfathered for its owner, and an input echoes its state to the v1 state topic.
     */
    void adopt(LegacyNbtV1.Snapshot v1, boolean trusted) {
        if (owner == null && ownerName.isEmpty()) ownerName = v1.ownerName();
        RawLinkSpec spec = type.rawLink().orElse(null);
        if (spec == null || v1.combinedTopic().isEmpty()) return;
        String topic = v1.combinedTopic();
        ValueCodecs.CustomPayloads custom = spec.customPayloads() ? v1.custom() : ValueCodecs.CustomPayloads.NONE;
        raw = new RawLink(topic, spec.direction(), custom.on(), custom.off(), 0, false,
                trusted ? new TopicAcl.Grandfather(topic, owner) : null, trusted && spec.echoes(), null);
    }

    /** Whether the block entity holds v1 data that no attach has adopted yet. */
    public boolean awaitingAdoption() {
        return pendingV1 != null;
    }

    LegacyNbtV1.@Nullable Snapshot takePendingV1() {
        LegacyNbtV1.Snapshot v1 = pendingV1;
        pendingV1 = null;
        return v1;
    }

    /**
     * The owner a v1 owner name resolved to; a grandfather flag that waited for it is bound to it.
     *
     * @return false when the device has an owner already
     */
    boolean resolveOwner(UUID resolved) {
        if (owner != null) return false;
        owner = resolved;
        if (raw != null) raw = raw.withGrandfatherOwner(resolved);
        return true;
    }

    /**
     * A player claims a device that has neither an owner nor an owner name (the private-mode toggle, a trusted server
     * path behind the CONFIGURE check): they own it from now on, and a grandfather flag that waited for an owner is
     * bound to them, as a resolved v1 owner name binds it.
     *
     * @return false when the device has an owner, or a v1 owner name still to resolve
     */
    public boolean claim(BlockEntity blockEntity, UUID player, String playerName) {
        if (owner != null || !ownerName.isEmpty()) return false;
        resolveOwner(player);
        ownerName = playerName;
        blockEntity.setChanged();
        DeviceRuntime.ifAttached(blockEntity, DeviceRuntime::ownerChanged);
        return true;
    }

    public DeviceType type() {
        return type;
    }

    public @Nullable UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String slug() {
        return slug;
    }

    /** The registry's identity of this device; true when the copy changed and needs saving. */
    boolean assign(UUID id, String name, String slug) {
        boolean changed = !id.equals(this.id) || !name.equals(this.name) || !slug.equals(this.slug);
        this.id = id;
        this.name = name;
        this.slug = slug;
        return changed;
    }

    public @Nullable UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    /** Who besides the owner and the operators may use and configure the device. */
    public AccessMode access() {
        return access;
    }

    /** The explicit Home Assistant exposure; null follows {@code ha.expose}. */
    public @Nullable Boolean expose() {
        return expose;
    }

    /** An exposure granted without a player: migrated v1 lamps keep their Home Assistant entity (§8.8). */
    void setExpose(@Nullable Boolean expose) {
        this.expose = expose;
    }

    public @Nullable RawLink raw() {
        return raw;
    }

    /** A Raw Link a trusted path set (the GUI after its ACL check, or the v1 migrator); null removes it. */
    public void setRaw(@Nullable RawLink raw) {
        this.raw = raw;
    }

    public boolean attached() {
        return attached;
    }

    void setAttached(boolean attached) {
        this.attached = attached;
    }

    boolean placedByPlayer() {
        return placedByPlayer;
    }

    void clearPlacedByPlayer() {
        placedByPlayer = false;
    }
}

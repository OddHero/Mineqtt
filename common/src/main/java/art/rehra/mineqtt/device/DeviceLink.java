package art.rehra.mineqtt.device;

import art.rehra.mineqtt.legacy.LegacyNbtV1;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.security.AccessMode;
import art.rehra.mineqtt.security.AclContext;
import art.rehra.mineqtt.twin.BindingCodecs;
import art.rehra.mineqtt.twin.core.Binding;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * A device block entity's identity and lifecycle, by composition (design §6.5). It holds the device's copy of its
 * identity (id, name, slug; the registry is authoritative for name and slug), its owner and access mode, its Home
 * Assistant exposure, its Raw Link and its twin binding ({@link #TWIN_KEY}, only on a type with a twin adapter), all saved
 * in the {@code mineqtt} compound, and what the block keeps beside them:
 * whether its Raw Link's channel resolves into the owner's private subtree, the items its old configuration slots held
 * ({@link #legacyItems()}, M2-12) and the tab its screen last showed.
 * <p>
 * Lifecycle: {@code BlockEntity#setLevel} schedules the attach on the next server tick, {@code setRemoved} detaches
 * (unload) or destroys (the block was replaced), {@code preRemoveSideEffects} destroys. Only the server runs any of
 * it; loading and saving only (de)serialise data.
 * <p>
 * Data without a {@code mineqtt} compound is v1 data, and so is a placed v1 item's ({@link #load}): loading keeps it as a
 * {@link LegacyNbtV1.Snapshot}, which the attach adopts (design §9), or the placement at once. Until then the block
 * entity saves its v1 keys, so it is still v1 data if it unloads first.
 * <p>
 * Versions of the compound: 2 is M1's, whose block kept its slots ({@code Items}), its private mode
 * ({@code privateMode}) and its last tab ({@code lastTabId}) beside the compound; 3 keeps them in it. {@link #load}
 * migrates both, purely.
 */
public final class DeviceLink {
    public static final String KEY = "mineqtt";
    /** The version of the {@code mineqtt} compound. */
    public static final int VERSION = 3;
    /** The version of M1's compound, whose block kept its slots and private mode beside it. */
    public static final int VERSION_M1 = 2;
    /** v1 data, which had no compound. */
    public static final int VERSION_V1 = 1;
    public static final String LEGACY_ITEMS = "legacyItems";
    /** The key of the twin binding in the compound (M3 plan D14); absent for a device without one, the version stays 3. */
    public static final String TWIN_KEY = "twin";

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
    private @Nullable Binding twin;
    private boolean privateMode;
    private List<ItemStackWithSlot> legacyItems = List.of();
    private String lastTab = "";
    // Runtime state, never saved.
    private int loadedVersion = VERSION;
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
     * <p>
     * What the block kept beside its data before M2-12 is migrated here: the items of its configuration slots
     * ({@code Items}) become its {@link #legacyItems()}, and its private mode and last tab come along. v1 data keeps them
     * until it is adopted, as {@link #saveV1} writes them back as they were read; M1 data ({@value #VERSION_M1}) has them
     * beside its compound, where its next save leaves them out.
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
        if (compound.isEmpty()) {
            loadedVersion = VERSION_V1;
            privateMode = pendingV1.privateMode();
            legacyItems = LegacyNbtV1.items(input);
            lastTab = input.getStringOr(LegacyNbtV1.LAST_TAB, "");
            return compound;
        }
        ValueInput data = compound.get();
        loadedVersion = data.getIntOr("v", VERSION);
        id = data.read("id", UUIDUtil.CODEC).orElse(null);
        name = data.getStringOr("name", "");
        slug = data.getStringOr("slug", "");
        owner = data.read("owner", UUIDUtil.CODEC).orElse(null);
        ownerName = data.getStringOr("ownerName", "");
        access = data.read("access", AccessMode.CODEC).orElse(AccessMode.DEFAULT);
        expose = data.read("expose", Codec.BOOL).orElse(null);
        raw = data.read("raw", RawLink.CODEC).orElse(null);
        // Only a type with a twin adapter keeps a binding; one that breaks the binding rules reads as none.
        twin = type.twin().isPresent() ? data.read(TWIN_KEY, BindingCodecs.CODEC).orElse(null) : null;
        if (loadedVersion <= VERSION_M1) {
            privateMode = input.getBooleanOr(LegacyNbtV1.PRIVATE_MODE, false);
            legacyItems = LegacyNbtV1.items(input);
            lastTab = input.getStringOr(LegacyNbtV1.LAST_TAB, "");
        } else {
            privateMode = data.getBooleanOr("private", false);
            legacyItems = data.listOrEmpty(LEGACY_ITEMS, ItemStackWithSlot.CODEC).stream().filter(item -> !item.stack().isEmpty()).toList();
            lastTab = data.getStringOr("lastTab", "");
        }
        return compound;
    }

    /**
     * Writes the {@code mineqtt} compound and returns it, so the block entity can add its own keys. Not while
     * {@link #awaitingAdoption()}: the block entity then writes its v1 keys and {@link #saveV1}.
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
        // An unbound device writes no key, so its data stays as an M2 device's.
        data.storeNullable(TWIN_KEY, BindingCodecs.CODEC, twin);
        if (privateMode) data.putBoolean("private", true);
        if (!legacyItems.isEmpty()) {
            ValueOutput.TypedOutputList<ItemStackWithSlot> items = data.list(LEGACY_ITEMS, ItemStackWithSlot.CODEC);
            legacyItems.forEach(items::add);
        }
        if (!lastTab.isEmpty()) data.putString("lastTab", lastTab);
        return data;
    }

    /**
     * Writes the keys every v1 device wrote, as they were read, while its v1 data waits for adoption ({@link #save}
     * refuses then): its slots, owner name, private mode, topic and last tab. The block entity adds its type's v1 keys.
     */
    public void saveV1(ValueOutput output) {
        if (pendingV1 == null) throw new IllegalStateException("Only v1 data that waits for adoption is saved as v1 data");
        LegacyNbtV1.writeDevice(output, pendingV1, legacyItems, lastTab);
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
        TwinRules.Rights rights = player == null ? TwinRules.Rights.NONE : TwinRules.Rights.of(player.permissions(), level.getServer().isDedicatedServer())
                .withTopics(acl.map(rules -> TwinRules.Topics.of(rules, subject)).orElse(TwinRules.Topics.NONE));
        sanitizePlaced(player == null ? null : player.getUUID(), player == null ? "" : player.getGameProfile().name(), link -> acl
                .map(rules -> DeviceRuntime.refusal(rules.checkPublish(subject, link.topic(), null)))
                .orElse(Optional.of("MineQTT is not running")), rights);
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

    /**
     * The block entity changed something its screen shows besides the state and the Raw Link (its private mode): open
     * screens get the new view, also while the device is not attached.
     */
    public void viewChanged(BlockEntity blockEntity) {
        DeviceRuntime.viewChanged(blockEntity);
    }

    /**
     * Publishes an event on the device's {@code action} topic, e.g. {@code {"event_type":"pulse","signal":11}}; only an
     * event its type declares ({@link DeviceType#eventProblem}).
     */
    public void publishEvent(BlockEntity blockEntity, JsonObject event) {
        DeviceRuntime.ifAttached(blockEntity, (devices, host) -> devices.publishEvent(host, event));
    }

    /**
     * The block entity knows its current value after the attach ({@link DeviceHost#rawValue()} answers): a retaining
     * output publishes it once, as the level stored on the broker may be stale.
     */
    public void rawValueKnown(BlockEntity blockEntity) {
        DeviceRuntime.ifAttached(blockEntity, DeviceRuntime::rawValueKnown);
    }

    /** Publishes to the device's Raw Link output, if it has an active one. */
    public void publishRaw(BlockEntity blockEntity, String payload) {
        DeviceRuntime.ifAttached(blockEntity, (devices, host) -> devices.publishRaw(host, payload));
    }

    /**
     * Publishes an edge of the device's level to its Raw Link output, if it has an active one that publishes this edge: a
     * link of rising edges only skips the falling one ({@link RawLink.Edges}).
     */
    public void publishRawEdge(BlockEntity blockEntity, boolean rising, String payload) {
        DeviceRuntime.ifAttached(blockEntity, (devices, host) -> devices.publishRawEdge(host, rising, payload));
    }

    /**
     * The untrusted-data rule of a placement: no copied id (a fresh one is minted), no legacy items, the placer owns it,
     * access follows the claim, the exposure follows the policy default, grandfathering is dropped and the Raw Link is
     * checked for the placer. A link with a channel is resolved for the placer again at the attach, which replaces this check.
     * A twin binding is judged for the placer (M3 plan D13 #6): refused when they may not bind its entities, without
     * control unless they may turn it on; the attach judges it again for its owner, so a refusal heals there.
     * <p>
     * The fresh id is minted here, not at the attach: data saved before the attach must not read as v1 data when it
     * loads again, which the Redstone Input tells by a missing id (its v1 data has no keys of its own), so a reload in
     * between is an unseen id that the attach checks again.
     *
     * @param check  the refusal for a Raw Link topic, empty when allowed
     * @param rights what the placer may bind ({@link TwinRules.Rights#NONE} without a player)
     */
    void sanitizePlaced(@Nullable UUID placer, String placerName, Function<RawLink, Optional<String>> check, TwinRules.Rights rights) {
        // v1 data from an item is adopted now, as a placement: no grandfather flag, no v1 echo (§8.6).
        if (pendingV1 != null) adopt(takePendingV1(), false);
        id = UUID.randomUUID();
        // Items copied with the data were never in this block: ejecting or breaking it must not make them.
        legacyItems = List.of();
        name = "";
        slug = "";
        owner = placer;
        ownerName = placerName;
        access = AccessMode.DEFAULT;
        expose = null;
        if (raw != null) raw = raw.withoutGrandfather();
        revalidateRaw(check);
        if (twin != null) twin = TwinRules.placed(type.twin().orElseThrow(), twin, rights);
        placedByPlayer = true;
    }

    /** {@link #sanitizePlaced(UUID, String, Function, TwinRules.Rights)} by a placer who may bind nothing. */
    void sanitizePlaced(@Nullable UUID placer, String placerName, Function<RawLink, Optional<String>> check) {
        sanitizePlaced(placer, placerName, check, TwinRules.Rights.NONE);
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
     * custom payloads, if the type has them, and the type's default retain: wireless redstone of v1 publishes retained
     * from now on (M2 plan D2). Only data adopted on chunk load is {@code trusted}: its topic is grandfathered for its
     * owner, and an input echoes its state to the v1 state topic; the owner UUID a legacy block saved since M2 F11 is
     * the owner at once, as the resolution would have bound it. The topic is the slots' literal as v1 used it, not
     * resolved: untrusted data keeps it as its channel, resolved for its new owner at the attach. A name an anvil gave
     * the v1 block becomes the device's name, which the attach registers. A converted Static Publisher's output keeps
     * v1's events (M2 plan D11): its message on rising edges only, explicitly not retained. A v1 remote's topic becomes
     * no Raw Link ({@link DeviceType#remote()}).
     */
    void adopt(LegacyNbtV1.Snapshot v1, boolean trusted) {
        if (owner == null && ownerName.isEmpty()) {
            ownerName = v1.ownerName();
            if (trusted && !ownerName.isEmpty()) owner = v1.ownerUuid();
        }
        if (name.isEmpty()) name = ValueCodecs.sanitizeText(v1.name()).strip();
        RawLinkSpec spec = type.rawLink().orElse(null);
        // A v1 remote's topic named the lamps it commanded, which the attach wires it to instead (M2 plan D15).
        if (spec == null || type.remote() || v1.combinedTopic().isEmpty()) return;
        String topic = v1.combinedTopic();
        ValueCodecs.CustomPayloads custom = spec.customPayloads() ? v1.custom() : ValueCodecs.CustomPayloads.NONE;
        RawLink.Edges edges = spec.direction() == RawLinkSpec.Direction.OUT ? v1.edges() : RawLink.Edges.BOTH;
        raw = new RawLink(topic, spec.direction(), custom.on(), custom.off(), 0, edges.level() ? null : Boolean.FALSE,
                trusted ? new TopicAcl.Grandfather(topic, owner) : null, trusted && spec.echoes(), null, 0, trusted ? null : topic, edges);
    }

    /** Whether the block entity holds v1 data that no attach has adopted yet. */
    public boolean awaitingAdoption() {
        return pendingV1 != null;
    }

    /** The v1 data that waits for adoption, as the block entity writes its type's v1 keys back; null when none waits. */
    public LegacyNbtV1.@Nullable Snapshot pendingV1() {
        return pendingV1;
    }

    LegacyNbtV1.@Nullable Snapshot takePendingV1() {
        LegacyNbtV1.Snapshot v1 = pendingV1;
        pendingV1 = null;
        return v1;
    }

    /**
     * A failed attach hands back what it used up (M2 plan D7): the v1 data it took waits for adoption again, and is
     * saved as v1 data until then, and a placement it cleared is one again.
     *
     * @param v1     the v1 data the attach took, null when there was none
     * @param placed whether the device was placed by a player when the attach began
     */
    void handBack(LegacyNbtV1.@Nullable Snapshot v1, boolean placed) {
        if (v1 != null) pendingV1 = v1;
        if (placed) placedByPlayer = true;
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
     * A player claims a device that has neither an owner nor an owner name (the Access tab's claim, a trusted server
     * path behind the CONFIGURE check): they own it from now on, and a grandfather flag that waited for an owner is
     * bound to them, as a resolved v1 owner name binds it. The claimer is no v1 owner, so the flag holds for this device
     * only: it is settled without joining their literal set ({@link LegacyAdoption#settleGrandfathered}).
     *
     * @return false when the device has an owner, or a v1 owner name still to resolve
     */
    public boolean claim(BlockEntity blockEntity, UUID player, String playerName) {
        if (owner != null || !ownerName.isEmpty()) return false;
        resolveOwner(player);
        if (raw != null && raw.grandfathered() != null) raw = raw.markResolved();
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

    /** Set by the owner or an operator through the screen or {@code /mineqtt devices setting} (M2-6). */
    void setAccess(AccessMode access) {
        this.access = access;
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

    /** The device's twin binding (M3 plan §3.1); null when it is bound to nothing. */
    public @Nullable Binding twin() {
        return twin;
    }

    /**
     * A binding a trusted path set: an edit after its twin gate ({@link TwinRules#edit}), or a judgement for the owner
     * ({@link TwinRules#recheck}); null unbinds the device.
     *
     * @throws IllegalStateException for a type without a twin adapter
     */
    void setTwin(@Nullable Binding twin) {
        if (twin != null && type.twin().isEmpty()) throw new IllegalStateException(type.id() + " devices take no twin binding");
        this.twin = twin;
    }

    /**
     * Whether the Raw Link's channel resolves into the owner's private subtree ({@link RawTopics#resolve}): v1's private
     * mode, which a private channel sets (the Advanced tab, {@code /mineqtt devices raw ... private}).
     */
    public boolean privateMode() {
        return privateMode;
    }

    /** Set with the channel it resolves; a typed topic or a removed link clears it. */
    void setPrivateMode(boolean privateMode) {
        this.privateMode = privateMode;
    }

    /**
     * The items the block's configuration slots held before devices lost their containers (M2-12), each with its slot.
     * Breaking the block drops them; the Diagnostics tab and {@code /mineqtt devices eject} hand them out.
     */
    public List<ItemStackWithSlot> legacyItems() {
        return legacyItems;
    }

    /**
     * Takes the legacy items out of the block: it is broken, or a player ejects them. Ejecting waits while v1 data waits
     * for adoption ({@link #awaitingAdoption()}), as those slots still are its topic.
     */
    public List<ItemStackWithSlot> takeLegacyItems() {
        List<ItemStackWithSlot> taken = legacyItems;
        legacyItems = List.of();
        return taken;
    }

    /** The tab the device's screen last showed; empty for none. */
    public String lastTab() {
        return lastTab;
    }

    public void setLastTab(String lastTab) {
        this.lastTab = lastTab;
    }

    /**
     * The version of the data the link was loaded from: {@value #VERSION_V1} for v1 data, {@value #VERSION_M1} for M1's,
     * {@value #VERSION} for anything newer and for a new device. A block entity migrates its own keys by it.
     */
    public int loadedVersion() {
        return loadedVersion;
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

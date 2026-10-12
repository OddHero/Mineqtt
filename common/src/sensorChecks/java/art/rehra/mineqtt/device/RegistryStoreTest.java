package art.rehra.mineqtt.device;

import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.flow.core.Mapping;
import art.rehra.mineqtt.flow.core.Scope;
import art.rehra.mineqtt.flow.core.Wire;
import art.rehra.mineqtt.presence.Consent;
import art.rehra.mineqtt.presence.PlayerEntry;
import art.rehra.mineqtt.rooms.Room;
import art.rehra.mineqtt.security.AccessMode;
import art.rehra.mineqtt.twin.core.Binding;
import art.rehra.mineqtt.twin.core.ValuePath;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The registry file (design §6.7), run by Gradle's verifyRegistryRoundTrip task: it is read back twice (the second
 * load is what wipes a vanilla SavedData without a data fixer), the backup takes over from a corrupt file, unreadable
 * files are kept aside and never overwritten, and single bad entries do not take the registry down. A structural
 * change is saved at the end of its tick, at most once a second; any other change waits for the autosave. A broken
 * device stays in the file until the level save, as its chunk may still hold its block, and its wires with it. Wires
 * are stored as M2 builds them (the reserved endpoints and the cable scope are refused), a file M1 wrote has none, and
 * removing a device or group removes its wires. The devices of each owner are counted for the device limit.
 */
public final class RegistryStoreTest {
    private static final Identifier LAMP = Identifier.fromNamespaceAndPath("mineqtt", "smart_lamp");
    private static final Identifier UNKNOWN = Identifier.fromNamespaceAndPath("othermod", "gadget");
    private static final UUID KITCHEN_OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    static void main(String[] args) throws IOException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path root = Files.createTempDirectory("mineqtt-registry");
        try {
            roundTrip(root.resolve("round-trip"));
            backupFallback(root.resolve("backup"));
            bothCorrupt(root.resolve("both-corrupt"));
            wrongShapeAndNewerSchema(root.resolve("shape"));
            badEntries();
            registryRules();
            dirtyClassification();
            structureSaveThrottle(root.resolve("throttle"));
            brokenDevices(root.resolve("broken"));
            wireCodec();
            ownerCounts();
            twinCopies();
            discoveryCleanup();
            rooms();
        } finally {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("Registry round-trip checks passed.");
    }

    private static void roundTrip(Path dir) throws IOException {
        RegistryStore store = new RegistryStore(dir);
        DeviceRegistry fresh = store.load("New World", new Random(7));
        check(Files.exists(store.file()), "A new registry is saved at once, so its bridge id survives a crash");
        check(fresh.generatedBridgeId().matches("new_world_[0-9a-f]{4}"), "Bridge id from the level name: " + fresh.generatedBridgeId());
        check(fresh.records().isEmpty() && fresh.groups().isEmpty(), "A new registry is empty");
        check(!fresh.isDirty() && !fresh.structureChanged(), "A saved registry is clean");

        populate(fresh);
        check(fresh.isDirty(), "Changes mark the registry dirty");
        check(store.save(fresh), "First save");
        byte[] firstFile = Files.readAllBytes(store.file());
        CompoundTag written = fresh.encode();
        check(written.getIntOr("schema", -1) == DeviceRegistry.SCHEMA, "The schema version is written");

        DeviceRegistry first = new RegistryStore(dir).load("ignored", new Random(1));
        check(first.encode().equals(written), "First read gives back what was written");
        check(first.generatedBridgeId().equals(fresh.generatedBridgeId()) && first.worldUuid().equals(fresh.worldUuid()),
                "Bridge id and world UUID persist instead of being generated again");
        DeviceRecord lamp = first.bySlug("kitchen_lamp");
        check(lamp != null && lamp.lastState().get("on").getAsBoolean() && lamp.lastState().get("note").isJsonNull(),
                "JSON state keeps booleans and nulls exactly");
        check(lamp.pending().get("brightness").getAsInt() == 128, "Pending commands persist");
        check(lamp.access() == AccessMode.PUBLIC && first.bySlug("hall_lamp_1").access() == AccessMode.CLAIM,
                "The access mode persists, and a record without one has the default");
        check(first.bySlug("gadget") != null, "A record of an unknown type survives");
        DeviceGroup group = first.groupBySlug("downstairs");
        check(group != null && group.members().size() == 2 && group.state().get("state").getAsString().equals("ON"), "Groups persist with members and state");
        check(lamp.group().equals(group.id()), "Membership is restored on the device side");
        check(first.inChunk(Level.OVERWORLD, ChunkPos.containing(new BlockPos(17, 64, -3))).contains(lamp), "The chunk index is rebuilt");
        check("abc123".equals(lamp.discoveryHash()) && "home_kitchen".equals(lamp.legacyUniqueId())
                && "def456".equals(group.discoveryHash()) && "home_downstairs".equals(group.legacyUniqueId()),
                "Discovery hashes and v1 unique_ids of devices and groups persist");
        check("home/kitchen".equals(lamp.v1Topic()), "A v1 device's v1 topic persists (M2 plan D15)");
        check(first.bySlug("hall_lamp_1").discoveryHash() == null && first.bySlug("hall_lamp_1").legacyUniqueId() == null
                && first.bySlug("hall_lamp_1").v1Topic() == null, "Absent ones stay absent");
        check(first.legacyDiscoveryMigrated("home_kitchen") && !first.legacyDiscoveryMigrated("home_downstairs")
                && first.legacyImportDone() && first.v1StatusCleared(), "The migration flags persist");
        check(first.ownerLiterals(KITCHEN_OWNER).equals(Set.of("minecraft/stone", "zigbee2mqtt/kitchen/set"))
                && first.ownerLiterals(UUID.randomUUID()).isEmpty() && first.ownerLiterals(null).isEmpty(), "Owner literal sets persist, per owner");
        check(List.copyOf(first.wires()).equals(List.copyOf(fresh.wires())) && first.wiresFrom(Endpoint.port(first.bySlug("hall_lamp_1").id(), "light")).size() == 1
                && first.wiresOf(lamp.id()).size() == 1 && first.wiresOf(group.id()).size() == 1, "Wires persist in order, indexed by source and by node");
        check(List.copyOf(first.players()).equals(List.copyOf(fresh.players())) && first.player(KITCHEN_OWNER).orElseThrow().consent() == Consent.FULL
                && first.playerBySlug("player_steve").isPresent(), "Player entries persist in order, with consent, name and slug (M4 plan D9)");
        CompoundTag beforePlayers = written.copy();
        beforePlayers.remove("players");
        DeviceRegistry m3 = DeviceRegistry.decode(beforePlayers, problem -> {
            throw new AssertionError("A file without player entries (0.7.0) is valid: " + problem);
        });
        check(m3.players().isEmpty() && m3.player(KITCHEN_OWNER).isEmpty() && m3.records().size() == 3 && !m3.isDirty(),
                "A file 0.7.0 wrote, without player entries, reads with every consent unset");
        CompoundTag withoutWires = written.copy();
        withoutWires.remove("wires");
        DeviceRegistry m1 = DeviceRegistry.decode(withoutWires, problem -> {
            throw new AssertionError("A schema 1 file without wires is valid: " + problem);
        });
        check(m1.wires().isEmpty() && m1.records().size() == 3 && !m1.isDirty(), "A file M1 wrote, without wires, reads with none");
        CompoundTag beforeFlags = written.copy();
        beforeFlags.remove("legacyDiscoveryMigrated");
        beforeFlags.remove("legacyImportDone");
        beforeFlags.remove("v1StatusCleared");
        beforeFlags.remove("ownerLiterals");
        DeviceRegistry older = DeviceRegistry.decode(beforeFlags, problem -> {
            throw new AssertionError("A schema 1 file without the migration flags and literal sets is valid: " + problem);
        });
        check(!older.legacyDiscoveryMigrated("home_kitchen") && !older.legacyImportDone() && !older.v1StatusCleared(),
                "A file written before the flags existed reads with nothing migrated");
        check(older.ownerLiterals(KITCHEN_OWNER).isEmpty() && !older.isDirty(), "A file M1 wrote, without literal sets, reads with empty sets");
        older.addOwnerLiteral(KITCHEN_OWNER, "minecraft/stone");
        check(older.isDirty() && older.structureChanged(), "A new literal is a structural change");

        check(store.save(first), "Second save");
        check(Arrays.equals(Files.readAllBytes(dir.resolve(RegistryStore.FILE_NAME + ".bak")), firstFile), "The previous file is kept as the backup");
        DeviceRegistry second = new RegistryStore(dir).load("ignored", new Random(2));
        check(second.encode().equals(written), "Second read gives back the same registry");
        check(!Files.exists(dir.resolve(RegistryStore.FILE_NAME + ".tmp")), "No temp file is left behind");
    }

    private static void backupFallback(Path dir) throws IOException {
        RegistryStore store = new RegistryStore(dir);
        DeviceRegistry registry = store.load("world", new Random(3));
        populate(registry);
        store.save(registry);
        store.save(registry);
        Files.write(store.file(), "not gzip".getBytes(StandardCharsets.UTF_8));

        check(store.incompleteReason() == null, "A registry that is saved and loaded is complete");
        RegistryStore restoring = new RegistryStore(dir);
        DeviceRegistry restored = restoring.load("world", new Random(4));
        check(restored.generatedBridgeId().equals(registry.generatedBridgeId()) && restored.records().size() == 3, "The backup takes over from a corrupt file");
        check(restoring.incompleteReason() != null, "A registry restored from its backup may lack the devices of the last save, so the ghost cleanup trusts it with nothing");
        check(quarantined(dir, RegistryStore.FILE_NAME + ".corrupt-").size() == 1, "The corrupt file is kept aside");
        check(!Files.exists(store.file()), "The corrupt file is no longer in the way");
        check(restored.isDirty(), "The restored registry is saved again soon");
        RegistryStore again = new RegistryStore(dir);
        check(again.save(restored) && again.load("world", new Random(5)).encode().equals(restored.encode()), "Saving after a restore works");
        check(again.incompleteReason() == null, "The file saved after a restore loads as a complete registry");
    }

    private static void bothCorrupt(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.write(dir.resolve(RegistryStore.FILE_NAME), new byte[]{31, -117, 8, 0, 1, 2, 3});
        Files.write(dir.resolve(RegistryStore.FILE_NAME + ".bak"), "garbage".getBytes(StandardCharsets.UTF_8));
        DeviceRegistry registry = new RegistryStore(dir).load("My World", new Random(6));
        check(registry.records().isEmpty() && registry.generatedBridgeId().startsWith("my_world_"), "Nothing readable starts a new registry");
        check(quarantined(dir, RegistryStore.FILE_NAME + ".corrupt-").size() == 1, "The corrupt file is kept as devices.nbt.corrupt-<time>");
        check(quarantined(dir, RegistryStore.FILE_NAME + ".bak.corrupt-").size() == 1, "The corrupt backup is kept too");
        check(Files.exists(dir.resolve(RegistryStore.FILE_NAME)), "The new registry is saved");
    }

    private static void wrongShapeAndNewerSchema(Path dir) throws IOException {
        Files.createDirectories(dir);
        CompoundTag wrongShape = new CompoundTag();
        wrongShape.putString("hello", "world");
        NbtIo.writeCompressed(wrongShape, dir.resolve(RegistryStore.FILE_NAME));
        DeviceRegistry registry = new RegistryStore(dir).load("world", new Random(8));
        check(registry.records().isEmpty() && quarantined(dir, RegistryStore.FILE_NAME + ".corrupt-").size() == 1,
                "Valid NBT without a registry inside is unreadable too");

        // A downgrade: the newer version saved twice, so the backup is newer too, or once, so the backup is older.
        UUID worldUuid = UUID.randomUUID();
        CompoundTag future = new DeviceRegistry("world_0001", worldUuid).encode();
        future.putInt("schema", DeviceRegistry.SCHEMA + 1);
        CompoundTag older = new DeviceRegistry("world_0001", worldUuid).encode();
        for (CompoundTag backup : List.of(future, older)) {
            Path newer = dir.resolve(backup == future ? "newer-twice" : "newer-once");
            Files.createDirectories(newer);
            Path file = newer.resolve(RegistryStore.FILE_NAME);
            NbtIo.writeCompressed(future, file);
            NbtIo.writeCompressed(backup, newer.resolve(RegistryStore.FILE_NAME + ".bak"));
            byte[] before = Files.readAllBytes(file);
            RegistryStore store = new RegistryStore(newer);
            DeviceRegistry loaded = store.load("other", new Random(9));
            check(loaded.generatedBridgeId().equals("world_0001") && loaded.worldUuid().equals(worldUuid) && loaded.records().isEmpty(),
                    "A file of a newer schema keeps the bridge identity");
            check(quarantined(newer, RegistryStore.FILE_NAME + ".corrupt-").isEmpty() && quarantined(newer, RegistryStore.FILE_NAME + ".bak.corrupt-").isEmpty(),
                    "A file of a newer schema is not treated as corrupt, nor its backup");
            loaded.markDirty();
            check(!store.save(loaded) && Arrays.equals(Files.readAllBytes(file), before), "A file of a newer schema is never overwritten");
            check(store.incompleteReason() != null, "A registry of a newer schema is not saved, so the ghost cleanup trusts it with nothing");
        }
    }

    private static void badEntries() {
        DeviceRegistry registry = new DeviceRegistry("world_0002", UUID.randomUUID());
        populate(registry);
        CompoundTag tag = registry.encode();
        ListTag devices = tag.getListOrEmpty("devices");
        CompoundTag broken = devices.getCompoundOrEmpty(0).copy();
        broken.remove("pos");
        devices.add(broken);
        devices.add(StringTag.valueOf("not a device"));
        CompoundTag sameSlug = devices.getCompoundOrEmpty(0).copy();
        sameSlug.remove("group");
        sameSlug.store("id", UUIDUtil.CODEC, UUID.randomUUID());
        sameSlug.store("pos", GlobalPos.CODEC, GlobalPos.of(Level.NETHER, new BlockPos(1, 2, 3)));
        sameSlug.putString("legacyUniqueId", "kitchen/+");
        devices.add(sameSlug);
        tag.getListOrEmpty("groups").getCompoundOrEmpty(0).putString("legacyUniqueId", "down#stairs");
        tag.getListOrEmpty("ownerLiterals").add(StringTag.valueOf("not a literal set"));
        ListTag wires = tag.getListOrEmpty("wires");
        CompoundTag dangling = wires.getCompoundOrEmpty(0).copy();
        dangling.store("id", UUIDUtil.CODEC, UUID.randomUUID());
        dangling.getCompoundOrEmpty("to").store("node", UUIDUtil.CODEC, UUID.randomUUID());
        wires.add(dangling);
        wires.add(wires.getCompoundOrEmpty(1).copy());
        wires.add(StringTag.valueOf("not a wire"));
        List<String> problems = new ArrayList<>();
        DeviceRegistry read = DeviceRegistry.decode(tag, problems::add);
        check(read.records().size() == 4, "Bad entries are skipped, the rest is read: " + read.records().size());
        check(problems.size() == 9, "Each skipped or repaired entry is reported: " + problems);
        check(read.wires().size() == 2, "A wire to a node that is gone, a duplicate and garbage are skipped; the good wires are read");
        check(read.ownerLiterals(KITCHEN_OWNER).size() == 2, "A bad literal set does not take the good ones down");
        DeviceRecord suffixed = read.bySlug(registry.records().iterator().next().slug() + "_2");
        check(suffixed != null, "A duplicate slug is suffixed");
        check(suffixed.legacyUniqueId() == null && read.groups().iterator().next().legacyUniqueId() == null,
                "A v1 unique_id that is no valid topic level is dropped");
        check(!read.isDirty() && !read.structureChanged(), "A decoded registry is clean, although decoding added its entries");
    }

    private static void registryRules() {
        DeviceRegistry registry = new DeviceRegistry("world_0003", UUID.randomUUID());
        populate(registry);
        check(registry.uniqueSlug("kitchen_lamp", null).equals("kitchen_lamp_2"), "A taken slug gets a suffix");
        check(registry.uniqueSlug("bridge", null).equals("bridge_2") && registry.uniqueSlug("raw", null).equals("raw_2")
                && registry.uniqueSlug("set", null).equals("set_2"), "Reserved slugs are never used");
        check(registry.uniqueSlug("", null).equals("device"), "An empty slug falls back to device");
        check(registry.uniqueSlug("Kitchen Lamp", null).equals("kitchen_lamp_2"), "An edited, invalid slug is made topic-safe before the suffix");
        DeviceRecord lamp = registry.bySlug("kitchen_lamp");
        check(registry.uniqueSlug("kitchen_lamp", lamp.id()).equals("kitchen_lamp"), "A device keeps its own slug");
        expectFailure(() -> registry.add(new DeviceRecord(UUID.randomUUID(), LAMP, at(99, 64, 99), "Dup", "kitchen_lamp")), "A duplicate slug is refused");
        expectFailure(() -> registry.add(new DeviceRecord(UUID.randomUUID(), LAMP, lamp.pos(), "Dup", "elsewhere")), "A duplicate position is refused");

        registry.rename(lamp, "Hall Lamp", "hall_lamp");
        check(registry.bySlug("hall_lamp") == lamp && registry.bySlug("kitchen_lamp") == null, "Renaming updates the slug index");

        DeviceGroup group = registry.groupBySlug("downstairs");
        DeviceRecord gadget = registry.bySlug("gadget");
        expectFailure(() -> registry.joinGroup(group, gadget), "A group takes one device type only");
        Wire lampWire = registry.wiresOf(lamp.id()).getFirst();
        expectFailure(() -> registry.addWire(lampWire), "A wire id is registered once");
        expectFailure(() -> registry.addWire(new Wire(UUID.randomUUID(), Endpoint.port(UUID.randomUUID(), "state"), lampWire.to(), Mapping.FOLLOW,
                Scope.WIRELESS, KITCHEN_OWNER, true)), "A wire from a device that is not registered is refused");
        expectFailure(() -> registry.addWire(new Wire(UUID.randomUUID(), lampWire.from(), Endpoint.groupPort(UUID.randomUUID(), "state"), Mapping.FOLLOW,
                Scope.WIRELESS, KITCHEN_OWNER, true)), "A wire to a group that is not registered is refused");
        Wire extra = new Wire(UUID.randomUUID(), lampWire.from(), Endpoint.port(gadget.id(), "state"), Mapping.FOLLOW, Scope.WIRELESS, KITCHEN_OWNER, true);
        registry.addWire(extra);
        check(registry.wiresFrom(lampWire.from()).equals(List.of(lampWire, extra)), "The wires leaving a port keep the order they were made in");
        check(registry.removeWire(extra.id()) == extra && registry.wiresFrom(lampWire.from()).equals(List.of(lampWire)) && registry.removeWire(extra.id()) == null,
                "A wire is removed once");
        registry.remove(lamp.id());
        check(!group.members().contains(lamp.id()), "A removed device leaves its group");
        check(registry.wire(lampWire.id()) == null && registry.wiresFrom(lampWire.from()).isEmpty() && registry.wiresOf(lamp.id()).isEmpty(),
                "A removed device takes its wires along, at either end");
        DeviceRecord other = registry.record(group.members().iterator().next());
        check(registry.wiresOf(group.id()).size() == 1, "The group still has its wire");
        registry.removeGroup(group);
        check(registry.groupBySlug("downstairs") == null && other.group() == null, "Deleting a group frees its members");
        check(registry.wires().isEmpty(), "A deleted group takes its wires along");
        check(registry.createGroup(UUID.randomUUID(), "Down Stairs").slug().equals("down_stairs"), "Group slugs come from the name");
        check(registry.createGroup(UUID.randomUUID(), "Set").slug().equals("set_2"), "A group is never called set, which would overlap the command topics");

        // M2-17: a renamed group's slug follows its name among the groups, and its old slug is free again.
        DeviceGroup upstairs = registry.createGroup(UUID.randomUUID(), "Upstairs");
        check(registry.uniqueGroupSlug("Upstairs", upstairs).equals("upstairs"), "A group keeps its own slug when its name gives it again");
        check(registry.uniqueGroupSlug("Down Stairs", upstairs).equals("down_stairs_2"), "Another group's slug is taken");
        registry.renameGroup(upstairs, "Attic", registry.uniqueGroupSlug("Attic", upstairs));
        check(upstairs.name().equals("Attic") && registry.groupBySlug("attic") == upstairs && registry.groupBySlug("upstairs") == null,
                "A renamed group is found by its new slug, not its old one");
        expectFailure(() -> registry.renameGroup(upstairs, "Down Stairs", "down_stairs"), "Another group's slug is refused");
        expectFailure(() -> registry.renameGroup(upstairs, "Group", "group"), "A reserved slug is refused");
        check(upstairs.slug().equals("attic"), "A refused rename changes nothing");
    }

    /**
     * Structural changes are the ones chunk data cannot rebuild: devices and groups added or removed, slugs, names,
     * memberships and trusted literals. States, pending commands, discovery hashes, the copies the attach takes from the
     * block entity again and the migration flags wait for the autosave.
     */
    private static void dirtyClassification() {
        DeviceRegistry template = new DeviceRegistry("world_0004", UUID.randomUUID());
        populate(template);
        template.add(new DeviceRecord(UUID.randomUUID(), LAMP, at(5, 64, 5), "Porch", "porch"));
        CompoundTag saved = template.encode();
        UUID kitchen = template.bySlug("kitchen_lamp").id();
        UUID hall = template.bySlug("hall_lamp_1").id();
        UUID porch = template.bySlug("porch").id();
        UUID downstairs = template.groupBySlug("downstairs").id();

        structural(saved, "Adding a device", registry -> registry.add(new DeviceRecord(UUID.randomUUID(), LAMP, at(6, 64, 6), "Shed", "shed")));
        structural(saved, "Removing a device", registry -> registry.remove(kitchen));
        structural(saved, "Breaking a device", registry -> registry.removeBroken(kitchen));
        structural(saved, "Renaming a device", registry -> registry.rename(registry.record(kitchen), "Kitchen", "kitchen"));
        structural(saved, "Creating a group", registry -> registry.createGroup(UUID.randomUUID(), "Upstairs"));
        structural(saved, "Deleting a group", registry -> registry.removeGroup(registry.group(downstairs)));
        structural(saved, "Renaming a group", registry -> registry.renameGroup(registry.group(downstairs), "Ground Floor", "ground_floor"));
        structural(saved, "Joining a group", registry -> registry.joinGroup(registry.group(downstairs), registry.record(porch)));
        structural(saved, "Leaving a group", registry -> registry.leaveGroup(registry.record(hall)));
        structural(saved, "Trusting a literal", registry -> registry.addOwnerLiteral(KITCHEN_OWNER, "zigbee2mqtt/hall/set"));
        structural(saved, "Making a wire", registry -> registry.addWire(new Wire(UUID.randomUUID(), Endpoint.port(porch, "light_level"),
                Endpoint.port(kitchen, "state"), Mapping.FOLLOW, Scope.WIRELESS, KITCHEN_OWNER, true)));
        UUID wire = template.wires().iterator().next().id();
        structural(saved, "Removing a wire", registry -> registry.removeWire(wire));

        state(saved, "A state", registry -> {
            registry.record(kitchen).setLastState(JsonParser.parseString("{\"on\":false}").getAsJsonObject());
            registry.markDirty();
        });
        state(saved, "A pending command", registry -> {
            registry.record(porch).setPending(JsonParser.parseString("{\"on\":true}").getAsJsonObject());
            registry.markDirty();
        });
        state(saved, "A group state and a discovery hash", registry -> {
            registry.group(downstairs).setState(JsonParser.parseString("{\"state\":\"OFF\"}").getAsJsonObject());
            registry.record(kitchen).setDiscoveryHash("fff000");
            registry.markDirty();
        });
        state(saved, "The owner, access and exposure copies", registry -> {
            registry.record(hall).setOwner(KITCHEN_OWNER);
            registry.record(hall).setAccess(AccessMode.PRIVATE);
            registry.record(hall).setExpose(false);
            registry.markDirty();
        });
        state(saved, "A migrated v1 discovery", registry -> registry.markLegacyDiscoveryMigrated("home_hall"));

        // M4 plan D8: whether Home Assistant may hold the bridge's broadcast text entity; written only while set.
        check(!saved.contains("bridgeBroadcastDiscovered") && !DeviceRegistry.decode(saved, RegistryStoreTest::noProblem).bridgeBroadcastDiscovered(),
                "A world that never discovered the broadcast entity saves no mark and reads none");
        structural(saved, "Marking the broadcast entity discovered", registry -> registry.setBridgeBroadcastDiscovered(true));
        DeviceRegistry marked = DeviceRegistry.decode(saved, RegistryStoreTest::noProblem);
        marked.setBridgeBroadcastDiscovered(true);
        CompoundTag markedTag = marked.encode();
        DeviceRegistry reread = DeviceRegistry.decode(markedTag, RegistryStoreTest::noProblem);
        check(markedTag.getBooleanOr("bridgeBroadcastDiscovered", false) && reread.bridgeBroadcastDiscovered() && !reread.isDirty(), "The mark persists");
        reread.setBridgeBroadcastDiscovered(true);
        check(!reread.isDirty(), "Marking it again changes nothing");
        reread.setBridgeBroadcastDiscovered(false);
        check(reread.structureChanged() && !reread.encode().contains("bridgeBroadcastDiscovered"), "Cleared, it is a structural change and leaves the file");

        DeviceRegistry same = DeviceRegistry.decode(saved, RegistryStoreTest::noProblem);
        check(!same.addOwnerLiteral(KITCHEN_OWNER, "minecraft/stone") && !same.isDirty(), "A literal the set holds changes nothing");
        same.leaveGroup(same.record(porch));
        same.setLegacyImportDone();
        same.setV1StatusCleared();
        check(same.removeWire(UUID.randomUUID()) == null, "No wire has a random id");
        check(!same.isDirty(), "Leaving no group, removing no wire and setting flags that are set change nothing");
    }

    private static void structural(CompoundTag saved, String change, Consumer<DeviceRegistry> action) {
        DeviceRegistry registry = DeviceRegistry.decode(saved, RegistryStoreTest::noProblem);
        action.accept(registry);
        check(registry.isDirty() && registry.structureChanged(), change + " is a structural change");
    }

    private static void state(CompoundTag saved, String change, Consumer<DeviceRegistry> action) {
        DeviceRegistry registry = DeviceRegistry.decode(saved, RegistryStoreTest::noProblem);
        action.accept(registry);
        check(registry.isDirty() && !registry.structureChanged(), change + " waits for the autosave");
    }

    /** The save at the end of a tick, with an injected clock: at most once a second, counted from the last save. */
    private static void structureSaveThrottle(Path dir) throws IOException {
        long[] now = {50_000};
        RegistryStore store = new RegistryStore(dir, () -> now[0]);
        DeviceRegistry registry = store.load("Throttle", new Random(10));
        check(!store.structureSavePending(registry), "A new registry is saved by its load");

        // The load saved at 50 000, so a change right after it waits for the interval.
        registry.add(new DeviceRecord(UUID.randomUUID(), LAMP, at(1, 64, 1), "One", "one"));
        now[0] = 50_300;
        store.saveStructure(registry);
        check(store.structureSavePending(registry) && savedSlugs(dir).isEmpty(), "A structural change within a second of the last save waits");
        now[0] = 51_000;
        store.saveStructure(registry);
        check(!registry.isDirty() && !registry.structureChanged() && savedSlugs(dir).equals(Set.of("one")), "It is saved one second after the last save");

        // A change at every tick: saved once a second while the changes go on, not only once they stop.
        Set<String> placed = new HashSet<>(Set.of("one"));
        Set<String> before = savedSlugs(dir);
        int saves = 0;
        for (int tick = 1; tick <= 50; tick++) {
            now[0] = 51_000 + tick * 50L;
            String slug = "row_" + tick;
            registry.add(new DeviceRecord(UUID.randomUUID(), LAMP, at(10 + tick, 64, 1), "Row " + tick, slug));
            placed.add(slug);
            store.saveStructure(registry);
            Set<String> after = savedSlugs(dir);
            if (!after.equals(before)) {
                saves++;
                check(after.equals(placed), "A save writes every change so far");
                check(now[0] == 51_000 + 1_000L * saves, "One save a second, counted from the last one: save " + saves + " at " + now[0]);
            }
            before = after;
        }
        check(saves == 2 && registry.structureChanged(), "Changes at every tick for 2.5 seconds are saved twice so far: " + saves);
        now[0] = 54_000;
        store.saveStructure(registry);
        check(!registry.structureChanged() && savedSlugs(dir).equals(placed), "The rest is saved once the interval passed");

        // A state change is left to the autosave, which restarts the interval.
        registry.bySlug("one").setLastState(JsonParser.parseString("{\"on\":true}").getAsJsonObject());
        registry.markDirty();
        now[0] = 70_000;
        byte[] beforeState = Files.readAllBytes(store.file());
        store.saveStructure(registry);
        check(registry.isDirty() && !store.structureSavePending(registry) && Arrays.equals(Files.readAllBytes(store.file()), beforeState),
                "A state change is not saved at the end of the tick");
        store.saveIfDirty(registry);
        check(!registry.isDirty(), "The autosave writes it");
        registry.remove(registry.bySlug("one").id());
        now[0] = 70_500;
        store.saveStructure(registry);
        check(registry.structureChanged() && savedSlugs(dir).contains("one"), "The autosave restarts the interval");
        now[0] = 71_000;
        store.saveStructure(registry);
        check(!registry.structureChanged() && !savedSlugs(dir).contains("one"), "A removal is saved like an addition");

        // A failed save stays pending and is tried again a second later, with what changed in between.
        Path notADirectory = dir.resolveSibling("throttle-file");
        Files.write(notADirectory, new byte[0]);
        RegistryStore failing = new RegistryStore(notADirectory, () -> now[0]);
        DeviceRegistry unsaved = new DeviceRegistry("world_0005", UUID.randomUUID());
        unsaved.add(new DeviceRecord(UUID.randomUUID(), LAMP, at(2, 64, 2), "Two", "two"));
        failing.saveStructure(unsaved);
        check(unsaved.isDirty() && failing.structureSavePending(unsaved), "A failed structural save stays pending");
        Files.delete(notADirectory);
        unsaved.createGroup(UUID.randomUUID(), "Again");
        now[0] = 71_500;
        failing.saveStructure(unsaved);
        check(!Files.exists(failing.file()) && failing.structureSavePending(unsaved), "No new attempt within a second of the failed one");
        now[0] = 72_000;
        failing.saveStructure(unsaved);
        check(!unsaved.isDirty() && !failing.structureSavePending(unsaved) && savedSlugs(notADirectory).equals(Set.of("two"))
                && savedGroups(notADirectory).equals(Set.of("again")), "The retry a second later writes every change so far");

        // A file of a newer schema is never written, so no structural save is pending for it.
        Path newer = dir.resolveSibling("throttle-newer");
        Files.createDirectories(newer);
        CompoundTag future = new DeviceRegistry("world_0006", UUID.randomUUID()).encode();
        future.putInt("schema", DeviceRegistry.SCHEMA + 1);
        NbtIo.writeCompressed(future, newer.resolve(RegistryStore.FILE_NAME));
        RegistryStore blocked = new RegistryStore(newer, () -> now[0]);
        DeviceRegistry run = blocked.load("world", new Random(11));
        run.add(new DeviceRecord(UUID.randomUUID(), LAMP, at(3, 64, 3), "Three", "three"));
        check(run.structureChanged() && !blocked.structureSavePending(run), "A store that never saves has no structural save pending");
    }

    /**
     * A device whose block was broken is gone from the registry at once, but the structural saves keep it, with its group
     * membership, until the level save: a crash before then restores its block and its record together.
     */
    private static void brokenDevices(Path dir) throws IOException {
        long[] now = {200_000};
        RegistryStore store = new RegistryStore(dir, () -> now[0]);
        DeviceRegistry registry = store.load("Broken", new Random(12));
        populate(registry);
        store.saveIfDirty(registry);
        UUID kitchen = registry.bySlug("kitchen_lamp").id();
        UUID hall = registry.bySlug("hall_lamp_1").id();
        UUID downstairs = registry.groupBySlug("downstairs").id();
        CompoundTag kitchenBefore = (CompoundTag) DeviceRecord.CODEC.encodeStart(NbtOps.INSTANCE, registry.record(kitchen)).getOrThrow();
        List<Wire> kitchenWires = registry.wiresOf(kitchen);

        registry.removeBroken(kitchen);
        check(registry.record(kitchen) == null && registry.group(downstairs).members().equals(Set.of(hall)), "A broken device is gone at once");
        check(registry.wiresOf(kitchen).isEmpty() && kitchenWires.size() == 1, "Its wires are gone with it");
        now[0] = 201_000;
        store.saveStructure(registry);
        check(!registry.structureChanged() && !store.structureSavePending(registry) && registry.isDirty(),
                "A structural save that kept a broken device leaves the registry dirty for the level save");
        DeviceRegistry file = saved(dir);
        check(file.record(kitchen) != null && file.group(downstairs).members().equals(Set.of(kitchen, hall))
                && DeviceRecord.CODEC.encodeStart(NbtOps.INSTANCE, file.record(kitchen)).getOrThrow().equals(kitchenBefore),
                "The structural save writes the broken device as it was, in its group");
        check(file.wiresOf(kitchen).equals(kitchenWires), "Its wires ride along, so a crash restores the device wired");
        store.saveIfDirty(registry);
        file = saved(dir);
        check(!registry.isDirty() && file.record(kitchen) == null && file.group(downstairs).members().equals(Set.of(hall)) && file.wiresOf(kitchen).isEmpty(),
                "The level save writes the registry without it and its wires");
        now[0] = 202_000;
        registry.rename(registry.record(hall), "Hall", "hall");
        store.saveStructure(registry);
        check(!registry.isDirty() && saved(dir).record(kitchen) == null, "Once the level is saved, it is left out for good");

        // A device that holds the broken one's position or slug wins; a deleted group takes no member back.
        DeviceRecord hallRecord = registry.record(hall);
        registry.removeBroken(hall);
        DeviceRecord successor = new DeviceRecord(UUID.randomUUID(), LAMP, hallRecord.pos(), "Hall", "hall");
        registry.add(successor);
        UUID gadget = registry.bySlug("gadget").id();
        registry.removeBroken(gadget);
        registry.joinGroup(registry.group(downstairs), successor);
        DeviceRecord shed = new DeviceRecord(UUID.randomUUID(), LAMP, at(30, 64, 0), "Shed", "shed");
        registry.add(shed);
        registry.joinGroup(registry.group(downstairs), shed);
        registry.removeBroken(shed.id());
        registry.removeGroup(registry.group(downstairs));
        now[0] = 203_000;
        store.saveStructure(registry);
        file = saved(dir);
        check(file.record(hall) == null && file.record(successor.id()) != null && file.record(gadget) != null,
                "A broken device whose position or slug is taken now is left out");
        check(file.wires().isEmpty(), "So is its wire, whose group is gone as well");
        check(file.group(downstairs) == null && file.record(shed.id()) != null && file.record(shed.id()).group() == null,
                "A broken device whose group is gone is kept without it");

        // Two devices broken at one position: the newest is kept. A copy placed with a broken device's id wins over it.
        DeviceRecord first = new DeviceRecord(UUID.randomUUID(), LAMP, at(40, 64, 0), "First", "first");
        registry.add(first);
        registry.removeBroken(first.id());
        DeviceRecord second = new DeviceRecord(UUID.randomUUID(), LAMP, at(40, 64, 0), "Second", "second");
        registry.add(second);
        registry.removeBroken(second.id());
        DeviceRecord copy = new DeviceRecord(shed.id(), LAMP, at(50, 64, 0), "Shed (copy)", "shed_copy");
        registry.add(copy);
        now[0] = 204_000;
        store.saveStructure(registry);
        file = saved(dir);
        check(file.record(first.id()) == null && file.record(second.id()) != null, "Of two devices broken at one position the newest is kept");
        check(file.record(shed.id()).slug().equals("shed_copy") && file.bySlug("shed") == null, "A device with a broken device's id wins over it");

        // A broken device registered again under its id (an undo) has lost its wires; one broken with another device rides on.
        DeviceRecord porch = new DeviceRecord(UUID.randomUUID(), LAMP, at(60, 64, 0), "Porch", "porch");
        registry.add(porch);
        Wire dropped = new Wire(UUID.randomUUID(), Endpoint.port(porch.id(), "light_level"), Endpoint.port(copy.id(), "state"), Mapping.FOLLOW,
                Scope.WIRELESS, KITCHEN_OWNER, true);
        registry.addWire(dropped);
        registry.removeBroken(porch.id());
        registry.add(new DeviceRecord(porch.id(), LAMP, porch.pos(), "Porch", "porch"));
        now[0] = 205_000;
        store.saveStructure(registry);
        check(saved(dir).wire(dropped.id()) == null, "A device registered again brings back none of the wires its removal dropped");
        registry.removeBroken(porch.id());
        now[0] = 206_000;
        store.saveStructure(registry);
        check(saved(dir).wire(dropped.id()) == null, "Nor when it is broken again");
        registry.add(new DeviceRecord(porch.id(), LAMP, porch.pos(), "Porch", "porch"));
        Wire kept = new Wire(UUID.randomUUID(), Endpoint.port(porch.id(), "light_level"), Endpoint.port(copy.id(), "state"), Mapping.FOLLOW,
                Scope.WIRELESS, KITCHEN_OWNER, true);
        registry.addWire(kept);
        registry.removeBroken(porch.id());
        registry.removeBroken(copy.id());
        registry.add(new DeviceRecord(copy.id(), LAMP, copy.pos(), "Shed (copy)", "shed_copy"));
        now[0] = 207_000;
        store.saveStructure(registry);
        check(saved(dir).wire(kept.id()) != null, "A broken device's wire rides along while its other end is registered again");

        store.saveIfDirty(registry);
        file = saved(dir);
        check(!registry.isDirty() && file.record(second.id()) == null && file.record(gadget) == null && file.record(porch.id()) == null
                && file.records().size() == 2 && file.wires().isEmpty(), "The level save drops every broken device and its wires");
    }

    // The slugs of the devices in the saved file.
    private static Set<String> savedSlugs(Path dir) throws IOException {
        Set<String> slugs = new HashSet<>();
        saved(dir).records().forEach(record -> slugs.add(record.slug()));
        return slugs;
    }

    // The slugs of the groups in the saved file.
    private static Set<String> savedGroups(Path dir) throws IOException {
        Set<String> slugs = new HashSet<>();
        saved(dir).groups().forEach(group -> slugs.add(group.slug()));
        return slugs;
    }

    // The saved file, which must read without problems.
    private static DeviceRegistry saved(Path dir) throws IOException {
        CompoundTag tag = NbtIo.readCompressed(dir.resolve(RegistryStore.FILE_NAME), NbtAccounter.unlimitedHeap());
        return DeviceRegistry.decode(tag, RegistryStoreTest::noProblem);
    }

    private static void noProblem(String problem) {
        throw new AssertionError("Unexpected registry problem: " + problem);
    }

    // Two lamps in a group, one with a queued command; one device of a type this MineQTT does not know.
    private static void populate(DeviceRegistry registry) {
        registry.putPlayer(new PlayerEntry(KITCHEN_OWNER, Consent.FULL, true, "Steve", Optional.of("player_steve"), 20_738));
        registry.putPlayer(PlayerEntry.of(UUID.fromString("0f3e2d1c-4b5a-4968-8776-655443322110")).withConsent(Consent.OFF).withAsked());
        DeviceRecord kitchen = new DeviceRecord(UUID.randomUUID(), LAMP, at(17, 64, -3), "Kitchen Lamp", "kitchen_lamp");
        kitchen.setOwner(KITCHEN_OWNER);
        kitchen.setAccess(AccessMode.PUBLIC);
        kitchen.setExpose(true);
        kitchen.setLastState(JsonParser.parseString("{\"on\":true,\"brightness\":200,\"note\":null,\"color\":{\"r\":255,\"g\":0,\"b\":0}}").getAsJsonObject());
        JsonObject pending = new JsonObject();
        pending.addProperty("brightness", 128);
        kitchen.setPending(pending);
        kitchen.setDiscoveryHash("abc123");
        kitchen.setLegacyUniqueId("home_kitchen");
        kitchen.setV1Topic("home/kitchen");
        DeviceRecord hall = new DeviceRecord(UUID.randomUUID(), LAMP, at(20, 64, -3), "Hall Lamp 1", "hall_lamp_1");
        DeviceRecord gadget = new DeviceRecord(UUID.randomUUID(), UNKNOWN, GlobalPos.of(Level.END, new BlockPos(0, 50, 0)), "Gadget", "gadget");
        registry.add(kitchen);
        registry.add(hall);
        registry.add(gadget);
        DeviceGroup group = registry.createGroup(UUID.randomUUID(), "Downstairs");
        registry.joinGroup(group, kitchen);
        registry.joinGroup(group, hall);
        group.setState(JsonParser.parseString("{\"state\":\"ON\"}").getAsJsonObject());
        group.setDiscoveryHash("def456");
        group.setLegacyUniqueId("home_downstairs");
        registry.markLegacyDiscoveryMigrated("home_kitchen");
        registry.setLegacyImportDone();
        registry.setV1StatusCleared();
        registry.addOwnerLiteral(KITCHEN_OWNER, "minecraft/stone");
        registry.addOwnerLiteral(KITCHEN_OWNER, "zigbee2mqtt/kitchen/set");
        // A disabled wire between the lamps, and one from the hall lamp to the group.
        registry.addWire(new Wire(UUID.randomUUID(), Endpoint.port(kitchen.id(), "light_level"), Endpoint.port(hall.id(), "state"), Mapping.INVERT,
                Scope.WIRELESS, KITCHEN_OWNER, false));
        registry.addWire(new Wire(UUID.randomUUID(), Endpoint.port(hall.id(), "light"), Endpoint.groupPort(group.id(), "light"), Mapping.FOLLOW,
                Scope.WIRELESS, KITCHEN_OWNER, true));
    }

    private static GlobalPos at(int x, int y, int z) {
        return GlobalPos.of(Level.OVERWORLD, new BlockPos(x, y, z));
    }

    private static List<Path> quarantined(Path dir, String prefix) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(path -> path.getFileName().toString().startsWith(prefix)).toList();
        }
    }

    /**
     * M3 plan D14: a record's copy of its twin binding is the nested {@code twin} compound, its 16th and last codec field.
     * An unbound record writes no key, so it saves as an M2 record; an M2 file reads unbound; a bound record round-trips
     * every part; defaults are left out; a {@code twin} compound that breaks the binding rules loses only the binding.
     */
    private static void twinCopies() {
        DeviceRecord plain = new DeviceRecord(UUID.fromString("00000000-0000-0000-0000-0000000000c1"), LAMP, at(1, 64, 1), "Porch Lamp", "porch_lamp");
        plain.setOwner(KITCHEN_OWNER);
        plain.setLastState(JsonParser.parseString("{\"state\":\"ON\"}").getAsJsonObject());
        CompoundTag unbound = (CompoundTag) DeviceRecord.CODEC.encodeStart(NbtOps.INSTANCE, plain).getOrThrow();
        check(!unbound.contains("twin"), "An unbound record writes no twin key: " + unbound);
        check(unbound.keySet().equals(Set.of("id", "type", "pos", "name", "slug", "owner", "lastState")),
                "...and exactly the keys an M2 record writes: " + unbound.keySet());
        DeviceRecord m2 = DeviceRecord.CODEC.parse(NbtOps.INSTANCE, unbound).getOrThrow();
        check(m2.twin() == null && DeviceRecord.CODEC.encodeStart(NbtOps.INSTANCE, m2).getOrThrow().equals(unbound),
                "An M2 record reads unbound and saves byte-identical");

        Binding full = new Binding(new Endpoint.HaEntityRef("input_number.volume", "value"),
                Map.of("pressed", new Endpoint.HaEntityRef("scene.movie_night", "activate")), true,
                new Binding.Settings(true, new BigDecimal("-12.5"), new BigDecimal("100"), 2, Binding.Unavailable.OFF, ValuePath.parse("attributes.max")),
                "input_number.volume is not in twin.allowedEntities");
        DeviceRecord bound = DeviceRecord.CODEC.parse(NbtOps.INSTANCE, unbound).getOrThrow();
        bound.setTwin(full);
        CompoundTag saved = (CompoundTag) DeviceRecord.CODEC.encodeStart(NbtOps.INSTANCE, bound).getOrThrow();
        CompoundTag twin = saved.getCompound("twin").orElseThrow();
        check(twin.keySet().equals(Set.of("source", "targets", "control", "invert", "min", "max", "decimals", "unavailable", "path", "refused")),
                "Every part of a binding is stored: " + twin);
        check(twin.getStringOr("min", "").equals("-12.5") && twin.getStringOr("unavailable", "").equals("off")
                && twin.getCompound("source").orElseThrow().getStringOr("entity", "").equals("input_number.volume"), "...in its plain form: " + twin);
        DeviceRecord read = DeviceRecord.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        check(full.equals(read.twin()), "A binding round-trips: " + read.twin());
        unbound.keySet().forEach(key -> check(saved.get(key).equals(unbound.get(key)), "Binding a record changes no other key: " + key));

        DeviceRecord minimal = DeviceRecord.CODEC.parse(NbtOps.INSTANCE, unbound).getOrThrow();
        minimal.setTwin(Binding.of(new Endpoint.HaEntityRef("switch.porch", "state")));
        CompoundTag small = ((CompoundTag) DeviceRecord.CODEC.encodeStart(NbtOps.INSTANCE, minimal).getOrThrow()).getCompound("twin").orElseThrow();
        check(small.keySet().equals(Set.of("source")), "Defaults are left out: " + small);
        Binding topic = new Binding(new Endpoint.TopicRef("zigbee2mqtt/porch", "state"), Map.of(), false, Binding.Settings.DEFAULT, null);
        minimal.setTwin(topic);
        check(topic.equals(DeviceRecord.CODEC.parse(NbtOps.INSTANCE, DeviceRecord.CODEC.encodeStart(NbtOps.INSTANCE, minimal).getOrThrow()).getOrThrow().twin()),
                "A topic source round-trips with its path");

        for (String broken : List.of("{control:1b,targets:{pressed:{entity:\"scene.movie\",port:\"activate\"}}}", "{source:{entity:\"Light.Kitchen\",port:\"state\"}}",
                "{source:{entity:\"light.kitchen\",topic:\"x\"}}", "{source:{topic:\"x\",path:\"[\"}}", "{}", "{source:{entity:\"light.kitchen\",port:\"state\"},decimals:9}")) {
            CompoundTag withBroken = unbound.copy();
            try {
                withBroken.put("twin", net.minecraft.nbt.TagParser.parseCompoundFully(broken));
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                throw new AssertionError(e);
            }
            DeviceRecord kept = DeviceRecord.CODEC.parse(NbtOps.INSTANCE, withBroken).getOrThrow();
            check(kept.twin() == null && kept.name().equals("Porch Lamp"), "A broken twin compound loses the binding, not the record: " + broken);
        }

        DeviceRegistry registry = new DeviceRegistry("world_7f3a", UUID.randomUUID());
        registry.add(bound);
        CompoundTag encoded = registry.encode();
        DeviceRegistry decoded = DeviceRegistry.decode(encoded, problem -> {
            throw new AssertionError("A bound record is valid: " + problem);
        });
        check(full.equals(decoded.record(bound.id()).twin()) && decoded.encode().equals(encoded), "The registry keeps the copy, schema " + DeviceRegistry.SCHEMA);
    }

    /**
     * M3-7b: a pending discovery cleanup is the flat key {@code discoveryCleanup}, written only while true, beside
     * {@code discoveryHash} in the same codec field. A record without it saves exactly as before; one with it round-trips,
     * through the registry too, and changes no other key; an older MineQTT reading it ignores the unknown key.
     */
    private static void discoveryCleanup() {
        DeviceRecord plain = new DeviceRecord(UUID.fromString("00000000-0000-0000-0000-0000000000c2"), LAMP, at(2, 64, 1), "Hall Lamp", "hall_lamp");
        plain.setOwner(KITCHEN_OWNER);
        plain.setDiscoveryHash("4f2a");
        CompoundTag before = (CompoundTag) DeviceRecord.CODEC.encodeStart(NbtOps.INSTANCE, plain).getOrThrow();
        check(before.keySet().equals(Set.of("id", "type", "pos", "name", "slug", "owner", "discoveryHash")) && !plain.discoveryCleanup(),
                "No pending cleanup writes no key: " + before.keySet());
        DeviceRecord read = DeviceRecord.CODEC.parse(NbtOps.INSTANCE, before).getOrThrow();
        check(!read.discoveryCleanup() && "4f2a".equals(read.discoveryHash()) && DeviceRecord.CODEC.encodeStart(NbtOps.INSTANCE, read).getOrThrow().equals(before),
                "A record from before M3-7b reads without a cleanup and saves byte-identical");

        read.setDiscoveryCleanup(true);
        CompoundTag marked = (CompoundTag) DeviceRecord.CODEC.encodeStart(NbtOps.INSTANCE, read).getOrThrow();
        check(marked.getBooleanOr("discoveryCleanup", false) && marked.keySet().size() == before.keySet().size() + 1,
                "A pending cleanup is one flat key beside the hash: " + marked);
        before.keySet().forEach(key -> check(marked.get(key).equals(before.get(key)), "Marking a record changes no other key: " + key));
        DeviceRecord again = DeviceRecord.CODEC.parse(NbtOps.INSTANCE, marked).getOrThrow();
        check(again.discoveryCleanup() && "4f2a".equals(again.discoveryHash()), "A pending cleanup round-trips");
        again.setDiscoveryHash(null);
        CompoundTag withoutHash = (CompoundTag) DeviceRecord.CODEC.encodeStart(NbtOps.INSTANCE, again).getOrThrow();
        check(!withoutHash.contains("discoveryHash") && DeviceRecord.CODEC.parse(NbtOps.INSTANCE, withoutHash).getOrThrow().discoveryCleanup(),
                "The two keys are independent");
        again.setDiscoveryCleanup(false);
        check(!((CompoundTag) DeviceRecord.CODEC.encodeStart(NbtOps.INSTANCE, again).getOrThrow()).contains("discoveryCleanup"), "A done cleanup leaves no key");

        DeviceRegistry registry = new DeviceRegistry("world_7f3a", UUID.randomUUID());
        DeviceRecord kept = DeviceRecord.CODEC.parse(NbtOps.INSTANCE, marked).getOrThrow();
        registry.add(kept);
        CompoundTag encoded = registry.encode();
        DeviceRegistry decoded = DeviceRegistry.decode(encoded, problem -> {
            throw new AssertionError("A marked record is valid: " + problem);
        });
        check(decoded.record(kept.id()).discoveryCleanup() && decoded.encode().equals(encoded), "The registry keeps the mark, schema " + DeviceRegistry.SCHEMA);
    }

    /**
     * M4 plan D11, D17: the rooms are a list of their own after the wires, read entry by entry; the area a device's
     * discovery created it with is a third flat key of the discovery state, so a record without it saves byte-identical
     * and a registry from before rooms reads unchanged.
     */
    private static void rooms() {
        DeviceRecord lamp = new DeviceRecord(UUID.fromString("00000000-0000-0000-0000-0000000000d1"), LAMP, at(3, 64, 1), "Den Lamp", "den_lamp");
        lamp.setDiscoveryHash("77aa");
        CompoundTag before = (CompoundTag) DeviceRecord.CODEC.encodeStart(NbtOps.INSTANCE, lamp).getOrThrow();
        check(!before.contains("discoveryArea"), "No area writes no key");
        DeviceRecord read = DeviceRecord.CODEC.parse(NbtOps.INSTANCE, before).getOrThrow();
        check(read.discoveryArea() == null && DeviceRecord.CODEC.encodeStart(NbtOps.INSTANCE, read).getOrThrow().equals(before),
                "A record from before rooms reads without an area and saves byte-identical");
        for (String area : List.of("", "Kitchen")) {
            read.setDiscoveryArea(area);
            CompoundTag withArea = (CompoundTag) DeviceRecord.CODEC.encodeStart(NbtOps.INSTANCE, read).getOrThrow();
            check(withArea.getStringOr("discoveryArea", "?").equals(area) && withArea.keySet().size() == before.keySet().size() + 1,
                    "The area '" + area + "' is one flat key beside the hash: " + withArea);
            before.keySet().forEach(key -> check(withArea.get(key).equals(before.get(key)), "The area changes no other key: " + key));
            check(area.equals(DeviceRecord.CODEC.parse(NbtOps.INSTANCE, withArea).getOrThrow().discoveryArea()), "The area '" + area + "' round-trips");
        }

        DeviceRegistry registry = new DeviceRegistry("world_7f3a", UUID.randomUUID());
        CompoundTag empty = registry.encode();
        check(empty.getListOrEmpty("rooms").isEmpty(), "A registry without rooms writes an empty list");
        Room kitchen = Room.between(UUID.fromString("00000000-0000-0000-0000-0000000000e1"), "Kitchen", Level.OVERWORLD, new BlockPos(5, 67, 5),
                new BlockPos(0, 64, 0), KITCHEN_OWNER, false, 1_700_000_000_000L);
        Room shared = Room.between(UUID.fromString("00000000-0000-0000-0000-0000000000e2"), "Great Hall", Level.NETHER, new BlockPos(-8, 30, -8),
                new BlockPos(8, 40, 8), Util.NIL_UUID, true, 1_700_000_000_001L);
        check(kitchen.min().equals(new BlockPos(0, 64, 0)) && kitchen.max().equals(new BlockPos(5, 67, 5)), "Corners in any order are normalised");
        registry.add(read);
        registry.markSaved();
        registry.addRoom(kitchen);
        check(registry.structureChanged(), "A new room is a structural change: chunks cannot rebuild it");
        registry.addRoom(shared);
        expectFailure(() -> registry.addRoom(kitchen), "A room id registered twice");
        CompoundTag encoded = registry.encode();
        List<String> keys = List.copyOf(encoded.keySet());
        check(encoded.getListOrEmpty("rooms").size() == 2, "Both rooms are written: " + keys);
        DeviceRegistry decoded = DeviceRegistry.decode(encoded, problem -> {
            throw new AssertionError("The rooms are valid: " + problem);
        });
        check(List.copyOf(decoded.rooms()).equals(List.of(kitchen, shared)), "The rooms round-trip in order: " + decoded.rooms());
        check(decoded.encode().equals(encoded) && DeviceRegistry.decode(decoded.encode(), problem -> {
        }).encode().equals(encoded), "Twice round-tripped, the registry is the same");
        check(decoded.roomOf(decoded.record(read.id())).isEmpty(), "The lamp has no owner, so only a room for everyone could hold it");

        Room renamed = kitchen.renamed("Cookhouse");
        decoded.markSaved();
        decoded.replaceRoom(renamed);
        check(decoded.structureChanged() && decoded.room(kitchen.id()).name().equals("Cookhouse") && List.copyOf(decoded.rooms()).getFirst().equals(renamed),
                "A rename replaces the room in its place, a structural change");
        decoded.markSaved();
        check(shared.equals(decoded.removeRoom(shared.id())) && decoded.structureChanged() && decoded.rooms().size() == 1, "A removal is a structural change");
        check(decoded.removeRoom(shared.id()) == null, "A room removed twice is gone once");

        // Bad entries are skipped and reported; the rest is read.
        ListTag rooms = encoded.getListOrEmpty("rooms");
        CompoundTag nameless = rooms.getCompoundOrEmpty(0).copy();
        nameless.remove("name");
        nameless.store("id", UUIDUtil.CODEC, UUID.randomUUID());
        CompoundTag blank = rooms.getCompoundOrEmpty(0).copy();
        blank.putString("name", "   ");
        blank.store("id", UUIDUtil.CODEC, UUID.randomUUID());
        CompoundTag tooLong = rooms.getCompoundOrEmpty(0).copy();
        tooLong.putString("name", "x".repeat(33));
        tooLong.store("id", UUIDUtil.CODEC, UUID.randomUUID());
        CompoundTag swapped = rooms.getCompoundOrEmpty(0).copy();
        UUID swappedId = UUID.randomUUID();
        swapped.store("id", UUIDUtil.CODEC, swappedId);
        swapped.store("min", BlockPos.CODEC, new BlockPos(9, 9, 9));
        swapped.store("max", BlockPos.CODEC, new BlockPos(1, 1, 1));
        rooms.add(nameless);
        rooms.add(blank);
        rooms.add(tooLong);
        rooms.add(rooms.getCompoundOrEmpty(1).copy());
        rooms.add(StringTag.valueOf("not a room"));
        rooms.add(swapped);
        // A name today's text rules would clean is still read: a stricter sanitiser never loses a stored room.
        CompoundTag oldName = rooms.getCompoundOrEmpty(0).copy();
        UUID oldNameId = UUID.randomUUID();
        oldName.store("id", UUIDUtil.CODEC, oldNameId);
        oldName.putString("name", "OldDen");
        rooms.add(oldName);
        List<String> problems = new ArrayList<>();
        DeviceRegistry repaired = DeviceRegistry.decode(encoded, problems::add);
        check(problems.size() == 5, "Each bad room is reported: " + problems);
        check(repaired.rooms().size() == 4 && repaired.room(swappedId).min().equals(new BlockPos(1, 1, 1)) && repaired.room(oldNameId) != null,
                "The good rooms are read, stored corners in the wrong order are put right, an old name is kept: " + repaired.rooms());

        // A registry from before rooms has no list: nothing is reported and nothing is lost.
        CompoundTag old = encoded.copy();
        old.remove("rooms");
        DeviceRegistry fromOld = DeviceRegistry.decode(old, problem -> {
            throw new AssertionError("A registry from before rooms is valid: " + problem);
        });
        check(fromOld.rooms().isEmpty() && fromOld.records().size() == 1 && fromOld.roomIndex().roomOf(fromOld.record(read.id())).isEmpty(),
                "A registry from before rooms reads without rooms");
    }

    // The wire codec (M2 plan §3.2) round-trips what M2 builds and refuses the reserved endpoints and the cable scope.
    private static void wireCodec() {
        UUID input = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        UUID group = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
        Wire wire = new Wire(UUID.fromString("00000000-0000-0000-0000-0000000000a3"), Endpoint.port(input, "powered"), Endpoint.groupPort(group, "state"),
                Mapping.PULSE_TOGGLE, Scope.WIRELESS, KITCHEN_OWNER, true);
        Tag tag = WireCodecs.WIRE.encodeStart(NbtOps.INSTANCE, wire).getOrThrow();
        check(WireCodecs.WIRE.parse(NbtOps.INSTANCE, tag).getOrThrow().equals(wire), "A wire round-trips: " + tag);
        CompoundTag compound = (CompoundTag) tag;
        check(compound.getCompoundOrEmpty("from").getStringOr("type", "").equals("port") && compound.getCompoundOrEmpty("to").getStringOr("type", "").equals("group_port")
                && compound.getStringOr("mapping", "").equals("pulse_toggle") && compound.getStringOr("scope", "").equals("wireless") && !compound.contains("enabled"),
                "Endpoints are typed, enums are lower case, an enabled wire leaves the flag out: " + compound);
        Wire disabled = new Wire(wire.id(), wire.from(), wire.to(), Mapping.INVERT, Scope.WIRELESS, KITCHEN_OWNER, false);
        check(WireCodecs.WIRE.parse(NbtOps.INSTANCE, WireCodecs.WIRE.encodeStart(NbtOps.INSTANCE, disabled).getOrThrow()).getOrThrow().equals(disabled),
                "A disabled wire round-trips");

        // Home Assistant and topic ends, and the twin scope, exist only on wires derived from bindings (M3 plan §3.3).
        Endpoint entity = new Endpoint.HaEntityRef("light.kitchen", "state");
        Endpoint topic = new Endpoint.TopicRef("a/b", "");
        check(WireCodecs.ENDPOINT.encodeStart(NbtOps.INSTANCE, topic).isError()
                && WireCodecs.ENDPOINT.encodeStart(NbtOps.INSTANCE, entity).isError(), "Topic and Home Assistant ends are not written");
        check(WireCodecs.ENDPOINT.encodeStart(NbtOps.INSTANCE, entity).error().orElseThrow().message().contains("derived from device bindings"),
                "The refusal says why");
        check(WireCodecs.WIRE.encodeStart(NbtOps.INSTANCE, new Wire(wire.id(), entity, wire.to(), Mapping.FOLLOW, Scope.TWIN, KITCHEN_OWNER, true)).isError()
                && WireCodecs.WIRE.encodeStart(NbtOps.INSTANCE, new Wire(wire.id(), wire.from(), entity, Mapping.FOLLOW, Scope.TWIN, KITCHEN_OWNER, true)).isError(),
                "A derived twin wire is not written");
        check(WireCodecs.WIRE.encodeStart(NbtOps.INSTANCE, new Wire(wire.id(), wire.from(), wire.to(), Mapping.FOLLOW, Scope.TWIN, KITCHEN_OWNER, true)).isError(),
                "Nor is the twin scope between device ports");
        CompoundTag entityTag = new CompoundTag();
        entityTag.putString("type", "ha_entity");
        entityTag.putString("entity_id", "light.kitchen");
        entityTag.putString("port", "state");
        CompoundTag fromEntity = compound.copy();
        fromEntity.put("from", entityTag);
        check(WireCodecs.WIRE.parse(NbtOps.INSTANCE, fromEntity).isError(), "A stored wire from a Home Assistant end is refused");
        CompoundTag twin = compound.copy();
        twin.putString("scope", "twin");
        check(WireCodecs.WIRE.parse(NbtOps.INSTANCE, twin).error().orElseThrow().message().contains("derived from device bindings"),
                "A stored wire of the twin scope is refused");
        for (String type : List.of("topic", "ha_entity", "cable", "")) {
            CompoundTag endpoint = new CompoundTag();
            endpoint.putString("type", type);
            endpoint.putString("topic", "a/b");
            check(WireCodecs.ENDPOINT.parse(NbtOps.INSTANCE, endpoint).isError(), "An endpoint of type '" + type + "' is refused");
        }
        CompoundTag badKey = ((CompoundTag) WireCodecs.ENDPOINT.encodeStart(NbtOps.INSTANCE, wire.from()).getOrThrow()).copy();
        badKey.putString("port", "Powered");
        check(WireCodecs.ENDPOINT.parse(NbtOps.INSTANCE, badKey).isError(), "A port key that is no field key is refused, not thrown");
        CompoundTag network = compound.copy();
        network.putString("scope", "network");
        check(WireCodecs.WIRE.parse(NbtOps.INSTANCE, network).isError(), "The cable scope is reserved");
        check(WireCodecs.WIRE.encodeStart(NbtOps.INSTANCE, new Wire(wire.id(), wire.from(), wire.to(), Mapping.FOLLOW, Scope.NETWORK, KITCHEN_OWNER, true)).isError(),
                "A cable wire is not written");
        CompoundTag mapping = compound.copy();
        mapping.putString("mapping", "delay");
        check(WireCodecs.WIRE.parse(NbtOps.INSTANCE, mapping).isError(), "An unknown mapping is refused");
    }

    /**
     * {@code limits.maxDevicesPerPlayer} (M2-26): a player's devices are the records they own, loaded or not, read back
     * the same; groups and devices without an owner count for nobody, and a broken device stops counting at once.
     */
    private static void ownerCounts() {
        UUID alice = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        UUID bob = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
        DeviceRegistry registry = new DeviceRegistry("counts_0000", UUID.randomUUID());
        DeviceRecord porch = owned(registry, alice, "porch_lamp", 0);
        DeviceRecord hall = owned(registry, alice, "hall_lamp", 1);
        owned(registry, bob, "bob_lamp", 2);
        DeviceRecord v1 = owned(registry, null, "v1_lamp", 3);
        registry.joinGroup(registry.createGroup(UUID.randomUUID(), "Alice Lights"), porch);
        check(registry.ownedBy(alice) == 2 && registry.ownedBy(bob) == 1, "Each owner's devices are counted");
        check(registry.ownedBy(UUID.randomUUID()) == 0 && registry.ownedBy(null) == 0, "Nobody else owns any, and no owner owns none");

        DeviceRegistry read = DeviceRegistry.decode(registry.encode(), RegistryStoreTest::noProblem);
        check(read.ownedBy(alice) == 2 && read.ownedBy(bob) == 1, "The counts survive a round trip");

        v1.setOwner(bob);
        check(registry.ownedBy(bob) == 2, "A v1 device counts once its owner name is resolved");
        registry.removeBroken(hall.id());
        check(registry.ownedBy(alice) == 1, "A broken device no longer counts, though the file keeps it until the level save");
        registry.remove(porch.id());
        check(registry.ownedBy(alice) == 0, "A forgotten device no longer counts");

        check(!DeviceRuntime.deviceLimitReached(0, 1, false) && DeviceRuntime.deviceLimitReached(1, 1, false),
                "A limit of 1 allows the first device and refuses the second");
        check(DeviceRuntime.deviceLimitReached(3, 2, false), "An owner above a lowered limit places no other device");
        check(!DeviceRuntime.deviceLimitReached(9, 2, true), "Operators are not limited");
        check(!DeviceRuntime.deviceLimitReached(1000, 0, false), "A limit of 0 is no limit");
    }

    private static DeviceRecord owned(DeviceRegistry registry, @Nullable UUID owner, String slug, int x) {
        DeviceRecord record = new DeviceRecord(UUID.randomUUID(), LAMP, at(x, 64, 0), slug, slug);
        record.setOwner(owner);
        registry.add(record);
        return record;
    }

    private static void expectFailure(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

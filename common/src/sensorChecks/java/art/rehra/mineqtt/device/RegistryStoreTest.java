package art.rehra.mineqtt.device;

import art.rehra.mineqtt.security.AccessMode;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The registry file (design §6.7), run by Gradle's verifyRegistryRoundTrip task: it is read back twice (the second
 * load is what wipes a vanilla SavedData without a data fixer), the backup takes over from a corrupt file, unreadable
 * files are kept aside and never overwritten, and single bad entries do not take the registry down.
 */
public final class RegistryStoreTest {
    private static final Identifier LAMP = Identifier.fromNamespaceAndPath("mineqtt", "smart_lamp");
    private static final Identifier UNKNOWN = Identifier.fromNamespaceAndPath("othermod", "gadget");

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
        check(!fresh.isDirty(), "A saved registry is clean");

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
        check(first.bySlug("hall_lamp_1").discoveryHash() == null && first.bySlug("hall_lamp_1").legacyUniqueId() == null, "Absent ones stay absent");
        check(first.legacyDiscoveryMigrated("home_kitchen") && !first.legacyDiscoveryMigrated("home_downstairs")
                && first.legacyImportDone() && first.v1StatusCleared(), "The migration flags persist");
        CompoundTag beforeFlags = written.copy();
        beforeFlags.remove("legacyDiscoveryMigrated");
        beforeFlags.remove("legacyImportDone");
        beforeFlags.remove("v1StatusCleared");
        DeviceRegistry older = DeviceRegistry.decode(beforeFlags, problem -> {
            throw new AssertionError("A schema 1 file without the migration flags is valid: " + problem);
        });
        check(!older.legacyDiscoveryMigrated("home_kitchen") && !older.legacyImportDone() && !older.v1StatusCleared(),
                "A file written before the flags existed reads with nothing migrated");

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

        DeviceRegistry restored = new RegistryStore(dir).load("world", new Random(4));
        check(restored.generatedBridgeId().equals(registry.generatedBridgeId()) && restored.records().size() == 3, "The backup takes over from a corrupt file");
        check(quarantined(dir, RegistryStore.FILE_NAME + ".corrupt-").size() == 1, "The corrupt file is kept aside");
        check(!Files.exists(store.file()), "The corrupt file is no longer in the way");
        check(restored.isDirty(), "The restored registry is saved again soon");
        RegistryStore again = new RegistryStore(dir);
        check(again.save(restored) && again.load("world", new Random(5)).encode().equals(restored.encode()), "Saving after a restore works");
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
        List<String> problems = new ArrayList<>();
        DeviceRegistry read = DeviceRegistry.decode(tag, problems::add);
        check(read.records().size() == 4, "Bad entries are skipped, the rest is read: " + read.records().size());
        check(problems.size() == 5, "Each skipped or repaired entry is reported: " + problems);
        DeviceRecord suffixed = read.bySlug(registry.records().iterator().next().slug() + "_2");
        check(suffixed != null, "A duplicate slug is suffixed");
        check(suffixed.legacyUniqueId() == null && read.groups().iterator().next().legacyUniqueId() == null,
                "A v1 unique_id that is no valid topic level is dropped");
        check(!read.isDirty(), "A decoded registry is clean");
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
        registry.remove(lamp.id());
        check(!group.members().contains(lamp.id()), "A removed device leaves its group");
        DeviceRecord other = registry.record(group.members().iterator().next());
        registry.removeGroup(group);
        check(registry.groupBySlug("downstairs") == null && other.group() == null, "Deleting a group frees its members");
        check(registry.createGroup(UUID.randomUUID(), "Down Stairs").slug().equals("down_stairs"), "Group slugs come from the name");
        check(registry.createGroup(UUID.randomUUID(), "Set").slug().equals("set_2"), "A group is never called set, which would overlap the command topics");
    }

    // Two lamps in a group, one with a queued command; one device of a type this MineQTT does not know.
    private static void populate(DeviceRegistry registry) {
        DeviceRecord kitchen = new DeviceRecord(UUID.randomUUID(), LAMP, at(17, 64, -3), "Kitchen Lamp", "kitchen_lamp");
        kitchen.setOwner(UUID.randomUUID());
        kitchen.setAccess(AccessMode.PUBLIC);
        kitchen.setExpose(true);
        kitchen.setLastState(JsonParser.parseString("{\"on\":true,\"brightness\":200,\"note\":null,\"color\":{\"r\":255,\"g\":0,\"b\":0}}").getAsJsonObject());
        JsonObject pending = new JsonObject();
        pending.addProperty("brightness", 128);
        kitchen.setPending(pending);
        kitchen.setDiscoveryHash("abc123");
        kitchen.setLegacyUniqueId("home_kitchen");
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
    }

    private static GlobalPos at(int x, int y, int z) {
        return GlobalPos.of(Level.OVERWORLD, new BlockPos(x, y, z));
    }

    private static List<Path> quarantined(Path dir, String prefix) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(path -> path.getFileName().toString().startsWith(prefix)).toList();
        }
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

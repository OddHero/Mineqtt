package art.rehra.mineqtt.device;

import art.rehra.mineqtt.MineQTT;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/**
 * The registry file {@code <world>/data/mineqtt/devices.nbt} (design §6.7). It is its own file and not vanilla
 * {@code SavedData}, whose loader would discard it on the second start.
 * <ul>
 *     <li>Saving writes {@code devices.nbt.tmp}, keeps the current file as {@code devices.nbt.bak} and moves the new
 *     one into place atomically, so a crash leaves either the new file or the previous one.</li>
 *     <li>A dirty registry is saved with the overworld (autosave, {@code /save-all}) and when the server stops. A
 *     structural change is saved at the end of its tick, at most once a second (M2 plan D7), so a device whose chunk
 *     unloads right after it was placed is still known after a crash. A failed one is tried again a second later. Until
 *     the level save, it keeps the devices broken since the last one, whose chunks may still hold their blocks.</li>
 *     <li>Loading reads {@code devices.nbt}, else the backup. An unreadable file (I/O or format error, wrong layout)
 *     is renamed to {@code devices.nbt.corrupt-<time>} and never overwritten. When nothing is readable, a new
 *     registry with a new bridge identity is started and saved at once; the devices register again from their block
 *     entities as their chunks load.</li>
 *     <li>A file of a newer schema (a newer MineQTT ran, then this one was installed again) is not corrupt: it stays
 *     where it is and nothing is saved, so the newer version finds it unchanged. This run keeps its bridge identity
 *     and starts without devices and groups.</li>
 * </ul>
 */
public final class RegistryStore {
    public static final String FILE_NAME = "devices.nbt";
    /** Used when the level name has no topic-safe characters at all. */
    static final String FALLBACK_NAME = "world";
    /** The shortest time between a save and the save of a structural change that follows it. */
    static final long STRUCTURE_SAVE_INTERVAL_MILLIS = 1_000;
    private static final DateTimeFormatter QUARANTINE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path dir;
    private final Path file;
    private final Path backup;
    private final Path temp;
    private final LongSupplier millis;
    // When a structural change may be saved again: one interval after the last save or failed attempt, whatever
    // triggered it.
    private long structureSaveDueMillis = Long.MIN_VALUE;
    // Why nothing is saved: a file that saving would replace must stay (unreadable and not moved aside, or newer).
    private @Nullable String saveBlocked;
    private boolean saveBlockedLogged;
    // Set when the registry came from the backup, one save older than the file it replaces.
    private boolean restoredFromBackup;
    // The error of the last save when it failed: a structural save is tried again every second, so the same error is
    // logged once, and then the save that works again.
    private @Nullable String saveFailure;

    public RegistryStore(Path dir) {
        this(dir, Util::getMillis);
    }

    /** @param millis a monotonic clock in milliseconds, which throttles the saves of structural changes */
    public RegistryStore(Path dir, LongSupplier millis) {
        this.dir = dir;
        this.file = dir.resolve(FILE_NAME);
        this.backup = dir.resolve(FILE_NAME + ".bak");
        this.temp = dir.resolve(FILE_NAME + ".tmp");
        this.millis = millis;
    }

    /** The store of a server's world, next to the v1 topic states in {@code data/mineqtt}. */
    public static RegistryStore of(MinecraftServer server) {
        return new RegistryStore(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(MineQTT.MOD_ID));
    }

    /** {@code slug(levelName)_<4 random hex>}, e.g. {@code world_7f3a}; always a valid bridge id. */
    public static String generateBridgeId(String levelName, RandomGenerator random) {
        String slug = Slugs.fromName(levelName);
        return (slug.isEmpty() ? FALLBACK_NAME : slug) + "_" + HexFormat.of().toHexDigits((short) random.nextInt(0x10000));
    }

    public Path file() {
        return file;
    }

    /**
     * The saved registry, else one restored from the backup, else a new one for {@code levelName}. A new registry is
     * saved before this returns, so its bridge id is the same after a crash.
     */
    public DeviceRegistry load(String levelName, RandomGenerator random) {
        boolean fileUnreadable = false;
        if (Files.exists(file)) {
            CompoundTag tag = readTag(file);
            // Checked before the backup, which may be an older copy the newer version would lose.
            if (tag != null && DeviceRegistry.isNewerSchema(tag)) return newerSchema(file, tag, levelName, random);
            DeviceRegistry registry = decode(file, tag);
            if (registry != null) return registry;
            fileUnreadable = true;
            quarantine(file);
        }
        if (Files.exists(backup)) {
            CompoundTag tag = readTag(backup);
            if (tag != null && DeviceRegistry.isNewerSchema(tag)) return newerSchema(backup, tag, levelName, random);
            DeviceRegistry registry = decode(backup, tag);
            if (registry != null) {
                MineQTT.LOGGER.warn("Restored the MineQTT device registry from its backup {}", backup);
                restoredFromBackup = true;
                // Rewrite the main file, so the next save keeps this good copy as the backup.
                registry.markDirty();
                return registry;
            }
            quarantine(backup);
        }
        if (fileUnreadable) {
            MineQTT.LOGGER.error("The MineQTT device registry could not be read and was moved aside; starting a new one. Devices "
                    + "register again as their chunks load, but groups and commands queued for unloaded devices are lost");
        }
        DeviceRegistry registry = new DeviceRegistry(generateBridgeId(levelName, random), UUID.randomUUID());
        registry.markDirty();
        MineQTT.LOGGER.info("New MineQTT device registry for world '{}': bridge id {}", levelName, registry.generatedBridgeId());
        save(registry);
        return registry;
    }

    /** Writes the registry when it changed since it was loaded or last saved. */
    public void saveIfDirty(DeviceRegistry registry) {
        if (registry.isDirty()) save(registry);
    }

    /**
     * The end of a tick: writes the registry when its structure changed and the last save is at least a second ago, so
     * a run of changes is saved once a second while it lasts. The devices broken since the last level save are written
     * too ({@link DeviceRegistry#encodeKeepingBroken()}). A failed save keeps the change pending, so it is tried again a
     * second later.
     */
    public void saveStructure(DeviceRegistry registry) {
        if (!structureSavePending(registry) || millis.getAsLong() < structureSaveDueMillis) return;
        write(registry, true);
    }

    /**
     * Why this run's registry may lack devices that exist, or null when it holds every device the world saved: nothing
     * is saved (a file of a newer schema, or an unreadable one that could not be moved aside), or it was restored from
     * the backup, which misses the devices added by the last save. Nothing is deleted for not being in such a registry
     * (the ghost cleanup).
     */
    public @Nullable String incompleteReason() {
        if (saveBlocked != null) return "it is not saved: " + saveBlocked;
        return restoredFromBackup ? "it was restored from its backup, which is one save old" : null;
    }

    /** Whether {@link #saveStructure} still has a structural change to write, now or once the interval has passed. */
    public boolean structureSavePending(DeviceRegistry registry) {
        return registry.structureChanged() && saveBlocked == null;
    }

    /**
     * Writes the registry atomically and keeps the previous file as the backup. This is the level save: the devices
     * broken before it are left out from now on.
     *
     * @return false when it could not be written; the error is logged unless it is the one the last save failed with
     */
    public boolean save(DeviceRegistry registry) {
        return write(registry, false);
    }

    private boolean write(DeviceRegistry registry, boolean keepBroken) {
        if (saveBlocked != null) {
            if (!saveBlockedLogged) MineQTT.LOGGER.error("Not saving the MineQTT device registry to {}: {}", file, saveBlocked);
            saveBlockedLogged = true;
            return false;
        }
        structureSaveDueMillis = millis.getAsLong() + STRUCTURE_SAVE_INTERVAL_MILLIS;
        try {
            Files.createDirectories(dir);
            CompoundTag tag = keepBroken ? registry.encodeKeepingBroken() : registry.encode();
            NbtIo.writeCompressed(tag, temp);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            if (Files.exists(file)) move(file, backup);
            move(temp, file);
            if (keepBroken) {
                registry.markStructureSaved();
            } else {
                registry.markSaved();
            }
            if (saveFailure != null) MineQTT.LOGGER.info("Saved the MineQTT device registry to {} again", file);
            saveFailure = null;
            return true;
        } catch (IOException | RuntimeException e) {
            String failure = e.toString();
            if (!failure.equals(saveFailure)) MineQTT.LOGGER.error("Could not save the MineQTT device registry to {}", file, e);
            saveFailure = failure;
            return false;
        }
    }

    // A newer MineQTT wrote it: the file stays as it is and nothing is saved. This run keeps the bridge identity, so
    // no topic moves and Home Assistant sees the same bridge; its devices register again for this run only.
    private DeviceRegistry newerSchema(Path path, CompoundTag tag, String levelName, RandomGenerator random) {
        saveBlocked = path.getFileName() + " is of a newer schema";
        MineQTT.LOGGER.error("The MineQTT device registry {} was written by a newer MineQTT (schema {}, this one reads up to {}). It is "
                + "left unchanged and nothing is saved until that version runs again; this run has no groups and no queued commands",
                path, tag.getIntOr("schema", 0), DeviceRegistry.SCHEMA);
        return DeviceRegistry.identityOf(tag).orElseGet(() -> new DeviceRegistry(generateBridgeId(levelName, random), UUID.randomUUID()));
    }

    private static @Nullable CompoundTag readTag(Path path) {
        try {
            return NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        } catch (IOException | RuntimeException e) {
            MineQTT.LOGGER.error("Could not read the MineQTT device registry {}", path, e);
            return null;
        }
    }

    private static @Nullable DeviceRegistry decode(Path path, @Nullable CompoundTag tag) {
        if (tag == null) return null;
        try {
            DeviceRegistry registry = DeviceRegistry.decode(tag, problem -> MineQTT.LOGGER.warn("MineQTT device registry {}: {}", path.getFileName(), problem));
            MineQTT.LOGGER.info("Loaded the MineQTT device registry from {}: {} devices, {} groups", path.getFileName(), registry.records().size(), registry.groups().size());
            return registry;
        } catch (RuntimeException e) {
            MineQTT.LOGGER.error("Could not read the MineQTT device registry {}", path, e);
            return null;
        }
    }

    private void quarantine(Path path) {
        String stamp = LocalDateTime.now().format(QUARANTINE_TIME);
        Path target = path.resolveSibling(path.getFileName() + ".corrupt-" + stamp);
        for (int n = 2; Files.exists(target); n++) {
            target = path.resolveSibling(path.getFileName() + ".corrupt-" + stamp + "-" + n);
        }
        try {
            Files.move(path, target);
            MineQTT.LOGGER.error("Kept the unreadable {} as {}", path.getFileName(), target.getFileName());
        } catch (IOException e) {
            saveBlocked = "the unreadable " + path.getFileName() + " could not be moved aside";
            MineQTT.LOGGER.error("Could not move the unreadable {} aside; the device registry will not be saved until it is removed", path, e);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

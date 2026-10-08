package art.rehra.mineqtt.device;

import art.rehra.mineqtt.ha.Ha;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.security.AccessMode;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import sun.misc.Unsafe;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The server-only lifecycle without a running game, run by Gradle's verifyLifecycleGuards task: the predicates the
 * block entity hooks use (no attach without a server level, destroy versus detach), the untrusted-data sanitiser, the
 * {@code mineqtt} compound, and the per-type publish limits.
 */
public final class LifecycleGuardTest {
    private static final Field<Integer> POWER = Field.intRange("power", 0, 15, Access.READ_WRITE);
    private static final DeviceType TYPE = DeviceType.builder(Identifier.fromNamespaceAndPath("mineqtt", "test_output"))
            .model("Test Output").field(POWER).entity(Ha.number("power", POWER).range(0, 15))
            .rawLink(RawLinkSpec.in(ValueCodecs.REDSTONE)).build();
    private static final UUID OWNER = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID PLACER = UUID.fromString("99999999-8888-4777-8666-555555555555");

    static void main(String[] args) throws ReflectiveOperationException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        attachGuard();
        removalTable();
        placementSanitiser();
        unseenRevalidation();
        compoundRoundTrip();
        throttle();
        System.out.println("Lifecycle guard checks passed.");
    }

    private static void attachGuard() throws ReflectiveOperationException {
        check(!DeviceLink.shouldAttach(null), "A block entity without a level never attaches");
        // A client level as the client's block entities see it; allocated without running its constructor.
        java.lang.reflect.Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Level clientLevel = (Level) ((Unsafe) unsafeField.get(null)).allocateInstance(ClientLevel.class);
        check(!DeviceLink.shouldAttach(clientLevel), "A client block entity never attaches");
    }

    private static void removalTable() {
        check(DeviceLink.removal(true, true) == DeviceLink.Removal.DESTROY, "Loaded chunk, other block: /setblock or /fill replaced it");
        check(DeviceLink.removal(true, false) == DeviceLink.Removal.DETACH, "Loaded chunk, same block: the chunk is unloading");
        check(DeviceLink.removal(false, false) == DeviceLink.Removal.DETACH, "Chunk gone: unload");
        check(DeviceLink.removal(false, true) == DeviceLink.Removal.DETACH, "Without the chunk nothing proves a replacement");
    }

    private static void placementSanitiser() {
        DeviceLink link = linkWithCopiedData();
        UUID copied = link.id();
        link.sanitizePlaced(PLACER, "Alex", raw -> Optional.of("not in the raw topic allowlist"));
        check(link.id() != null && !link.id().equals(copied) && link.name().isEmpty() && link.slug().isEmpty(),
                "A copied identity is dropped, a fresh one is minted");
        check(PLACER.equals(link.owner()) && link.ownerName().equals("Alex"), "The placer owns the device");
        check(link.access() == AccessMode.CLAIM, "A copied access mode is dropped, access follows the claim");
        check(link.expose() == null, "Exposure falls back to the policy default");
        check(link.raw().grandfathered() == null, "Grandfather flags are dropped");
        check(!link.raw().active() && link.raw().disabledReason().equals("not in the raw topic allowlist"), "A topic the placer may not use is disabled");
        check(link.placedByPlayer(), "The attach knows the data was already checked");

        DeviceLink allowed = linkWithCopiedData();
        allowed.sanitizePlaced(PLACER, "Alex", raw -> Optional.empty());
        check(allowed.raw().active() && allowed.raw().grandfathered() == null, "An allowed topic stays usable, still without its grandfather flag");
    }

    private static void unseenRevalidation() {
        DeviceLink link = linkWithCopiedData();
        TopicAcl.Grandfather[] seen = new TopicAcl.Grandfather[1];
        link.revalidateUnseen(true, raw -> {
            seen[0] = raw.grandfathered();
            return Optional.empty();
        });
        check(seen[0] != null && seen[0].owner().equals(OWNER), "The second check sees the grandfather flag, bound to the NBT owner");
        check(link.raw().active() && Boolean.TRUE.equals(link.expose()) && OWNER.equals(link.owner()),
                "An unseen id keeps its owner, and the exposure its owner may grant");
        link.revalidateUnseen(true, raw -> Optional.of("denied"));
        check(!link.raw().active(), "An unseen id whose topic is refused loses the link");
        link.revalidateUnseen(true, raw -> Optional.empty());
        check(link.raw().active(), "A link refused before is enabled again once the ACL allows it");
        link.revalidateUnseen(false, raw -> Optional.empty());
        check(link.expose() == null, "An exposure the owner may not grant falls back to the policy default");
        DeviceLink hidden = linkWithCopiedData(false);
        hidden.revalidateUnseen(false, raw -> Optional.empty());
        check(Boolean.FALSE.equals(hidden.expose()), "Hiding a device needs no right");
    }

    private static void compoundRoundTrip() {
        DeviceLink link = linkWithCopiedData();
        UUID id = UUID.randomUUID();
        link.assign(id, "Garage Door", "garage_door");
        TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        link.save(output).putInt("power", 9);
        CompoundTag tag = output.buildResult();
        CompoundTag data = tag.getCompound(DeviceLink.KEY).orElseThrow();
        check(data.getIntOr("v", 0) == DeviceLink.VERSION && data.getStringOr("type", "").equals("mineqtt:test_output"), "Version and type are written");

        DeviceLink loaded = new DeviceLink(TYPE);
        Optional<ValueInput> compound = loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, tag), false);
        check(compound.isPresent() && compound.get().getIntOr("power", 0) == 9, "The block entity's own keys are in the same compound");
        check(id.equals(loaded.id()) && loaded.name().equals("Garage Door") && loaded.slug().equals("garage_door"), "Identity round-trips");
        check(OWNER.equals(loaded.owner()) && Boolean.TRUE.equals(loaded.expose()), "Owner and exposure round-trip");
        check(loaded.access() == AccessMode.PRIVATE, "The access mode round-trips");
        check(loaded.raw().equals(link.raw()), "The Raw Link round-trips, grandfather flag included: " + loaded.raw());
        check(!loaded.attached(), "Runtime state is never loaded");

        DeviceLink v1 = new DeviceLink(TYPE);
        check(v1.load(TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, new CompoundTag()), false).isEmpty() && v1.id() == null,
                "A block entity without a mineqtt compound has no identity yet");
        check(v1.access() == AccessMode.CLAIM, "A device without an access mode follows the claim");
        DeviceLink withV1Keys = new DeviceLink(TYPE);
        check(withV1Keys.load(TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, tag), true).isPresent() && !withV1Keys.awaitingAdoption(),
                "A compound with an id is v2 data, whatever else the data holds");
    }

    private static void throttle() {
        StateThrottle actuator = new StateThrottle(Duration.ZERO, Map.of());
        check(actuator.offer(state(1, 20.0), 0) == StateThrottle.Verdict.PUBLISH, "First state");
        check(actuator.offer(state(2, 20.0), 1) == StateThrottle.Verdict.PUBLISH, "An actuator publishes every change at once");
        check(actuator.offer(state(2, 20.0), 2) == StateThrottle.Verdict.SKIP, "An unchanged state is not published");

        StateThrottle sensor = new StateThrottle(Duration.ofSeconds(1), Map.of("temperature", 0.2));
        check(sensor.offer(state(1, 20.0), 0) == StateThrottle.Verdict.PUBLISH, "First sensor state");
        check(sensor.offer(state(1, 20.1), 100) == StateThrottle.Verdict.SKIP, "Inside the deadband");
        check(sensor.offer(state(1, 20.15), 2000) == StateThrottle.Verdict.SKIP, "Still inside the deadband of the published value");
        check(sensor.offer(state(1, 20.3), 2100) == StateThrottle.Verdict.PUBLISH, "Drift that adds up is published");
        check(sensor.offer(state(2, 20.3), 2200) == StateThrottle.Verdict.DEFER, "A change inside the interval waits");
        check(sensor.offer(state(3, 20.3), 2300) == StateThrottle.Verdict.DEFER, "A newer state replaces the waiting one");
        check(sensor.due(2500) == null, "Not due before the interval is over");
        JsonObject due = sensor.due(3100);
        check(due != null && due.get("count").getAsInt() == 3, "The newest waiting state is published when the interval is over, never dropped");
        check(sensor.due(5000) == null, "Published once");
        check(sensor.offer(state(4, 20.3), 5000) == StateThrottle.Verdict.PUBLISH, "After the interval, a change is published at once");
        check(sensor.offer(state(5, 20.3), 5100) == StateThrottle.Verdict.DEFER, "Waiting again");
        check(sensor.offer(state(4, 20.35), 5200) == StateThrottle.Verdict.SKIP && sensor.due(9000) == null,
                "Going back near the published state drops the stale waiting state");
    }

    private static DeviceLink linkWithCopiedData() {
        return linkWithCopiedData(true);
    }

    private static DeviceLink linkWithCopiedData(boolean expose) {
        DeviceLink link = new DeviceLink(TYPE);
        CompoundTag data = new CompoundTag();
        data.store("id", UUIDUtil.CODEC, UUID.randomUUID());
        data.putString("name", "Copied Name");
        data.putString("slug", "copied_slug");
        data.store("owner", UUIDUtil.CODEC, OWNER);
        data.putString("ownerName", "Steve");
        data.putString("access", "private");
        data.putBoolean("expose", expose);
        data.store("raw", RawLink.CODEC, new RawLink("zigbee2mqtt/garage/set", RawLinkSpec.Direction.IN, "OPEN", null, 0, false,
                new TopicAcl.Grandfather("zigbee2mqtt/garage/set", OWNER), false, null));
        CompoundTag tag = new CompoundTag();
        tag.put(DeviceLink.KEY, data);
        link.load(TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, tag), false);
        return link;
    }

    private static JsonObject state(int count, double temperature) {
        return JsonParser.parseString("{\"count\":" + count + ",\"temperature\":" + temperature + "}").getAsJsonObject();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

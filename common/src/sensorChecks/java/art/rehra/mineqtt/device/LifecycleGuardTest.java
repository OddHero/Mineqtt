package art.rehra.mineqtt.device;

import art.rehra.mineqtt.CheckItems;
import art.rehra.mineqtt.blocks.Interaction;
import art.rehra.mineqtt.blocks.LightRemoteBlock;
import art.rehra.mineqtt.device.types.PresenceSensor;
import art.rehra.mineqtt.device.types.RedstoneInput;
import art.rehra.mineqtt.device.types.RedstoneOutput;
import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.device.types.SmartPlug;
import art.rehra.mineqtt.device.types.Timers;
import art.rehra.mineqtt.device.types.WallSwitch;
import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.flow.core.FlowMessage;
import art.rehra.mineqtt.flow.core.Mapping;
import art.rehra.mineqtt.flow.core.MessageBus;
import art.rehra.mineqtt.flow.core.PortSpec;
import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.flow.core.Root;
import art.rehra.mineqtt.flow.core.Scope;
import art.rehra.mineqtt.flow.core.Wire;
import art.rehra.mineqtt.ha.Ha;
import art.rehra.mineqtt.items.ConfiguratorMode;
import art.rehra.mineqtt.legacy.LegacyNbtV1;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.security.AccessMode;
import art.rehra.mineqtt.twin.TwinRequests;
import art.rehra.mineqtt.twin.core.Binding;
import art.rehra.mineqtt.twin.core.EntityGlobs;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import sun.misc.Unsafe;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * The server-only lifecycle without a running game, run by Gradle's verifyLifecycleGuards task: the predicates the
 * block entity hooks use (no attach without a server level, destroy versus detach), the attach guard that isolates a
 * failing device and hands back what its attach used up, the untrusted-data sanitiser, the {@code mineqtt} compound, a
 * claim of a device without an owner, the attach's exposure merge, the routing table of the one command path, the root
 * each command brings to the message bus, what a right-click on a MineQTT block does, and the per-type publish limits.
 */
public final class LifecycleGuardTest {
    private static final Field<Integer> POWER = Field.intRange("power", 0, 15, Access.READ_WRITE);
    private static final DeviceType TYPE = DeviceType.builder(Identifier.fromNamespaceAndPath("mineqtt", "test_output"))
            .model("Test Output").field(POWER).entity(Ha.number("power", POWER).range(0, 15))
            .rawLink(RawLinkSpec.in(ValueCodecs.REDSTONE)).build();
    private static final UUID OWNER = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID PLACER = UUID.fromString("99999999-8888-4777-8666-555555555555");

    public static void main(String[] args) throws ReflectiveOperationException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        attachGuard();
        attachQueue();
        attachHandBack();
        removalTable();
        placementSanitiser();
        timers();
        unseenRevalidation();
        claim();
        exposeMerge();
        commandRoutes();
        commandRoots();
        interaction();
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

    /**
     * M4-3 (plan D2, D4): a timer is unavailable while unloaded, so a command to an unloaded one is dropped, never queued;
     * a timer item placed with v1 data is adopted as a placement: the placer owns it, no Raw Link, no legacy items.
     */
    private static void timers() {
        for (DeviceType timer : Timers.all()) {
            check(plain(false, timer, false) == DeviceRuntime.Route.DROP && plain(false, timer, true) == DeviceRuntime.Route.APPLY,
                    timer + ": a loaded timer takes a command, an unloaded one drops it (a queued trigger would be a stale world edit)");
        }
        CompoundTag v1 = new CompoundTag();
        v1.putString("ownerName", "Steve");
        v1.putBoolean("privateMode", true);
        v1.putString("lastTabId", "alarm_clock");
        v1.putInt("alarmTime", 6000);
        DeviceLink placed = new DeviceLink(Timers.ALARM_CLOCK);
        placed.load(TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, v1), true);
        check(placed.awaitingAdoption(), "A timer item's v1 data waits as v1 data");
        placed.sanitizePlaced(PLACER, "Alex", raw -> Optional.empty());
        check(!placed.awaitingAdoption() && placed.raw() == null && placed.legacyItems().isEmpty() && PLACER.equals(placed.owner()) && placed.id() != null
                && !placed.privateMode(), "Placed, it is adopted at once: the placer owns it, without a Raw Link, legacy items or v1's private mode");
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
        check("zigbee2mqtt/garage/set".equals(link.raw().channel()), "The grandfathered topic becomes the channel, resolved for the placer at the attach");
        check(!link.raw().active() && link.raw().disabledReason().equals("not in the raw topic allowlist"), "A topic the placer may not use is disabled");
        check(link.placedByPlayer(), "The attach knows the data was already checked");

        DeviceLink allowed = linkWithCopiedData();
        allowed.sanitizePlaced(PLACER, "Alex", raw -> Optional.empty());
        check(allowed.raw().active() && allowed.raw().grandfathered() == null, "An allowed topic stays usable, still without its grandfather flag");

        // M3 plan D13 #6: a copied binding is judged for the placer.
        Binding copiedBinding = TwinTestTypes.bound("switch.porch", true);
        TwinRules.Rights mayBind = new TwinRules.Rights(true, false, EntityGlobs.ALL, Optional.empty());
        DeviceLink bound = new DeviceLink(TwinTestTypes.TYPE);
        bound.setTwin(copiedBinding);
        bound.sanitizePlaced(PLACER, "Alex", raw -> Optional.empty(), mayBind);
        check(bound.twin().active() && !bound.twin().control() && bound.twin().sourceEntity().orElse("").equals("switch.porch"),
                "A placer who may bind but not control keeps the binding without control: " + bound.twin());
        DeviceLink stranger = new DeviceLink(TwinTestTypes.TYPE);
        stranger.setTwin(copiedBinding);
        stranger.sanitizePlaced(PLACER, "Alex", raw -> Optional.empty());
        check(stranger.twin().bound() && !stranger.twin().active() && stranger.twin().refused().contains("mineqtt:twin.bind"),
                "A placer who may not bind gets the binding refused, kept to heal: " + stranger.twin());
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

        // A change of exactly the band is published, also where the binary difference falls short of it (20.2 - 20.0, 64.1 - 63.1).
        StateThrottle bands = new StateThrottle(Duration.ZERO, Map.of("temperature", 0.2, "humidity", 1.0));
        check(bands.offer(climate(20.0, 63.1), 0) == StateThrottle.Verdict.PUBLISH, "First climate state");
        check(bands.offer(climate(20.2, 63.1), 1) == StateThrottle.Verdict.PUBLISH, "A temperature change of exactly 0.2 is published");
        check(bands.offer(climate(20.2, 64.1), 2) == StateThrottle.Verdict.PUBLISH, "A humidity change of exactly 1 is published");
        check(bands.offer(climate(20.39, 65.09), 3) == StateThrottle.Verdict.SKIP, "Changes just inside both bands are not");
    }

    /**
     * A player's claim of a device without an owner binds its waiting grandfather flag to them and settles it, so the
     * topic never joins the claimer's literal set, not at a later attach either.
     */
    private static void claim() {
        DeviceLink link = new DeviceLink(TYPE);
        CompoundTag data = new CompoundTag();
        data.store("id", UUIDUtil.CODEC, UUID.randomUUID());
        data.store("raw", RawLink.CODEC, new RawLink("zigbee2mqtt/lock/set", RawLinkSpec.Direction.IN, null, null, 0, null,
                new TopicAcl.Grandfather("zigbee2mqtt/lock/set", null), false, null, 0, null, RawLink.Edges.BOTH));
        CompoundTag tag = new CompoundTag();
        tag.put(DeviceLink.KEY, data);
        link.load(TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, tag), false);
        BlockEntity blockEntity = new SignBlockEntity(BlockPos.ZERO, Blocks.OAK_SIGN.defaultBlockState());
        check(link.claim(blockEntity, PLACER, "Alex") && PLACER.equals(link.owner()) && link.ownerName().equals("Alex"), "The player claims the device");
        RawLink raw = link.raw();
        check(PLACER.equals(raw.grandfathered().owner()) && raw.resolved(), "The flag is bound to the claimer and settled: " + raw);
        DeviceRegistry registry = new DeviceRegistry("world_7f3a", UUID.randomUUID());
        check(LegacyAdoption.settleGrandfathered(registry, raw, true) == null && registry.ownerLiterals(PLACER).isEmpty(),
                "The claimed topic never joins the claimer's literal set");
        check(!link.claim(blockEntity, OWNER, "Steve"), "A device with an owner cannot be claimed");
    }

    /**
     * The attach's merge of a device's exposure: a new device brings its own, while for a registered one the registry
     * wins, so {@code /mineqtt devices expose} on an unloaded device survives its next chunk load.
     */
    private static void exposeMerge() {
        DeviceLink placed = linkWithCopiedData(true);
        DeviceRecord fresh = record(null);
        check(!DeviceRuntime.mergeExpose(fresh, placed, false) && Boolean.TRUE.equals(fresh.expose()) && Boolean.TRUE.equals(placed.expose()),
                "A device new to the registry gives its link's exposure to the record");

        DeviceLink unloaded = linkWithCopiedData(false);
        DeviceRecord exposedMeanwhile = record(true);
        check(DeviceRuntime.mergeExpose(exposedMeanwhile, unloaded, true) && Boolean.TRUE.equals(unloaded.expose())
                && Boolean.TRUE.equals(exposedMeanwhile.expose()), "A device exposed while unloaded takes the registry's value and is saved");
        DeviceLink followed = linkWithCopiedData(true);
        DeviceRecord reset = record(null);
        check(DeviceRuntime.mergeExpose(reset, followed, true) && followed.expose() == null && reset.expose() == null,
                "A device set back to follow ha.expose while unloaded follows it again");
        check(!DeviceRuntime.mergeExpose(exposedMeanwhile, unloaded, true), "An unchanged exposure leaves the block entity unsaved");
    }

    /**
     * The routing table of {@link DeviceRuntime#command} (M2 plan §3.3): Home Assistant, groups, Raw Link inputs, players
     * and later wires all take it, so a loaded device applies, an unloaded actuator queues and an unloaded sensor drops.
     */
    private static void commandRoutes() {
        DeviceType sensor = PresenceSensor.TYPE;
        check(TYPE.unloadedPolicy() == UnloadedPolicy.QUEUE && sensor.unloadedPolicy() == UnloadedPolicy.UNAVAILABLE, "One type of each policy");
        check(plain(false, TYPE, true) == DeviceRuntime.Route.APPLY, "A loaded actuator applies the command");
        check(plain(false, sensor, true) == DeviceRuntime.Route.APPLY, "A loaded sensor gets it too, and answers for itself");
        check(plain(false, TYPE, false) == DeviceRuntime.Route.QUEUE, "An unloaded QUEUE device queues it");
        check(plain(false, sensor, false) == DeviceRuntime.Route.DROP, "An unloaded UNAVAILABLE device drops it");
        check(plain(false, null, true) == DeviceRuntime.Route.DROP && plain(false, null, false) == DeviceRuntime.Route.DROP,
                "A device of a type this MineQTT does not know drops it");
        check(plain(true, TYPE, false) == DeviceRuntime.Route.FAN_OUT && plain(true, TYPE, true) == DeviceRuntime.Route.FAN_OUT,
                "A group fans it out to its members, whatever its own load state");
        check(plain(true, null, false) == DeviceRuntime.Route.DROP, "A group without a (known) member type drops it");
        // A new following wire seeds its target only from a source that is not offline (M2-16).
        check(DeviceRuntime.seeds(TYPE, true, false) && DeviceRuntime.seeds(sensor, true, false), "A loaded source seeds a new wire's target");
        check(DeviceRuntime.seeds(TYPE, false, false), "An unloaded QUEUE source seeds it from its record");
        check(!DeviceRuntime.seeds(sensor, false, false), "An unloaded UNAVAILABLE source does not: its stored value is stale");
        check(!DeviceRuntime.seeds(TYPE, false, true) && !DeviceRuntime.seeds(sensor, false, true), "Nor does a faulted source");
        // A wire's message never goes back into the device it came from, also through that device's group (M2-15).
        UUID member = UUID.fromString("00000000-0000-0000-0000-00000000aa01");
        UUID other = UUID.fromString("00000000-0000-0000-0000-00000000aa02");
        check(DeviceRuntime.skipsMember(CommandSource.BINDING, member, member), "A group's fan-out of a wire's message skips the member it came from");
        check(!DeviceRuntime.skipsMember(CommandSource.BINDING, member, other) && !DeviceRuntime.skipsMember(CommandSource.BINDING, null, member),
                "It reaches every other member");
        for (CommandSource source : CommandSource.values()) {
            if (source != CommandSource.BINDING) check(!DeviceRuntime.skipsMember(source, member, member), "A " + source + " command reaches every member");
        }
        // What the bus counts as taken: a device that applied, ignored or queued it, and a group's fan-out.
        check(DeviceRuntime.taken(new DeviceRuntime.Delivery(DeviceRuntime.Route.APPLY, CommandResult.applied()))
                && DeviceRuntime.taken(new DeviceRuntime.Delivery(DeviceRuntime.Route.APPLY, CommandResult.unchanged()))
                && DeviceRuntime.taken(new DeviceRuntime.Delivery(DeviceRuntime.Route.QUEUE, null))
                && DeviceRuntime.taken(new DeviceRuntime.Delivery(DeviceRuntime.Route.FAN_OUT, null)), "A wire's message is taken unless refused");
        check(!DeviceRuntime.taken(new DeviceRuntime.Delivery(DeviceRuntime.Route.APPLY, CommandResult.rejected("no")))
                && !DeviceRuntime.taken(new DeviceRuntime.Delivery(DeviceRuntime.Route.DROP, null)),
                "A device that rejected it, or a target that drops it, refused it");
        check(DeviceRuntime.taken(new DeviceRuntime.Delivery(DeviceRuntime.Route.FORWARD, null, null))
                && !DeviceRuntime.taken(new DeviceRuntime.Delivery(DeviceRuntime.Route.FORWARD, null, "not sent"))
                && !DeviceRuntime.taken(new DeviceRuntime.Delivery(DeviceRuntime.Route.REFUSE, null, "mirrors light.kitchen")),
                "A forward is taken when it was handed on; a refused command and an unsent forward are not");
        // M3-9: what became of the forward's request decides; dedupe and a value waiting for the rate count as taken.
        check(DeviceRuntime.taken(DeviceRuntime.Delivery.forwarded(new TwinRequests.Sent("0123456789ab", "light.turn_on")))
                && DeviceRuntime.taken(DeviceRuntime.Delivery.forwarded(new TwinRequests.Held("light.turn_on")))
                && DeviceRuntime.taken(DeviceRuntime.Delivery.forwarded(new TwinRequests.Skipped("it already is on"))), "A sent, held or needless forward is taken");
        for (TwinRequests.Refusal refusal : TwinRequests.Refusal.values()) {
            DeviceRuntime.Delivery refused = DeviceRuntime.Delivery.forwarded(new TwinRequests.Refused(refusal, "not sent: " + refusal));
            check(!DeviceRuntime.taken(refused) && refused.reason().equals("not sent: " + refusal) && refused.route() == DeviceRuntime.Route.FORWARD,
                    "A forward refused for " + refusal + " is not taken and says why");
        }
        mirrorRoutes();
    }

    // Every source into an unbound device, or into a port its source does not own, routes as before M3.
    private static DeviceRuntime.Route plain(boolean group, DeviceType type, boolean attached) {
        DeviceRuntime.Route route = DeviceRuntime.route(group, type, attached, CommandSource.PLAYER, DeviceRuntime.Mirrored.NONE);
        for (CommandSource source : CommandSource.values()) {
            if (source == CommandSource.TWIN) continue;
            check(DeviceRuntime.route(group, type, attached, source, DeviceRuntime.Mirrored.NONE) == route,
                    "A " + source + " command to a plain device routes as a player's");
        }
        return route;
    }

    /**
     * M3 plan §3.4, D14, D17: the routes of a bound device. FORWARD and REFUSE are decided before any queue, the mirrored
     * device's own apply never queues and never forwards, Home Assistant, groups and the paused Raw Link are refused, and
     * players and wires forward exactly while control is on.
     */
    private static void mirrorRoutes() {
        DeviceRuntime.Mirrored controlled = new DeviceRuntime.Mirrored(true, true);
        DeviceRuntime.Mirrored readOnly = new DeviceRuntime.Mirrored(true, false);
        for (boolean attached : new boolean[]{true, false}) {
            String where = attached ? "a loaded" : "an unloaded";
            for (DeviceRuntime.Mirrored mirrored : List.of(controlled, readOnly, DeviceRuntime.Mirrored.NONE)) {
                check(DeviceRuntime.route(false, TYPE, attached, CommandSource.TWIN, mirrored) == (attached ? DeviceRuntime.Route.APPLY : DeviceRuntime.Route.DROP),
                        "TWIN applies to " + where + " device and is never queued nor forwarded (" + mirrored + ")");
            }
            for (CommandSource source : List.of(CommandSource.PLAYER, CommandSource.BINDING)) {
                check(DeviceRuntime.route(false, TYPE, attached, source, controlled) == DeviceRuntime.Route.FORWARD,
                        "A " + source + " command to a mirrored port of " + where + " device forwards while control is on");
                check(DeviceRuntime.route(false, TYPE, attached, source, readOnly) == DeviceRuntime.Route.REFUSE,
                        "A " + source + " command to a mirrored port of " + where + " device is refused while control is off");
            }
            for (CommandSource source : List.of(CommandSource.HA, CommandSource.GROUP, CommandSource.RAW)) {
                check(DeviceRuntime.route(false, TYPE, attached, source, controlled) == DeviceRuntime.Route.REFUSE
                                && DeviceRuntime.route(false, TYPE, attached, source, readOnly) == DeviceRuntime.Route.REFUSE,
                        "A " + source + " command to a mirrored port of " + where + " device is refused, control or not");
            }
        }
        check(DeviceRuntime.route(false, PresenceSensor.TYPE, false, CommandSource.PLAYER, controlled) == DeviceRuntime.Route.FORWARD,
                "A forward does not need the device loaded (D11: a wire into an unloaded mirrored device still forwards)");
        check(DeviceRuntime.route(false, null, true, CommandSource.TWIN, controlled) == DeviceRuntime.Route.DROP, "An unknown type drops even TWIN");
        check(DeviceRuntime.route(true, TYPE, true, CommandSource.HA, DeviceRuntime.Mirrored.NONE) == DeviceRuntime.Route.FAN_OUT,
                "A group fans out (it has no bound member, D6)");
    }

    /**
     * M3 plan D10's root table ({@link DeviceRuntime#root}, {@link DeviceRuntime#commandRoot}): the root each command
     * source brings, what inherits it, and that a block's own cause (a ticker, a neighbour update, a hand) opens no extent,
     * so inside a command's extent it carries that command's root and outside any it is WORLD.
     */
    private static void commandRoots() {
        check(DeviceRuntime.root(CommandSource.HA).equals(Optional.of(Root.HA)), "A /set topic is HA");
        check(DeviceRuntime.root(CommandSource.RAW).equals(Optional.of(Root.RAW)), "A Raw Link message is RAW, not WORLD");
        check(DeviceRuntime.root(CommandSource.PLAYER).equals(Optional.of(Root.PLAYER)), "A player's command is PLAYER");
        check(DeviceRuntime.root(CommandSource.TWIN).equals(Optional.of(Root.TWIN)), "The mirrored real device is TWIN");
        check(DeviceRuntime.root(CommandSource.GROUP).isEmpty() && DeviceRuntime.root(CommandSource.BINDING).isEmpty(),
                "A group's fan-out and a wire's message inherit");
        for (CommandSource source : CommandSource.values()) {
            Root own = DeviceRuntime.root(source).orElse(null);
            check(DeviceRuntime.commandRoot(source, null) == (own == null ? Root.WORLD : own), source + " outside any extent");
            check(DeviceRuntime.commandRoot(source, Root.WORLD) == (own == null ? Root.WORLD : own), source + " inside the world's extent");
            for (Root outside : List.of(Root.TWIN, Root.HA, Root.RAW, Root.STARTUP)) {
                Root expected = own == null || own.inGame() ? outside : own;
                check(DeviceRuntime.commandRoot(source, outside) == expected, source + " inside " + outside + " is " + expected);
            }
        }
        check(DeviceRuntime.commandRoot(CommandSource.BINDING, Root.TWIN) == Root.TWIN && DeviceRuntime.commandRoot(CommandSource.GROUP, Root.HA) == Root.HA,
                "A wire's message and a group's fan-out keep an outside root");
        check(DeviceRuntime.commandRoot(CommandSource.PLAYER, Root.TWIN) == Root.TWIN, "Nothing in a TWIN extent becomes a player's");
        check(DeviceRuntime.commandRoot(CommandSource.BINDING, Root.PLAYER) == Root.PLAYER, "A wire carries a player's root on");

        // A block's own cause through a real bus: a Redstone Input's neighbour update emits what it reads.
        UUID inputId = UUID.fromString("00000000-0000-0000-0000-00000000bb01");
        UUID lampId = UUID.fromString("00000000-0000-0000-0000-00000000bb02");
        Endpoint powered = Endpoint.port(inputId, "powered");
        Endpoint lampState = Endpoint.port(lampId, "state");
        Wire wire = new Wire(UUID.randomUUID(), powered, lampState, Mapping.FOLLOW, Scope.WIRELESS, OWNER, true);
        List<Root> roots = new ArrayList<>();
        MessageBus bus = new MessageBus(new MessageBus.Wiring() {
            @Override
            public List<Wire> from(Endpoint source) {
                return source.equals(powered) ? List.of(wire) : List.of();
            }

            @Override
            public Optional<? extends PortSpec> out(Endpoint source) {
                return source.equals(powered) ? Optional.of(new Spec(PortType.BOOL, false)) : Optional.empty();
            }

            @Override
            public Optional<? extends PortSpec> in(Endpoint target) {
                return target.equals(lampState) ? Optional.of(new Spec(PortType.BOOL, true)) : Optional.empty();
            }
        }, (delivered, message) -> {
            roots.add(message.root());
            return MessageBus.Outcome.TAKEN;
        }, new MessageBus.Listener() {
            @Override
            public void loopCut(Wire cut, FlowMessage message) {
                throw new AssertionError("No loop here");
            }

            @Override
            public void failed(Wire failed, FlowMessage message, RuntimeException e) {
                throw new AssertionError("No delivery throws here", e);
            }
        // A frozen clock: the 2 ms drain budget must not cut a drain short on a busy machine (a parallel build).
        }, () -> 0L);
        boolean[] level = {false};
        // DeviceLink.publishState from neighborChanged: no extent of its own, only the bus's current root.
        Runnable neighborChanged = () -> {
            level[0] = !level[0];
            bus.emit(FlowMessage.Kind.VALUE, powered, "powered", new JsonPrimitive(level[0]), 0);
        };
        neighborChanged.run();
        // A TWIN apply's setBlock updates the input's neighbours within the command's extent.
        bus.withRoot(DeviceRuntime.commandRoot(CommandSource.TWIN, null), neighborChanged);
        bus.withRoot(DeviceRuntime.commandRoot(CommandSource.RAW, null), neighborChanged);
        bus.withRoot(DeviceRuntime.commandRoot(CommandSource.PLAYER, null), neighborChanged);
        bus.withRoot(Root.STARTUP, neighborChanged);
        bus.drain();
        check(roots.equals(List.of(Root.WORLD, Root.TWIN, Root.RAW, Root.PLAYER, Root.STARTUP)),
                "A neighbour update is WORLD on its own and inherits the command it happens in: " + roots);
    }

    private record Spec(PortType kind, boolean toggles) implements PortSpec {
    }

    /**
     * What a right-click on a MineQTT block does ({@link Interaction#decide}, design §7.4, M2 plan §4.2 M2-13), and what
     * the clicking hand counts as ({@link Interaction.Held#of}). The Configurator is a mod item, which a bare bootstrap
     * does not register, so its row is checked through the table alone.
     */
    private static void interaction() {
        DeviceType lamp = SmartLamp.TYPE;
        DeviceType output = TYPE;
        DeviceType noRawLink = DeviceType.builder(Identifier.fromNamespaceAndPath("mineqtt", "test_sensor")).model("Test Sensor")
                .unloaded(UnloadedPolicy.UNAVAILABLE).field(Field.bool("occupied", Access.READ)).build();
        check(lamp.use().map(Action::key).equals(Optional.of(Action.STATE)) && SmartPlug.TYPE.use().map(Action::key).equals(Optional.of(Action.STATE))
                && output.use().isEmpty() && RedstoneOutput.TYPE.use().isEmpty()
                && RedstoneInput.TYPE.use().isEmpty() && PresenceSensor.TYPE.use().isEmpty(),
                "Only the lamp and the plug have a natural use; the output, input and sensor open their screens");

        for (DeviceType type : Arrays.asList(lamp, output, noRawLink, null)) {
            for (Interaction.Held held : Interaction.Held.values()) {
                // Sneaking configures; only a block in hand is placed against the block, as vanilla lets one place against a chest,
                // and Room mode picks a room that holds the block (M4 plan D11).
                Interaction.Outcome expected = held == Interaction.Held.PLACEABLE ? Interaction.Outcome.PASS
                        : held == Interaction.Held.CONFIGURATOR_ROOM ? Interaction.Outcome.ROOM : Interaction.Outcome.SCREEN;
                Interaction.Outcome sneaking = Interaction.decide(true, held, type);
                check(sneaking == expected, "Sneak + right-click with " + held + " on " + type + ": " + sneaking + ", expected " + expected);
            }
            check(Interaction.decide(false, Interaction.Held.CONFIGURATOR, type) == Interaction.Outcome.SCREEN, "The Configurator opens the screen of " + type);
            // M2-16: in Link mode a plain click links (a block that is no device is refused where the click is carried out).
            check(Interaction.decide(false, Interaction.Held.CONFIGURATOR_LINK, type) == Interaction.Outcome.LINK, "The Configurator in Link mode links " + type);
            // M2-17: in Group and Copy-Paste mode a plain click is a step of that mode, refused alike for a block that is no device.
            check(Interaction.decide(false, Interaction.Held.CONFIGURATOR_GROUP, type) == Interaction.Outcome.GROUP, "The Configurator in Group mode groups " + type);
            check(Interaction.decide(false, Interaction.Held.CONFIGURATOR_COPY, type) == Interaction.Outcome.COPY, "The Configurator in Copy-Paste mode copies " + type);
            // M3-13 (D19): in Bind mode a plain click opens the Twin tab with the picker; a device without a twin, or a block
            // that is no device, is told so where the click is carried out.
            check(Interaction.decide(false, Interaction.Held.CONFIGURATOR_BIND, type) == Interaction.Outcome.BIND, "The Configurator in Bind mode binds " + type);
            // M4 plan D11: in Room mode the block is a corner, whatever it is; the device is not used.
            check(Interaction.decide(false, Interaction.Held.CONFIGURATOR_ROOM, type) == Interaction.Outcome.ROOM, "The Configurator in Room mode takes " + type + " as a corner");
        }
        check(Interaction.decide(false, Interaction.Held.CONFIGURATOR_BIND, SmartLamp.TYPE) == Interaction.Outcome.BIND
                        && Interaction.decide(true, Interaction.Held.CONFIGURATOR_BIND, SmartLamp.TYPE) == Interaction.Outcome.SCREEN,
                "In Bind mode a click on the lamp binds instead of toggling it, sneaking opens its screen");
        check(Arrays.stream(Interaction.Outcome.values()).filter(outcome -> outcome != Interaction.Outcome.PASS).allMatch(outcome ->
                        Arrays.stream(Interaction.Held.values()).anyMatch(held -> Interaction.decide(false, held, lamp) == outcome
                                || Interaction.decide(false, held, output) == outcome || Interaction.decide(false, held, null) == outcome)),
                "Every outcome but PASS is reached by a plain click with something in hand");
        check(Interaction.decide(false, Interaction.Held.OTHER, lamp) == Interaction.Outcome.USE
                && Interaction.decide(false, Interaction.Held.PLACEABLE, lamp) == Interaction.Outcome.USE, "A right-click toggles the lamp, also with a block in hand");
        check(Interaction.decide(false, Interaction.Held.OTHER, SmartPlug.TYPE) == Interaction.Outcome.USE && Interaction.decide(true, Interaction.Held.OTHER, SmartPlug.TYPE) == Interaction.Outcome.SCREEN
                && Interaction.decide(false, Interaction.Held.NAMED_PAPER, SmartPlug.TYPE) == Interaction.Outcome.PAPER,
                "A right-click toggles the plug, sneaking opens its screen, a renamed paper fills its channel");
        check(Interaction.decide(false, Interaction.Held.OTHER, WallSwitch.TYPE) == Interaction.Outcome.USE
                        && Interaction.decide(false, Interaction.Held.PLACEABLE, WallSwitch.TYPE) == Interaction.Outcome.USE
                        && Interaction.decide(true, Interaction.Held.OTHER, WallSwitch.TYPE) == Interaction.Outcome.SCREEN
                        && Interaction.decide(false, Interaction.Held.NAMED_PAPER, WallSwitch.TYPE) == Interaction.Outcome.PAPER,
                "A right-click presses the Wall Switch, sneaking opens its screen, a renamed paper fills its channel");
        // M2-22: the button a click hits, left and right as seen by a player facing the switch.
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            Direction playersRight = facing.getCounterClockWise();
            check(LightRemoteBlock.buttonAt(facing, 0.3 * playersRight.getStepX(), 0.3 * playersRight.getStepZ()) == WallSwitch.Button.RIGHT
                            && LightRemoteBlock.buttonAt(facing, -0.3 * playersRight.getStepX(), -0.3 * playersRight.getStepZ()) == WallSwitch.Button.LEFT,
                    "Facing a switch that faces " + facing + ", the player's right is its right button");
        }
        check(LightRemoteBlock.buttonAt(Direction.NORTH, -0.2, 0.4) == WallSwitch.Button.RIGHT && LightRemoteBlock.buttonAt(Direction.EAST, 0.4, -0.2) == WallSwitch.Button.RIGHT
                && LightRemoteBlock.buttonAt(Direction.SOUTH, -0.2, -0.4) == WallSwitch.Button.LEFT, "Facing north the right button is to the west, facing east to the north");
        check(Interaction.decide(false, Interaction.Held.OTHER, output) == Interaction.Outcome.SCREEN
                && Interaction.decide(false, Interaction.Held.PLACEABLE, output) == Interaction.Outcome.SCREEN, "A right-click opens the output's screen");
        check(Interaction.decide(false, Interaction.Held.OTHER, null) == Interaction.Outcome.SCREEN
                && Interaction.decide(false, Interaction.Held.PLACEABLE, null) == Interaction.Outcome.SCREEN, "A right-click opens the screen of a block that is no device");
        check(Interaction.decide(false, Interaction.Held.NAMED_PAPER, lamp) == Interaction.Outcome.PAPER
                && Interaction.decide(false, Interaction.Held.NAMED_PAPER, output) == Interaction.Outcome.PAPER,
                "A renamed paper fills the channel of a device with a Raw Link, ahead of its use");
        check(Interaction.decide(false, Interaction.Held.NAMED_PAPER, noRawLink) == Interaction.Outcome.SCREEN
                && Interaction.decide(false, Interaction.Held.NAMED_PAPER, null) == Interaction.Outcome.SCREEN,
                "A renamed paper opens the screen of a device without a Raw Link and of a block that is no device");
        check(Interaction.decide(false, Interaction.Held.NAMED_TAG, lamp) == Interaction.Outcome.RENAME
                && Interaction.decide(false, Interaction.Held.NAMED_TAG, output) == Interaction.Outcome.RENAME
                && Interaction.decide(false, Interaction.Held.NAMED_TAG, noRawLink) == Interaction.Outcome.RENAME,
                "A name tag renames any device, ahead of its use");
        check(Interaction.decide(false, Interaction.Held.NAMED_TAG, null) == Interaction.Outcome.SCREEN, "A block that is no device has no name to change");

        // An item stack needs its item's default components, which a server binds when it loads its data packs.
        CheckItems.bindCommonComponents(Items.STICK, Items.STONE, Items.LEVER, Items.PAPER, Items.NAME_TAG);
        check(Interaction.Held.of(ItemStack.EMPTY) == Interaction.Held.OTHER &&Interaction.Held.of(new ItemStack(Items.STICK)) == Interaction.Held.OTHER,
                "An empty hand and a stick count as nothing");
        check(Interaction.Held.of(new ItemStack(Items.STONE)) == Interaction.Held.PLACEABLE && Interaction.Held.of(new ItemStack(Items.LEVER)) == Interaction.Held.PLACEABLE,
                "Stone and a lever place blocks");
        check(Interaction.Held.of(named(Items.PAPER, "garage")) == Interaction.Held.NAMED_PAPER && Interaction.Held.of(new ItemStack(Items.PAPER)) == Interaction.Held.OTHER,
                "A renamed paper counts, a plain one does not");
        ItemStack tag = named(Items.NAME_TAG, "  Kitchen Lamp ");
        check(Interaction.Held.of(tag) == Interaction.Held.NAMED_TAG && Interaction.nameTag(tag).equals(Optional.of("Kitchen Lamp")),
                "A named name tag gives its name, stripped");
        check(Interaction.Held.of(new ItemStack(Items.NAME_TAG)) == Interaction.Held.OTHER && Interaction.nameTag(named(Items.NAME_TAG, "   ")).isEmpty()
                && Interaction.nameTag(named(Items.PAPER, "Kitchen Lamp")).isEmpty(), "A name tag without a name, or a blank one, renames nothing; a paper is no name tag");

        // M2-17: sneak + right-click into the air steps through every mode and back, each its own kind of Configurator in hand.
        List<ConfiguratorMode> modes = new ArrayList<>();
        ConfiguratorMode mode = ConfiguratorMode.CONFIGURE;
        do {
            modes.add(mode);
            mode = mode.next();
        } while (mode != ConfiguratorMode.CONFIGURE);
        check(modes.equals(List.of(ConfiguratorMode.CONFIGURE, ConfiguratorMode.LINK, ConfiguratorMode.GROUP, ConfiguratorMode.COPY_PASTE, ConfiguratorMode.BIND,
                        ConfiguratorMode.ROOM)),
                "The Configurator steps Configure, Link, Group, Copy-Paste, Bind, Room and back to Configure: " + modes);
        check(modes.stream().map(Interaction.Held::of).toList().equals(List.of(Interaction.Held.CONFIGURATOR, Interaction.Held.CONFIGURATOR_LINK,
                Interaction.Held.CONFIGURATOR_GROUP, Interaction.Held.CONFIGURATOR_COPY, Interaction.Held.CONFIGURATOR_BIND, Interaction.Held.CONFIGURATOR_ROOM)),
                "Each mode is its own kind of Configurator in hand");
        // M4 plan D11, R15: Room is appended, so every stored name and every ordinal sent before keeps its mode.
        check(ConfiguratorMode.ROOM.ordinal() == 5 && ConfiguratorMode.BIND.ordinal() == 4 && ConfiguratorMode.CONFIGURE.ordinal() == 0,
                "Room mode comes last; the modes before it keep their ordinals");
        check(ConfiguratorMode.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, new com.google.gson.JsonPrimitive("room")).result()
                .equals(Optional.of(ConfiguratorMode.ROOM)), "Room mode is stored as room");
        io.netty.buffer.ByteBuf modeBuf = io.netty.buffer.Unpooled.buffer();
        ConfiguratorMode.STREAM_CODEC.encode(modeBuf, ConfiguratorMode.ROOM);
        check(ConfiguratorMode.STREAM_CODEC.decode(modeBuf) == ConfiguratorMode.ROOM, "Room mode goes through the stream codec");
        check(ConfiguratorMode.BIND.getSerializedName().equals("bind") && ConfiguratorMode.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                new com.google.gson.JsonPrimitive("bind")).result().equals(Optional.of(ConfiguratorMode.BIND)), "Bind mode is stored as bind");
    }

    private static ItemStack named(Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    /**
     * The attach guard (M2 plan D7): a device whose attach throws is isolated and reported once, the devices after it still
     * attach, one whose chunk is not ready waits, and the failed one is not tried again until it is queued again.
     */
    private static void attachQueue() {
        AttachQueue<String> queue = new AttachQueue<>();
        List<String> attached = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Predicate<String> attach = device -> {
            if (device.equals("waiting")) return false;
            if (device.equals("broken")) throw new IllegalStateException("broken attach");
            attached.add(device);
            return true;
        };
        BiConsumer<String, RuntimeException> onFailure = (device, e) -> failed.add(device + ": " + e.getMessage());
        List.of("lamp", "broken", "waiting", "output").forEach(queue::add);
        queue.drain(attach, onFailure);
        check(failed.equals(List.of("broken: broken attach")), "The throwing attach is reported once: " + failed);
        check(attached.equals(List.of("lamp", "output")), "The devices before and after it still attach: " + attached);

        queue.drain(attach, onFailure);
        check(failed.size() == 1 && attached.size() == 2, "No retry until it is queued again, and nothing attaches twice");
        check(!queue.isEmpty(), "A device whose chunk is not ready waits in the queue");
        queue.remove("waiting");
        check(queue.isEmpty(), "Only the waiting device was left");

        queue.add("broken");
        queue.drain(attach, onFailure);
        check(failed.size() == 2 && queue.isEmpty(), "Queued again (chunk load, devices retry), it is tried again and reported again");

        List<String> order = new ArrayList<>();
        queue.add("first");
        queue.add("second");
        queue.drain(device -> {
            order.add(device);
            if (device.equals("first")) {
                queue.remove("second");
                queue.add("later");
            }
            return true;
        }, onFailure);
        check(order.equals(List.of("first")), "A device dequeued meanwhile is skipped, one queued meanwhile waits: " + order);
        check(!queue.isEmpty(), "The device queued during the drain attaches on the next one");

        check(!AttachQueue.isolate("record", device -> {
            throw new IllegalArgumentException("bad state");
        }, onFailure) && failed.getLast().equals("record: bad state"), "A power-on or prune that throws is reported, not thrown");
        check(AttachQueue.isolate("record", device -> true, onFailure), "Work that does not throw returns its result");
    }

    /**
     * A failed attach hands back what it used up (M2 plan D7): v1 data still awaits adoption, so the block entity saves
     * it as v1 data and the retry adopts it as trusted v1 data, and a placement is still one.
     */
    private static void attachHandBack() {
        DeviceLink v1 = new DeviceLink(TYPE);
        v1.load(TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, new CompoundTag()), false);
        LegacyNbtV1.Snapshot snapshot = v1.takePendingV1();
        check(snapshot != null && !v1.awaitingAdoption(), "The attach takes the v1 data");
        v1.adopt(snapshot, true);
        v1.assign(UUID.randomUUID(), "Lamp", "lamp");
        v1.handBack(snapshot, false);
        check(v1.awaitingAdoption() && v1.takePendingV1() == snapshot, "Handed back, the v1 data awaits adoption again, for the retry to adopt");
        v1.handBack(snapshot, false);
        boolean savedAsV1 = false;
        try {
            v1.save(TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING));
        } catch (IllegalStateException e) {
            savedAsV1 = true;
        }
        check(savedAsV1, "Until then the block entity saves v1 data, not a half-adopted compound");

        DeviceLink placed = linkWithCopiedData();
        placed.sanitizePlaced(PLACER, "Alex", raw -> Optional.empty());
        UUID minted = placed.id();
        placed.clearPlacedByPlayer();
        placed.handBack(null, true);
        check(placed.placedByPlayer() && minted.equals(placed.id()), "A placement stays one for the retry, with the id minted at the placement");
        check(!placed.awaitingAdoption(), "No v1 data is handed back where the attach took none");

        DeviceLink loaded = linkWithCopiedData();
        loaded.handBack(null, false);
        check(!loaded.placedByPlayer() && !loaded.awaitingAdoption(), "A device loaded with its chunk stays one");
    }

    private static DeviceRecord record(Boolean expose) {
        DeviceRecord record = new DeviceRecord(UUID.randomUUID(), TYPE.id(), GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO), "Lamp", "lamp");
        record.setExpose(expose);
        return record;
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
        data.store("raw", RawLink.CODEC, new RawLink("zigbee2mqtt/garage/set", RawLinkSpec.Direction.IN, "OPEN", null, 0, null,
                new TopicAcl.Grandfather("zigbee2mqtt/garage/set", OWNER), false, null, 0, null, RawLink.Edges.BOTH));
        CompoundTag tag = new CompoundTag();
        tag.put(DeviceLink.KEY, data);
        link.load(TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, tag), false);
        return link;
    }

    private static JsonObject state(int count, double temperature) {
        return JsonParser.parseString("{\"count\":" + count + ",\"temperature\":" + temperature + "}").getAsJsonObject();
    }

    private static JsonObject climate(double temperature, double humidity) {
        return JsonParser.parseString("{\"temperature\":" + temperature + ",\"humidity\":" + humidity + "}").getAsJsonObject();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

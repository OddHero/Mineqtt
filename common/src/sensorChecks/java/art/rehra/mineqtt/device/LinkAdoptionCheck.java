package art.rehra.mineqtt.device;

import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.blocks.entities.MotionSensorBlockEntity;
import art.rehra.mineqtt.blocks.entities.PublisherBlockEntity;
import art.rehra.mineqtt.blocks.entities.RedstoneEmitterBlockEntity;
import art.rehra.mineqtt.blocks.entities.RgbLedBlockEntity;
import art.rehra.mineqtt.device.ValueCodecs.LightState;
import art.rehra.mineqtt.device.types.PresenceSensor;
import art.rehra.mineqtt.device.types.PresenceSensor.Presence;
import art.rehra.mineqtt.device.types.RedstoneInput;
import art.rehra.mineqtt.device.types.RedstoneOutput;
import art.rehra.mineqtt.device.types.RedstoneOutput.Output;
import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.legacy.LegacyNbtV1;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.security.AclContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * The device side of the v1 migration (design §8.6, §9.4), for {@code legacy.LegacyNbtV1Test}: how a {@link DeviceLink}
 * adopts v1 data, which needs its package-private steps. Device data is read and written as the block entities do it
 * ({@link RgbLedBlockEntity#loadLamp}, {@link RedstoneEmitterBlockEntity#loadOutput}, {@link PublisherBlockEntity#loadInput},
 * {@link MotionSensorBlockEntity#loadSensor} and their save functions).
 */
public final class LinkAdoptionCheck {
    private static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
    private static final UUID ALEX = UUID.fromString("ec561538-f3fd-461d-aff5-086b22154bce");
    private static final TopicAcl ACL = new TopicAcl(new TopicAcl.Rules("mineqtt", "world_7f3a", "homeassistant", null,
            TopicAcl.defaultRawAllow("mineqtt", "world_7f3a"), false, false));

    private LinkAdoptionCheck() {
    }

    /**
     * A v1 lamp adopted on chunk load: its topic is grandfathered for its owner once the name is resolved, it echoes its
     * state, and the link saves as v2 data with its v1 owner name.
     */
    public static void adoptedOnChunkLoad(HolderLookup.Provider registries, CompoundTag v1Lamp, String topic) {
        DeviceLink link = new DeviceLink(SmartLamp.TYPE);
        RgbLedBlockEntity.loadLamp(link, input(registries, v1Lamp));
        check(link.awaitingAdoption() && link.id() == null && link.raw() == null, "v1 data waits for its attach");
        boolean refused = false;
        try {
            link.save(TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries));
        } catch (IllegalStateException e) {
            refused = true;
        }
        check(refused, "v1 data that is not adopted is never saved as v2 data");

        link.adopt(link.takePendingV1(), true);
        RawLink raw = link.raw();
        check(!link.awaitingAdoption() && "Steve".equals(link.ownerName()) && link.owner() == null, "The v1 owner name is kept until it is resolved");
        check(raw != null && raw.topic().equals(topic) && raw.direction() == RawLinkSpec.Direction.IN && raw.active(), "The v1 topic is the Raw Link input: " + raw);
        check(raw.stateEcho() && raw.echoTopic().equals(topic + "/state"), "A lamp echoes its state to the v1 state topic");
        check(raw.grandfathered() != null && raw.grandfathered().topic().equals(topic) && raw.grandfathered().owner() == null,
                "The topic is grandfathered, for an owner still to be resolved");
        check(raw.qos() == 0 && !raw.retain() && raw.on() == null && raw.off() == null, "No custom payloads on a lamp");
        check(ACL.checkPublish(AclContext.NOBODY, topic, raw.grandfathered()) == TopicAcl.Decision.ALLOWED_GRANDFATHERED,
                "The lamp keeps its topic while the owner name is looked up");

        check(link.resolveOwner(STEVE) && STEVE.equals(link.owner()), "The resolved owner is bound");
        check(new TopicAcl.Grandfather(topic, STEVE).equals(link.raw().grandfathered()), "The grandfather flag belongs to the resolved owner");
        check(new TopicAcl.Grandfather(topic + "/state", STEVE).equals(link.raw().echoGrandfather()), "The v1 state topic is grandfathered with it");
        check(!link.resolveOwner(ALEX) && STEVE.equals(link.owner()), "A device with an owner keeps it");

        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        link.save(output);
        CompoundTag data = output.buildResult().getCompound(DeviceLink.KEY).orElseThrow();
        check(data.getIntOr("v", 0) == DeviceLink.VERSION && data.getStringOr("type", "").equals("mineqtt:smart_lamp"), "Saved as v2 data");
        check(data.read("owner", UUIDUtil.CODEC).equals(Optional.of(STEVE)) && data.getStringOr("ownerName", "").equals("Steve"), "Owner and name are saved");
        CompoundTag rawTag = data.getCompound("raw").orElseThrow();
        check(rawTag.getStringOr("topic", "").equals(topic) && rawTag.getStringOr("dir", "").equals("in") && rawTag.getBooleanOr("stateEcho", false),
                "The Raw Link is saved with its echo: " + rawTag);
        CompoundTag grandfather = rawTag.getCompound("grandfathered").orElseThrow();
        check(grandfather.getStringOr("topic", "").equals(topic) && grandfather.read("owner", UUIDUtil.CODEC).equals(Optional.of(STEVE)),
                "The grandfather flag is saved with its owner: " + grandfather);

        CompoundTag saved = output.buildResult();
        DeviceLink reloaded = new DeviceLink(SmartLamp.TYPE);
        check(reloaded.load(input(registries, saved), false).isPresent() && !reloaded.awaitingAdoption(), "Saved v2 data is no v1 data any more");
        check(link.raw().equals(reloaded.raw()), "The Raw Link round-trips");

        // An owner name that was never resolved stays a grandfather flag without owner.
        DeviceLink pending = new DeviceLink(SmartLamp.TYPE);
        RgbLedBlockEntity.loadLamp(pending, input(registries, v1Lamp));
        pending.adopt(pending.takePendingV1(), true);
        TagValueOutput pendingOutput = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        pending.save(pendingOutput);
        DeviceLink pendingReloaded = new DeviceLink(SmartLamp.TYPE);
        pendingReloaded.load(input(registries, pendingOutput.buildResult()), false);
        check(pendingReloaded.raw().grandfathered() != null && pendingReloaded.raw().grandfathered().owner() == null,
                "A grandfather flag waiting for its owner round-trips");
    }

    /**
     * A v1 lamp without an owner name (placed before v1 recorded owners, or by no player) keeps its topic and its echo:
     * the grandfather flag holds while the lamp has no owner. A player who claims it gets the flag with it.
     */
    public static void adoptedWithoutOwner(HolderLookup.Provider registries, CompoundTag v1Lamp, String topic) {
        CompoundTag ownerless = v1Lamp.copy();
        ownerless.remove("ownerName");
        LampData lamp = new LampData();
        lamp.loadCustomOnly(input(registries, ownerless));
        lamp.link.adopt(lamp.link.takePendingV1(), true);
        RawLink raw = lamp.link.raw();
        check(lamp.link.owner() == null && lamp.link.ownerName().isEmpty(), "A v1 lamp without owner name has no owner");
        check(raw.grandfathered() != null && raw.grandfathered().owner() == null && raw.stateEcho(), "Its topic is grandfathered nonetheless: " + raw);
        check(ACL.checkPublish(AclContext.NOBODY, topic, raw.grandfathered()).allowed()
                && ACL.checkPublish(AclContext.NOBODY, raw.echoTopic(), raw.echoGrandfather()).allowed(), "It follows its topic and echoes its state");

        check(lamp.link.claim(lamp, ALEX, "Alex") && ALEX.equals(lamp.link.owner()) && lamp.link.ownerName().equals("Alex"), "A player claims it");
        check(new TopicAcl.Grandfather(topic, ALEX).equals(lamp.link.raw().grandfathered()), "The waiting grandfather flag is bound to the claimer");
        check(ACL.checkPublish(new TopicAcl.Subject(ALEX, false, false), topic, lamp.link.raw().grandfathered()).allowed()
                && !ACL.checkPublish(AclContext.NOBODY, topic, lamp.link.raw().grandfathered()).allowed(), "The topic is now theirs alone");
        check(!lamp.link.claim(lamp, STEVE, "Steve") && ALEX.equals(lamp.link.owner()), "An owned device cannot be claimed");

        LampData named = new LampData();
        named.loadCustomOnly(input(registries, v1Lamp));
        named.link.adopt(named.link.takePendingV1(), true);
        check(!named.link.claim(named, ALEX, "Alex") && named.link.owner() == null, "A v1 owner name still to resolve is not claimed by someone else");
    }

    /**
     * v1 data from an item is untrusted (§8.6). Placing the item merges its data into the fresh lamp's own save, which
     * already holds a {@code mineqtt} compound ({@code TypedEntityData.loadInto}); the lamp still recognises v1 data and
     * adopts it with the placement, without grandfather flag or echo, owned by the placer, its topic checked for them.
     */
    public static void placedFromItem(HolderLookup.Provider registries, CompoundTag v1Lamp, String topic, LightState v1Light) {
        LampData lamp = new LampData();
        check(TypedEntityData.of(BlockEntityTypes.DAYLIGHT_DETECTOR, v1Lamp).loadInto(lamp, registries), "The item data is applied");
        check(lamp.link.awaitingAdoption() && lamp.light.equals(v1Light), "The merged data is still v1 data, colour included: " + lamp.light);
        lamp.link.sanitizePlaced(ALEX, "Alex", raw -> Optional.of("not in acl.rawAllow"));
        RawLink raw = lamp.link.raw();
        check(!lamp.link.awaitingAdoption(), "Placed v1 data is adopted with the placement");
        check(ALEX.equals(lamp.link.owner()) && lamp.link.ownerName().equals("Alex"), "The placer owns it, not the v1 owner");
        check(raw != null && raw.topic().equals(topic) && raw.grandfathered() == null && !raw.stateEcho(), "No grandfather flag and no echo: " + raw);
        check(!raw.active() && "not in acl.rawAllow".equals(raw.disabledReason()), "The topic is checked for the placer");

        // A v2 lamp's item (pick-block of a placed lamp) carries its identity, so it stays v2 data.
        LampData v2 = new LampData();
        v2.link.assign(UUID.randomUUID(), "Hall", "hall");
        v2.light = v1Light;
        CompoundTag v2Item = v2.saveCustomOnly(registries);
        LampData copy = new LampData();
        check(TypedEntityData.of(BlockEntityTypes.DAYLIGHT_DETECTOR, v2Item).loadInto(copy, registries), "The v2 item data is applied");
        check(!copy.link.awaitingAdoption() && v2.link.id().equals(copy.link.id()) && copy.light.equals(v1Light),
                "v2 item data stays v2 data until the placement drops the copied id");
    }

    /** v1 data without a base item has no topic, so the device gets no Raw Link. */
    public static void withoutTopic(HolderLookup.Provider registries, CompoundTag v1Lamp) {
        DeviceLink link = new DeviceLink(SmartLamp.TYPE);
        RgbLedBlockEntity.loadLamp(link, input(registries, v1Lamp));
        link.adopt(link.takePendingV1(), true);
        check(link.raw() == null, "No topic, no Raw Link");
    }

    /**
     * A v1 emitter adopted on chunk load (§9.4): its topic is a grandfathered Raw Link input that takes retained values,
     * slots 2 and 3 are its custom payloads, and it keeps the level of its block. It has no state echo. Saved before the
     * adoption it is still v1 data, with v1's own key.
     */
    public static void emitterAdopted(HolderLookup.Provider registries, CompoundTag v1Emitter, String topic, String on, String off) {
        DeviceLink link = new DeviceLink(RedstoneOutput.TYPE);
        Output output = RedstoneEmitterBlockEntity.loadOutput(link, input(registries, v1Emitter), 9);
        check(link.awaitingAdoption() && output.equals(Output.ofLevel(9)) && output.lastLevel() == 9, "v1 data keeps the level of its block: " + output);
        TagValueOutput early = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        RedstoneEmitterBlockEntity.saveOutput(link, output, topic, early);
        CompoundTag earlySave = early.buildResult();
        check(earlySave.getCompound(DeviceLink.KEY).isEmpty() && earlySave.getStringOr("currentSubscribedTopic", "").equals(topic),
                "Saved before its adoption, an emitter writes v1's key and no v2 data: " + earlySave);

        link.adopt(link.takePendingV1(), true);
        RawLink raw = link.raw();
        check(raw != null && raw.topic().equals(topic) && raw.direction() == RawLinkSpec.Direction.IN && raw.active(), "The v1 topic is the Raw Link input: " + raw);
        check(on.equals(raw.on()) && off.equals(raw.off()), "Slots 2 and 3 are the custom payloads: " + raw);
        check(new TopicAcl.Grandfather(topic, null).equals(raw.grandfathered()) && !raw.stateEcho() && raw.qos() == 0 && !raw.retain(),
                "Grandfathered, no echo, v1's QoS 0: " + raw);
        check(RedstoneOutput.TYPE.rawLink().orElseThrow().acceptRetained(), "An output applies a retained raw value, as v1 emitters did");
        check(ValueCodecs.REDSTONE.command(on, raw.customPayloads()).orElseThrow().get("power").getAsInt() == 15
                && ValueCodecs.REDSTONE.command(off, raw.customPayloads()).orElseThrow().get("power").getAsInt() == 0, "The custom payloads switch it");
        check(ACL.checkPublish(AclContext.NOBODY, topic, raw.grandfathered()).allowed(), "The output keeps its v1 topic while the owner is looked up");
        // An edit of slot 2 or 3 alone (BaseMqttBlockEntity.followSlots) must not cost the topic its grandfather flag.
        RawLink edited = raw.withCustomPayloads(new ValueCodecs.CustomPayloads("UP", null));
        check(edited.topic().equals(raw.topic()) && raw.grandfathered().equals(edited.grandfathered()) && edited.qos() == raw.qos()
                && edited.retain() == raw.retain() && edited.active() && edited.direction() == raw.direction() && edited.stateEcho() == raw.stateEcho(),
                "New custom payloads keep the grandfathered link: " + edited);
        check("UP".equals(edited.on()) && edited.off() == null, "and replace only the payloads: " + edited);

        TagValueOutput saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        RedstoneEmitterBlockEntity.saveOutput(link, output, topic, saved);
        DeviceLink reloaded = new DeviceLink(RedstoneOutput.TYPE);
        Output reread = RedstoneEmitterBlockEntity.loadOutput(reloaded, input(registries, saved.buildResult()), 0);
        check(!reloaded.awaitingAdoption() && raw.equals(reloaded.raw()) && output.equals(reread), "The adopted output round-trips: " + reread);
    }

    /**
     * A v1 publisher in private mode, adopted on chunk load: its owner-prefixed v1 topic is a grandfathered Raw Link
     * output with v1's QoS and retain, and slots 2 and 3 are the payloads of its edges. v1 publishers kept no signal.
     */
    public static void publisherAdopted(HolderLookup.Provider registries, CompoundTag v1Publisher, String topic, String on) {
        DeviceLink link = new DeviceLink(RedstoneInput.TYPE);
        int signal = PublisherBlockEntity.loadInput(link, input(registries, v1Publisher), true);
        check(link.awaitingAdoption() && signal == RedstoneInput.MAX_SIGNAL, "A powered v1 publisher counts as full power until it reads its signal");
        TagValueOutput early = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        PublisherBlockEntity.saveInput(link, signal, early);
        check(early.buildResult().isEmpty(), "Saved before its adoption, a publisher writes nothing of its own");

        link.adopt(link.takePendingV1(), true);
        RawLink raw = link.raw();
        check(raw != null && raw.topic().equals(topic) && raw.direction() == RawLinkSpec.Direction.OUT && raw.active(), "The v1 topic is the Raw Link output: " + raw);
        check(new TopicAcl.Grandfather(topic, null).equals(raw.grandfathered()) && !raw.stateEcho() && raw.qos() == 0 && !raw.retain(),
                "Grandfathered, no echo, QoS 0 and not retained as in v1: " + raw);
        check(RedstoneInput.rawPayload(true, raw.customPayloads()).equals(on) && RedstoneInput.rawPayload(false, raw.customPayloads()).equals("false"),
                "The custom on payload, and v1's false without a custom off payload");
        check("Steve".equals(link.ownerName()), "The v1 owner name is kept until it is resolved");

        // The attach that adopts it gives it its id; without one, a publisher's data reads as v1 data again.
        link.assign(UUID.randomUUID(), "Bell", "bell");
        TagValueOutput saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        PublisherBlockEntity.saveInput(link, 11, saved);
        DeviceLink reloaded = new DeviceLink(RedstoneInput.TYPE);
        int reread = PublisherBlockEntity.loadInput(reloaded, input(registries, saved.buildResult()), false);
        check(!reloaded.awaitingAdoption() && raw.equals(reloaded.raw()) && reread == 11, "The adopted input round-trips with its signal");
    }

    /**
     * v1 emitter and publisher items: placing one merges its data into the fresh block entity's save, which has a
     * {@code mineqtt} compound without id. The emitter recognises v1 data by its own key, the publisher by the missing
     * id; both adopt it with the placement, untrusted. Items of v2 devices stay v2 data.
     */
    public static void redstonePlacedFromItem(HolderLookup.Provider registries, CompoundTag v1Emitter, CompoundTag v1Publisher) {
        OutputData emitter = new OutputData();
        check(TypedEntityData.of(BlockEntityTypes.DAYLIGHT_DETECTOR, v1Emitter).loadInto(emitter, registries), "The emitter item data is applied");
        check(emitter.link.awaitingAdoption(), "Merged v1 emitter data is still v1 data");
        emitter.link.sanitizePlaced(ALEX, "Alex", raw -> Optional.of("not in acl.rawAllow"));
        check(emitter.link.raw() != null && emitter.link.raw().grandfathered() == null && !emitter.link.raw().active(),
                "A placed v1 emitter's topic is checked for the placer, without grandfather flag: " + emitter.link.raw());

        InputData publisher = new InputData();
        check(TypedEntityData.of(BlockEntityTypes.DAYLIGHT_DETECTOR, v1Publisher).loadInto(publisher, registries), "The publisher item data is applied");
        check(publisher.link.awaitingAdoption(), "Merged v1 publisher data is still v1 data");
        publisher.link.sanitizePlaced(ALEX, "Alex", raw -> Optional.empty());
        check(publisher.link.raw() != null && publisher.link.raw().direction() == RawLinkSpec.Direction.OUT && publisher.link.raw().grandfathered() == null
                && ALEX.equals(publisher.link.owner()), "A placed v1 publisher's topic is an output owned by the placer: " + publisher.link.raw());

        OutputData v2Emitter = new OutputData();
        v2Emitter.link.assign(UUID.randomUUID(), "Gate", "gate");
        v2Emitter.output = new Output(5, 5, RedstoneOutput.Mode.LEVEL, RedstoneOutput.PowerOn.ON);
        OutputData emitterCopy = new OutputData();
        check(TypedEntityData.of(BlockEntityTypes.DAYLIGHT_DETECTOR, v2Emitter.saveCustomOnly(registries)).loadInto(emitterCopy, registries)
                && !emitterCopy.link.awaitingAdoption() && emitterCopy.output.equals(v2Emitter.output), "A v2 output's item stays v2 data");
        InputData v2Publisher = new InputData();
        v2Publisher.link.assign(UUID.randomUUID(), "Bell", "bell");
        InputData publisherCopy = new InputData();
        check(TypedEntityData.of(BlockEntityTypes.DAYLIGHT_DETECTOR, v2Publisher.saveCustomOnly(registries)).loadInto(publisherCopy, registries)
                && !publisherCopy.link.awaitingAdoption() && v2Publisher.link.id().equals(publisherCopy.link.id()), "A v2 input's item stays v2 data");
    }

    /**
     * A v1 motion sensor adopted on chunk load (§9.4): its topic is a grandfathered Raw Link output for its count, and
     * the spawn eggs v1's filter tab put into slots 2 and 3 are no custom payloads. It keeps counting mobs, as v1 did.
     * Saved before the adoption it is still v1 data, with v1's own keys.
     *
     * @param snapshotCustom the custom payloads a generic v1 snapshot reads from slots 2 and 3
     */
    public static void motionAdopted(HolderLookup.Provider registries, CompoundTag v1Motion, String topic, ValueCodecs.CustomPayloads snapshotCustom) {
        DeviceLink link = new DeviceLink(PresenceSensor.TYPE);
        Presence presence = MotionSensorBlockEntity.loadSensor(link, input(registries, v1Motion));
        check(link.awaitingAdoption() && presence.equals(new Presence(PresenceSensor.Detect.MOBS, PresenceSensor.DEFAULT_RANGE,
                PresenceSensor.DEFAULT_CLEAR_DELAY, 2, true, 0)), "v1 data counts mobs with its last count and motion: " + presence);
        TagValueOutput early = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        MotionSensorBlockEntity.saveSensor(link, presence, early);
        CompoundTag earlySave = early.buildResult();
        check(earlySave.getCompound(DeviceLink.KEY).isEmpty() && earlySave.getIntOr("LastCount", -1) == 2 && earlySave.getByteOr("MotionDetected", (byte) 0) == 1,
                "Saved before its adoption, a sensor writes v1's keys and no v2 data: " + earlySave);
        DeviceLink earlyReload = new DeviceLink(PresenceSensor.TYPE);
        check(MotionSensorBlockEntity.loadSensor(earlyReload, input(registries, earlySave)).equals(presence) && earlyReload.awaitingAdoption(),
                "and reads back as the same v1 data");

        LegacyNbtV1.Snapshot snapshot = LegacyNbtV1.read(input(registries, v1Motion));
        check(snapshot.custom().equals(snapshotCustom), "The generic snapshot reads the eggs of slots 2 and 3: " + snapshot.custom());
        link.adopt(link.takePendingV1(), true);
        RawLink raw = link.raw();
        check(raw != null && raw.topic().equals(topic) && raw.direction() == RawLinkSpec.Direction.OUT && raw.active(), "The v1 topic is the Raw Link output: " + raw);
        check(raw.on() == null && raw.off() == null, "Spawn eggs in slots 2 and 3 are filters, no custom payloads: " + raw);
        check(new TopicAcl.Grandfather(topic, null).equals(raw.grandfathered()) && !raw.stateEcho() && raw.qos() == 0 && !raw.retain(),
                "Grandfathered, no echo, QoS 0 and not retained as in v1: " + raw);
        check("Steve".equals(link.ownerName()), "The v1 owner name is kept until it is resolved");

        link.assign(UUID.randomUUID(), "Hall Sensor", "hall_sensor");
        Presence counted = presence.scanned(3, 400);
        TagValueOutput saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        MotionSensorBlockEntity.saveSensor(link, counted, saved);
        CompoundTag savedTag = saved.buildResult();
        check(!LegacyNbtV1.hasMotionKeys(input(registries, savedTag)), "Adopted data writes no v1 key any more: " + savedTag);
        DeviceLink reloaded = new DeviceLink(PresenceSensor.TYPE);
        Presence reread = MotionSensorBlockEntity.loadSensor(reloaded, input(registries, savedTag));
        check(!reloaded.awaitingAdoption() && raw.equals(reloaded.raw()) && counted.equals(reread), "The adopted sensor round-trips: " + reread);
    }

    /**
     * A v1 motion sensor item: placing it merges its data into the fresh block entity's save, which has a
     * {@code mineqtt} compound without id; the sensor recognises v1 data by its own keys and adopts it with the
     * placement, untrusted. A v2 sensor's item stays v2 data with its settings.
     */
    public static void motionPlacedFromItem(HolderLookup.Provider registries, CompoundTag v1Motion) {
        SensorData sensor = new SensorData();
        check(TypedEntityData.of(BlockEntityTypes.DAYLIGHT_DETECTOR, v1Motion).loadInto(sensor, registries), "The sensor item data is applied");
        check(sensor.link.awaitingAdoption() && sensor.presence.detect() == PresenceSensor.Detect.MOBS, "Merged v1 sensor data is still v1 data");
        sensor.link.sanitizePlaced(ALEX, "Alex", raw -> Optional.empty());
        RawLink raw = sensor.link.raw();
        check(raw != null && raw.direction() == RawLinkSpec.Direction.OUT && raw.grandfathered() == null && raw.on() == null && raw.off() == null
                && ALEX.equals(sensor.link.owner()), "A placed v1 sensor's topic is an output owned by the placer, without payloads: " + raw);

        SensorData v2 = new SensorData();
        v2.link.assign(UUID.randomUUID(), "Porch", "porch");
        v2.presence = new Presence(PresenceSensor.Detect.ALL, 3, 0, 1, true, 120);
        SensorData copy = new SensorData();
        check(TypedEntityData.of(BlockEntityTypes.DAYLIGHT_DETECTOR, v2.saveCustomOnly(registries)).loadInto(copy, registries)
                && !copy.link.awaitingAdoption() && copy.presence.equals(v2.presence), "A v2 sensor's item stays v2 data");
    }

    /**
     * The server ticker's first {@link BaseMqttBlockEntity#followSlots} after the adoption, on the adopted topic: spawn
     * eggs in a sensor's slots 2 and 3 stay filters, while papers in an output's slots 2 and 3 are its custom payloads.
     */
    public static void slotsFollowed(HolderLookup.Provider registries, CompoundTag v1Motion, CompoundTag v1Emitter, String on, String off) {
        SlotsData sensor = new SlotsData(PresenceSensor.TYPE, MotionSensorBlockEntity::loadSensor);
        sensor.loadCustomOnly(input(registries, v1Motion));
        sensor.link.adopt(sensor.link.takePendingV1(), true);
        RawLink adopted = sensor.link.raw();
        check(!sensor.follow() && adopted.equals(sensor.link.raw()) && adopted.on() == null && adopted.off() == null,
                "Spawn eggs in slots 2 and 3 do not become a sensor's custom payloads: " + sensor.link.raw());

        SlotsData emitter = new SlotsData(RedstoneOutput.TYPE, (link, input) -> RedstoneEmitterBlockEntity.loadOutput(link, input, 0));
        emitter.loadCustomOnly(input(registries, v1Emitter));
        emitter.link.adopt(emitter.link.takePendingV1(), true);
        emitter.link.setRaw(emitter.link.raw().withCustomPayloads(ValueCodecs.CustomPayloads.NONE));
        check(emitter.follow() && on.equals(emitter.link.raw().on()) && off.equals(emitter.link.raw().off()),
                "Papers in slots 2 and 3 become an output's custom payloads: " + emitter.link.raw());
    }

    private static ValueInput input(HolderLookup.Provider registries, CompoundTag tag) {
        return TagValueInput.create(ProblemReporter.DISCARDING, registries, tag);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /**
     * A device's block entity with its slots, without the block entity type only a mod loader registers, so
     * {@code followSlots} runs as the server ticker runs it.
     */
    private static final class SlotsData extends BaseMqttBlockEntity {
        private final DeviceLink link;
        private final BiConsumer<DeviceLink, ValueInput> load;

        SlotsData(DeviceType type, BiConsumer<DeviceLink, ValueInput> load) {
            super(BlockEntityTypes.DAYLIGHT_DETECTOR, BlockPos.ZERO, Blocks.DAYLIGHT_DETECTOR.defaultBlockState());
            this.link = new DeviceLink(type);
            this.load = load;
        }

        // On an unchanged topic followSlots needs no level.
        boolean follow() {
            return followSlots(null, link);
        }

        @Override
        protected void loadAdditional(ValueInput input) {
            super.loadAdditional(input);
            load.accept(link, input);
            // As the device block entities do: the adopted link is the slots' topic, not an edit.
            if (link.awaitingAdoption()) topic = getCombinedTopic();
        }

        @Override
        protected String getDefaultTopic() {
            return "";
        }

        @Override
        protected Component getDefaultName() {
            return Component.empty();
        }

        @Override
        public int getContainerSize() {
            return INVENTORY_SIZE + MotionSensorBlockEntity.NUM_FILTERS;
        }
    }

    /** A block entity whose data is the Redstone Output's, for the vanilla item-data path like {@link LampData}. */
    private static final class OutputData extends BlockEntity {
        private final DeviceLink link = new DeviceLink(RedstoneOutput.TYPE);
        private Output output = Output.DEFAULT;

        OutputData() {
            super(BlockEntityTypes.DAYLIGHT_DETECTOR, BlockPos.ZERO, Blocks.DAYLIGHT_DETECTOR.defaultBlockState());
        }

        @Override
        protected void saveAdditional(ValueOutput output) {
            RedstoneEmitterBlockEntity.saveOutput(link, this.output, "", output);
        }

        @Override
        protected void loadAdditional(ValueInput input) {
            output = RedstoneEmitterBlockEntity.loadOutput(link, input, 0);
        }
    }

    /** A block entity whose data is the Presence Sensor's, for the vanilla item-data path like {@link LampData}. */
    private static final class SensorData extends BlockEntity {
        private final DeviceLink link = new DeviceLink(PresenceSensor.TYPE);
        private Presence presence = Presence.DEFAULT;

        SensorData() {
            super(BlockEntityTypes.DAYLIGHT_DETECTOR, BlockPos.ZERO, Blocks.DAYLIGHT_DETECTOR.defaultBlockState());
        }

        @Override
        protected void saveAdditional(ValueOutput output) {
            MotionSensorBlockEntity.saveSensor(link, presence, output);
        }

        @Override
        protected void loadAdditional(ValueInput input) {
            presence = MotionSensorBlockEntity.loadSensor(link, input);
        }
    }

    /** A block entity whose data is the Redstone Input's, for the vanilla item-data path like {@link LampData}. */
    private static final class InputData extends BlockEntity {
        private final DeviceLink link = new DeviceLink(RedstoneInput.TYPE);
        private int signal;

        InputData() {
            super(BlockEntityTypes.DAYLIGHT_DETECTOR, BlockPos.ZERO, Blocks.DAYLIGHT_DETECTOR.defaultBlockState());
        }

        @Override
        protected void saveAdditional(ValueOutput output) {
            PublisherBlockEntity.saveInput(link, signal, output);
        }

        @Override
        protected void loadAdditional(ValueInput input) {
            signal = PublisherBlockEntity.loadInput(link, input, false);
        }
    }

    /**
     * A block entity whose data is the Smart Lamp's, so the vanilla item-data path ({@code TypedEntityData.loadInto})
     * runs without the lamp's block entity type, which only a mod loader registers.
     */
    private static final class LampData extends BlockEntity {
        private final DeviceLink link = new DeviceLink(SmartLamp.TYPE);
        private LightState light = LightState.DEFAULT;

        LampData() {
            super(BlockEntityTypes.DAYLIGHT_DETECTOR, BlockPos.ZERO, Blocks.DAYLIGHT_DETECTOR.defaultBlockState());
        }

        @Override
        protected void saveAdditional(ValueOutput output) {
            RgbLedBlockEntity.saveLamp(link, light, output);
        }

        @Override
        protected void loadAdditional(ValueInput input) {
            light = RgbLedBlockEntity.loadLamp(link, input);
        }
    }
}

package art.rehra.mineqtt.device;

import art.rehra.mineqtt.compat.NbtTags;
import art.rehra.mineqtt.compat.BlockEntityStorage;
import art.rehra.mineqtt.CheckItems;
import art.rehra.mineqtt.blocks.entities.BlockEmitterBlockEntity;
import art.rehra.mineqtt.blocks.entities.BlockSensorBlockEntity;
import art.rehra.mineqtt.blocks.entities.DeviceBlockEntity;
import art.rehra.mineqtt.blocks.entities.LightRemoteBlockEntity;
import art.rehra.mineqtt.blocks.entities.MotionSensorBlockEntity;
import art.rehra.mineqtt.blocks.entities.PublisherBlockEntity;
import art.rehra.mineqtt.blocks.entities.RedstoneEmitterBlockEntity;
import art.rehra.mineqtt.blocks.entities.RgbLedBlockEntity;
import art.rehra.mineqtt.blocks.entities.SmartPlugBlockEntity;
import art.rehra.mineqtt.blocks.entities.TemperatureSensorBlockEntity;
import art.rehra.mineqtt.device.ValueCodecs.LightState;
import art.rehra.mineqtt.device.types.BlockObserver;
import art.rehra.mineqtt.device.types.BlockPlacer;
import art.rehra.mineqtt.device.types.ClimateSensor;
import art.rehra.mineqtt.device.types.ClimateSensor.Climate;
import art.rehra.mineqtt.device.types.PresenceSensor;
import art.rehra.mineqtt.device.types.PresenceSensor.Presence;
import art.rehra.mineqtt.device.types.RedstoneInput;
import art.rehra.mineqtt.device.types.RedstoneOutput;
import art.rehra.mineqtt.device.types.RedstoneOutput.Output;
import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.device.types.SmartPlug;
import art.rehra.mineqtt.device.types.SmartPlug.Plug;
import art.rehra.mineqtt.device.types.WallSwitch;
import art.rehra.mineqtt.ha.Ha;
import art.rehra.mineqtt.legacy.LegacyNbtV1;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.security.AclContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The device side of the v1 migration (design §8.6, §9.4), for {@code legacy.LegacyNbtV1Test}: how a {@link DeviceLink}
 * adopts v1 data, which needs its package-private steps. Device data is read and written as the block entities do it
 * ({@link RgbLedBlockEntity#loadLamp}, {@link RedstoneEmitterBlockEntity#loadOutput}, {@link PublisherBlockEntity#loadInput},
 * {@link MotionSensorBlockEntity#loadSensor}, {@link SmartPlugBlockEntity#loadPlug} and their save functions).
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
        TagValueOutput early = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        RgbLedBlockEntity.saveLamp(link, LegacyNbtV1.lampState(input(registries, v1Lamp)), early);
        CompoundTag earlySave = early.buildResult();
        for (String key : List.of("topic", "lastTabId", "ownerName", "privateMode", "Items", "currentSubscribedTopic", "TargetRed", "Brightness", "Lit")) {
            check(Objects.equals(earlySave.get(key), v1Lamp.get(key)), "Saved before its adoption, a lamp writes its v1 " + key + " back as it was read: " + earlySave);
        }

        link.adopt(link.takePendingV1(), true);
        RawLink raw = link.raw();
        check(!link.awaitingAdoption() && "Steve".equals(link.ownerName()) && link.owner() == null, "The v1 owner name is kept until it is resolved");
        check(raw != null && raw.topic().equals(topic) && raw.direction() == RawLinkSpec.Direction.IN && raw.active(), "The v1 topic is the Raw Link input: " + raw);
        check(raw.stateEcho() && raw.echoTopic().equals(topic + "/state"), "A lamp echoes its state to the v1 state topic");
        check(raw.grandfathered() != null && raw.grandfathered().topic().equals(topic) && raw.grandfathered().owner() == null,
                "The topic is grandfathered, for an owner still to be resolved");
        check(raw.qos() == 0 && raw.retain() == null && raw.on() == null && raw.off() == null, "No custom payloads on a lamp");
        check(ACL.checkPublish(AclContext.NOBODY, topic, raw.grandfathered()) == TopicAcl.Decision.ALLOWED_GRANDFATHERED,
                "The lamp keeps its topic while the owner name is looked up");

        check(link.resolveOwner(STEVE) && STEVE.equals(link.owner()), "The resolved owner is bound");
        check(new TopicAcl.Grandfather(topic, STEVE).equals(link.raw().grandfathered()), "The grandfather flag belongs to the resolved owner");
        check(new TopicAcl.Grandfather(topic + "/state", STEVE).equals(link.raw().echoGrandfather()), "The v1 state topic is grandfathered with it");
        check(!link.resolveOwner(ALEX) && STEVE.equals(link.owner()), "A device with an owner keeps it");

        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        link.save(output);
        CompoundTag data = NbtTags.getCompound(output.buildResult(), DeviceLink.KEY).orElseThrow();
        check(NbtTags.getIntOr(data, "v", 0) == DeviceLink.VERSION && NbtTags.getStringOr(data, "type", "").equals("mineqtt:smart_lamp"), "Saved as v2 data");
        check(NbtTags.read(data, "owner", UUIDUtil.CODEC).equals(Optional.of(STEVE)) && NbtTags.getStringOr(data, "ownerName", "").equals("Steve"), "Owner and name are saved");
        CompoundTag rawTag = NbtTags.getCompound(data, "raw").orElseThrow();
        check(NbtTags.getStringOr(rawTag, "topic", "").equals(topic) && NbtTags.getStringOr(rawTag, "dir", "").equals("in") && NbtTags.getBooleanOr(rawTag, "stateEcho", false),
                "The Raw Link is saved with its echo: " + rawTag);
        CompoundTag grandfather = NbtTags.getCompound(rawTag, "grandfathered").orElseThrow();
        check(NbtTags.getStringOr(grandfather, "topic", "").equals(topic) && NbtTags.read(grandfather, "owner", UUIDUtil.CODEC).equals(Optional.of(STEVE)),
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
        BlockEntityStorage.loadCustomOnly(lamp, input(registries, ownerless));
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
        BlockEntityStorage.loadCustomOnly(named, input(registries, v1Lamp));
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
        check(CheckItems.loadItemData(BlockEntityTypes.DAYLIGHT_DETECTOR, v1Lamp, lamp, registries), "The item data is applied");
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
        check(CheckItems.loadItemData(BlockEntityTypes.DAYLIGHT_DETECTOR, v2Item, copy, registries), "The v2 item data is applied");
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
     * adoption it is still v1 data, written back exactly as it was read.
     */
    public static void emitterAdopted(HolderLookup.Provider registries, CompoundTag v1Emitter, String topic, String on, String off) {
        DeviceLink link = new DeviceLink(RedstoneOutput.TYPE);
        Output output = RedstoneEmitterBlockEntity.loadOutput(link, input(registries, v1Emitter), 9);
        check(link.awaitingAdoption() && output.equals(Output.ofLevel(9)) && output.lastLevel() == 9, "v1 data keeps the level of its block: " + output);
        check(link.loadedVersion() == DeviceLink.VERSION_V1 && sameItems(link.legacyItems(), v1Emitter, registries),
                "v1 data keeps its slots as legacy items: " + link.legacyItems());
        TagValueOutput early = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        RedstoneEmitterBlockEntity.saveOutput(link, output, early);
        CompoundTag earlySave = early.buildResult();
        check(earlySave.equals(v1Emitter), "Saved before its adoption, an emitter writes its v1 data back as it was read: " + earlySave);

        link.adopt(link.takePendingV1(), true);
        RawLink raw = link.raw();
        check(raw != null && raw.topic().equals(topic) && raw.direction() == RawLinkSpec.Direction.IN && raw.active(), "The v1 topic is the Raw Link input: " + raw);
        check(on.equals(raw.on()) && off.equals(raw.off()), "Slots 2 and 3 are the custom payloads: " + raw);
        check(new TopicAcl.Grandfather(topic, null).equals(raw.grandfathered()) && !raw.stateEcho() && raw.qos() == 0 && raw.retain() == null,
                "Grandfathered, no echo, v1's QoS 0: " + raw);
        check(RedstoneOutput.TYPE.rawLink().orElseThrow().acceptRetained(), "An output applies a retained raw value, as v1 emitters did");
        check(ValueCodecs.REDSTONE.command(on, raw.customPayloads()).orElseThrow().get("power").getAsInt() == 15
                && ValueCodecs.REDSTONE.command(off, raw.customPayloads()).orElseThrow().get("power").getAsInt() == 0, "The custom payloads switch it");
        check(ACL.checkPublish(AclContext.NOBODY, topic, raw.grandfathered()).allowed(), "The output keeps its v1 topic while the owner is looked up");
        // An edit of slot 2 or 3 alone (BaseMqttBlockEntity.followSlots) must not cost the topic its grandfather flag.
        RawLink edited = raw.withCustomPayloads(new ValueCodecs.CustomPayloads("UP", null));
        check(edited.topic().equals(raw.topic()) && raw.grandfathered().equals(edited.grandfathered()) && edited.qos() == raw.qos()
                && Objects.equals(edited.retain(), raw.retain()) && edited.active() && edited.direction() == raw.direction() && edited.stateEcho() == raw.stateEcho(),
                "New custom payloads keep the grandfathered link: " + edited);
        check("UP".equals(edited.on()) && edited.off() == null, "and replace only the payloads: " + edited);

        TagValueOutput saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        RedstoneEmitterBlockEntity.saveOutput(link, output, saved);
        CompoundTag savedTag = saved.buildResult();
        check(NbtTags.keySet(savedTag).equals(Set.of(DeviceLink.KEY)) && NbtTags.getCompound(savedTag, DeviceLink.KEY).orElseThrow().contains(DeviceLink.LEGACY_ITEMS),
                "Adopted, its slots are legacy items in the compound and no v1 key is left: " + savedTag);
        DeviceLink reloaded = new DeviceLink(RedstoneOutput.TYPE);
        Output reread = RedstoneEmitterBlockEntity.loadOutput(reloaded, input(registries, savedTag), 0);
        check(!reloaded.awaitingAdoption() && raw.equals(reloaded.raw()) && output.equals(reread), "The adopted output round-trips: " + reread);
        check(sameItems(reloaded.legacyItems(), v1Emitter, registries), "and so do its legacy items: " + reloaded.legacyItems());
    }

    /**
     * A v1 smart plug adopted on chunk load (M2-18): like an emitter, its topic is a grandfathered Raw Link input that
     * takes retained values, slots 2 and 3 are its custom payloads, and it is on when its block is powered, as v1 kept no
     * other state. A plug saved by the M2 legacy layer after F11 carries its owner's UUID, which is its owner at once, so
     * its topic joins that owner's literal set at the first attach; a v1 plug's name is resolved later. Untrusted data
     * keeps only the owner's name: its UUID binds no owner. Saved before the adoption it is still v1 data, written back
     * exactly as it was read, the owner UUID included.
     *
     * @param ownerUuid the {@code ownerUuid} key of the data, null for data v1 wrote
     */
    public static void plugAdopted(HolderLookup.Provider registries, CompoundTag v1Plug, String topic, @Nullable String on, @Nullable String off,
                                   @Nullable UUID ownerUuid) {
        DeviceLink link = new DeviceLink(SmartPlug.TYPE);
        Plug plug = SmartPlugBlockEntity.loadPlug(link, input(registries, v1Plug), 15);
        check(link.awaitingAdoption() && plug.equals(Plug.ofPower(15)) && plug.on(), "v1 data is on while its block is powered: " + plug);
        check(SmartPlugBlockEntity.loadPlug(new DeviceLink(SmartPlug.TYPE), input(registries, v1Plug), 0).equals(Plug.DEFAULT), "and off while it is not");
        check(link.loadedVersion() == DeviceLink.VERSION_V1 && sameItems(link.legacyItems(), v1Plug, registries),
                "v1 data keeps its slots as legacy items: " + link.legacyItems());
        check(Objects.equals(link.pendingV1().ownerUuid(), ownerUuid), "The snapshot reads the owner UUID: " + link.pendingV1());
        TagValueOutput early = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        SmartPlugBlockEntity.savePlug(link, plug, early);
        CompoundTag earlySave = early.buildResult();
        check(earlySave.equals(v1Plug), "Saved before its adoption, a plug writes its v1 data back as it was read: " + earlySave);

        link.adopt(link.takePendingV1(), true);
        RawLink raw = link.raw();
        check(raw != null && raw.topic().equals(topic) && raw.direction() == RawLinkSpec.Direction.IN && raw.active(), "The v1 topic is the Raw Link input: " + raw);
        check(Objects.equals(on, raw.on()) && Objects.equals(off, raw.off()), "Slots 2 and 3 are the custom payloads: " + raw);
        check(new TopicAcl.Grandfather(topic, ownerUuid).equals(raw.grandfathered()) && !raw.stateEcho() && raw.qos() == 0 && raw.retain() == null,
                "Grandfathered for its owner, no echo of its state to the topic, v1's QoS 0: " + raw);
        check("Steve".equals(link.ownerName()) && Objects.equals(link.owner(), ownerUuid), "The owner: its name, and its UUID when the data had one");
        if (ownerUuid != null) {
            DeviceLink untrusted = new DeviceLink(SmartPlug.TYPE);
            SmartPlugBlockEntity.loadPlug(untrusted, input(registries, v1Plug), 0);
            untrusted.adopt(untrusted.takePendingV1(), false);
            check(untrusted.owner() == null && "Steve".equals(untrusted.ownerName()) && untrusted.raw() != null && untrusted.raw().grandfathered() == null,
                    "Adopted untrusted, the owner UUID in the data binds no owner and grandfathers nothing: " + untrusted.raw());
        }
        RawLinkSpec spec = SmartPlug.TYPE.rawLink().orElseThrow();
        check(spec.acceptRetained() && spec.grammar() == ValueCodecs.SWITCH && !spec.echoes(), "A plug applies a retained raw value with v1's plug grammar, as v1 plugs did");
        if (on != null) {
            check(spec.grammar().command(on, raw.customPayloads()).orElseThrow().get("state").getAsString().equals("ON")
                    && spec.grammar().command(off, raw.customPayloads()).orElseThrow().get("state").getAsString().equals("OFF"), "The custom payloads switch it");
        }
        TopicAcl.Subject owner = ownerUuid == null ? AclContext.NOBODY : new TopicAcl.Subject(ownerUuid, false, false);
        check(ACL.checkPublish(owner, topic, raw.grandfathered()) == TopicAcl.Decision.ALLOWED_GRANDFATHERED, "The plug keeps its v1 topic");

        DeviceRegistry registry = new DeviceRegistry("world_7f3a", UUID.randomUUID());
        LegacyAdoption.Settlement settled = LegacyAdoption.settleGrandfathered(registry, raw, true);
        if (ownerUuid == null) {
            check(settled == null, "A v1 plug's topic waits for its owner's name to resolve before it joins a literal set");
        } else {
            check(settled != null && topic.equals(settled.added()) && registry.ownerLiterals(ownerUuid).contains(topic) && settled.link().resolved(),
                    "A plug with its owner's UUID adds its topic to the owner's literal set at the first attach");
        }

        TagValueOutput saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        SmartPlugBlockEntity.savePlug(link, plug, saved);
        CompoundTag savedTag = saved.buildResult();
        CompoundTag compound = NbtTags.getCompound(savedTag, DeviceLink.KEY).orElseThrow();
        check(NbtTags.keySet(savedTag).equals(Set.of(DeviceLink.KEY)) && compound.contains(DeviceLink.LEGACY_ITEMS)
                        && NbtTags.getStringOr(compound, "type", "").equals("mineqtt:smart_plug"),
                "Adopted, it is v2 data with its slots as legacy items and no v1 key is left: " + savedTag);
        check(NbtTags.getIntOr(NbtTags.getCompound(compound, "state").orElseThrow(), "power", 0) == SmartPlug.ON_POWER, "Its power is saved (v1 kept none): " + compound);
        DeviceLink reloaded = new DeviceLink(SmartPlug.TYPE);
        Plug reread = SmartPlugBlockEntity.loadPlug(reloaded, input(registries, savedTag), 0);
        check(!reloaded.awaitingAdoption() && raw.equals(reloaded.raw()) && plug.equals(reread) && Objects.equals(reloaded.owner(), ownerUuid),
                "The adopted plug round-trips, on although its block is not powered yet: " + reread);
        check(sameItems(reloaded.legacyItems(), v1Plug, registries), "and so do its legacy items: " + reloaded.legacyItems());
    }

    /**
     * A plug's item: v1 data merged into the fresh plug's save is still v1 data, by its own key, and is adopted with the
     * placement, untrusted, owned by the placer; the owner UUID in the item counts for nothing. A v2 plug's item stays v2
     * data, and a placed copy is off and keeps its power-on behaviour (M2 plan D14).
     */
    public static void plugPlacedFromItem(HolderLookup.Provider registries, CompoundTag v1Plug) {
        PlugData placed = new PlugData();
        check(CheckItems.loadItemData(BlockEntityTypes.DAYLIGHT_DETECTOR, v1Plug, placed, registries), "The plug item data is applied");
        check(placed.link.awaitingAdoption(), "Merged v1 plug data is still v1 data");
        placed.link.sanitizePlaced(ALEX, "Alex", raw -> Optional.of("not in acl.rawAllow"));
        check(ALEX.equals(placed.link.owner()) && placed.link.ownerName().equals("Alex") && placed.link.raw() != null
                        && placed.link.raw().grandfathered() == null && !placed.link.raw().active(),
                "A placed v1 plug is the placer's, its topic checked for them without grandfather flag: " + placed.link.raw());

        PlugData v2 = new PlugData();
        v2.link.assign(UUID.randomUUID(), "Kettle", "kettle");
        v2.plug = new Plug(true, RedstoneOutput.PowerOn.ON);
        PlugData copy = placedCopy(registries, v2, PlugData::new);
        check(!copy.link.awaitingAdoption() && copy.plug.equals(new Plug(false, RedstoneOutput.PowerOn.ON)),
                "A placed plug is off and keeps its power-on behaviour: " + copy.plug);
    }

    /**
     * A v1 temperature sensor, or a v1 humidity sensor converted into a Climate Sensor (M2-19), adopted on chunk load: its
     * topic is a grandfathered Raw Link output without custom payloads, not retained, which carries the temperature, or
     * for a humidity sensor the humidity, as v1 published it; its interval and last reading come along. Its owner UUID,
     * which a sensor saved by the M2 legacy layer has, binds the owner at once, and the name an anvil gave it becomes the
     * device's. Saved before its adoption it is still the same v1 data.
     */
    public static void climateAdopted(HolderLookup.Provider registries, CompoundTag v1Sensor, String topic, Climate expected, @Nullable UUID ownerUuid,
                                      String name) {
        DeviceLink link = new DeviceLink(ClimateSensor.TYPE);
        Climate climate = TemperatureSensorBlockEntity.loadSensor(link, input(registries, v1Sensor));
        check(link.awaitingAdoption() && link.loadedVersion() == DeviceLink.VERSION_V1 && climate.equals(expected), "v1 sensor data: " + climate);
        check(sameItems(link.legacyItems(), v1Sensor, registries), "v1 data keeps its slots as legacy items: " + link.legacyItems());
        check(link.pendingV1().name().equals(name) && Objects.equals(link.pendingV1().ownerUuid(), ownerUuid), "The snapshot reads the name and owner: " + link.pendingV1());
        TagValueOutput early = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        TemperatureSensorBlockEntity.saveSensor(link, climate, early);
        DeviceLink again = new DeviceLink(ClimateSensor.TYPE);
        Climate reread = TemperatureSensorBlockEntity.loadSensor(again, input(registries, early.buildResult()));
        check(again.awaitingAdoption() && reread.equals(climate) && again.pendingV1().equals(link.pendingV1()) && sameItems(again.legacyItems(), v1Sensor, registries),
                "Saved before its adoption, it is still the same v1 sensor: " + early.buildResult());

        link.adopt(link.takePendingV1(), true);
        RawLink raw = link.raw();
        RawLinkSpec spec = ClimateSensor.TYPE.rawLink().orElseThrow();
        check(raw != null && raw.topic().equals(topic) && raw.direction() == RawLinkSpec.Direction.OUT && raw.active() && raw.on() == null && raw.off() == null,
                "The v1 topic is the Raw Link output, without custom payloads: " + raw);
        check(new TopicAcl.Grandfather(topic, ownerUuid).equals(raw.grandfathered()) && raw.retain() == null && !raw.effectiveRetain(spec) && raw.qos() == 0,
                "Grandfathered for its owner, not retained, v1's QoS 0: " + raw);
        check("Steve".equals(link.ownerName()) && Objects.equals(link.owner(), ownerUuid) && link.name().equals(name),
                "The owner, and the anvil name as the device's name: " + link.name());

        TagValueOutput saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        TemperatureSensorBlockEntity.saveSensor(link, climate, saved);
        CompoundTag savedTag = saved.buildResult();
        CompoundTag compound = NbtTags.getCompound(savedTag, DeviceLink.KEY).orElseThrow();
        check(NbtTags.keySet(savedTag).equals(Set.of(DeviceLink.KEY)) && NbtTags.getStringOr(compound, "type", "").equals("mineqtt:climate_sensor")
                        && NbtTags.getStringOr(NbtTags.getCompound(compound, "state").orElseThrow(), "raw_value", "temperature").equals(expected.rawValue().getSerializedName()),
                "Adopted, it is v2 data with its Raw Link reading and no v1 key is left: " + savedTag);
        DeviceLink reloaded = new DeviceLink(ClimateSensor.TYPE);
        Climate adopted = TemperatureSensorBlockEntity.loadSensor(reloaded, input(registries, savedTag));
        check(!reloaded.awaitingAdoption() && raw.equals(reloaded.raw()) && adopted.equals(climate) && reloaded.name().equals(name)
                && sameItems(reloaded.legacyItems(), v1Sensor, registries), "The adopted sensor round-trips: " + adopted);
    }

    /**
     * A v1 Block Sensor, adopted on chunk load (M4 plan D12): its topic is a grandfathered Raw Link output, not retained
     * as v1 sent it, without custom payloads (slots 2 and 3 never were payloads), and its data mode is the Block
     * Observer's {@code payload}. Saved before its adoption it is still the same v1 data; adopted, it writes no v1 key.
     */
    public static void sensorAdopted(HolderLookup.Provider registries, CompoundTag v1Sensor, String topic, BlockObserver.Payload payload) {
        DeviceLink link = new DeviceLink(BlockObserver.TYPE);
        BlockObserver.Observation observation = BlockSensorBlockEntity.loadObserver(link, input(registries, v1Sensor));
        check(link.awaitingAdoption() && link.loadedVersion() == DeviceLink.VERSION_V1 && observation.equals(BlockObserver.Observation.ofV1(payload)),
                "v1 sensor data: its mode is the payload: " + observation);
        check(sameItems(link.legacyItems(), v1Sensor, registries), "v1 data keeps its slots as legacy items: " + link.legacyItems());
        TagValueOutput early = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        BlockSensorBlockEntity.saveObserver(link, observation, early);
        check(early.buildResult().equals(v1Sensor), "Saved before its adoption, a sensor writes its v1 data back as it was read: " + early.buildResult());
        DeviceLink again = new DeviceLink(BlockObserver.TYPE);
        check(BlockSensorBlockEntity.loadObserver(again, input(registries, early.buildResult())).equals(observation) && again.awaitingAdoption(),
                "and reads back as the same v1 data");

        link.adopt(link.takePendingV1(), true);
        RawLink raw = link.raw();
        RawLinkSpec spec = BlockObserver.TYPE.rawLink().orElseThrow();
        check(raw != null && raw.topic().equals(topic) && raw.direction() == RawLinkSpec.Direction.OUT && raw.active() && raw.on() == null && raw.off() == null,
                "The v1 topic is the Raw Link output, without custom payloads: " + raw);
        check(new TopicAcl.Grandfather(topic, null).equals(raw.grandfathered()) && !raw.effectiveRetain(spec) && !spec.retainByDefault() && raw.qos() == 0,
                "Grandfathered, not retained and QoS 0, as v1 sent it: " + raw);
        check("Steve".equals(link.ownerName()), "The v1 owner name is kept until it is resolved");

        link.assign(UUID.randomUUID(), "Gate Observer", "gate_observer");
        TagValueOutput saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        BlockSensorBlockEntity.saveObserver(link, observation, saved);
        CompoundTag savedTag = saved.buildResult();
        check(!LegacyNbtV1.hasSensorKeys(input(registries, savedTag)) && NbtTags.keySet(savedTag).equals(Set.of(DeviceLink.KEY))
                        && NbtTags.getStringOr(NbtTags.getCompound(NbtTags.getCompound(savedTag, DeviceLink.KEY).orElseThrow(), "state").orElseThrow(), "payload", BlockObserver.Payload.FULL.getSerializedName())
                        .equals(payload.getSerializedName()),
                "Adopted, it is v2 data with its payload and no v1 key is left: " + savedTag);
        DeviceLink reloaded = new DeviceLink(BlockObserver.TYPE);
        BlockObserver.Observation reread = BlockSensorBlockEntity.loadObserver(reloaded, input(registries, savedTag));
        check(!reloaded.awaitingAdoption() && raw.equals(reloaded.raw()) && reread.equals(observation), "The adopted observer round-trips: " + reread);
    }

    /**
     * A v1 Block Emitter, adopted on chunk load (M4 plan D12): its listen topic is a grandfathered Raw Link input with the
     * Block Placer's grammar that never applies a retained message, without custom payloads, and it is set to no block:
     * v1 took its blocks from each message. Saved before its adoption it keeps v1's key; adopted, it writes none.
     */
    public static void placerAdopted(HolderLookup.Provider registries, CompoundTag v1Emitter, String topic) {
        DeviceLink link = new DeviceLink(BlockPlacer.TYPE);
        BlockEmitterBlockEntity.Loaded loaded = BlockEmitterBlockEntity.loadPlacer(link, input(registries, v1Emitter));
        check(link.awaitingAdoption() && link.loadedVersion() == DeviceLink.VERSION_V1 && loaded.settings().equals(BlockPlacer.Settings.DEFAULT)
                && loaded.placed() == null, "v1 emitter data: no block set, nothing placed");
        check(sameItems(link.legacyItems(), v1Emitter, registries), "v1 data keeps its slots as legacy items: " + link.legacyItems());
        TagValueOutput early = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        BlockEmitterBlockEntity.savePlacer(link, loaded.settings(), loaded.placed(), early);
        check(LegacyNbtV1.hasSubscribedKeys(input(registries, early.buildResult())), "Saved before its adoption, it keeps v1's key: " + early.buildResult());
        DeviceLink again = new DeviceLink(BlockPlacer.TYPE);
        BlockEmitterBlockEntity.loadPlacer(again, input(registries, early.buildResult()));
        check(again.awaitingAdoption() && again.pendingV1().equals(link.pendingV1()), "and reads back as the same v1 data");

        link.adopt(link.takePendingV1(), true);
        RawLink raw = link.raw();
        RawLinkSpec spec = BlockPlacer.TYPE.rawLink().orElseThrow();
        check(raw != null && raw.topic().equals(topic) && raw.direction() == RawLinkSpec.Direction.IN && raw.active() && raw.on() == null && raw.off() == null,
                "The v1 topic is the Raw Link input, without custom payloads: " + raw);
        check(new TopicAcl.Grandfather(topic, null).equals(raw.grandfathered()) && !spec.acceptRetained() && spec.grammar() == ValueCodecs.PLACER && !raw.stateEcho(),
                "Grandfathered, the placer's grammar, no retained message applied (acceptRetained=false), no state echo: " + raw);

        link.assign(UUID.randomUUID(), "Door Placer", "door_placer");
        BlockPlacer.Placed placed = new BlockPlacer.Placed(new BlockPos(3, 64, 1), Blocks.WOOL.white().defaultBlockState());
        BlockPlacer.Settings settings = new BlockPlacer.Settings("minecraft:white_wool", "placed minecraft:white_wool");
        TagValueOutput saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        BlockEmitterBlockEntity.savePlacer(link, settings, placed, saved);
        CompoundTag savedTag = saved.buildResult();
        check(!LegacyNbtV1.hasSubscribedKeys(input(registries, savedTag)) && NbtTags.keySet(savedTag).equals(Set.of(DeviceLink.KEY)),
                "Adopted, it writes no v1 key: " + savedTag);
        DeviceLink reloaded = new DeviceLink(BlockPlacer.TYPE);
        BlockEmitterBlockEntity.Loaded reread = BlockEmitterBlockEntity.loadPlacer(reloaded, input(registries, savedTag));
        check(!reloaded.awaitingAdoption() && raw.equals(reloaded.raw()) && reread.settings().equals(settings) && placed.equals(reread.placed()),
                "The adopted placer round-trips with its block and what it placed: " + reread);
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
        check(early.buildResult().equals(v1Publisher), "Saved before its adoption, a publisher writes its v1 data back as it was read");
        check(link.privateMode(), "v1's private mode is the link's");

        link.adopt(link.takePendingV1(), true);
        RawLink raw = link.raw();
        check(raw != null && raw.topic().equals(topic) && raw.direction() == RawLinkSpec.Direction.OUT && raw.active(), "The v1 topic is the Raw Link output: " + raw);
        check(new TopicAcl.Grandfather(topic, null).equals(raw.grandfathered()) && !raw.stateEcho() && raw.qos() == 0,
                "Grandfathered, no echo, QoS 0 as in v1: " + raw);
        // Wireless redstone is retained from now on (M2 plan D2): the adopted link follows the type's default.
        check(raw.retain() == null && raw.effectiveRetain(RedstoneInput.TYPE.rawLink().orElseThrow()), "An adopted v1 publisher publishes retained: " + raw);
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
     * A v1 Static Publisher converted into a Redstone Input (M2 plan D11), adopted on chunk load: its topic is a
     * grandfathered Raw Link output whose on payload is its message ({@code true} for an empty one), published on rising
     * edges only and explicitly not retained, as v1 published it; the item in its third slot, which never fed the message,
     * is no payload but a legacy item. Its owner UUID binds the owner at once and its anvil name becomes the device's.
     * Saved before its adoption it is still the same converted data; a copy placed from an item keeps the edges.
     *
     * @param payload the payload of a rising edge
     */
    public static void staticPublisherAdopted(HolderLookup.Provider registries, CompoundTag converted, String topic, @Nullable String on, String payload,
                                              @Nullable UUID ownerUuid, String name) {
        DeviceLink link = new DeviceLink(RedstoneInput.TYPE);
        int signal = PublisherBlockEntity.loadInput(link, input(registries, converted), false);
        check(link.awaitingAdoption() && signal == 0, "Converted static publisher data is v1 data: " + link.pendingV1());
        LegacyNbtV1.Snapshot v1 = link.pendingV1();
        check(v1.edges() == RawLink.Edges.RISING && v1.custom().equals(new ValueCodecs.CustomPayloads(on, null)),
                "The message is the on payload of rising edges, the third slot's item is none: " + v1);
        check(sameItems(link.legacyItems(), converted, registries), "Its slots are legacy items: " + link.legacyItems());
        TagValueOutput early = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        PublisherBlockEntity.saveInput(link, signal, early);
        check(early.buildResult().equals(converted), "Saved before its adoption, it writes the converted data back as it was read: " + early.buildResult());

        link.adopt(link.takePendingV1(), true);
        RawLink raw = link.raw();
        RawLinkSpec spec = RedstoneInput.TYPE.rawLink().orElseThrow();
        check(raw != null && raw.topic().equals(topic) && raw.direction() == RawLinkSpec.Direction.OUT && raw.active()
                && Objects.equals(raw.on(), on) && raw.off() == null, "The v1 topic is the Raw Link output with the message: " + raw);
        check(raw.edges() == RawLink.Edges.RISING && Boolean.FALSE.equals(raw.retain()) && !raw.effectiveRetain(spec) && !RawRetain.retains(raw, spec),
                "It publishes rising edges only, explicitly not retained: " + raw);
        check(new TopicAcl.Grandfather(topic, ownerUuid).equals(raw.grandfathered()) && raw.qos() == 0 && !raw.stateEcho(),
                "Grandfathered for its owner, QoS 0, no echo: " + raw);
        check(RedstoneInput.rawPayload(true, raw.customPayloads()).equals(payload) && raw.edges().publishes(true) && !raw.edges().publishes(false),
                "A rising edge sends " + payload + ", a falling edge nothing");
        check("Steve".equals(link.ownerName()) && Objects.equals(link.owner(), ownerUuid) && link.name().equals(name),
                "The owner, and the anvil name as the device's name: " + link.name());
        check(RawRetain.destroyed(raw, spec).isEmpty(), "Destroyed, it leaves nothing on its topic, as it stored nothing there");

        link.assign(UUID.randomUUID(), "Doorbell", "doorbell");
        TagValueOutput saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        PublisherBlockEntity.saveInput(link, 0, saved);
        CompoundTag savedTag = saved.buildResult();
        CompoundTag savedRaw = NbtTags.getCompound(NbtTags.getCompound(savedTag, DeviceLink.KEY).orElseThrow(), "raw").orElseThrow();
        check(NbtTags.keySet(savedTag).equals(Set.of(DeviceLink.KEY)) && NbtTags.getStringOr(savedRaw, "edges", "").equals("rising") && savedRaw.contains("retain"),
                "Adopted, it is v2 data whose link stores its edges and its retain: " + savedTag);
        DeviceLink reloaded = new DeviceLink(RedstoneInput.TYPE);
        PublisherBlockEntity.loadInput(reloaded, input(registries, savedTag), false);
        check(!reloaded.awaitingAdoption() && raw.equals(reloaded.raw()) && sameItems(reloaded.legacyItems(), converted, registries),
                "The adopted input round-trips: " + reloaded.raw());

        InputData placed = new InputData();
        check(CheckItems.loadItemData(BlockEntityTypes.DAYLIGHT_DETECTOR, converted, placed, registries) && placed.link.awaitingAdoption(),
                "A converted item's data is still v1 data");
        placed.link.sanitizePlaced(ALEX, "Alex", candidate -> Optional.empty());
        RawLink placedRaw = placed.link.raw();
        check(placedRaw != null && placedRaw.grandfathered() == null && ALEX.equals(placed.link.owner()) && placedRaw.edges() == RawLink.Edges.RISING
                && Boolean.FALSE.equals(placedRaw.retain()), "Placed, it is the placer's without grandfather flag and keeps its edges: " + placedRaw);
    }

    /**
     * A v1 Light Remote as a Wall Switch (M2-22): its data is v1 data, written back as it was read until adopted. Adopted,
     * its topic becomes no Raw Link and not its name, since it named the lamps the remote commanded, which the attach
     * wires it to instead (M2 plan D15); its owner and its topic slots as legacy items stay. Placed from an item, it is
     * the placer's, without a link either.
     */
    public static void remoteAdopted(HolderLookup.Provider registries, CompoundTag v1, String topic) {
        DeviceLink link = new DeviceLink(WallSwitch.TYPE);
        LightRemoteBlockEntity.loadSwitch(link, input(registries, v1));
        check(link.awaitingAdoption() && link.pendingV1().combinedTopic().equals(topic), "v1 Light Remote data is v1 data: " + link.pendingV1());
        check(sameItems(link.legacyItems(), v1, registries), "Its topic slots are legacy items: " + link.legacyItems());
        TagValueOutput early = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        LightRemoteBlockEntity.saveSwitch(link, early);
        check(early.buildResult().equals(v1), "Saved before its adoption, it writes the v1 data back as it was read: " + early.buildResult());

        link.adopt(link.takePendingV1(), true);
        check(link.raw() == null && link.name().isEmpty() && "Steve".equals(link.ownerName()),
                "Its topic becomes no Raw Link and no name, its owner stays: " + link.raw());
        link.assign(UUID.randomUUID(), "Wall Switch 1A2B", "wall_switch_1a2b");
        TagValueOutput saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        LightRemoteBlockEntity.saveSwitch(link, saved);
        CompoundTag savedTag = saved.buildResult();
        check(NbtTags.keySet(savedTag).equals(Set.of(DeviceLink.KEY)) && !NbtTags.getCompound(savedTag, DeviceLink.KEY).orElseThrow().contains("raw"),
                "Adopted, it is v2 data without a Raw Link: " + savedTag);
        DeviceLink reloaded = new DeviceLink(WallSwitch.TYPE);
        LightRemoteBlockEntity.loadSwitch(reloaded, input(registries, savedTag));
        check(!reloaded.awaitingAdoption() && reloaded.raw() == null && sameItems(reloaded.legacyItems(), v1, registries),
                "The adopted switch round-trips with its legacy items");

        DeviceLink placed = new DeviceLink(WallSwitch.TYPE);
        LightRemoteBlockEntity.loadSwitch(placed, input(registries, v1));
        placed.sanitizePlaced(ALEX, "Alex", candidate -> Optional.empty());
        check(placed.raw() == null && ALEX.equals(placed.owner()) && placed.legacyItems().isEmpty(),
                "Placed from an item, it is the placer's, without a Raw Link or the item's slots");
    }

    /**
     * v1 emitter and publisher items: placing one merges its data into the fresh block entity's save, which has a
     * {@code mineqtt} compound without id. The emitter recognises v1 data by its own key, the publisher by the missing
     * id; both adopt it with the placement, untrusted. Items of v2 devices stay v2 data.
     */
    public static void redstonePlacedFromItem(HolderLookup.Provider registries, CompoundTag v1Emitter, CompoundTag v1Publisher) {
        OutputData emitter = new OutputData();
        check(CheckItems.loadItemData(BlockEntityTypes.DAYLIGHT_DETECTOR, v1Emitter, emitter, registries), "The emitter item data is applied");
        check(emitter.link.awaitingAdoption(), "Merged v1 emitter data is still v1 data");
        emitter.link.sanitizePlaced(ALEX, "Alex", raw -> Optional.of("not in acl.rawAllow"));
        check(emitter.link.raw() != null && emitter.link.raw().grandfathered() == null && !emitter.link.raw().active(),
                "A placed v1 emitter's topic is checked for the placer, without grandfather flag: " + emitter.link.raw());

        InputData publisher = new InputData();
        check(CheckItems.loadItemData(BlockEntityTypes.DAYLIGHT_DETECTOR, v1Publisher, publisher, registries), "The publisher item data is applied");
        check(publisher.link.awaitingAdoption(), "Merged v1 publisher data is still v1 data");
        publisher.link.sanitizePlaced(ALEX, "Alex", raw -> Optional.empty());
        check(publisher.link.raw() != null && publisher.link.raw().direction() == RawLinkSpec.Direction.OUT && publisher.link.raw().grandfathered() == null
                && ALEX.equals(publisher.link.owner()), "A placed v1 publisher's topic is an output owned by the placer: " + publisher.link.raw());

        OutputData v2Emitter = new OutputData();
        v2Emitter.link.assign(UUID.randomUUID(), "Gate", "gate");
        v2Emitter.output = new Output(5, 5, RedstoneOutput.Mode.LEVEL, RedstoneOutput.PowerOn.ON);
        OutputData emitterCopy = new OutputData();
        check(CheckItems.loadItemData(BlockEntityTypes.DAYLIGHT_DETECTOR, v2Emitter.saveCustomOnly(registries), emitterCopy, registries)
                && !emitterCopy.link.awaitingAdoption() && emitterCopy.output.equals(v2Emitter.output), "A v2 output's item stays v2 data");
        InputData v2Publisher = new InputData();
        v2Publisher.link.assign(UUID.randomUUID(), "Bell", "bell");
        InputData publisherCopy = new InputData();
        check(CheckItems.loadItemData(BlockEntityTypes.DAYLIGHT_DETECTOR, v2Publisher.saveCustomOnly(registries), publisherCopy, registries)
                && !publisherCopy.link.awaitingAdoption() && v2Publisher.link.id().equals(publisherCopy.link.id()), "A v2 input's item stays v2 data");
    }

    /**
     * A v1 motion sensor adopted on chunk load (§9.4): its topic is a grandfathered Raw Link output for its count, and
     * the spawn eggs v1's filter tab put into slots 2 and 3 are no custom payloads but its filter (M2-12). It keeps
     * counting mobs, as v1 did. Saved before the adoption it is still v1 data, with v1's own keys and its slots.
     *
     * @param snapshotCustom the custom payloads a generic v1 snapshot reads from slots 2 and 3
     * @param filter         the mobs of the eggs in its filter slots
     */
    public static void motionAdopted(HolderLookup.Provider registries, CompoundTag v1Motion, String topic, ValueCodecs.CustomPayloads snapshotCustom,
                                     List<Identifier> filter) {
        DeviceLink link = new DeviceLink(PresenceSensor.TYPE);
        Presence presence = MotionSensorBlockEntity.loadSensor(link, input(registries, v1Motion));
        check(link.awaitingAdoption() && presence.equals(new Presence(PresenceSensor.Detect.MOBS, PresenceSensor.DEFAULT_RANGE,
                PresenceSensor.DEFAULT_CLEAR_DELAY, filter, 2, true, 0)), "v1 data counts the mobs of its eggs with its last count and motion: " + presence);
        TagValueOutput early = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        MotionSensorBlockEntity.saveSensor(link, presence, early);
        CompoundTag earlySave = early.buildResult();
        CompoundTag v1Read = v1Motion.copy();
        // v1's wall-clock time of the last motion is never read; it is written back as 0.
        v1Read.putLong("LastMotionTime", 0L);
        check(earlySave.equals(v1Read), "Saved before its adoption, a sensor writes its v1 data back as it was read: " + earlySave);
        DeviceLink earlyReload = new DeviceLink(PresenceSensor.TYPE);
        check(MotionSensorBlockEntity.loadSensor(earlyReload, input(registries, earlySave)).equals(presence) && earlyReload.awaitingAdoption(),
                "and reads back as the same v1 data");

        LegacyNbtV1.Snapshot snapshot = LegacyNbtV1.read(input(registries, v1Motion));
        check(snapshot.custom().equals(snapshotCustom), "The generic snapshot reads the eggs of slots 2 and 3: " + snapshot.custom());
        link.adopt(link.takePendingV1(), true);
        RawLink raw = link.raw();
        check(raw != null && raw.topic().equals(topic) && raw.direction() == RawLinkSpec.Direction.OUT && raw.active(), "The v1 topic is the Raw Link output: " + raw);
        check(raw.on() == null && raw.off() == null, "Spawn eggs in slots 2 and 3 are filters, no custom payloads: " + raw);
        check(new TopicAcl.Grandfather(topic, null).equals(raw.grandfathered()) && !raw.stateEcho() && raw.qos() == 0
                && raw.retain() == null && !raw.effectiveRetain(PresenceSensor.TYPE.rawLink().orElseThrow()),
                "Grandfathered, no echo, QoS 0 and not retained as in v1: a count is no wireless redstone: " + raw);
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
        check(CheckItems.loadItemData(BlockEntityTypes.DAYLIGHT_DETECTOR, v1Motion, sensor, registries), "The sensor item data is applied");
        check(sensor.link.awaitingAdoption() && sensor.presence.detect() == PresenceSensor.Detect.MOBS, "Merged v1 sensor data is still v1 data");
        sensor.link.sanitizePlaced(ALEX, "Alex", raw -> Optional.empty());
        RawLink raw = sensor.link.raw();
        check(raw != null && raw.direction() == RawLinkSpec.Direction.OUT && raw.grandfathered() == null && raw.on() == null && raw.off() == null
                && ALEX.equals(sensor.link.owner()), "A placed v1 sensor's topic is an output owned by the placer, without payloads: " + raw);
        check(sensor.link.legacyItems().isEmpty() && !sensor.presence.filter().isEmpty(),
                "The items of placed data are no legacy items; the filter of its eggs stays: " + sensor.presence);

        SensorData v2 = new SensorData();
        v2.link.assign(UUID.randomUUID(), "Porch", "porch");
        v2.presence = new Presence(PresenceSensor.Detect.ALL, 3, 0, List.of(Identifier.withDefaultNamespace("pig")), 1, true, 120);
        SensorData copy = new SensorData();
        check(CheckItems.loadItemData(BlockEntityTypes.DAYLIGHT_DETECTOR, v2.saveCustomOnly(registries), copy, registries)
                && !copy.link.awaitingAdoption() && copy.presence.equals(v2.presence), "A v2 sensor's item stays v2 data");
    }

    /**
     * M1 data as {@code data get block} captured it from an M1 world ({@code e2e/tools/buildm1.sh}), migrated to devices
     * without containers (M2-12): the slots become legacy items, the private mode and the last tab move into the link,
     * the Raw Link and the identity stay byte for byte, and the keys beside the compound are gone from the next save. A
     * second load and save changes nothing. The Presence Sensor's spawn eggs become its filter.
     *
     * @param m1 the captured block entity data by device: input, output, lamp, presence, private_output, adopted_output
     */
    public static void m1Migrated(HolderLookup.Provider registries, Map<String, CompoundTag> m1) {
        InputData input = migrated(registries, m1.get("input"), InputData::new);
        check("mineqtt/shared/m1/door".equals(input.link.raw().topic()) && "ON".equals(input.link.raw().on()),
                "The input keeps its resolved channel and payloads: " + input.link.raw());
        migrated(registries, m1.get("output"), OutputData::new);
        migrated(registries, m1.get("lamp"), LampData::new);
        SensorData presence = migrated(registries, m1.get("presence"), SensorData::new);
        check(presence.presence.filter().equals(List.of(Identifier.withDefaultNamespace("zombie"), Identifier.withDefaultNamespace("pig")))
                && presence.presence.detect() == PresenceSensor.Detect.MOBS, "The sensor counts the mobs of its eggs: " + presence.presence);
        check(presence.link.legacyItems().size() == 5, "The never shown slots 7 and 8 are legacy items too: " + presence.link.legacyItems());
        // The fixture's slots as captured, so a bug shared by the migration and sameItems shows.
        List<ItemStackWithSlot> legacy = presence.link.legacyItems();
        ItemStack paper = legacy.getFirst().stack();
        check(legacy.getFirst().slot() == 0 && paper.is(Items.PAPER) && paper.getCount() == 1 && paper.get(DataComponents.CUSTOM_NAME) != null
                && "m1/hall".equals(paper.get(DataComponents.CUSTOM_NAME).getString()), "Slot 0 keeps the named paper: " + paper);
        check(legacyItem(legacy, 1, 2, Items.ZOMBIE_SPAWN_EGG, 1) && legacyItem(legacy, 2, 3, Items.PIG_SPAWN_EGG, 2)
                && legacyItem(legacy, 3, 7, Items.STONE, 5) && legacyItem(legacy, 4, 8, Items.DIRT, 7), "The eggs, the stone and the dirt keep their slots: " + legacy);
        OutputData privateOutput = migrated(registries, m1.get("private_output"), OutputData::new);
        check(privateOutput.link.privateMode() && privateOutput.link.raw().topic().contains("/raw/players/"),
                "A private-mode output keeps its private topic: " + privateOutput.link.raw());
        OutputData adopted = migrated(registries, m1.get("adopted_output"), OutputData::new);
        check(adopted.link.raw().grandfathered() != null && "m1/legacy".equals(adopted.link.raw().grandfathered().topic()),
                "An adopted v1 output keeps its grandfathered topic: " + adopted.link.raw());

        List<ItemStack> ejected = presence.ejectLegacyItems();
        check(ejected.stream().mapToInt(ItemStack::getCount).sum() == 16 && presence.link.legacyItems().isEmpty()
                && presence.ejectLegacyItems().isEmpty(), "Eject hands out every legacy item once: " + ejected);
        CompoundTag ejectedSave = presence.saveCustomOnly(registries);
        check(!NbtTags.getCompound(ejectedSave, DeviceLink.KEY).orElseThrow().contains(DeviceLink.LEGACY_ITEMS)
                && presence.presence.filter().size() == 2, "An ejected sensor saves no legacy items and keeps its filter: " + ejectedSave);
    }

    private static boolean legacyItem(List<ItemStackWithSlot> legacy, int index, int slot, Item item, int count) {
        ItemStackWithSlot legacyItem = legacy.get(index);
        return legacyItem.slot() == slot && legacyItem.stack().is(item) && legacyItem.stack().getCount() == count;
    }

    // Loads M1 data into a fresh stand-in, checks the migration and that a second load is idempotent, and returns it.
    private static <T extends StandIn> T migrated(HolderLookup.Provider registries, CompoundTag m1, Supplier<T> fresh) {
        T device = fresh.get();
        BlockEntityStorage.loadCustomOnly(device, input(registries, m1));
        DeviceLink link = device.deviceLink();
        CompoundTag m1Compound = NbtTags.getCompound(m1, DeviceLink.KEY).orElseThrow();
        String name = NbtTags.getStringOr(m1Compound, "slug", "?");
        check(!link.awaitingAdoption() && link.loadedVersion() == DeviceLink.VERSION_M1, name + ": M1 data is v2 data of version 2");
        check(sameItems(link.legacyItems(), m1, registries), name + ": every slot item is a legacy item: " + link.legacyItems());
        check(link.privateMode() == NbtTags.getBooleanOr(m1, "privateMode", false) && link.lastTab().equals(NbtTags.getStringOr(m1, "lastTabId", "")),
                name + ": private mode and last tab move into the link");
        CompoundTag saved = device.saveCustomOnly(registries);
        for (String key : List.of("Items", "topic", "ownerName", "ownerUuid", "privateMode", "lastTabId")) {
            check(!saved.contains(key), name + ": no " + key + " beside the compound any more: " + saved);
        }
        CompoundTag compound = NbtTags.getCompound(saved, DeviceLink.KEY).orElseThrow();
        check(NbtTags.getIntOr(compound, "v", 0) == DeviceLink.VERSION, name + ": saved as version " + DeviceLink.VERSION);
        for (String key : List.of("id", "type", "name", "slug", "owner", "ownerName", "access", "expose", "raw")) {
            check(Objects.equals(compound.get(key), m1Compound.get(key)), name + ": " + key + " is unchanged: " + compound.get(key) + " was " + m1Compound.get(key));
        }
        T again = fresh.get();
        BlockEntityStorage.loadCustomOnly(again, input(registries, saved));
        check(again.deviceLink().loadedVersion() == DeviceLink.VERSION && sameItems(again.deviceLink().legacyItems(), m1, registries)
                && again.saveCustomOnly(registries).equals(saved), name + ": a second load and save changes nothing");
        return device;
    }

    /**
     * A placed copy (M2 plan D14, pick-block or an item with block entity data) keeps its settings and Raw Link, and
     * what it did is reset: the output emits nothing, the lamp is off, the sensor has seen nothing. Legacy items copied
     * with the data are none of the copy's.
     */
    public static void placedCopies(HolderLookup.Provider registries, CompoundTag m1Output, CompoundTag m1Presence) {
        OutputData output = fresh(registries, m1Output, OutputData::new);
        output.output = new Output(9, 9, RedstoneOutput.Mode.LEVEL, RedstoneOutput.PowerOn.ON);
        OutputData outputCopy = placedCopy(registries, output, OutputData::new);
        check(outputCopy.output.equals(new Output(0, 9, RedstoneOutput.Mode.LEVEL, RedstoneOutput.PowerOn.ON))
                && output.link.raw().equals(outputCopy.link.raw()), "A placed output emits nothing and keeps its settings and link: " + outputCopy.output);

        LampData lamp = new LampData();
        lamp.light = new LightState(true, 180, new ValueCodecs.Rgb(0, 0, 255), null);
        LampData lampCopy = placedCopy(registries, lamp, LampData::new);
        check(lampCopy.light.equals(new LightState(false, 180, new ValueCodecs.Rgb(0, 0, 255), null)), "A placed lamp is off in its colour: " + lampCopy.light);

        SensorData sensor = fresh(registries, m1Presence, SensorData::new);
        sensor.presence = sensor.presence.scanned(2, 100);
        SensorData sensorCopy = placedCopy(registries, sensor, SensorData::new);
        check(sensorCopy.presence.equals(new Presence(PresenceSensor.Detect.MOBS, PresenceSensor.DEFAULT_RANGE, PresenceSensor.DEFAULT_CLEAR_DELAY,
                sensor.presence.filter(), 0, false, 0)), "A placed sensor has seen nothing and keeps its filter: " + sensorCopy.presence);

    }

    private static <T extends StandIn> T fresh(HolderLookup.Provider registries, CompoundTag data, Supplier<T> factory) {
        T device = factory.get();
        BlockEntityStorage.loadCustomOnly(device, input(registries, data));
        return device;
    }

    // The item data of a device placed again, as the block's placement applies and checks it.
    private static <T extends StandIn> T placedCopy(HolderLookup.Provider registries, StandIn original, Supplier<T> factory) {
        T copy = factory.get();
        check(CheckItems.loadItemData(BlockEntityTypes.DAYLIGHT_DETECTOR, original.saveCustomOnly(registries), copy, registries), "The item data is applied");
        copy.deviceLink().sanitizePlaced(ALEX, "Alex", raw -> Optional.empty());
        copy.placedBy(null);
        check(copy.deviceLink().legacyItems().isEmpty(), "A placed copy has no legacy items");
        return copy;
    }

    /** Whether a link's legacy items are the items of the {@code Items} list of v1 or M1 data, slot by slot. */
    private static boolean sameItems(List<ItemStackWithSlot> legacy, CompoundTag data, HolderLookup.Provider registries) {
        List<ItemStackWithSlot> items = LegacyNbtV1.items(input(registries, data));
        if (items.isEmpty() || items.size() != legacy.size()) return false;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).slot() != legacy.get(i).slot() || !ItemStack.matches(items.get(i).stack(), legacy.get(i).stack())) return false;
        }
        return true;
    }

    /**
     * M4 plan D2 (M4-1 seam): a type marked {@code noV1Topic} adopts no Raw Link from its v1 slots' literal, trusted or
     * not, although it has a Raw Link: the v1 timers never published, their slots held hopper-inserted items. The owner
     * and an anvil name are adopted as ever, the slot items become legacy items. The same data for a type without the mark
     * keeps the M2 rule, the literal as a Raw Link.
     *
     * @param v1Timer v1 data whose slots spell {@code topic} (base and sub paper), owned by Steve
     */
    public static void noV1TopicAdopted(HolderLookup.Provider registries, CompoundTag v1Timer, String topic) {
        DeviceType quiet = timerLike("check_quiet_timer").noV1Topic().build();
        DeviceType plain = timerLike("check_plain_timer").build();
        check(quiet.noV1Topic() && !plain.noV1Topic() && quiet.rawLink().isPresent(), "Both types have a Raw Link, one is marked");

        DeviceLink control = new DeviceLink(plain);
        control.load(input(registries, v1Timer), true);
        control.adopt(control.takePendingV1(), true);
        check(control.raw() != null && control.raw().topic().equals(topic) && control.raw().grandfathered() != null,
                "Without the mark the slots' literal becomes a grandfathered Raw Link, as in M2: " + control.raw());

        for (boolean trusted : new boolean[]{true, false}) {
            DeviceLink link = new DeviceLink(quiet);
            link.load(input(registries, v1Timer), true);
            check(link.awaitingAdoption() && link.legacyItems().size() == 2, "The timer's v1 data waits for its attach, its slot items kept: " + link.legacyItems());
            link.adopt(link.takePendingV1(), trusted);
            check(link.raw() == null, "A noV1Topic type adopts no Raw Link from the slots' literal (trusted " + trusted + "): " + link.raw());
            check("Steve".equals(link.ownerName()) && link.name().equals("Kitchen Alarm") && link.legacyItems().size() == 2,
                    "Its owner name, anvil name and legacy items are adopted as for any type (trusted " + trusted + ")");
        }
    }

    /**
     * M4-3: the attach adopts a timer's v1 data as it adopts any device's ({@link DeviceLink#adopt}), for the timer checks
     * outside this package.
     */
    public static void adopt(DeviceLink link, boolean trusted) {
        link.adopt(link.takePendingV1(), trusted);
    }

    // A timer-like type with an events Raw Link (M4 plan D2), not registered.
    private static DeviceType.Builder timerLike(String path) {
        Field<Boolean> enabled = Field.bool("enabled", true, Access.READ_WRITE);
        return DeviceType.builder(Identifier.fromNamespaceAndPath("mineqtt", path)).model("Check Timer").unloaded(UnloadedPolicy.UNAVAILABLE)
                .field(enabled).entity(Ha.switchOf("enabled", enabled).on(true).off(false).name(null)).action(Action.state())
                .event(EventSpec.of("fired")).entity(Ha.event("fired", "fired")).rawLink(RawLinkSpec.events()).preview((state, command) -> state);
    }

    private static ValueInput input(HolderLookup.Provider registries, CompoundTag tag) {
        return TagValueInput.create(ProblemReporter.DISCARDING, registries, tag);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /**
     * A device's block entity without the block entity type only a mod loader registers: the real
     * {@link DeviceBlockEntity} over a vanilla type, so its load, save, render dispatch, placement and eject run as the
     * device's do.
     */
    private abstract static class StandIn extends DeviceBlockEntity {
        StandIn() {
            super(BlockEntityTypes.DAYLIGHT_DETECTOR, BlockPos.ZERO, Blocks.DAYLIGHT_DETECTOR.defaultBlockState());
        }

        @Override
        public void writeState(StateWriter out) {
        }

        @Override
        public CommandResult applyCommand(CommandReader in, CommandSource source) {
            return CommandResult.unchanged();
        }
    }

    /** The Redstone Output's data. */
    private static final class OutputData extends StandIn {
        private final DeviceLink link = new DeviceLink(RedstoneOutput.TYPE);
        private Output output = Output.DEFAULT;

        @Override
        public DeviceType deviceType() {
            return RedstoneOutput.TYPE;
        }

        @Override
        public DeviceLink deviceLink() {
            return link;
        }

        @Override
        protected void saveDevice(ValueOutput output) {
            RedstoneEmitterBlockEntity.saveOutput(link, this.output, output);
        }

        @Override
        protected void loadDevice(ValueInput input) {
            output = RedstoneEmitterBlockEntity.loadOutput(link, input, 0);
        }

        @Override
        protected void placedCopy() {
            output = output.placed();
        }
    }

    /** The Smart Plug's data. */
    private static final class PlugData extends StandIn {
        private final DeviceLink link = new DeviceLink(SmartPlug.TYPE);
        private Plug plug = Plug.DEFAULT;

        @Override
        public DeviceType deviceType() {
            return SmartPlug.TYPE;
        }

        @Override
        public DeviceLink deviceLink() {
            return link;
        }

        @Override
        protected void saveDevice(ValueOutput output) {
            SmartPlugBlockEntity.savePlug(link, plug, output);
        }

        @Override
        protected void loadDevice(ValueInput input) {
            plug = SmartPlugBlockEntity.loadPlug(link, input, 0);
        }

        @Override
        protected void placedCopy() {
            plug = plug.placed();
        }
    }

    /** The Presence Sensor's data. */
    private static final class SensorData extends StandIn {
        private final DeviceLink link = new DeviceLink(PresenceSensor.TYPE);
        private Presence presence = Presence.DEFAULT;

        @Override
        public DeviceType deviceType() {
            return PresenceSensor.TYPE;
        }

        @Override
        public DeviceLink deviceLink() {
            return link;
        }

        @Override
        protected void saveDevice(ValueOutput output) {
            MotionSensorBlockEntity.saveSensor(link, presence, output);
        }

        @Override
        protected void loadDevice(ValueInput input) {
            presence = MotionSensorBlockEntity.loadSensor(link, input);
        }

        @Override
        protected void placedCopy() {
            presence = presence.placed();
        }
    }

    /** The Redstone Input's data. */
    private static final class InputData extends StandIn {
        private final DeviceLink link = new DeviceLink(RedstoneInput.TYPE);
        private int signal;

        @Override
        public DeviceType deviceType() {
            return RedstoneInput.TYPE;
        }

        @Override
        public DeviceLink deviceLink() {
            return link;
        }

        @Override
        protected void saveDevice(ValueOutput output) {
            PublisherBlockEntity.saveInput(link, signal, output);
        }

        @Override
        protected void loadDevice(ValueInput input) {
            signal = PublisherBlockEntity.loadInput(link, input, false);
        }

        @Override
        protected void placedCopy() {
            signal = 0;
        }
    }

    /**
     * The Smart Lamp's data, so the vanilla item-data path ({@code TypedEntityData.loadInto}) runs without the lamp's
     * block entity type, which only a mod loader registers.
     */
    private static final class LampData extends StandIn {
        private final DeviceLink link = new DeviceLink(SmartLamp.TYPE);
        private LightState light = LightState.DEFAULT;

        @Override
        public DeviceType deviceType() {
            return SmartLamp.TYPE;
        }

        @Override
        public DeviceLink deviceLink() {
            return link;
        }

        @Override
        protected void saveDevice(ValueOutput output) {
            RgbLedBlockEntity.saveLamp(link, light, output);
        }

        @Override
        protected void loadDevice(ValueInput input) {
            light = RgbLedBlockEntity.loadLamp(link, input);
        }

        @Override
        protected void placedCopy() {
            light = light.placed();
        }
    }
}

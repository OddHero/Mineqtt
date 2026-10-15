package art.rehra.mineqtt.device;

import art.rehra.mineqtt.compat.NbtTags;
import art.rehra.mineqtt.blocks.entities.PublisherBlockEntity;
import art.rehra.mineqtt.device.types.PresenceSensor;
import art.rehra.mineqtt.device.types.RedstoneInput;
import art.rehra.mineqtt.device.types.RedstoneOutput;
import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.device.types.Timers;
import art.rehra.mineqtt.legacy.LegacyNbtV1;
import art.rehra.mineqtt.mqtt.core.OfflinePolicy;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;

import java.util.List;
import java.util.UUID;

/**
 * Wireless redstone is retained (M2 plan D2), run by Gradle's verifyRawLinkRetain task: the {@code retain} override of a
 * Raw Link and the type defaults, M1 and v1 links that become retained, empty payloads that never command, links of
 * rising edges only (M2 plan D11), and what an output leaves on the broker when it is destroyed or its link changes
 * ({@link RawRetain}).
 */
public final class RawLinkRetainCheck {
    private static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
    private static final UUID ALEX = UUID.fromString("ec561538-f3fd-461d-aff5-086b22154bce");
    private static final TopicAcl ACL = new TopicAcl(new TopicAcl.Rules("mineqtt", "world_7f3a", "homeassistant", null,
            TopicAcl.defaultRawAllow("mineqtt", "world_7f3a"), false, false));
    /**
     * A Redstone Input's block entity data as M1 (commit 9eb2b2c) saved it: {@code DeviceLink.save} wrote the link with
     * {@code Codec.BOOL.optionalFieldOf("retain", false)}, which leaves out a {@code false} retain, and without the
     * {@code resolvedV} and {@code channel} keys F1 added.
     */
    private static final String M1_PUBLISHER = """
            {topic:"mineqtt/shared/garage",lastTabId:"settings",ownerName:"Steve",privateMode:0b,Items:[],
             mineqtt:{v:2,id:[I;-1214907277,1167531354,-1546101369,-1127461225],type:"mineqtt:redstone_input",
              name:"Garage Door",slug:"garage_door",owner:[I;-2040103311,-1202044924,-1352383321,-1754993961],
              ownerName:"Steve",access:"claim",raw:{topic:"mineqtt/shared/garage",dir:"out"},state:{signal:15}}}""";

    // Set once Minecraft is bootstrapped: the device types load vanilla classes that need it.
    private static HolderLookup.Provider registries;
    private static RawLinkSpec INPUT;
    private static RawLinkSpec SENSOR;

    public static void main(String[] args) throws CommandSyntaxException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        INPUT = RedstoneInput.TYPE.rawLink().orElseThrow();
        SENSOR = PresenceSensor.TYPE.rawLink().orElseThrow();
        typeDefaults();
        codec();
        m1Data();
        adoptedV1();
        emptyPayloads();
        toggles();
        risingEdges();
        runtimeEdges();
        destroyPlan();
        changePlans();
        delivery();
        System.out.println("Raw Link retain checks passed.");
    }

    private static void typeDefaults() {
        check(INPUT.retainByDefault() && !SENSOR.retainByDefault(), "Wireless redstone retains by default, a count does not");
        check(INPUT.offPayload(ValueCodecs.CustomPayloads.NONE).equals("false") && INPUT.offPayload(new ValueCodecs.CustomPayloads("OPEN", "SHUT")).equals("SHUT"),
                "An input is off with v1's false or its custom off payload");
        check(SENSOR.offPayload(ValueCodecs.CustomPayloads.NONE).equals("0"), "A sensor is off with a count of 0");
        for (RawLinkSpec in : List.of(RedstoneOutput.TYPE.rawLink().orElseThrow(), SmartLamp.TYPE.rawLink().orElseThrow())) {
            check(in.off() == null && !in.retainByDefault(), "An input publishes nothing");
        }
        expectFailure(() -> RawLinkSpec.in(ValueCodecs.REDSTONE).retainByDefault(true), "An input has no retain default");
        // M4-4: a timer's link carries events, one per fire: never retained, not even when a player asked for it.
        for (DeviceType timer : Timers.all()) {
            RawLinkSpec spec = timer.rawLink().orElseThrow();
            check(spec.direction() == RawLinkSpec.Direction.OUT && !spec.level() && !spec.retainByDefault() && !spec.customPayloads(),
                    timer + ": an output of events without custom payloads");
            for (Boolean retain : new Boolean[]{null, true, false}) {
                RawLink link = new RawLink("mineqtt/shared/porch", RawLinkSpec.Direction.OUT, null, null, 0, retain, null, false, null, RawTopics.VERSION, "porch",
                        RawLink.Edges.BOTH);
                check(!link.effectiveRetain(spec) && !RawRetain.retains(link, spec), timer + ": never retained, with retain " + retain);
            }
            check(spec.offPayload(ValueCodecs.CustomPayloads.NONE).isEmpty(), timer + ": it leaves nothing behind on a topic it leaves");
        }
        check(Timers.RAW_PAYLOAD.equals("ON"), "A fire publishes ON");
    }

    private static void codec() {
        RawLink unset = out("mineqtt/shared/garage", null);
        CompoundTag encoded = encode(unset);
        check(!encoded.contains("retain"), "A link without an override saves no retain key: " + encoded);
        RawLink decoded = decode(encoded);
        check(decoded.equals(unset) && decoded.retain() == null, "A missing key decodes as the type default: " + decoded);
        check(decoded.effectiveRetain(INPUT) && !decoded.effectiveRetain(SENSOR), "The default is the input's true and the sensor's false");

        RawLink off = out("mineqtt/shared/garage", false);
        CompoundTag offTag = encode(off);
        check(offTag.contains("retain") && decode(offTag).equals(off) && !decode(offTag).effectiveRetain(INPUT),
                "A player's explicit false is stored and wins over the default: " + offTag);
        RawLink on = out("mineqtt/shared/hall", true);
        check(decode(encode(on)).equals(on) && on.effectiveRetain(SENSOR), "An explicit true round-trips and makes a sensor retain");
        check(Boolean.TRUE.equals(on.withCustomPayloads(new ValueCodecs.CustomPayloads("UP", null)).retain())
                        && Boolean.FALSE.equals(off.checked("refused").markResolved().withoutGrandfather().retain()),
                "Every derived link keeps the override");
    }

    /** A link M1 saved carries no retain key, so a Redstone Input of an M1 world publishes retained from now on. */
    private static void m1Data() throws CommandSyntaxException {
        CompoundTag saved = NbtTags.parseCompoundFully(M1_PUBLISHER);
        DeviceLink link = new DeviceLink(RedstoneInput.TYPE);
        int signal = PublisherBlockEntity.loadInput(link, TagValueInput.create(ProblemReporter.DISCARDING, registries, saved), false);
        RawLink raw = link.raw();
        check(!link.awaitingAdoption() && signal == 15 && raw != null && raw.topic().equals("mineqtt/shared/garage"), "The M1 data loads: " + raw);
        check(raw.retain() == null && raw.effectiveRetain(INPUT) && RawRetain.retains(raw, INPUT), "The M1 link is retained now: " + raw);
        check(!raw.resolved() && raw.channel() == null, "and still waits for its one-time re-resolution: " + raw);
    }

    /** v1 publishers published without retain; adopted, they follow the type's default and are retained. */
    private static void adoptedV1() {
        DeviceLink link = new DeviceLink(RedstoneInput.TYPE);
        link.adopt(new LegacyNbtV1.Snapshot("garage/door", "Steve", null, false, new ValueCodecs.CustomPayloads("OPEN", null), "", RawLink.Edges.BOTH), true);
        RawLink raw = link.raw();
        check(raw != null && raw.grandfathered() != null && raw.retain() == null && raw.effectiveRetain(INPUT), "An adopted v1 publisher is retained: " + raw);
        DeviceLink sensor = new DeviceLink(PresenceSensor.TYPE);
        sensor.adopt(new LegacyNbtV1.Snapshot("hall/motion", "Steve", null, false, ValueCodecs.CustomPayloads.NONE, "", RawLink.Edges.BOTH), true);
        check(!sensor.raw().effectiveRetain(SENSOR), "An adopted v1 motion sensor is not: " + sensor.raw());
    }

    /** An empty payload clears a retained level; it never commands, also not as an empty custom payload. */
    private static void emptyPayloads() {
        ValueCodecs.CustomPayloads emptyOn = new ValueCodecs.CustomPayloads("", "SHUT");
        ValueCodecs.CustomPayloads emptyOff = new ValueCodecs.CustomPayloads("OPEN", "");
        for (ValueCodecs.RawGrammar grammar : List.of(ValueCodecs.REDSTONE, ValueCodecs.LIGHT)) {
            check(grammar.command("", ValueCodecs.CustomPayloads.NONE).isEmpty(), "An empty payload commands nothing");
            check(grammar.command("", emptyOn).isEmpty() && grammar.command("", emptyOff).isEmpty(), "Neither does an empty custom payload");
            check(grammar.command("OPEN", emptyOff).isPresent() && grammar.command("SHUT", emptyOn).isPresent(), "The other custom payload still works");
        }
        check(ValueCodecs.REDSTONE.command("SHUT", new ValueCodecs.CustomPayloads("OPEN", "SHUT")).orElseThrow().get("power").getAsInt() == 0,
                "The off payload an input leaves on its topic switches an output off");
    }

    /**
     * A custom payload that toggles is never retained: stored, it would flip every receiver again at each subscribe,
     * reconnect and republish. Its link publishes plain edges, as in v1.
     */
    private static void toggles() {
        for (String toggle : List.of("toggle", " Toggle ", "TOGGLE", "{\"state\":\"toggle\"}")) {
            check(ValueCodecs.isToggle(toggle), "A toggle: " + toggle);
        }
        for (String level : List.of("true", "OPEN", "{\"state\":\"ON\"}", "{\"state\":{}}", "{broken", "toggled")) {
            check(!ValueCodecs.isToggle(level), "No toggle: " + level);
        }
        check(ValueCodecs.REDSTONE.command(" Toggle ", ValueCodecs.CustomPayloads.NONE).orElseThrow().get("state").getAsString().equals("TOGGLE"),
                "The grammar reads the same spelling as a toggle");

        RawLink garage = out("mineqtt/shared/garage", null);
        RawLink toggling = garage.withCustomPayloads(new ValueCodecs.CustomPayloads(" Toggle ", null));
        check(!toggling.effectiveRetain(INPUT) && !RawRetain.retains(toggling, INPUT), "A toggling on payload is not retained: " + toggling);
        RawLink toggleOff = garage.withCustomPayloads(new ValueCodecs.CustomPayloads("OPEN", "toggle"));
        check(!toggleOff.effectiveRetain(INPUT), "Neither is a toggling off payload");
        RawLink forced = new RawLink(garage.topic(), RawLinkSpec.Direction.OUT, "toggle", "toggle", 0, true, null, false, null,
                RawTopics.VERSION, "garage", RawLink.Edges.BOTH);
        check(!forced.effectiveRetain(INPUT), "not even with retain switched on explicitly");
        check(RawRetain.destroyed(toggling, INPUT).isEmpty(), "A destroyed toggling input leaves nothing on the topic");

        DeviceLink adopted = new DeviceLink(RedstoneInput.TYPE);
        adopted.adopt(new LegacyNbtV1.Snapshot("garage/door", "Steve", null, false, new ValueCodecs.CustomPayloads("toggle", "toggle"), "", RawLink.Edges.BOTH), true);
        check(!adopted.raw().effectiveRetain(INPUT), "A v1 publisher with toggle paper keeps publishing edges only: " + adopted.raw());

        List<RawRetain.Step> becameToggle = RawRetain.plan(garage, toggling, INPUT);
        check(becameToggle.size() == 1 && becameToggle.getFirst().kind() == RawRetain.Kind.CLEAR
                && becameToggle.getFirst().topic().equals(garage.topic()), "Toggle paper on a retained topic clears its stored level: " + becameToggle);
        List<RawRetain.Step> becameLevel = RawRetain.plan(toggling, garage, INPUT);
        check(becameLevel.size() == 1 && becameLevel.getFirst().kind() == RawRetain.Kind.LEVEL, "Toggle paper taken out: the level: " + becameLevel);
        check(RawRetain.plan(toggling, out("mineqtt/shared/hall", null).withCustomPayloads(toggling.customPayloads()), INPUT).isEmpty(),
                "A toggling link that moves publishes nothing");
    }

    /**
     * A link of rising edges only (M2 plan D11, a converted Static Publisher) publishes its on payload once per pulse and
     * never on the falling edge; it carries events, so it has no level to publish again and is never retained, whatever
     * its override says. Its edges survive the codec and every change of the link; links without them carry both.
     */
    private static void risingEdges() {
        RawLink both = out("mineqtt/shared/bell", null);
        RawLink rising = new RawLink("mineqtt/shared/bell", RawLinkSpec.Direction.OUT, "DING", null, 0, false, null, false, null, RawTopics.VERSION,
                "bell", RawLink.Edges.RISING);
        List<Boolean> pulses = List.of(true, false, true, false, true, false);
        List<Boolean> sent = pulses.stream().filter(rising.edges()::publishes).toList();
        check(sent.size() == 3 && !sent.contains(false), "Three pulses publish three rising edges and no falling one: " + sent);
        check(pulses.stream().filter(both.edges()::publishes).count() == pulses.size(), "A level link publishes every edge");
        check(both.edges().level() && !rising.edges().level(), "Only a link of both edges carries a level");

        CompoundTag bothTag = encode(both);
        CompoundTag risingTag = encode(rising);
        check(!bothTag.contains("edges") && NbtTags.getStringOr(risingTag, "edges", "").equals("rising") && decode(risingTag).equals(rising),
                "Both edges save no key; rising edges save theirs and round-trip: " + risingTag);
        CompoundTag unknown = risingTag.copy();
        unknown.putString("edges", "falling");
        check(RawLink.CODEC.parse(NbtOps.INSTANCE, unknown).isError(), "Unknown edges are refused");

        check(!rising.effectiveRetain(INPUT) && !rising.withRetain(true).effectiveRetain(INPUT) && !RawRetain.retains(rising.withRetain(true), INPUT),
                "Events are never retained, not even with retain switched on: " + rising);
        check(RawRetain.destroyed(rising.withRetain(true), INPUT).isEmpty() && RawRetain.plan(rising, rising.resolvedTo(
                        new RawTopics.Resolution("hall", RawTopics.Kind.SHARED, "mineqtt/shared/hall", null, null)), INPUT).isEmpty(),
                "Destroyed or moved, it leaves nothing on a topic it never stored a level on");

        List<RawLink> derived = List.of(rising.withCustomPayloads(new ValueCodecs.CustomPayloads("DONG", null)), rising.withRetain(null), rising.withQos(1),
                rising.withoutGrandfather(), rising.checked("refused"), rising.markResolved(), rising.markResolved("hall"),
                rising.resolvedTo(new RawTopics.Resolution("hall", RawTopics.Kind.SHARED, "mineqtt/shared/hall", null, null)),
                RawLink.typed("zigbee2mqtt/bell/set", RawLinkSpec.Direction.OUT, rising),
                new RawLink("home/bell", RawLinkSpec.Direction.OUT, "DING", null, 0, false, new TopicAcl.Grandfather("home/bell", null), false, null, 0,
                        null, RawLink.Edges.RISING).withGrandfatherOwner(STEVE));
        for (RawLink link : derived) check(link.edges() == RawLink.Edges.RISING, "Every change of the link keeps its edges: " + link);
        check(RawLink.typed("zigbee2mqtt/bell/set", RawLinkSpec.Direction.OUT, null).edges() == RawLink.Edges.BOTH
                        && new RawTopics.Resolution("bell", RawTopics.Kind.SHARED, "mineqtt/shared/bell", null, null).link(RawLinkSpec.Direction.OUT, false).edges()
                        == RawLink.Edges.BOTH, "A new link carries both edges");

        DeviceLink adopted = new DeviceLink(RedstoneInput.TYPE);
        adopted.adopt(new LegacyNbtV1.Snapshot("home/bell", "Steve", STEVE, false, new ValueCodecs.CustomPayloads("DING", null), "", RawLink.Edges.RISING), true);
        check(adopted.raw().edges() == RawLink.Edges.RISING && Boolean.FALSE.equals(adopted.raw().retain()) && "DING".equals(adopted.raw().on()),
                "An adopted static publisher's link sends its message on rising edges, explicitly not retained: " + adopted.raw());
        DeviceLink output = new DeviceLink(RedstoneOutput.TYPE);
        output.adopt(new LegacyNbtV1.Snapshot("home/bell", "Steve", STEVE, false, ValueCodecs.CustomPayloads.NONE, "", RawLink.Edges.RISING), true);
        check(output.raw().edges() == RawLink.Edges.BOTH && output.raw().retain() == null, "An input link has no edges to publish: " + output.raw());
    }

    /**
     * The runtime applies the edges: an edge a Redstone Input sends is published only when its link publishes it, and what
     * the runtime publishes again on its own (after a CONNACK, an owner lookup or an ACL change) is the live level of a
     * level link only, never a converted Static Publisher's event.
     */
    private static void runtimeEdges() {
        RawLink both = out("mineqtt/shared/bell", null);
        RawLink rising = new RawLink("mineqtt/shared/bell", RawLinkSpec.Direction.OUT, "DING", null, 0, false, null, false, null, RawTopics.VERSION,
                "bell", RawLink.Edges.RISING);
        check(DeviceRuntime.publishesEdge(rising, true) && !DeviceRuntime.publishesEdge(rising, false),
                "The runtime publishes a rising-edge link's rising edge and drops its falling one");
        check(DeviceRuntime.publishesEdge(both, true) && DeviceRuntime.publishesEdge(both, false), "and both edges of a level link");
        check(!DeviceRuntime.publishesEdge(null, true) && !DeviceRuntime.publishesEdge(null, false), "and nothing without a link");

        String live = "true";
        DeviceLink link = new DeviceLink(RedstoneInput.TYPE);
        DeviceHost host = new DeviceHost() {
            @Override
            public DeviceType deviceType() {
                return RedstoneInput.TYPE;
            }

            @Override
            public DeviceLink deviceLink() {
                return link;
            }

            @Override
            public void writeState(StateWriter out) {
            }

            @Override
            public CommandResult applyCommand(CommandReader in, CommandSource source) {
                throw new UnsupportedOperationException("The stub host takes no commands");
            }

            @Override
            public String rawValue() {
                return live;
            }
        };
        link.setRaw(both);
        check(live.equals(DeviceRuntime.rawLevel(host)), "A level link publishes its live level again");
        link.setRaw(rising);
        check(DeviceRuntime.rawLevel(host) == null, "A rising-edge link has no level to publish again");
        link.setRaw(null);
        check(DeviceRuntime.rawLevel(host) == null, "Neither has a device without a link");
    }

    /**
     * A destroyed input publishes its off payload retained to its topic, judged by the ACL as its link was, grandfather flag
     * included; nothing for a link that does not retain, is refused or is an input's.
     */
    private static void destroyPlan() {
        RawLink grandfathered = new RawLink("home/garage", RawLinkSpec.Direction.OUT, "OPEN", "SHUT", 1, null,
                new TopicAcl.Grandfather("home/garage", STEVE), false, null, RawTopics.VERSION, null, RawLink.Edges.BOTH);
        List<RawRetain.Step> steps = RawRetain.destroyed(grandfathered, INPUT);
        check(steps.size() == 1, "One publish: " + steps);
        RawRetain.Step off = steps.getFirst();
        check(off.kind() == RawRetain.Kind.OFF && off.topic().equals("home/garage") && off.payload().equals("SHUT") && off.judgedBy() == grandfathered,
                "The custom off payload to the old topic, judged by the old link: " + off);
        check(ACL.checkPublish(subject(STEVE), off.topic(), off.judgedBy().grandfathered()) == TopicAcl.Decision.ALLOWED_GRANDFATHERED,
                "The grandfather flag lets the owner clear their v1 topic");
        check(!ACL.checkPublish(subject(ALEX), off.topic(), off.judgedBy().grandfathered()).allowed(), "but nobody else");
        check(RawRetain.destroyed(out("mineqtt/shared/garage", null), INPUT).getFirst().payload().equals("false"), "v1's false without a custom payload");

        check(RawRetain.destroyed(out("mineqtt/shared/garage", false), INPUT).isEmpty(), "A link that does not retain leaves nothing");
        check(RawRetain.destroyed(out("mineqtt/shared/hall", null), SENSOR).isEmpty(), "Neither does a sensor by default");
        check(RawRetain.destroyed(out("mineqtt/shared/hall", true), SENSOR).getFirst().payload().equals("0"), "A retaining sensor leaves a count of 0");
        check(RawRetain.destroyed(out("mineqtt/shared/garage", null).checked("refused"), INPUT).isEmpty(), "A refused link never published");
        check(RawRetain.destroyed(null, INPUT).isEmpty(), "No link, nothing");
        RawLink in = new RawLink("mineqtt/shared/garage", RawLinkSpec.Direction.IN, null, null, 0, null, null, false, null, RawTopics.VERSION, "garage", RawLink.Edges.BOTH);
        check(RawRetain.destroyed(in, RedstoneOutput.TYPE.rawLink().orElseThrow()).isEmpty(), "An input publishes nothing");
    }

    /** A retarget leaves the old topic off and holds the level on the new one; retain switched off clears the topic. */
    private static void changePlans() {
        RawLink garage = out("mineqtt/shared/garage", null);
        RawLink hall = out("mineqtt/shared/hall", null);
        List<RawRetain.Step> moved = RawRetain.plan(garage, hall, INPUT);
        check(moved.size() == 2 && moved.get(0).kind() == RawRetain.Kind.OFF && moved.get(0).topic().equals("mineqtt/shared/garage")
                        && moved.get(0).judgedBy() == garage && moved.get(1).kind() == RawRetain.Kind.LEVEL
                        && moved.get(1).topic().equals("mineqtt/shared/hall") && moved.get(1).judgedBy() == hall && moved.get(1).payload() == null,
                "A retarget: off to the old topic, the level to the new one: " + moved);
        List<RawRetain.Step> emptied = RawRetain.plan(garage, null, INPUT);
        check(emptied.size() == 1 && emptied.getFirst().kind() == RawRetain.Kind.OFF, "Emptied slots leave the topic off: " + emptied);
        List<RawRetain.Step> created = RawRetain.plan(null, garage, INPUT);
        check(created.size() == 1 && created.getFirst().kind() == RawRetain.Kind.LEVEL, "A new link holds its level: " + created);

        List<RawRetain.Step> switchedOff = RawRetain.plan(garage, out("mineqtt/shared/garage", false), INPUT);
        check(switchedOff.size() == 1 && switchedOff.getFirst().kind() == RawRetain.Kind.CLEAR && switchedOff.getFirst().payload().isEmpty()
                && switchedOff.getFirst().topic().equals("mineqtt/shared/garage"), "Retain switched off: an empty retained clear: " + switchedOff);
        List<RawRetain.Step> switchedOn = RawRetain.plan(out("mineqtt/shared/garage", false), garage, INPUT);
        check(switchedOn.size() == 1 && switchedOn.getFirst().kind() == RawRetain.Kind.LEVEL, "Retain switched on: the level: " + switchedOn);
        check(RawRetain.plan(out("mineqtt/shared/garage", false), out("mineqtt/shared/hall", false), INPUT).isEmpty(),
                "A link that does not retain publishes nothing when it moves");

        RawLink payloads = garage.withCustomPayloads(new ValueCodecs.CustomPayloads("OPEN", "SHUT"));
        List<RawRetain.Step> reworded = RawRetain.plan(garage, payloads, INPUT);
        check(reworded.size() == 1 && reworded.getFirst().kind() == RawRetain.Kind.LEVEL, "New payloads: the level in their form: " + reworded);
        check(RawRetain.plan(garage, garage.markResolved(), INPUT).isEmpty(), "A link that keeps topic, retain and payloads publishes nothing");
        List<RawRetain.Step> healed = RawRetain.plan(garage.checked("refused"), garage, INPUT);
        check(healed.size() == 1 && healed.getFirst().kind() == RawRetain.Kind.LEVEL, "A link usable again holds its level: " + healed);
        check(RawRetain.plan(garage, garage.checked("refused"), INPUT).isEmpty(), "A link refused on its topic publishes nothing there");
    }

    /** Retained raw levels go out edge by edge and wait in the outbox while offline; non-retained raw edges are dropped. */
    private static void delivery() {
        Outbound retained = Outbound.rawRetained("mineqtt/shared/garage", ValueCodecs.utf8("true"), 1, STEVE);
        check(retained.retain() && retained.offline() == OfflinePolicy.COALESCE && retained.delivery() == Outbound.Delivery.EDGE
                && STEVE.equals(retained.origin()), "A retained raw level: " + retained);
        Outbound plain = Outbound.raw("mineqtt/shared/garage", ValueCodecs.utf8("true"), 0, STEVE);
        check(!plain.retain() && plain.offline() == OfflinePolicy.DROP && plain.delivery() == Outbound.Delivery.EDGE, "A plain raw edge: " + plain);
        Outbound left = Outbound.rawRetainedFinal("mineqtt/shared/garage", ValueCodecs.utf8("false"), 0, STEVE);
        check(left.retain() && left.offline() == OfflinePolicy.KEEP && left.delivery() == Outbound.Delivery.EDGE,
                "The off payload a topic is left with is never evicted: " + left);
    }

    private static RawLink out(String topic, Boolean retain) {
        return new RawLink(topic, RawLinkSpec.Direction.OUT, null, null, 0, retain, null, false, null, RawTopics.VERSION, topic.substring("mineqtt/shared/".length()), RawLink.Edges.BOTH);
    }

    private static TopicAcl.Subject subject(UUID player) {
        return new TopicAcl.Subject(player, false, false);
    }

    private static CompoundTag encode(RawLink link) {
        Tag tag = RawLink.CODEC.encodeStart(NbtOps.INSTANCE, link).getOrThrow();
        return (CompoundTag) tag;
    }

    private static RawLink decode(Tag tag) {
        return RawLink.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
    }

    private static void expectFailure(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalStateException | IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

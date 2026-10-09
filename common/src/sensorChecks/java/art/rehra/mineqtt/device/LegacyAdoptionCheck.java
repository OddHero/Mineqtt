package art.rehra.mineqtt.device;

import art.rehra.mineqtt.device.types.PresenceSensor;
import art.rehra.mineqtt.device.types.RedstoneInput;
import art.rehra.mineqtt.device.types.RedstoneOutput;
import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.device.types.WallSwitch;
import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.legacy.LegacyTopicStateImport;
import art.rehra.mineqtt.mqtt.BlockStatePersistence;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The attach-time decisions of the v1 lamp migration (design §9.2), for {@code legacy.LegacyNbtV1Test}: v1 kept a
 * lamp's position under every topic it ever had, so the import leaves positions listed under several topics to the
 * lamp's own v1 topic, and a lamp takes over only a record imported for its topic. Then which v1 light a lamp keeps,
 * which legacy group it joins, when the group follows the topic for it, which grandfathered topic joins its owner's
 * literal set, and which lamps a v1 Light Remote adopted by a Wall Switch is wired to (M2 plan D15).
 */
public final class LegacyAdoptionCheck {
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final GlobalPos MOVED = at(1);
    private static final GlobalPos STAYED = at(2);
    private static final GlobalPos PAIR_A = at(5);
    private static final GlobalPos PAIR_B = at(6);
    private static final GlobalPos PORCH = at(8);
    private static final GlobalPos SHED = at(9);

    private LegacyAdoptionCheck() {
    }

    public static void run() {
        DeviceRegistry registry = new DeviceRegistry("world_7f3a", UUID.randomUUID());
        BlockStatePersistence.PersistedData data = new BlockStatePersistence.PersistedData();
        // MOVED shared home/a with STAYED, then moved to home/b; v1 left it listed under both.
        data.topicStates.put("home/a", state(15, 0, 0, key(MOVED), key(STAYED)));
        data.topicStates.put("home/b", state(0, 15, 0, key(MOVED)));
        // Two lamps moved together from hall/y to hall/x: either topic may be theirs.
        data.topicStates.put("hall/x", state(0, 0, 15, key(PAIR_A), key(PAIR_B)));
        data.topicStates.put("hall/y", state(15, 15, 0, key(PAIR_A), key(PAIR_B)));
        data.topicStates.put("porch", state(15, 15, 15, key(PORCH)));
        // A lamp whose new topic never got a state: listed only under its old one.
        data.topicStates.put("shed/old", state(1, 1, 1, key(SHED)));
        List<String> problems = new ArrayList<>();
        int[] imported = LegacyTopicStateImport.importInto(registry, data, problems::add);

        check(imported[0] == 2 && imported[1] == 3, "porch and shed/old are single lamps; home/a, hall/x and hall/y groups: "
                + imported[0] + " and " + imported[1]);
        check(problems.size() == 3 && problems.stream().filter(problem -> problem.contains("listed under 2 topics")).count() == 3,
                "Each position listed under two topics is reported: " + problems);
        check(registry.at(MOVED) == null && registry.at(PAIR_A) == null && registry.at(PAIR_B) == null,
                "A position listed under several topics gets no record; its lamp's own topic decides");

        DeviceGroup homeA = legacyGroup(registry, "home/a");
        DeviceRecord stayed = registry.at(STAYED);
        check(homeA.members().equals(Set.of(stayed.id())) && "home/a".equals(stayed.legacyTopic()) && SmartLamp.TYPE.id().equals(homeA.memberType()),
                "A shared topic stays a group although one of its lamps may have left: " + homeA.members());
        check("home_a".equals(homeA.legacyUniqueId()), "The group keeps the v1 light of the topic");
        check(registry.groups().stream().noneMatch(group -> "home/b".equals(group.legacyTopic()))
                && registry.records().stream().noneMatch(record -> "home/b".equals(record.legacyTopic())), "home/b's only position is ambiguous: nothing is imported");
        DeviceGroup hallX = legacyGroup(registry, "hall/x");
        check(hallX.members().isEmpty() && hallX.memberType() == null && "hall_x".equals(hallX.legacyUniqueId()),
                "A group whose positions are all ambiguous waits for its lamps, without a member type (and so without discovery)");

        DeviceRecord porch = registry.at(PORCH);
        check("porch".equals(porch.legacyTopic()) && "porch".equals(porch.legacyUniqueId()) && Boolean.TRUE.equals(porch.expose()),
                "A single lamp's record knows its v1 topic");
        check(SmartLamp.command(new ValueCodecs.LightState(true, 255, ValueCodecs.Rgb.WHITE, null)).equals(porch.pending()),
                "The file's state waits for its lamp (the file wins over the lamp's own data)");

        takeOver(registry, porch);
        keptLight(registry);
        joining(registry, homeA, hallX);
        settling();
        remoteTargets();
        remoteUndecided(registry, porch, homeA, stayed);
    }

    /**
     * M2 plan D15: the lamps a v1 remote's topic commanded on this server, pure. A loaded lamp of the remote's owner on
     * the topic, each of them, or the topic's legacy group when one of its lamps is the owner's; owners by UUID when both
     * are known, else by the v1 owner name; nothing for another owner, an empty topic or a remote without an owner.
     */
    private static void remoteTargets() {
        DeviceRegistry registry = new DeviceRegistry("world_7f3a", UUID.randomUUID());
        DeviceRecord kitchen = lamp(registry, 20, STEVE);
        DeviceRecord pantry = lamp(registry, 21, STEVE);
        DeviceRecord porch = lamp(registry, 22, ALEX);
        LegacyAdoption.Lamp kitchenLamp = new LegacyAdoption.Lamp(kitchen.id(), STEVE, "Steve", "home/kitchen");
        LegacyAdoption.Lamp porchLamp = new LegacyAdoption.Lamp(porch.id(), ALEX, "Alex", "porch");
        List<LegacyAdoption.Lamp> loaded = List.of(kitchenLamp, porchLamp);

        check(LegacyAdoption.remoteTargets(registry, "home/kitchen", STEVE, "Steve", loaded).equals(List.of(port(kitchen))),
                "A remote on a lamp's topic is wired to that lamp of its owner");
        check(LegacyAdoption.remoteTargets(registry, "porch", STEVE, "Steve", loaded).isEmpty(), "Another owner's lamp on the topic is not wired");
        check(LegacyAdoption.remoteTargets(registry, "home/attic", STEVE, "Steve", loaded).isEmpty(), "A topic no loaded lamp listens on commands nothing");
        check(LegacyAdoption.remoteTargets(registry, "", STEVE, "Steve", loaded).isEmpty(), "A remote without a topic commanded nothing");
        check(LegacyAdoption.remoteTargets(registry, "home/kitchen", null, "", loaded).isEmpty(), "A remote without an owner is wired to nothing");
        // An offline-mode server stores no UUID for a name until its player is seen: the v1 names decide then.
        check(LegacyAdoption.remoteTargets(registry, "home/kitchen", null, "Steve", loaded).equals(List.of(port(kitchen))),
                "The remote's owner name matches the lamp's while its UUID is not known");
        check(LegacyAdoption.remoteTargets(registry, "home/kitchen", STEVE, "Steve",
                        List.of(new LegacyAdoption.Lamp(kitchen.id(), null, "Steve", "home/kitchen"))).equals(List.of(port(kitchen))),
                "The lamp's owner name matches while the lamp's UUID is not known");
        check(LegacyAdoption.remoteTargets(registry, "home/kitchen", ALEX, "Steve", loaded).isEmpty(), "Known UUIDs decide over equal names");
        check(LegacyAdoption.remoteTargets(registry, "home/kitchen", STEVE, "Steve",
                        List.of(kitchenLamp, new LegacyAdoption.Lamp(pantry.id(), STEVE, "Steve", "home/kitchen"))).equals(List.of(port(kitchen), port(pantry))),
                "Every lamp of the owner on the topic is wired, as v1's remote commanded them all");

        // The lamps of a v1 topic state shared by several lamps are its legacy group: the remote is wired to the group.
        DeviceGroup hall = registry.createGroup(UUID.randomUUID(), "Hall");
        hall.setLegacyTopic("hall");
        DeviceRecord hallA = lamp(registry, 23, null);
        DeviceRecord hallB = lamp(registry, 24, null);
        registry.joinGroup(hall, hallA);
        registry.joinGroup(hall, hallB);
        List<LegacyAdoption.Lamp> hallLoaded = List.of(new LegacyAdoption.Lamp(hallA.id(), null, "Steve", "hall"));
        Endpoint group = Endpoint.groupPort(hall.id(), Action.STATE);
        check(LegacyAdoption.remoteTargets(registry, "hall", STEVE, "Steve", hallLoaded).equals(List.of(group)),
                "A loaded lamp of the owner in the topic's legacy group: the group, not its lamps one by one");
        check(LegacyAdoption.remoteTargets(registry, "hall", STEVE, "Steve", List.of()).isEmpty(),
                "Unloaded members whose owner the registry does not know yet: nothing");
        hallB.setOwner(STEVE);
        check(LegacyAdoption.remoteTargets(registry, "hall", STEVE, "Steve", List.of()).equals(List.of(group)),
                "An unloaded member whose record names the owner's UUID");
        check(LegacyAdoption.remoteTargets(registry, "hall", null, "Steve", List.of()).isEmpty(),
                "An unloaded member's owner is known by UUID only");
        check(LegacyAdoption.remoteTargets(registry, "hall", ALEX, "Alex", hallLoaded).isEmpty(), "Another owner's legacy group is not wired");
        DeviceGroup gates = registry.createGroup(UUID.randomUUID(), "Gates");
        gates.setLegacyTopic("gates");
        DeviceRecord gate = new DeviceRecord(UUID.randomUUID(), RedstoneOutput.TYPE.id(), at(25), "Gate", "gate");
        registry.add(gate);
        gate.setOwner(STEVE);
        registry.joinGroup(gates, gate);
        check(LegacyAdoption.remoteTargets(registry, "gates", STEVE, "Steve", List.of()).isEmpty(), "Only a legacy group of lamps: v1 remotes commanded lights");
    }

    /**
     * M2 plan D15 over the imported records, which name no owner until their lamps attach: a remote waits while a lamp it
     * may have commanded has not loaded since v1, or an unloaded lamp's owner cannot be compared by UUID, and is wired or
     * has its v1 commands dropped once that is told. A lamp keeps its v1 topic, so an unloaded one is found by it.
     */
    private static void remoteUndecided(DeviceRegistry registry, DeviceRecord porch, DeviceGroup homeA, DeviceRecord stayed) {
        check("porch".equals(LegacyAdoption.keptV1Topic(SmartLamp.TYPE, "porch")) && "hall".equals(LegacyAdoption.keptV1Topic(WallSwitch.TYPE, "hall"))
                        && LegacyAdoption.keptV1Topic(RedstoneOutput.TYPE, "porch") == null && LegacyAdoption.keptV1Topic(SmartLamp.TYPE, "") == null,
                "Lamps and remotes keep their v1 topic for D15, other types and an empty topic nothing");

        check(LegacyAdoption.remoteTargets(registry, "porch", STEVE, "Steve", List.of()).isEmpty()
                        && LegacyAdoption.remoteUndecided(registry, "porch", STEVE, "Steve", List.of(), List.of()),
                "An imported lamp that has not loaded since v1: nothing yet, and the remote waits for it");
        check(LegacyAdoption.remoteUndecided(registry, "porch", null, "Steve", List.of(), List.of()),
                "Also while the remote's own UUID is not known");
        check(LegacyAdoption.remoteUndecided(registry, "shed/old", STEVE, "Steve", List.of(), List.of()),
                "A single lamp listed only under its old topic is waited for too, until its lamp decides");
        check(!LegacyAdoption.remoteUndecided(registry, "home/b", STEVE, "Steve", List.of(), List.of())
                        && !LegacyAdoption.remoteUndecided(registry, "attic", STEVE, "Steve", List.of(), List.of()),
                "A topic the registry knows no lamp of is decided: nothing is waited for");
        check(!LegacyAdoption.remoteUndecided(registry, "porch", null, "", List.of(), List.of()), "A remote without an owner waits for nothing");

        check(LegacyAdoption.remoteUndecided(registry, "home/a", STEVE, "Steve", List.of(), List.of()),
                "The members of a legacy group that have not loaded since v1 are waited for");
        DeviceRecord second = new DeviceRecord(UUID.randomUUID(), SmartLamp.TYPE.id(), at(41), "Second", "second");
        second.setLegacyTopic("home/a");
        registry.add(second);
        registry.joinGroup(homeA, second);
        List<LegacyAdoption.Lamp> stayedLoaded = List.of(new LegacyAdoption.Lamp(stayed.id(), STEVE, "Steve", "home/a"));
        List<Endpoint> group = LegacyAdoption.remoteTargets(registry, "home/a", STEVE, "Steve", stayedLoaded);
        check(group.equals(List.of(Endpoint.groupPort(homeA.id(), Action.STATE)))
                        && !LegacyAdoption.remoteUndecided(registry, "home/a", STEVE, "Steve", stayedLoaded, group),
                "Once a member of the owner loaded, the group is wired; its other members are not waited for");

        // The porch lamp attaches: it takes the record over and keeps its v1 topic; then its chunk unloads again.
        porch.setLegacyTopic(null);
        porch.setV1Topic("porch");
        check(LegacyAdoption.remoteTargets(registry, "porch", STEVE, "Steve", List.of()).isEmpty()
                        && LegacyAdoption.remoteUndecided(registry, "porch", STEVE, "Steve", List.of(), List.of()),
                "An unloaded lamp whose owner's UUID is not known yet is waited for");
        porch.setOwner(STEVE);
        List<Endpoint> lamp = LegacyAdoption.remoteTargets(registry, "porch", STEVE, "Steve", List.of());
        check(lamp.equals(List.of(port(porch))) && !LegacyAdoption.remoteUndecided(registry, "porch", STEVE, "Steve", List.of(), lamp),
                "An unloaded lamp adopted with the topic, whose record names the owner's UUID, is wired");
        check(LegacyAdoption.remoteTargets(registry, "porch", null, "Steve", List.of()).isEmpty()
                        && LegacyAdoption.remoteUndecided(registry, "porch", null, "Steve", List.of(), List.of()),
                "An unloaded record keeps no owner name: the remote waits for its own UUID");
        check(LegacyAdoption.remoteTargets(registry, "porch", ALEX, "Alex", List.of()).isEmpty()
                        && !LegacyAdoption.remoteUndecided(registry, "porch", ALEX, "Alex", List.of(), List.of()),
                "Another owner's unloaded lamp is decided: the remote's v1 commands are dropped");
        List<LegacyAdoption.Lamp> porchLoaded = List.of(new LegacyAdoption.Lamp(porch.id(), STEVE, "Steve", "porch/new"));
        check(LegacyAdoption.remoteTargets(registry, "porch", STEVE, "Steve", porchLoaded).isEmpty()
                        && !LegacyAdoption.remoteUndecided(registry, "porch", STEVE, "Steve", porchLoaded, List.of()),
                "A loaded lamp is matched by the topic its Raw Link listens on now, not by its v1 topic");
    }

    private static DeviceRecord lamp(DeviceRegistry registry, int x, UUID owner) {
        DeviceRecord record = new DeviceRecord(UUID.randomUUID(), SmartLamp.TYPE.id(), at(x), "Lamp " + x, "lamp_" + x);
        registry.add(record);
        record.setOwner(owner);
        return record;
    }

    private static Endpoint port(DeviceRecord lamp) {
        return Endpoint.port(lamp.id(), Action.STATE);
    }

    /** A lamp takes over only the record imported for its own v1 topic, of its type, that no device holds. */
    private static void takeOver(DeviceRegistry registry, DeviceRecord porch) {
        check(LegacyAdoption.takesOver(porch, SmartLamp.TYPE, "porch", false), "The porch lamp takes over the porch record");
        check(!LegacyAdoption.takesOver(porch, SmartLamp.TYPE, "porch/new", false), "A lamp that left the topic does not");
        check(!LegacyAdoption.takesOver(porch, SmartLamp.TYPE, "porch", true), "Nor does a second lamp while the first is attached");
        check(!LegacyAdoption.takesOver(null, SmartLamp.TYPE, "porch", false), "Nothing is taken over where nothing was imported");
        DeviceRecord shed = registry.at(SHED);
        check(!LegacyAdoption.takesOver(shed, SmartLamp.TYPE, "shed/new", false),
                "A lamp listed only under its old topic does not take that record; it is forgotten as stale");
        DeviceRecord placed = new DeviceRecord(UUID.randomUUID(), SmartLamp.TYPE.id(), at(30), "Lamp", "lamp_30");
        check(!LegacyAdoption.takesOver(placed, SmartLamp.TYPE, "", false), "A device the import did not create is never taken over");
    }

    /** A v1 lamp that takes over no record keeps its topic's v1 light, unless a group or another device holds it. */
    private static void keptLight(DeviceRegistry registry) {
        check("home_b".equals(LegacyAdoption.keptUniqueId(registry, SmartLamp.TYPE, "home/b")), "The moved lamp keeps home/b's light");
        check("shed_new".equals(LegacyAdoption.keptUniqueId(registry, SmartLamp.TYPE, "shed/new")), "So does the lamp with the unlisted topic");
        check(LegacyAdoption.keptUniqueId(registry, SmartLamp.TYPE, "home/a") == null, "A lamp on a legacy group's topic joins the group instead");
        check(LegacyAdoption.keptUniqueId(registry, SmartLamp.TYPE, "porch") == null, "A unique_id another device holds is not taken twice");
        check(LegacyAdoption.keptUniqueId(registry, SmartLamp.TYPE, "") == null, "A lamp without topic had no v1 light");
        check(LegacyAdoption.uniqueIdTaken(registry, "hall_x") && !LegacyAdoption.uniqueIdTaken(registry, "home_b"), "Groups hold unique_ids too");
        check(LegacyAdoption.keptUniqueId(registry, RedstoneOutput.TYPE, "home/b") == null && LegacyAdoption.keptUniqueId(registry, RedstoneInput.TYPE, "home/b") == null
                        && LegacyAdoption.keptUniqueId(registry, PresenceSensor.TYPE, "home/b") == null,
                "v1 emitters, publishers and motion sensors had no Home Assistant light");
    }

    /** Which legacy group a lamp joins, when the group follows the topic for it and when it leaves. */
    private static void joining(DeviceRegistry registry, DeviceGroup homeA, DeviceGroup hallX) {
        DeviceRecord moved = new DeviceRecord(UUID.randomUUID(), SmartLamp.TYPE.id(), MOVED, "Moved", "moved");
        registry.add(moved);
        RawLink onA = link("home/a", true);
        RawLink onB = link("home/b", true);
        check(LegacyAdoption.groupToJoin(registry, moved, onB, SmartLamp.TYPE) == null, "A lamp on home/b joins no group");
        check(LegacyAdoption.groupToJoin(registry, moved, onA, SmartLamp.TYPE) == homeA, "A lamp on home/a joins its legacy group");
        check(LegacyAdoption.groupToJoin(registry, moved, onA.checked("refused"), SmartLamp.TYPE) == null, "A disabled link joins nothing");
        check(LegacyAdoption.groupToJoin(registry, moved, null, SmartLamp.TYPE) == null, "Nor does a lamp without link");
        RawLink onX = link("hall/x", true);
        check(LegacyAdoption.groupToJoin(registry, moved, onX, SmartLamp.TYPE) == hallX, "A group still without members takes its first lamp");
        DeviceRecord output = new DeviceRecord(UUID.randomUUID(), RedstoneOutput.TYPE.id(), at(40), "Output", "output");
        registry.add(output);
        check(LegacyAdoption.groupToJoin(registry, output, link("hall/x", false), RedstoneOutput.TYPE) == null
                        && LegacyAdoption.groupToJoin(registry, output, link("home/a", false), RedstoneOutput.TYPE) == null,
                "A v1 emitter on a lamp topic joins no legacy group, not even one without a member type");
        check(!LegacyAdoption.takesOver(registry.at(PORCH), RedstoneOutput.TYPE, "porch", false), "Nor does it take over a lamp's record");

        check(!LegacyAdoption.followedByGroup(registry, moved, onA), "A lamp in no group follows its topic itself");
        registry.joinGroup(homeA, moved);
        check(LegacyAdoption.groupToJoin(registry, moved, onX, SmartLamp.TYPE) == null, "A lamp in a group joins no other");
        check(LegacyAdoption.followedByGroup(registry, moved, onA), "Its legacy group follows the topic for it: it neither subscribes nor echoes");
        check(!LegacyAdoption.followedByGroup(registry, moved, onB), "A member linked to another topic follows that one itself");
        check(LegacyAdoption.leavesLegacyGroup(homeA, onB) && LegacyAdoption.leavesLegacyGroup(homeA, null), "Linking another topic, or none, leaves the group");
        check(!LegacyAdoption.leavesLegacyGroup(homeA, onA), "Keeping the topic stays");
        DeviceGroup own = registry.createGroup(UUID.randomUUID(), "Own");
        check(!LegacyAdoption.leavesLegacyGroup(own, onB) && !LegacyAdoption.leavesLegacyGroup(null, onB), "Only legacy groups follow a topic");
    }

    /**
     * Which grandfathered v1 topic joins its owner's literal set (M2 plan D1): once, when trusted data's flag is bound
     * to the v1 owner; never for untrusted data or a player's claim, and never for a link that is no v1 topic.
     */
    private static void settling() {
        DeviceRegistry registry = new DeviceRegistry("world_7f3a", UUID.randomUUID());
        RawLink bound = grandfathered("home/garage", STEVE);
        LegacyAdoption.Settlement settled = LegacyAdoption.settleGrandfathered(registry, bound, true);
        check(settled != null && "home/garage".equals(settled.added()) && settled.link().resolved()
                        && settled.link().grandfathered().equals(bound.grandfathered()) && settled.link().channel() == null,
                "A bound v1 topic joins its owner's set, and the link is marked: " + settled);
        check(registry.ownerLiterals(STEVE).equals(Set.of("home/garage")) && registry.ownerLiterals(ALEX).isEmpty(), "Only the owner's set");
        check(LegacyAdoption.settleGrandfathered(registry, settled.link(), true) == null, "It is decided once");
        LegacyAdoption.Settlement again = LegacyAdoption.settleGrandfathered(registry, grandfathered("home/garage", STEVE), true);
        check(again != null && again.added() == null && again.link().resolved(), "A literal the set holds is not added twice: " + again);

        RawLink waiting = grandfathered("home/porch", null);
        check(LegacyAdoption.settleGrandfathered(registry, waiting, true) == null, "A flag waiting for its v1 owner is not decided yet");
        LegacyAdoption.Settlement untrusted = LegacyAdoption.settleGrandfathered(registry, grandfathered("home/shed", ALEX), false);
        check(untrusted != null && untrusted.added() == null && untrusted.link().resolved() && registry.ownerLiterals(ALEX).isEmpty(),
                "Untrusted data (a structure, a lost registry) is marked without adding anything: " + untrusted);
        LegacyAdoption.Settlement unboundUntrusted = LegacyAdoption.settleGrandfathered(registry, waiting, false);
        check(unboundUntrusted != null && unboundUntrusted.link().resolved()
                        && LegacyAdoption.settleGrandfathered(registry, unboundUntrusted.link().withGrandfatherOwner(ALEX), true) == null
                        && registry.ownerLiterals(ALEX).isEmpty(),
                "Nor does it add anything once its owner name resolves later");
        // A claim binds the flag to the claimer and settles it (DeviceLink.claim): nothing joins the claimer's set.
        RawLink claimed = waiting.withGrandfatherOwner(ALEX).markResolved();
        check(LegacyAdoption.settleGrandfathered(registry, claimed, true) == null && registry.ownerLiterals(ALEX).isEmpty(),
                "A claimed device's topic never joins the claimer's set");

        check(LegacyAdoption.settleGrandfathered(registry, null, true) == null
                        && LegacyAdoption.settleGrandfathered(registry, link("home/a", false), true) == null,
                "A link without a grandfather flag has nothing to settle");
        RawTopics.Resolution fromSet = RawTopics.resolve("home/garage", false,
                new TopicAcl(new TopicAcl.Rules("mineqtt", "world_7f3a", "homeassistant", null, List.of("mineqtt/shared/#"), false, false)),
                STEVE, registry.ownerLiterals(STEVE));
        RawLink owned = fromSet.link(RawLinkSpec.Direction.OUT, false);
        check(fromSet.kind() == RawTopics.Kind.OWNER_LITERAL && owned.grandfathered() != null
                        && LegacyAdoption.settleGrandfathered(registry, owned, true) == null,
                "A literal-set link is resolved from its channel, not settled: " + owned);
        RawLink copy = bound.withoutGrandfather();
        check(copy.grandfathered() == null && "home/garage".equals(copy.channel()) && LegacyAdoption.settleGrandfathered(registry, copy, true) == null,
                "A copy of a v1 topic loses the flag and resolves its topic as a channel: " + copy);
    }

    private static RawLink grandfathered(String topic, UUID owner) {
        return new RawLink(topic, RawLinkSpec.Direction.IN, null, null, 0, null, new TopicAcl.Grandfather(topic, owner), true, null, 0, null, RawLink.Edges.BOTH);
    }

    // A link a player's slots set up on a topic that needs no resolving.
    private static RawLink link(String topic, boolean stateEcho) {
        return new RawLink(topic, RawLinkSpec.Direction.IN, null, null, 0, null, null, stateEcho, null, RawTopics.VERSION, topic, RawLink.Edges.BOTH);
    }

    private static DeviceGroup legacyGroup(DeviceRegistry registry, String topic) {
        DeviceGroup group = LegacyAdoption.legacyGroup(registry, topic, SmartLamp.TYPE);
        check(group != null, "The legacy group of " + topic);
        return group;
    }

    private static BlockStatePersistence.TopicState state(int red, int green, int blue, String... positions) {
        BlockStatePersistence.TopicState state = new BlockStatePersistence.TopicState(red, green, blue, 255, true);
        state.blockPositions = new ArrayList<>(List.of(positions));
        return state;
    }

    private static GlobalPos at(int x) {
        return GlobalPos.of(Level.OVERWORLD, new BlockPos(x, 64, 0));
    }

    private static String key(GlobalPos pos) {
        return "minecraft:overworld:" + pos.pos().getX() + ":" + pos.pos().getY() + ":" + pos.pos().getZ();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

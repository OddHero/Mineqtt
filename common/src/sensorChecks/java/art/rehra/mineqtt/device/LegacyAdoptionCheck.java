package art.rehra.mineqtt.device;

import art.rehra.mineqtt.device.types.PresenceSensor;
import art.rehra.mineqtt.device.types.RedstoneInput;
import art.rehra.mineqtt.device.types.RedstoneOutput;
import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.legacy.LegacyTopicStateImport;
import art.rehra.mineqtt.mqtt.BlockStatePersistence;
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
 * which legacy group it joins, and when the group follows the topic for it.
 */
public final class LegacyAdoptionCheck {
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
        RawLink onA = RawLink.of("home/a", RawLinkSpec.Direction.IN, true);
        RawLink onB = RawLink.of("home/b", RawLinkSpec.Direction.IN, true);
        check(LegacyAdoption.groupToJoin(registry, moved, onB, SmartLamp.TYPE) == null, "A lamp on home/b joins no group");
        check(LegacyAdoption.groupToJoin(registry, moved, onA, SmartLamp.TYPE) == homeA, "A lamp on home/a joins its legacy group");
        check(LegacyAdoption.groupToJoin(registry, moved, onA.checked("refused"), SmartLamp.TYPE) == null, "A disabled link joins nothing");
        check(LegacyAdoption.groupToJoin(registry, moved, null, SmartLamp.TYPE) == null, "Nor does a lamp without link");
        RawLink onX = RawLink.of("hall/x", RawLinkSpec.Direction.IN, true);
        check(LegacyAdoption.groupToJoin(registry, moved, onX, SmartLamp.TYPE) == hallX, "A group still without members takes its first lamp");
        DeviceRecord output = new DeviceRecord(UUID.randomUUID(), RedstoneOutput.TYPE.id(), at(40), "Output", "output");
        registry.add(output);
        check(LegacyAdoption.groupToJoin(registry, output, RawLink.of("hall/x", RawLinkSpec.Direction.IN, false), RedstoneOutput.TYPE) == null
                        && LegacyAdoption.groupToJoin(registry, output, RawLink.of("home/a", RawLinkSpec.Direction.IN, false), RedstoneOutput.TYPE) == null,
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

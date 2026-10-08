package art.rehra.mineqtt.legacy;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.device.DeviceGroup;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceRegistry;
import art.rehra.mineqtt.device.LegacyAdoption;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.mqtt.BlockStatePersistence;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Imports the RGB LED states v1 kept per topic in {@code mineqtt_topic_states.json} (design §9.2), once per world and
 * before the first connect, so the states v1 treated as authoritative win over the lamps' own data:
 * <ul>
 *     <li>A topic with one lamp becomes that lamp's record, which keeps the v1 light's unique_id and is exposed.</li>
 *     <li>A topic with several lamps becomes the legacy group {@code legacy:<topic>}: one Home Assistant light with the
 *     v1 unique_id, following the topic once for all members, with one record per lamp, hidden behind the group.</li>
 * </ul>
 * Each record holds the topic's state as queued command, which the lamp applies when it attaches and adopts the record
 * (matched by position and v1 topic); until then it is published from the record. v1 never took a lamp's position off
 * the topics it left, so a position listed under several topics gets no record: its lamp's own v1 topic decides when it
 * loads (design §9.2's rule for lamps the file does not list). The file is never written, renamed or deleted.
 */
public final class LegacyTopicStateImport {
    private LegacyTopicStateImport() {
    }

    /** {@code SERVER_STARTED}, after the registry loaded and before MQTT connects. */
    public static void run(MinecraftServer server, DeviceRegistry registry) {
        if (registry.legacyImportDone()) return;
        Path directory = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(MineQTT.MOD_ID);
        // An unreadable file is tried again at the next start.
        Optional<BlockStatePersistence.PersistedData> data = BlockStatePersistence.load(directory);
        if (data.isEmpty()) return;
        int[] imported = importInto(registry, data.get(), problem -> MineQTT.LOGGER.warn("v1 topic states: {}", problem));
        if (imported[0] + imported[1] > 0) {
            MineQTT.LOGGER.info("Imported the v1 lamp states: {} lamps of their own, {} legacy groups", imported[0], imported[1]);
        }
    }

    /**
     * Creates the records and groups of the v1 topic states and marks the import done.
     *
     * @param problems receives one message per topic or position that is skipped
     * @return the number of single-lamp records and of legacy groups created
     */
    public static int[] importInto(DeviceRegistry registry, BlockStatePersistence.PersistedData data, Consumer<String> problems) {
        // Sorted, so a world imports the same way on every server.
        Map<String, List<GlobalPos>> listed = new LinkedHashMap<>();
        Map<GlobalPos, Integer> listings = new LinkedHashMap<>();
        for (Map.Entry<String, BlockStatePersistence.TopicState> entry : new TreeMap<>(data.topicStates).entrySet()) {
            String topic = entry.getKey();
            BlockStatePersistence.TopicState state = entry.getValue();
            if (topic == null || topic.isBlank() || state == null) {
                problems.accept("skipped a topic without a name or a state");
                continue;
            }
            List<GlobalPos> positions = positions(registry, topic, state, problems);
            listed.put(topic, positions);
            positions.forEach(pos -> listings.merge(pos, 1, Integer::sum));
        }
        listings.forEach((pos, count) -> {
            if (count > 1) {
                problems.accept("the position " + pos.dimension().identifier() + " " + pos.pos().toShortString() + " is listed under " + count
                        + " topics; its lamp's own topic decides when it loads");
            }
        });
        int singles = 0;
        int groups = 0;
        for (Map.Entry<String, List<GlobalPos>> entry : listed.entrySet()) {
            String topic = entry.getKey();
            List<GlobalPos> positions = entry.getValue();
            if (positions.isEmpty()) continue;
            // v1 never took a lamp's position off the topics it left, so a position listed under several topics gets no
            // record: its lamp joins the legacy group of its own topic, or keeps its v1 light, when it loads. It still
            // counts here, so a shared topic stays a group.
            List<GlobalPos> own = positions.stream().filter(pos -> listings.get(pos) == 1).toList();
            if (positions.size() == 1 && own.isEmpty()) continue;
            BlockStatePersistence.TopicState state = data.topicStates.get(topic);
            ValueCodecs.LightState light = new ValueCodecs.LightState(state.lit, state.brightness, new ValueCodecs.Rgb(
                    LegacyNbtV1.to8Bit(state.targetRed), LegacyNbtV1.to8Bit(state.targetGreen), LegacyNbtV1.to8Bit(state.targetBlue)), null);
            String uniqueId = LegacyNbtV1.legacyUniqueId(topic);
            boolean uniqueIdFree = !LegacyAdoption.uniqueIdTaken(registry, uniqueId);
            if (!uniqueIdFree) problems.accept("topic " + topic + " has the same v1 unique_id as another topic; it gets a new Home Assistant light");
            String name = LegacyNbtV1.topicName(topic);
            if (positions.size() == 1) {
                DeviceRecord record = lamp(registry, own.getFirst(), topic, name, light);
                if (uniqueIdFree) record.setLegacyUniqueId(uniqueId);
                record.setExpose(true);
                singles++;
            } else {
                // Without a member of its own the group has no member type yet; it stays hidden until a lamp joins.
                DeviceGroup group = registry.createGroup(UUID.randomUUID(), name.isEmpty() ? SmartLamp.TYPE.model() : name);
                group.setLegacyTopic(topic);
                if (uniqueIdFree) group.setLegacyUniqueId(uniqueId);
                group.setState(SmartLamp.command(light));
                for (GlobalPos pos : own) {
                    registry.joinGroup(group, lamp(registry, pos, topic, "", light));
                }
                groups++;
            }
        }
        registry.setLegacyImportDone();
        return new int[]{singles, groups};
    }

    // A lamp's record for its v1 topic, with the topic's state queued and shown until the lamp attaches.
    private static DeviceRecord lamp(DeviceRegistry registry, GlobalPos pos, String topic, String name, ValueCodecs.LightState light) {
        UUID id = UUID.randomUUID();
        DeviceRecord record = name.isEmpty()
                ? new DeviceRecord(id, SmartLamp.TYPE.id(), pos, SmartLamp.TYPE.defaultName(id), registry.uniqueSlug(SmartLamp.TYPE.defaultSlug(id), id))
                : new DeviceRecord(id, SmartLamp.TYPE.id(), pos, name, registry.uniqueSlug(name, id));
        record.setLegacyTopic(topic);
        record.setLastState(SmartLamp.state(light, pos));
        record.setPending(SmartLamp.command(light));
        registry.add(record);
        return record;
    }

    private static List<GlobalPos> positions(DeviceRegistry registry, String topic, BlockStatePersistence.TopicState state, Consumer<String> problems) {
        Set<GlobalPos> positions = new LinkedHashSet<>();
        if (state.blockPositions == null) return List.of();
        for (String key : state.blockPositions) {
            Optional<GlobalPos> pos = key == null ? Optional.empty() : BlockStatePersistence.parseBlockPositionKey(key);
            if (pos.isEmpty()) {
                problems.accept("skipped the position '" + key + "' of topic " + topic + ": not a position");
            } else if (registry.at(pos.get()) != null) {
                problems.accept("skipped the position " + key + " of topic " + topic + ": a device is registered there");
            } else {
                positions.add(pos.get());
            }
        }
        return new ArrayList<>(positions);
    }
}

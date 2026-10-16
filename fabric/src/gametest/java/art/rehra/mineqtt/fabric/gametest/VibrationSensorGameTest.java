package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.blocks.entities.VibrationSensorBlockEntity;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.RawLink;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.VibrationSensor;
import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The Vibration Sensor. Set by command, it has heard nothing (defaults all, range 8, clear delay 10 s). A door that
 * redstone opens 4 blocks away is a {@code door} vibration of frequency 10 caused by the world, 4 blocks off, and a
 * comparator reads 10 until vibration clears a second later (clear delay 1). A dropped item landing is an {@code item}
 * vibration of an entity, a cow landing a {@code step}; a wool block between the sensor and a landing item keeps it
 * silent. The player's own block placing is a {@code block} vibration of a {@code player}, without a distance and without
 * their name or UUID anywhere in the state; {@code detect players} hears only that, {@code detect non_players} not that.
 * With a broker: each group's event reaches the action topic at most once a second, a player's event names nobody, the
 * Raw Link carries the frequency, and nothing is retained there.
 */
public final class VibrationSensorGameTest implements FabricClientGameTest {
    private static final int Y = GameTestSession.GROUND_Y + 1;
    private static final BlockPos SENSOR = new BlockPos(4, Y, 6);
    // Three blocks west: within hearing, and the block the player places lands right beside the sensor.
    private static final BlockPos STAND = SENSOR.west(3);
    private static final BlockPos PLACE_ON = SENSOR.west().below();
    private static final BlockPos DOOR = SENSOR.east(4);
    private static final BlockPos POWER = DOOR.east();
    private static final BlockPos DROP = SENSOR.south(5);
    private static final BlockPos MUFFLED_DROP = SENSOR.north(5);
    private static final BlockPos WOOL = SENSOR.north(2);
    private static final String CHANNEL = "vibration_sensor_" + GameTestSession.BRIDGE_ID;
    private static final String RAW_TOPIC = "mineqtt/shared/" + CHANNEL;
    private static final int ANSWER_TICKS = 80;
    // Long enough for a vibration to travel 16 blocks and the sensor to rest, so "nothing heard" means it.
    private static final int SILENCE_TICKS = 50;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "vibration_sensor", VibrationSensorGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        // The server resends an unconfirmed teleport after 20 ticks; the player stands still before the first vibration.
        session.context().waitTicks(25);
        String slug = sensor(session);
        // Everything but the player first, so the player's own landing after a teleport cannot get in between.
        session.consoleCommand("mineqtt devices setting " + slug + " detect non_players");
        session.consoleCommand("mineqtt devices setting " + slug + " clear_delay 1");
        session.check(session.await(() -> state(session).get("detect").getAsString().equals("non_players") && state(session).get("clear_delay").getAsInt() == 1,
                ANSWER_TICKS), "detect and clear delay are set (" + state(session) + ")");
        door(session);
        landings(session);
        wool(session);
        player(session, slug);
        Optional<BrokerConfig> broker = session.broker();
        if (broker.isEmpty()) {
            session.skip("the sensor's events and Raw Link payloads reach the broker", "no broker (" + GameTestSession.BROKER_ENV + " is not set)");
        } else {
            published(session, slug, broker.get());
        }
    }

    /** Set by command: a Vibration Sensor device that has heard nothing, with the defaults. */
    private static String sensor(GameTestSession session) {
        setblock(session, SENSOR, "mineqtt:vibration_sensor");
        session.awaitServer(s -> s.overworld().getBlockEntity(SENSOR) instanceof DeviceHost host && host.deviceLink().attached());
        session.check(session.onServer(s -> {
            var blockEntity = s.overworld().getBlockEntity(SENSOR);
            return ((DeviceHost) blockEntity).deviceLink().claim(blockEntity, session.playerId(), session.playerName());
        }), "the player claims the sensor");
        session.check(VibrationSensor.TYPE.equals(session.device(SENSOR, DeviceLink::type)), "a placed Vibration Sensor block is a Vibration Sensor device");
        JsonObject state = state(session);
        session.check(!state.get("vibration").getAsBoolean() && state.get("count").getAsInt() == 0 && state.get("frequency").getAsInt() == 0
                        && state.get("last_event").getAsString().isEmpty() && state.get("detect").getAsString().equals("all")
                        && state.get("range").getAsInt() == 8 && state.get("clear_delay").getAsInt() == 10,
                "it has heard nothing; the defaults are all, range 8 and clear delay 10 (" + state + ")");
        return session.device(SENSOR, DeviceLink::slug);
    }

    /**
     * A door that redstone opens: a door vibration of frequency 10 that nobody caused, 4 blocks away; a comparator reads
     * 10 until vibration clears.
     */
    private static void door(GameTestSession session) {
        setblock(session, DOOR, "minecraft:oak_door[half=lower,facing=west]");
        setblock(session, DOOR.above(), "minecraft:oak_door[half=upper,facing=west]");
        session.context().waitTicks(SILENCE_TICKS);
        int before = count(session);
        setblock(session, POWER, "minecraft:redstone_block");
        session.check(session.await(() -> heard(session, before, "minecraft:block_open"), ANSWER_TICKS),
                "the sensor hears the door open (" + state(session) + ")");
        JsonObject state = state(session);
        session.check(state.get("vibration").getAsBoolean() && state.get("last_group").getAsString().equals("door") && state.get("frequency").getAsInt() == 10
                        && state.get("last_source").getAsString().equals("world") && state.get("last_distance").getAsDouble() == 4.0,
                "a door vibration of frequency 10, caused by the world, 4 blocks away (" + state + ")");
        session.check(comparator(session) == 10, "a comparator reads the frequency while vibration is on (" + comparator(session) + ")");
        session.aim(SENSOR);
        session.screenshot("vibration_sensor_door");
        session.check(session.await(() -> !state(session).get("vibration").getAsBoolean(), ANSWER_TICKS),
                "vibration clears a second after the last vibration (" + state(session) + ")");
        session.check(comparator(session) == 0 && state(session).get("frequency").getAsInt() == 10, "then the comparator reads 0, the last frequency stays");
        setblock(session, POWER, "minecraft:air");
        session.context().waitTicks(SILENCE_TICKS);
    }

    /** A dropped item landing is an item vibration of an entity; a cow landing is a step. */
    private static void landings(GameTestSession session) {
        int before = count(session);
        session.consoleCommand("summon minecraft:item " + (DROP.getX() + 0.5) + " " + (DROP.getY() + 3) + " " + (DROP.getZ() + 0.5)
                + " {Item:{id:\"minecraft:stone\",count:1},PickupDelay:32767}");
        session.check(session.await(() -> heard(session, before, "minecraft:hit_ground"), ANSWER_TICKS), "the sensor hears the item land (" + state(session) + ")");
        JsonObject item = state(session);
        session.check(item.get("last_group").getAsString().equals("item") && item.get("last_source").getAsString().equals("entity")
                        && item.get("frequency").getAsInt() == 2 && item.get("last_distance").getAsDouble() > 4 && item.get("last_distance").getAsDouble() < 6,
                "a dropped item landing is an item vibration of an entity, about 5 blocks away (" + item + ")");
        clearItems(session);
        session.context().waitTicks(SILENCE_TICKS);

        int beforeCow = count(session);
        session.consoleCommand("summon minecraft:cow " + (DROP.getX() + 0.5) + " " + (DROP.getY() + 3) + " " + (DROP.getZ() + 0.5) + " {Silent:1b}");
        session.check(session.await(() -> heard(session, beforeCow, "minecraft:hit_ground") && state(session).get("last_group").getAsString().equals("step"),
                ANSWER_TICKS), "a cow landing is a step (" + state(session) + ")");
        session.check(state(session).get("last_source").getAsString().equals("entity"), "of an entity");
        session.consoleCommand("tp @e[type=minecraft:cow] " + SENSOR.getX() + " -200 " + SENSOR.getZ());
        session.consoleCommand("kill @e[type=minecraft:cow]");
        clearItems(session);
        session.context().waitTicks(SILENCE_TICKS);
    }

    /** Wool between the sensor and a landing item keeps it silent, as it does a sculk sensor; without the wool it hears it. */
    private static void wool(GameTestSession session) {
        setblock(session, WOOL, "minecraft:white_wool");
        session.context().waitTicks(SILENCE_TICKS);
        int before = count(session);
        dropAt(session, MUFFLED_DROP);
        session.context().waitTicks(SILENCE_TICKS);
        session.check(count(session) == before, "behind wool, the landing item is not heard (" + state(session) + ")");
        clearItems(session);
        setblock(session, WOOL, "minecraft:air");
        session.context().waitTicks(SILENCE_TICKS);
        int open = count(session);
        dropAt(session, MUFFLED_DROP);
        session.check(session.await(() -> heard(session, open, "minecraft:hit_ground"), ANSWER_TICKS), "without the wool it is (" + state(session) + ")");
        clearItems(session);
        session.context().waitTicks(SILENCE_TICKS);
    }

    /**
     * The player's own block placing: a block vibration of a player, without a distance, and the state never names them.
     * detect players hears it and no dropped item; detect non_players does not hear it.
     */
    private static void player(GameTestSession session, String slug) {
        session.consoleCommand("mineqtt devices setting " + slug + " detect players");
        session.check(session.await(() -> state(session).get("detect").getAsString().equals("players"), ANSWER_TICKS), "detect players is set");
        session.context().waitTicks(SILENCE_TICKS);
        int before = count(session);
        BlockPos placed = session.place(Blocks.STONE, PLACE_ON);
        session.check(session.await(() -> heard(session, before, "minecraft:block_place"), ANSWER_TICKS),
                "the sensor hears the player place a block beside it (" + state(session) + ")");
        JsonObject state = state(session);
        session.check(state.get("last_group").getAsString().equals("block") && state.get("last_source").getAsString().equals("player")
                && state.get("frequency").getAsInt() == 13 && state.get("last_distance").getAsDouble() == 0, "a block vibration of a player, without a distance (" + state + ")");
        anonymous(session, state.toString());
        setblock(session, placed, "minecraft:air");
        session.context().waitTicks(SILENCE_TICKS);

        int beforeItem = count(session);
        dropAt(session, DROP);
        session.context().waitTicks(SILENCE_TICKS);
        session.check(count(session) == beforeItem, "detecting players, a dropped item is not heard (" + state(session) + ")");
        clearItems(session);

        session.consoleCommand("mineqtt devices setting " + slug + " detect non_players");
        session.check(session.await(() -> state(session).get("detect").getAsString().equals("non_players"), ANSWER_TICKS), "detect non_players is set");
        session.context().waitTicks(SILENCE_TICKS);
        int beforeOwn = count(session);
        BlockPos again = session.place(Blocks.STONE, PLACE_ON);
        session.context().waitTicks(SILENCE_TICKS);
        session.check(count(session) == beforeOwn, "detecting everything but players, the player's block is not heard (" + state(session) + ")");
        setblock(session, again, "minecraft:air");
        session.hold(Items.AIR);
        session.consoleCommand("mineqtt devices setting " + slug + " detect all");
        session.context().waitTicks(SILENCE_TICKS);
    }

    /**
     * With a broker: the door's events reach the action topic, a second within a second is held back while the state still
     * counts it, a player's event names nobody, the Raw Link carries each published vibration's frequency, unretained.
     */
    private static void published(GameTestSession session, String slug, BrokerConfig broker) {
        session.clearAtClose(RAW_TOPIC);
        session.consoleCommand("mineqtt devices raw " + slug + " channel " + CHANNEL);
        RawLink raw = session.device(SENSOR, DeviceLink::raw);
        session.check(raw != null && RAW_TOPIC.equals(raw.topic()) && raw.active(), "its Raw Link channel resolves to " + RAW_TOPIC + " (" + raw + ")");
        String stateTopic = "mineqtt/" + GameTestSession.BRIDGE_ID + "/" + slug;
        // The Raw Link is read through a probe of its own: one probe's listeners share its stream of messages, and the
        // action listener's reads would take the Raw Link payloads that arrive with the events.
        try (BrokerProbe probe = BrokerProbe.connect(broker);
             BrokerProbe.Listener actions = probe.listen(stateTopic + "/action");
             BrokerProbe rawProbe = BrokerProbe.connect(broker);
             BrokerProbe.Listener rawPayloads = rawProbe.listen(RAW_TOPIC)) {
            session.consoleCommand("mineqtt devices setting " + slug + " detect non_players");
            session.context().waitTicks(SILENCE_TICKS);
            drain(actions);
            drain(rawPayloads);
            int before = count(session);
            setblock(session, POWER, "minecraft:redstone_block");
            session.check(session.await(() -> heard(session, before, "minecraft:block_open"), ANSWER_TICKS), "the door opens again (" + state(session) + ")");
            long opened = System.nanoTime();
            // Past the sensor's rest, within the door group's second: heard and counted, not published.
            session.context().waitTicks(VibrationSensor.COOLDOWN_TICKS + 1);
            setblock(session, POWER, "minecraft:air");
            session.check(session.await(() -> heard(session, before + 1, "minecraft:block_close"), ANSWER_TICKS), "and closes (" + state(session) + ")");
            long gapMillis = (System.nanoTime() - opened) / 1_000_000;
            session.context().waitTicks(25);
            List<JsonObject> doorEvents = events(actions);
            List<String> payloads = texts(rawPayloads);
            session.check(!doorEvents.isEmpty() && doorEvents.getFirst().toString().equals(
                            "{\"event_type\":\"door\",\"frequency\":10,\"game_event\":\"minecraft:block_open\",\"source\":\"world\"}"),
                    "the door's opening is a door event (" + doorEvents + ")");
            session.check(!payloads.isEmpty() && payloads.getFirst().equals("10") && payloads.size() == doorEvents.size(),
                    "the Raw Link carries each published vibration's frequency (" + payloads + ")");
            // The wall clock decides the throttle; a server too slow to close the door within the second is no failure.
            if (gapMillis < 800) {
                session.check(doorEvents.size() == 1, "the close " + gapMillis + " ms later is held back by the group's second (" + doorEvents + ")");
            } else {
                session.info("the close came " + gapMillis + " ms after the opening, too late to be held back; verifyVibrationSensor checks the throttle");
            }
            session.check(probe.retained(RAW_TOPIC).isEmpty(), "nothing is retained on its Raw Link topic");

            session.consoleCommand("mineqtt devices setting " + slug + " detect all");
            session.context().waitTicks(SILENCE_TICKS);
            drain(actions);
            int beforePlace = count(session);
            BlockPos placed = session.place(Blocks.STONE, PLACE_ON);
            session.check(session.await(() -> heard(session, beforePlace, "minecraft:block_place"), ANSWER_TICKS), "the player places a block (" + state(session) + ")");
            session.context().waitTicks(25);
            Optional<JsonObject> playerEvent = events(actions).stream().filter(event -> event.get("game_event").getAsString().equals("minecraft:block_place"))
                    .findFirst();
            session.check(playerEvent.filter(event -> event.keySet().equals(Set.of("event_type", "frequency", "game_event", "source"))
                            && event.get("event_type").getAsString().equals("block") && event.get("source").getAsString().equals("player")).isPresent(),
                    "the player's event is a block vibration of a player, with nothing else (" + playerEvent + ")");
            playerEvent.ifPresent(event -> anonymous(session, event.toString()));
            session.context().waitTicks(25);
            anonymous(session, probe.retained(stateTopic).orElse(""));
            setblock(session, placed, "minecraft:air");
            session.hold(Items.AIR);
        }
    }

    // Nothing that tells who the player is: neither their name nor their UUID.
    private static void anonymous(GameTestSession session, String published) {
        session.check(!published.contains(session.playerName()) && !published.contains(session.playerId().toString()),
                "the player is anonymous in " + published);
    }

    private static void dropAt(GameTestSession session, BlockPos pos) {
        session.consoleCommand("summon minecraft:item " + (pos.getX() + 0.5) + " " + (pos.getY() + 3) + " " + (pos.getZ() + 0.5)
                + " {Item:{id:\"minecraft:stone\",count:1},PickupDelay:32767}");
    }

    private static void clearItems(GameTestSession session) {
        session.consoleCommand("kill @e[type=minecraft:item]");
        session.consoleCommand("kill @e[type=minecraft:experience_orb]");
    }

    // Whether the sensor heard another vibration of this game event since it had counted `before`.
    private static boolean heard(GameTestSession session, int before, String gameEvent) {
        JsonObject state = state(session);
        return state.get("count").getAsInt() > before && state.get("last_event").getAsString().equals(gameEvent);
    }

    private static int count(GameTestSession session) {
        return state(session).get("count").getAsInt();
    }

    // What a comparator beside the sensor reads.
    private static int comparator(GameTestSession session) {
        return session.onServer(s -> {
            BlockState state = s.overworld().getBlockState(SENSOR);
            //? if >=1.21.9 {
            int signal = state.getAnalogOutputSignal(s.overworld(), SENSOR, Direction.NORTH);
            //?} else {
            /*int signal = state.getAnalogOutputSignal(s.overworld(), SENSOR);
            *///?}
            boolean same = s.overworld().getBlockEntity(SENSOR) instanceof VibrationSensorBlockEntity sensor && sensor.comparatorLevel() == signal;
            return same ? signal : -1;
        });
    }

    private static void drain(BrokerProbe.Listener listener) {
        while (listener.nextLive().isPresent()) {
            // Only what comes after counts.
        }
    }

    private static List<JsonObject> events(BrokerProbe.Listener listener) {
        List<JsonObject> events = new ArrayList<>();
        for (String text : texts(listener)) events.add(JsonParser.parseString(text).getAsJsonObject());
        return events;
    }

    private static List<String> texts(BrokerProbe.Listener listener) {
        List<String> texts = new ArrayList<>();
        Optional<String> message;
        while ((message = listener.nextLive()).isPresent()) texts.add(message.get());
        return texts;
    }

    private static void setblock(GameTestSession session, BlockPos pos, String block) {
        session.consoleCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block);
    }

    private static JsonObject state(GameTestSession session) {
        return session.onServer(s -> {
            StateWriter state = new StateWriter();
            if (s.overworld().getBlockEntity(SENSOR) instanceof DeviceHost host) host.writeState(state);
            return state.json();
        });
    }
}

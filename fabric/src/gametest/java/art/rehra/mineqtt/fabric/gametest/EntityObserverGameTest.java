package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.config.TelemetryPlayers;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.RawLink;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.EntityObserver;
import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The Entity Observer. Set facing east, it reports nothing while the space in front is empty; a named cow there is its
 * entity with its name, health, maximum health, distance and details, a baby cow beside it raises the count, and both
 * gone leave it empty again. The player in front is counted as a player and shown by the player type's name only: as the
 * integrated host they share presence, which names nobody at a device; with {@code /mineqtt privacy share full} on a
 * server that shares full data their name shows, and with {@code share off} it goes again; never health, distance or
 * details. Its Raw Link's full payload (with a broker) carries a tamed wolf's entity data without the owner's UUID, no
 * entity data or position for the player, and none at all while {@code entityObserver.exportEntityData} is off.
 */
public final class EntityObserverGameTest implements FabricClientGameTest {
    private static final int Y = GameTestSession.GROUND_Y + 1;
    private static final BlockPos OBSERVER = new BlockPos(4, Y, 6);
    // Within reach of the observer, behind it from the space it watches.
    private static final BlockPos STAND = OBSERVER.west(2);
    private static final BlockPos FRONT = OBSERVER.east();
    private static final String CHANNEL = "entity_observer_" + GameTestSession.BRIDGE_ID;
    private static final String RAW_TOPIC = "mineqtt/shared/" + CHANNEL;
    private static final int ANSWER_TICKS = 60;
    // The summoned mobs have fixed UUIDs, so the console and the checks find them on every version.
    private static final UUID COW = UUID.fromString("e0b5e7a1-0000-4000-8000-00000000c0e1");
    private static final UUID CALF = UUID.fromString("e0b5e7a1-0000-4000-8000-00000000ca1f");
    private static final UUID WOLF = UUID.fromString("e0b5e7a1-0000-4000-8000-0000000000f0");

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "entity_observer", EntityObserverGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        String slug = observer(session);
        mobs(session);
        player(session, slug);
        Optional<BrokerConfig> broker = session.broker();
        if (broker.isEmpty()) {
            session.skip("the observer's Raw Link payloads reach the broker", "no broker (" + GameTestSession.BROKER_ENV + " is not set)");
        } else {
            payloads(session, slug, broker.get());
        }
    }

    /** Set facing east with nothing in front: no entity, not present, a count of 0. */
    private static String observer(GameTestSession session) {
        setblock(session, OBSERVER, "mineqtt:entity_observer[facing=east]");
        session.awaitServer(s -> s.overworld().getBlockEntity(OBSERVER) instanceof DeviceHost host && host.deviceLink().attached());
        session.check(session.onServer(s -> {
            BlockEntity blockEntity = s.overworld().getBlockEntity(OBSERVER);
            return ((DeviceHost) blockEntity).deviceLink().claim(blockEntity, session.playerId(), session.playerName());
        }), "the player claims the observer");
        session.check(EntityObserver.TYPE.equals(session.device(OBSERVER, DeviceLink::type)), "a placed Entity Observer block is an Entity Observer device");
        session.context().waitTicks(EntityObserver.SCAN_INTERVAL * 2);
        JsonObject state = state(session);
        session.check(!state.get("present").getAsBoolean() && state.get("entity").getAsString().isEmpty() && state.get("count").getAsInt() == 0
                        && state.get("detect").getAsString().equals("all") && state.get("range").getAsInt() == 1 && state.get("payload").getAsString().equals("full"),
                "with nothing in front it reports nothing; the defaults are all, range 1 and full (" + state + ")");
        return session.device(OBSERVER, DeviceLink::slug);
    }

    /** A named cow in front with its health and details; a baby cow beside it raises the count; both gone, nothing. */
    private static void mobs(GameTestSession session) {
        summon(session, "minecraft:cow", FRONT.getX() + 0.3, COW, "");
        session.onServer(s -> {
            Entity cow = find(s.overworld(), COW).orElseThrow();
            cow.setCustomName(Component.literal("Daisy"));
            ((LivingEntity) cow).setHealth(7);
            return null;
        });
        session.check(session.await(() -> state(session).get("entity").getAsString().equals("minecraft:cow") && state(session).get("entity_name").getAsString().equals("Daisy")
                && state(session).get("health").getAsDouble() == 7, ANSWER_TICKS), "a named cow in front is its entity (" + state(session) + ")");
        JsonObject state = state(session);
        JsonObject details = state.getAsJsonObject("details");
        session.check(state.get("present").getAsBoolean() && state.get("count").getAsInt() == 1 && state.get("living").getAsBoolean()
                        && state.get("max_health").getAsDouble() == 10 && state.get("distance").getAsDouble() < 1.5,
                "present, one entity, living, health 7 of 10, under a block and a half from the face (" + state + ")");
        session.check(details.get("custom_name").getAsString().equals("Daisy") && !details.get("baby").getAsBoolean() && details.get("armor").getAsInt() == 0
                && !details.get("on_fire").getAsBoolean() && details.get("passengers").getAsInt() == 0, "its details are the attributes (" + details + ")");

        summon(session, "minecraft:cow", FRONT.getX() + 0.8, CALF, ",Age:-24000");
        session.check(session.await(() -> state(session).get("count").getAsInt() == 2, ANSWER_TICKS), "a calf beside it makes two (" + state(session) + ")");
        session.check(state(session).get("entity_name").getAsString().equals("Daisy"), "the nearer cow stays the one reported");
        session.aim(OBSERVER);
        session.screenshot("entity_observer_cows");

        remove(session, COW);
        session.check(session.await(() -> state(session).get("count").getAsInt() == 1 && state(session).getAsJsonObject("details").get("baby").getAsBoolean(), ANSWER_TICKS),
                "with the cow gone the calf is the entity (" + state(session) + ")");
        remove(session, CALF);
        session.check(session.await(() -> !state(session).get("present").getAsBoolean() && state(session).get("entity").getAsString().isEmpty(), ANSWER_TICKS),
                "with both gone it reports nothing again (" + state(session) + ")");
    }

    /**
     * The player in front is counted and reported as a player. As the integrated host they share presence, which names
     * nobody at a device; with full sharing on a full server their name shows; never health, distance or details.
     */
    private static void player(GameTestSession session, String slug) {
        TelemetryPlayers mode = session.onServer(s -> MineQTTConfig.telemetryPlayers);
        try {
            session.teleport(FRONT);
            String typeName = session.onServer(s -> session.player(s).getType().getDescription().getString());
            session.check(session.await(() -> state(session).get("entity").getAsString().equals(EntityObserver.PLAYER), ANSWER_TICKS),
                    "the player in front is a player (" + state(session) + ")");
            JsonObject state = state(session);
            session.check(state.get("entity_name").getAsString().equals(typeName) && !state.toString().contains(session.playerName()),
                    "as the host sharing presence only, the player is not named (" + state + ")");
            noPersonalData(session, state);

            session.onServer(s -> MineQTTConfig.telemetryPlayers = TelemetryPlayers.FULL);
            session.playerCommand("mineqtt privacy share full");
            session.check(session.await(() -> state(session).get("entity_name").getAsString().equals(session.playerName()), ANSWER_TICKS),
                    "with full sharing on a full server the name shows (" + state(session) + ")");
            noPersonalData(session, state(session));
            session.playerCommand("mineqtt privacy share off");
            session.check(session.await(() -> state(session).get("entity_name").getAsString().equals(typeName), ANSWER_TICKS),
                    "after share off the name is gone again (" + state(session) + ")");

            session.consoleCommand("mineqtt devices setting " + slug + " detect non_players");
            session.check(session.await(() -> state(session).get("count").getAsInt() == 0, ANSWER_TICKS),
                    "detecting everything but players, the player in front counts nothing (" + state(session) + ")");
            session.consoleCommand("mineqtt devices setting " + slug + " detect all");
        } finally {
            session.onServer(s -> MineQTTConfig.telemetryPlayers = mode);
            session.teleport(STAND);
        }
    }

    private static void noPersonalData(GameTestSession session, JsonObject state) {
        session.check(!state.get("living").getAsBoolean() && state.get("health").getAsDouble() == 0 && state.get("distance").getAsDouble() == 0
                && state.getAsJsonObject("details").isEmpty(), "never a player's health, distance or details (" + state + ")");
    }

    /**
     * The full payload: a tamed wolf's entity data without its owner's UUID; for the player no entity data and no
     * position; without entityObserver.exportEntityData no entity data at all.
     */
    private static void payloads(GameTestSession session, String slug, BrokerConfig broker) {
        session.clearAtClose(RAW_TOPIC);
        session.consoleCommand("mineqtt devices raw " + slug + " channel " + CHANNEL);
        RawLink raw = session.device(OBSERVER, DeviceLink::raw);
        session.check(raw != null && RAW_TOPIC.equals(raw.topic()) && raw.active(), "its Raw Link channel resolves to " + RAW_TOPIC + " (" + raw + ")");
        boolean export = session.onServer(s -> MineQTTConfig.entityObserverExportEntityData);
        int[] owner = UUIDUtil.uuidToIntArray(session.playerId());
        String ownerArray = "[I;" + owner[0] + "," + owner[1] + "," + owner[2] + "," + owner[3] + "]";
        try (BrokerProbe probe = BrokerProbe.connect(broker); BrokerProbe.Listener listener = probe.listen(RAW_TOPIC)) {
            session.onServer(s -> MineQTTConfig.entityObserverExportEntityData = true);
            summon(session, "minecraft:wolf", FRONT.getX() + 0.5, WOLF, ",Owner:" + ownerArray);
            Optional<String> wolf = next(session, listener, text -> text.contains("\"entity\":\"minecraft:wolf\"") && text.contains("\"entity_data\":{"));
            JsonObject wolfJson = wolf.map(text -> JsonParser.parseString(text).getAsJsonObject()).orElse(new JsonObject());
            session.check(wolfJson.has("entity_data") && wolfJson.getAsJsonObject("entity_data").has("Health")
                            && wolfJson.getAsJsonObject("entity_data").has("UUID") && !wolfJson.getAsJsonObject("entity_data").has("Owner"),
                    "the full payload carries the wolf's entity data with its own UUID (" + wolf.orElse("nothing") + ")");
            session.check(wolf.filter(text -> !text.contains(Integer.toString(owner[0])) && !text.contains(session.playerId().toString())).isPresent(),
                    "and nothing of its owner's UUID");
            session.check(session.await(() -> state(session).getAsJsonObject("details").has("tamed")
                    && state(session).getAsJsonObject("details").get("tamed").getAsBoolean(), ANSWER_TICKS), "the state says it is tamed, not by whom");
            session.check(!state(session).toString().contains("entity_data") && !state(session).toString().contains(Integer.toString(owner[0])),
                    "entity data never reaches the state");

            session.onServer(s -> MineQTTConfig.entityObserverExportEntityData = false);
            session.check(next(session, listener, text -> text.contains("\"entity\":\"minecraft:wolf\"") && text.contains("\"entity_data\":null")).isPresent(),
                    "without entityObserver.exportEntityData the next sample has no entity data");
            session.onServer(s -> MineQTTConfig.entityObserverExportEntityData = true);
            remove(session, WOLF);

            session.teleport(FRONT);
            Optional<String> player = next(session, listener, text -> text.contains("\"entity\":\"" + EntityObserver.PLAYER + "\""));
            JsonObject playerJson = player.map(text -> JsonParser.parseString(text).getAsJsonObject()).orElse(new JsonObject());
            session.check(playerJson.has("entity_data") && playerJson.get("entity_data").isJsonNull() && playerJson.get("position").isJsonNull()
                            && playerJson.get("distance").isJsonNull() && playerJson.get("health").isJsonNull() && !player.get().contains(session.playerName()),
                    "for the player no entity data, position, distance, health or name (" + player.orElse("nothing") + ")");
            session.check(probe.retained(RAW_TOPIC).isEmpty(), "nothing is retained on its Raw Link topic");
        } finally {
            session.onServer(s -> MineQTTConfig.entityObserverExportEntityData = export);
            session.teleport(STAND);
        }
    }

    // A mob without AI at x in the space in front, with a fixed UUID and extra SNBT keys (each starting with a comma).
    private static void summon(GameTestSession session, String type, double x, UUID id, String extra) {
        int[] uuid = UUIDUtil.uuidToIntArray(id);
        session.consoleCommand("summon " + type + " " + x + " " + FRONT.getY() + " " + (FRONT.getZ() + 0.5) + " {NoAI:1b,Silent:1b,UUID:[I;" + uuid[0] + ","
                + uuid[1] + "," + uuid[2] + "," + uuid[3] + "]" + extra + "}");
        session.awaitServer(s -> find(s.overworld(), id).isPresent());
    }

    // Kills a summoned mob and clears the loot it dropped, which the observer would report next.
    private static void remove(GameTestSession session, UUID id) {
        session.consoleCommand("kill " + id);
        session.consoleCommand("kill @e[type=minecraft:item]");
        session.consoleCommand("kill @e[type=minecraft:experience_orb]");
    }

    // A summoned mob near the space in front, by its UUID.
    private static Optional<Entity> find(ServerLevel level, UUID id) {
        return level.getEntities((Entity) null, new AABB(FRONT).inflate(4), entity -> entity.getUUID().equals(id)).stream().findFirst();
    }

    // The next live message on the listener's topic that matches, within the answer time.
    private static Optional<String> next(GameTestSession session, BrokerProbe.Listener listener, Predicate<String> matches) {
        for (int tick = 0; tick < ANSWER_TICKS; tick += 5) {
            Optional<String> message;
            while ((message = listener.nextLive()).isPresent()) {
                if (matches.test(message.get())) return message;
            }
            session.context().waitTicks(5);
        }
        return Optional.empty();
    }

    private static void setblock(GameTestSession session, BlockPos pos, String block) {
        session.consoleCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block);
    }

    private static JsonObject state(GameTestSession session) {
        return session.onServer(s -> {
            StateWriter state = new StateWriter();
            if (s.overworld().getBlockEntity(OBSERVER) instanceof DeviceHost host) host.writeState(state);
            return state.json();
        });
    }
}

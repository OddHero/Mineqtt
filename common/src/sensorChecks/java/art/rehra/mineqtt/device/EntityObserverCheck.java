package art.rehra.mineqtt.device;

import art.rehra.mineqtt.config.TelemetryPlayers;
import art.rehra.mineqtt.device.types.EntityObserver;
import art.rehra.mineqtt.device.types.EntityObserver.Detect;
import art.rehra.mineqtt.device.types.EntityObserver.Details;
import art.rehra.mineqtt.device.types.EntityObserver.Observation;
import art.rehra.mineqtt.device.types.EntityObserver.Payload;
import art.rehra.mineqtt.device.types.EntityObserver.Seen;
import art.rehra.mineqtt.presence.Consent;
import art.rehra.mineqtt.presence.PrivacyPolicy;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Dependency-free regression checks of the Entity Observer, run by Gradle's verifyEntityObserver task: the watched spaces,
 * what each detect setting counts, the player rules (counted, named only with full consent on a server that shares that
 * much, never with health, distance, position, details or entity data), the entity-data gate, the UUID scrub and the
 * 16 KiB cap, the Raw Link payload of each setting, the state, the details, the {@code changed} event and the saved form.
 */
public final class EntityObserverCheck {
    private static final BlockPos POS = new BlockPos(-4, 64, 10);
    private static final UUID OWNER = UUID.fromString("6a1c0f4e-0b6e-4a43-9d76-2f4f6f0e5a11");
    private static final UUID COW = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        geometry();
        detect();
        players();
        entityDataGate();
        uuidScrub();
        payloads();
        state();
        details();
        changes();
        saved();
        System.out.println("Entity Observer checks passed.");
    }

    /** The observer watches range block spaces straight out of its face, the block space in front by default. */
    private static void geometry() {
        check(EntityObserver.RANGE.defaultValue() == 1 && EntityObserver.DEFAULT_RANGE == 1, "By default the observer watches the block space in front");
        check(EntityObserver.spaces(POS, Direction.EAST, 1).equals(List.of(POS.east())), "Range 1: the space in front");
        check(EntityObserver.spaces(POS, Direction.DOWN, 3).equals(List.of(POS.below(), POS.below(2), POS.below(3))), "Range 3 downwards, the nearest first");
        check(EntityObserver.spaces(POS, Direction.NORTH, 99).size() == EntityObserver.MAX_RANGE && EntityObserver.spaces(POS, Direction.NORTH, 0).size() == 1,
                "The range is clamped to 1-8");
        AABB east = EntityObserver.area(POS, Direction.EAST, 1);
        check(east.minX == -3 && east.maxX == -2 && east.minY == 64 && east.maxY == 65 && east.minZ == 10 && east.maxZ == 11, "One block east: " + east);
        AABB west = EntityObserver.area(POS, Direction.WEST, 4);
        check(west.minX == -8 && west.maxX == -4 && west.minY == 64 && west.maxY == 65, "Four blocks west, the corners sorted: " + west);
        check(!west.contains(Vec3.atCenterOf(POS)) && west.contains(Vec3.atCenterOf(POS.west(4))), "The observer itself is not watched, the fourth space is");
        Vec3 face = EntityObserver.face(POS, Direction.UP);
        check(face.x == -3.5 && face.y == 65 && face.z == 10.5, "Distances count from the centre of the face: " + face);
    }

    /** All counts every entity, non_players everything but players, mobs living entities that are no players. */
    private static void detect() {
        check(EntityObserver.DETECT.defaultValue() == Detect.ALL, "A new observer sees every entity");
        check(Detect.ALL.counts(true, true) && Detect.ALL.counts(false, false) && Detect.ALL.counts(false, true), "all: players, items and mobs");
        check(!Detect.NON_PLAYERS.counts(true, true) && Detect.NON_PLAYERS.counts(false, false) && Detect.NON_PLAYERS.counts(false, true),
                "non_players: items and mobs");
        check(!Detect.MOBS.counts(true, true) && !Detect.MOBS.counts(false, false) && Detect.MOBS.counts(false, true), "mobs: living non-players only");
    }

    /**
     * M4 plan D9, D10: a player is counted and reported as a player; named only with consent to full sharing on a server
     * that shares that much, because a name at a fixed device tells where the player is; never with health, distance,
     * details, position or entity data.
     */
    private static void players() {
        PrivacyPolicy presence = new PrivacyPolicy(TelemetryPlayers.PRESENCE, true);
        PrivacyPolicy full = new PrivacyPolicy(TelemetryPlayers.FULL, true);
        PrivacyPolicy veto = new PrivacyPolicy(TelemetryPlayers.FULL, false);
        PrivacyPolicy off = new PrivacyPolicy(TelemetryPlayers.OFF, true);
        for (Consent consent : Consent.values()) {
            for (boolean host : new boolean[]{false, true}) {
                Consent effective = consent.effective(host);
                check(EntityObserver.names(full.shared(effective)) == (effective == Consent.FULL), "Full server, " + consent + " (host " + host + ")");
                check(!EntityObserver.names(presence.shared(effective)), "A presence server never names a player in front: " + consent);
                check(!EntityObserver.names(veto.shared(effective)) && !EntityObserver.names(off.shared(effective)),
                        "publishPlayerNames=false and telemetry.players=off never name one: " + consent);
            }
        }
        check(!EntityObserver.names(full.shared(Consent.UNSET.effective(true))), "The integrated host shares presence, which names nobody at a device");

        Seen hidden = Seen.player(PrivacyPolicy.Shared.PRESENCE, "Player", "Steve");
        Seen named = Seen.player(PrivacyPolicy.Shared.FULL, "Player", "Steve");
        check(hidden.entity().equals(EntityObserver.PLAYER) && hidden.name().equals("Player"), "Without full consent: the player type and its name only");
        check(named.name().equals("Steve"), "With full consent on a full server: the player's name");
        for (Seen player : List.of(hidden, named)) {
            check(!player.living() && player.health() == 0 && player.maxHealth() == 0 && player.distance() == 0 && player.details().isEmpty(),
                    "Never health, distance or details of a player: " + player);
        }

        Observation two = Observation.DEFAULT.looked(2, hidden);
        JsonObject state = two.write(new StateWriter()).json();
        check(state.get("entity").getAsString().equals(EntityObserver.PLAYER) && state.get("count").getAsInt() == 2 && state.get("present").getAsBoolean()
                        && state.get("entity_name").getAsString().equals("Player") && !state.get("living").getAsBoolean() && state.getAsJsonObject("details").isEmpty(),
                "The state counts the player and shows nothing that names them: " + state);
        check(!state.toString().contains("Steve"), "The name never reaches the state without consent");

        // The Raw Link: no position, distance, health or entity data, whatever the caller hands over.
        JsonObject data = new JsonObject();
        data.addProperty("Health", 20);
        JsonObject payload = json(EntityObserver.payload(two, new Vec3(1, 2, 3), "minecraft:overworld", data));
        check(payload.get("position").isJsonNull() && payload.get("distance").isJsonNull() && payload.get("health").isJsonNull()
                && payload.get("entity_data").isJsonNull() && !payload.has("truncated"), "A player's full payload has no position, distance, health or entity data: " + payload);
        check(EntityObserver.payload(withPayload(two, Payload.NAME), null, "minecraft:overworld", null).equals("Player"),
                "payload name for a player without consent: the player type's name");
        check(EntityObserver.payload(withPayload(Observation.DEFAULT.looked(1, named), Payload.NAME), null, "minecraft:overworld", null).equals("Steve"),
                "and with full consent the player's");
    }

    /** Entity data only while the server allows it, never for a player, and only in the observer's claim. */
    private static void entityDataGate() {
        check(EntityObserver.exportsEntityData(true, false, true), "Allowed, no player, same claim: exported");
        check(!EntityObserver.exportsEntityData(false, false, true) && !EntityObserver.exportsEntityData(true, true, true)
                && !EntityObserver.exportsEntityData(true, false, false) && !EntityObserver.exportsEntityData(false, true, false),
                "Off, a player or another claim: never");
    }

    /**
     * Entity data names others by UUID: a tamed animal's owner, an item's thrower, a villager's gossip targets, an allay's
     * liked player. Every UUID but the entity's own goes, in compounds and lists, as int arrays and as text.
     */
    private static void uuidScrub() {
        CompoundTag wolf = new CompoundTag();
        wolf.putIntArray("UUID", UUIDUtil.uuidToIntArray(COW));
        wolf.putIntArray("Owner", UUIDUtil.uuidToIntArray(OWNER));
        wolf.putFloat("Health", 8.0f);
        wolf.putIntArray("home_pos", new int[]{1, 64, -3});
        ListTag chances = new ListTag();
        for (int i = 0; i < 4; i++) chances.add(net.minecraft.nbt.FloatTag.valueOf(2.0f));
        wolf.put("ArmorDropChances", chances);
        CompoundTag memories = new CompoundTag();
        CompoundTag liked = new CompoundTag();
        liked.putIntArray("value", UUIDUtil.uuidToIntArray(OWNER));
        liked.putLong("ttl", 600);
        memories.put("minecraft:liked_player", liked);
        CompoundTag brain = new CompoundTag();
        brain.put("memories", memories);
        wolf.put("Brain", brain);
        ListTag gossips = new ListTag();
        CompoundTag gossip = new CompoundTag();
        gossip.putIntArray("Target", UUIDUtil.uuidToIntArray(OWNER));
        gossip.putString("Type", "minor_negative");
        gossip.putInt("Value", 5);
        gossips.add(gossip);
        wolf.put("Gossips", gossips);
        CompoundTag item = new CompoundTag();
        item.putString("Thrower", OWNER.toString());
        item.putString("id", "minecraft:bone");
        wolf.put("Item", item);
        ListTag uuids = new ListTag();
        uuids.add(new net.minecraft.nbt.IntArrayTag(UUIDUtil.uuidToIntArray(OWNER)));
        wolf.put("AngryAt", uuids);

        JsonObject data = (JsonObject) NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, wolf);
        String before = data.toString();
        JsonObject scrubbed = EntityObserver.withoutForeignUuids(data);
        check(data.toString().equals(before), "The data handed in is not changed");
        check(scrubbed.has("UUID") && scrubbed.getAsJsonArray("UUID").equals(data.getAsJsonArray("UUID")), "The entity's own UUID stays");
        check(!scrubbed.has("Owner"), "The owner's UUID goes");
        check(scrubbed.getAsJsonObject("Brain").getAsJsonObject("memories").getAsJsonObject("minecraft:liked_player").keySet().equals(java.util.Set.of("ttl")),
                "A memory's player UUID goes, the rest of the memory stays");
        JsonObject target = scrubbed.getAsJsonArray("Gossips").get(0).getAsJsonObject();
        check(!target.has("Target") && target.get("Value").getAsInt() == 5, "A gossip keeps its kind and value, not its target");
        check(!scrubbed.getAsJsonObject("Item").has("Thrower") && scrubbed.getAsJsonObject("Item").has("id"), "A UUID written as text goes too");
        check(scrubbed.getAsJsonArray("AngryAt").isEmpty(), "A UUID in a list goes");
        check(scrubbed.getAsJsonArray("home_pos").size() == 3 && scrubbed.getAsJsonArray("ArmorDropChances").size() == 4 && scrubbed.get("Health").getAsFloat() == 8,
                "Positions, four floats and plain values stay");
        check(!scrubbed.toString().contains(Integer.toString(UUIDUtil.uuidToIntArray(OWNER)[0])) && !scrubbed.toString().contains(OWNER.toString()),
                "Nothing of the owner's UUID is left: " + scrubbed);

        // Names as text: a player head a zombie wears, a written book on the ground.
        JsonObject zombie = json("{\"UUID\":[1,2,3,4],\"equipment\":{\"head\":{\"id\":\"minecraft:player_head\",\"components\":"
                + "{\"minecraft:profile\":{\"name\":\"Steve\",\"properties\":[{\"name\":\"textures\",\"value\":\"eyJ0\"}]}}}},"
                + "\"Item\":{\"components\":{\"minecraft:written_book_content\":{\"author\":\"Steve\",\"title\":{\"raw\":\"Diary\"}}}},"
                + "\"SkullOwner\":\"Steve\",\"CustomName\":\"Zed\"}");
        JsonObject named = EntityObserver.withoutForeignUuids(zombie);
        check(!named.toString().contains("Steve") && !named.toString().contains("eyJ0"), "No player profile, skull owner or book author is left: " + named);
        check(named.getAsJsonObject("equipment").getAsJsonObject("head").get("id").getAsString().equals("minecraft:player_head")
                && named.toString().contains("Diary") && named.get("CustomName").getAsString().equals("Zed"),
                "The head, the book's title and custom names stay: " + named);

        JsonObject stray = new JsonObject();
        stray.add("UUID", JsonParser.parseString("\"" + OWNER + "\""));
        check(EntityObserver.withoutForeignUuids(stray).has("UUID"), "The entity's own UUID stays in its text form too");
    }

    /** Each payload setting; health only for a living entity, entity data only as handed over and never beyond 16 KiB. */
    private static void payloads() {
        check(EntityObserver.PAYLOAD.defaultValue() == Payload.FULL, "A new observer sends everything");
        Observation cow = Observation.DEFAULT.looked(3, cow());
        check(EntityObserver.payload(withPayload(cow, Payload.ENTITY_ID), null, "minecraft:overworld", null).equals("minecraft:cow"), "entity_id: the type id");
        check(EntityObserver.payload(withPayload(cow, Payload.NAME), null, "minecraft:overworld", null).equals("Daisy"), "name: the name");
        check(EntityObserver.payload(withPayload(cow, Payload.COUNT), null, "minecraft:overworld", null).equals("3"), "count: how many");
        check(EntityObserver.payload(withPayload(Observation.DEFAULT.looked(0, cow()), Payload.ENTITY_ID), null, "minecraft:overworld", null).isEmpty(),
                "Nothing in front: an empty id");

        JsonObject data = new JsonObject();
        data.addProperty("id", "minecraft:cow");
        data.addProperty("Health", 9.5);
        JsonObject full = json(EntityObserver.payload(cow, new Vec3(-2.3456, 64, 10.5), "minecraft:overworld", data));
        check(full.keySet().stream().toList().equals(List.of("entity", "name", "present", "count", "living", "health", "max_health", "distance", "details",
                "position", "dimension", "entity_data")), "full has its keys in a fixed order: " + full.keySet());
        check(full.get("entity").getAsString().equals("minecraft:cow") && full.get("count").getAsInt() == 3 && full.get("health").getAsDouble() == 9.5
                && full.get("max_health").getAsDouble() == 10 && full.get("distance").getAsDouble() == 0.7, "full: the state's values: " + full);
        check(full.getAsJsonObject("position").get("x").getAsDouble() == -2.35 && full.getAsJsonObject("position").get("y").getAsDouble() == 64
                && full.get("dimension").getAsString().equals("minecraft:overworld"), "full: the entity's position to the hundredth, and the dimension");
        check(full.getAsJsonObject("entity_data").equals(data) && !full.has("truncated"), "full: the entity data handed over");
        check(json(EntityObserver.payload(cow, new Vec3(0, 0, 0), "minecraft:overworld", null)).get("entity_data").isJsonNull(), "Without data: null");

        Seen stand = Seen.entity("minecraft:minecart", "Minecart", false, 20, 20, 0.5, new JsonObject());
        JsonObject nonLiving = json(EntityObserver.payload(Observation.DEFAULT.looked(1, stand), new Vec3(0, 0, 0), "minecraft:overworld", null));
        check(nonLiving.get("health").isJsonNull() && nonLiving.get("max_health").isJsonNull() && nonLiving.get("distance").getAsDouble() == 0.5,
                "A non-living entity has no health, but a distance: " + nonLiving);
        JsonObject nothing = json(EntityObserver.payload(Observation.DEFAULT.looked(0, cow()), new Vec3(0, 0, 0), "minecraft:overworld", data));
        check(!nothing.get("present").getAsBoolean() && nothing.get("entity").getAsString().isEmpty() && nothing.get("position").isJsonNull()
                && nothing.get("distance").isJsonNull(), "Nothing in front: no position or distance: " + nothing);

        JsonObject large = new JsonObject();
        large.addProperty("id", "minecraft:villager");
        large.addProperty("Offers", "x".repeat(EntityObserver.MAX_ENTITY_DATA_BYTES));
        JsonObject cut = json(EntityObserver.payload(cow, new Vec3(0, 0, 0), "minecraft:overworld", large));
        check(cut.get("entity_data").isJsonNull() && cut.get("truncated").getAsBoolean(), "Data beyond 16 KiB is left out and marked truncated");
        JsonObject edge = new JsonObject();
        edge.addProperty("a", "x".repeat(EntityObserver.MAX_ENTITY_DATA_BYTES - "{\"a\":\"\"}".length()));
        check(json(EntityObserver.payload(cow, new Vec3(0, 0, 0), "minecraft:overworld", edge)).get("entity_data").isJsonObject(), "Exactly 16 KiB still fits");
    }

    /** The state: every readable field; health only while living; values rounded as shown, so equal looks are no change. */
    private static void state() {
        JsonObject state = Observation.DEFAULT.looked(3, cow()).write(new StateWriter()).json();
        check(state.keySet().stream().toList().equals(List.of("entity", "present", "count", "entity_name", "living", "health", "max_health", "distance", "details",
                "detect", "range", "payload")), "The state's keys: " + state.keySet());
        check(state.get("entity").getAsString().equals("minecraft:cow") && state.get("present").getAsBoolean() && state.get("count").getAsInt() == 3
                && state.get("entity_name").getAsString().equals("Daisy") && state.get("living").getAsBoolean() && state.get("health").getAsDouble() == 9.5
                && state.get("detect").getAsString().equals("all") && state.get("range").getAsInt() == 1 && state.get("payload").getAsString().equals("full"),
                "The state of a cow in front: " + state);
        JsonObject empty = Observation.DEFAULT.write(new StateWriter()).json();
        check(!empty.get("present").getAsBoolean() && empty.get("entity").getAsString().isEmpty() && empty.get("count").getAsInt() == 0, "Nothing seen: " + empty);
        for (JsonObject shown : List.of(state, empty)) {
            for (Field<?> field : EntityObserver.TYPE.fields()) {
                if (!shown.has(field.key())) continue;
                Optional<String> problem = field.kind().problem(shown.get(field.key()));
                check(problem.isEmpty(), field.key() + " fits its declared port: " + problem.orElse(""));
            }
        }

        Seen a = Seen.entity("minecraft:cow", "Cow", true, 9.66, 10, 1.04, new JsonObject());
        Seen b = Seen.entity("minecraft:cow", "Cow", true, 9.71, 10, 0.96, new JsonObject());
        check(Observation.DEFAULT.looked(1, a).equals(Observation.DEFAULT.looked(1, b)), "Health and distance count to the tenth, as shown");
        Seen hurt = Seen.entity("minecraft:cow", "Cow", true, 4000, -2, 99, new JsonObject());
        Observation clamped = Observation.DEFAULT.looked(1, hurt);
        check(clamped.health() == EntityObserver.MAX_HEALTH_POINTS && clamped.maxHealth() == 0 && clamped.distance() == EntityObserver.MAX_DISTANCE,
                "Values beyond the bounds are clamped: " + clamped);
        Seen dirty = Seen.entity("minecraft:cow", "§cRed\nCow", true, 1, 1, 1, new JsonObject());
        check(dirty.name().equals("RedCow"), "A name loses its formatting codes and control characters: " + dirty.name());
    }

    /** Details: only what the entity has, text cut to 64 code points, keys in the order written. */
    private static void details() {
        JsonObject details = new Details().text("custom_name", "Daisy").flag("baby", false).flag("tamed", null).number("armor", 0).text("vehicle", null)
                .text("color", "white").number("level", null).build();
        check(details.keySet().stream().toList().equals(List.of("custom_name", "baby", "armor", "color")), "Absent details are left out: " + details);
        String longName = "n".repeat(100);
        String cut = new Details().text("custom_name", longName).build().get("custom_name").getAsString();
        check(cut.codePointCount(0, cut.length()) == EntityObserver.MAX_DETAIL_TEXT && cut.endsWith("…"), "Long text is cut to 64: " + cut);
        check(new Details().text("custom_name", "").build().isEmpty(), "Empty text is no detail");
        check(new Details().text("custom_name", "§aGreen").build().get("custom_name").getAsString().equals("Green"), "Detail text is cleaned");
    }

    /** changed: another nearest entity or the last one leaving; the event names the entity after it. */
    private static void changes() {
        check(EntityObserver.changed(Optional.empty(), Optional.of(7)) && EntityObserver.changed(Optional.of(7), Optional.empty())
                && EntityObserver.changed(Optional.of(7), Optional.of(8)), "Arriving, leaving and another nearest are changes");
        check(!EntityObserver.changed(Optional.of(7), Optional.of(7)) && !EntityObserver.changed(Optional.empty(), Optional.empty()),
                "The same entity, or still nothing, is no change");
        JsonObject event = EntityObserver.changedEvent("minecraft:cow");
        check(event.equals(json("{\"event_type\":\"changed\",\"entity\":\"minecraft:cow\"}")) && EntityObserver.TYPE.eventProblem(event).isEmpty(),
                "The changed event: " + event);
        check(EntityObserver.TYPE.eventProblem(EntityObserver.changedEvent("")).isEmpty(), "The last one leaving: an empty entity");
        DeviceTraffic traffic = new DeviceTraffic();
        int published = 0;
        for (long millis = 0; millis < 1000; millis += 100) {
            if (traffic.admitEvent(EntityObserver.CHANGED_EVENT, millis)) published++;
        }
        check(published == 2 && EntityObserver.CHANGED_EVENT.minInterval().equals(Duration.ofMillis(500)), "Mobs milling about: two changed events a second");
    }

    /** The saved form: settings and what it saw survive, details keep their types, unreadable values take their defaults. */
    private static void saved() {
        Observation seen = new Observation(Detect.MOBS, 5, Payload.COUNT, 0, "", "", false, 0, 0, 0, new JsonObject(), false).looked(2, cow());
        Tag tag = Observation.CODEC.encodeStart(NbtOps.INSTANCE, seen).getOrThrow();
        Observation loaded = Observation.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
        check(loaded.equals(seen) && loaded.looked(), "Saved and loaded unchanged: " + loaded);
        check(loaded.details().get("baby").getAsJsonPrimitive().isBoolean(), "A detail's boolean stays a boolean in NBT");
        CompoundTag broken = new CompoundTag();
        broken.putString("detect", "everyone");
        broken.putInt("range", 40);
        broken.putString("details", "not json");
        Observation lenient = Observation.CODEC.parse(NbtOps.INSTANCE, broken).getOrThrow();
        check(lenient.detect() == Detect.ALL && lenient.range() == EntityObserver.MAX_RANGE && lenient.details().isEmpty() && !lenient.looked(),
                "Unreadable values take their defaults, a range is clamped: " + lenient);
        check(seen.placed().equals(new Observation(Detect.MOBS, 5, Payload.COUNT, 0, "", "", false, 0, 0, 0, new JsonObject(), false)),
                "A placed copy keeps its settings and has seen nothing");
        Optional<Observation> commanded = seen.apply(new CommandReader(json("{\"range\":3,\"detect\":\"non_players\"}")));
        check(commanded.isPresent() && commanded.get().range() == 3 && commanded.get().detect() == Detect.NON_PLAYERS && commanded.get().payload() == Payload.COUNT
                && commanded.get().count() == 2, "A command changes its settings only");
        check(seen.apply(new CommandReader(json("{\"count\":9}"))).isEmpty(), "What it saw is no command");
    }

    private static Seen cow() {
        JsonObject details = new Details().text("custom_name", "Daisy").flag("baby", false).number("armor", 0).build();
        return Seen.entity("minecraft:cow", "Daisy", true, 9.5, 10, 0.7071, details);
    }

    private static Observation withPayload(Observation observation, Payload payload) {
        return observation.apply(new CommandReader(json("{\"payload\":\"" + payload.getSerializedName() + "\"}"))).orElseThrow();
    }

    private static JsonObject json(String text) {
        JsonElement parsed = JsonParser.parseString(text);
        return parsed.getAsJsonObject();
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}

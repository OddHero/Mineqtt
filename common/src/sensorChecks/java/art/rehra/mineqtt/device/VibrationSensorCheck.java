package art.rehra.mineqtt.device;

import art.rehra.mineqtt.compat.Vibrations;
import art.rehra.mineqtt.device.types.VibrationSensor;
import art.rehra.mineqtt.device.types.VibrationSensor.Detect;
import art.rehra.mineqtt.device.types.VibrationSensor.Group;
import art.rehra.mineqtt.device.types.VibrationSensor.Hearing;
import art.rehra.mineqtt.device.types.VibrationSensor.Source;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Dependency-free regression checks of the Vibration Sensor, run by Gradle's verifyVibrationSensor task: every vanilla
 * vibration has its group, landings go by what landed, who caused a vibration and what each detect setting hears, the
 * player rule (a player's vibration is anonymous: no name, UUID, position or distance), the rest after each vibration,
 * the clear delay, the per-group event throttle, the events and the state, the comparator level and the saved form.
 */
public final class VibrationSensorCheck {
    private static final int TICKS_PER_SECOND = 20;
    /** The vibrations no group takes, and so {@code other}: explosions, lightning and the amethyst resonances. */
    private static final Set<String> OTHER = otherEvents();

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        groups();
        landings();
        sources();
        detect();
        players();
        rest();
        clearDelay();
        throttle();
        events();
        state();
        saved();
        System.out.println("Vibration Sensor checks passed.");
    }

    /**
     * Every game event vanilla gives a frequency (what a sculk sensor hears) is in the table, apart from the few that are
     * {@code other}; the table names only game events that exist, but for {@code minecraft:bounce}, which 1.21.5 added.
     */
    private static void groups() {
        Set<String> registered = new TreeSet<>();
        List<String> missing = new ArrayList<>();
        // Iterated and keyed as every version allows (the registry's holder stream changed its name in 1.21.2).
        for (GameEvent event : BuiltInRegistries.GAME_EVENT) {
            Identifier key = BuiltInRegistries.GAME_EVENT.getKey(event);
            String id = key.toString();
            registered.add(id);
            int frequency = VibrationSystem.getGameEventFrequency(ResourceKey.create(Registries.GAME_EVENT, key));
            if (frequency > 0 && !VibrationSensor.groups().containsKey(id) && !OTHER.contains(id)) missing.add(id);
        }
        check(missing.isEmpty(), "Every vibration has a group: " + missing);
        for (String id : VibrationSensor.groups().keySet()) {
            check(registered.contains(id) || id.equals("minecraft:bounce"), "The table names a game event that exists: " + id);
        }
        for (String id : OTHER) check(VibrationSensor.group(id, false, false) == Group.OTHER, id + " is other");
        check(VibrationSensor.group("minecraft:step", false, false) == Group.STEP && VibrationSensor.group("minecraft:block_open", false, false) == Group.DOOR
                        && VibrationSensor.group("minecraft:container_open", false, false) == Group.CONTAINER
                        && VibrationSensor.group("minecraft:block_place", false, false) == Group.BLOCK
                        && VibrationSensor.group("minecraft:block_destroy", false, false) == Group.BLOCK
                        && VibrationSensor.group("minecraft:block_activate", false, false) == Group.BLOCK
                        && VibrationSensor.group("minecraft:projectile_shoot", false, false) == Group.PROJECTILE
                        && VibrationSensor.group("minecraft:eat", false, false) == Group.ITEM
                        && VibrationSensor.group("minecraft:entity_damage", false, false) == Group.ENTITY
                        && VibrationSensor.group("minecraft:explode", false, false) == Group.OTHER,
                "Steps, doors, containers, blocks, projectiles, items, entities and the rest");
        check(VibrationSensor.group("othermod:rumble", false, false) == Group.OTHER && VibrationSensor.group("", true, true) == Group.OTHER,
                "A game event of a mod, or none, is other");
        // The groups are Home Assistant's event types and the device triggers' subtypes: a fixed list, in this order.
        check(List.of(Group.values()).stream().map(Group::getSerializedName).toList()
                        .equals(List.of("step", "projectile", "item", "entity", "door", "container", "block", "other")),
                "The groups are fixed");
        check(VibrationSystem.getGameEventFrequency(GameEvent.STEP) == 1 && VibrationSystem.getGameEventFrequency(GameEvent.BLOCK_OPEN) == 10
                && VibrationSystem.getGameEventFrequency(GameEvent.EXPLODE) == 15 && VibrationSystem.getGameEventFrequency(GameEvent.SHRIEK) == 0,
                "The frequencies are vanilla's, and a shriek is no vibration");
        check(Vibrations.id(GameEvent.STEP).equals("minecraft:step"), "A game event's id: " + Vibrations.id(GameEvent.STEP));
    }

    /** A landing, splash or bounce belongs to what landed: a dropped item, a projectile, else whoever stepped. */
    private static void landings() {
        for (String landing : List.of("minecraft:hit_ground", "minecraft:splash", "minecraft:bounce")) {
            check(VibrationSensor.group(landing, true, false) == Group.ITEM, landing + " of a dropped item is item");
            check(VibrationSensor.group(landing, false, true) == Group.PROJECTILE, landing + " of a projectile is projectile");
            check(VibrationSensor.group(landing, false, false) == Group.STEP, landing + " of anything else is step");
        }
        check(VibrationSensor.group("minecraft:step", true, false) == Group.STEP, "Only landings go by what landed");
    }

    private static void sources() {
        check(VibrationSensor.source(false, false) == Source.WORLD, "Nothing caused it: the world");
        check(VibrationSensor.source(true, false) == Source.ENTITY, "An entity that acts for no player");
        check(VibrationSensor.source(true, true) == Source.PLAYER, "A player, or what acts for one");
        check(Vibrations.causedByPlayer(null) == false && !Vibrations.item(null) && !Vibrations.projectile(null), "No entity is nobody");
        check(VibrationSensor.ownBlockEvent("minecraft:block_place", true) && VibrationSensor.ownBlockEvent("minecraft:block_destroy", true)
                        && !VibrationSensor.ownBlockEvent("minecraft:block_place", false) && !VibrationSensor.ownBlockEvent("minecraft:block_change", true),
                "The sensor does not hear its own placing and breaking, as a sculk sensor does not");
    }

    private static void detect() {
        record Case(Detect detect, boolean world, boolean entity, boolean player) {
        }
        for (Case c : List.of(new Case(Detect.ALL, true, true, true), new Case(Detect.ENTITIES, false, true, true), new Case(Detect.PLAYERS, false, false, true),
                new Case(Detect.NON_PLAYERS, true, true, false))) {
            check(c.detect().hears(Source.WORLD) == c.world() && c.detect().hears(Source.ENTITY) == c.entity() && c.detect().hears(Source.PLAYER) == c.player(),
                    c.detect() + " hears what it says");
        }
        check(Hearing.DEFAULT.detect() == Detect.ALL && Hearing.DEFAULT.range() == 8 && Hearing.DEFAULT.clearDelay() == 10,
                "By default it hears everything within a sculk sensor's 8 blocks and clears after 10 s");
    }

    /** A player's vibration says that one happened, its group, game event and frequency; never who or where. */
    private static void players() {
        Hearing heard = Hearing.DEFAULT.heard(Group.STEP, 1, "minecraft:step", Source.PLAYER, 5.4, 100);
        check(heard.distance() == 0 && heard.source().equals("player") && heard.vibration() && heard.count() == 1, "A player's vibration has no distance: " + heard);
        check(VibrationSensor.reportedDistance(Source.PLAYER, 3.2) == 0 && VibrationSensor.reportedDistance(Source.ENTITY, 3.2) == 3.2
                && VibrationSensor.reportedDistance(Source.WORLD, 3.2) == 3.2, "Only a player's distance is left out");
        JsonObject state = heard.write(new StateWriter()).json();
        JsonObject event = VibrationSensor.event(Group.STEP, 1, "minecraft:step", Source.PLAYER);
        check(event.keySet().equals(Set.of("event_type", "frequency", "game_event", "source")), "A player's event carries no name, UUID or position: " + event);
        for (JsonObject published : List.of(state, event)) {
            String text = published.toString();
            for (String personal : List.of("name", "uuid", "UUID", "pos", "player_", "x\"", "\"y", "\"z")) {
                check(!text.contains(personal), "Nothing personal (" + personal + ") in " + text);
            }
        }
        Hearing mob = Hearing.DEFAULT.heard(Group.STEP, 1, "minecraft:step", Source.ENTITY, 5.44, 100);
        check(mob.distance() == 5.4 && mob.source().equals("entity"), "A mob's vibration has its distance, in tenths: " + mob);
    }

    /** The sensor hears at most one vibration every COOLDOWN_TICKS ticks, so at most two a second reach the device. */
    private static void rest() {
        check(!Hearing.DEFAULT.resting(0) && !Hearing.DEFAULT.resting(5), "A sensor that heard nothing does not rest");
        Hearing heard = Hearing.DEFAULT.heard(Group.BLOCK, 13, "minecraft:block_place", Source.WORLD, 2, 1_000);
        check(heard.resting(1_000) && heard.resting(1_000 + VibrationSensor.COOLDOWN_TICKS - 1) && !heard.resting(1_000 + VibrationSensor.COOLDOWN_TICKS),
                "It rests for " + VibrationSensor.COOLDOWN_TICKS + " ticks after a vibration");
        check(!heard.resting(500), "A last vibration in the future (data from another world) is over");
        check(VibrationSensor.COOLDOWN_TICKS == 10, "At most two vibrations a second");
    }

    private static void clearDelay() {
        long at = 2_000;
        Hearing heard = Hearing.DEFAULT.heard(Group.DOOR, 10, "minecraft:block_open", Source.ENTITY, 3, at);
        long delay = (long) Hearing.DEFAULT.clearDelay() * TICKS_PER_SECOND;
        check(!heard.expired(at) && !heard.expired(at + delay - 1) && heard.expired(at + delay), "Vibration clears the clear delay after the last vibration");
        check(heard.expired(at - 1), "A last vibration in the future has expired");
        Hearing again = heard.heard(Group.STEP, 1, "minecraft:step", Source.ENTITY, 2, at + delay - 5);
        check(!again.expired(at + delay) && again.count() == 2, "Every vibration starts the delay again and is counted");
        Hearing cleared = heard.cleared();
        check(!cleared.vibration() && cleared.frequency() == 10 && cleared.group().equals("door") && cleared.gameEvent().equals("minecraft:block_open")
                && cleared.count() == 1 && !cleared.expired(at + delay * 2), "Clearing turns vibration off and keeps what it heard last");
        check(heard.comparatorLevel() == 10 && cleared.comparatorLevel() == 0 && Hearing.DEFAULT.comparatorLevel() == 0,
                "A comparator reads the last frequency while vibration is on");
        check(new Hearing(Detect.ALL, 8, 0, false, 0, 0, "", "", "", 0, 0).clearDelay() == VibrationSensor.MIN_CLEAR_DELAY
                && new Hearing(Detect.ALL, 99, 9_999, false, -3, 40, "", "", "", 99, 0).range() == VibrationSensor.MAX_RANGE,
                "Settings and readings are clamped");
        Hearing full = new Hearing(Detect.ALL, 8, 10, false, Integer.MAX_VALUE, 0, "", "", "", 0, 0);
        check(full.heard(Group.STEP, 1, "minecraft:step", Source.WORLD, 1, 5).count() == Integer.MAX_VALUE, "The count stops at its largest value");
    }

    /** Each group's event is published at most once a second; another group has its own clock. */
    private static void throttle() {
        DeviceTraffic traffic = new DeviceTraffic();
        EventSpec step = Group.STEP.spec();
        EventSpec door = Group.DOOR.spec();
        check(step.minInterval().equals(Duration.ofSeconds(1)) && step.throttled(), "A group's event is throttled to one a second");
        check(traffic.admitEvent(step, 0) && !traffic.admitEvent(step, 500) && !traffic.admitEvent(step, 999) && traffic.admitEvent(step, 1_000),
                "Steps sooner than a second after the last are held back");
        check(traffic.admitEvent(door, 1_100), "A door opening is not held back by the steps");
        check(traffic.throttled() == 2, "Every held-back event is counted: " + traffic.throttled());
    }

    private static void events() {
        for (Group group : Group.values()) {
            for (Source source : Source.values()) {
                JsonObject event = VibrationSensor.event(group, 7, "minecraft:entity_damage", source);
                check(group.spec().problem(event).isEmpty(), group + " " + source + " fits its declaration: " + group.spec().problem(event));
                check(VibrationSensor.TYPE.eventProblem(event).isEmpty(), "The type publishes it: " + VibrationSensor.TYPE.eventProblem(event));
            }
        }
        JsonObject door = VibrationSensor.event(Group.DOOR, 10, "minecraft:block_open", Source.ENTITY);
        check(door.toString().equals("{\"event_type\":\"door\",\"frequency\":10,\"game_event\":\"minecraft:block_open\",\"source\":\"entity\"}"),
                "A door's event: " + door);
        check(VibrationSensor.event(Group.STEP, 0, "minecraft:step", Source.WORLD).get("frequency").getAsInt() == 1
                && VibrationSensor.event(Group.STEP, 40, "minecraft:step", Source.WORLD).get("frequency").getAsInt() == 15, "The frequency is 1 to 15");
    }

    private static void state() {
        JsonObject state = Hearing.DEFAULT.heard(Group.CONTAINER, 10, "minecraft:container_open", Source.ENTITY, 4.04, 50).write(new StateWriter()).json();
        check(state.keySet().stream().toList().equals(List.of("vibration", "count", "frequency", "last_group", "last_event", "last_source", "last_distance",
                "detect", "range", "clear_delay")), "The state's keys: " + state.keySet());
        check(state.get("vibration").getAsBoolean() && state.get("count").getAsInt() == 1 && state.get("frequency").getAsInt() == 10
                        && state.get("last_group").getAsString().equals("container") && state.get("last_event").getAsString().equals("minecraft:container_open")
                        && state.get("last_source").getAsString().equals("entity") && state.get("last_distance").getAsDouble() == 4.0
                        && state.get("detect").getAsString().equals("all") && state.get("range").getAsInt() == 8 && state.get("clear_delay").getAsInt() == 10,
                "The state of a chest opened by a mob: " + state);
        JsonObject empty = Hearing.DEFAULT.write(new StateWriter()).json();
        check(!empty.get("vibration").getAsBoolean() && empty.get("count").getAsInt() == 0 && empty.get("frequency").getAsInt() == 0
                && empty.get("last_event").getAsString().isEmpty() && empty.get("last_source").getAsString().isEmpty(), "Nothing heard: " + empty);
        for (JsonObject shown : List.of(state, empty)) {
            for (Field<?> field : VibrationSensor.TYPE.fields()) {
                if (!shown.has(field.key())) continue;
                Optional<String> problem = field.kind().problem(shown.get(field.key()));
                check(problem.isEmpty(), field.key() + " fits its declared port: " + problem.orElse(""));
            }
        }
    }

    private static void saved() {
        Hearing heard = new Hearing(Detect.PLAYERS, 12, 30, false, 0, 0, "", "", "", 0, 0)
                .heard(Group.PROJECTILE, 2, "minecraft:projectile_land", Source.PLAYER, 6, 777);
        Tag tag = Hearing.CODEC.encodeStart(NbtOps.INSTANCE, heard).getOrThrow();
        Hearing loaded = Hearing.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
        check(loaded.equals(heard) && loaded.lastHeard() == 777, "Saved and loaded unchanged: " + loaded);
        CompoundTag broken = new CompoundTag();
        broken.putString("detect", "everyone");
        broken.putInt("range", 40);
        broken.putInt("clear_delay", 0);
        Hearing lenient = Hearing.CODEC.parse(NbtOps.INSTANCE, broken).getOrThrow();
        check(lenient.detect() == Detect.ALL && lenient.range() == VibrationSensor.MAX_RANGE && lenient.clearDelay() == VibrationSensor.MIN_CLEAR_DELAY,
                "Unreadable values take their defaults, a range and a clear delay are clamped: " + lenient);
        check(heard.placed().equals(new Hearing(Detect.PLAYERS, 12, 30, false, 0, 0, "", "", "", 0, 0)), "A placed copy keeps its settings and has heard nothing");
        Optional<Hearing> commanded = heard.apply(new CommandReader(json("{\"range\":3,\"detect\":\"non_players\",\"clear_delay\":5}")));
        check(commanded.isPresent() && commanded.get().range() == 3 && commanded.get().detect() == Detect.NON_PLAYERS && commanded.get().clearDelay() == 5
                && commanded.get().count() == 1 && commanded.get().vibration(), "A command changes its settings only");
        check(heard.apply(new CommandReader(json("{\"count\":9,\"vibration\":false}"))).isEmpty(), "What it heard is no command");
    }

    private static Set<String> otherEvents() {
        Set<String> other = new TreeSet<>(Set.of("minecraft:explode", "minecraft:lightning_strike"));
        for (int frequency = 1; frequency <= VibrationSensor.MAX_FREQUENCY; frequency++) other.add("minecraft:resonate_" + frequency);
        return other;
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}

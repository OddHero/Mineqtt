package art.rehra.mineqtt.device.types;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.device.Access;
import art.rehra.mineqtt.device.Action;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.EventSpec;
import art.rehra.mineqtt.device.Field;
import art.rehra.mineqtt.device.Setting;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.StatusLine;
import art.rehra.mineqtt.device.UnloadedPolicy;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.flow.core.Coerce;
import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.ha.Ha;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.security.AccessMode;
import art.rehra.mineqtt.security.DeviceAccess;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * The Smart Lock ({@code smart_lock}): a small plate a player puts onto a door, trapdoor, fence gate or container with
 * its item, which then keeps every player without access from opening that target by hand. Double doors and double
 * chests lock as a pair. Home Assistant sees a {@code lock}; an {@code attempt} event tells that someone without access
 * tried (how and on what, never who).
 * <p>
 * Who may open a locked target ({@link #mayPass}): the owner, players the device's access mode lets use it (it starts
 * {@link AccessMode#PRIVATE}), and operators while {@code smartLock.opsBypass} is on. They also toggle the lock with a
 * right-click on the plate. Locking closes the target; something other than such a player's hand that opens a locked
 * door, trapdoor or gate (redstone, a mob, a wind charge) is undone the next tick. Containers also carry vanilla's own
 * lock while locked, so vanilla refuses them with its message on every path that opens their menu, and hoppers and
 * hopper minecarts pull nothing out of them (they may still put items in).
 * <p>
 * It stops hands and hoppers, not explosions, pistons or other mods' item pipes; claims stay the real protection.
 */
public final class SmartLock {
    /** The longest auto-relock delay: an hour. */
    public static final int MAX_RELOCK = 3600;
    public static final int DEFAULT_RELOCK = 0;
    public static final int TICKS_PER_SECOND = 20;
    /** The shortest time between two published attempts of one lock. */
    public static final Duration ATTEMPT_INTERVAL = Duration.ofSeconds(1);
    /** The actions and the event. */
    public static final String LOCK = "lock";
    public static final String UNLOCK = "unlock";
    public static final String TOGGLE = "toggle";
    public static final String ATTEMPT = "attempt";
    public static final String METHOD_KEY = "method";
    public static final String TARGET_KEY = "target";
    /** The largest attempt count kept; it stops there. */
    public static final int MAX_ATTEMPTS = 1_000_000_000;

    /** The lock's state as Home Assistant reads it; each name is a Home Assistant lock state. */
    public enum LockState implements StringRepresentable {
        LOCKED, UNLOCKED, OPEN, JAMMED;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Whether the lock reaches its target. */
    public enum Status implements StringRepresentable {
        /** The block it is attached to is a lockable target. */
        OK,
        /** It never had a target: set by command or a structure where nothing lockable is. */
        NO_TARGET,
        /** Its target is gone or not loaded: replaced by a block that cannot be locked, or in a chunk that is not loaded. */
        TARGET_MISSING;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What a lock locks, as the attempt event names it. */
    public enum TargetKind implements StringRepresentable {
        DOOR, TRAPDOOR, FENCE_GATE, CONTAINER,
        /** A block a datapack or mod put into {@code #mineqtt:lockable} that is none of the above. */
        OTHER;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Whether it opens and closes as a block state ({@code open}): doors, trapdoors and fence gates. */
        public boolean swings() {
            return this == DOOR || this == TRAPDOOR || this == FENCE_GATE;
        }
    }

    /** How somebody or something tried to get past a locked lock. */
    public enum Method implements StringRepresentable {
        /** A player without access used the target by hand. */
        HAND,
        /** A player without access used the plate. */
        PLATE,
        /** A redstone signal opened a locked door, trapdoor or gate; the lock closed it again. */
        REDSTONE,
        /** Something else opened it (a mob, a wind charge, another mod); the lock closed it again. */
        OTHER;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Whether the lock is locked; a command sets it, Home Assistant's lock and unlock among them. */
    public static final Field<Boolean> LOCKED = Field.bool("locked", true, Access.READ_WRITE);
    /** What Home Assistant shows ({@link #state}). */
    public static final Field<LockState> STATE = Field.enumOf("state", LockState.class, LockState.LOCKED, Access.READ);
    /** Seconds after an unlock until it locks again by itself; 0 never. */
    public static final Field<Integer> RELOCK = Field.intRange("relock", 0, MAX_RELOCK, DEFAULT_RELOCK, "s", Access.READ_WRITE);
    /** How many attempts it counted since it was placed. */
    public static final Field<Integer> ATTEMPTS = Field.intRange("attempts", 0, MAX_ATTEMPTS, 0, null, Access.READ);
    /** The target's block id, e.g. {@code minecraft:oak_door}; empty before it ever had one. */
    public static final Field<String> TARGET = Field.text("target", Access.READ);
    /** Whether it locks a pair: a double door or a double chest. */
    public static final Field<Boolean> PAIRED = Field.bool("paired", Access.READ);
    public static final Field<Status> STATUS = Field.enumOf("status", Status.class, Status.OK, Access.READ);
    public static final Action LOCK_ACTION = Action.press(LOCK);
    public static final Action UNLOCK_ACTION = Action.press(UNLOCK);
    public static final Action TOGGLE_ACTION = Action.press(TOGGLE);
    public static final EventSpec ATTEMPT_EVENT = EventSpec.of(ATTEMPT)
            .attribute(METHOD_KEY, PortType.enumOf(Arrays.stream(Method.values()).map(Method::getSerializedName).toList()))
            .attribute(TARGET_KEY, PortType.enumOf(Arrays.stream(TargetKind.values()).map(TargetKind::getSerializedName).toList()))
            .minInterval(ATTEMPT_INTERVAL);

    public static final DeviceType TYPE = DeviceType.builder(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "smart_lock"))
            // Queued while unloaded: Home Assistant may lock a far door, which then locks when its chunk loads.
            .model("Smart Lock").unloaded(UnloadedPolicy.QUEUE).noV1Topic()
            // Owner only until the owner says otherwise: CLAIM would let everybody in where no claim mod runs.
            .defaultAccess(AccessMode.PRIVATE)
            .field(LOCKED).field(STATE).field(RELOCK).field(ATTEMPTS).field(TARGET).field(PAIRED).field(STATUS).field(CommonFields.LOCATION)
            .setting(new Setting<>(RELOCK))
            // Wires and Home Assistant fire them; the Controls tab's locked switch does the same by hand.
            .action(LOCK_ACTION).action(UNLOCK_ACTION).action(TOGGLE_ACTION)
            .withoutControl(LOCK).withoutControl(UNLOCK).withoutControl(TOGGLE)
            // A right-click on the plate toggles it, for players who may use it.
            .use(TOGGLE)
            .event(ATTEMPT_EVENT)
            .entity(Ha.lock("lock", STATE, LOCKED).name(null))
            .entity(Ha.event("attempt", ATTEMPT).name("Attempt"))
            .entity(Ha.sensor("attempts", ATTEMPTS).name("Attempts").stateClass("total_increasing"))
            .entity(Ha.number("relock", RELOCK).name("Auto-relock").range(0, MAX_RELOCK).unit("s").config())
            .entity(Ha.sensor("target", TARGET).name("Target").diagnostic())
            .entity(Ha.sensor("status", STATUS).name("Status").diagnostic())
            .entity(Ha.sensor("location", CommonFields.LOCATION).name("Location").diagnostic().disabledByDefault())
            .preview(SmartLock::preview)
            .status(SmartLock::statusLine)
            .build();

    private SmartLock() {
    }

    /**
     * What Home Assistant shows: jammed while locked without a target in reach, unlocked while unlocked without one,
     * open while a door, trapdoor or gate of the target stands open (left open by a player with access, or open while
     * unlocked), else locked or unlocked.
     *
     * @param open whether a door, trapdoor or gate of the target stands open
     */
    public static LockState state(boolean locked, Status status, boolean open) {
        if (status != Status.OK) return locked ? LockState.JAMMED : LockState.UNLOCKED;
        if (open) return LockState.OPEN;
        return locked ? LockState.LOCKED : LockState.UNLOCKED;
    }

    /**
     * Whether a player may open a locked target and toggle the lock by hand: the owner always; an operator while
     * {@code smartLock.opsBypass} is on; anybody else as the device's access mode lets them use it (private: nobody; claim:
     * whom the claim trusts; public: everybody). {@link DeviceAccess#permits} lets every operator use a device, so the
     * operator is asked here, under the key, and not there.
     *
     * @param claimTrust whether the claim at the lock trusts the player with USE; asked only when it matters
     */
    public static boolean mayPass(boolean owner, boolean op, boolean opsBypass, AccessMode access, BooleanSupplier claimTrust) {
        if (owner) return true;
        if (op && opsBypass) return true;
        return DeviceAccess.permits(MineqttPermission.USE, access, false, false, claimTrust);
    }

    /** The ticks an auto-relock waits after an unlock; 0 for none. */
    public static int relockTicks(int seconds) {
        return Math.clamp(seconds, 0, MAX_RELOCK) * TICKS_PER_SECOND;
    }

    /**
     * Whether a command locks ({@code true}) or unlocks ({@code false}) a lock that is {@code locked} now: {@code locked}
     * true or false (also {@code LOCK}, {@code UNLOCK}, {@code ON}, {@code OFF}, {@code TOGGLE}), or one of the actions
     * {@code lock}, {@code unlock} and {@code toggle}, whose value does not matter. Empty for a command that does neither.
     * The last of these keys a command has wins, in that order.
     */
    public static Optional<Boolean> lockTo(CommandReader command, boolean locked) {
        Optional<Boolean> to = command.text(LOCKED.key()).map(String::trim).flatMap(text -> switch (text.toUpperCase(Locale.ROOT)) {
            case "LOCK", "LOCKED" -> Optional.of(true);
            case "UNLOCK", "UNLOCKED" -> Optional.of(false);
            case Coerce.TOGGLE -> Optional.of(!locked);
            default -> ValueCodecs.bool(text);
        });
        if (command.has(LOCK)) to = Optional.of(true);
        if (command.has(UNLOCK)) to = Optional.of(false);
        if (command.has(TOGGLE)) to = Optional.of(!to.orElse(locked));
        return to;
    }

    /** The attempt event: how and on what, never who. */
    public static JsonObject attempt(Method method, TargetKind target) {
        JsonObject event = ATTEMPT_EVENT.payload();
        event.addProperty(METHOD_KEY, method.getSerializedName());
        event.addProperty(TARGET_KEY, target.getSerializedName());
        return event;
    }

    /**
     * A lock's saved settings and what it knows: locked or not, the relock delay, the attempts counted, its target's
     * block id (empty before it had one), whether it locks a pair, and the ticks until it relocks (0: not counting).
     */
    public record Settings(boolean locked, int relock, int attempts, String target, boolean paired, int relockLeft) {
        /** A new lock is locked, as a padlock snapped onto a door: it protects from the moment it is placed. */
        public static final Settings DEFAULT = new Settings(true, DEFAULT_RELOCK, 0, "", false, 0);
        public static final Codec<Settings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.lenientOptionalFieldOf(LOCKED.key(), true).forGetter(Settings::locked),
                RELOCK.codec().lenientOptionalFieldOf(RELOCK.key(), DEFAULT_RELOCK).forGetter(Settings::relock),
                Codec.INT.lenientOptionalFieldOf(ATTEMPTS.key(), 0).forGetter(Settings::attempts),
                Codec.STRING.lenientOptionalFieldOf(TARGET.key(), "").forGetter(Settings::target),
                Codec.BOOL.lenientOptionalFieldOf(PAIRED.key(), false).forGetter(Settings::paired),
                Codec.INT.lenientOptionalFieldOf("relock_left", 0).forGetter(Settings::relockLeft)
        ).apply(instance, Settings::new));

        public Settings {
            relock = Math.clamp(relock, 0, MAX_RELOCK);
            attempts = Math.clamp(attempts, 0, MAX_ATTEMPTS);
            target = ValueCodecs.sanitizeText(target);
            relockLeft = Math.clamp(relockLeft, 0, relockTicks(MAX_RELOCK));
        }

        /**
         * Locked or unlocked: an unlock starts the relock countdown (if the lock has a delay), a lock ends it. The same
         * state again changes nothing, so a second unlock does not restart the countdown.
         */
        public Settings lockedTo(boolean to) {
            if (to == locked) return this;
            return new Settings(to, relock, attempts, target, paired, to ? 0 : relockTicks(relock));
        }

        /** Another relock delay: an unlocked lock counts down the new delay from now (0 stops it). */
        public Settings relockTo(int seconds) {
            int clamped = Math.clamp(seconds, 0, MAX_RELOCK);
            if (clamped == relock) return this;
            return new Settings(locked, clamped, attempts, target, paired, locked ? 0 : relockTicks(clamped));
        }

        /** One tick of the relock countdown; {@link #relockDue()} tells when it ran out. */
        public Settings tickRelock() {
            if (locked || relockLeft <= 0) return this;
            return new Settings(locked, relock, attempts, target, paired, relockLeft - 1);
        }

        /** Whether the countdown ran out: the lock relocks now. */
        public boolean relockDue() {
            return !locked && relock > 0 && relockLeft == 0;
        }

        public Settings attempted() {
            return new Settings(locked, relock, Math.min(attempts + 1, MAX_ATTEMPTS), target, paired, relockLeft);
        }

        /** What the lock found at its target: the block id (kept while the target is missing) and whether it is a pair. */
        public Settings target(Optional<String> id, boolean pair) {
            String nextTarget = id.orElse(target);
            boolean nextPaired = id.isPresent() && pair;
            // Asked every tick: the same target is the same settings.
            if (nextTarget.equals(target) && nextPaired == paired) return this;
            return new Settings(locked, relock, attempts, nextTarget, nextPaired, relockLeft);
        }

        /** A placed copy (M2 plan D14): the delay stays; it is locked, has counted nothing and finds its own target. */
        public Settings placed() {
            return new Settings(true, relock, 0, "", false, 0);
        }

        /** Writes every readable field but the location. */
        public StateWriter write(StateWriter out, Status status, boolean open) {
            return out.put(LOCKED, locked).put(STATE, state(locked, status, open)).put(RELOCK, relock).put(ATTEMPTS, attempts)
                    .put(TARGET, target).put(PAIRED, paired).put(STATUS, status);
        }
    }

    /** The status of a lock whose target is {@code lockable} now, given whether it ever had one ({@code target} not empty). */
    public static Status status(boolean lockable, String target) {
        if (lockable) return Status.OK;
        return target.isEmpty() ? Status.NO_TARGET : Status.TARGET_MISSING;
    }

    // An unloaded lock's queued command: locked and its state as a loaded lock would show them, the target unchanged.
    private static JsonObject preview(JsonObject state, CommandReader command) {
        JsonObject next = state.deepCopy();
        boolean locked = Optional.ofNullable(state.get(LOCKED.key())).flatMap(Coerce::bool).orElse(true);
        Optional<Boolean> to = lockTo(command, locked);
        command.get(RELOCK).ifPresent(relock -> next.addProperty(RELOCK.key(), relock));
        if (to.isEmpty()) return next;
        next.addProperty(LOCKED.key(), to.get());
        Status status = Optional.ofNullable(state.get(STATUS.key())).map(JsonElement::getAsString)
                .flatMap(name -> Arrays.stream(Status.values()).filter(value -> value.getSerializedName().equals(name)).findFirst())
                .orElse(Status.OK);
        // Locking closes the target; an unlocked target that stood open stays open.
        boolean open = !to.get() && LockState.OPEN.getSerializedName().equals(Optional.ofNullable(state.get(STATE.key())).map(JsonElement::getAsString).orElse(""));
        next.addProperty(STATE.key(), state(to.get(), status, open).getSerializedName());
        return next;
    }

    // The Controls tab's foot: what it locks, or why it locks nothing.
    private static String statusLine(JsonObject state, java.time.Instant now) {
        String target = Optional.ofNullable(state.get(TARGET.key())).map(JsonElement::getAsString).orElse("");
        String status = Optional.ofNullable(state.get(STATUS.key())).map(JsonElement::getAsString).orElse("");
        boolean paired = Optional.ofNullable(state.get(PAIRED.key())).flatMap(Coerce::bool).orElse(false);
        String name = target.contains(":") ? target.substring(target.indexOf(':') + 1).replace('_', ' ') : target;
        if (status.equals(Status.NO_TARGET.getSerializedName())) return "No target";
        if (status.equals(Status.TARGET_MISSING.getSerializedName())) return "Target missing" + (name.isEmpty() ? "" : ": " + name);
        if (name.isEmpty()) return "";
        return "Locks " + name + (paired ? " (pair)" : "");
    }
}

package art.rehra.mineqtt.device.types;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.device.Action;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.EventSpec;
import art.rehra.mineqtt.device.RawLinkSpec;
import art.rehra.mineqtt.device.UnloadedPolicy;
import art.rehra.mineqtt.flow.core.Coerce;
import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.ha.Ha;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The Wall Switch ({@code light_remote_block}): a plate with two buttons, which took over from v1's Light Remote (design
 * §4.4, §7.2). A press, a double press or a hold of either button is an event with the attribute {@code button}
 * ({@code {"event_type":"press","button":"left"}}): Home Assistant sees it as an event entity and as device triggers,
 * and wires carry it to lamps, plugs and outputs, also with the broker down ({@code press} toggles a {@code state} by
 * default). The actions {@code press_left} and {@code press_right} press a button from the screen, Home Assistant, a
 * wire or {@code /mineqtt devices action}. The Raw Link, if the switch has one, publishes each event's name with its
 * button ({@code press_left}), never retained.
 * <p>
 * A hand tells its gestures apart by time ({@link Gestures}); an action is a press at once.
 */
public final class WallSwitch {
    public static final String PRESS = "press";
    public static final String DOUBLE_PRESS = "double_press";
    public static final String HOLD = "hold";
    /** The attribute every event carries: which button. */
    public static final String BUTTON = "button";

    /** One of the two buttons, left and right as seen by a player facing the switch. */
    public enum Button implements StringRepresentable {
        LEFT, RIGHT;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The action that presses it: {@code press_left}, {@code press_right}. */
        public String action() {
            return PRESS + "_" + getSerializedName();
        }
    }

    /** What a hand did to a button. */
    public enum Gesture {
        PRESS(WallSwitch.PRESS, "button_short_press"),
        DOUBLE_PRESS(WallSwitch.DOUBLE_PRESS, "button_double_press"),
        HOLD(WallSwitch.HOLD, "button_long_press");

        private final String eventType;
        private final String triggerType;

        Gesture(String eventType, String triggerType) {
            this.eventType = eventType;
            this.triggerType = triggerType;
        }

        /** The event type, also its out-port. */
        public String eventType() {
            return eventType;
        }

        /** The type of its Home Assistant device trigger, one Home Assistant knows how to name. */
        public String triggerType() {
            return triggerType;
        }
    }

    private static final PortType BUTTON_KIND = PortType.enumOf(Arrays.stream(Button.values()).map(Button::getSerializedName).toList());
    public static final EventSpec PRESS_EVENT = EventSpec.of(PRESS).attribute(BUTTON, BUTTON_KIND);
    public static final EventSpec DOUBLE_PRESS_EVENT = EventSpec.of(DOUBLE_PRESS).attribute(BUTTON, BUTTON_KIND);
    public static final EventSpec HOLD_EVENT = EventSpec.of(HOLD).attribute(BUTTON, BUTTON_KIND);
    public static final Action PRESS_LEFT = Action.press(Button.LEFT.action());
    public static final Action PRESS_RIGHT = Action.press(Button.RIGHT.action());

    public static final DeviceType TYPE = type();

    private WallSwitch() {
    }

    private static DeviceType type() {
        DeviceType.Builder builder = DeviceType.builder(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "wall_switch"))
                // A press queued for later is a stale click, which must never fire (design §3.2).
                .model("Wall Switch").unloaded(UnloadedPolicy.UNAVAILABLE).remote()
                .field(CommonFields.LOCATION)
                .event(PRESS_EVENT).event(DOUBLE_PRESS_EVENT).event(HOLD_EVENT)
                .action(PRESS_LEFT).action(PRESS_RIGHT)
                // A right-click presses the button it hits; the block picks it and tells the gestures apart.
                .use(PRESS_LEFT.key())
                .entity(Ha.event("action", PRESS, DOUBLE_PRESS, HOLD).deviceClass("button").name(null));
        for (Gesture gesture : Gesture.values()) {
            for (Button button : Button.values()) {
                builder.entity(Ha.deviceTrigger(gesture.triggerType(), button.getSerializedName(), gesture.eventType(), BUTTON));
            }
        }
        return builder.entity(Ha.sensor("location", CommonFields.LOCATION).name("Location").diagnostic().disabledByDefault())
                .rawLink(RawLinkSpec.events())
                .build();
    }

    /** The event of a gesture: {@code {"event_type":"press","button":"left"}}. */
    public static JsonObject event(Gesture gesture, Button button) {
        JsonObject event = switch (gesture) {
            case PRESS -> PRESS_EVENT.payload();
            case DOUBLE_PRESS -> DOUBLE_PRESS_EVENT.payload();
            case HOLD -> HOLD_EVENT.payload();
        };
        event.addProperty(BUTTON, button.getSerializedName());
        return event;
    }

    /** What the Raw Link carries for a gesture: the event's name with its button, {@code press_left}. */
    public static String rawPayload(Gesture gesture, Button button) {
        return gesture.eventType() + "_" + button.getSerializedName();
    }

    /**
     * The buttons a command presses: {@code {"press_left":"PRESS"}}, either action or both, each with {@code PRESS} (any
     * case) or an empty value, as {@link Action#parse} gives it. Empty when the command is anything else.
     */
    public static Optional<List<Button>> presses(CommandReader in) {
        List<Button> pressed = new ArrayList<>();
        for (String key : in.keys()) {
            Optional<Button> button = Arrays.stream(Button.values()).filter(candidate -> candidate.action().equals(key)).findFirst();
            String value = in.text(key).orElse(null);
            if (button.isEmpty() || value == null || !(value.isBlank() || value.strip().equalsIgnoreCase(Coerce.PRESS))) return Optional.empty();
            pressed.add(button.get());
        }
        return pressed.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(pressed));
    }

    /**
     * Tells a hand's gestures apart, per button, from the right-clicks the server receives and the game time; pure, so
     * the checks run it. The server sees no key go down or up, only uses: one per click, and while the use key is held,
     * one every {@value #REPEAT_TICKS} ticks (Minecraft repeats a held use key every 4 ticks).
     * <ul>
     *     <li><b>hold</b>: uses at most {@value #REPEAT_TICKS} ticks apart for {@value #HOLD_TICKS} ticks; it fires once,
     *     while the button is still held, and nothing follows when it is let go.</li>
     *     <li><b>double press</b>: a second use within {@value #DOUBLE_TICKS} ticks of the one before, short of a hold; a
     *     click held a fifth of a second repeats once and so counts as two.</li>
     *     <li><b>press</b>: one use and no other for {@value #DOUBLE_TICKS} ticks, so a press is told
     *     {@value #DOUBLE_TICKS} ticks after the click.</li>
     * </ul>
     */
    public static final class Gestures {
        /** Uses at most this far apart are a held use key. */
        public static final int REPEAT_TICKS = 5;
        /** A second use at most this long after the one before makes a double press. */
        public static final int DOUBLE_TICKS = 8;
        /** Holding a button this long is a hold. */
        public static final int HOLD_TICKS = 10;

        private final Map<Button, Tracker> trackers = new EnumMap<>(Button.class);

        public Gestures() {
            for (Button button : Button.values()) trackers.put(button, new Tracker());
        }

        /**
         * A right-click on a button at a game time: a hold it completes, or the gesture before it whose time ran out
         * before a tick told it.
         */
        public Optional<Gesture> use(Button button, long now) {
            return trackers.get(button).use(now);
        }

        /** Every tick: the presses and double presses whose time to wait for another click is over, by button. */
        public Map<Button, Gesture> tick(long now) {
            Map<Button, Gesture> done = new EnumMap<>(Button.class);
            trackers.forEach((button, tracker) -> tracker.tick(now).ifPresent(gesture -> done.put(button, gesture)));
            return done;
        }

        /** Whether the button looks held down: used within the last {@value #REPEAT_TICKS} ticks. */
        public boolean down(Button button, long now) {
            return trackers.get(button).down(now);
        }

        /** Whether nothing is pending, so the ticks have nothing to do. */
        public boolean idle() {
            return trackers.values().stream().allMatch(Tracker::idle);
        }

        /** Forgets every gesture under way. */
        public void clear() {
            trackers.values().forEach(Tracker::clear);
        }
    }

    private static final class Tracker {
        private static final long NONE = Long.MIN_VALUE;
        // The first use of the gesture, the last one, the first of the uses that came without a break, and how many.
        private long start = NONE;
        private long last;
        private long runStart;
        private int uses;
        private boolean held;

        Optional<Gesture> use(long now) {
            // A use after the gesture's time ran out, before a tick ended it, ends it first and starts a new one.
            Optional<Gesture> ended = tick(now);
            if (start == NONE) {
                start = now;
                runStart = now;
                last = now;
                uses = 1;
                return ended;
            }
            if (now - last > Gestures.REPEAT_TICKS) runStart = now;
            last = now;
            if (held) return Optional.empty();
            uses++;
            if (now - runStart >= Gestures.HOLD_TICKS) {
                held = true;
                return Optional.of(Gesture.HOLD);
            }
            return Optional.empty();
        }

        Optional<Gesture> tick(long now) {
            if (start == NONE) return Optional.empty();
            if (held) {
                if (now - last > Gestures.REPEAT_TICKS) clear();
                return Optional.empty();
            }
            if (now - last <= Gestures.DOUBLE_TICKS) return Optional.empty();
            Gesture gesture = uses == 1 ? Gesture.PRESS : Gesture.DOUBLE_PRESS;
            clear();
            return Optional.of(gesture);
        }

        boolean down(long now) {
            return start != NONE && now - last <= Gestures.REPEAT_TICKS;
        }

        boolean idle() {
            return start == NONE;
        }

        void clear() {
            start = NONE;
            uses = 0;
            held = false;
        }
    }
}

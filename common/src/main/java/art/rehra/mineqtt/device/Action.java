package art.rehra.mineqtt.device;

import art.rehra.mineqtt.flow.core.Coerce;
import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.flow.core.PortType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Optional;

/**
 * A command a device type declares besides its writable fields (M2 plan §3.1, §3.8 #1): a switch's state, a Home
 * Assistant button, later a Wall Switch press. Its key is a command key and an in-port id, so every path sends the same
 * command {@code {"<key>": value}} to {@link DeviceHost#applyCommand}: Home Assistant's {@code /set} and
 * {@code /set/<key>}, a group, the screen's {@code DeviceActionC2S}, a wire and the command twin. Like field keys,
 * action keys are frozen.
 *
 * @param toggles whether it is a switch state: {@code TOGGLE} flips it, and an event wired to it toggles it
 */
public record Action(String key, PortType kind, boolean toggles) {
    /** The key Home Assistant commands switches and lights with (z2m style). */
    public static final String STATE = "state";

    public Action {
        if (!Endpoint.isPortKey(key)) throw new IllegalArgumentException("Invalid action key: " + key);
        if (toggles && !(kind instanceof PortType.Bool)) throw new IllegalArgumentException("Action " + key + " toggles but is no switch state");
    }

    /**
     * The switch state of a type with a Home Assistant switch or light: {@code ON}, {@code OFF}, {@code TOGGLE} or a
     * boolean spelling. The type maps it onto its fields (the Redstone Output's ON restores its last level).
     */
    public static Action state() {
        return new Action(STATE, PortType.BOOL, true);
    }

    /** An action that fires, as a Home Assistant button does with {@code PRESS}; an event wired to it fires it too. */
    public static Action press(String key) {
        return new Action(key, PortType.event(key), false);
    }

    /**
     * The command value of a value a player or a command twin sent: {@code ON}, {@code OFF} or {@code TOGGLE} for a
     * switch state, {@code PRESS} for an action that fires (an empty value fires too), the text as sent for a text
     * action, else the value read as a {@code /set/<key>} payload, if it fits the action's kind. Empty when it does not
     * fit.
     */
    public Optional<JsonElement> parse(String value) {
        String text = value.trim();
        if (toggles) {
            if (text.equalsIgnoreCase(Coerce.TOGGLE)) return Optional.of(new JsonPrimitive(Coerce.TOGGLE));
            return Coerce.bool(text).map(on -> new JsonPrimitive(on ? "ON" : "OFF"));
        }
        if (kind instanceof PortType.Event) {
            return text.isEmpty() || text.equalsIgnoreCase(Coerce.PRESS) ? Optional.of(new JsonPrimitive(Coerce.PRESS)) : Optional.empty();
        }
        // Text stays text, also when it looks like a number or a boolean.
        JsonElement json = kind instanceof PortType.Text ? new JsonPrimitive(value) : ValueCodecs.plainValue(text);
        return kind.problem(json).isEmpty() ? Optional.of(json) : Optional.empty();
    }

    /**
     * The value a hand sends, which has none to give (design §7.4): {@code TOGGLE} for a switch state, {@code PRESS}
     * for an action that fires; empty for an action that needs a value.
     */
    public Optional<String> handValue() {
        if (toggles) return Optional.of(Coerce.TOGGLE);
        return kind instanceof PortType.Event ? Optional.of(Coerce.PRESS) : Optional.empty();
    }

    /** The command that performs the action with a {@link #parse parsed} value. */
    public JsonObject command(JsonElement value) {
        JsonObject command = new JsonObject();
        command.add(key, value.deepCopy());
        return command;
    }
}

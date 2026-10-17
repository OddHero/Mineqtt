package art.rehra.mineqtt.ui.device;

import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.types.AlarmPanel;
import art.rehra.mineqtt.network.MineqttNetworking.AlarmKeypadPayload;
import art.rehra.mineqtt.ui.framework.MqttTab;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Optional;

/**
 * The Alarm Panel's keypad, its first tab and the one a right-click opens: ten digit keys, C (clear) and the typed code
 * shown as dots; Arm away, Arm home and Disarm (with the typed code) for players who may use the panel; Set code and
 * Remove code for players who may configure it, while it is disarmed. Typing happens on the client only; a key sends an
 * {@link AlarmKeypadPayload}, and the panel judges it. Server-safe: the pure rules are here, {@code AlarmKeypadTabView}
 * draws it. No slots. Players who may only use the panel (P2) get it too, as it needs USE only.
 */
public class AlarmKeypadTab extends MqttTab {
    public static final String ID = "keypad";

    public AlarmKeypadTab() {
        super(ID);
    }

    /** Whether a type's screen has the keypad: a type whose code is a secret it checks itself, the Alarm Panel. */
    public static boolean appliesTo(DeviceType type) {
        return type.equals(AlarmPanel.TYPE);
    }

    /** The typed entry after a digit key: digits only, at most {@value AlarmPanel#MAX_CODE_LENGTH}. */
    public static String typed(String entry, char digit) {
        if (digit < '0' || digit > '9' || entry.length() >= AlarmPanel.MAX_CODE_LENGTH) return entry;
        return entry + digit;
    }

    /** The typed entry as the keypad shows it: a dot per digit, never the digits. */
    public static String masked(String entry) {
        return "•".repeat(entry.length());
    }

    /** What the keys may do for a viewer, from their grants and the panel's state. */
    public record Keys(boolean arm, boolean disarm, boolean setCode, boolean removeCode) {
        /**
         * Arm while disarmed with a code set; Disarm while not disarmed with an entry; Set code and Remove code for a
         * configurer while disarmed (Set with a whole code typed, Remove while a code is set). Nothing while locked out but
         * the code keys, which end a lockout.
         */
        public static Keys of(DeviceView.Grants grants, JsonObject state, String entry) {
            AlarmPanel.AlarmState alarm = alarmState(state);
            boolean disarmed = alarm == AlarmPanel.AlarmState.DISARMED;
            boolean codeSet = bool(state, AlarmPanel.CODE_SET.key());
            boolean lockedOut = number(state, AlarmPanel.LOCKOUT.key()) > 0;
            boolean configure = grants.configure();
            return new Keys(grants.use() && disarmed && codeSet, grants.use() && !disarmed && !lockedOut && !entry.isEmpty(),
                    configure && disarmed && AlarmPanel.codeProblem(entry).isEmpty(), configure && disarmed && codeSet);
        }
    }

    /** The panel's state in a view's state; disarmed when it has none. */
    public static AlarmPanel.AlarmState alarmState(JsonObject state) {
        String name = Optional.ofNullable(state.get(AlarmPanel.STATE.key())).filter(JsonElement::isJsonPrimitive).map(JsonElement::getAsString).orElse("");
        for (AlarmPanel.AlarmState value : AlarmPanel.AlarmState.values()) {
            if (value.getSerializedName().equals(name)) return value;
        }
        return AlarmPanel.AlarmState.DISARMED;
    }

    public static int number(JsonObject state, String key) {
        JsonElement value = state.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsInt() : 0;
    }

    public static boolean bool(JsonObject state, String key) {
        JsonElement value = state.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.STONE_BUTTON);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.keypad");
    }
}

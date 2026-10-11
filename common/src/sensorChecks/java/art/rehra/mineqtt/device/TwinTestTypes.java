package art.rehra.mineqtt.device;

import art.rehra.mineqtt.ha.Ha;
import art.rehra.mineqtt.twin.TwinAdapter;
import art.rehra.mineqtt.twin.core.Binding;
import art.rehra.mineqtt.twin.core.HaCalls;
import art.rehra.mineqtt.twin.core.PortRead;
import art.rehra.mineqtt.twin.core.ValueMap;
import art.rehra.mineqtt.twin.core.ValuePath;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;

/**
 * A synthetic device type with a twin adapter (M3-7): no shipped type declares one yet, so the checks of bindings, the
 * mirror route table and the reduced discovery bind this one. A level output that mirrors a switch or an
 * {@code input_number} into its {@code power} and forwards switching back, with a {@code fade} setting that is no
 * mirrored port and a {@code pressed} event that may switch a scene, a script or a light.
 */
public final class TwinTestTypes {
    public static final Field<Integer> POWER = Field.level("power", Access.READ_WRITE);
    public static final Field<Integer> FADE = Field.intRange("fade", 0, 10, Access.READ_WRITE);
    public static final String PRESSED = "pressed";
    public static final TwinAdapter ADAPTER = TwinAdapter.builder("power")
            .source((domain, settings) -> Map.of("power", new PortRead(ValuePath.keys(ValuePath.STATE), domain.equals("input_number")
                    ? new ValueMap.LevelByRange(settings.min(), settings.max(), settings.invert())
                    : new ValueMap.LevelByBool(settings.invert()))), "switch", "input_boolean", "input_number")
            .mirrors(Action.STATE)
            .forward(TwinTestTypes::forward)
            .target("target", PRESSED)
            .targetDomains("scene", "script", "light")
            .off("power", new JsonPrimitive(0))
            .shapes(TwinAdapter.Shape.INVERT, TwinAdapter.Shape.RANGE)
            .build();
    public static final DeviceType TYPE = DeviceType.builder(Identifier.fromNamespaceAndPath("mineqtt", "test_mirror"))
            .model("Test Mirror").field(POWER).field(FADE).setting(new Setting<>(FADE)).action(Action.state()).use(Action.STATE)
            .event(EventSpec.of(PRESSED))
            .entity(Ha.switchOf("output", POWER).on(15).off(0).name(null))
            .entity(Ha.number("fade", FADE).range(0, 10))
            .rawLink(RawLinkSpec.in(ValueCodecs.REDSTONE))
            .twin(ADAPTER)
            .build();

    private TwinTestTypes() {
    }

    /** A working binding of the test type to {@code entity} with the default settings. */
    public static Binding bound(String entity, boolean control) {
        return new Binding(ADAPTER.sourceEnd(entity), Map.of(), control, Binding.Settings.DEFAULT, null);
    }

    // Switching through the service-call table: power above 0 is on, the state action's value as it is (TOGGLE included).
    private static List<HaCalls.Outcome> forward(JsonObject command, String domain, HaCalls.Mirror mirror, Binding binding) {
        if (domain.equals("input_number")) {
            return command.has("power") ? List.of(HaCalls.resolve(domain, "value", command.get("power"), mirror)) : List.of();
        }
        JsonElement value = command.has(Action.STATE) ? command.get(Action.STATE)
                : new JsonPrimitive(command.get("power").getAsInt() > 0 ? "ON" : "OFF");
        return List.of(HaCalls.resolve(domain, "state", value, mirror));
    }
}

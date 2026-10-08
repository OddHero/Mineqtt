package art.rehra.mineqtt.device.types;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.device.Access;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.Field;
import art.rehra.mineqtt.device.RawLinkSpec;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.UnloadedPolicy;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.ha.Ha;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;

import java.time.Duration;

/**
 * The Redstone Input ({@code redstone_publisher_block}, block entity {@code publisher_block}): reports the redstone
 * signal it receives (design §4.4, §7.2). Its state follows the signal at most once a second, as sensors do; every
 * rising edge is a {@code pulse} event, so a pulse shorter than that still reaches Home Assistant. The Raw Link
 * publishes v1's {@code true}/{@code false} (or the custom payloads) on every edge, as v1 publishers did.
 */
public final class RedstoneInput {
    /** The event type of a rising edge. */
    public static final String PULSE = "pulse";
    public static final int MAX_SIGNAL = 15;

    public static final Field<Boolean> POWERED = Field.bool("powered", Access.READ);
    public static final Field<Integer> SIGNAL = Field.intRange("signal", 0, MAX_SIGNAL, Access.READ);

    public static final DeviceType TYPE = DeviceType.builder(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "redstone_input"))
            .model("Redstone Input").unloaded(UnloadedPolicy.UNAVAILABLE).minInterval(Duration.ofSeconds(1))
            .field(POWERED).field(SIGNAL).field(CommonFields.LOCATION)
            .entity(Ha.binarySensor("powered", POWERED).deviceClass("power"))
            .entity(Ha.sensor("signal", SIGNAL).stateClass("measurement"))
            .entity(Ha.event(PULSE, PULSE))
            .entity(Ha.sensor("location", CommonFields.LOCATION).name("Location").diagnostic().disabledByDefault())
            .rawLink(RawLinkSpec.out())
            .build();

    private RedstoneInput() {
    }

    /** Writes {@code powered} and {@code signal}. */
    public static StateWriter write(StateWriter out, int signal) {
        return out.put(POWERED, signal > 0).put(SIGNAL, signal);
    }

    /** The event of a rising edge: {@code {"event_type":"pulse","signal":11}}. */
    public static JsonObject pulse(int signal) {
        JsonObject event = new JsonObject();
        event.addProperty("event_type", PULSE);
        event.addProperty(SIGNAL.key(), signal);
        return event;
    }

    /** What v1 publishers sent on an edge: the custom payload of slot 2 or 3 if set, else {@code true} or {@code false}. */
    public static String rawPayload(boolean powered, ValueCodecs.CustomPayloads custom) {
        String customPayload = powered ? custom.on() : custom.off();
        return customPayload != null ? customPayload : Boolean.toString(powered);
    }
}

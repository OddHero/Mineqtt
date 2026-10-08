package art.rehra.mineqtt.mqtt.core;

import java.util.UUID;

/**
 * How one handler subscribes.
 *
 * @param qos            the QoS requested from the broker; handlers sharing a filter get the highest one
 * @param acceptRetained whether stored retained messages are delivered. When false, MQTT 5 subscribes with
 *                       {@code DO_NOT_SEND} (unless another handler on the filter accepts them) and any message
 *                       flagged retained is dropped for this handler, which is what keeps MQTT 3.1.1 safe.
 * @param noLocal        true: this server's own publishes reach the handler exactly once through the local loopback,
 *                       even with the broker down, and never as the broker's copy. false: they arrive only as the
 *                       broker's copy, like any other message.
 * @param maxPayloadBytes larger messages are dropped for this handler
 * @param owner          the subscribing device; the loopback never delivers a message whose origin is the owner.
 *                       May be null.
 */
public record SubOptions(int qos, boolean acceptRetained, boolean noLocal, int maxPayloadBytes, UUID owner) {
    /** Device and group commands: never retained. */
    public static final SubOptions COMMAND = new SubOptions(1, false, false, 16 * 1024, null);
    /** Foreign state a block mirrors: a retained value is that device's current truth. */
    public static final SubOptions MIRROR = new SubOptions(1, true, true, 64 * 1024, null);

    public SubOptions {
        if (qos < 0 || qos > 2) throw new IllegalArgumentException("QoS must be 0, 1 or 2: " + qos);
        if (maxPayloadBytes <= 0) throw new IllegalArgumentException("Payload limit must be positive: " + maxPayloadBytes);
    }

    /** A Raw Link input of a device: no-local plus loopback. */
    public static SubOptions raw(boolean acceptRetained, UUID owner) {
        return new SubOptions(1, acceptRetained, true, 16 * 1024, owner);
    }
}

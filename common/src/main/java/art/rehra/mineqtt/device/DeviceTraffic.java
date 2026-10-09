package art.rehra.mineqtt.device;

import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * What one device sent and received over MQTT on this server (M2-11), for its Diagnostics tab: the last message each way,
 * a ring of one, and counts since the server started. A payload is kept only as the start the tab shows, as text without
 * control characters and formatting codes, never whole; its topic is kept without them too. Server thread only.
 */
public final class DeviceTraffic {
    // More of a payload than the shown text can ever take, so a large message is never decoded whole.
    private static final int MAX_DECODED_BYTES = 4 * DeviceView.MAX_SHOWN_PAYLOAD_BYTES;

    private @Nullable Message lastIn;
    private @Nullable Message lastOut;
    private long received;
    private long dropped;
    private long sent;

    // One message: its topic, its payload's whole size and shown start, and when it went in or out (Util.getMillis).
    private record Message(String topic, int bytes, String shown, long at) {
        static Message of(String topic, byte[] payload, long now) {
            String text = new String(payload, 0, Math.min(payload.length, MAX_DECODED_BYTES), StandardCharsets.UTF_8);
            // Whoever may publish to the broker chooses an inbound topic as much as its payload.
            return new Message(ValueCodecs.sanitizeText(topic), payload.length, DeviceView.cap(ValueCodecs.sanitizeText(text), DeviceView.MAX_SHOWN_PAYLOAD_BYTES), now);
        }

        DeviceView.Traffic view(boolean payload, long now) {
            return new DeviceView.Traffic(topic, bytes, Math.max(0, now - at), payload ? Optional.of(shown) : Optional.empty());
        }
    }

    /** A message on one of the device's topics reached it. */
    public void received(String topic, byte[] payload, long now) {
        lastIn = Message.of(topic, payload, now);
        received++;
    }

    /** The message it received last did nothing: it was no command, the device refused it, or nobody took it. */
    public void dropped() {
        dropped++;
    }

    /** The device published a message. */
    public void sent(String topic, byte[] payload, long now) {
        lastOut = Message.of(topic, payload, now);
        sent++;
    }

    /** The counts as {@code /mineqtt devices info} shows them: {@code 3 in (1 dropped), 7 out}. */
    public String summary() {
        return received + " in (" + dropped + " dropped), " + sent + " out";
    }

    /**
     * What the Diagnostics tab shows of it for one viewer: the payloads only for a viewer who may configure the device
     * (M2 plan §4.2 M2-11), everyone else sees topic, size and age.
     *
     * @param id           the device's UUID; null until its first attach
     * @param detail       what {@link DeviceView.Diagnostics#detail()} says about the availability
     * @param showPayloads whether the viewer may configure the device
     * @param now          {@code Util.getMillis()} now
     */
    public DeviceView.Diagnostics diagnostics(@Nullable UUID id, DeviceView.Availability availability, String detail, boolean showPayloads, long now,
                                              long bridgeDroppedIn, long bridgeDroppedOut) {
        return new DeviceView.Diagnostics(Optional.ofNullable(id), availability, detail,
                Optional.ofNullable(lastIn).map(message -> message.view(showPayloads, now)), Optional.ofNullable(lastOut).map(message -> message.view(showPayloads, now)),
                received, dropped, sent, bridgeDroppedIn, bridgeDroppedOut);
    }
}

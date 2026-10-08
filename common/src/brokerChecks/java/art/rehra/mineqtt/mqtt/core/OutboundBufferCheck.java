package art.rehra.mineqtt.mqtt.core;

import com.google.gson.JsonObject;
import com.hivemq.client.mqtt.exceptions.ConnectionClosedException;
import com.hivemq.client.mqtt.exceptions.MqttClientStateException;
import com.hivemq.client.mqtt.exceptions.MqttEncodeException;
import com.hivemq.client.mqtt.exceptions.MqttSessionExpiredException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link OutboundBuffer}: coalescing and dedupe, edge preservation and caps, the global cap and the offline outbox;
 * plus which publish failures {@link HiveMqttService} hands back to it for the outbox.
 */
public final class OutboundBufferCheck {
    private static final long MILLI = 1_000_000L;
    private static final long SECOND = 1_000 * MILLI;

    static void main(String[] args) {
        stateCoalescingAndDedupe();
        refreshSkipsDedupe();
        edgesArePreserved();
        edgeCapPerOrigin();
        globalCapDefersStates();
        offlineOutbox();
        keepIsNeverEvicted();
        failedSends();
        supersededOutboxEntry();
        noBroker();
        failureClassification();
        System.out.println("Outbound buffer checks passed.");
    }

    private static void stateCoalescingAndDedupe() {
        Fixture fixture = new Fixture(OutboundBuffer.Limits.DEFAULT);
        fixture.buffer.add(state("dev/lamp", 1));
        fixture.buffer.add(state("dev/lamp", 2));
        fixture.buffer.add(state("dev/plug", 7));
        fixture.buffer.add(state("dev/lamp", 3));
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("dev/lamp=3", "dev/plug=7")), "States coalesce to the latest per topic, got " + fixture.sent);
        check(fixture.looped.equals(fixture.sent), "Every released message is looped back once");

        fixture.clearRecords();
        fixture.buffer.add(state("dev/lamp", 3));
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.isEmpty() && fixture.looped.isEmpty(), "Bytes equal to the last release are skipped");

        fixture.buffer.add(state("dev/lamp", 4));
        fixture.buffer.add(state("dev/lamp", 3));
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.isEmpty(), "A change that is undone within one tick is no change");

        fixture.buffer.onConnected();
        fixture.buffer.add(state("dev/lamp", 3));
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("dev/lamp=3")), "After a CONNACK nothing is deduplicated against the old session");
        check(fixture.buffer.dropped() == 0, "Coalescing is not dropping");
    }

    private static void refreshSkipsDedupe() {
        Fixture fixture = new Fixture(OutboundBuffer.Limits.DEFAULT);
        fixture.buffer.add(state("ha/device/a/config", 1));
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        fixture.clearRecords();
        fixture.buffer.add(state("ha/device/a/config", 1).asRefresh());
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("ha/device/a/config=1")), "A refresh is sent although the broker has its bytes, got " + fixture.sent);

        fixture.clearRecords();
        fixture.buffer.add(state("ha/device/a/config", 1).asRefresh());
        fixture.buffer.add(state("ha/device/a/config", 1));
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("ha/device/a/config=1")), "A newer state keeps the refresh it replaces, got " + fixture.sent);

        fixture.clearRecords();
        fixture.buffer.add(state("ha/device/a/config", 1));
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.isEmpty(), "Plain states are deduplicated again afterwards");
    }

    private static void edgesArePreserved() {
        Fixture fixture = new Fixture(OutboundBuffer.Limits.DEFAULT);
        UUID origin = UUID.randomUUID();
        for (String payload : new String[]{"ON", "OFF", "ON", "OFF"}) {
            fixture.buffer.add(Outbound.raw("raw/door", utf8(payload), 0, false, origin));
        }
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("raw/door=ON", "raw/door=OFF", "raw/door=ON", "raw/door=OFF")),
                "Every edge of a fast clock survives in order, got " + fixture.sent);
        fixture.clearRecords();
        fixture.buffer.add(Outbound.raw("raw/door", utf8("OFF"), 0, false, origin));
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("raw/door=OFF")), "Edges are never deduplicated");
    }

    private static void edgeCapPerOrigin() {
        Fixture fixture = new Fixture(OutboundBuffer.Limits.DEFAULT);
        UUID busy = UUID.randomUUID();
        UUID quiet = UUID.randomUUID();
        for (int i = 0; i < 45; i++) fixture.buffer.add(Outbound.raw("raw/busy", utf8("e" + i), 0, false, busy));
        fixture.buffer.add(Outbound.raw("raw/quiet", utf8("q"), 0, false, quiet));
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.size() == 41, "40 edges of the busy origin plus the quiet one, got " + fixture.sent.size());
        check(fixture.sent.get(39).equals("raw/busy=e39") && fixture.sent.get(40).equals("raw/quiet=q"), "The first 40 edges win");
        check(fixture.buffer.dropped() == 5, "Edges over the cap are counted, got " + fixture.buffer.dropped());

        fixture.clearRecords();
        fixture.clock.addAndGet(SECOND);
        for (int i = 0; i < 40; i++) fixture.buffer.add(Outbound.raw("raw/busy", utf8("n" + i), 0, false, busy));
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.size() == 40, "The cap refills over one second");

        fixture.clearRecords();
        for (int i = 0; i < 41; i++) fixture.buffer.add(Outbound.event("dev/btn/action", new JsonObject()));
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.size() == 40, "Origin-less events share a cap per topic");
    }

    private static void globalCapDefersStates() {
        Fixture fixture = new Fixture(new OutboundBuffer.Limits(10, 40, 100, 100));
        for (int i = 0; i < 15; i++) fixture.buffer.add(state("dev/d" + i, i));
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.size() == 10 && fixture.buffer.pendingSize() == 5, "The global cap defers five states");
        check(fixture.buffer.dropped() == 0, "Deferred states are not dropped");
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.size() == 10, "Without time passing the cap stays exhausted");
        fixture.clock.addAndGet(SECOND / 2);
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.size() == 15 && fixture.buffer.pendingSize() == 0, "Half a second later the rest goes out");
        check(fixture.sent.get(14).equals("dev/d14=14"), "Deferred states keep their order");
    }

    private static void offlineOutbox() {
        Fixture fixture = new Fixture(OutboundBuffer.Limits.DEFAULT);
        fixture.buffer.add(Outbound.clear("ha/device/a/config"));
        fixture.buffer.add(state("dev/lamp", 1));
        fixture.buffer.add(Outbound.event("dev/btn/action", new JsonObject()));
        fixture.buffer.add(Outbound.raw("raw/door", utf8("ON"), 0, false, UUID.randomUUID()));
        fixture.flush(OutboundBuffer.Link.DISCONNECTED);
        check(fixture.sent.isEmpty(), "Nothing is sent while disconnected");
        check(fixture.looped.size() == 4, "The local loopback works with the broker down");
        check(fixture.buffer.dropped() == 2, "DROP messages are counted, got " + fixture.buffer.dropped());
        check(fixture.buffer.outboxSize() == 2, "COALESCE and KEEP wait in the outbox");

        fixture.buffer.add(state("dev/lamp", 2));
        fixture.flush(OutboundBuffer.Link.DISCONNECTED);
        check(fixture.buffer.outboxSize() == 2, "The outbox keeps the latest per topic");

        fixture.clearRecords();
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("ha/device/a/config=", "dev/lamp=2")), "The outbox is sent after reconnecting, got " + fixture.sent);
        check(fixture.looped.isEmpty(), "Outbox replays are not looped back a second time");
        check(fixture.buffer.outboxSize() == 0, "The outbox is empty afterwards");

        fixture.buffer.add(state("dev/plug", 1));
        fixture.flush(OutboundBuffer.Link.DISCONNECTED);
        fixture.clearRecords();
        fixture.buffer.onConnected();
        fixture.buffer.add(state("dev/plug", 2));
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("dev/plug=2")), "A newer state supersedes its outbox entry, got " + fixture.sent);
    }

    private static void keepIsNeverEvicted() {
        Fixture fixture = new Fixture(new OutboundBuffer.Limits(500, 40, 100, 2));
        fixture.buffer.add(Outbound.clear("k/1"));
        fixture.buffer.add(state("s/1", 1));
        fixture.buffer.add(state("s/2", 2));
        fixture.flush(OutboundBuffer.Link.DISCONNECTED);
        check(fixture.buffer.outboxSize() == 2 && fixture.buffer.dropped() == 1, "A full outbox evicts its oldest COALESCE entry");
        fixture.buffer.add(Outbound.clear("k/2"));
        fixture.buffer.add(Outbound.clear("k/3"));
        fixture.flush(OutboundBuffer.Link.DISCONNECTED);
        check(fixture.buffer.outboxSize() == 3, "KEEP entries are kept even beyond the capacity");
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("k/1=", "k/2=", "k/3=")), "Every clear reaches the broker, got " + fixture.sent);
    }

    private static void failedSends() {
        Fixture fixture = new Fixture(OutboundBuffer.Limits.DEFAULT);
        Outbound clear = Outbound.clear("ha/device/b/config");
        fixture.buffer.add(clear);
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        // The connection dropped before the PUBACK.
        fixture.buffer.sendFailed(clear);
        fixture.buffer.sendFailed(Outbound.event("dev/btn/action", new JsonObject()));
        fixture.clearRecords();
        fixture.flush(OutboundBuffer.Link.DISCONNECTED);
        check(fixture.buffer.outboxSize() == 1 && fixture.buffer.dropped() == 1, "A failed KEEP goes to the outbox, a failed DROP is counted");
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("ha/device/b/config=")), "The failed clear is sent again");

        fixture.clearRecords();
        fixture.buffer.add(state("dev/lamp", 5));
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        fixture.buffer.sendFailed(state("dev/lamp", 5));
        fixture.buffer.add(state("dev/lamp", 5));
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("dev/lamp=5", "dev/lamp=5")), "After a failure the same bytes are not deduplicated away, got " + fixture.sent);

        // The broker answered with an error reason code: resending would be refused again, every tick.
        fixture.clearRecords();
        long droppedBefore = fixture.buffer.dropped();
        fixture.buffer.countRejected();
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.isEmpty() && fixture.buffer.outboxSize() == 0, "A rejected message is never resent, got " + fixture.sent);
        check(fixture.buffer.dropped() == droppedBefore + 1, "A rejected message is counted as dropped");
    }

    private static void supersededOutboxEntry() {
        Fixture fixture = new Fixture(OutboundBuffer.Limits.DEFAULT);
        // Released while offline: the outbox holds the clear and the dedupe remembers its bytes.
        fixture.buffer.add(Outbound.clear("ha/device/c/config"));
        fixture.flush(OutboundBuffer.Link.DISCONNECTED);
        // The same clear again in the first connected flush, before onConnected() ran for the new connection.
        fixture.buffer.add(Outbound.clear("ha/device/c/config"));
        fixture.clearRecords();
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("ha/device/c/config=")), "A superseded outbox entry still lets its replacement out, got " + fixture.sent);
    }

    private static void noBroker() {
        Fixture fixture = new Fixture(OutboundBuffer.Limits.DEFAULT);
        fixture.buffer.add(Outbound.clear("k/1"));
        fixture.buffer.add(Outbound.raw("raw/door", utf8("ON"), 0, false, UUID.randomUUID()));
        fixture.flush(OutboundBuffer.Link.NONE);
        check(fixture.looped.size() == 2 && fixture.sent.isEmpty(), "Without a broker messages are only looped back");
        check(fixture.buffer.outboxSize() == 0 && fixture.buffer.dropped() == 0, "Without a broker nothing is kept or counted");
    }

    private static void failureClassification() {
        check(HiveMqttService.isConnectionLoss(new CompletionException(new ConnectionClosedException("closed"))),
                "A closed connection is a loss, also wrapped as the publish future reports it");
        check(HiveMqttService.isConnectionLoss(new MqttSessionExpiredException("expired", new IllegalStateException())),
                "An ended session is a loss");
        check(HiveMqttService.isConnectionLoss(new MqttClientStateException("not connected")), "A disconnected client is a loss");
        check(!HiveMqttService.isConnectionLoss(new CompletionException(new MqttEncodeException("too large"))),
                "A packet the client cannot encode is a rejection");
        check(!HiveMqttService.isConnectionLoss(new IllegalStateException("PUBACK 0x87")), "Anything else is a rejection");
    }

    private static Outbound state(String topic, int value) {
        return new Outbound(topic, utf8(Integer.toString(value)), 1, true, OfflinePolicy.COALESCE, Outbound.Delivery.STATE, null);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static final class Fixture implements OutboundBuffer.Sink {
        final AtomicLong clock = new AtomicLong();
        final OutboundBuffer buffer;
        final List<String> sent = new ArrayList<>();
        final List<String> looped = new ArrayList<>();

        Fixture(OutboundBuffer.Limits limits) {
            this.buffer = new OutboundBuffer(limits, clock::get);
        }

        void flush(OutboundBuffer.Link link) {
            buffer.flush(link, this);
        }

        void clearRecords() {
            sent.clear();
            looped.clear();
        }

        @Override
        public void loopback(Outbound msg) {
            looped.add(msg.topic() + "=" + msg.text());
        }

        @Override
        public void send(Outbound msg) {
            sent.add(msg.topic() + "=" + msg.text());
        }
    }
}

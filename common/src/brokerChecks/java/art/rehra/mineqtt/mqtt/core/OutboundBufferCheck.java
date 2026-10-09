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
        retainedRawLevels();
        cappedRetainedLevels();
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
            fixture.buffer.add(Outbound.raw("raw/door", utf8(payload), 0, origin));
        }
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("raw/door=ON", "raw/door=OFF", "raw/door=ON", "raw/door=OFF")),
                "Every edge of a fast clock survives in order, got " + fixture.sent);
        fixture.clearRecords();
        fixture.buffer.add(Outbound.raw("raw/door", utf8("OFF"), 0, origin));
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("raw/door=OFF")), "Edges are never deduplicated");
    }

    private static void edgeCapPerOrigin() {
        Fixture fixture = new Fixture(OutboundBuffer.Limits.DEFAULT);
        UUID busy = UUID.randomUUID();
        UUID quiet = UUID.randomUUID();
        for (int i = 0; i < 45; i++) fixture.buffer.add(Outbound.raw("raw/busy", utf8("e" + i), 0, busy));
        fixture.buffer.add(Outbound.raw("raw/quiet", utf8("q"), 0, quiet));
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.size() == 41, "40 edges of the busy origin plus the quiet one, got " + fixture.sent.size());
        check(fixture.sent.get(39).equals("raw/busy=e39") && fixture.sent.get(40).equals("raw/quiet=q"), "The first 40 edges win");
        check(fixture.buffer.dropped() == 5, "Edges over the cap are counted, got " + fixture.buffer.dropped());

        fixture.clearRecords();
        fixture.clock.addAndGet(SECOND);
        for (int i = 0; i < 40; i++) fixture.buffer.add(Outbound.raw("raw/busy", utf8("n" + i), 0, busy));
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
        check(fixture.buffer.hasWork(false), "Added messages wait for a flush, connected or not");
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.size() == 10 && fixture.buffer.pendingSize() == 5, "The global cap defers five states");
        check(fixture.buffer.hasWork(true), "Deferred states are work for a later flush");
        check(fixture.buffer.dropped() == 0, "Deferred states are not dropped");
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.size() == 10, "Without time passing the cap stays exhausted");
        fixture.clock.addAndGet(SECOND / 2);
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.size() == 15 && fixture.buffer.pendingSize() == 0, "Half a second later the rest goes out");
        check(!fixture.buffer.hasWork(true), "Nothing is left for a flush");
        check(fixture.sent.get(14).equals("dev/d14=14"), "Deferred states keep their order");
    }

    private static void offlineOutbox() {
        Fixture fixture = new Fixture(OutboundBuffer.Limits.DEFAULT);
        fixture.buffer.add(Outbound.clear("ha/device/a/config"));
        fixture.buffer.add(state("dev/lamp", 1));
        fixture.buffer.add(Outbound.event("dev/btn/action", new JsonObject()));
        fixture.buffer.add(Outbound.raw("raw/door", utf8("ON"), 0, UUID.randomUUID()));
        fixture.flush(OutboundBuffer.Link.DISCONNECTED);
        check(fixture.sent.isEmpty(), "Nothing is sent while disconnected");
        check(fixture.looped.size() == 4, "The local loopback works with the broker down");
        check(fixture.buffer.dropped() == 2, "DROP messages are counted, got " + fixture.buffer.dropped());
        check(fixture.buffer.outboxSize() == 2, "COALESCE and KEEP wait in the outbox");
        check(!fixture.buffer.hasWork(false) && fixture.buffer.hasWork(true), "The outbox is work only once connected");

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

    /** Wireless redstone levels: every edge goes out while connected, only the last level waits while not. */
    private static void retainedRawLevels() {
        Fixture fixture = new Fixture(OutboundBuffer.Limits.DEFAULT);
        UUID origin = UUID.randomUUID();
        for (String payload : new String[]{"true", "false", "true"}) {
            fixture.buffer.add(Outbound.rawRetained("raw/garage", utf8(payload), 0, origin));
        }
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("raw/garage=true", "raw/garage=false", "raw/garage=true")),
                "Online, every retained edge is sent in order, got " + fixture.sent);
        check(fixture.looped.equals(fixture.sent), "and looped back once each");

        fixture.clearRecords();
        for (String payload : new String[]{"false", "true", "false"}) {
            fixture.buffer.add(Outbound.rawRetained("raw/garage", utf8(payload), 0, origin));
        }
        fixture.flush(OutboundBuffer.Link.DISCONNECTED);
        check(fixture.looped.size() == 3 && fixture.sent.isEmpty(), "Offline, every edge still reaches local receivers");
        check(fixture.buffer.outboxSize() == 1 && fixture.buffer.dropped() == 0, "and only the last level waits, nothing is counted as dropped");
        fixture.clearRecords();
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("raw/garage=false")), "After the reconnect the broker gets the last level once, got " + fixture.sent);

        fixture.buffer.add(Outbound.rawRetained("raw/garage", utf8("true"), 0, origin));
        fixture.flush(OutboundBuffer.Link.DISCONNECTED);
        fixture.clearRecords();
        fixture.buffer.add(Outbound.rawRetained("raw/garage", utf8("false"), 0, origin));
        fixture.buffer.add(Outbound.raw("raw/garage", utf8("pulse"), 0, origin));
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("raw/garage=false", "raw/garage=pulse")),
                "A newer retained level replaces its outbox entry, got " + fixture.sent);

        fixture.buffer.add(Outbound.rawRetained("raw/garage", utf8("true"), 0, origin));
        fixture.flush(OutboundBuffer.Link.DISCONNECTED);
        fixture.clearRecords();
        fixture.buffer.add(Outbound.raw("raw/garage", utf8("pulse"), 0, origin));
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("raw/garage=true", "raw/garage=pulse")), "A plain edge does not replace it, got " + fixture.sent);
    }

    /** A retained level over its origin's edge cap is held, latest per topic, so the broker still gets the last one. */
    private static void cappedRetainedLevels() {
        Fixture fixture = new Fixture(OutboundBuffer.Limits.DEFAULT);
        UUID origin = UUID.randomUUID();
        // A 0-tick clock: 46 edges in one tick, the last one off.
        for (int i = 0; i < 46; i++) fixture.buffer.add(Outbound.rawRetained("raw/garage", utf8(i % 2 == 0 ? "true" : "false"), 0, origin));
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.size() == 40 && fixture.sent.get(39).equals("raw/garage=false"), "The first 40 edges go out, got " + fixture.sent.size());
        check(fixture.buffer.pendingSize() == 1 && fixture.buffer.dropped() == 5, "Of the six over the cap only the last level is held, got "
                + fixture.buffer.pendingSize() + " held and " + fixture.buffer.dropped() + " dropped");
        fixture.clearRecords();
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.isEmpty(), "It waits while the cap is exhausted");
        fixture.clock.addAndGet(SECOND / 20);
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("raw/garage=false")) && fixture.looped.equals(fixture.sent),
                "Once the cap refills the last level goes out and is looped back, got " + fixture.sent);
        check(fixture.buffer.pendingSize() == 0, "Nothing is held afterwards");

        // The input is destroyed right after a burst: its off payload and a retarget's new level, held per topic.
        fixture.clearRecords();
        fixture.clock.addAndGet(SECOND);
        for (int i = 0; i < 40; i++) fixture.buffer.add(Outbound.rawRetained("raw/garage", utf8(i % 2 == 0 ? "true" : "false"), 0, origin));
        fixture.buffer.add(Outbound.rawRetained("raw/garage", utf8("true"), 0, origin));
        fixture.buffer.add(Outbound.rawRetainedFinal("raw/garage", utf8("false"), 0, origin));
        fixture.buffer.add(Outbound.rawRetained("raw/hall", utf8("true"), 0, origin));
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.size() == 40 && fixture.buffer.pendingSize() == 2, "The off payload and the new topic's level are held");
        fixture.clearRecords();
        fixture.clock.addAndGet(SECOND / 20);
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("raw/garage=false", "raw/hall=true")), "Both go out in order, got " + fixture.sent);

        // A newer level of the topic from another input goes out first: the held one is older and is dropped.
        fixture.clearRecords();
        fixture.clock.addAndGet(SECOND);
        for (int i = 0; i < 41; i++) fixture.buffer.add(Outbound.rawRetained("raw/garage", utf8("busy" + i), 0, origin));
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        long droppedBefore = fixture.buffer.dropped();
        fixture.clearRecords();
        fixture.buffer.add(Outbound.rawRetained("raw/garage", utf8("other"), 0, UUID.randomUUID()));
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        fixture.clock.addAndGet(SECOND);
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("raw/garage=other")) && fixture.buffer.dropped() == droppedBefore + 1,
                "A newer level of another origin supersedes the held one, got " + fixture.sent);

        // Held while the broker comes back: the older outbox entry of the topic is not sent first.
        fixture.clearRecords();
        fixture.buffer.add(Outbound.rawRetained("raw/garage", utf8("true"), 0, origin));
        fixture.flush(OutboundBuffer.Link.DISCONNECTED);
        // The rest of the second's cap.
        for (int i = 0; i < 39; i++) fixture.buffer.add(Outbound.rawRetained("raw/porch", utf8("e" + i), 0, origin));
        fixture.flush(OutboundBuffer.Link.DISCONNECTED);
        fixture.buffer.add(Outbound.rawRetained("raw/garage", utf8("false"), 0, origin));
        fixture.clearRecords();
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("raw/porch=e38")), "Only the porch's last level leaves the outbox, got " + fixture.sent);
        fixture.clearRecords();
        fixture.clock.addAndGet(SECOND / 20);
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("raw/garage=false")), "then the held level, got " + fixture.sent);
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

        // The off payload a destroyed input leaves is never evicted; the levels of live inputs are.
        fixture.clearRecords();
        UUID origin = UUID.randomUUID();
        fixture.buffer.add(Outbound.rawRetainedFinal("raw/gone", utf8("false"), 0, origin));
        fixture.buffer.add(Outbound.rawRetained("raw/a", utf8("true"), 0, origin));
        fixture.buffer.add(Outbound.rawRetained("raw/b", utf8("true"), 0, origin));
        fixture.flush(OutboundBuffer.Link.DISCONNECTED);
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("raw/gone=false", "raw/b=true")), "The off payload survives a full outbox, got " + fixture.sent);
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

        // An input switched on and destroyed just before the connection dropped: both were in flight and fail in order.
        UUID origin = UUID.randomUUID();
        Outbound on = Outbound.rawRetained("raw/garage", utf8("true"), 1, origin);
        Outbound off = Outbound.rawRetainedFinal("raw/garage", utf8("false"), 1, origin);
        fixture.buffer.add(on);
        fixture.buffer.add(off);
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        fixture.buffer.sendFailed(on);
        fixture.buffer.sendFailed(off);
        fixture.clearRecords();
        fixture.flush(OutboundBuffer.Link.DISCONNECTED);
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("raw/garage=false")), "The later of two failures on a topic is sent again, got " + fixture.sent);

        // A level released while disconnected is newer than any failure that arrives after it.
        fixture.clearRecords();
        Outbound inFlight = Outbound.rawRetained("raw/garage", utf8("true"), 1, origin);
        fixture.buffer.add(inFlight);
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        fixture.buffer.add(Outbound.rawRetained("raw/garage", utf8("false"), 1, origin));
        fixture.flush(OutboundBuffer.Link.DISCONNECTED);
        fixture.buffer.sendFailed(inFlight);
        fixture.clearRecords();
        fixture.flush(OutboundBuffer.Link.DISCONNECTED);
        fixture.flush(OutboundBuffer.Link.CONNECTED);
        check(fixture.sent.equals(List.of("raw/garage=false")), "A failure does not replace a newer outbox entry, got " + fixture.sent);

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
        fixture.buffer.add(Outbound.raw("raw/door", utf8("ON"), 0, UUID.randomUUID()));
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

package art.rehra.mineqtt.mqtt.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/** {@link InboundQueue} ordering, bounds and isolation, plus the 3.1.1 echo window ({@link LocalLoopback}). */
public final class InboundQueueCheck {
    private static final long MILLI = 1_000_000L;

    static void main(String[] args) throws InterruptedException {
        fifoWithoutCoalescing();
        overflowDropsOldest();
        byteBudgetDropsOldest();
        dispatcherExceptionIsIsolated();
        budgetAndMessageLimit();
        concurrentProducersKeepTheirOrder();
        echoWindow();
        payloadDecoding();
        System.out.println("Inbound queue checks passed.");
    }

    private static void payloadDecoding() {
        check(inbound("Grüße 🌙").text().equals("Grüße 🌙"), "Payloads decode as UTF-8 regardless of the platform charset");
        check(inbound("{\"state\":\"ON\",\"brightness\":200}").json().orElseThrow().getAsJsonObject().get("brightness").getAsInt() == 200,
                "JSON payloads parse");
        check(inbound("{\"state\":").json().isEmpty(), "Broken JSON is empty, not an exception");
        check(inbound("hello world").json().isEmpty(), "Two bare words are not JSON");
        check(inbound("").json().isEmpty(), "An empty payload is not JSON");
        check(inbound("21.5").json().orElseThrow().getAsDouble() == 21.5, "A bare number is lenient JSON");
    }

    private static Inbound inbound(String payload) {
        return new Inbound("t", utf8(payload), false, false, null, 0);
    }

    private static void fifoWithoutCoalescing() {
        InboundQueue<String> queue = new InboundQueue<>();
        for (int i = 0; i < 100; i++) queue.offer("m" + i);
        // Five messages on one topic are five deliveries: events must never be coalesced away.
        for (int i = 0; i < 5; i++) queue.offer("same");
        List<String> drained = drainAll(queue);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 100; i++) expected.add("m" + i);
        for (int i = 0; i < 5; i++) expected.add("same");
        check(drained.equals(expected), "Queue must deliver every entry in FIFO order");
        check(queue.size() == 0 && queue.dropped() == 0, "Drained queue is empty with nothing dropped");
    }

    private static void overflowDropsOldest() {
        InboundQueue<Integer> queue = new InboundQueue<>(3, System::nanoTime);
        for (int i = 1; i <= 5; i++) queue.offer(i);
        check(queue.dropped() == 2, "Two of five entries overflow a queue of three, got " + queue.dropped());
        check(queue.size() == 3, "The queue stays at its capacity");
        check(drainAll(queue).equals(List.of(3, 4, 5)), "Overflow drops the oldest entries");
    }

    private static void byteBudgetDropsOldest() {
        InboundQueue<String> queue = new InboundQueue<>(100, 10, String::length, System::nanoTime);
        queue.offer("aaaa");
        queue.offer("bbbb");
        check(queue.bytes() == 8 && queue.dropped() == 0, "Entries within the byte budget are kept");
        queue.offer("cccccc");
        check(queue.dropped() == 1 && queue.bytes() == 10, "Going over the budget drops the oldest entry, got " + queue.bytes());
        queue.offer("dddddddd");
        check(queue.dropped() == 3 && queue.size() == 1, "A large entry drops as many old ones as it takes, got " + queue.dropped());
        queue.offer("e".repeat(11));
        check(queue.size() == 0 && queue.bytes() == 0 && queue.dropped() == 5, "An entry larger than the whole budget is dropped itself");
        queue.offer("ff");
        check(drainAll(queue).equals(List.of("ff")) && queue.bytes() == 0, "Draining releases the bytes");
        queue.offer("gg");
        queue.clear();
        check(queue.size() == 0 && queue.bytes() == 0, "Clearing releases the bytes");
    }

    private static void dispatcherExceptionIsIsolated() {
        InboundQueue<Integer> queue = new InboundQueue<>();
        for (int i = 1; i <= 4; i++) queue.offer(i);
        List<Integer> delivered = new ArrayList<>();
        List<Integer> failed = new ArrayList<>();
        int handled = queue.drain(Long.MAX_VALUE, Integer.MAX_VALUE, entry -> {
            if (entry == 2) throw new IllegalStateException("handler bug");
            delivered.add(entry);
        }, (entry, error) -> failed.add(entry));
        check(handled == 4, "Every entry is handed out despite the exception");
        check(delivered.equals(List.of(1, 3, 4)), "Entries after a failing one still arrive, got " + delivered);
        check(failed.equals(List.of(2)), "The failing entry is reported, got " + failed);
    }

    private static void budgetAndMessageLimit() {
        // Every clock read advances one millisecond, so a 3 ms budget allows exactly three entries.
        AtomicLong clock = new AtomicLong();
        InboundQueue<Integer> queue = new InboundQueue<>(100, () -> clock.getAndAdd(MILLI));
        for (int i = 0; i < 10; i++) queue.offer(i);
        int handled = queue.drain(3 * MILLI, Integer.MAX_VALUE, entry -> { }, (entry, error) -> { });
        check(handled == 3, "The time budget stops the drain after three entries, got " + handled);
        check(queue.size() == 7, "The rest stays queued for the next tick");

        handled = queue.drain(Long.MAX_VALUE, 4, entry -> { }, (entry, error) -> { });
        check(handled == 4 && queue.size() == 3, "The message limit stops the drain after four entries");

        handled = queue.drain(0, Integer.MAX_VALUE, entry -> { }, (entry, error) -> { });
        check(handled == 1, "An exhausted budget still hands out one entry so the queue progresses");
    }

    private static void concurrentProducersKeepTheirOrder() throws InterruptedException {
        int producers = 4;
        int perProducer = 10_000;
        InboundQueue<int[]> queue = new InboundQueue<>(producers * perProducer, System::nanoTime);
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            int producer = p;
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perProducer; i++) queue.offer(new int[]{producer, i});
            }));
        }
        start.countDown();
        for (Thread thread : threads) thread.join();
        check(queue.size() == producers * perProducer && queue.dropped() == 0, "No entry is lost below capacity");
        int[] next = new int[producers];
        for (int[] entry : drainAll(queue)) {
            check(entry[1] == next[entry[0]]++, "Producer " + entry[0] + " entries arrive in their offer order");
        }
    }

    private static void echoWindow() {
        AtomicLong clock = new AtomicLong();
        LocalLoopback window = new LocalLoopback(2_000 * MILLI, 100, clock::get);
        UUID origin = UUID.randomUUID();
        byte[] on = utf8("ON");

        window.record("raw/door", on, origin, 1);
        Optional<LocalLoopback.Echo> echo = window.consume("raw/door", on);
        check(echo.isPresent() && origin.equals(echo.get().origin()), "The broker's copy of an own publish is recognised with its origin");
        check(window.consume("raw/door", on).isEmpty(), "An entry is consumed by exactly one copy");

        window.record("raw/door", on, origin, 1);
        check(window.consume("raw/door", utf8("OFF")).isEmpty(), "A different payload is foreign");
        check(window.consume("raw/other", on).isEmpty(), "A different topic is foreign");
        clock.addAndGet(2_001 * MILLI);
        check(window.consume("raw/door", on).isEmpty(), "Entries expire after the window");
        check(window.size() == 0, "Expired entries are purged");

        // Two broker subscriptions that both echo: two copies are expected and each consumes one entry.
        window.record("raw/door", on, null, 2);
        check(window.consume("raw/door", on).isPresent() && window.consume("raw/door", on).isPresent(), "Each expected copy is recognised");
        check(window.consume("raw/door", on).isEmpty(), "A third copy is foreign");

        window.record("raw/door", on, origin, 1);
        window.record("raw/door", on, null, 1);
        check(origin.equals(window.consume("raw/door", on).orElseThrow().origin()), "Equal publishes are consumed oldest first");

        LocalLoopback small = new LocalLoopback(2_000 * MILLI, 2, clock::get);
        small.record("t/1", on, null, 1);
        small.record("t/2", on, null, 1);
        small.record("t/3", on, null, 1);
        check(small.size() == 2 && small.consume("t/1", on).isEmpty() && small.consume("t/3", on).isPresent(),
                "A full window evicts its oldest entry");
    }

    private static <T> List<T> drainAll(InboundQueue<T> queue) {
        List<T> drained = new ArrayList<>();
        queue.drain(Long.MAX_VALUE, Integer.MAX_VALUE, drained::add, (entry, error) -> {
            throw error;
        });
        return drained;
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}

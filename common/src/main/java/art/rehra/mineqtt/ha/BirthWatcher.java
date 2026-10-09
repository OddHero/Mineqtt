package art.rehra.mineqtt.ha;

import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.mqtt.core.Inbound;
import art.rehra.mineqtt.mqtt.core.MqttService;
import art.rehra.mineqtt.mqtt.core.SubOptions;
import art.rehra.mineqtt.mqtt.core.Subscription;
import org.jetbrains.annotations.Nullable;

import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/**
 * Follows Home Assistant's birth message (design §4.2): {@code online} on {@code haBirthTopic} means Home Assistant
 * (re)started, so discovery, availability and state are republished after a random 0-3 s delay, which spreads the
 * load when many bridges share a broker. Births arriving while one is pending fold into it. A retained birth is
 * ignored: the republish after every connect already covers it. What it last heard there, the birth or Home Assistant's
 * last will {@code offline}, is kept for the Bridge Console ({@link #heard()}). Server thread only.
 */
public final class BirthWatcher {
    public static final String ONLINE = "online";
    /** Home Assistant's last will on the birth topic. */
    public static final String OFFLINE = "offline";
    static final long MAX_JITTER_MILLIS = 3_000;
    // Never retained (the connect republishes anyway); a birth is one short word.
    private static final SubOptions BIRTH = new SubOptions(1, false, false, 256, null);

    private final MqttService mqtt;
    private final Runnable republish;
    private final LongSupplier clock;
    private final RandomGenerator random;
    private @Nullable Subscription subscription;
    private String topic;
    private long dueMillis = -1;
    private Heard heard = Heard.NOTHING;
    private boolean stopped;

    /** What Home Assistant last said on the birth topic. */
    public enum Status {
        /** Nothing since the server started or the birth topic changed. */
        NOTHING,
        /** Its birth: it (re)started. */
        BIRTH,
        /** Its last will: it went away. */
        WILL
    }

    /**
     * What Home Assistant last said on the birth topic, and when.
     *
     * @param atMillis when it came, on this watcher's clock; -1 for {@link Status#NOTHING}
     */
    public record Heard(Status status, long atMillis) {
        public static final Heard NOTHING = new Heard(Status.NOTHING, -1);
    }

    /** @param clock milliseconds */
    public BirthWatcher(MqttService mqtt, Runnable republish, LongSupplier clock, RandomGenerator random) {
        this.mqtt = mqtt;
        this.republish = republish;
        this.clock = clock;
        this.random = random;
        this.topic = MineQTTConfig.haBirthTopic;
        this.subscription = mqtt.subscribe(topic, BIRTH, this::onBirth);
    }

    /** After a config change: follows a changed {@code haBirthTopic}. */
    public void configChanged() {
        if (stopped || topic.equals(MineQTTConfig.haBirthTopic)) return;
        if (subscription != null) subscription.close();
        topic = MineQTTConfig.haBirthTopic;
        heard = Heard.NOTHING;
        subscription = mqtt.subscribe(topic, BIRTH, this::onBirth);
    }

    /** What Home Assistant last said on the birth topic since the server started or the topic changed. */
    public Heard heard() {
        return heard;
    }

    /** {@code SERVER_POST}: runs a pending republish once its delay is over. */
    public void tick() {
        if (stopped || dueMillis < 0 || clock.getAsLong() < dueMillis) return;
        dueMillis = -1;
        republish.run();
    }

    /** Whether a republish is waiting for its delay. */
    public boolean pending() {
        return dueMillis >= 0;
    }

    /** {@code SERVER_STOPPING}. */
    public void stop() {
        stopped = true;
        dueMillis = -1;
        heard = Heard.NOTHING;
        if (subscription != null) subscription.close();
        subscription = null;
    }

    private void onBirth(Inbound message) {
        if (stopped || !message.topic().equals(topic)) return;
        String text = message.text().strip();
        if (text.equals(OFFLINE)) heard = new Heard(Status.WILL, clock.getAsLong());
        if (!text.equals(ONLINE)) return;
        heard = new Heard(Status.BIRTH, clock.getAsLong());
        if (dueMillis >= 0) return;
        dueMillis = clock.getAsLong() + random.nextLong(MAX_JITTER_MILLIS + 1);
    }
}

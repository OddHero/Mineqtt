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
 * ignored: the republish after every connect already covers it. Server thread only.
 */
public final class BirthWatcher {
    public static final String ONLINE = "online";
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
    private boolean stopped;

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
        subscription = mqtt.subscribe(topic, BIRTH, this::onBirth);
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
        if (subscription != null) subscription.close();
        subscription = null;
    }

    private void onBirth(Inbound message) {
        if (stopped || !message.topic().equals(topic) || !message.text().strip().equals(ONLINE) || dueMillis >= 0) return;
        dueMillis = clock.getAsLong() + random.nextLong(MAX_JITTER_MILLIS + 1);
    }
}

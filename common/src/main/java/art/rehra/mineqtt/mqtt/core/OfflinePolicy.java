package art.rehra.mineqtt.mqtt.core;

/** What happens to an outbound message while the broker is unreachable. */
public enum OfflinePolicy {
    /** Dropped and counted: events, raw outputs and requests, where a late delivery would be wrong. */
    DROP,
    /** Kept in the outbox, latest per topic; evicted first when the outbox is full. */
    COALESCE,
    /** Kept in the outbox, latest per topic, and never evicted, e.g. discovery clears. */
    KEEP
}

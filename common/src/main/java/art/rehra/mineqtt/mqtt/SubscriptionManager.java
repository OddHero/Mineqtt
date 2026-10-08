package art.rehra.mineqtt.mqtt;

import art.rehra.mineqtt.legacy.LegacySubscriptionFacade;

/** The v1 subscription API of the unmigrated blocks, implemented by {@link LegacySubscriptionFacade}. */
public final class SubscriptionManager {
    private SubscriptionManager() {
    }

    /**
     * Subscribes on the server only. Client block entities and block entities without a level yet are ignored; a
     * subscriber asks again from {@code setLevel}. Subscribing a block to the same topic twice does nothing.
     */
    public static void subscribe(String topic, ICallbackTarget callbackTarget) {
        LegacySubscriptionFacade.subscribe(topic, callbackTarget);
    }

    public static void unsubscribe(String topic, ICallbackTarget callbackTarget) {
        LegacySubscriptionFacade.unsubscribe(topic, callbackTarget);
    }
}

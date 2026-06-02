package art.rehra.mineqtt.ui.framework;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Client-side registry that maps {@link MqttTab#id()} to a factory producing
 * its {@link MqttTabView}. Populated during client mod init.
 */
public final class MqttTabViews {

    private static final Map<String, Function<TabbedMqttScreen, MqttTabView>> FACTORIES = new HashMap<>();

    private MqttTabViews() {
    }

    public static synchronized void register(String tabId, Function<TabbedMqttScreen, MqttTabView> factory) {
        FACTORIES.put(tabId, factory);
    }

    /**
     * Creates a view for the given tab id, or an empty placeholder view if none registered.
     */
    public static MqttTabView create(String tabId, TabbedMqttScreen screen) {
        Function<TabbedMqttScreen, MqttTabView> f = FACTORIES.get(tabId);
        return f != null ? f.apply(screen) : new MqttTabView() {
        };
    }
}

package art.rehra.mineqtt.runtime;

/** The topics of one bridge: everything it owns lives under {@code <base>/<bridgeId>}. */
public record TopicScheme(String base, String bridgeId) {
    /** {@code <base>/<bridgeId>}. */
    public String root() {
        return base + "/" + bridgeId;
    }

    /** {@code online}/{@code offline}, retained; the will of the connection. */
    public String bridgeState() {
        return bridge("state");
    }

    /** Retained JSON describing the bridge: versions, world, protocol and device count. */
    public String bridgeInfo() {
        return bridge("info");
    }

    /** The bridge's retained telemetry JSON: players online, the dropped message counters and the world (M4 plan D6). */
    public String bridgeTelemetry() {
        return root() + "/bridge";
    }

    /** The bridge's player events (M4 plan D7), {@code bridge/event}: QoS 1, never retained, dropped while offline. */
    public String bridgeEvent() {
        return bridge("event");
    }

    /** The filter of the requests the bridge answers, {@code bridge/request/<command>}. */
    public String bridgeRequests() {
        return bridge("request/+");
    }

    /** A request to the bridge, e.g. {@code bridge/request/republish}. */
    public String bridgeRequest(String command) {
        return bridge("request/" + command);
    }

    /** Where the bridge answers a request, {@code bridge/response/<command>}; never retained. */
    public String bridgeResponse(String command) {
        return bridge("response/" + command);
    }

    /**
     * Where the bridge asks Home Assistant's control blueprint to call a service (M3 plan D11, D12), and pings it:
     * {@code bridge/ha_request}, QoS 1, never retained.
     */
    public String haRequest() {
        return bridge("ha_request");
    }

    /** Where the control blueprint answers a request or a ping, {@code bridge/ha_response}: advisory, never retained. */
    public String haResponse() {
        return bridge("ha_response");
    }

    /** Where the bridge asks the catalog blueprint to publish the entity catalog anew (M3 plan D3), {@code bridge/ha_sync}. */
    public String haSync() {
        return bridge("ha_sync");
    }

    /** A device's retained state JSON. */
    public String device(String slug) {
        return root() + "/" + slug;
    }

    /** A device's retained {@code online}/{@code offline}. */
    public String availability(String slug) {
        return device(slug) + "/availability";
    }

    /** A device's events, never retained. */
    public String action(String slug) {
        return device(slug) + "/action";
    }

    /**
     * A player's presence device (M4 plan D10), {@code player/<slug>}: retained JSON, published only while the player
     * consents. {@code player} is a reserved device slug, so no device topic collides with it.
     */
    public String player(String slug) {
        return playerRoot() + "/" + slug;
    }

    /** Where the presence devices' states live, {@code <base>/<bridgeId>/player}. */
    public String playerRoot() {
        return root() + "/player";
    }

    /** A group's retained state JSON. */
    public String group(String slug) {
        return root() + "/group/" + slug;
    }

    /** The four filters every device and group command arrives on, whatever the number of devices. */
    public String[] commandFilters() {
        return new String[]{root() + "/+/set", root() + "/+/set/+", root() + "/group/+/set", root() + "/group/+/set/+"};
    }

    /** A device's Home Assistant device discovery topic, e.g. {@code homeassistant/device/mineqtt_<uuid32>/config}. */
    public static String discoveryConfig(String discoveryPrefix, String objectId) {
        return discoveryPrefix + "/device/" + objectId + "/config";
    }

    private String bridge(String leaf) {
        return root() + "/bridge/" + leaf;
    }
}

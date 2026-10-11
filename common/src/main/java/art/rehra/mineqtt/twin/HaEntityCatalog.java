package art.rehra.mineqtt.twin;

import art.rehra.mineqtt.mqtt.core.Inbound;
import art.rehra.mineqtt.mqtt.core.InboundHandler;
import art.rehra.mineqtt.mqtt.core.MqttService;
import art.rehra.mineqtt.mqtt.core.SubOptions;
import art.rehra.mineqtt.mqtt.core.Subscription;
import art.rehra.mineqtt.twin.core.CatalogParser;
import art.rehra.mineqtt.twin.core.EntityScan;
import art.rehra.mineqtt.twin.core.StatestreamTopic;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Where the twin learns which Home Assistant entities exist (M3 plan D3, D4), owned by the {@link TwinRuntime}: the catalog
 * blueprint's topics, and the statestream fallback scan when no catalog answers. Server thread only.
 * <ul>
 *     <li><b>The catalog</b> ({@code <base>/_mineqtt/catalog/+} and {@code <base>/_mineqtt/status}) opens in its own
 *     SUBSCRIBE {@value #CATALOG_DELAY_MILLIS} ms after the first CONNACK, so the devices' retained messages drain first
 *     through the inbound queue. Its messages go to the twin's entity documents (the gate, the merge, freshness). A
 *     domain list is taken up to {@value #CATALOG_MAX_BYTES} bytes, so one over the 64 KiB cap reaches the parser, which
 *     refuses it visibly instead of the subscription dropping it.</li>
 *     <li><b>The scan</b> ({@code <base>/+/+/state} and {@code <base>/+/+/friendly_name}) opens in another SUBSCRIBE
 *     {@value #SCAN_DELAY_MILLIS} ms after the first CONNACK, only while no catalog status has arrived, into an
 *     {@link EntityScan} capped at {@code twin.catalogMax}. A status arriving later closes it; a status cleared later (the
 *     catalog blueprint removed) opens it again.</li>
 *     <li><b>Off switch</b>: nothing is subscribed while the twin is off (no allowed entities); the catalog also not while
 *     {@code twin.catalog} is false. A config reload that changes any of it closes both and sets them up again under the
 *     new settings at the next drain (the delays after the first CONNACK still apply).</li>
 * </ul>
 * Every topic is checked with the ACL's mirror rule first; nothing filters these messages afterwards.
 */
public final class HaEntityCatalog {
    /** The catalog's own SUBSCRIBE comes this long after the first CONNACK (D4). */
    public static final long CATALOG_DELAY_MILLIS = 5_000;
    /** The scan's SUBSCRIBE comes this long after the first CONNACK, if no status arrived by then (D4). */
    public static final long SCAN_DELAY_MILLIS = 10_000;
    /** The largest catalog message taken from the broker; the parser refuses anything over its own 64 KiB cap. */
    public static final int CATALOG_MAX_BYTES = 4 * CatalogParser.MAX_TOPIC_BYTES;
    private static final SubOptions CATALOG = new SubOptions(1, true, true, CATALOG_MAX_BYTES, null);

    /** What the catalog needs from the twin. */
    public interface Host {
        /** The statestream base now; empty while the twin has none. */
        String base();

        /** Whether the twin is on (allowed entities exist). */
        boolean twinOn();

        /** Whether the catalog blueprint's topics are read ({@code twin.catalog}). */
        boolean catalogEnabled();

        /** Whether a catalog status has arrived, so the gate is in catalog mode. */
        boolean catalogMode();

        /** {@code twin.catalogMax}. */
        int maxEntities();

        /** Whether the ACL lets the server mirror this topic or filter. */
        boolean mayMirror(String filter);
    }

    private final MqttService mqtt;
    private final Host host;
    private final InboundHandler catalogSink;
    private final List<Subscription> catalogSubscriptions = new ArrayList<>();
    private final List<Subscription> scanSubscriptions = new ArrayList<>();
    private EntityScan scan;
    private long openAt = -1;
    private long scanAt = -1;
    // Whether each was set up (or found off or refused) since the start or the last reload: refusals are logged once.
    private boolean catalogTried;
    private boolean scanTried;
    private boolean stopped;

    /**
     * @param catalogSink where catalog and status messages go: the twin's entity documents
     */
    public HaEntityCatalog(MqttService mqtt, Host host, InboundHandler catalogSink) {
        this.mqtt = Objects.requireNonNull(mqtt, "mqtt");
        this.host = Objects.requireNonNull(host, "host");
        this.catalogSink = Objects.requireNonNull(catalogSink, "catalogSink");
        this.scan = new EntityScan(scanBase(), host.maxEntities());
    }

    /** After every CONNACK: the first one schedules the catalog and the scan; the broker keeps them over reconnects. */
    public void connected(long now) {
        if (stopped || openAt >= 0) return;
        openAt = now + CATALOG_DELAY_MILLIS;
        scanAt = now + SCAN_DELAY_MILLIS;
    }

    /** Each drain: opens what is due, and closes the scan once the catalog answers. */
    public void tick(long now) {
        if (stopped) return;
        if (openAt >= 0 && now >= openAt && !catalogTried) {
            catalogTried = true;
            openCatalog();
        }
        if (scanAt >= 0 && now >= scanAt && !scanTried && !host.catalogMode()) {
            scanTried = true;
            openScan();
        }
        if (!scanSubscriptions.isEmpty() && host.catalogMode()) {
            // The catalog answers; should its status be cleared later, the scan opens again.
            closeScan();
            scanTried = false;
        }
    }

    /** Whether a drain has work: a subscription still to open. */
    public boolean pending() {
        if (stopped || openAt < 0) return false;
        return !catalogTried || !scanTried && !host.catalogMode();
    }

    /**
     * After a config reload (and a new base, which the twin applied first): everything closes, and what was open or due
     * opens again under the new settings at the next drain.
     */
    public void configChanged() {
        if (stopped) return;
        closeCatalog();
        closeScan();
        catalogTried = false;
        scanTried = false;
        scan = new EntityScan(scanBase(), host.maxEntities());
    }

    /** The fallback scan's rows; empty while the catalog answers or the scan is not open. */
    public EntityScan scan() {
        return scan;
    }

    /** Whether the catalog's subscriptions are open. */
    public boolean catalogOpen() {
        return !catalogSubscriptions.isEmpty();
    }

    /** Whether the scan's subscriptions are open. */
    public boolean scanOpen() {
        return !scanSubscriptions.isEmpty();
    }

    /** {@code SERVER_STOPPING}. */
    public void stop() {
        stopped = true;
        closeCatalog();
        closeScan();
    }

    private void openCatalog() {
        String base = host.base();
        if (base.isEmpty() || !host.twinOn() || !host.catalogEnabled()) return;
        String normalized = StatestreamTopic.normalizeBase(base);
        subscribe(catalogSubscriptions, normalized + "/" + StatestreamTopic.MINEQTT_LEVEL + "/catalog/+", CATALOG, catalogSink);
        subscribe(catalogSubscriptions, StatestreamTopic.statusTopic(normalized), CATALOG, catalogSink);
    }

    private void openScan() {
        String base = host.base();
        if (base.isEmpty() || !host.twinOn()) return;
        scan = new EntityScan(base, host.maxEntities());
        String prefix = StatestreamTopic.normalizeBase(base) + "/+/+/";
        subscribe(scanSubscriptions, prefix + "state", SubOptions.MIRROR, this::scanned);
        subscribe(scanSubscriptions, prefix + "friendly_name", SubOptions.MIRROR, this::scanned);
    }

    private void subscribe(List<Subscription> into, String filter, SubOptions options, InboundHandler handler) {
        if (!host.mayMirror(filter)) return;
        into.add(mqtt.subscribe(filter, options, handler));
    }

    private void scanned(Inbound message) {
        if (!stopped && !scanSubscriptions.isEmpty()) scan.accept(message.topic(), message.payload());
    }

    private void closeCatalog() {
        catalogSubscriptions.forEach(Subscription::close);
        catalogSubscriptions.clear();
    }

    private void closeScan() {
        scanSubscriptions.forEach(Subscription::close);
        scanSubscriptions.clear();
        scan.clear();
    }

    // The base the scan is made with; a placeholder while the twin has none, which no subscription reaches.
    private String scanBase() {
        String base = host.base();
        return base.isEmpty() ? "mineqtt_twin_off" : base;
    }
}

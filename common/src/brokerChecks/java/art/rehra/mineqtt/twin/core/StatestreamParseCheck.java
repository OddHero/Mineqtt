package art.rehra.mineqtt.twin.core;

import art.rehra.mineqtt.twin.core.EntityDocument.Freshness;
import art.rehra.mineqtt.twin.core.EntityDocument.Origin;
import art.rehra.mineqtt.twin.core.EntityDocuments.Kind;
import art.rehra.mineqtt.twin.core.EntityDocuments.Layer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The twin core's statestream parser and entity documents (M3 plan D1, D3, M3-2), Minecraft-free: every cap §7 row,
 * the real capture of cap §9 replayed in order (with the §8 interleaving), zero length as cleared, raw states, strict
 * attribute JSON, both ISO forms, stale attributes held back while unavailable, null colours, a multi-level base,
 * foreign topics and {@code _}-domains ignored and counted, the catalog with its 64 KiB cap, the D3 merge table with
 * the Home Assistant birth downgrade, the bounds, and the {@code twin.allowedEntities} globs.
 */
public final class StatestreamParseCheck {
    private static final String BASE = "ha_statestream";
    private static final String CAPTURE = "/statestream/capture-2026.10.0.txt";
    private static long clock;

    static void main(String[] args) throws IOException {
        topics();
        stateValues();
        timestamps();
        attributes();
        documents();
        List<Section> capture = loadCapture();
        replay(capture);
        replayRetained(capture);
        multiLevelBase(capture);
        ignoredTopics();
        catalogParse();
        catalogInStore();
        catalogAreas();
        mergeTable();
        mergeInStore();
        birth();
        retainedReplays();
        bounds();
        globs();
        restartSnapshot();
        drift();
        targetsLeaveCatalog();
        catalogHealth();
        scan();
        search();
        System.out.println("Statestream parse checks passed.");
    }

    // <base>/<domain>/<object_id>/<leaf> exactly; the catalog and its status; everything else ignored by reason.
    private static void topics() {
        check(StatestreamTopic.normalizeBase("ha_statestream/").equals(BASE), "One trailing slash is dropped, as Home Assistant adds one");
        check(StatestreamTopic.normalizeBase("home/ha/statestream").equals("home/ha/statestream"), "A base may have several levels");
        for (String bad : new String[]{"", "/", "a/+/b", "a/#", "a//b", "/a", "a\0b"}) {
            check(throwsIllegal(() -> StatestreamTopic.normalizeBase(bad)), "Base refused: " + bad);
        }

        StatestreamTopic.Entity state = entity(BASE, "ha_statestream/light/kitchen/state");
        check(state.domain().equals("light") && state.objectId().equals("kitchen") && state.entityId().equals("light.kitchen")
                && state.kind() == StatestreamTopic.LeafKind.STATE, "State leaf: " + state);
        check(entity(BASE, "ha_statestream/light/kitchen/last_updated").kind() == StatestreamTopic.LeafKind.LAST_UPDATED, "last_updated leaf");
        check(entity(BASE, "ha_statestream/light/kitchen/last_changed").kind() == StatestreamTopic.LeafKind.LAST_CHANGED, "last_changed leaf");
        check(entity(BASE, "ha_statestream/light/kitchen/rgb_color").kind() == StatestreamTopic.LeafKind.ATTRIBUTE, "Attribute leaf");
        check(entity(BASE, "ha_statestream/sensor/x/Friendly Name").leaf().equals("Friendly Name"), "An attribute key is any leaf");
        check(entity(BASE, "ha_statestream/3d_printer/printer_1/state").entityId().equals("3d_printer.printer_1"), "Digits in ids");

        ignored(BASE, "ha_statestream", StatestreamTopic.Reason.OUTSIDE_BASE);
        ignored(BASE, "ha_statestream/", StatestreamTopic.Reason.OUTSIDE_BASE);
        ignored(BASE, "ha_statestreamx/light/a/state", StatestreamTopic.Reason.OUTSIDE_BASE);
        ignored(BASE, "homeassistant/light/a/config", StatestreamTopic.Reason.OUTSIDE_BASE);
        ignored(BASE, "mineqtt/srv/bridge/ha_sync", StatestreamTopic.Reason.OUTSIDE_BASE);
        ignored(BASE, "ha_statestream/light/kitchen", StatestreamTopic.Reason.SHAPE);
        ignored(BASE, "ha_statestream/light/kitchen/state/extra", StatestreamTopic.Reason.SHAPE);
        ignored(BASE, "ha_statestream/light/kitchen/", StatestreamTopic.Reason.SHAPE);
        ignored(BASE, "ha_statestream/light", StatestreamTopic.Reason.SHAPE);
        ignored(BASE, "ha_statestream/_mineqtt/catalog", StatestreamTopic.Reason.RESERVED_DOMAIN);
        ignored(BASE, "ha_statestream/_mineqtt/catalog/light/x", StatestreamTopic.Reason.RESERVED_DOMAIN);
        ignored(BASE, "ha_statestream/_mineqtt/catalog/_x", StatestreamTopic.Reason.RESERVED_DOMAIN);
        ignored(BASE, "ha_statestream/_mineqtt/x/y", StatestreamTopic.Reason.RESERVED_DOMAIN);
        ignored(BASE, "ha_statestream/_other/a/state", StatestreamTopic.Reason.RESERVED_DOMAIN);
        ignored(BASE, "ha_statestream/Light/kitchen/state", StatestreamTopic.Reason.INVALID_ID);
        ignored(BASE, "ha_statestream/light/küche/state", StatestreamTopic.Reason.INVALID_ID);
        ignored(BASE, "ha_statestream/light/kit-chen/state", StatestreamTopic.Reason.INVALID_ID);
        ignored(BASE, "ha_statestream/light//state", StatestreamTopic.Reason.INVALID_ID);
        check(StatestreamTopic.parse(BASE, "ha_statestream/_mineqtt/catalog/light").equals(new StatestreamTopic.Catalog("light")), "Catalog topic");
        check(StatestreamTopic.parse(BASE, "ha_statestream/_mineqtt/status").equals(new StatestreamTopic.Status()), "Status topic");

        String multi = "home/ha/statestream";
        check(entity(multi, "home/ha/statestream/light/kitchen/state").entityId().equals("light.kitchen"), "Multi-level base");
        ignored(multi, "home/ha/light/kitchen/state", StatestreamTopic.Reason.OUTSIDE_BASE);
        ignored(multi, "ha_statestream/light/kitchen/state", StatestreamTopic.Reason.OUTSIDE_BASE);
        ignored(multi, "home/ha/statestream/statestream/light/kitchen/state", StatestreamTopic.Reason.SHAPE);

        String rgb = StatestreamTopic.topic(multi, "light.kitchen", "rgb_color");
        check(rgb.equals("home/ha/statestream/light/kitchen/rgb_color"), "Leaf topic: " + rgb);
        check(entity(multi, rgb).leaf().equals("rgb_color"), "A built leaf topic parses back");
        check(StatestreamTopic.parse(multi, StatestreamTopic.catalogTopic(multi, "light")).equals(new StatestreamTopic.Catalog("light")), "Catalog topic round trip");
        check(StatestreamTopic.parse(multi, StatestreamTopic.statusTopic(multi)) instanceof StatestreamTopic.Status, "Status topic round trip");
        check(throwsIllegal(() -> StatestreamTopic.topic(BASE, "kitchen", "state")), "No leaf topic for a non-entity id");
        check(throwsIllegal(() -> StatestreamTopic.topic(BASE, "light.kitchen", "+")), "No wildcard leaf (D1 #8: per needed leaf, never +)");
        check(throwsIllegal(() -> StatestreamTopic.topic(BASE, "light.kitchen", "a/b")), "No multi-level leaf");
        check(StatestreamTopic.isEntityId("light.kitchen") && !StatestreamTopic.isEntityId("light") && !StatestreamTopic.isEntityId("_x.y")
                && !StatestreamTopic.isEntityId("Light.x") && !StatestreamTopic.isEntityId(null), "Entity ids");
    }

    // Cap §7, state topic table: raw str(state), never JSON; the placeholders; zero length is cleared, not "".
    private static void stateValues() {
        for (String raw : new String[]{"on", "off", "42.5", "50.0", "0.0", "10.62", "12.5", "4", "Turbo Mode", "Grüße \"quoted\" / slash",
                "07:30:00", "2026-10-10T16:59:06.240029+00:00", "2026-10-11T04:48:44+00:00", "unlocking", "unlocked", "cool", "below_horizon",
                "0", "32.9", "Hans"}) {
            check(state(raw).equals(new StatestreamValue.Text(raw)), "State stays raw text: " + raw + " -> " + state(raw));
        }
        // What JSON-first parsing (design §5.2 before its correction) would misread.
        for (String raw : new String[]{"true", "false", "null", "007", "1e+21", "\"quoted\"", "\"\\u00b0C\"", "[1, 2]", "{\"a\": 1}", " on "}) {
            check(state(raw).equals(new StatestreamValue.Text(raw)), "State is never JSON-parsed: " + raw + " -> " + state(raw));
        }
        check(state("unavailable") == StatestreamValue.UNAVAILABLE && state("unknown") == StatestreamValue.UNKNOWN, "Placeholders");
        check(state("Unavailable").equals(new StatestreamValue.Text("Unavailable")), "Placeholders are exact, as Home Assistant writes them");
        StatestreamValue cleared = StatestreamValue.ofPayload(new byte[0]);
        check(cleared == StatestreamValue.CLEARED, "Zero length is Cleared");
        check(!cleared.equals(new StatestreamValue.Text("")) && !cleared.equals(StatestreamValue.UNKNOWN) && !cleared.equals(StatestreamValue.UNAVAILABLE),
                "Cleared is distinct from \"\", unknown and unavailable");
        check(StatestreamValue.of("").equals(new StatestreamValue.Text("")) && StatestreamValue.of("").known(), "The text \"\" is a value (catalog only)");
        check(!cleared.known() && !StatestreamValue.UNKNOWN.known() && !StatestreamValue.UNAVAILABLE.known() && state("on").known(), "Known values");
        check(cleared.json().isJsonNull() && StatestreamValue.UNKNOWN.json().getAsString().equals("unknown")
                && state("Turbo Mode").json().getAsString().equals("Turbo Mode"), "As JSON");
        check(state("on").text().orElseThrow().equals("on") && cleared.text().isEmpty() && StatestreamValue.UNAVAILABLE.text().isEmpty(), "Text");
    }

    // Cap §7 timestamp topics: raw isoformat(), with and without microseconds; lenient, never fatal.
    private static void timestamps() {
        check(HaTime.parse("2026-10-10T16:58:44.639470+00:00").equals(Optional.of(Instant.parse("2026-10-10T16:58:44.639470Z"))), "With fraction");
        check(HaTime.parse("2026-10-11T04:48:44+00:00").equals(Optional.of(Instant.parse("2026-10-11T04:48:44Z"))), "Without fraction");
        check(HaTime.parse("2026-10-11T06:48:44+02:00").equals(Optional.of(Instant.parse("2026-10-11T04:48:44Z"))), "Another offset");
        check(HaTime.parse("2026-10-11T04:48:44Z").isPresent() && HaTime.parse(" 2026-10-11T04:48:44+00:00\n").isPresent(), "Z and whitespace");
        for (String bad : new String[]{"", "2026-10-11T04:48:44", "07:30:00", "yesterday", "2026-13-01T00:00:00+00:00", "\"2026-10-11T04:48:44+00:00\""}) {
            check(HaTime.parse(bad).isEmpty(), "Not a timestamp: " + bad);
        }
        check(HaTime.parse(new JsonPrimitive("2026-10-11T04:48:44.089643+00:00")).isPresent(), "A JSON string (catalog, datetime attributes)");
        check(HaTime.parse(new JsonPrimitive(27000)).isEmpty() && HaTime.parse(JsonNull.INSTANCE).isEmpty() && HaTime.parse((JsonElement) null).isEmpty(),
                "Not a JSON string");
        check(HaTime.format(Instant.parse("2026-10-10T16:58:44.639470Z")).equals("2026-10-10T16:58:44.639470+00:00"), "Formatted with micros");
        check(HaTime.format(Instant.parse("2026-10-11T04:48:44Z")).equals("2026-10-11T04:48:44+00:00"), "Formatted without micros");
        Instant a = Instant.parse("2026-10-10T00:00:00Z");
        Instant b = a.plusNanos(1000);
        check(HaTime.newer(b, a) && !HaTime.newer(a, b) && !HaTime.newer(a, a) && HaTime.newer(a, null) && !HaTime.newer(null, a)
                && !HaTime.newer(null, null), "newer: missing is oldest, ties are not newer");
    }

    // Cap §7 attribute table: strict JSON with ensure_ascii escapes; anything else kept as raw text and counted.
    private static void attributes() {
        check(attr("null").isJsonNull(), "null");
        check(attr("true").getAsJsonPrimitive().isBoolean() && attr("true").getAsBoolean() && !attr("false").getAsBoolean(), "Booleans");
        for (String number : new String[]{"7", "200", "27000", "0.1", "1.0", "0.0", "100.0", "33.333333333333336", "1e+21", "-6.29"}) {
            JsonElement json = attr(number);
            check(json.getAsJsonPrimitive().isNumber() && json.getAsBigDecimal().compareTo(new BigDecimal(number)) == 0, "Number " + number);
        }
        check(attr("1e+21").getAsDouble() == 1e21, "1e+21 is 10^21");
        check(attr("33.333333333333336").getAsString().equals("33.333333333333336"), "A float keeps its digits");
        check(attr("\"mdi:flag\"").getAsString().equals("mdi:flag"), "String");
        check(attr("\"007\"").getAsJsonPrimitive().isString() && attr("\"007\"").getAsString().equals("007"), "A numeric-looking string stays a string");
        check(attr("\"Gr\\u00fc\\u00dfe \\u2713\"").getAsString().equals("Grüße ✓"), "ensure_ascii escapes decode");
        check(attr("\"\\u00b0C\"").getAsString().equals("°C"), "\\u00b0C is °C");
        check(attr("\"2026-10-11T04:48:44.089643+00:00\"").getAsJsonPrimitive().isString(), "A datetime attribute is a JSON string");
        check(attr("[1, 2.5, \"x\"]").equals(JsonParser.parseString("[1,2.5,\"x\"]")), "List");
        check(attr("[1, \"two\", null, true]").getAsJsonArray().get(2).isJsonNull(), "List with null");
        check(attr("[]").getAsJsonArray().isEmpty(), "Empty list");
        check(attr("{\"k\": 1, \"nested\": {\"b\": true}}").getAsJsonObject().getAsJsonObject("nested").get("b").getAsBoolean(), "Dict");
        check(attr("{\"a\": [1, 2], \"b\": null}").getAsJsonObject().get("b").isJsonNull(), "Dict with null");
        check(attr("[{\"confidence\": 98.34, \"name\": \"Hans\", \"age\": 16.0}, {\"name\": \"Eva\"}]").getAsJsonArray().size() == 2, "List of dicts");
        check(attr("\"\"").getAsJsonPrimitive().isString() && attr("\"\"").getAsString().isEmpty(), "\"\" is the empty string");
        // Not strict JSON: kept raw, as a string (defensive, attributes only).
        for (String raw : new String[]{"NaN", "Infinity", "-Infinity", "'single'", "{a:1}", "1 2", "on", "[1,]", "{\"a\":1}x", "// c\n1", "Turbo Mode"}) {
            JsonElement json = attr(raw);
            check(json.isJsonPrimitive() && json.getAsJsonPrimitive().isString() && json.getAsString().equals(raw), "Raw fallback for " + raw + " -> " + json);
            check(EntityDocuments.strictJson(raw).isEmpty(), "Not strict JSON: " + raw);
        }
        EntityDocuments store = store();
        accept(store, "ha_statestream/sensor/raw/state", "1", false);
        accept(store, "ha_statestream/sensor/raw/odd", "NaN", false);
        accept(store, "ha_statestream/sensor/raw/fine", "1", false);
        check(store.counters().rawAttributes() == 1, "A raw fallback is counted: " + store.counters());
        check(store.document("sensor.raw").orElseThrow().value("odd").orElseThrow().getAsString().equals("NaN"), "and kept");
    }

    // The document's accessors: null kept, gated values, the value-path view, copies.
    private static void documents() {
        Map<String, JsonElement> attrs = new LinkedHashMap<>();
        attrs.put("rgb_color", JsonParser.parseString("[255,120,10]"));
        attrs.put("effect", JsonNull.INSTANCE);
        Instant updated = Instant.parse("2026-10-10T16:58:44.639470Z");
        EntityDocument on = new EntityDocument("light.bed", state("on"), attrs, updated, updated, 5, Freshness.LIVE, Origin.STATESTREAM);
        check(on.domain().equals("light") && on.deliverable(), "Domain, deliverable");
        check(on.attribute("effect").orElseThrow().isJsonNull() && on.attribute("missing").isEmpty(), "A null attribute is present, a missing one is not");
        check(on.value("effect").isEmpty() && on.value("missing").isEmpty() && on.value("rgb_color").isPresent(), "value() hides null and missing");
        on.attribute("rgb_color").orElseThrow().getAsJsonArray().set(0, new JsonPrimitive(0));
        check(on.value("rgb_color").orElseThrow().getAsJsonArray().get(0).getAsInt() == 255, "attribute() returns a copy");
        attrs.put("rgb_color", new JsonArray());
        check(on.attributes().get("rgb_color").getAsJsonArray().size() == 3, "The document copied its attributes");
        check(throwsUnsupported(() -> on.attributes().put("x", JsonNull.INSTANCE)), "Attributes are read-only");
        JsonObject json = on.toJson();
        check(json.get("state").getAsString().equals("on") && json.getAsJsonObject("attributes").has("rgb_color")
                && json.get("last_updated").getAsString().equals("2026-10-10T16:58:44.639470+00:00"), "Value-path view: " + json);
        EntityDocument unavailable = new EntityDocument("light.bed", StatestreamValue.UNAVAILABLE, on.attributes(), updated, null, 6,
                Freshness.RETAINED, Origin.STATESTREAM);
        check(!unavailable.deliverable() && unavailable.value("rgb_color").isEmpty() && unavailable.attribute("rgb_color").isPresent(),
                "Unavailable: stored, not delivered");
        check(unavailable.toJson().getAsJsonObject("attributes").isEmpty() && unavailable.toJson().get("last_changed").isJsonNull(),
                "Unavailable: the view has no attributes");
        EntityDocument cleared = new EntityDocument("light.bed", StatestreamValue.CLEARED, Map.of(), null, null, 7, Freshness.LIVE, Origin.STATESTREAM);
        check(cleared.toJson().get("state").isJsonNull(), "Cleared is a JSON null state");
        EntityDocument later = new EntityDocument("light.bed", state("on"), on.attributes(), updated, updated, 99, Freshness.LIVE, Origin.STATESTREAM);
        check(later.sameContent(on) && !later.sameContent(unavailable) && !later.sameContent(null), "sameContent ignores receivedAt");
        check(throwsIllegal(() -> new EntityDocument("light.bed", state("on"), Map.of(), null, null, 0, Freshness.NONE, Origin.STATESTREAM)),
                "A document is never NONE");
        check(throwsIllegal(() -> new EntityDocument("bed", state("on"), Map.of(), null, null, 0, Freshness.LIVE, Origin.STATESTREAM)),
                "A document needs an entity id");
    }

    // Cap §9.1 to §9.10, live, in capture order (each line a live delivery of Home Assistant's retained publish).
    private static void replay(List<Section> capture) {
        EntityDocuments store = store();
        Map<String, Consumer<EntityDocuments>> after = new LinkedHashMap<>();
        after.put("9.1", StatestreamParseCheck::after91);
        after.put("9.2", StatestreamParseCheck::after92);
        after.put("9.3", StatestreamParseCheck::after93);
        after.put("9.4", StatestreamParseCheck::after94);
        after.put("9.5", StatestreamParseCheck::after95);
        after.put("9.6", StatestreamParseCheck::after96);
        after.put("9.7", StatestreamParseCheck::after97);
        after.put("9.8", StatestreamParseCheck::after98);
        after.put("9.9", StatestreamParseCheck::after99);
        after.put("9.10", StatestreamParseCheck::after910);
        after.put("9.10 then", StatestreamParseCheck::after910Then);
        check(capture.stream().map(Section::id).toList().equals(List.copyOf(after.keySet())), "Capture sections: " + capture.stream().map(Section::id).toList());
        for (Section section : capture) {
            if (section.id().equals("9.1")) {
                replay91(store, section);
            } else {
                if (section.id().equals("9.5")) staleBeforeUnavailable(store);
                for (Line line : section.lines()) line.into(store, false);
            }
            after.get(section.id()).accept(store);
        }
        check(store.counters().ignoredTopics() == 0 && store.counters().rawAttributes() == 0 && store.counters().badTimestamps() == 0,
                "The capture is all valid: " + store.counters());
    }

    // §9.1 line by line: the §8 order (all states, then all timestamps, then attributes alternating).
    private static void replay91(EntityDocuments store, Section section) {
        List<Line> lines = section.lines();
        check(lines.get(0).into(store, false).changed().equals(Set.of("input_boolean.ss_flag")), "The first state makes a document");
        lines.get(1).into(store, false);
        EntityDocument lamp = store.document("light.ss_lamp").orElseThrow();
        check(lamp.state().equals(state("on")) && lamp.lastUpdated() == null && lamp.attributes().isEmpty() && lamp.freshness() == Freshness.LIVE,
                "§8: the state arrives before its timestamps and attributes, and is usable at once: " + lamp);
        lines.get(2).into(store, false);
        lines.get(3).into(store, false);
        check(store.document("light.ss_lamp").orElseThrow().lastUpdated().equals(Instant.parse("2026-10-10T16:58:38.285151Z"))
                        && store.document("input_boolean.ss_flag").orElseThrow().lastUpdated().equals(Instant.parse("2026-10-10T16:58:38.284900Z")),
                "§8: interleaved timestamps land on their own entities");
        for (Line line : lines.subList(4, lines.size())) line.into(store, false);
    }

    private static void after91(EntityDocuments store) {
        EntityDocument flag = store.document("input_boolean.ss_flag").orElseThrow();
        check(flag.state().equals(state("on")) && !flag.value("editable").orElseThrow().getAsBoolean()
                && flag.value("icon").orElseThrow().getAsString().equals("mdi:flag")
                && flag.value("friendly_name").orElseThrow().getAsString().equals("SS Flag"), "§9.1 flag: " + flag);
        check(flag.lastChanged().equals(Instant.parse("2026-10-10T16:58:38.284900Z")), "§9.1 flag last_changed");
        EntityDocument lamp = store.document("light.ss_lamp").orElseThrow();
        check(lamp.state().equals(state("on")) && lamp.value("brightness").orElseThrow().getAsInt() == 0,
                "§9.1: a light can be on with brightness 0: " + lamp);
        check(lamp.value("supported_color_modes").orElseThrow().equals(JsonParser.parseString("[\"brightness\"]"))
                && lamp.value("supported_features").orElseThrow().getAsInt() == 0 && lamp.origin() == Origin.STATESTREAM, "§9.1 lamp attributes");
    }

    private static void after92(EntityDocuments store) {
        EntityDocument bed = store.document("light.bed_light").orElseThrow();
        check(bed.state().equals(state("on")) && bed.value("brightness").orElseThrow().getAsInt() == 200, "§9.2 on at 200");
        check(bed.value("rgb_color").orElseThrow().equals(JsonParser.parseString("[255,120,10]")), "§9.2 rgb_color is present although color_mode is hs");
        check(bed.value("color_mode").orElseThrow().getAsString().equals("hs") && bed.value("effect").orElseThrow().getAsString().equals("rainbow"),
                "§9.2 color_mode, effect");
        check(bed.attribute("color_temp_kelvin").orElseThrow().isJsonNull() && bed.value("color_temp_kelvin").isEmpty(), "§9.2 a null kelvin is kept as null");
        check(bed.value("hs_color").orElseThrow().getAsJsonArray().get(0).getAsDouble() == 26.939
                && bed.value("xy_color").orElseThrow().getAsJsonArray().get(1).getAsDouble() == 0.366
                && bed.value("effect_list").orElseThrow().getAsJsonArray().size() == 2, "§9.2 lists");
        check(bed.attributes().size() == 13, "§9.2 13 attributes: " + bed.attributes().keySet());
    }

    private static void after93(EntityDocuments store) {
        EntityDocument rgbww = store.document("light.living_room_rgbww_lights").orElseThrow();
        check(rgbww.value("rgb_color").orElseThrow().equals(JsonParser.parseString("[93,65,40]"))
                && rgbww.value("rgbww_color").orElseThrow().getAsJsonArray().size() == 5, "§9.3 rgbww colour");
        check(rgbww.attribute("color_temp_kelvin").isEmpty() && rgbww.attribute("min_color_temp_kelvin").isEmpty(),
                "§9.3 no kelvin topics for an rgbww-only light");
    }

    private static void after94(EntityDocuments store) {
        EntityDocument bed = store.document("light.bed_light").orElseThrow();
        check(bed.state().equals(state("off")) && bed.deliverable(), "§9.4 off is a real state");
        for (String key : new String[]{"rgb_color", "brightness", "hs_color", "xy_color", "color_mode", "effect", "color_temp_kelvin"}) {
            check(bed.attribute(key).orElseThrow().isJsonNull(), "§9.4 " + key + " is null, not removed");
            check(bed.value(key).isEmpty(), "§9.4 a null " + key + " means keep the last value, never black");
        }
        check(bed.value("supported_color_modes").isPresent() && bed.value("min_color_temp_kelvin").orElseThrow().getAsInt() == 2000,
                "§9.4 the other attributes stay");
        check(bed.lastUpdated() == null, "§9.4 the capture shows no timestamps for this publish, so the old ones were forgotten");
    }

    // Before §9.5: what the broker kept from when the lamp was on (cap §9.5: stale brightness and rgb_color).
    private static void staleBeforeUnavailable(EntityDocuments store) {
        accept(store, "ha_statestream/light/smart_lamp_bf9b/state", "on", true);
        accept(store, "ha_statestream/light/smart_lamp_bf9b/brightness", "180", true);
        accept(store, "ha_statestream/light/smart_lamp_bf9b/rgb_color", "[255, 0, 0]", true);
        check(store.document("light.smart_lamp_bf9b").orElseThrow().value("brightness").isPresent(), "Stale attributes delivered while on");
    }

    private static void after95(EntityDocuments store) {
        EntityDocument lamp = store.document("light.smart_lamp_bf9b").orElseThrow();
        check(lamp.state() == StatestreamValue.UNAVAILABLE && !lamp.deliverable() && lamp.freshness() == Freshness.LIVE, "§9.5 unavailable: " + lamp);
        check(lamp.attribute("brightness").orElseThrow().getAsInt() == 180 && lamp.attribute("rgb_color").isPresent(),
                "§9.5 stale attributes stay stored next to unavailable");
        check(lamp.value("brightness").isEmpty() && lamp.value("rgb_color").isEmpty() && lamp.value("friendly_name").isEmpty()
                && lamp.toJson().getAsJsonObject("attributes").isEmpty(), "§9.5 and are not delivered");
        EntityDocument person = store.document("person.mineqtt_e2e").orElseThrow();
        check(person.state() == StatestreamValue.UNKNOWN && person.value("id").isEmpty(), "§9.5 unknown holds attributes back too");
    }

    private static void after96(EntityDocuments store) {
        EntityDocument level = store.document("input_number.ss_level").orElseThrow();
        check(level.state().equals(state("42.5")) && level.value("min").orElseThrow().getAsDouble() == 0.0
                && level.value("max").orElseThrow().getAsDouble() == 100.0 && level.value("step").orElseThrow().getAsDouble() == 0.5
                && level.value("unit_of_measurement").orElseThrow().getAsString().equals("%") && level.attribute("initial").orElseThrow().isJsonNull(),
                "§9.6 input_number: " + level);
        EntityDocument temperature = store.document("sensor.ss_temperature").orElseThrow();
        check(temperature.state().equals(state("10.62")), "§9.6 a numeric state stays HA's own string");
        check(temperature.value("unit_of_measurement").orElseThrow().getAsString().equals("°C"), "§9.6 \\u00b0C decodes to °C");
        check(temperature.value("a_unicode").orElseThrow().getAsString().equals("Grüße ✓"), "§9.6 escapes");
        check(temperature.value("a_str_num").orElseThrow().getAsJsonPrimitive().isString(), "§9.6 \"007\" stays a string");
        check(temperature.value("a_dict").orElseThrow().getAsJsonObject().getAsJsonObject("nested").get("b").getAsBoolean()
                && temperature.value("a_list").orElseThrow().getAsJsonArray().get(2).getAsString().equals("x")
                && temperature.attribute("a_none").orElseThrow().isJsonNull() && temperature.value("a_bool").orElseThrow().getAsBoolean()
                && temperature.value("a_float").orElseThrow().getAsDouble() == 1.0, "§9.6 nested values");
        EntityDocument lamp = store.document("light.ss_lamp").orElseThrow();
        check(lamp.value("brightness").orElseThrow().getAsInt() == 108 && lamp.value("friendly_name").isPresent(),
                "§9.6 the lamp's other entity's interleaved update lands, and older attributes stay");
    }

    private static void after97(EntityDocuments store) {
        check(store.document("input_select.ss_mode").orElseThrow().state().equals(state("Turbo Mode")), "§9.7 Turbo Mode stays raw");
        check(store.document("input_select.ss_mode").orElseThrow().value("options").orElseThrow().getAsJsonArray().get(2).getAsString().equals("Turbo Mode"),
                "§9.7 options");
        check(store.document("input_text.ss_note").orElseThrow().state().equals(state("Grüße \"quoted\" / slash")), "§9.7 Grüße \"quoted\" / slash stays raw");
        check(store.document("input_datetime.ss_alarm").orElseThrow().state().equals(state("07:30:00"))
                && store.document("input_datetime.ss_alarm").orElseThrow().value("timestamp").orElseThrow().getAsInt() == 27000, "§9.7 time only");
        check(store.document("input_button.ss_button").orElseThrow().state().equals(state("2026-10-10T16:59:06.240029+00:00")), "§9.7 a button's state is text");
        check(store.document("counter.ss_counter").orElseThrow().state().equals(state("4")), "§9.7 counter");
    }

    private static void after98(EntityDocuments store) {
        EntityDocument hvac = store.document("climate.hvac").orElseThrow();
        check(hvac.state().equals(state("cool")) && hvac.value("current_temperature").orElseThrow().getAsInt() == 22
                && hvac.value("temperature").orElseThrow().getAsDouble() == 22.5 && hvac.attribute("target_temp_high").orElseThrow().isJsonNull(),
                "§9.8 climate");
        check(store.document("lock.front_door").orElseThrow().state().equals(state("unlocked")), "§9.8 the second publish wins");
        check(store.document("switch.decorative_lights").orElseThrow().state().equals(state("off")), "§9.8 switch");
        check(store.document("fan.living_room_fan").orElseThrow().value("percentage_step").orElseThrow().getAsString().equals("33.333333333333336")
                && store.document("fan.living_room_fan").orElseThrow().value("percentage").orElseThrow().getAsInt() == 66, "§9.8 fan");
    }

    private static void after99(EntityDocuments store) {
        check(store.document("zone.home").orElseThrow().state().equals(state("0"))
                && store.document("zone.home").orElseThrow().value("persons").orElseThrow().getAsJsonArray().isEmpty(), "§9.9 zone (parsed; HaDomains refuses it)");
        EntityDocument sun = store.document("sun.sun").orElseThrow();
        check(sun.state().equals(state("below_horizon")) && sun.value("elevation").orElseThrow().getAsDouble() == -6.29
                && HaTime.parse(sun.value("next_dawn").orElseThrow()).orElseThrow().equals(Instant.parse("2026-10-11T04:48:44.089643Z")), "§9.9 sun");
        check(store.document("sensor.sun_next_dawn").orElseThrow().state().equals(state("2026-10-11T04:48:44+00:00")), "§9.9 a timestamp sensor's state is text");
    }

    private static void after910(EntityDocuments store) {
        EntityDocument rest = store.document("sensor.ss_rest").orElseThrow();
        check(rest.state().equals(state("12.5")) && rest.attribute("null_attr").orElseThrow().isJsonNull()
                && rest.value("int_attr").orElseThrow().getAsInt() == 7 && rest.value("float_attr").orElseThrow().getAsDouble() == 0.1
                && rest.value("big").orElseThrow().getAsDouble() == 1e21 && !rest.value("bool_attr").orElseThrow().getAsBoolean(), "§9.10 scalars");
        check(rest.value("list_attr").orElseThrow().equals(JsonParser.parseString("[1,\"two\",null,true]"))
                && rest.value("obj_attr").orElseThrow().equals(JsonParser.parseString("{\"a\":[1,2],\"b\":null}")), "§9.10 containers");
        check(rest.value("empty_str").orElseThrow().getAsString().isEmpty(), "§9.10 an empty attribute is JSON \"\"");
    }

    private static void after910Then(EntityDocuments store) {
        EntityDocument rest = store.document("sensor.ss_rest").orElseThrow();
        check(rest.state() == StatestreamValue.CLEARED && !rest.state().equals(new StatestreamValue.Text("")), "§9.10 state \"\" arrives as Cleared: " + rest);
        check(!rest.deliverable() && rest.attribute("drop_me").isPresent(), "§9.10 nothing removes the old attributes; they are held back");
    }

    // The same capture as a broker's retained replay at subscribe time: the same values, all RETAINED. Only a retained
    // state keeps the stored timestamps (a live one starts a new update), so §9.4's bed light, whose publish shows no
    // timestamps, keeps §9.2's when retained.
    private static void replayRetained(List<Section> capture) {
        EntityDocuments live = store();
        EntityDocuments retained = store();
        for (Section section : capture) {
            for (Line line : section.lines()) {
                line.into(live, false);
                line.into(retained, true);
            }
        }
        check(live.entityIds().equals(retained.entityIds()) && live.size() == 21, "Same entities: " + live.entityIds());
        check(live.document("light.bed_light").orElseThrow().lastUpdated() == null
                        && retained.document("light.bed_light").orElseThrow().lastUpdated().equals(Instant.parse("2026-10-10T16:58:44.639470Z")),
                "§9.4 retained keeps §9.2's last_updated");
        for (String id : retained.entityIds()) {
            EntityDocument doc = retained.document(id).orElseThrow();
            EntityDocument liveDoc = live.document(id).orElseThrow();
            check(doc.freshness() == Freshness.RETAINED && retained.freshness(id) == Freshness.RETAINED, "Retained replay is RETAINED: " + id);
            check(doc.state().equals(liveDoc.state()) && doc.attributes().equals(liveDoc.attributes()), "Retained replay folds alike: " + id);
            // Where the live replay forgot them at its last state, the retained one keeps whatever the broker had (if any).
            if (liveDoc.lastUpdated() != null || liveDoc.lastChanged() != null) {
                check(Objects.equals(doc.lastUpdated(), liveDoc.lastUpdated()) && Objects.equals(doc.lastChanged(), liveDoc.lastChanged()),
                        "Retained replay keeps the same timestamps: " + id);
            }
        }
        check(retained.freshness("light.nothing") == Freshness.NONE, "Nothing known is NONE");
    }

    // The whole capture under a three-level base folds exactly like under ha_statestream.
    private static void multiLevelBase(List<Section> capture) {
        EntityDocuments flat = store();
        EntityDocuments deep = new EntityDocuments("home/ha/statestream/", EntityDocuments.Limits.DEFAULT);
        check(deep.base().equals("home/ha/statestream"), "The store normalises its base");
        for (Section section : capture) {
            for (Line line : section.lines()) {
                line.into(flat, false);
                deep.accept("home/ha/statestream" + line.topic().substring(BASE.length()), line.payload(), false, ++clock);
                // The unmoved topic is now foreign.
                deep.accept(line.topic(), line.payload(), false, ++clock);
            }
        }
        check(deep.entityIds().equals(flat.entityIds()), "Multi-level base: same entities");
        for (String id : flat.entityIds()) {
            check(deep.document(id).orElseThrow().toJson().equals(flat.document(id).orElseThrow().toJson()), "Multi-level base: same document " + id);
        }
        int lines = capture.stream().mapToInt(s -> s.lines().size()).sum();
        check(deep.counters().outsideBase() == lines, "The unmoved topics are counted as outside the base: " + deep.counters());
    }

    // Foreign topics and _-domains are ignored, counted by reason, and make no entity.
    private static void ignoredTopics() {
        EntityDocuments store = store();
        check(accept(store, "homeassistant/light/x/config", "{}", false).kind() == Kind.IGNORED, "Foreign topic ignored");
        accept(store, "ha_statestream/light/kitchen", "on", false);
        accept(store, "ha_statestream/light/kitchen/state/x", "on", false);
        accept(store, "ha_statestream/_private/x/state", "on", false);
        accept(store, "ha_statestream/_mineqtt/whatever", "on", false);
        accept(store, "ha_statestream/Light/kitchen/state", "on", false);
        EntityDocuments.Counters counters = store.counters();
        check(counters.outsideBase() == 1 && counters.shape() == 2 && counters.reservedDomain() == 2 && counters.invalidId() == 1
                && counters.ignoredTopics() == 6 && store.size() == 0, "Ignored and counted: " + counters);
        accept(store, "ha_statestream/light/kitchen/last_updated", "yesterday", false);
        check(store.counters().badTimestamps() == 1 && store.document("light.kitchen").orElseThrow().lastUpdated() == null,
                "A bad timestamp is ignored and counted, never fatal");
        accept(store, "ha_statestream/light/kitchen/last_updated", "2026-10-10T16:58:44+00:00", false);
        accept(store, "ha_statestream/light/kitchen/last_updated", "garbage", false);
        check(store.document("light.kitchen").orElseThrow().lastUpdated().equals(Instant.parse("2026-10-10T16:58:44Z")), "and keeps the last good one");
        accept(store, "ha_statestream/light/kitchen/last_updated", "", true);
        check(store.document("light.kitchen").orElseThrow().lastUpdated() == null, "A cleared timestamp topic forgets it");
        accept(store, "ha_statestream/light/kitchen/state", "on", false);
        accept(store, "ha_statestream/light/kitchen/brightness", "12", false);
        accept(store, "ha_statestream/light/kitchen/brightness", "", true);
        check(store.document("light.kitchen").orElseThrow().attribute("brightness").isEmpty(), "A cleared attribute topic removes the attribute");
    }

    // The catalog blueprint's lists and status (D3).
    private static void catalogParse() {
        String list = "[{\"id\":\"light.kitchen\",\"name\":\"Kitchen\",\"area\":\"Kitchen\",\"control\":true,\"state\":\"on\","
                + "\"last_updated\":\"2026-10-10T17:00:00.000001+00:00\",\"attributes\":{\"brightness\":200,\"rgb_color\":[255,120,10]}},"
                + "{\"id\":\"light.porch\",\"state\":\"unavailable\"},"
                + "{\"id\":\"switch.wrong_domain\"},{\"id\":\"Light.Bad\"},42,{\"name\":\"no id\"},{\"id\":\"light.kitchen\"}]";
        CatalogParser.DomainList parsed = CatalogParser.parseDomain("light", bytes(list));
        check(!parsed.refused() && parsed.entries().size() == 2 && parsed.skipped() == 5, "Entries and skips: " + parsed);
        CatalogEntry kitchen = parsed.entries().get(0);
        check(kitchen.entityId().equals("light.kitchen") && kitchen.name().equals("Kitchen") && kitchen.area().equals("Kitchen") && kitchen.control()
                && kitchen.state().equals(state("on")) && kitchen.lastUpdated().equals(Instant.parse("2026-10-10T17:00:00.000001Z"))
                && kitchen.attributes().get("brightness").getAsInt() == 200 && kitchen.domain().equals("light"), "Full entry: " + kitchen);
        CatalogEntry porch = parsed.entries().get(1);
        check(porch.name().equals("light.porch") && porch.area().isEmpty() && !porch.control() && porch.state() == StatestreamValue.UNAVAILABLE
                && porch.lastUpdated() == null && porch.attributes().isEmpty(), "Defaults: " + porch);
        check(CatalogParser.parseDomain("light", bytes("[{\"id\":\"light.a\",\"control\":\"yes\",\"state\":null}]")).entries().get(0).state()
                == StatestreamValue.UNKNOWN, "A null state is unknown");
        check(CatalogParser.parseDomain("light", bytes("[{\"id\":\"light.a\",\"control\":\"yes\"}]")).entries().get(0).control() == false,
                "Control only from a JSON true");
        check(CatalogParser.parseDomain("light", bytes("[]")).entries().isEmpty() && !CatalogParser.parseDomain("light", bytes("[]")).refused(),
                "An empty list is read (and clears the domain)");
        check(CatalogParser.parseDomain("light", new byte[0]).entries().isEmpty() && !CatalogParser.parseDomain("light", new byte[0]).refused(),
                "A cleared list topic is empty");
        for (String bad : new String[]{"{\"id\":\"light.a\"}", "[", "NaN", "[{'id':'light.a'}]", "[] []"}) {
            check(CatalogParser.parseDomain("light", bytes(bad)).refusal() == CatalogParser.Refusal.MALFORMED, "Malformed: " + bad);
        }
        // 64 KiB counts bytes: 'ü' is two.
        byte[] atCap = paddedList(CatalogParser.MAX_TOPIC_BYTES, 'a');
        check(atCap.length == CatalogParser.MAX_TOPIC_BYTES && CatalogParser.parseDomain("light", atCap).entries().size() == 1, "64 KiB exactly is read");
        byte[] overCap = paddedList(CatalogParser.MAX_TOPIC_BYTES + 1, 'ü');
        check(overCap.length > CatalogParser.MAX_TOPIC_BYTES && new String(overCap, StandardCharsets.UTF_8).length() < CatalogParser.MAX_TOPIC_BYTES,
                "The oversized list has fewer characters than bytes");
        CatalogParser.DomainList refused = CatalogParser.parseDomain("light", overCap);
        check(refused.refusal() == CatalogParser.Refusal.TOO_LARGE && refused.entries().isEmpty(), "Over 64 KiB is refused");
        check(throwsIllegal(() -> CatalogParser.parseDomain("_mineqtt", bytes("[]"))), "Domains are checked");

        Optional<CatalogParser.Status> status = CatalogParser.parseStatus(bytes("{\"v\":1,\"blueprint\":\"catalog\",\"generated\":\"2026-10-10T17:00:00+00:00\","
                + "\"read_label\":\"mineqtt_read\",\"control_label\":\"mineqtt_control\",\"entities\":3}"));
        check(status.isPresent() && status.get().version() == 1 && status.get().blueprint().equals("catalog")
                && status.get().generated().equals(Instant.parse("2026-10-10T17:00:00Z")) && status.get().readLabel().equals("mineqtt_read")
                && status.get().controlLabel().equals("mineqtt_control") && status.get().entities() == 3, "Status: " + status);
        Optional<CatalogParser.Status> minimal = CatalogParser.parseStatus(bytes("{\"v\":1}"));
        check(minimal.isPresent() && minimal.get().generated() == null && minimal.get().entities() == -1 && minimal.get().blueprint().isEmpty(), "Minimal status");
        for (String bad : new String[]{"", "{\"blueprint\":\"catalog\"}", "{\"v\":0}", "{\"v\":\"1\"}", "{\"v\":1.5}", "[]", "v=1"}) {
            check(CatalogParser.parseStatus(bytes(bad)).isEmpty(), "Not a status: " + bad);
        }
    }

    // M4 plan D11: the catalog's area names for the rooms, each once as Home Assistant compares them, kept current.
    private static void catalogAreas() {
        EntityDocuments store = store();
        check(store.areas().isEmpty(), "No catalog, no areas");
        accept(store, "ha_statestream/_mineqtt/catalog/light", "[{\"id\":\"light.kitchen\",\"area\":\"Kitchen\",\"state\":\"on\"},"
                + "{\"id\":\"light.counter\",\"area\":\" kitchen \",\"state\":\"on\"},{\"id\":\"light.sofa\",\"area\":\"Living Room\",\"state\":\"on\"},"
                + "{\"id\":\"light.attic\",\"area\":\"\",\"state\":\"on\"},{\"id\":\"light.porch\",\"state\":\"on\"}]", true);
        accept(store, "ha_statestream/_mineqtt/catalog/switch", "[{\"id\":\"switch.fan\",\"area\":\"livingroom\",\"state\":\"off\"},"
                + "{\"id\":\"switch.bed\",\"area\":\"bedroom\",\"state\":\"off\"}]", true);
        check(store.areas().equals(List.of("bedroom", "Kitchen", "Living Room")),
                "Each area once ignoring case and spaces, none for an entity without one, sorted ignoring case: " + store.areas());
        check(store.areas(EntityGlobs.of(List.of("light.*"))).equals(List.of("Kitchen", "Living Room")),
                "Only the areas of the entities the read gate lets through: " + store.areas(EntityGlobs.of(List.of("light.*"))));
        check(store.areas(EntityGlobs.NONE).isEmpty(), "An empty allowlist shows no area");
        accept(store, "ha_statestream/_mineqtt/catalog/switch", "[]", true);
        check(store.areas().equals(List.of("Kitchen", "Living Room")), "A list that changed changes the areas: " + store.areas());
        store.clearCatalog();
        check(store.areas().isEmpty(), "Without the catalog no areas are left");

        check(AreaNames.spelling("living room", List.of("Kitchen", "Living Room")).equals(Optional.of("Living Room"))
                && AreaNames.spelling(" LIVINGROOM ", List.of("Living Room")).equals(Optional.of("Living Room")),
                "An area matches ignoring case and spaces, in Home Assistant's spelling");
        check(AreaNames.spelling("Pantry", List.of("Kitchen")).isEmpty() && AreaNames.spelling(" ", List.of(" ")).isEmpty(),
                "No match, and a blank name matches nothing");
        check(AreaNames.key("Straße Room").equals(AreaNames.key("STRASSE room")), "Case is folded, as Python's casefold does for ß");
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) many.add("Area " + i);
        check(AreaNames.distinct(many).size() == 40 && AreaNames.MAX_SUGGESTIONS == 16,
                "The list keeps every distinct name; a prompt takes at most 16 of them");
    }

    // The catalog in the store: membership, the read gate in both modes, clearing, failing closed on a refusal.
    private static void catalogInStore() {
        EntityDocuments store = store();
        String lights = "[{\"id\":\"light.kitchen\",\"name\":\"Kitchen\",\"control\":true,\"state\":\"on\",\"last_updated\":\"2026-10-10T17:00:00+00:00\","
                + "\"attributes\":{\"brightness\":200}},{\"id\":\"light.porch\",\"state\":\"unavailable\"}]";
        EntityDocuments.Update update = accept(store, "ha_statestream/_mineqtt/catalog/light", lights, true);
        check(update.kind() == Kind.CATALOG && update.changed().equals(Set.of("light.kitchen", "light.porch")), "Catalog update: " + update);
        EntityDocument kitchen = store.document("light.kitchen").orElseThrow();
        check(kitchen.origin() == Origin.CATALOG && kitchen.freshness() == Freshness.RETAINED && kitchen.value("brightness").orElseThrow().getAsInt() == 200,
                "A catalog entry is a document: " + kitchen);
        check(store.catalogEntry("light.kitchen").orElseThrow().control() && store.catalogEntry("light.other").isEmpty()
                && store.catalogEntry("nodot").isEmpty() && store.catalog().get("light").size() == 2, "Membership");
        check(accept(store, "ha_statestream/_mineqtt/catalog/light", lights, true).changed().isEmpty(), "The same list again changes nothing");

        // Statestream-only mode until a status arrives: any entity the allowlist matches.
        check(!store.catalogMode() && store.readable("light.kitchen", EntityGlobs.ALL) && store.readable("light.other", EntityGlobs.ALL),
                "Without a status: statestream-only mode");
        check(!store.readable("light.kitchen", EntityGlobs.NONE), "An empty allowlist reads nothing, catalog or not");
        check(store.shareProblem("light.other").isEmpty(), "Without a status the share gate refuses nothing");
        check(accept(store, "ha_statestream/_mineqtt/status", "{\"v\":1}", true).kind() == Kind.STATUS && store.catalogMode(), "Status switches to catalog mode");
        check(store.readable("light.kitchen", EntityGlobs.ALL) && !store.readable("light.other", EntityGlobs.ALL), "Catalog mode needs membership");
        check(store.shareProblem("light.kitchen").isEmpty() && store.shareProblem("light.other").orElse("").contains("label it MineQTT read"),
                "The bind-time share gate: a listed entity is shared, an unlisted one is refused with the label to set");
        check(!store.readable("light.kitchen", EntityGlobs.of(List.of("switch.*"))), "and the allowlist");
        check(accept(store, "ha_statestream/_mineqtt/status", "garbage", true).kind() == Kind.REFUSED && store.catalogMode()
                && store.counters().badStatus() == 1, "A bad status is counted and keeps the last one");

        // A statestream layer keeps its entity when the catalog drops it; the gate still closes.
        accept(store, "ha_statestream/light/kitchen/state", "off", false);
        update = accept(store, "ha_statestream/_mineqtt/catalog/light", "[]", false);
        check(update.changed().equals(Set.of("light.kitchen", "light.porch")) && store.catalog().isEmpty(), "Unlabelling clears the domain: " + update);
        check(store.document("light.porch").isEmpty() && store.document("light.kitchen").orElseThrow().state().equals(state("off"))
                && !store.readable("light.kitchen", EntityGlobs.ALL), "A catalog-only entity is forgotten; a streamed one stays but is not shared");

        accept(store, "ha_statestream/_mineqtt/catalog/light", lights, false);
        update = accept(store, "ha_statestream/_mineqtt/catalog/light", new String(paddedList(CatalogParser.MAX_TOPIC_BYTES + 1, 'a'), StandardCharsets.UTF_8), false);
        check(update.kind() == Kind.REFUSED && store.catalogRefusals().get("light") == CatalogParser.Refusal.TOO_LARGE && store.catalog().isEmpty()
                && !store.readable("light.kitchen", EntityGlobs.ALL) && store.counters().catalogRefused() == 1, "A refused list fails closed: " + update);
        check(store.shareProblem("light.kitchen").orElse("").contains("its catalog list of light was refused (larger than 64 KiB)")
                        && store.shareProblem("switch.x").orElse("").contains("label it"),
                "Binding into a refused domain names the refusal: " + store.shareProblem("light.kitchen"));
        accept(store, "ha_statestream/_mineqtt/catalog/light", lights, false);
        check(store.catalogRefusals().isEmpty() && store.readable("light.kitchen", EntityGlobs.ALL), "A good list clears the refusal");
        accept(store, "ha_statestream/_mineqtt/catalog/light", "[{\"id\":\"switch.x\"}]", false);
        check(store.counters().catalogSkipped() == 1, "Skipped entries are counted");

        check(accept(store, "ha_statestream/_mineqtt/status", "", true).kind() == Kind.STATUS && !store.catalogMode(),
                "A cleared status falls back to statestream-only mode");
        store.clear();
        check(store.size() == 0 && store.catalog().isEmpty() && !store.catalogMode() && store.counters().badStatus() == 1, "clear() keeps the counters");

        // twin.catalog turned off by a reload: the catalog layer, refusals and status go, the statestream layer stays.
        EntityDocuments off = store();
        accept(off, "ha_statestream/light/kitchen/state", "on", false);
        accept(off, "ha_statestream/_mineqtt/catalog/light", lights, true);
        accept(off, "ha_statestream/_mineqtt/catalog/switch", new String(paddedList(CatalogParser.MAX_TOPIC_BYTES + 1, 'a'), StandardCharsets.UTF_8), true);
        accept(off, "ha_statestream/_mineqtt/status", "{\"v\":1}", true);
        check(off.catalogMode() && !off.readable("light.other", EntityGlobs.ALL) && off.catalogRefusals().containsKey("switch"), "In catalog mode before");
        Set<String> cleared = off.clearCatalog();
        check(cleared.equals(Set.of("light.kitchen", "light.porch")) && !off.catalogMode() && off.status().isEmpty() && off.catalog().isEmpty()
                && off.catalogRefusals().isEmpty(), "clearCatalog() leaves catalog mode: " + cleared);
        check(off.document("light.porch").isEmpty() && off.document("light.kitchen").orElseThrow().origin() == Origin.STATESTREAM
                        && off.document("light.kitchen").orElseThrow().state().equals(state("on")) && off.catalogEntry("light.kitchen").isEmpty(),
                "A catalog-only entity is forgotten; a streamed one keeps its statestream layer");
        check(off.readable("light.kitchen", EntityGlobs.ALL) && off.readable("light.other", EntityGlobs.ALL) && !off.readable("light.other", EntityGlobs.NONE),
                "The gate is statestream-only: the allowlist alone decides");
        check(off.clearCatalog().isEmpty(), "Clearing again changes nothing");
    }

    // The D3 merge table, pure.
    private static void mergeTable() {
        Instant t1 = Instant.parse("2026-10-10T10:00:00Z");
        Instant t2 = Instant.parse("2026-10-10T11:00:00Z");
        Instant t3 = Instant.parse("2026-10-10T12:00:00Z");
        mergeRow(Layer.ABSENT, Layer.ABSENT, null, "neither");
        mergeRow(Layer.of(false, null), Layer.ABSENT, Origin.STATESTREAM, "statestream alone");
        mergeRow(Layer.ABSENT, Layer.of(false, null), Origin.CATALOG, "catalog alone");
        mergeRow(Layer.of(false, t1), Layer.of(false, t2), Origin.CATALOG, "retained older than the catalog loses");
        mergeRow(Layer.of(false, t3), Layer.of(false, t2), Origin.STATESTREAM, "retained newer than the catalog wins");
        mergeRow(Layer.of(false, t2), Layer.of(true, t2), Origin.STATESTREAM, "a tie keeps statestream (the catalog must be newer)");
        mergeRow(Layer.of(false, null), Layer.of(false, t2), Origin.CATALOG, "retained without last_updated loses");
        mergeRow(Layer.of(true, null), Layer.of(true, t2), Origin.STATESTREAM, "live without last_updated always applies");
        mergeRow(Layer.of(true, t1), Layer.of(false, t2), Origin.CATALOG, "a newer catalog replaces even a live value");
        mergeRow(Layer.of(true, t3), Layer.of(true, t2), Origin.STATESTREAM, "live and newer wins");
        mergeRow(Layer.of(false, null), Layer.of(true, null), Origin.STATESTREAM, "a catalog without last_updated never replaces");
        mergeRow(Layer.of(false, t1), Layer.of(true, null), Origin.STATESTREAM, "a catalog without last_updated never replaces a dated value");
    }

    // The merge table through the store, with statestream's real order (state, then last_updated, then attributes).
    private static void mergeInStore() {
        String t1 = "2026-10-10T10:00:00+00:00";
        String t2 = "2026-10-10T11:00:00+00:00";
        String t3 = "2026-10-10T12:00:00.500000+00:00";

        // Retained statestream, then a newer catalog: the catalog wins.
        EntityDocuments store = store();
        stream(store, "light.a", "on", t1, true);
        catalog(store, "light.a", "off", t2, true);
        check(origin(store, "light.a") == Origin.CATALOG && stateOf(store, "light.a").equals(state("off")), "Retained older loses to the catalog");
        // An older catalog loses.
        stream(store, "light.b", "on", t3, true);
        catalog(store, "light.b", "off", t2, true);
        check(origin(store, "light.b") == Origin.STATESTREAM && stateOf(store, "light.b").equals(state("on")), "Retained newer beats the catalog");

        // Catalog first, then a retained state: it waits for its last_updated (missing loses), then wins when newer.
        store = store();
        catalog(store, "light.c", "off", t2, true);
        accept(store, "ha_statestream/light/c/state", "on", true);
        check(origin(store, "light.c") == Origin.CATALOG, "A retained state without its timestamp yet loses to the catalog");
        accept(store, "ha_statestream/light/c/last_updated", t3, true);
        check(origin(store, "light.c") == Origin.STATESTREAM && stateOf(store, "light.c").equals(state("on")), "Its newer last_updated then wins");
        accept(store, "ha_statestream/light/c/state", "on", true);
        accept(store, "ha_statestream/light/c/last_updated", t1, true);
        check(origin(store, "light.c") == Origin.CATALOG, "An older retained replay loses again");

        // A live state applies at once, before its timestamp.
        catalog(store, "light.d", "off", t2, false);
        stream(store, "light.d", "on", t1, true);
        check(origin(store, "light.d") == Origin.CATALOG, "Retained older loses");
        EntityDocuments.Update update = accept(store, "ha_statestream/light/d/state", "on", false);
        check(update.changed().equals(Set.of("light.d")) && origin(store, "light.d") == Origin.STATESTREAM
                && store.freshness("light.d") == Freshness.LIVE, "A live value always applies");
        accept(store, "ha_statestream/light/d/last_updated", t3, false);
        check(origin(store, "light.d") == Origin.STATESTREAM && store.document("light.d").orElseThrow().lastUpdated().equals(Instant.parse("2026-10-10T12:00:00.5Z")),
                "and keeps applying with its timestamp");

        // Statestream without timestamps (publish_timestamps off): retained loses to the catalog, live wins.
        store = store();
        accept(store, "ha_statestream/light/e/state", "on", true);
        catalog(store, "light.e", "off", t2, true);
        check(origin(store, "light.e") == Origin.CATALOG, "Retained without timestamps loses");
        accept(store, "ha_statestream/light/e/state", "on", false);
        check(origin(store, "light.e") == Origin.STATESTREAM, "Live without timestamps wins");

        // A catalog without last_updated never replaces.
        store = store();
        stream(store, "light.f", "on", t1, true);
        catalog(store, "light.f", "off", null, false);
        check(origin(store, "light.f") == Origin.STATESTREAM, "Undated catalog never replaces");
        // A tie keeps statestream, with its full attribute set.
        stream(store, "light.g", "on", t2, true);
        accept(store, "ha_statestream/light/g/effect", "\"rainbow\"", true);
        check(store.freshness("light.g") == Freshness.RETAINED, "Retained statestream alone is RETAINED");
        catalog(store, "light.g", "on", t2, false);
        check(origin(store, "light.g") == Origin.STATESTREAM && store.document("light.g").orElseThrow().value("effect").isPresent(), "A tie keeps statestream");
        check(store.freshness("light.g") == Freshness.LIVE, "and a live catalog of the same update makes it LIVE (the normal state after ha_sync)");
        catalog(store, "light.g", "on", t2, true);
        check(origin(store, "light.g") == Origin.STATESTREAM && store.freshness("light.g") == Freshness.RETAINED, "A retained catalog vouches for nothing");
        // A live catalog older than the retained value keeps it, and vouches for nothing either.
        stream(store, "light.i", "on", t3, true);
        catalog(store, "light.i", "off", t2, false);
        check(origin(store, "light.i") == Origin.STATESTREAM && store.freshness("light.i") == Freshness.RETAINED, "An older live catalog does not make it LIVE");
        // An undated live catalog vouches for nothing.
        stream(store, "light.j", "on", t2, true);
        catalog(store, "light.j", "on", null, false);
        check(origin(store, "light.j") == Origin.STATESTREAM && store.freshness("light.j") == Freshness.RETAINED, "An undated live catalog does not make it LIVE");
        // A catalog-only document has its own attributes and no last_changed.
        catalog(store, "light.h", "on", t2, false);
        EntityDocument h = store.document("light.h").orElseThrow();
        check(h.origin() == Origin.CATALOG && h.lastChanged() == null && h.lastUpdated().equals(Instant.parse("2026-10-10T11:00:00Z"))
                && h.value("brightness").orElseThrow().getAsInt() == 1, "Catalog document: " + h);
    }

    // Home Assistant's birth downgrades every LIVE value until the next live value or live catalog; cap §5's restart.
    private static void birth() {
        EntityDocuments store = store();
        stream(store, "light.a", "on", "2026-10-10T10:00:00+00:00", false);
        catalog(store, "light.b", "on", "2026-10-10T10:00:00+00:00", false);
        stream(store, "light.c", "on", "2026-10-10T10:00:00+00:00", true);
        check(store.freshness("light.a") == Freshness.LIVE && store.freshness("light.b") == Freshness.LIVE && store.freshness("light.c") == Freshness.RETAINED,
                "Live statestream and a live catalog are LIVE; retained is RETAINED");
        Set<String> changed = store.haBirth();
        check(changed.equals(Set.of("light.a", "light.b")), "Birth changes exactly the LIVE ones: " + changed);
        check(store.freshness("light.a") == Freshness.RETAINED && store.freshness("light.b") == Freshness.RETAINED, "Birth downgrades to RETAINED");
        accept(store, "ha_statestream/light/a/state", "on", false);
        catalog(store, "light.b", "on", "2026-10-10T10:00:00+00:00", false);
        check(store.freshness("light.a") == Freshness.LIVE && store.freshness("light.b") == Freshness.LIVE, "The next live value or catalog is LIVE again");
        catalog(store, "light.b", "on", "2026-10-10T10:00:00+00:00", true);
        check(store.freshness("light.b") == Freshness.RETAINED, "A retained catalog is RETAINED");

        // Cap §5: after an HA restart the broker keeps the old text while HA restored its initial; the catalog fixes it.
        store = store();
        stream(store, "input_text.ss_note", "Grüße \"quoted\" / slash", "2026-10-10T16:59:00+00:00", true);
        store.haBirth();
        catalog(store, "input_text.ss_note", "hello world", "2026-10-10T18:00:00+00:00", false);
        EntityDocument note = store.document("input_text.ss_note").orElseThrow();
        check(note.state().equals(state("hello world")) && note.origin() == Origin.CATALOG && note.freshness() == Freshness.LIVE,
                "Cap §5: the catalog after the restart wins over the stale retained value: " + note);

        // HA only reconnected to MQTT: the birth comes again, the timestamps do not move, and the catalog it triggers
        // ties with the stream value, which is LIVE again.
        store = store();
        stream(store, "switch.idle", "off", "2026-10-10T09:00:00+00:00", false);
        store.haBirth();
        check(store.freshness("switch.idle") == Freshness.RETAINED, "Birth downgrades the idle entity");
        catalog(store, "switch.idle", "off", "2026-10-10T09:00:00+00:00", false);
        check(origin(store, "switch.idle") == Origin.STATESTREAM && store.freshness("switch.idle") == Freshness.LIVE,
                "The catalog after the birth makes the unchanged statestream value LIVE");
    }

    // Retained replays come in subscription order, not cap §8's, and repeat what the store already holds.
    private static void retainedReplays() {
        String t1 = "2026-10-10T10:00:00+00:00";
        String t2 = "2026-10-10T11:00:00+00:00";
        String t3 = "2026-10-10T12:00:00+00:00";

        // The resubscribe table in hash order: last_updated before state. The state keeps it, and beats an older catalog.
        EntityDocuments store = store();
        catalog(store, "light.a", "off", t2, true);
        accept(store, "ha_statestream/light/a/last_updated", t3, true);
        accept(store, "ha_statestream/light/a/state", "on", true);
        EntityDocument a = store.document("light.a").orElseThrow();
        check(a.lastUpdated().equals(Instant.parse("2026-10-10T12:00:00Z")) && a.origin() == Origin.STATESTREAM && a.state().equals(state("on"))
                && a.freshness() == Freshness.RETAINED, "A retained state keeps the retained last_updated that came first: " + a);
        // and an older retained pair still loses to the catalog in either order.
        accept(store, "ha_statestream/light/b/last_updated", t1, true);
        accept(store, "ha_statestream/light/b/state", "on", true);
        catalog(store, "light.b", "off", t2, true);
        check(origin(store, "light.b") == Origin.CATALOG && stateOf(store, "light.b").equals(state("off")), "An older retained pair loses either way");

        // A live entity, then a second binding subscribes more leaves: the broker replays them retained.
        store = store();
        stream(store, "fan.x", "on", t2, false);
        accept(store, "ha_statestream/fan/x/percentage", "66", false);
        check(store.freshness("fan.x") == Freshness.LIVE, "Live fan");
        EntityDocuments.Update update = accept(store, "ha_statestream/fan/x/state", "on", true);
        check(update.changed().isEmpty() && store.freshness("fan.x") == Freshness.LIVE
                && store.document("fan.x").orElseThrow().lastUpdated().equals(Instant.parse("2026-10-10T11:00:00Z")),
                "A retained replay of the same state changes nothing: still LIVE, timestamps intact");
        update = accept(store, "ha_statestream/fan/x/last_updated", t2, true);
        check(update.changed().isEmpty() && store.freshness("fan.x") == Freshness.LIVE, "nor does the same last_updated");
        update = accept(store, "ha_statestream/fan/x/percentage", "66", true);
        check(update.changed().isEmpty() && store.freshness("fan.x") == Freshness.LIVE, "nor the same attribute");
        update = accept(store, "ha_statestream/fan/x/oscillating", "", true);
        check(update.changed().isEmpty() && store.document("fan.x").orElseThrow().attribute("oscillating").isEmpty(), "nor a cleared attribute it never had");
        update = accept(store, "ha_statestream/fan/x/preset_mode", "\"auto\"", true);
        check(update.changed().equals(Set.of("fan.x")) && store.freshness("fan.x") == Freshness.LIVE
                && store.document("fan.x").orElseThrow().value("preset_mode").orElseThrow().getAsString().equals("auto"),
                "A retained attribute leaf it had not read yet is added and does not downgrade LIVE");
        update = accept(store, "ha_statestream/fan/x/state", "off", true);
        check(update.changed().equals(Set.of("fan.x")) && store.freshness("fan.x") == Freshness.RETAINED
                && store.document("fan.x").orElseThrow().lastUpdated() != null, "A retained state that differs is broker data: RETAINED");
        accept(store, "ha_statestream/fan/x/state", "on", false);
        check(store.freshness("fan.x") == Freshness.LIVE && store.document("fan.x").orElseThrow().lastUpdated() == null,
                "A live state is LIVE and starts a new update");
    }

    // At most maxEntities entities, maxAttributes per entity, 64 KiB per leaf; the rest is counted.
    private static void bounds() {
        EntityDocuments store = new EntityDocuments(BASE, new EntityDocuments.Limits(2, 3));
        accept(store, "ha_statestream/light/a/state", "on", false);
        accept(store, "ha_statestream/light/b/state", "on", false);
        check(accept(store, "ha_statestream/light/c/state", "on", false).kind() == Kind.REFUSED && store.size() == 2
                && store.counters().entityOverflow() == 1, "A full store refuses a new entity");
        accept(store, "ha_statestream/light/a/x1", "1", false);
        accept(store, "ha_statestream/light/a/x2", "2", false);
        accept(store, "ha_statestream/light/a/x3", "3", false);
        check(accept(store, "ha_statestream/light/a/x4", "4", false).kind() == Kind.REFUSED && store.counters().attributeOverflow() == 1,
                "A full entity refuses a new attribute");
        accept(store, "ha_statestream/light/a/x3", "33", false);
        check(store.document("light.a").orElseThrow().value("x3").orElseThrow().getAsInt() == 33, "but updates an existing one");
        byte[] big = new byte[EntityDocuments.MAX_PAYLOAD_BYTES + 1];
        Arrays.fill(big, (byte) 'a');
        check(store.accept("ha_statestream/light/a/x1", big, false, ++clock).kind() == Kind.REFUSED && store.counters().oversize() == 1
                && store.document("light.a").orElseThrow().value("x1").orElseThrow().getAsInt() == 1, "An oversized leaf is refused");
        accept(store, "ha_statestream/_mineqtt/catalog/switch", "[{\"id\":\"switch.x\"},{\"id\":\"switch.y\"}]", false);
        check(store.size() == 2 && store.counters().entityOverflow() == 3 && store.catalog().isEmpty(), "Catalog entries count towards the limit too");
        check(throwsIllegal(() -> new EntityDocuments.Limits(0, 1)) && throwsIllegal(() -> new EntityDocuments(BASE + "/+", EntityDocuments.Limits.DEFAULT)),
                "Limits and base are checked");
    }

    // twin.allowedEntities: whole-id globs with * and ?, lowercase, [] = off, ["*"] = all.
    private static void globs() {
        check(EntityGlobs.ALL.matches("light.kitchen") && EntityGlobs.ALL.matches("zone.home") && !EntityGlobs.ALL.isEmpty(), "[\"*\"] allows everything");
        check(!EntityGlobs.NONE.matches("light.kitchen") && EntityGlobs.NONE.isEmpty() && EntityGlobs.of(List.of()).isEmpty(), "[] allows nothing");
        EntityGlobs lights = EntityGlobs.of(List.of(" light.* "));
        check(lights.patterns().equals(List.of("light.*")) && lights.matches("light.kitchen") && !lights.matches("switch.light") && !lights.matches("light"),
                "light.*");
        EntityGlobs kitchen = EntityGlobs.of(List.of("*.kitchen_*"));
        check(kitchen.matches("light.kitchen_ceiling") && kitchen.matches("sensor.kitchen_temp") && !kitchen.matches("light.kitchen"), "*.kitchen_*");
        EntityGlobs plug = EntityGlobs.of(List.of("switch.plug_?"));
        check(plug.matches("switch.plug_1") && !plug.matches("switch.plug_10") && !plug.matches("switch.plug_"), "switch.plug_?");
        EntityGlobs several = EntityGlobs.of(List.of("sensor.outdoor_temperature", "light.k*n*g"));
        check(several.matches("sensor.outdoor_temperature") && !several.matches("sensor.outdoor_temperature_2") && several.matches("light.kitchen_ceiling")
                && several.matches("light.kng") && !several.matches("light.kitchen"), "Exact ids and backtracking stars");
        check(EntityGlobs.of(List.of("a*a*a*a*a*b")).matches("a".repeat(40) + "b") && !EntityGlobs.of(List.of("a*a*a*a*a*b")).matches("a".repeat(200)),
                "Many stars stay cheap");
        check(EntityGlobs.of(List.of("light.*")).equals(lights) && lights.hashCode() == EntityGlobs.of(List.of("light.*")).hashCode(), "Equality");
        for (String bad : new String[]{"", "  ", "Light.*", "light.[ab]", "light kitchen", "light.a,switch.b", "light.*/x", "x".repeat(256)}) {
            check(EntityGlobs.problem(bad.strip()).isPresent() && throwsIllegal(() -> EntityGlobs.of(List.of(bad))), "Invalid glob: " + bad);
        }
        check(EntityGlobs.problem("Light.*").orElseThrow().contains("lowercase"), "Uppercase is explained");
        check(throwsIllegal(() -> EntityGlobs.of(Arrays.asList("light.*", null))), "A null entry is invalid");
        check(!lights.matches(null), "null matches nothing");
    }

    // --- helpers ---

    /**
     * M3-11, item 26's cap §5 gap: after a Home Assistant restart the broker still holds a stale retained state; the birth
     * makes everything retained, the catalog's snapshot (live, newer) replaces the stale value, and the next live
     * statestream value wins again. Unlabelling (an empty list) closes the gate: NOT_SHARED for a bound device.
     */
    private static void restartSnapshot() {
        EntityDocuments store = store();
        accept(store, BASE + "/_mineqtt/status", "{\"v\":1}", true);
        catalog(store, "input_text.m3_note", "Grüße", "2026-10-11T08:00:00+00:00", true);
        stream(store, "input_text.m3_note", "stale by hand", "2026-10-11T07:00:00+00:00", true);
        check(stateOf(store, "input_text.m3_note").equals(state("Grüße")) && origin(store, "input_text.m3_note") == Origin.CATALOG,
                "A stale retained value loses to the newer catalog snapshot");
        store.haBirth();
        check(store.freshness("input_text.m3_note") == Freshness.RETAINED, "Home Assistant's birth makes it retained");
        catalog(store, "input_text.m3_note", "after restart", "2026-10-11T09:00:00+00:00", false);
        check(stateOf(store, "input_text.m3_note").equals(state("after restart")) && store.freshness("input_text.m3_note") == Freshness.LIVE,
                "The catalog after the birth (ha_sync) brings Home Assistant's real value, live");
        stream(store, "input_text.m3_note", "typed", "2026-10-11T09:00:05+00:00", false);
        check(stateOf(store, "input_text.m3_note").equals(state("typed")) && origin(store, "input_text.m3_note") == Origin.STATESTREAM,
                "A live statestream value wins again");
        check(store.readable("input_text.m3_note", EntityGlobs.ALL), "Shared while listed");
        accept(store, StatestreamTopic.catalogTopic(BASE, "input_text"), "[]", false);
        check(!store.readable("input_text.m3_note", EntityGlobs.ALL), "An empty list unshares it");
        check(TwinHealth.of(true, store.document("input_text.m3_note"), store.readable("input_text.m3_note", EntityGlobs.ALL)) == TwinHealth.NOT_SHARED,
                "...so a bound device shows NOT_SHARED");
        accept(store, BASE + "/_mineqtt/status", "", true);
        check(store.readable("input_text.m3_note", EntityGlobs.ALL), "Without a catalog (status cleared) statestream alone gates again");
    }

    /**
     * D3 drift: an entity whose catalog entry changes twice (a newer last_updated with another state or other attributes)
     * with no live statestream value in between is changing in Home Assistant without statestream publishing it; a live
     * value ends it, one change is not enough, an unchanged last_updated is no change, and an entity never streamed drifts
     * the same. A re-dating with the same state and attributes is no change: every restored entity after a Home Assistant
     * restart.
     */
    private static void drift() {
        EntityDocuments store = store();
        catalog(store, "input_boolean.m3_bolt", "off", "2026-10-11T08:00:00+00:00", false);
        check(!store.drifting("input_boolean.m3_bolt"), "A first entry is no move");
        catalog(store, "input_boolean.m3_bolt", "off", "2026-10-11T08:00:00+00:00", false);
        catalog(store, "input_boolean.m3_bolt", "on", "2026-10-11T08:10:00+00:00", false);
        check(!store.drifting("input_boolean.m3_bolt"), "One move, and an unchanged cycle, are not drift");
        catalog(store, "input_boolean.m3_bolt", "off", "2026-10-11T08:20:00+00:00", false);
        check(store.drifting("input_boolean.m3_bolt"), "Two moves with no live value: drift");
        stream(store, "input_boolean.m3_bolt", "off", "2026-10-11T08:20:00+00:00", true);
        check(store.drifting("input_boolean.m3_bolt"), "A retained statestream value proves nothing");
        stream(store, "input_boolean.m3_bolt", "on", "2026-10-11T08:25:00+00:00", false);
        check(!store.drifting("input_boolean.m3_bolt"), "A live statestream value ends it");

        // An included entity: every change streams live before the catalog sees it, so it never drifts.
        for (int minute = 0; minute < 50; minute += 10) {
            String at = String.format("2026-10-11T09:%02d:00+00:00", minute);
            stream(store, "input_boolean.m3_flag", minute % 20 == 0 ? "on" : "off", at, false);
            catalog(store, "input_boolean.m3_flag", minute % 20 == 0 ? "on" : "off", at, false);
        }
        check(!store.drifting("input_boolean.m3_flag") && !store.drifting("light.unknown"), "An included entity never drifts");

        // After two Home Assistant restarts a scene's, a script's and a button's catalog last_updated
        // moved twice without a live value (restored entities are re-dated at each start, statestream publishes nothing),
        // which is no sign of a missing include: they change only when triggered, so no drift is inferred for them.
        for (String entity : List.of("scene.m3_scene", "script.m3_script", "button.m3_button", "input_button.m3_press")) {
            catalog(store, entity, "2026-10-11T08:00:00+00:00", "2026-10-11T08:00:00+00:00", false);
            catalog(store, entity, "2026-10-11T08:00:00+00:00", "2026-10-11T08:30:00+00:00", false);
            catalog(store, entity, "2026-10-11T08:00:00+00:00", "2026-10-11T09:00:00+00:00", false);
            check(!store.drifting(entity), "A trigger-only entity never drifts after restarts: " + entity);
        }
        // Triggered in Home Assistant between two catalog cycles, twice: its state (the last-triggered time) really changes,
        // so the content rule counts both moves, and only the trigger-domain exemption keeps it from drifting.
        for (String entity : List.of("scene.m3_scene2", "script.m3_script2", "button.m3_button2", "input_button.m3_press2")) {
            catalog(store, entity, "2026-10-11T08:00:00+00:00", "2026-10-11T08:00:00+00:00", false);
            catalog(store, entity, "2026-10-11T08:25:00+00:00", "2026-10-11T08:25:00+00:00", false);
            catalog(store, entity, "2026-10-11T08:55:00+00:00", "2026-10-11T08:55:00+00:00", false);
            check(!store.drifting(entity), "A trigger-only entity triggered twice between catalog cycles never drifts: " + entity);
        }
        // The same moves on a domain that infers drift: drift. So the case above rests on the exemption, not the content rule.
        catalog(store, "input_datetime.m3_alarm", "2026-10-11T08:00:00+00:00", "2026-10-11T08:00:00+00:00", false);
        catalog(store, "input_datetime.m3_alarm", "2026-10-11T08:25:00+00:00", "2026-10-11T08:25:00+00:00", false);
        catalog(store, "input_datetime.m3_alarm", "2026-10-11T08:55:00+00:00", "2026-10-11T08:55:00+00:00", false);
        check(store.drifting("input_datetime.m3_alarm"), "The same two state changes on another domain drift");
        check(HaDomains.TRIGGER_DOMAINS.stream().noneMatch(HaDomains::infersDrift) && HaDomains.infersDrift("light") && HaDomains.infersDrift("sensor"),
                "Drift is inferred for every domain but the trigger-only ones");
        // Two Home Assistant restarts re-date every restored entity (the catalog's last_updated moves,
        // statestream publishes nothing at start), also a light and a flag that are in the include and did not change: no
        // drift for them (the real-HA run named input_boolean.m3_flag and light.m3_lamp after the second restart).
        for (String entity : List.of("light.m3_lamp", "input_boolean.m3_flag2", "sensor.m3_temperature")) {
            catalog(store, entity, "on", "2026-10-11T10:06:52+00:00", false);
            catalog(store, entity, "on", "2026-10-11T10:07:40+00:00", false);
            catalog(store, entity, "on", "2026-10-11T10:08:53+00:00", false);
            check(!store.drifting(entity), "An unchanged entity re-dated by two restarts does not drift: " + entity);
        }
        // ...while the same moves with another state each time, or other attributes, are changes nothing streamed: drift.
        catalog(store, "light.m3_porch", "on", "2026-10-11T10:06:52+00:00", false);
        catalog(store, "light.m3_porch", "off", "2026-10-11T10:16:52+00:00", false);
        catalog(store, "light.m3_porch", "on", "2026-10-11T10:26:52+00:00", false);
        check(store.drifting("light.m3_porch"), "A light whose state changes twice with nothing streamed drifts");
        catalogWith(store, "light.m3_hall", "on", "2026-10-11T10:06:52+00:00", 10);
        catalogWith(store, "light.m3_hall", "on", "2026-10-11T10:16:52+00:00", 20);
        check(!store.drifting("light.m3_hall"), "One attribute change is not enough");
        catalogWith(store, "light.m3_hall", "on", "2026-10-11T10:16:53+00:00", 20);
        check(!store.drifting("light.m3_hall"), "...nor a re-dating after it");
        catalogWith(store, "light.m3_hall", "on", "2026-10-11T10:26:52+00:00", 30);
        check(store.drifting("light.m3_hall"), "A light whose brightness changes twice with nothing streamed drifts");
        // A restart between two real changes neither counts nor resets: the third changed entry still makes two changes.
        catalog(store, "input_boolean.m3_bolt2", "off", "2026-10-11T10:00:00+00:00", false);
        catalog(store, "input_boolean.m3_bolt2", "on", "2026-10-11T10:10:00+00:00", false);
        catalog(store, "input_boolean.m3_bolt2", "on", "2026-10-11T10:12:00+00:00", false);
        check(!store.drifting("input_boolean.m3_bolt2"), "A change and a re-dating are one change");
        catalog(store, "input_boolean.m3_bolt2", "off", "2026-10-11T10:20:00+00:00", false);
        check(store.drifting("input_boolean.m3_bolt2"), "A second change after a re-dating: drift");
    }

    /**
     * An entity a target switches (a held target entity, M3-14) that leaves the catalog is reported as
     * changed and no longer readable, so the runtime judges the target again (NOT_SHARED, presses not sent); labelled
     * again, it is reported and readable once more, which heals it.
     */
    private static void targetsLeaveCatalog() {
        EntityDocuments store = store();
        // Without a catalog (statestream-only mode) a target is judged by twin.allowedEntities alone: a scene, which
        // statestream rarely publishes, is switched although no document of it was ever seen.
        check(store.readable("scene.m3_scene", EntityGlobs.ALL) && store.document("scene.m3_scene").isEmpty() && store.shareProblem("scene.m3_scene").isEmpty(),
                "No catalog: a never-seen scene is still switched");
        accept(store, "ha_statestream/_mineqtt/status", "{\"v\":1}", true);
        catalog(store, "scene.m3_scene", "2026-10-11T08:00:00+00:00", "2026-10-11T08:00:00+00:00", false);
        check(store.readable("scene.m3_scene", EntityGlobs.ALL) && store.shareProblem("scene.m3_scene").isEmpty(), "A labelled scene is shared");
        EntityDocuments.Update left = accept(store, StatestreamTopic.catalogTopic(BASE, "scene"), "[]", false);
        check(left.changed().contains("scene.m3_scene"), "Its leaving is reported as a change, so its target is judged again: " + left.changed());
        check(!store.readable("scene.m3_scene", EntityGlobs.ALL) && store.shareProblem("scene.m3_scene").orElse("").contains("not shared"),
                "...and it is no longer readable: NOT_SHARED, nothing sent");
        catalog(store, "scene.m3_scene", "2026-10-11T08:00:00+00:00", "2026-10-11T08:10:00+00:00", false);
        check(store.readable("scene.m3_scene", EntityGlobs.ALL), "Labelled again, it is readable again: the target heals");
    }

    // D3 catalog health: a status generated more than 30 min ago (or never dated) may mean the automation is off.
    private static void catalogHealth() {
        Instant generated = Instant.parse("2026-10-11T08:00:00Z");
        CatalogParser.Status status = new CatalogParser.Status(1, "catalog", generated, "mineqtt_read", "mineqtt_control", 3);
        check(!status.stale(generated.plusSeconds(29 * 60)) && !status.stale(generated.plus(CatalogParser.STALE_AFTER)), "Fresh within 30 min");
        check(status.stale(generated.plus(CatalogParser.STALE_AFTER).plusSeconds(1)), "Older than 30 min: stale");
        check(new CatalogParser.Status(1, "", null, "", "", -1).stale(generated), "A status without generated is stale");
        check(CatalogParser.parseStatus(bytes("{\"v\":1,\"generated\":\"2026-10-11T08:00:00.123456+00:00\"}")).orElseThrow().generated().equals(generated.plusNanos(123_456_000)),
                "The blueprint's now().isoformat() parses");
    }

    /**
     * D4 fallback scan: state and friendly_name leaves of readable domains, at most catalogMax entities (first seen wins,
     * the rest counted), the privacy domains never, a cleared state forgets the entity, a friendly name is a JSON string.
     */
    private static void scan() {
        EntityScan scan = new EntityScan(BASE, 3);
        check(scan.accept(BASE + "/light/kitchen/state", bytes("on")) && scan.accept(BASE + "/light/kitchen/friendly_name", bytes("\"Kitchen Ceiling\"")),
                "A state and a name make a row");
        check(scan.rows().get("light.kitchen").equals(new EntityScan.Row("light.kitchen", "Kitchen Ceiling", state("on"))), "The row: " + scan.rows());
        check(!scan.accept(BASE + "/person/joel/state", bytes("home")) && !scan.accept(BASE + "/zone/home/friendly_name", bytes("\"Home\""))
                && !scan.accept(BASE + "/weather/home/state", bytes("sunny")), "Privacy and unknown domains are never kept");
        check(!scan.accept(BASE + "/light/kitchen/brightness", bytes("200")) && !scan.accept("other/light/kitchen/state", bytes("on"))
                && !scan.accept(BASE + "/_mineqtt/catalog/light", bytes("[]")), "Other leaves and topics are ignored");
        check(!scan.accept(BASE + "/light/kitchen/friendly_name", bytes("Kitchen")) && !scan.accept(BASE + "/light/kitchen/friendly_name", bytes("42")),
                "A name that is no JSON string is ignored");
        scan.accept(BASE + "/switch/a/friendly_name", bytes("\"" + "x".repeat(100) + "\""));
        check(scan.rows().get("switch.a").name().length() == EntityScan.MAX_NAME_LENGTH && scan.rows().get("switch.a").state() == StatestreamValue.UNKNOWN,
                "A long name is cut; a name before the state leaves it unknown");
        scan.accept(BASE + "/switch/b/state", bytes("off"));
        check(!scan.accept(BASE + "/switch/c/state", bytes("on")) && scan.size() == 3 && scan.overflow() == 1, "Over the cap: first seen wins, the rest counted");
        check(scan.accept(BASE + "/switch/b/state", bytes("on")) && !scan.accept(BASE + "/switch/b/state", bytes("on")), "A row changes, a repeat does not");
        check(scan.accept(BASE + "/switch/b/state", new byte[0]) && !scan.rows().containsKey("switch.b"), "A cleared state forgets the entity");
        check(scan.accept(BASE + "/switch/c/state", bytes("on")) && scan.size() == 3, "...which makes room again");
        check(!scan.accept(BASE + "/switch/c/state", bytes("x".repeat(EntityScan.MAX_STATE_BYTES + 1))) && scan.rows().get("switch.c").state().equals(state("on"))
                        && scan.accept(BASE + "/switch/c/state", bytes("ü".repeat(255))),
                "A state longer than any Home Assistant state is ignored; 255 characters of UTF-8 are kept");
        scan.clear();
        check(scan.size() == 0 && scan.overflow() == 1, "clear() keeps the overflow count");
        check(new EntityScan(BASE + "/", 1).accept(BASE + "/light/x/state", bytes("on")), "The base is normalised");
    }

    /**
     * D4 ranking: the device's domains in their order, then prefix before word start before substring (on name, entity
     * id or area), then recently bound, then unavailable last; no match is left out, the limit holds, an empty query
     * lists all.
     */
    private static void search() {
        List<EntitySearch.Candidate> candidates = List.of(
                new EntitySearch.Candidate("binary_sensor.kitchen_door", "Kitchen Door", "Kitchen", state("off"), false),
                new EntitySearch.Candidate("light.kitchen_ceiling", "Kitchen Ceiling", "Kitchen", state("on"), true),
                new EntitySearch.Candidate("switch.coffee", "Coffee Machine", "Kitchen", state("off"), true),
                new EntitySearch.Candidate("light.hall", "Hall", "Hallway", state("on"), false),
                new EntitySearch.Candidate("light.dead_kitchen", "Old Kitchen Lamp", "Basement", StatestreamValue.UNAVAILABLE, false),
                new EntitySearch.Candidate("sensor.outdoor", "Outdoor Temperature", "", state("21.4"), false));
        List<String> lamp = List.of("light", "switch", "input_boolean", "fan", "binary_sensor");
        check(ids(EntitySearch.search(candidates, "kitchen", lamp, Set.of(), 40))
                        .equals(List.of("light.kitchen_ceiling", "light.dead_kitchen", "switch.coffee", "binary_sensor.kitchen_door")),
                "Domains first, then match quality, unavailable last: " + ids(EntitySearch.search(candidates, "kitchen", lamp, Set.of(), 40)));
        check(ids(EntitySearch.search(candidates, "", lamp, Set.of(), 40)).size() == 5, "An empty query lists every usable domain; sensor is not one for a lamp");
        check(ids(EntitySearch.search(candidates, "hall", List.of(), Set.of(), 40)).equals(List.of("light.hall")), "No domains: any domain");
        check(ids(EntitySearch.search(candidates, "", List.of("light"), Set.of("light.hall"), 40)).getFirst().equals("light.hall"), "Recently bound first");
        check(ids(EntitySearch.search(candidates, "LAMP", List.of("light"), Set.of(), 40)).equals(List.of("light.dead_kitchen")), "Case does not matter");
        check(ids(EntitySearch.search(candidates, "temp", List.of(), Set.of(), 40)).equals(List.of("sensor.outdoor")), "A word start matches");
        check(ids(EntitySearch.search(candidates, "eiling", List.of(), Set.of(), 40)).equals(List.of("light.kitchen_ceiling")), "A substring matches");
        check(ids(EntitySearch.search(candidates, "ceil", List.of(), Set.of(), 40)).equals(List.of("light.kitchen_ceiling")), "...after a word start in the id");
        check(EntitySearch.search(candidates, "zzz", List.of(), Set.of(), 40).isEmpty() && EntitySearch.search(candidates, "", List.of(), Set.of(), 0).isEmpty(),
                "No match, or a zero limit, finds nothing");
        check(EntitySearch.search(candidates, "", List.of(), Set.of(), 2).size() == 2, "The limit holds");
        check(EntitySearch.search(candidates, "k".repeat(500), List.of(), Set.of(), 40).isEmpty(), "A long query is cut, not refused");
    }

    private static List<String> ids(List<EntitySearch.Candidate> found) {
        return found.stream().map(EntitySearch.Candidate::entityId).toList();
    }

    private static void mergeRow(Layer stream, Layer catalog, Origin expected, String row) {
        Origin actual = EntityDocuments.merge(stream, catalog);
        check(actual == expected, "Merge: " + row + ": expected " + expected + ", got " + actual);
    }

    private static void stream(EntityDocuments store, String entityId, String state, String lastUpdated, boolean retained) {
        String prefix = BASE + "/" + entityId.replace('.', '/') + "/";
        accept(store, prefix + "state", state, retained);
        accept(store, prefix + "last_updated", lastUpdated, retained);
    }

    // A catalog entry of a light whose brightness is the given one.
    private static void catalogWith(EntityDocuments store, String entityId, String state, String lastUpdated, int brightness) {
        String domain = entityId.substring(0, entityId.indexOf('.'));
        JsonObject entry = new JsonObject();
        entry.addProperty("id", entityId);
        entry.addProperty("state", state);
        entry.addProperty("last_updated", lastUpdated);
        JsonObject attrs = new JsonObject();
        attrs.addProperty("brightness", brightness);
        entry.add("attributes", attrs);
        JsonArray list = new JsonArray();
        store.catalog().getOrDefault(domain, Map.of()).forEach((id, existing) -> {
            if (!id.equals(entityId)) list.add(kept(id, existing));
        });
        list.add(entry);
        accept(store, StatestreamTopic.catalogTopic(BASE, domain), list.toString(), false);
    }

    // Another entry of the domain as the list before had it, so republishing the list leaves it as it was.
    private static JsonObject kept(String id, CatalogEntry existing) {
        JsonObject keep = new JsonObject();
        keep.addProperty("id", id);
        keep.add("state", existing.state().json());
        if (existing.lastUpdated() != null) keep.addProperty("last_updated", HaTime.format(existing.lastUpdated()));
        JsonObject attributes = new JsonObject();
        existing.attributes().forEach(attributes::add);
        keep.add("attributes", attributes);
        return keep;
    }

    private static void catalog(EntityDocuments store, String entityId, String state, String lastUpdated, boolean retained) {
        String domain = entityId.substring(0, entityId.indexOf('.'));
        JsonObject entry = new JsonObject();
        entry.addProperty("id", entityId);
        entry.addProperty("state", state);
        if (lastUpdated != null) entry.addProperty("last_updated", lastUpdated);
        JsonObject attrs = new JsonObject();
        attrs.addProperty("brightness", 1);
        entry.add("attributes", attrs);
        // Keep the domain's other entries: the store treats every list as the whole domain.
        JsonArray list = new JsonArray();
        store.catalog().getOrDefault(domain, Map.of()).forEach((id, existing) -> {
            if (!id.equals(entityId)) list.add(kept(id, existing));
        });
        list.add(entry);
        accept(store, StatestreamTopic.catalogTopic(BASE, domain), list.toString(), retained);
    }

    private static Origin origin(EntityDocuments store, String entityId) {
        return store.document(entityId).orElseThrow().origin();
    }

    private static StatestreamValue stateOf(EntityDocuments store, String entityId) {
        return store.document(entityId).orElseThrow().state();
    }

    private static EntityDocuments store() {
        return new EntityDocuments(BASE, EntityDocuments.Limits.DEFAULT);
    }

    private static EntityDocuments.Update accept(EntityDocuments store, String topic, String payload, boolean retained) {
        return store.accept(topic, bytes(payload), retained, ++clock);
    }

    private static StatestreamValue state(String raw) {
        return StatestreamValue.ofPayload(bytes(raw));
    }

    private static JsonElement attr(String raw) {
        return EntityDocuments.decodeAttribute(bytes(raw));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // A one-entry light list of exactly `size` bytes (or one more, when `pad` is two bytes wide), padded in the name.
    private static byte[] paddedList(int size, char pad) {
        String head = "[{\"id\":\"light.big\",\"name\":\"";
        String tail = "\"}]";
        int padBytes = String.valueOf(pad).getBytes(StandardCharsets.UTF_8).length;
        int room = size - head.length() - tail.length();
        String body = String.valueOf(pad).repeat((room + padBytes - 1) / padBytes);
        return bytes(head + body + tail);
    }

    private static StatestreamTopic.Entity entity(String base, String topic) {
        StatestreamTopic parsed = StatestreamTopic.parse(base, topic);
        if (!(parsed instanceof StatestreamTopic.Entity entity)) throw new AssertionError("Not an entity topic: " + topic + " -> " + parsed);
        return entity;
    }

    private static void ignored(String base, String topic, StatestreamTopic.Reason reason) {
        StatestreamTopic parsed = StatestreamTopic.parse(base, topic);
        check(parsed.equals(new StatestreamTopic.Ignored(reason)), "Ignored as " + reason + ": " + topic + " -> " + parsed);
    }

    private static boolean throwsIllegal(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static boolean throwsUnsupported(Runnable action) {
        try {
            action.run();
            return false;
        } catch (UnsupportedOperationException e) {
            return true;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    // --- the capture ---

    private record Line(String topic, byte[] payload, boolean retainFlag, int qos) {
        EntityDocuments.Update into(EntityDocuments store, boolean retained) {
            return store.accept(topic, payload, retained, ++clock);
        }
    }

    private record Section(String id, List<Line> lines) {
    }

    // "## <id> <title>" starts a section; "r=<r> q=<q> <topic> <payload>", where a line ending at the topic is zero length.
    private static List<Section> loadCapture() throws IOException {
        InputStream in = StatestreamParseCheck.class.getResourceAsStream(CAPTURE);
        if (in == null) throw new AssertionError("Missing capture resource " + CAPTURE);
        List<Section> sections = new ArrayList<>();
        List<Line> current = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
                if (line.startsWith("## ")) {
                    String title = line.substring(3);
                    String id = title.startsWith("9.10 then") ? "9.10 then" : title.substring(0, title.indexOf(' '));
                    current = new ArrayList<>();
                    sections.add(new Section(id, current));
                    continue;
                }
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(" ", 4);
                if (parts.length < 3 || !parts[0].startsWith("r=") || !parts[1].startsWith("q=") || current == null) {
                    throw new AssertionError("Bad capture line: " + line);
                }
                String payload = parts.length == 4 ? parts[3] : "";
                current.add(new Line(parts[2], bytes(payload), parts[0].equals("r=1"), Integer.parseInt(parts[1].substring(2))));
            }
        }
        // Format consistency of the resource only, not evidence: its flags were written from the plan's Appendix A text,
        // not recorded (M3-2 deviation 1). Statestream's hard-coded retain=1 QoS 1 (cap §2, §4) was observed on the
        // fixture sessions instead (e2e/README Results, the two 2026-10-10 runs of M3-1 to M3-3).
        for (Section section : sections) {
            for (Line line : section.lines()) check(line.retainFlag() && line.qos() == 1, "The resource lists r=1 q=1 throughout: " + line.topic());
        }
        check(sections.stream().mapToInt(s -> s.lines().size()).sum() == 113, "Capture lines: " + sections.stream().mapToInt(s -> s.lines().size()).sum());
        check(sections.getLast().lines().getLast().payload().length == 0, "The last line is the zero-length state");
        check(new String(sections.get(6).lines().get(2).payload(), StandardCharsets.UTF_8).equals("Grüße \"quoted\" / slash"), "The capture reads as UTF-8");
        return sections;
    }
}

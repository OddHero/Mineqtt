package art.rehra.mineqtt.mqtt.core;

import art.rehra.mineqtt.mqtt.core.TopicAcl.Decision;
import art.rehra.mineqtt.mqtt.core.TopicAcl.Exposure;
import art.rehra.mineqtt.mqtt.core.TopicAcl.Grandfather;
import art.rehra.mineqtt.mqtt.core.TopicAcl.Rules;
import art.rehra.mineqtt.mqtt.core.TopicAcl.Subject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The {@link TopicAcl} allow/deny matrix of design §8.2. */
public final class TopicAclCheck {
    private static final String BASE = "mineqtt";
    private static final String BRIDGE = "world_7f3a";
    private static final UUID STEVE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID ALEX = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final Subject PLAYER = new Subject(STEVE, false, false);
    private static final Subject OTHER = new Subject(ALEX, false, false);
    private static final Subject OP = new Subject(ALEX, true, false);
    private static final Subject ADMIN = new Subject(ALEX, true, true);

    static void main(String[] args) {
        TopicAcl acl = new TopicAcl(rules(withDefaults("zigbee2mqtt/+/set"), false, false));
        validation(acl);
        hardDeny(acl);
        allowlist(acl);
        opBypass();
        privateSubtree(acl);
        grandfathering(acl);
        listening(acl);
        explorerScope(acl);
        explorerExposure(acl);
        mirroring(acl);
        legacy(acl);
        rulesValidation();
        System.out.println("Topic ACL checks passed.");
    }

    private static void validation(TopicAcl acl) {
        expect(acl.checkPublish(PLAYER, "mineqtt/shared/+"), Decision.WILDCARD, "a + in a publish topic");
        expect(acl.checkPublish(PLAYER, "mineqtt/shared/#"), Decision.WILDCARD, "a # in a publish topic");
        expect(acl.checkPublish(PLAYER, "mineqtt/shared/a+b"), Decision.INVALID, "a malformed wildcard");
        expect(acl.checkPublish(PLAYER, ""), Decision.INVALID, "an empty topic");
        expect(acl.checkPublish(PLAYER, null), Decision.INVALID, "a missing topic");
        expect(acl.checkPublish(PLAYER, "mineqtt//shared"), Decision.INVALID, "an empty level");
        expect(acl.checkPublish(PLAYER, "mineqtt/shared/"), Decision.INVALID, "a trailing empty level");
        expect(acl.checkPublish(PLAYER, "/mineqtt/shared"), Decision.INVALID, "a leading empty level");
        expect(acl.checkPublish(PLAYER, "mineqtt/shared/a\0b"), Decision.INVALID, "a NUL character");
        expect(acl.checkPublish(PLAYER, "$SYS/broker/clients"), Decision.DOLLAR, "a $-topic");
        expect(acl.checkPublish(OP, "$SYS/broker/clients"), Decision.DOLLAR, "a $-topic as op");

        String prefix = "mineqtt/shared/";
        expect(acl.checkPublish(PLAYER, prefix + "x".repeat(256 - prefix.length())), Decision.ALLOWED, "exactly 256 bytes");
        expect(acl.checkPublish(PLAYER, prefix + "x".repeat(257 - prefix.length())), Decision.INVALID, "257 bytes");
        // The limit counts UTF-8 bytes: 121 two-byte characters plus the prefix are 257 bytes in 136 chars.
        expect(acl.checkPublish(PLAYER, prefix + "é".repeat(121)), Decision.INVALID, "257 UTF-8 bytes in fewer chars");
    }

    private static void hardDeny(TopicAcl acl) {
        for (Subject subject : List.of(PLAYER, OP, ADMIN, Subject.CONSOLE)) {
            String who = subject == Subject.CONSOLE ? "console" : subject.op() ? "op" : "player";
            expect(acl.checkPublish(subject, "homeassistant/light/x/config"), Decision.DENY_DISCOVERY, "discovery as " + who);
            expect(acl.checkPublish(subject, "homeassistant"), Decision.DENY_DISCOVERY, "the bare discovery prefix as " + who);
            expect(acl.checkPublish(subject, "homeassistant/status"), Decision.DENY_DISCOVERY, "the HA birth topic as " + who);
            expect(acl.checkPublish(subject, "mineqtt/world_7f3a/kitchen_lamp/set"), Decision.DENY_BRIDGE, "a device command as " + who);
            expect(acl.checkPublish(subject, "mineqtt/world_7f3a/bridge/state"), Decision.DENY_BRIDGE, "the bridge state as " + who);
            expect(acl.checkPublish(subject, "mineqtt/world_7f3a"), Decision.DENY_BRIDGE, "the bridge root as " + who);
            expect(acl.checkPublish(subject, "ha_statestream/light/kitchen/state"), Decision.DENY_STATESTREAM, "statestream as " + who);
        }
        expect(acl.checkPublish(PLAYER, "mineqtt/world_7f3a/raw/door"), Decision.ALLOWED, "the raw subtree is open");
        expect(acl.checkPublish(PLAYER, "homeassistantx/foo"), Decision.NOT_ALLOWLISTED, "a prefix match is not a level match");
        expect(acl.checkPublish(PLAYER, "mineqtt/world_7f3b/x"), Decision.NOT_ALLOWLISTED, "another bridge is not this bridge");
    }

    private static void allowlist(TopicAcl acl) {
        expect(acl.checkPublish(PLAYER, "mineqtt/shared/garage_door"), Decision.ALLOWED, "the shared namespace");
        expect(acl.checkPublish(PLAYER, "zigbee2mqtt/kitchen/set"), Decision.ALLOWED, "an admin-added namespace");
        expect(acl.checkPublish(PLAYER, "zigbee2mqtt/kitchen/get"), Decision.NOT_ALLOWLISTED, "outside the admin-added filter");
        expect(acl.checkPublish(PLAYER, "zigbee2mqtt/kitchen/set/brightness"), Decision.NOT_ALLOWLISTED, "+ covers only one level");
        expect(acl.checkPublish(PLAYER, "garage/door"), Decision.NOT_ALLOWLISTED, "an unlisted topic");
        expect(acl.checkPublish(OP, "garage/door"), Decision.NOT_ALLOWLISTED, "ops do not bypass when the bypass is off");
        check(TopicAcl.defaultRawAllow(BASE, BRIDGE).equals(List.of("mineqtt/shared/#", "mineqtt/world_7f3a/raw/#")), "Default allowlist");
        for (Decision decision : Decision.values()) {
            check(!decision.message().isBlank(), decision + " has a status message");
        }
    }

    private static void opBypass() {
        TopicAcl acl = new TopicAcl(rules(withDefaults(), true, false));
        expect(acl.checkPublish(OP, "garage/door"), Decision.ALLOWED_OP_BYPASS, "ops bypass the allowlist when enabled");
        expect(acl.checkPublish(Subject.CONSOLE, "garage/door"), Decision.ALLOWED_OP_BYPASS, "the console counts as op");
        expect(acl.checkPublish(PLAYER, "garage/door"), Decision.NOT_ALLOWLISTED, "players never bypass");
        expect(acl.checkPublish(OP, "homeassistant/light/x/config"), Decision.DENY_DISCOVERY, "the bypass never beats the hard deny");
        expect(acl.checkPublish(OP, "mineqtt/shared/x"), Decision.ALLOWED, "an allowlisted topic is plainly allowed for ops");
    }

    private static void privateSubtree(TopicAcl acl) {
        String own = acl.privateTopic(STEVE, "door");
        check(own.equals("mineqtt/world_7f3a/raw/players/" + STEVE + "/door"), "Private topic layout: " + own);
        String root = acl.privateTopic(STEVE, "");
        check(root.equals("mineqtt/world_7f3a/raw/players/" + STEVE), "An empty private topic is the subtree's root: " + root);
        expect(acl.checkPublish(PLAYER, root), Decision.ALLOWED_PRIVATE, "the root of the owner's private subtree");
        expect(acl.checkPublish(PLAYER, own), Decision.ALLOWED_PRIVATE, "the owner's private topic");
        expect(acl.checkPublish(OTHER, own), Decision.DENY_FOREIGN_PRIVATE, "another player's private topic");
        expect(acl.checkPublish(OP, own), Decision.DENY_FOREIGN_PRIVATE, "another player's private topic as op");
        expect(acl.checkPublish(Subject.CONSOLE, own), Decision.DENY_FOREIGN_PRIVATE, "a private topic from the console");
        expect(acl.checkPublish(PLAYER, "mineqtt/world_7f3a/raw/players"), Decision.DENY_FOREIGN_PRIVATE, "the bare players root");

        TopicAcl narrow = new TopicAcl(rules(List.of("mineqtt/shared/#"), false, false));
        expect(narrow.checkPublish(PLAYER, own), Decision.ALLOWED_PRIVATE, "private mode works without the raw subtree in the allowlist");
        expect(narrow.checkPublish(PLAYER, "mineqtt/world_7f3a/raw/door"), Decision.NOT_ALLOWLISTED, "the raw subtree is only open when listed");
    }

    private static void grandfathering(TopicAcl acl) {
        Grandfather v1 = new Grandfather("garage/door", STEVE);
        expect(acl.checkPublish(PLAYER, "garage/door", v1), Decision.ALLOWED_GRANDFATHERED, "the grandfathered topic for its owner");
        expect(acl.checkPublish(OTHER, "garage/door", v1), Decision.NOT_ALLOWLISTED, "the exemption ends when the owner changes");
        expect(acl.checkPublish(PLAYER, "garage/door2", v1), Decision.NOT_ALLOWLISTED, "the exemption ends when the topic is edited");
        expect(acl.checkPublish(PLAYER, "homeassistant/light/v1/set", new Grandfather("homeassistant/light/v1/set", STEVE)),
                Decision.DENY_DISCOVERY, "grandfathering never beats the hard deny");
        expect(acl.checkPublish(PLAYER, "garage/door", new Grandfather("garage/door", null)), Decision.NOT_ALLOWLISTED,
                "a grandfather flag without an owner exempts no player");
        Subject ownerless = new Subject(null, false, false);
        expect(acl.checkPublish(ownerless, "garage/door", new Grandfather("garage/door", null)), Decision.ALLOWED_GRANDFATHERED,
                "a v1 device without an owner keeps its topic while it has none");
        expect(acl.checkPublish(ownerless, "garage/door2", new Grandfather("garage/door", null)), Decision.NOT_ALLOWLISTED,
                "an ownerless exemption covers only its topic");
        expect(acl.checkPublish(ownerless, "garage/door", new Grandfather("garage/door", STEVE)), Decision.NOT_ALLOWLISTED,
                "a device that lost its owner loses the exemption");
        expect(acl.checkPublish(ownerless, "homeassistant/light/v1/set", new Grandfather("homeassistant/light/v1/set", null)),
                Decision.DENY_DISCOVERY, "an ownerless exemption never beats the hard deny");
    }

    private static void listening(TopicAcl acl) {
        expect(acl.checkListen(PLAYER, "#"), Decision.DENY_BROKER_WIDE, "# for a player");
        expect(acl.checkListen(PLAYER, "+/status"), Decision.DENY_BROKER_WIDE, "a root + for a player");
        expect(acl.checkListen(ADMIN, "#"), Decision.DENY_BROKER_WIDE, "# for an admin without explorerBrokerWide");
        expect(acl.checkListen(PLAYER, "mineqtt/world_7f3a/#"), Decision.ALLOWED, "the own bridge namespace");
        expect(acl.checkListen(PLAYER, "mineqtt/world_7f3a/+/set"), Decision.ALLOWED, "a filter inside the bridge namespace");
        expect(acl.checkListen(PLAYER, "mineqtt/shared/#"), Decision.ALLOWED, "an allowlisted namespace");
        expect(acl.checkListen(PLAYER, "zigbee2mqtt/+/set"), Decision.ALLOWED, "an allowlisted filter");
        expect(acl.checkListen(PLAYER, "zigbee2mqtt/#"), Decision.NOT_ALLOWLISTED, "a filter wider than the allowlist entry");
        expect(acl.checkListen(PLAYER, "homeassistant/#"), Decision.NOT_ALLOWLISTED, "discovery is not in the default scope");
        expect(acl.checkListen(PLAYER, "$SYS/#"), Decision.DOLLAR, "$SYS");
        expect(acl.checkListen(PLAYER, "mineqtt/world_7f3a/raw/players/" + ALEX + "/#"), Decision.DENY_FOREIGN_PRIVATE,
                "another player's private subtree");
        expect(acl.checkListen(PLAYER, "mineqtt/world_7f3a/raw/players/+/door"), Decision.DENY_FOREIGN_PRIVATE,
                "a wildcard across everyone's private topics");
        expect(acl.checkListen(PLAYER, "mineqtt/world_7f3a/raw/players/" + STEVE + "/#"), Decision.ALLOWED, "the own private subtree");

        TopicAcl brokerWide = new TopicAcl(rules(withDefaults(), false, true));
        expect(brokerWide.checkListen(ADMIN, "#"), Decision.ALLOWED, "# for an admin with explorerBrokerWide");
        expect(brokerWide.checkListen(ADMIN, "homeassistant/#"), Decision.ALLOWED, "discovery for a broker-wide admin");
        expect(brokerWide.checkListen(OP, "#"), Decision.DENY_BROKER_WIDE, "an op without mineqtt.admin");
        expect(brokerWide.checkListen(PLAYER, "#"), Decision.DENY_BROKER_WIDE, "a player even with explorerBrokerWide");
        check(acl.redactPayload("homeassistant/light/x/config") && acl.redactPayload("homeassistant"), "Discovery payloads are redacted");
        check(!acl.redactPayload("mineqtt/world_7f3a/lamp"), "Other payloads are shown");
    }

    private static void explorerScope(TopicAcl acl) {
        // The raw subtree of the defaults lies inside the bridge namespace and is not subscribed twice.
        check(acl.listenScope(PLAYER).equals(List.of("mineqtt/world_7f3a/#", "mineqtt/shared/#", "zigbee2mqtt/+/set")),
                "A player's explorer scope: " + acl.listenScope(PLAYER));
        check(acl.listenScope(ADMIN).equals(acl.listenScope(PLAYER)), "An admin without explorerBrokerWide has the player scope");
        TopicAcl brokerWide = new TopicAcl(rules(withDefaults(), false, true));
        check(brokerWide.listenScope(ADMIN).equals(List.of("#")), "A broker-wide admin listens on #");
        check(brokerWide.listenScope(OP).equals(List.of("mineqtt/world_7f3a/#", "mineqtt/shared/#")), "An op is no admin: " + brokerWide.listenScope(OP));
        // A wider allowlist entry replaces the filters it covers; entries the player may not listen on are left out.
        TopicAcl wide = new TopicAcl(rules(List.of("mineqtt/shared/garage", "mineqtt/#", "#", "mineqtt/world_7f3a/raw/players/" + ALEX + "/#"), false, false));
        check(wide.listenScope(PLAYER).equals(List.of("mineqtt/#")), "Covered filters are dropped: " + wide.listenScope(PLAYER));
    }

    private static void explorerExposure(TopicAcl acl) {
        check(acl.explorerExposure(PLAYER, "mineqtt/world_7f3a/bridge/state").equals(Exposure.SHOW), "the bridge telemetry is shown");
        check(acl.explorerExposure(PLAYER, "mineqtt/world_7f3a").equals(Exposure.SHOW), "the bare bridge root is shown");
        check(acl.explorerExposure(PLAYER, "mineqtt/world_7f3a/raw/door").equals(Exposure.SHOW), "the shared raw subtree is shown");
        check(acl.explorerExposure(PLAYER, acl.privateTopic(STEVE, "door")).equals(Exposure.SHOW), "the own private topics are shown");
        check(acl.explorerExposure(PLAYER, acl.privateTopic(ALEX, "door")).equals(Exposure.HIDE), "another player's private topics are hidden");
        check(acl.explorerExposure(ADMIN, acl.privateTopic(STEVE, "door")).equals(Exposure.HIDE), "also from admins");
        check(acl.explorerExposure(PLAYER, "mineqtt/world_7f3a/raw/players").equals(Exposure.HIDE), "the bare players root belongs to no one");
        check(acl.explorerExposure(PLAYER, "mineqtt/world_7f3a/kitchen_lamp/set").equals(new Exposure(Exposure.Kind.DEVICE, "kitchen_lamp")),
                "a device topic names the device's slug");
        check(acl.explorerExposure(PLAYER, "mineqtt/world_7f3a/kitchen_lamp").equals(new Exposure(Exposure.Kind.DEVICE, "kitchen_lamp")),
                "a device's state topic names the device's slug");
        check(acl.explorerExposure(PLAYER, "mineqtt/world_7f3a/group/lights/set").equals(new Exposure(Exposure.Kind.GROUP, "lights")),
                "a group topic names the group's slug");
        check(acl.explorerExposure(PLAYER, "mineqtt/world_7f3a/group").equals(new Exposure(Exposure.Kind.GROUP, "")), "the bare group root");
        check(acl.explorerExposure(ADMIN, "homeassistant/light/x/config").equals(Exposure.REDACT), "discovery payloads are redacted");
        check(acl.explorerExposure(PLAYER, "mineqtt/shared/door").equals(Exposure.SHOW), "an allowlisted namespace is shown");
        check(acl.explorerExposure(ADMIN, "mineqtt/world_7f3b/raw/players/" + STEVE + "/door").equals(Exposure.SHOW),
                "another bridge's topics are shown to whoever may listen on them");
    }

    private static void mirroring(TopicAcl acl) {
        expect(acl.checkMirror(PLAYER, "ha_statestream/light/kitchen/state"), Decision.ALLOWED, "statestream is readable");
        expect(acl.checkMirror(PLAYER, "zigbee2mqtt/+/availability"), Decision.ALLOWED, "a foreign filter");
        expect(acl.checkMirror(PLAYER, "homeassistant/#"), Decision.DENY_DISCOVERY, "discovery payloads are not mirrored");
        expect(acl.checkMirror(PLAYER, "homeassistant/+/x/config"), Decision.DENY_DISCOVERY, "a filter into discovery");
        expect(acl.checkMirror(PLAYER, "#"), Decision.DENY_BROKER_WIDE, "the whole broker");
        expect(acl.checkMirror(PLAYER, "+/state"), Decision.DENY_BROKER_WIDE, "a root +");
        expect(acl.checkMirror(PLAYER, "zigbee2mqtt/a+b"), Decision.INVALID, "a malformed filter");
        expect(acl.checkMirror(PLAYER, "mineqtt/world_7f3a/raw/players/" + ALEX + "/door"), Decision.DENY_FOREIGN_PRIVATE,
                "another player's private topic");
        // Mirrors have no later filtering, so a wildcard that reaches another player's subtree is denied too.
        expect(acl.checkMirror(PLAYER, "mineqtt/+/raw/players/" + ALEX + "/door"), Decision.DENY_FOREIGN_PRIVATE, "+ at the bridge level");
        expect(acl.checkMirror(PLAYER, "mineqtt/world_7f3a/raw/+/" + ALEX + "/#"), Decision.DENY_FOREIGN_PRIVATE, "+ at the players level");
        expect(acl.checkMirror(PLAYER, "mineqtt/world_7f3a/raw/players/+/door"), Decision.DENY_FOREIGN_PRIVATE, "+ at the owner level");
        expect(acl.checkMirror(PLAYER, "mineqtt/world_7f3a/raw/#"), Decision.DENY_FOREIGN_PRIVATE, "the whole raw subtree");
        expect(acl.checkMirror(PLAYER, "mineqtt/#"), Decision.DENY_FOREIGN_PRIVATE, "the whole base topic");
        expect(acl.checkMirror(PLAYER, "mineqtt/world_7f3a/raw/players/" + STEVE + "/#"), Decision.ALLOWED, "the own private subtree");
        expect(acl.checkMirror(PLAYER, "mineqtt/+/raw/players/" + STEVE + "/door"), Decision.ALLOWED, "the own topic through a wildcard");
        expect(acl.checkMirror(PLAYER, "mineqtt/world_7f3a/raw/+"), Decision.ALLOWED, "one level below raw, which no private topic is");
        expect(acl.checkMirror(PLAYER, "mineqtt/shared/#"), Decision.ALLOWED, "the shared namespace");
        expect(acl.checkMirror(Subject.CONSOLE, "mineqtt/world_7f3a/raw/players/+/door"), Decision.DENY_FOREIGN_PRIVATE,
                "the console has no private subtree of its own");
    }

    private static void legacy(TopicAcl acl) {
        expect(acl.checkLegacy("homeassistant/light/v1/config", true), Decision.DENY_DISCOVERY, "a v1 publisher into discovery");
        expect(acl.checkLegacy("mineqtt/world_7f3a/lamp/set", true), Decision.DENY_BRIDGE, "a v1 publisher into the bridge namespace");
        expect(acl.checkLegacy("garage/door", true), Decision.ALLOWED, "the allowlist does not apply to v1 blocks");
        expect(acl.checkLegacy("garage/" + "x".repeat(300), true), Decision.ALLOWED, "v1 topics have no 256-byte limit");
        expect(acl.checkLegacy("garage/+", true), Decision.WILDCARD, "a v1 publish with a wildcard");
        expect(acl.checkLegacy("#", false), Decision.DENY_BROKER_WIDE, "a v1 subscription to #");
        expect(acl.checkLegacy("+/door", false), Decision.DENY_BROKER_WIDE, "a v1 subscription with a root +");
        expect(acl.checkLegacy("garage/#", false), Decision.ALLOWED, "a v1 subscription below a root level");
        expect(acl.checkLegacy("$SYS/#", false), Decision.DOLLAR, "a v1 subscription to $SYS");
        expect(acl.checkLegacy("mineqtt/world_7f3a/raw/players/" + ALEX + "/#", false), Decision.DENY_FOREIGN_PRIVATE,
                "a v1 subscription to a player's private subtree");
        expect(acl.checkLegacy("mineqtt/world_7f3a/+/players/#", false), Decision.DENY_FOREIGN_PRIVATE,
                "a v1 subscription reaching every private subtree");
        expect(acl.checkLegacy("mineqtt/#", false), Decision.DENY_FOREIGN_PRIVATE, "a v1 subscription to the whole base topic");
        expect(acl.checkLegacy("mineqtt/world_7f3a/raw/+", false), Decision.ALLOWED, "a v1 subscription one level below raw");
        expect(acl.checkLegacy("homeassistant/status", false), Decision.ALLOWED, "a v1 listener on HA's birth topic");
    }

    private static void rulesValidation() {
        rejectRules(() -> rules(BASE, "world/7f3a", withDefaults()), "a bridge id with a slash");
        rejectRules(() -> rules(BASE, BRIDGE, List.of("a/#/b")), "an invalid allowlist filter");
        rejectRules(() -> rules("", BRIDGE, withDefaults()), "an empty base topic");
        Rules noStatestream = new Rules(BASE, BRIDGE, "homeassistant", "", withDefaults(), false, false);
        check(noStatestream.statestreamBase() == null, "An empty statestream base means none");
        expect(new TopicAcl(noStatestream).checkPublish(PLAYER, "mineqtt/shared/x"), Decision.ALLOWED, "rules without statestream work");
    }

    private static List<String> withDefaults(String... extra) {
        List<String> filters = new ArrayList<>(TopicAcl.defaultRawAllow(BASE, BRIDGE));
        filters.addAll(List.of(extra));
        return filters;
    }

    private static Rules rules(List<String> rawAllow, boolean opsBypass, boolean brokerWide) {
        return new Rules(BASE, BRIDGE, "homeassistant", "ha_statestream", rawAllow, opsBypass, brokerWide);
    }

    private static Rules rules(String base, String bridge, List<String> rawAllow) {
        return new Rules(base, bridge, "homeassistant", "ha_statestream", rawAllow, false, false);
    }

    private static void rejectRules(Runnable build, String description) {
        try {
            build.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Rules accepted " + description);
    }

    private static void expect(Decision actual, Decision expected, String description) {
        check(actual == expected, description + ": expected " + expected + " but got " + actual);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}

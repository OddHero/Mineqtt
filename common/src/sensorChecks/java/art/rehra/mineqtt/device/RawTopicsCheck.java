package art.rehra.mineqtt.device;

import art.rehra.mineqtt.mqtt.core.TopicAcl;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Where item and paper topics go (M2 plan D1), run by Gradle's verifyRawTopics task: the shared namespace, the
 * allowlist and the owner's literal set, private mode, the hard denies, refusals, the independence from the operator
 * bypass, the one-time re-resolution of M1 links, and resolving a channel again. Strings only, no game bootstrap needed.
 */
public final class RawTopicsCheck {
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final String BRIDGE = "world_7f3a";
    private static final List<String> DEFAULT_ALLOW = TopicAcl.defaultRawAllow("mineqtt", BRIDGE);
    // A dedicated server with the default config, and an integrated one, whose operators bypass the allowlist.
    private static final TopicAcl DEDICATED = acl(DEFAULT_ALLOW, false);
    private static final TopicAcl INTEGRATED = acl(DEFAULT_ALLOW, true);

    public static void main(String[] args) {
        derivedTopics();
        ownerLiteralSet();
        privateMode();
        hardDenyAndRefusal();
        sameForEveryServer();
        reResolution();
        resolvingAgain();
        System.out.println("Raw topic checks passed.");
    }

    private static void derivedTopics() {
        RawTopics.Resolution stone = resolve("minecraft/stone", DEDICATED, STEVE, Set.of());
        check(stone.kind() == RawTopics.Kind.SHARED && stone.topic().equals("mineqtt/shared/minecraft/stone")
                && stone.grandfather() == null && stone.refusal() == null, "Default ACL: an item goes to the shared namespace: " + stone);
        check(resolve("garage", DEDICATED, STEVE, Set.of()).topic().equals("mineqtt/shared/garage"), "Paper 'garage' too");
        RawTopics.Resolution shared = resolve("mineqtt/shared/x", DEDICATED, STEVE, Set.of());
        check(shared.kind() == RawTopics.Kind.LITERAL && shared.topic().equals("mineqtt/shared/x"), "Paper already in the shared namespace stays: " + shared);
        RawTopics.Resolution ownRaw = resolve("mineqtt/" + BRIDGE + "/raw/door", DEDICATED, STEVE, Set.of());
        check(ownRaw.kind() == RawTopics.Kind.LITERAL && ownRaw.topic().equals("mineqtt/" + BRIDGE + "/raw/door"),
                "This server's raw subtree is allowlisted by default: " + ownRaw);
        TopicAcl home = acl(List.of("mineqtt/shared/#", "home/#", "zigbee2mqtt/#"), false);
        RawTopics.Resolution allowed = resolve("home/garage", home, STEVE, Set.of());
        check(allowed.kind() == RawTopics.Kind.LITERAL && allowed.topic().equals("home/garage") && allowed.grandfather() == null,
                "An admin allowlist entry keeps the literal: " + allowed);
        check(resolve("zigbee2mqtt/x/set", home, STEVE, Set.of()).topic().equals("zigbee2mqtt/x/set"), "zigbee2mqtt/# keeps paper verbatim");
        check(resolve("minecraft/stone", home, STEVE, Set.of()).topic().equals("mineqtt/shared/minecraft/stone"), "Other items still go shared");
        check(resolve("minecraft/stone", DEDICATED, null, Set.of()).topic().equals("mineqtt/shared/minecraft/stone"),
                "A device without an owner resolves the same way");
        check(RawTopics.shared("a/b").equals("mineqtt/shared/a/b"), "The shared form");
    }

    private static void ownerLiteralSet() {
        // The sets live in the registry, one per owner.
        DeviceRegistry registry = new DeviceRegistry(BRIDGE, UUID.randomUUID());
        registry.addOwnerLiteral(STEVE, "minecraft/stone");
        registry.addOwnerLiteral(STEVE, "zigbee2mqtt/x/set");
        Set<String> steves = registry.ownerLiterals(STEVE);
        RawTopics.Resolution own = resolve("minecraft/stone", DEDICATED, STEVE, steves);
        check(own.kind() == RawTopics.Kind.OWNER_LITERAL && own.topic().equals("minecraft/stone")
                        && new TopicAcl.Grandfather("minecraft/stone", STEVE).equals(own.grandfather()),
                "A literal of the owner's set stays verbatim with a grandfather flag for the owner: " + own);
        check(DEDICATED.checkPublish(new TopicAcl.Subject(STEVE, false, false), own.topic(), own.grandfather()) == TopicAcl.Decision.ALLOWED_GRANDFATHERED,
                "The ACL's grandfather rule accepts it");
        RawTopics.Resolution other = resolve("minecraft/stone", DEDICATED, ALEX, registry.ownerLiterals(ALEX));
        check(other.kind() == RawTopics.Kind.SHARED && other.topic().equals("mineqtt/shared/minecraft/stone") && other.grandfather() == null,
                "Another owner's identical item goes shared: " + other);
        check(resolve("minecraft/stone", DEDICATED, null, registry.ownerLiterals(null)).kind() == RawTopics.Kind.SHARED
                && resolve("minecraft/stone", DEDICATED, null, steves).kind() == RawTopics.Kind.SHARED, "So does a device without an owner");
        check(resolve("minecraft/dirt", DEDICATED, STEVE, steves).kind() == RawTopics.Kind.SHARED, "Only the literals in the set");
        RawLink link = own.link(RawLinkSpec.Direction.OUT, false);
        check(link.topic().equals("minecraft/stone") && link.grandfathered() != null && link.active() && link.resolved()
                && link.direction() == RawLinkSpec.Direction.OUT && link.qos() == 0 && link.retain() == null, "The new link: " + link);
    }

    private static void privateMode() {
        RawTopics.Resolution own = RawTopics.resolve("minecraft/stone", true, DEDICATED, STEVE, Set.of());
        String expected = "mineqtt/" + BRIDGE + "/raw/players/" + STEVE + "/minecraft/stone";
        check(own.kind() == RawTopics.Kind.PRIVATE && own.topic().equals(expected), "Private mode is unchanged: " + own);
        check(RawTopics.resolve("minecraft/stone", true, DEDICATED, STEVE, Set.of("minecraft/stone")).topic().equals(expected),
                "Private mode wins over the literal set");
        check(RawTopics.resolve("", true, DEDICATED, STEVE, Set.of()).topic().equals("mineqtt/" + BRIDGE + "/raw/players/" + STEVE),
                "Private mode without a base item is the owner's subtree root");
        RawTopics.Resolution unowned = RawTopics.resolve("minecraft/stone", true, DEDICATED, null, Set.of());
        check(unowned.kind() == RawTopics.Kind.REFUSED && "private mode needs a known owner".equals(unowned.refusal())
                && !unowned.link(RawLinkSpec.Direction.IN, false).active(), "Private mode needs a known owner: " + unowned);
    }

    private static void hardDenyAndRefusal() {
        TopicAcl wide = acl(List.of("mineqtt/shared/#", "#"), true);
        RawTopics.Resolution discovery = resolve("homeassistant/x", wide, STEVE, Set.of("homeassistant/x"));
        check(discovery.kind() == RawTopics.Kind.SHARED && discovery.topic().equals("mineqtt/shared/homeassistant/x") && discovery.grandfather() == null,
                "A hard-denied literal only gets its shared form, even when allowlisted or in the set: " + discovery);
        RawTopics.Resolution bridge = resolve("mineqtt/" + BRIDGE + "/lamp/set", DEDICATED, STEVE, Set.of());
        check(bridge.topic().equals("mineqtt/shared/mineqtt/" + BRIDGE + "/lamp/set"), "The bridge namespace is hard denied: " + bridge);

        TopicAcl noShared = acl(List.of("mineqtt/" + BRIDGE + "/raw/#"), true);
        RawTopics.Resolution refused = resolve("minecraft/stone", noShared, STEVE, Set.of());
        check(refused.kind() == RawTopics.Kind.REFUSED && refused.topic().equals("mineqtt/shared/minecraft/stone")
                        && refused.refusal() != null && refused.refusal().contains(TopicAcl.Decision.NOT_ALLOWLISTED.message()),
                "Without mineqtt/shared/# in the allowlist the link is refused with a reason: " + refused);
        RawLink disabled = refused.link(RawLinkSpec.Direction.IN, true);
        check(!disabled.active() && disabled.disabledReason().equals(refused.refusal()) && disabled.stateEcho(), "The refused link is disabled: " + disabled);
        check(resolve("minecraft/stone", noShared, STEVE, Set.of("minecraft/stone")).kind() == RawTopics.Kind.OWNER_LITERAL,
                "The owner's literal set still holds without the shared namespace");
        RawTopics.Resolution wildcard = resolve("home/+/door", DEDICATED, STEVE, Set.of());
        check(wildcard.kind() == RawTopics.Kind.REFUSED && wildcard.refusal().contains(TopicAcl.Decision.WILDCARD.message()),
                "A wildcard paper is refused: " + wildcard);
        RawTopics.Resolution tooLong = resolve("x".repeat(250), DEDICATED, STEVE, Set.of());
        check(tooLong.kind() == RawTopics.Kind.REFUSED && tooLong.refusal().contains(TopicAcl.Decision.INVALID.message()),
                "A literal whose shared form exceeds 256 bytes is refused: " + tooLong.refusal());
    }

    /** The op bypass is never consulted: host, guest and dedicated server give one topic. */
    private static void sameForEveryServer() {
        TopicAcl noShared = acl(List.of("mineqtt/" + BRIDGE + "/raw/#"), true);
        TopicAcl noSharedDedicated = acl(List.of("mineqtt/" + BRIDGE + "/raw/#"), false);
        for (String literal : List.of("minecraft/stone", "garage", "zigbee2mqtt/x/set", "mineqtt/shared/x", "homeassistant/x", "a/+/b")) {
            for (Set<String> literals : List.of(Set.<String>of(), Set.of(literal))) {
                check(resolve(literal, DEDICATED, STEVE, literals).equals(resolve(literal, INTEGRATED, STEVE, literals)),
                        "Operator bypass on or off gives the same topic for " + literal + " " + literals);
                check(resolve(literal, noSharedDedicated, STEVE, literals).equals(resolve(literal, noShared, STEVE, literals)),
                        "Also when the shared namespace is not allowlisted, for " + literal);
            }
        }
    }

    private static void reResolution() {
        // ACL-refused on a dedicated server (the user's broken blocks): resolved like a new link.
        RawLink m1 = m1Link("minecraft/stone");
        RawTopics.ReResolution refused = reResolve(m1, DEDICATED, STEVE, false, Set.of());
        check(refused.pass() == RawTopics.Pass.REMAPPED && refused.link().topic().equals("mineqtt/shared/minecraft/stone")
                        && refused.link().active() && refused.link().resolved() && refused.remember() == null
                        && "minecraft/stone".equals(refused.link().channel()),
                "An M1 link the ACL refuses is remapped, its literal becomes the channel: " + refused);
        check(refused.link().on().equals("OPEN") && refused.link().qos() == 1 && refused.link().direction() == RawLinkSpec.Direction.OUT,
                "The remap keeps payloads, QoS and direction: " + refused.link());

        // Accepted on a dedicated server: kept, the literal joins the set.
        RawLink sharedPaper = m1Link("mineqtt/shared/garage");
        RawTopics.ReResolution accepted = reResolve(sharedPaper, DEDICATED, STEVE, false, Set.of());
        check(accepted.pass() == RawTopics.Pass.KEPT && accepted.link().topic().equals("mineqtt/shared/garage") && accepted.link().resolved()
                        && "mineqtt/shared/garage".equals(accepted.remember()) && "mineqtt/shared/garage".equals(accepted.link().channel()),
                "An accepted M1 link is kept and seeds the set: " + accepted);
        check(reResolve(sharedPaper, DEDICATED, null, false, Set.of()).remember() == null, "Nothing to remember without an owner");

        // Accepted only through the op bypass on an integrated server: kept, the set is seeded.
        RawLink zigbee = m1Link("zigbee2mqtt/x/set");
        TopicAcl.Subject op = new TopicAcl.Subject(STEVE, true, false);
        TopicAcl.Decision bypass = INTEGRATED.checkPublish(op, zigbee.topic());
        check(bypass == TopicAcl.Decision.ALLOWED_OP_BYPASS, "The integrated op bypasses the allowlist");
        RawTopics.ReResolution kept = RawTopics.reResolveM1(zigbee, false, bypass, INTEGRATED, STEVE, Set.of());
        check(kept.pass() == RawTopics.Pass.KEPT && kept.link().topic().equals("zigbee2mqtt/x/set") && "zigbee2mqtt/x/set".equals(kept.remember()),
                "Kept through the op bypass, and the set is seeded: " + kept);
        // A new link of the same owner with that literal then resolves verbatim, also on a dedicated server, and so does
        // the kept link itself when it is resolved again from its channel.
        Set<String> seeded = Set.of(kept.remember());
        RawTopics.Resolution next = resolve("zigbee2mqtt/x/set", DEDICATED, STEVE, seeded);
        check(next.kind() == RawTopics.Kind.OWNER_LITERAL && next.topic().equals("zigbee2mqtt/x/set"), "The owner's new link follows: " + next);
        check(kept.link().resolvedTo(resolve(kept.link().channel(), DEDICATED, STEVE, seeded)).topic().equals("zigbee2mqtt/x/set"),
                "The kept link stays when resolved again");
        check(resolve("zigbee2mqtt/x/set", DEDICATED, ALEX, Set.of()).kind() == RawTopics.Kind.SHARED, "Another owner's goes shared");
        // Untrusted data (a copy, a placed item) never keeps a topic through the op bypass, and seeds nothing.
        RawTopics.ReResolution untrusted = RawTopics.reResolveM1(zigbee, false, null, INTEGRATED, STEVE, Set.of());
        check(untrusted.pass() == RawTopics.Pass.REMAPPED && untrusted.link().topic().equals("mineqtt/shared/zigbee2mqtt/x/set")
                && untrusted.remember() == null, "Untrusted M1 data is resolved like a new link: " + untrusted);

        // A disabled M1 link the ACL now accepts is usable again; one it refuses is remapped and usable.
        RawLink disabled = m1Link("mineqtt/shared/door").checked("not in the raw topic allowlist (acl.rawAllow)");
        check(reResolve(disabled, DEDICATED, STEVE, false, Set.of()).link().active(), "Accepted now: the stale refusal goes");
        RawLink copied = m1Link("zigbee2mqtt/x/set").checked("not in the raw topic allowlist (acl.rawAllow)");
        RawTopics.ReResolution copy = reResolve(copied, DEDICATED, ALEX, false, Set.of());
        check(copy.pass() == RawTopics.Pass.REMAPPED && copy.link().active() && copy.link().topic().equals("mineqtt/shared/zigbee2mqtt/x/set"),
                "A refused copy moves to the shared namespace: " + copy);

        // The owner's literal set turns a refused topic into a kept one.
        RawTopics.ReResolution fromSet = reResolve(m1, DEDICATED, STEVE, false, Set.of("minecraft/stone"));
        check(fromSet.pass() == RawTopics.Pass.KEPT && fromSet.link().topic().equals("minecraft/stone") && fromSet.link().grandfathered() != null,
                "A literal of the owner's set stays verbatim with its grandfather flag: " + fromSet);

        // Refused in both forms: moved to the shared form, disabled with the reason.
        TopicAcl noShared = acl(List.of("mineqtt/" + BRIDGE + "/raw/#"), false);
        RawTopics.ReResolution nowhere = reResolve(m1, noShared, STEVE, false, Set.of());
        check(nowhere.pass() == RawTopics.Pass.REMAPPED && !nowhere.link().active() && nowhere.link().resolved(), "Refused everywhere: " + nowhere);

        // Excluded links.
        RawLink grandfathered = new RawLink("home/garage", RawLinkSpec.Direction.IN, null, null, 0, null,
                new TopicAcl.Grandfather("home/garage", STEVE), true, null, 0, null, RawLink.Edges.BOTH);
        check(reResolve(grandfathered, DEDICATED, STEVE, false, Set.of()).pass() == RawTopics.Pass.NONE
                && !reResolve(grandfathered, DEDICATED, STEVE, false, Set.of()).link().resolved(), "A grandfathered v1 topic stays as it is");
        check(reResolve(grandfathered.withoutGrandfather(), DEDICATED, STEVE, false, Set.of()).pass() == RawTopics.Pass.NONE,
                "A copy of one has a channel: it is resolved again at the attach, not by this pass");
        String privateTopic = "mineqtt/" + BRIDGE + "/raw/players/" + STEVE + "/minecraft/stone";
        RawLink privateLink = m1Link(privateTopic);
        RawTopics.ReResolution marked = reResolve(privateLink, DEDICATED, STEVE, true, Set.of());
        check(marked.pass() == RawTopics.Pass.MARKED && marked.link().equals(privateLink.markResolved("minecraft/stone")) && marked.remember() == null,
                "A private-mode link is only marked, its literal becomes the channel: " + marked);
        check(marked.link().resolvedTo(RawTopics.resolve(marked.link().channel(), true, DEDICATED, STEVE, Set.of())).topic().equals(privateTopic),
                "Resolved again, it stays in the owner's subtree");
        check(reResolve(m1Link("mineqtt/" + BRIDGE + "/raw/players/" + ALEX + "/x"), DEDICATED, STEVE, true, Set.of()).link().channel() == null,
                "Another owner's private topic gets no channel");
        RawLink privateUnowned = m1Link("Steve/minecraft/stone").checked("private mode needs a known owner");
        check(reResolve(privateUnowned, DEDICATED, null, true, Set.of()).link().equals(privateUnowned.markResolved()),
                "A private-mode link without an owner keeps its refusal");

        // The second pass is a no-op.
        for (RawTopics.ReResolution first : List.of(refused, accepted, kept, untrusted, copy, nowhere, marked)) {
            RawTopics.ReResolution second = reResolve(first.link(), DEDICATED, STEVE, false, Set.of());
            check(second.pass() == RawTopics.Pass.NONE && second.link().equals(first.link()) && second.remember() == null,
                    "The second pass changes nothing: " + first.link());
        }
        RawLink stored = RawLink.CODEC.parse(JsonOps.INSTANCE,
                RawLink.CODEC.encodeStart(JsonOps.INSTANCE, refused.link()).getOrThrow()).getOrThrow();
        check(stored.equals(refused.link()) && stored.resolved() && "minecraft/stone".equals(stored.channel()), "The marker and the channel survive saving");
        JsonObject m1Json = RawLink.CODEC.encodeStart(JsonOps.INSTANCE, m1).getOrThrow().getAsJsonObject();
        check(!m1Json.has("resolvedV") && !m1Json.has("channel"), "An M1 link has no marker and no channel");
    }

    /**
     * A link with a channel is resolved again whenever the result could change (at every attach, when its owner's literal
     * set gains the channel): the outcome follows the owner, their literal set and the ACL, never the attach order.
     */
    private static void resolvingAgain() {
        // The owner's v1 emitter attaches after their M1 publisher on the same item moved to the shared form: resolved
        // again, the publisher follows the literal the emitter added to the set, as if the emitter had attached first.
        RawLink publisher = reResolve(m1Link("minecraft/stone"), DEDICATED, STEVE, false, Set.of()).link();
        RawLink after = publisher.resolvedTo(resolve(publisher.channel(), DEDICATED, STEVE, Set.of("minecraft/stone")));
        RawLink emitterFirst = reResolve(m1Link("minecraft/stone"), DEDICATED, STEVE, false, Set.of("minecraft/stone")).link();
        check(after.topic().equals("minecraft/stone") && after.equals(emitterFirst), "Either attach order ends on the owner's literal: " + after);

        // The owner's copy of their literal-set link keeps it; another owner's copy goes shared.
        RawLink own = resolve("minecraft/stone", DEDICATED, STEVE, Set.of("minecraft/stone")).link(RawLinkSpec.Direction.OUT, false);
        RawLink copy = own.withoutGrandfather().checked("not in the raw topic allowlist (acl.rawAllow)");
        RawLink ownCopy = copy.resolvedTo(resolve(copy.channel(), DEDICATED, STEVE, Set.of("minecraft/stone")));
        check(ownCopy.equals(own) && ownCopy.active(), "The owner's copy resolves to the owner's literal again: " + ownCopy);
        RawLink otherCopy = copy.resolvedTo(resolve(copy.channel(), DEDICATED, ALEX, Set.of()));
        check(otherCopy.active() && otherCopy.topic().equals("mineqtt/shared/minecraft/stone") && otherCopy.grandfathered() == null,
                "Another owner's copy goes shared: " + otherCopy);

        // A refusal heals once the configuration allows the shared form again.
        RawLink refused = reResolve(m1Link("minecraft/stone"), acl(List.of("zigbee2mqtt/#"), false), STEVE, false, Set.of()).link();
        check(!refused.active(), "Refused while the shared namespace is not allowlisted: " + refused);
        RawLink healed = refused.resolvedTo(resolve(refused.channel(), DEDICATED, STEVE, Set.of()));
        check(healed.active() && healed.topic().equals("mineqtt/shared/minecraft/stone") && healed.on().equals("OPEN"),
                "Resolved again under the fixed configuration it is usable: " + healed);
        check(healed.resolvedTo(resolve(healed.channel(), DEDICATED, STEVE, Set.of())).equals(healed), "Resolving again is stable");
    }

    private static RawTopics.ReResolution reResolve(RawLink link, TopicAcl acl, UUID owner, boolean privateMode, Set<String> literals) {
        TopicAcl.Decision decision = acl.checkPublish(new TopicAcl.Subject(owner, false, false), link.topic());
        return RawTopics.reResolveM1(link, privateMode, decision, acl, owner, literals);
    }

    // A link as M1 saved it from the slots: no marker, no channel.
    private static RawLink m1Link(String topic) {
        return new RawLink(topic, RawLinkSpec.Direction.OUT, "OPEN", null, 1, null, null, false, null, 0, null, RawLink.Edges.BOTH);
    }

    private static RawTopics.Resolution resolve(String literal, TopicAcl acl, UUID owner, Set<String> literals) {
        return RawTopics.resolve(literal, false, acl, owner, literals);
    }

    private static TopicAcl acl(List<String> rawAllow, boolean opsBypass) {
        return new TopicAcl(new TopicAcl.Rules("mineqtt", BRIDGE, "homeassistant", null, rawAllow, opsBypass, false));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

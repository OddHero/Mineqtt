package art.rehra.mineqtt.presence;

import art.rehra.mineqtt.config.TelemetryPlayers;
import art.rehra.mineqtt.device.DeviceRegistry;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.network.MineqttNetworking;
import art.rehra.mineqtt.security.AclContext;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code verifyPrivacy} (M4 plan D9, D10), bootstrapped: the consent matrix (the integrated host implicitly at presence,
 * a LAN guest and every dedicated player not; an explicit off wins; {@code publishPlayerNames=false} vetoes everything;
 * {@code telemetry.players=off}; full only with consent full and mode full), the first-join prompt once, the choices of
 * {@code /mineqtt privacy share} and the prompt's buttons that run them, what {@code /mineqtt privacy} reports, the
 * player entry's codec and what the registry stores of it, the presence devices' lifecycle ({@link PresenceLifecycleCheck}) and
 * who the explorer shows a presence topic to.
 */
public final class PrivacyCheck {
    private static final UUID STEVE = UUID.fromString("6a1c0f4e-0b6e-4a43-9d76-2f4f6f0e5a11");
    private static final UUID ALEX = UUID.fromString("0f3e2d1c-4b5a-4968-8776-655443322110");
    private static final long DAY = 20_738;

    private PrivacyCheck() {
    }

    static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        consentMatrix();
        prompt();
        choices();
        promptButtons();
        guiChoices();
        report();
        entryCodec();
        registryEntries();
        PresenceLifecycleCheck.run();
        playerTopics();
        System.out.println("Privacy checks passed.");
    }

    private static void consentMatrix() {
        check(Consent.UNSET.effective(true) == Consent.PRESENCE, "The integrated host who chose nothing shares presence");
        check(Consent.UNSET.effective(false) == Consent.UNSET, "A LAN guest or a dedicated server's player who chose nothing does not");
        check(Consent.OFF.effective(true) == Consent.OFF, "An explicit off wins, also for the host");
        check(Consent.FULL.effective(false) == Consent.FULL && Consent.PRESENCE.effective(true) == Consent.PRESENCE, "An explicit choice is kept");
        check(!Consent.UNSET.consents() && !Consent.OFF.consents() && Consent.PRESENCE.consents() && Consent.FULL.consents(), "Only presence and full consent");

        for (TelemetryPlayers mode : TelemetryPlayers.values()) {
            for (boolean names : new boolean[]{true, false}) {
                PrivacyPolicy policy = new PrivacyPolicy(mode, names);
                for (Consent consent : Consent.values()) {
                    for (boolean host : new boolean[]{true, false}) {
                        Consent effective = consent.effective(host);
                        boolean named = names && mode != TelemetryPlayers.OFF && effective.consents();
                        String what = mode + "/names=" + names + "/" + consent + "/host=" + host;
                        check(policy.mayName(effective) == named, "Named exactly with consent, names on and a mode: " + what);
                        check(policy.presence(effective) == named, "A presence device exactly when named: " + what);
                        check(policy.full(effective) == (named && effective == Consent.FULL && mode == TelemetryPlayers.FULL),
                                "Full data only with consent full and mode full: " + what);
                    }
                }
            }
        }
        check(new PrivacyPolicy(TelemetryPlayers.FULL, false).offered() == PrivacyPolicy.Shared.NOTHING, "publishPlayerNames=false offers nothing");
        check(new PrivacyPolicy(TelemetryPlayers.OFF, true).offered() == PrivacyPolicy.Shared.NOTHING, "telemetry.players=off offers nothing");
        check(new PrivacyPolicy(TelemetryPlayers.PRESENCE, true).shared(Consent.FULL) == PrivacyPolicy.Shared.PRESENCE, "share full is capped by a presence server");
        check(new PrivacyPolicy(TelemetryPlayers.FULL, true).shared(Consent.FULL) == PrivacyPolicy.Shared.FULL, "and full on a full server");
        check(new PrivacyPolicy(TelemetryPlayers.FULL, true).shared(Consent.PRESENCE) == PrivacyPolicy.Shared.PRESENCE, "share on stays presence on a full server");
    }

    private static void prompt() {
        PrivacyPolicy presence = new PrivacyPolicy(TelemetryPlayers.PRESENCE, true);
        PlayerEntry fresh = PlayerEntry.of(STEVE);
        check(presence.prompt(fresh, false, true) == PrivacyPolicy.Prompt.CHOICES, "A new player on a sharing server is offered the choices");
        check(presence.prompt(fresh, true, true) == PrivacyPolicy.Prompt.HOST_NOTE, "The integrated host gets the note instead");
        check(presence.prompt(fresh.withAsked(), false, true) == PrivacyPolicy.Prompt.NONE, "A player who was asked is not asked again");
        check(presence.prompt(fresh.withConsent(Consent.OFF), false, true) == PrivacyPolicy.Prompt.NONE, "nor one who chose already");
        check(presence.prompt(fresh, false, false) == PrivacyPolicy.Prompt.NONE, "Nobody is asked without a broker or with ha.expose NONE");
        check(new PrivacyPolicy(TelemetryPlayers.OFF, true).prompt(fresh, false, true) == PrivacyPolicy.Prompt.NONE, "nor with telemetry.players=off");
        check(new PrivacyPolicy(TelemetryPlayers.FULL, false).prompt(fresh, false, true) == PrivacyPolicy.Prompt.NONE, "nor with publishPlayerNames=false");

        // Once: the join marks the entry, and the next join shows nothing.
        PlayerPrivacy.Joined first = PlayerPrivacy.joined(fresh, false, "Steve", DAY, presence, true);
        check(first.prompt() == PrivacyPolicy.Prompt.CHOICES && first.entry().asked(), "The first join shows the prompt and marks the player asked");
        check(!first.entry().personal(), "A player who did not consent leaves no name behind: " + first.entry());
        PlayerPrivacy.Joined second = PlayerPrivacy.joined(first.entry(), false, "Steve", DAY + 1, presence, true);
        check(second.prompt() == PrivacyPolicy.Prompt.NONE && second.entry().equals(first.entry()), "A rejoin shows nothing and changes nothing");
        PlayerPrivacy.Joined unasked = PlayerPrivacy.joined(fresh, false, "Steve", DAY, new PrivacyPolicy(TelemetryPlayers.OFF, true), true);
        check(unasked.prompt() == PrivacyPolicy.Prompt.NONE && !unasked.entry().asked(), "A join on a server that shares nothing marks nobody asked");
        PlayerPrivacy.Joined consenting = PlayerPrivacy.joined(fresh.withConsent(Consent.PRESENCE).withAsked(), false, "Steve", DAY, presence, true);
        check(consenting.entry().name().equals("Steve") && consenting.entry().lastSeenDay() == DAY, "A consenting player's name and day are kept at the join");
        PlayerPrivacy.Joined host = PlayerPrivacy.joined(fresh, true, "Host", DAY, presence, true);
        check(host.prompt() == PrivacyPolicy.Prompt.HOST_NOTE && host.entry().name().equals("Host") && host.entry().asked(),
                "The host is told once, and their name is kept since they share presence");
    }

    private static void choices() {
        check(PrivacyCommands.Choice.ON.consent() == Consent.PRESENCE && PrivacyCommands.Choice.FULL.consent() == Consent.FULL
                && PrivacyCommands.Choice.OFF.consent() == Consent.OFF, "share on is presence, full is full, off is off");
        check(PrivacyCommands.Choice.ON.word().equals("on") && PrivacyCommands.Choice.FULL.word().equals("full") && PrivacyCommands.Choice.OFF.word().equals("off"),
                "The words the command takes");
        PlayerEntry fresh = PlayerEntry.of(STEVE);
        PlayerEntry on = PlayerPrivacy.chosen(fresh, Consent.PRESENCE, false, "Steve", DAY);
        check(on.consent() == Consent.PRESENCE && on.asked() && on.name().equals("Steve") && on.lastSeenDay() == DAY, "share on keeps the name and the day and answers the prompt");
        PlayerEntry full = PlayerPrivacy.chosen(on, Consent.FULL, false, "Steve", DAY);
        check(full.consent() == Consent.FULL, "share full is stored as full, whatever the server's mode: a wider mode later honours it");
        PlayerEntry off = PlayerPrivacy.chosen(on.withSlug(Optional.of("player_steve")), Consent.OFF, false, "Steve", DAY);
        check(off.consent() == Consent.OFF && !off.personal() && off.asked(), "share off removes name, slug and day, and keeps the decision: " + off);
        PlayerEntry hostOff = PlayerPrivacy.chosen(PlayerEntry.of(ALEX), Consent.OFF, true, "Host", DAY);
        check(!hostOff.personal(), "The host who stops sharing keeps nothing personal either");
    }

    private static void promptButtons() {
        PrivacyPolicy presence = new PrivacyPolicy(TelemetryPlayers.PRESENCE, true);
        PrivacyPolicy full = new PrivacyPolicy(TelemetryPlayers.FULL, true);
        check(PrivacyCommands.prompt(PrivacyPolicy.Prompt.NONE, presence).isEmpty(), "No prompt, no line");
        List<String> presenceCommands = commands(PrivacyCommands.prompt(PrivacyPolicy.Prompt.CHOICES, presence).orElseThrow());
        check(presenceCommands.equals(List.of("/mineqtt privacy share on", "/mineqtt privacy share off")),
                "On a presence server the buttons are [Share presence] and [Don't share]: " + presenceCommands);
        List<String> fullCommands = commands(PrivacyCommands.prompt(PrivacyPolicy.Prompt.CHOICES, full).orElseThrow());
        check(fullCommands.equals(List.of("/mineqtt privacy share on", "/mineqtt privacy share full", "/mineqtt privacy share off")),
                "On a full server [Share all] joins them: " + fullCommands);
        List<String> hostCommands = commands(PrivacyCommands.prompt(PrivacyPolicy.Prompt.HOST_NOTE, full).orElseThrow());
        check(hostCommands.equals(List.of("/mineqtt privacy share off")), "The host's note has only [Stop sharing]: " + hostCommands);
    }

    /**
     * M4-18 (D9): the explorer's You row offers exactly the prompt's choices, and its payload names a choice of the command:
     * each choice it sends is the {@code /mineqtt privacy share <word>} its button would run, handled by the same
     * {@link PrivacyCommands#share} (one code path, so the stored consent is the command's).
     */
    private static void guiChoices() {
        for (TelemetryPlayers mode : TelemetryPlayers.values()) {
            for (boolean names : List.of(true, false)) {
                PrivacyPolicy policy = new PrivacyPolicy(mode, names);
                List<String> fromRow = PrivacyCommands.offered(policy.offered()).stream().map(choice -> PrivacyCommands.SHARE_COMMAND + choice.word()).toList();
                check(fromRow.equals(commands(PrivacyCommands.choices(policy))), "The You row offers the prompt's choices (" + mode + ", names " + names + "): " + fromRow);
            }
        }
        for (PrivacyCommands.Choice choice : PrivacyCommands.Choice.values()) {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            MineqttNetworking.PrivacyChoicePayload.CODEC.encode(buf, new MineqttNetworking.PrivacyChoicePayload(choice));
            PrivacyCommands.Choice read = MineqttNetworking.PrivacyChoicePayload.CODEC.decode(buf).choice();
            PlayerEntry byRow = PlayerPrivacy.chosen(PlayerEntry.of(STEVE), read.consent(), false, "Steve", DAY);
            PlayerEntry byCommand = PlayerPrivacy.chosen(PlayerEntry.of(STEVE), PrivacyCommands.Choice.valueOf(choice.word().toUpperCase(Locale.ROOT)).consent(),
                    false, "Steve", DAY);
            check(read == choice && byRow.equals(byCommand), "The row's " + choice + " stores what /mineqtt privacy share " + choice.word() + " stores: " + byRow);
        }
    }

    private static void report() {
        PrivacyPolicy presence = new PrivacyPolicy(TelemetryPlayers.PRESENCE, true);
        List<String> unset = presence.report(Consent.UNSET, false, true, Optional.empty());
        check(unset.getFirst().contains("nothing is shared") && unset.stream().anyMatch(line -> line.contains("nothing that names you")),
                "A player who chose nothing reads that nothing names them: " + unset);
        check(unset.getLast().contains("number of players online"), "Everyone reads what is published about everyone");
        List<String> on = presence.report(Consent.PRESENCE, false, true, Optional.of("\"Player Steve\" (mineqtt/w/player/player_steve)"));
        check(on.stream().anyMatch(line -> line.contains("your name")) && on.stream().anyMatch(line -> line.contains("deaths"))
                && on.stream().anyMatch(line -> line.contains("presence device")), "A consenting player reads name, events and device: " + on);
        check(on.stream().noneMatch(line -> line.contains("position")), "but no position on a presence server");
        List<String> capped = presence.report(Consent.FULL, false, true, Optional.of("x"));
        check(capped.stream().anyMatch(line -> line.contains("shares presence only")), "share full on a presence server says it is capped: " + capped);
        List<String> fullServer = new PrivacyPolicy(TelemetryPlayers.FULL, true).report(Consent.FULL, false, true, Optional.of("x"));
        check(fullServer.stream().anyMatch(line -> line.contains("position")), "Full data is listed where it is published: " + fullServer);
        List<String> host = presence.report(Consent.UNSET, true, true, Optional.empty());
        check(host.getFirst().contains("host") && host.stream().anyMatch(line -> line.contains("your name")), "The host reads that they share presence: " + host);
        List<String> vetoed = new PrivacyPolicy(TelemetryPlayers.FULL, false).report(Consent.FULL, false, true, Optional.empty());
        check(vetoed.stream().anyMatch(line -> line.contains("player names are turned off")) && vetoed.stream().anyMatch(line -> line.contains("nothing that names you")),
                "The admin veto is named, and nothing names the player: " + vetoed);
        List<String> noBroker = presence.report(Consent.PRESENCE, false, false, Optional.empty());
        check(noBroker.stream().anyMatch(line -> line.contains("MQTT is not configured")) && noBroker.stream().noneMatch(line -> line.contains("your name")),
                "Without a broker nothing is published: " + noBroker);
    }

    private static void entryCodec() {
        PlayerEntry entry = new PlayerEntry(STEVE, Consent.FULL, true, "Steve", Optional.of("player_steve"), DAY);
        Tag tag = PlayerEntry.CODEC.encodeStart(NbtOps.INSTANCE, entry).getOrThrow();
        check(PlayerEntry.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow().equals(entry), "An entry reads back as written");
        check(((CompoundTag) tag).getStringOr("consent", "").equals("full"), "The consent is stored by its name");
        Tag bare = PlayerEntry.CODEC.encodeStart(NbtOps.INSTANCE, PlayerEntry.of(ALEX)).getOrThrow();
        check(((CompoundTag) bare).keySet().equals(java.util.Set.of("uuid")),
                "A bare entry stores only its UUID, the defaults are left out: " + ((CompoundTag) bare).keySet());
        CompoundTag minimal = new CompoundTag();
        minimal.store("uuid", net.minecraft.core.UUIDUtil.CODEC, ALEX);
        check(PlayerEntry.CODEC.parse(NbtOps.INSTANCE, minimal).getOrThrow().equals(PlayerEntry.of(ALEX)), "Only the UUID is required; the rest defaults");
        CompoundTag unknown = minimal.copy();
        unknown.putString("consent", "everything");
        check(PlayerEntry.CODEC.parse(NbtOps.INSTANCE, unknown).isError(), "An unknown consent makes the entry unreadable");
        PlayerEntry cut = new PlayerEntry(STEVE, Consent.PRESENCE, false, "A_very_long_name_indeed\u0007", Optional.of("Not A Slug"), -5);
        check(cut.name().equals("A_very_long_name") && cut.slug().isEmpty() && cut.lastSeenDay() == 0,
                "A name is cut to 16 characters without control characters, a slug that is no topic level dropped: " + cut);
        check(entry.forgotten().equals(new PlayerEntry(STEVE, Consent.FULL, true, "", Optional.empty(), 0)), "Forgetting keeps the consent and the prompt");
    }

    private static void registryEntries() {
        DeviceRegistry registry = new DeviceRegistry("world_0001", UUID.randomUUID());
        check(registry.player(STEVE).isEmpty() && registry.players().isEmpty(), "A new registry stores no player");
        registry.putPlayer(PlayerEntry.of(STEVE).withConsent(Consent.PRESENCE).seen("Steve", DAY));
        check(registry.structureChanged(), "A consent is a structural change, saved within a second");
        CompoundTag saved = registry.encode();
        DeviceRegistry read = DeviceRegistry.decode(saved, problem -> {
            throw new AssertionError(problem);
        });
        check(read.player(STEVE).orElseThrow().equals(registry.player(STEVE).orElseThrow()), "The entry is saved and read back");
        read.putPlayer(read.player(STEVE).orElseThrow().withLastSeenDay(DAY + 1));
        check(read.isDirty() && !read.structureChanged(), "A new last seen day alone waits for the autosave");
        read.putPlayer(read.player(STEVE).orElseThrow().withSlug(Optional.of("player_steve")));
        check(read.structureChanged() && read.playerBySlug("player_steve").orElseThrow().uuid().equals(STEVE), "A presence slug is structural and found by slug");
        check(fails(() -> read.putPlayer(PlayerEntry.of(ALEX).withSlug(Optional.of("player_steve")))), "Two players never share a slug");

        // A damaged list: garbage, a duplicate and a slug taken twice are reported; the rest survives.
        CompoundTag tag = read.encode();
        ListTag players = tag.getListOrEmpty("players");
        players.add(net.minecraft.nbt.StringTag.valueOf("not a player"));
        players.add(players.getCompoundOrEmpty(0).copy());
        CompoundTag sameSlug = players.getCompoundOrEmpty(0).copy();
        sameSlug.store("uuid", net.minecraft.core.UUIDUtil.CODEC, ALEX);
        players.add(sameSlug);
        List<String> problems = new ArrayList<>();
        DeviceRegistry repaired = DeviceRegistry.decode(tag, problems::add);
        check(problems.size() == 3, "Each bad player entry is reported: " + problems);
        check(repaired.players().size() == 2 && repaired.player(ALEX).orElseThrow().slug().isEmpty()
                && repaired.player(STEVE).orElseThrow().slug().equals(Optional.of("player_steve")), "The first holder keeps the slug, the second loses it");
    }

    /** D10: who the explorer shows a presence device's {@code player/<slug>} topics to (TopicAcl names it Kind.PLAYER). */
    private static void playerTopics() {
        java.util.function.Function<UUID, Optional<String>> slugs = player -> player.equals(STEVE) ? Optional.of("player_steve") : Optional.empty();
        TopicAcl.Subject steve = new TopicAcl.Subject(STEVE, false, false);
        TopicAcl.Subject alexOp = new TopicAcl.Subject(ALEX, true, false);
        TopicAcl.Subject alexAdmin = new TopicAcl.Subject(ALEX, true, true);
        check(AclContext.mayReadPlayerTopic(steve, "player_steve", slugs), "A player sees their own presence topic");
        check(!AclContext.mayReadPlayerTopic(steve, "player_alex", slugs), "not another player's");
        check(!AclContext.mayReadPlayerTopic(steve, "", slugs), "nor the bare player level");
        check(!AclContext.mayReadPlayerTopic(alexOp, "player_steve", slugs), "An operator who is no admin does not see it either");
        check(AclContext.mayReadPlayerTopic(alexAdmin, "player_steve", slugs) && AclContext.mayReadPlayerTopic(TopicAcl.Subject.CONSOLE, "player_steve", slugs),
                "Admins and the console see every presence topic");
        check(!AclContext.mayReadPlayerTopic(new TopicAcl.Subject(ALEX, false, false), "player_steve", slugs), "A player without a device sees none");
    }

    // The commands of the click events in a line, in order.
    private static List<String> commands(Component line) {
        List<String> commands = new ArrayList<>();
        line.visit((style, text) -> {
            if (style.getClickEvent() instanceof ClickEvent.RunCommand run && (commands.isEmpty() || !commands.getLast().equals(run.command()))) {
                commands.add(run.command());
            }
            return Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return commands;
    }

    private static boolean fails(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return true;
        }
        return false;
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}

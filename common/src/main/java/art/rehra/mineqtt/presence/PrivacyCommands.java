package art.rehra.mineqtt.presence;

import art.rehra.mineqtt.runtime.MineqttRuntime;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The privacy commands (M4 plan D9) and the chat lines that offer them:
 * <ul>
 *     <li>{@code /mineqtt privacy}, for every player: what this server publishes about them, given its mode;</li>
 *     <li>{@code /mineqtt privacy share on|full|off}: their choice ({@link #share}, the code path the GUI shares);</li>
 *     <li>{@code /mineqtt players list|forget <player>}, for operators.</li>
 * </ul>
 * The first-join prompt's buttons run the player's own {@code /mineqtt privacy share ...}, which every player reaches
 * because the {@code /mineqtt} root is open (D14).
 */
public final class PrivacyCommands {
    /** The command a prompt button runs for each choice. */
    public static final String SHARE_COMMAND = "/mineqtt privacy share ";

    private static final SuggestionProvider<CommandSourceStack> STORED_PLAYERS = (context, builder) ->
            SharedSuggestionProvider.suggest(running(context.getSource()).map(runtime -> Stream.concat(
                    runtime.registry().players().stream().filter(entry -> !entry.name().isEmpty()).map(PlayerEntry::name),
                    runtime.registry().players().stream().filter(entry -> entry.name().isEmpty()).map(entry -> entry.uuid().toString())).toList()).orElse(List.of()), builder);

    private PrivacyCommands() {
    }

    /** A choice as {@code /mineqtt privacy share} names it. */
    public enum Choice {
        ON(Consent.PRESENCE),
        FULL(Consent.FULL),
        OFF(Consent.OFF);

        private final Consent consent;

        Choice(Consent consent) {
            this.consent = consent;
        }

        public Consent consent() {
            return consent;
        }

        /** The word typed after {@code share}. */
        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** {@code /mineqtt privacy}: open to every source; its leaves refuse a source that is no player. */
    public static LiteralArgumentBuilder<CommandSourceStack> privacy() {
        LiteralArgumentBuilder<CommandSourceStack> share = Commands.literal("share");
        for (Choice choice : Choice.values()) {
            share.then(Commands.literal(choice.word()).executes(context -> asPlayer(context, (runtime, player) -> {
                List<String> lines = share(runtime, player, choice);
                context.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
                return 1;
            })));
        }
        return Commands.literal("privacy")
                .executes(context -> asPlayer(context, (runtime, player) -> {
                    context.getSource().sendSuccess(() -> Component.literal(String.join("\n", report(runtime, player))).append("\n").append(choices(runtime.privacy().policy())), false);
                    return 1;
                }))
                .then(share);
    }

    /** {@code /mineqtt players list|forget <player>}: operators (level 2). */
    public static LiteralArgumentBuilder<CommandSourceStack> players() {
        return Commands.literal("players")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("list")
                        .executes(context -> withRuntime(context, runtime -> list(context.getSource(), runtime))))
                .then(Commands.literal("forget")
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(STORED_PLAYERS)
                                .executes(context -> withRuntime(context, runtime ->
                                        forget(context.getSource(), runtime, StringArgumentType.getString(context, "player"))))));
    }

    /**
     * A player's choice, the one code path of the command and the GUI: stored, then reported as what is published about
     * them now.
     *
     * @return the lines to show the player
     */
    public static List<String> share(MineqttRuntime runtime, ServerPlayer player, Choice choice) {
        runtime.privacy().choose(player, choice.consent());
        List<String> lines = new java.util.ArrayList<>();
        lines.add("Saved: " + switch (choice) {
            case ON -> "share presence.";
            case FULL -> "share all.";
            case OFF -> "don't share.";
        });
        lines.addAll(report(runtime, player));
        return lines;
    }

    /** What {@code /mineqtt privacy} shows a player ({@link PrivacyPolicy#report}). */
    public static List<String> report(MineqttRuntime runtime, ServerPlayer player) {
        PlayerPrivacy privacy = runtime.privacy();
        PlayerEntry entry = privacy.entry(player.getUUID());
        return privacy.policy().report(entry.consent(), privacy.host(player.getUUID()), privacy.mqttConfigured(), runtime.presence().describe(player.getUUID()));
    }

    /**
     * The first-join prompt (D9), or the host's note: one chat line with buttons that run the player's own
     * {@code /mineqtt privacy share ...}.
     */
    public static Optional<Component> prompt(PrivacyPolicy.Prompt prompt, PrivacyPolicy policy) {
        return switch (prompt) {
            case NONE -> Optional.empty();
            case CHOICES -> Optional.of(Component.translatable("mineqtt.privacy.prompt").append(" ").append(choices(policy)));
            case HOST_NOTE -> Optional.of(Component.translatable("mineqtt.privacy.host_note").append(" ")
                    .append(button("mineqtt.privacy.stop_sharing", Choice.OFF, ChatFormatting.RED)));
        };
    }

    /**
     * The choices a player is offered, in the chat and in the explorer's You row alike: share presence, share all only
     * where the server shares full data, and don't share.
     *
     * @param offered what the server shares at most ({@link PrivacyPolicy#offered})
     */
    public static List<Choice> offered(PrivacyPolicy.Shared offered) {
        return offered == PrivacyPolicy.Shared.FULL ? List.of(Choice.ON, Choice.FULL, Choice.OFF) : List.of(Choice.ON, Choice.OFF);
    }

    /** {@code [Share presence] [Share all] [Don't share]}; {@code [Share all]} only where the server shares full data. */
    public static MutableComponent choices(PrivacyPolicy policy) {
        // An empty parent, so the spaces between the buttons do not inherit a button's click.
        MutableComponent line = Component.empty();
        for (Choice choice : offered(policy.offered())) {
            if (choice != Choice.ON) line.append(" ");
            line.append(switch (choice) {
                case ON -> button("mineqtt.privacy.share_presence", Choice.ON, ChatFormatting.GREEN);
                case FULL -> button("mineqtt.privacy.share_all", Choice.FULL, ChatFormatting.GREEN);
                case OFF -> button("mineqtt.privacy.dont_share", Choice.OFF, ChatFormatting.RED);
            });
        }
        return line;
    }

    private static MutableComponent button(String key, Choice choice, ChatFormatting colour) {
        return Component.translatable(key).withStyle(style -> style.withColor(colour)
                //? if >=1.21.5 {
                .withClickEvent(new ClickEvent.RunCommand(SHARE_COMMAND + choice.word()))
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable(key + ".hover"))));
                //?} else {
                /*.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, SHARE_COMMAND + choice.word()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(key + ".hover"))));
                *///?}
    }

    private static int list(CommandSourceStack source, MineqttRuntime runtime) {
        List<PlayerEntry> entries = List.copyOf(runtime.registry().players());
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No player has a stored privacy choice"), false);
            return 0;
        }
        PlayerPrivacy privacy = runtime.privacy();
        StringBuilder text = new StringBuilder(entries.size() + (entries.size() == 1 ? " player:" : " players:"));
        for (PlayerEntry entry : entries) {
            Consent effective = privacy.effective(entry.uuid());
            text.append("\n").append(entry.describe()).append(" - ").append(entry.consent().key());
            if (effective != entry.consent()) text.append(" (").append(effective.key()).append(" as the host)");
            text.append(entry.asked() ? ", asked" : ", not asked");
            if (runtime.server().getPlayerList().getPlayer(entry.uuid()) != null) text.append(", online");
            entry.slug().ifPresent(slug -> text.append(", device ").append(runtime.topics().player(slug)));
            if (entry.lastSeenDay() > 0) text.append(", last seen ").append(LocalDate.ofEpochDay(entry.lastSeenDay()));
        }
        source.sendSuccess(() -> Component.literal(text.toString()), false);
        return entries.size();
    }

    private static int forget(CommandSourceStack source, MineqttRuntime runtime, String who) {
        Optional<UUID> player = resolve(runtime, who);
        Optional<PlayerPrivacy.Change> change = player.flatMap(runtime.privacy()::forget);
        if (change.isEmpty()) {
            source.sendFailure(Component.literal("No stored player " + who));
            return 0;
        }
        PlayerEntry before = change.get().before();
        source.sendSuccess(() -> Component.literal("Forgot " + before.describe() + ": name, presence device and last seen day removed; their choice ("
                + before.consent().key() + ") is kept"), true);
        return 1;
    }

    // A UUID, a name an entry keeps (any case) or a player online.
    private static Optional<UUID> resolve(MineqttRuntime runtime, String who) {
        try {
            return Optional.of(UUID.fromString(who));
        } catch (IllegalArgumentException notUuid) {
            Optional<UUID> stored = runtime.registry().players().stream().filter(entry -> entry.name().equalsIgnoreCase(who)).map(PlayerEntry::uuid).findFirst();
            if (stored.isPresent()) return stored;
            return Optional.ofNullable(runtime.server().getPlayerList().getPlayerByName(who)).map(ServerPlayer::getUUID);
        }
    }

    private static Optional<MineqttRuntime> running(CommandSourceStack source) {
        return MineqttRuntime.current().filter(runtime -> runtime.server() == source.getServer());
    }

    private static int withRuntime(CommandContext<CommandSourceStack> context, Function<MineqttRuntime, Integer> action) {
        Optional<MineqttRuntime> runtime = running(context.getSource());
        if (runtime.isEmpty()) {
            context.getSource().sendFailure(Component.literal("MineQTT is not running on this server"));
            return 0;
        }
        try {
            return action.apply(runtime.get());
        } finally {
            // A command run while the server is paused (RCON) may leave work for the next tick.
            runtime.get().workQueued();
        }
    }

    private static int asPlayer(CommandContext<CommandSourceStack> context, PlayerAction action) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("Only players have privacy settings; operators see every player's with /mineqtt players list"));
            return 0;
        }
        return withRuntime(context, runtime -> action.run(runtime, player));
    }

    @FunctionalInterface
    private interface PlayerAction {
        int run(MineqttRuntime runtime, ServerPlayer player);
    }
}

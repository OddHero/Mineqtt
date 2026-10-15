package art.rehra.mineqtt.presence;

import art.rehra.mineqtt.compat.Profiles;
import art.rehra.mineqtt.config.HaExpose;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.DeviceRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * The players' privacy choices on one server (M4 plan D9): who consents, the first-join prompt, and the choice a player
 * makes with {@code /mineqtt privacy share} (or, later, a GUI). The choices live in the device registry's
 * {@code players} list ({@link PlayerEntry}); what they allow is {@link PrivacyPolicy}'s. Server thread only.
 */
public final class PlayerPrivacy {
    private final MinecraftServer server;
    private final DeviceRegistry registry;
    private final BooleanSupplier mqttConfigured;
    private final LongSupplier today;
    private Listener listener = Listener.NONE;

    /** What a player's choice changed. */
    public record Change(PlayerEntry before, PlayerEntry after) {
    }

    /** Hears every stored change, after it is stored: the presence devices follow it. */
    public interface Listener {
        Listener NONE = new Listener() {
            @Override
            public void chosen(Change change) {
            }

            @Override
            public void forgotten(Change change) {
            }
        };

        /** A player's choice ({@link #choose}). */
        void chosen(Change change);

        /** An operator forgot a player ({@link #forget}). */
        void forgotten(Change change);
    }

    /** The one listener (the presence devices). */
    public void listen(Listener listener) {
        this.listener = listener;
    }

    /**
     * @param mqttConfigured whether a broker is configured, so anything leaves the server at all
     * @param today          the UTC day ({@code LocalDate.toEpochDay}), for {@link PlayerEntry#lastSeenDay}
     */
    public PlayerPrivacy(MinecraftServer server, DeviceRegistry registry, BooleanSupplier mqttConfigured, LongSupplier today) {
        this.server = server;
        this.registry = registry;
        this.mqttConfigured = mqttConfigured;
        this.today = today;
    }

    /** The registry's entry of a player, or a fresh one (consent unset, not asked) that is not stored yet. */
    public PlayerEntry entry(UUID player) {
        return registry.player(player).orElseGet(() -> PlayerEntry.of(player));
    }

    /**
     * Whether the player owns this integrated server ({@code isSingleplayerOwner}): asked with the profile of the player
     * online, else with the name their entry keeps. A dedicated server has no owner.
     */
    public boolean host(UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) return Profiles.isSingleplayerOwner(server, Profiles.of(online));
        String name = entry(player).name();
        return !name.isEmpty() && Profiles.isSingleplayerOwner(server, new NameAndId(player, name));
    }

    /** The consent that counts for a player ({@link Consent#effective}). */
    public Consent effective(UUID player) {
        return entry(player).consent().effective(host(player));
    }

    /** Whether the player consents to being named; the name policy narrows it with the server's settings. */
    public boolean consents(UUID player) {
        return effective(player).consents();
    }

    /** The policy of the loaded config. */
    public PrivacyPolicy policy() {
        return PrivacyPolicy.current();
    }

    /** Whether MQTT is configured, so the bridge publishes anything. */
    public boolean mqttConfigured() {
        return mqttConfigured.getAsBoolean();
    }

    /**
     * A player joined (before their join event is published): a consenting player's name and day are kept, and the
     * prompt is shown once, when the server could share anything about them ({@link PrivacyPolicy#prompt}).
     *
     * @return what the player is shown
     */
    public PrivacyPolicy.Prompt joined(ServerPlayer player) {
        boolean bridgeShares = mqttConfigured() && MineQTTConfig.haExpose(server.isDedicatedServer()) != HaExpose.NONE;
        Optional<PlayerEntry> stored = registry.player(player.getUUID());
        Joined joined = joined(stored.orElseGet(() -> PlayerEntry.of(player.getUUID())), host(player.getUUID()), player.getGameProfile().name(),
                today.getAsLong(), policy(), bridgeShares);
        // A player who never consented and is not asked leaves nothing behind.
        if (stored.isPresent() || joined.entry().asked() || joined.entry().personal()) registry.putPlayer(joined.entry());
        return joined.prompt();
    }

    /** A joining player's entry and what they are shown. */
    public record Joined(PlayerEntry entry, PrivacyPolicy.Prompt prompt) {
    }

    /**
     * What a join makes of a player's entry (pure): a consenting player's name and day are kept, and a prompt that is
     * shown marks the entry as asked.
     */
    public static Joined joined(PlayerEntry entry, boolean host, String name, long day, PrivacyPolicy policy, boolean bridgeShares) {
        if (entry.consent().effective(host).consents()) entry = entry.seen(name, day);
        PrivacyPolicy.Prompt prompt = policy.prompt(entry, host, bridgeShares);
        return new Joined(prompt == PrivacyPolicy.Prompt.NONE ? entry : entry.withAsked(), prompt);
    }

    /**
     * A player's choice, from {@code /mineqtt privacy share on|full|off} and the GUI alike: it is stored (also beyond what
     * the server shares now, so a wider server mode later honours it), the prompt counts as answered, and a choice that
     * no longer consents removes the personal part. A consenting player online keeps their name and day.
     */
    public Change choose(ServerPlayer player, Consent consent) {
        PlayerEntry before = entry(player.getUUID());
        PlayerEntry after = chosen(before, consent, host(player.getUUID()), player.getGameProfile().name(), today.getAsLong());
        registry.putPlayer(after);
        Change change = new Change(before, after);
        listener.chosen(change);
        return change;
    }

    /**
     * What a choice makes of a player's entry (pure): the consent stored and the prompt answered; a consenting player keeps
     * their name and day, any other choice removes the personal part.
     */
    public static PlayerEntry chosen(PlayerEntry before, Consent consent, boolean host, String name, long day) {
        PlayerEntry after = before.withConsent(consent).withAsked();
        return consent.effective(host).consents() ? after.seen(name, day) : after.forgotten();
    }

    /**
     * {@code /mineqtt players forget}: the personal part of an entry goes (name, presence device slug, last seen day),
     * the player's choice stays.
     *
     * @return the change, or empty for a player without an entry
     */
    public Optional<Change> forget(UUID player) {
        Optional<PlayerEntry> before = registry.player(player);
        if (before.isEmpty()) return Optional.empty();
        PlayerEntry after = before.get().forgotten();
        registry.putPlayer(after);
        Change change = new Change(before.get(), after);
        listener.forgotten(change);
        return Optional.of(change);
    }

    /** The UTC day now. */
    public long today() {
        return today.getAsLong();
    }
}

package art.rehra.mineqtt.presence;

import art.rehra.mineqtt.device.Slugs;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.Optional;
import java.util.UUID;

/**
 * One player as the device registry keeps them (M4 plan D9, D10), in its {@code players} list: their consent, whether
 * the first-join prompt was shown, and, only while they consent, their name, the slug of their presence device and the
 * day they were last seen. Withdrawing consent, {@code /mineqtt players forget} and {@code telemetry.forgetAfterDays}
 * remove the personal part ({@link #forgotten()}) and keep the decision.
 *
 * @param name           the player's name while they consent, at most {@value #MAX_NAME_LENGTH} characters; empty
 *                       otherwise
 * @param slug           the topic level of their presence device under {@code player/}, while they have one; it is
 *                       chosen once and kept when the player renames, so only Home Assistant's name follows
 * @param lastSeenDay    the UTC day ({@code LocalDate.toEpochDay}) the player was last online while consenting; 0 for
 *                       none
 * @param fullDiscovered whether the presence device was last discovered with the sensors of full sharing, so a narrowing
 *                       must remove them in Home Assistant, also after a restart
 */
public record PlayerEntry(UUID uuid, Consent consent, boolean asked, String name, Optional<String> slug, long lastSeenDay,
                          boolean fullDiscovered) {
    /** Vanilla player names have at most 16 characters. */
    public static final int MAX_NAME_LENGTH = 16;

    public static final Codec<PlayerEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("uuid").forGetter(PlayerEntry::uuid),
            Consent.CODEC.optionalFieldOf("consent", Consent.UNSET).forGetter(PlayerEntry::consent),
            Codec.BOOL.optionalFieldOf("asked", false).forGetter(PlayerEntry::asked),
            Codec.STRING.optionalFieldOf("name", "").forGetter(PlayerEntry::name),
            Codec.STRING.optionalFieldOf("slug").forGetter(PlayerEntry::slug),
            Codec.LONG.optionalFieldOf("lastSeenDay", 0L).forGetter(PlayerEntry::lastSeenDay),
            Codec.BOOL.optionalFieldOf("fullDiscovered", false).forGetter(PlayerEntry::fullDiscovered)
    ).apply(instance, PlayerEntry::new));

    public PlayerEntry {
        if (uuid == null || consent == null) throw new IllegalArgumentException("A player entry needs a UUID and a consent");
        name = name == null ? "" : cutName(name);
        // A slug read from a damaged file that is no topic level is dropped; the device gets a new one.
        slug = slug == null ? Optional.empty() : slug.filter(Slugs::isValid);
        if (lastSeenDay < 0) lastSeenDay = 0;
        // Only a device has sensors to remove.
        if (slug.isEmpty()) fullDiscovered = false;
    }

    /** An entry whose device (if any) was discovered without the sensors of full sharing. */
    public PlayerEntry(UUID uuid, Consent consent, boolean asked, String name, Optional<String> slug, long lastSeenDay) {
        this(uuid, consent, asked, name, slug, lastSeenDay, false);
    }

    /** A player the registry knows nothing about yet: consent unset, not asked. */
    public static PlayerEntry of(UUID uuid) {
        return new PlayerEntry(uuid, Consent.UNSET, false, "", Optional.empty(), 0);
    }

    public PlayerEntry withConsent(Consent consent) {
        return new PlayerEntry(uuid, consent, asked, name, slug, lastSeenDay, fullDiscovered);
    }

    public PlayerEntry withAsked() {
        return new PlayerEntry(uuid, consent, true, name, slug, lastSeenDay, fullDiscovered);
    }

    /** The player seen online, consenting: their current name and today's day. */
    public PlayerEntry seen(String name, long day) {
        return new PlayerEntry(uuid, consent, asked, name, slug, day, fullDiscovered);
    }

    public PlayerEntry withLastSeenDay(long day) {
        return new PlayerEntry(uuid, consent, asked, name, slug, day, fullDiscovered);
    }

    /** With a presence device of this slug, or without one (which also has no sensors left to remove). */
    public PlayerEntry withSlug(Optional<String> slug) {
        return new PlayerEntry(uuid, consent, asked, name, slug, lastSeenDay, fullDiscovered);
    }

    public PlayerEntry withFullDiscovered(boolean fullDiscovered) {
        return new PlayerEntry(uuid, consent, asked, name, slug, lastSeenDay, fullDiscovered);
    }

    /** Without the personal part (name, slug, last seen day); the consent and whether they were asked stay. */
    public PlayerEntry forgotten() {
        return new PlayerEntry(uuid, consent, asked, "", Optional.empty(), 0);
    }

    /** Whether the entry holds anything personal: a name, a presence device slug or a day. */
    public boolean personal() {
        return !name.isEmpty() || slug.isPresent() || lastSeenDay != 0;
    }

    /** The name, or the UUID for an entry that keeps none. */
    public String describe() {
        return name.isEmpty() ? uuid.toString() : name;
    }

    // Control characters never come from a valid profile; cut to the vanilla length without splitting a surrogate pair.
    private static String cutName(String name) {
        String clean = name.replaceAll("\\p{Cntrl}", "");
        if (clean.codePointCount(0, clean.length()) <= MAX_NAME_LENGTH) return clean;
        return clean.substring(0, clean.offsetByCodePoints(0, MAX_NAME_LENGTH));
    }
}

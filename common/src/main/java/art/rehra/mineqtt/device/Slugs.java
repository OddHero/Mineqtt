package art.rehra.mineqtt.device;

import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Topic-safe names derived from free text, such as world and device names. */
public final class Slugs {
    /** The longest slug: {@code [a-z0-9_-]{1,48}}. */
    public static final int MAX_LENGTH = 48;
    /**
     * Slugs no device or group gets. Topic levels under {@code <base>/<bridgeId>} that are not devices: the bridge's
     * own topics, groups, the raw subtree players may publish into and the presence devices of players. And
     * {@code set}: a group {@code set} would make {@code <root>/group/set} a device command topic and
     * {@code <root>/group/set/set} match two command filters.
     */
    public static final Set<String> RESERVED = Set.of("bridge", "group", "raw", "player", "set");

    private static final Pattern VALID = Pattern.compile("[a-z0-9_-]{1," + MAX_LENGTH + "}");

    private Slugs() {
    }

    /**
     * Lowercase ASCII letters, digits, {@code _} and {@code -}: accents are dropped, any other run of characters
     * becomes one {@code _}, separators at either end are trimmed and the result is cut to {@link #MAX_LENGTH}.
     *
     * @return the slug, or an empty string when the text has nothing usable (e.g. only CJK characters)
     */
    public static String fromName(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        String slug = trimSeparators(ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_"));
        return cut(slug, MAX_LENGTH);
    }

    public static boolean isValid(String slug) {
        return slug != null && VALID.matcher(slug).matches();
    }

    /** A valid slug that is not {@link #RESERVED}, as the slug of every device and group is. */
    public static boolean isDeviceSlug(String slug) {
        return isValid(slug) && !RESERVED.contains(slug);
    }

    /** The default slug of a new device: {@code <type path>_<first 4 hex of its id>}, e.g. {@code smart_lamp_3f9c}. */
    public static String defaultSlug(String typePath, UUID id) {
        String hex = shortHex(id);
        return cut(fromName(typePath), MAX_LENGTH - hex.length() - 1) + "_" + hex;
    }

    /** The default name of a new device: {@code <model> <first 4 hex>}, e.g. {@code Smart Lamp 3F9C}. */
    public static String defaultName(String model, UUID id) {
        return model + " " + shortHex(id).toUpperCase(Locale.ROOT);
    }

    /**
     * {@code wanted} when it is free, else the first free of {@code wanted_2}, {@code wanted_3}, … (cut so the suffix
     * fits). {@code wanted} is made topic-safe first ({@link #fromName}); an empty result becomes {@code device} and a
     * reserved one is suffixed, so the result is always a device slug.
     *
     * @param taken whether a slug is already in use
     */
    public static String unique(String wanted, Predicate<String> taken) {
        String safe = fromName(wanted);
        String base = safe.isEmpty() ? "device" : safe;
        if (isDeviceSlug(base) && !taken.test(base)) return base;
        for (int n = 2; ; n++) {
            String suffix = "_" + n;
            String candidate = cut(base, MAX_LENGTH - suffix.length()) + suffix;
            if (!taken.test(candidate)) return candidate;
        }
    }

    private static String shortHex(UUID id) {
        return HexFormat.of().toHexDigits((short) (id.getMostSignificantBits() >>> 48));
    }

    private static String cut(String slug, int length) {
        return slug.length() <= length ? slug : trimSeparators(slug.substring(0, length));
    }

    private static String trimSeparators(String slug) {
        return slug.replaceAll("^[_-]+|[_-]+$", "");
    }
}

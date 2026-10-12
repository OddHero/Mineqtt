package art.rehra.mineqtt.twin.core;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Home Assistant's area names as the rooms meet them (M4 plan D11), taken from the catalog ({@link CatalogEntry#area()}).
 * Home Assistant finds an area by its name with case and spaces ignored, and creates one under the name given when none
 * matches, so a room typed {@code living room} names the area {@code Living Room} if the catalog knows it: the rooms then
 * suggest Home Assistant's own spelling. Minecraft-free.
 */
public final class AreaNames {
    /** The most area names a room prompt suggests. */
    public static final int MAX_SUGGESTIONS = 16;

    private AreaNames() {
    }

    /** A name as Home Assistant compares area names: case folded, without spaces. */
    public static String key(String name) {
        return name.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT).replace(" ", "");
    }

    /**
     * The distinct area names among {@code areas}, each once by {@link #key} (the first spelling met), blank ones left out,
     * stripped, sorted ignoring case.
     */
    public static List<String> distinct(Collection<String> areas) {
        Map<String, String> byKey = new TreeMap<>();
        for (String area : areas) {
            String name = area.strip();
            if (!name.isEmpty()) byKey.putIfAbsent(key(name), name);
        }
        return byKey.values().stream().sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(String::compareTo)).toList();
    }

    /** Home Assistant's spelling of the area {@code name} matches among {@code areas}, or empty when none does. */
    public static Optional<String> spelling(String name, Collection<String> areas) {
        String key = key(name.strip());
        if (key.isEmpty()) return Optional.empty();
        return areas.stream().map(String::strip).filter(area -> key(area).equals(key)).findFirst();
    }
}

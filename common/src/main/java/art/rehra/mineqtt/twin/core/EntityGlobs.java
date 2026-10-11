package art.rehra.mineqtt.twin.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code twin.allowedEntities} (M3 plan D13): the entity ids the twin may read or bind at all, as globs over the whole
 * entity id. {@code *} matches any run of characters (dots included), {@code ?} exactly one; everything else is
 * literal. A glob uses only what an entity id can contain plus those two wildcards, so a typo cannot silently allow
 * more than intended. An empty list allows nothing and turns the twin off; {@code ["*"]} allows every entity.
 * <p>
 * Examples: {@code light.*}, {@code *.kitchen_*}, {@code sensor.outdoor_temperature}, {@code switch.plug_?}.
 */
public final class EntityGlobs {
    /** The twin is off. */
    public static final EntityGlobs NONE = new EntityGlobs(List.of());
    /** Every entity. */
    public static final EntityGlobs ALL = new EntityGlobs(List.of("*"));
    /** Longer than any entity id Home Assistant allows (255). */
    public static final int MAX_LENGTH = 255;

    private final List<String> patterns;

    private EntityGlobs(List<String> patterns) {
        this.patterns = List.copyOf(patterns);
    }

    /**
     * Compiles a configured list; surrounding whitespace is dropped.
     *
     * @throws IllegalArgumentException naming the first invalid glob and why
     */
    public static EntityGlobs of(List<String> globs) {
        Objects.requireNonNull(globs, "globs");
        List<String> patterns = new ArrayList<>(globs.size());
        for (String glob : globs) {
            String pattern = glob == null ? null : glob.strip();
            Optional<String> problem = problem(pattern);
            if (problem.isPresent()) throw new IllegalArgumentException("Invalid allowed entity \"" + glob + "\": " + problem.get());
            patterns.add(pattern);
        }
        return patterns.isEmpty() ? NONE : new EntityGlobs(patterns);
    }

    /** Why {@code glob} is not a valid allowed-entity glob, or empty when it is. */
    public static Optional<String> problem(String glob) {
        if (glob == null || glob.isEmpty()) return Optional.of("it is empty");
        if (glob.length() > MAX_LENGTH) return Optional.of("it is longer than " + MAX_LENGTH + " characters");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '*' || c == '?';
            if (!valid) {
                return Optional.of(c >= 'A' && c <= 'Z' ? "entity ids are lowercase" : "'" + c + "' is not allowed; use lowercase letters, digits, _, ., * and ?");
            }
        }
        return Optional.empty();
    }

    /** Whether any glob matches the whole of {@code entityId}. */
    public boolean matches(String entityId) {
        if (entityId == null) return false;
        for (String pattern : patterns) {
            if (glob(pattern, entityId)) return true;
        }
        return false;
    }

    /** Whether nothing is allowed: the twin is off. */
    public boolean isEmpty() {
        return patterns.isEmpty();
    }

    /** The globs as configured (stripped). */
    public List<String> patterns() {
        return patterns;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EntityGlobs other && patterns.equals(other.patterns);
    }

    @Override
    public int hashCode() {
        return patterns.hashCode();
    }

    @Override
    public String toString() {
        return patterns.toString();
    }

    // Wildcard matching without recursion or backtracking blow-up (at most pattern × text steps): on a mismatch, the
    // last '*' takes one more character.
    private static boolean glob(String pattern, String text) {
        int p = 0, t = 0, star = -1, resume = 0;
        while (t < text.length()) {
            if (p < pattern.length() && (pattern.charAt(p) == '?' || pattern.charAt(p) == text.charAt(t))) {
                p++;
                t++;
            } else if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;
                resume = t;
            } else if (star >= 0) {
                p = star + 1;
                t = ++resume;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') p++;
        return p == pattern.length();
    }
}

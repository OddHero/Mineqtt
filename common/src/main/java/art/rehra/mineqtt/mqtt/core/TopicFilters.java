package art.rehra.mineqtt.mqtt.core;

import java.nio.charset.StandardCharsets;

/**
 * Topic name and topic filter rules shared by the router, the loopback and the ACL (MQTT 3.1.1 and 5, §4.7).
 * <p>
 * Levels are separated by {@code /} and may be empty ({@code a//b}, {@code /a}). {@code +} must fill a whole level,
 * {@code #} must fill the last level, and a filter whose first level is a wildcard never matches a topic starting
 * with {@code $}.
 */
public final class TopicFilters {
    /** The encoded length limit of every MQTT UTF-8 string. */
    public static final int MAX_LENGTH_BYTES = 65_535;

    private TopicFilters() {
    }

    /** A topic name as it may appear in a PUBLISH: non-empty, no wildcards, no NUL, within the length limit. */
    public static boolean isValidTopic(String topic) {
        return isValidString(topic) && !hasWildcard(topic);
    }

    /** A topic filter as it may appear in a SUBSCRIBE. */
    public static boolean isValidFilter(String filter) {
        if (!isValidString(filter)) return false;
        String[] levels = levels(filter);
        for (int i = 0; i < levels.length; i++) {
            String level = levels[i];
            if (level.equals("#")) {
                if (i != levels.length - 1) return false;
            } else if (!level.equals("+") && (level.indexOf('+') >= 0 || level.indexOf('#') >= 0)) {
                return false;
            }
        }
        return true;
    }

    public static boolean hasWildcard(String filter) {
        return filter.indexOf('+') >= 0 || filter.indexOf('#') >= 0;
    }

    /** Whether {@code filter} matches the topic name {@code topic}. Both must be valid. */
    public static boolean matches(String filter, String topic) {
        String[] filterLevels = levels(filter);
        String[] topicLevels = levels(topic);
        if (topic.startsWith("$") && isWildcard(filterLevels[0])) return false;
        for (int i = 0; i < filterLevels.length; i++) {
            String level = filterLevels[i];
            // "a/#" also matches its parent "a".
            if (level.equals("#")) return true;
            if (i >= topicLevels.length) return false;
            if (!level.equals("+") && !level.equals(topicLevels[i])) return false;
        }
        return filterLevels.length == topicLevels.length;
    }

    /**
     * Whether every topic matched by {@code inner} is also matched by {@code outer}. Both must be valid filters; a
     * plain topic name is a filter that matches only itself.
     */
    public static boolean covers(String outer, String inner) {
        String[] outerLevels = levels(outer);
        String[] innerLevels = levels(inner);
        // A root wildcard in outer cannot reach $-topics, which inner may name or (via its own root wildcard) not.
        if (isWildcard(outerLevels[0]) && innerLevels[0].startsWith("$")) return false;
        for (int i = 0; i < outerLevels.length; i++) {
            String level = outerLevels[i];
            if (level.equals("#")) return true;
            if (i >= innerLevels.length) return false;
            String innerLevel = innerLevels[i];
            if (innerLevel.equals("#")) return false;
            if (level.equals("+")) continue;
            if (innerLevel.equals("+") || !level.equals(innerLevel)) return false;
        }
        return outerLevels.length == innerLevels.length;
    }

    /** Splits into levels, keeping empty ones. */
    static String[] levels(String topicOrFilter) {
        return topicOrFilter.split("/", -1);
    }

    static boolean isWildcard(String level) {
        return level.equals("+") || level.equals("#");
    }

    private static boolean isValidString(String value) {
        if (value == null || value.isEmpty() || value.indexOf('\0') >= 0) return false;
        // Cheap bound first: each char encodes to at most three bytes.
        return value.length() * 3 <= MAX_LENGTH_BYTES || value.getBytes(StandardCharsets.UTF_8).length <= MAX_LENGTH_BYTES;
    }
}

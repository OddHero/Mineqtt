package art.rehra.mineqtt.twin.core;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Ranks the entities of the catalog for a search (M3 plan D4), pure: {@code /mineqtt twin entities} and the Twin tab's
 * picker use the same order.
 * <ol>
 *     <li>the domains the device can use, in its order of preference (any domain when none are given);</li>
 *     <li>how well the query matches the name, entity id or area: a prefix, then the start of a word, then anywhere
 *     (with a query, rows that match nowhere are left out);</li>
 *     <li>entities the searching player bound recently;</li>
 *     <li>entities Home Assistant reports unavailable or unknown last;</li>
 *     <li>then by name and entity id.</li>
 * </ol>
 */
public final class EntitySearch {
    /** The longest query searched; a longer one is cut (D4: the picker sends at most 64 characters). */
    public static final int MAX_QUERY_LENGTH = 64;

    /**
     * One entity that may be shown.
     *
     * @param area    the area name, {@code ""} for none
     * @param control whether Home Assistant's labels let Minecraft command it (catalog only; false without one)
     */
    public record Candidate(String entityId, String name, String area, StatestreamValue state, boolean control) {
        public Candidate {
            if (!StatestreamTopic.isEntityId(entityId)) throw new IllegalArgumentException("Not an entity id: " + entityId);
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(area, "area");
            Objects.requireNonNull(state, "state");
        }

        public String domain() {
            return entityId.substring(0, entityId.indexOf('.'));
        }
    }

    private EntitySearch() {
    }

    /**
     * Every entity a listing may name, the read gate not yet applied (D3, D4): the catalog's entries while it decides,
     * with the newest state the documents know; otherwise what the fallback scan saw and the documents of the entities
     * mirrored devices hold.
     *
     * @param held the entities loaded devices mirror or switch now
     */
    public static Map<String, Candidate> candidates(EntityDocuments documents, EntityScan scan, Collection<String> held) {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        if (documents.catalogMode()) {
            documents.catalog().values().forEach(entries -> entries.values().forEach(entry -> {
                StatestreamValue state = documents.document(entry.entityId()).map(EntityDocument::state).orElse(entry.state());
                candidates.put(entry.entityId(), new Candidate(entry.entityId(), entry.name(), entry.area(), state, entry.control()));
            }));
        } else {
            scan.rows().values().forEach(row -> candidates.put(row.entityId(), new Candidate(row.entityId(), row.name(), "",
                    documents.document(row.entityId()).map(EntityDocument::state).orElse(row.state()), false)));
            for (String id : held) {
                documents.document(id).ifPresent(document -> candidates.putIfAbsent(id, new Candidate(id,
                        document.value("friendly_name").filter(JsonElement::isJsonPrimitive).map(JsonElement::getAsString).orElse(id), "",
                        document.state(), false)));
            }
        }
        return candidates;
    }

    /**
     * The candidates the read gate lets through (D3, D13): readable domains only (never a private one), in
     * {@code twin.allowedEntities}, and in the catalog while it decides. Players see nothing else of Home Assistant.
     */
    public static List<Candidate> readable(Collection<Candidate> candidates, EntityDocuments documents, EntityGlobs allowed) {
        return candidates.stream().filter(candidate -> HaDomains.readable(candidate.domain()) && documents.readable(candidate.entityId(), allowed)).toList();
    }

    /**
     * The candidates that match {@code query}, best first, at most {@code limit}.
     *
     * @param domains the domains the device can use, most preferred first; empty for any domain
     * @param recent  entity ids the player bound recently
     */
    public static List<Candidate> search(List<Candidate> candidates, String query, List<String> domains, Set<String> recent, int limit) {
        if (limit <= 0) return List.of();
        String needle = normalize(query);
        List<Ranked> ranked = new ArrayList<>();
        for (Candidate candidate : candidates) {
            int domain = domains.isEmpty() ? 0 : domains.indexOf(candidate.domain());
            if (domain < 0) continue;
            int match = needle.isEmpty() ? 0 : Math.min(match(candidate.name(), needle), Math.min(match(candidate.entityId(), needle), match(candidate.area(), needle)));
            if (match == NO_MATCH) continue;
            ranked.add(new Ranked(candidate, domain, match, recent.contains(candidate.entityId()) ? 0 : 1, candidate.state().known() ? 0 : 1));
        }
        ranked.sort(Comparator.comparingInt(Ranked::domain).thenComparingInt(Ranked::match).thenComparingInt(Ranked::recent)
                .thenComparingInt(Ranked::unavailable).thenComparing(r -> r.candidate().name().toLowerCase(Locale.ROOT))
                .thenComparing(r -> r.candidate().entityId()));
        return ranked.stream().limit(limit).map(Ranked::candidate).toList();
    }

    private static final int NO_MATCH = 3;

    private record Ranked(Candidate candidate, int domain, int match, int recent, int unavailable) {
    }

    // 0: a prefix, 1: the start of a word (after a space, '_', '.', '-' or '/'), 2: anywhere, NO_MATCH: nowhere.
    private static int match(String text, String needle) {
        String haystack = text.toLowerCase(Locale.ROOT);
        int at = haystack.indexOf(needle);
        if (at < 0) return NO_MATCH;
        if (at == 0) return 0;
        for (; at > 0; at = haystack.indexOf(needle, at + 1)) {
            if (" _.-/".indexOf(haystack.charAt(at - 1)) >= 0) return 1;
        }
        return 2;
    }

    private static String normalize(String query) {
        String trimmed = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return trimmed.length() > MAX_QUERY_LENGTH ? trimmed.substring(0, MAX_QUERY_LENGTH) : trimmed;
    }
}

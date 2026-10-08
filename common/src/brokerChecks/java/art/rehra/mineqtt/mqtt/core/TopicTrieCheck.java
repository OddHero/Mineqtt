package art.rehra.mineqtt.mqtt.core;

import java.util.List;

/** Spec matching of {@link TopicTrie} and the filter algebra of {@link TopicFilters} that the ACL builds on. */
public final class TopicTrieCheck {
    static void main(String[] args) {
        singleLevelWildcard();
        multiLevelWildcard();
        dollarTopics();
        emptyLevels();
        overlappingFilters();
        unsubscribe();
        invalidInput();
        trieAgreesWithMatches();
        covers();
        System.out.println("Topic trie checks passed.");
    }

    private static void singleLevelWildcard() {
        TopicTrie<String> trie = new TopicTrie<>();
        trie.add("a/+/c", "plus");
        expect(trie.match("a/b/c"), List.of("plus"), "+ matches one level");
        expect(trie.match("a/b/c/d"), List.of(), "+ does not match two levels");
        expect(trie.match("a/c"), List.of(), "+ does not match zero levels");
        trie.add("+", "root-plus");
        expect(trie.match("a"), List.of("root-plus"), "root + matches a single-level topic");
        expect(trie.match("a/b"), List.of(), "root + does not match a two-level topic");
    }

    private static void multiLevelWildcard() {
        TopicTrie<String> trie = new TopicTrie<>();
        trie.add("a/#", "hash");
        expect(trie.match("a/b"), List.of("hash"), "a/# matches a child");
        expect(trie.match("a/b/c/d"), List.of("hash"), "a/# matches a deep descendant");
        expect(trie.match("a"), List.of("hash"), "a/# matches its parent a");
        expect(trie.match("b"), List.of(), "a/# does not match a sibling");
        trie.add("#", "all");
        expect(trie.match("x/y"), List.of("all"), "# matches everything");
        trie.add("a/+/#", "plus-hash");
        expect(trie.match("a/b"), List.of("plus-hash", "hash", "all"), "a/+/# matches a/b through its parent rule");
    }

    private static void dollarTopics() {
        TopicTrie<String> trie = new TopicTrie<>();
        trie.add("#", "all");
        trie.add("+/broker", "plus");
        trie.add("$SYS/#", "sys");
        trie.add("$SYS/+", "sys-plus");
        expect(trie.match("$SYS/broker"), List.of("sys-plus", "sys"), "root wildcards never match $-topics");
        expect(trie.match("x/broker"), List.of("plus", "all"), "the same filters match a normal topic");
        check(!TopicFilters.matches("#", "$SYS/x"), "TopicFilters.matches: # must not match $SYS/x");
        check(TopicFilters.matches("$SYS/#", "$SYS"), "TopicFilters.matches: $SYS/# matches $SYS");
    }

    private static void emptyLevels() {
        TopicTrie<String> trie = new TopicTrie<>();
        trie.add("a/+/b", "middle");
        trie.add("+/a", "leading");
        trie.add("a/+", "trailing");
        expect(trie.match("a//b"), List.of("middle"), "+ matches an empty level");
        expect(trie.match("/a"), List.of("leading"), "a leading empty level is a level");
        expect(trie.match("a/"), List.of("trailing"), "a trailing empty level is a level");
        expect(trie.match("a"), List.of(), "a/+ does not match a");
    }

    private static void overlappingFilters() {
        TopicTrie<String> trie = new TopicTrie<>();
        for (String filter : List.of("a/b", "a/+", "a/#", "#", "+/+", "+/#", "a/b/#")) {
            trie.add(filter, filter);
        }
        List<String> matched = trie.match("a/b");
        check(matched.size() == 7, "Every overlapping filter delivers once, got " + matched);
        check(matched.containsAll(List.of("a/b", "a/+", "a/#", "#", "+/+", "+/#", "a/b/#")), "Overlap set incomplete: " + matched);
        trie.add("a/b", "a/b");
        check(trie.match("a/b").size() == 8, "A second registration under the same filter is a second delivery");
    }

    private static void unsubscribe() {
        TopicTrie<String> trie = new TopicTrie<>();
        trie.add("s/+/x", "one");
        trie.add("s/+/x", "two");
        trie.add("s/#", "deep");
        check(trie.size() == 3, "Size counts registrations");
        check(trie.remove("s/+/x", "one"), "Removing an existing registration succeeds");
        expect(trie.match("s/k/x"), List.of("two", "deep"), "The other handler on the filter keeps receiving");
        check(!trie.remove("s/+/x", "one"), "Removing twice fails the second time");
        check(!trie.remove("s/+/y", "two"), "Removing under the wrong filter fails");
        check(trie.remove("s/+/x", "two"), "Removing the last handler succeeds");
        expect(trie.match("s/k/x"), List.of("deep"), "No handler is left on s/+/x");
        expect(trie.get("s/+/x"), List.of(), "The pruned filter has no handlers");
        check(trie.remove("s/#", "deep") && trie.isEmpty(), "The trie is empty after removing everything");
        expect(trie.match("s/k/x"), List.of(), "An empty trie matches nothing");
    }

    private static void invalidInput() {
        TopicTrie<String> trie = new TopicTrie<>();
        for (String invalid : new String[]{"", "a/#/b", "a+", "#a", "a/b#", "nul\0level"}) {
            try {
                trie.add(invalid, "x");
                throw new AssertionError("Invalid filter accepted: " + invalid);
            } catch (IllegalArgumentException expected) {
                // Rejected as it must be.
            }
        }
        trie.add("#", "all");
        expect(trie.match("a/+"), List.of(), "A topic name with a wildcard matches nothing");
        expect(trie.match(""), List.of(), "An empty topic matches nothing");
        check(!TopicFilters.isValidTopic("a/#") && TopicFilters.isValidTopic("a//b"), "Topic name validation");
        check(TopicFilters.isValidFilter("+/+/#") && !TopicFilters.isValidFilter("a/++"), "Filter validation");
    }

    /** The trie and the reference matcher must agree on every filter/topic pair. */
    private static void trieAgreesWithMatches() {
        List<String> filters = List.of("#", "+", "+/+", "a/#", "a/+", "a/+/c", "+/b/#", "a/b/c", "$SYS/#", "+/#", "/+", "a//#");
        List<String> topics = List.of("a", "a/b", "a/b/c", "a/b/c/d", "b/b", "$SYS/x", "$SYS", "/a", "a//c", "x/b/y/z");
        for (String filter : filters) {
            TopicTrie<String> trie = new TopicTrie<>();
            trie.add(filter, filter);
            for (String topic : topics) {
                boolean viaTrie = !trie.match(topic).isEmpty();
                check(viaTrie == TopicFilters.matches(filter, topic), "Trie and matches() disagree on " + filter + " vs " + topic);
            }
        }
    }

    private static void covers() {
        check(TopicFilters.covers("a/#", "a/b/c"), "a/# covers a/b/c");
        check(TopicFilters.covers("a/#", "a"), "a/# covers its parent a");
        check(TopicFilters.covers("a/#", "a/+/c"), "a/# covers a/+/c");
        check(TopicFilters.covers("a/+/set", "a/x/set"), "a/+/set covers a/x/set");
        check(TopicFilters.covers("a/+/set", "a/+/set"), "a filter covers itself");
        check(!TopicFilters.covers("a/+/set", "a/#"), "a/+/set does not cover a/#");
        check(!TopicFilters.covers("a/b", "a/+"), "a literal does not cover +");
        check(!TopicFilters.covers("a/+", "a/b/c"), "a/+ does not cover a deeper topic");
        check(!TopicFilters.covers("#", "$SYS/x"), "# does not cover a $-topic");
        check(TopicFilters.covers("#", "+/x"), "# covers a root + filter, which cannot reach $-topics either");
        check(!TopicFilters.covers("a/+/#", "a"), "a/+/# does not cover a");
    }

    private static void expect(List<String> actual, List<String> expected, String description) {
        check(actual.equals(expected), description + ": expected " + expected + " but got " + actual);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}

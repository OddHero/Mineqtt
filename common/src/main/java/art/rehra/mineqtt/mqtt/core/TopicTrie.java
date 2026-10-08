package art.rehra.mineqtt.mqtt.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stores values under MQTT topic filters and returns, for a received topic name, the values of every matching
 * filter (spec matching via {@link TopicFilters}: {@code +}, {@code #}, {@code a/#} matching {@code a}, and no root
 * wildcard matching a {@code $}-topic).
 * <p>
 * A value registered under several filters, or twice under one filter, is returned once per registration. Results
 * are ordered by trie position, then registration order. Not thread-safe.
 */
public final class TopicTrie<H> {
    private final Node<H> root = new Node<>();
    private int size;

    public void add(String filter, H value) {
        if (!TopicFilters.isValidFilter(filter)) throw new IllegalArgumentException("Invalid topic filter: " + filter);
        Node<H> node = root;
        for (String level : TopicFilters.levels(filter)) {
            node = node.children.computeIfAbsent(level, key -> new Node<>());
        }
        node.values.add(value);
        size++;
    }

    /** Removes one registration of {@code value} under exactly {@code filter}; returns whether one existed. */
    public boolean remove(String filter, H value) {
        if (!TopicFilters.isValidFilter(filter)) return false;
        String[] levels = TopicFilters.levels(filter);
        List<Node<H>> path = new ArrayList<>(levels.length + 1);
        Node<H> node = root;
        path.add(node);
        for (String level : levels) {
            node = node.children.get(level);
            if (node == null) return false;
            path.add(node);
        }
        if (!node.values.remove(value)) return false;
        size--;
        // Prune nodes left without values or children, deepest first.
        for (int i = levels.length; i > 0 && path.get(i).isEmpty(); i--) {
            path.get(i - 1).children.remove(levels[i - 1]);
        }
        return true;
    }

    /** The values of every filter matching the topic name, or an empty list for an invalid topic. */
    public List<H> match(String topic) {
        List<H> result = new ArrayList<>();
        if (!TopicFilters.isValidTopic(topic)) return result;
        String[] levels = TopicFilters.levels(topic);
        collect(root, levels, 0, levels[0].startsWith("$"), result);
        return result;
    }

    /** The values registered under exactly this filter. */
    public List<H> get(String filter) {
        Node<H> node = root;
        for (String level : TopicFilters.levels(filter)) {
            node = node.children.get(level);
            if (node == null) return List.of();
        }
        return List.copyOf(node.values);
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    private static <H> void collect(Node<H> node, String[] levels, int index, boolean dollarTopic, List<H> result) {
        if (index == levels.length) {
            result.addAll(node.values);
            Node<H> parentMatch = node.children.get("#");
            if (parentMatch != null) result.addAll(parentMatch.values);
            return;
        }
        Node<H> exact = node.children.get(levels[index]);
        if (exact != null) collect(exact, levels, index + 1, dollarTopic, result);
        if (index == 0 && dollarTopic) return;
        Node<H> single = node.children.get("+");
        if (single != null) collect(single, levels, index + 1, dollarTopic, result);
        Node<H> multi = node.children.get("#");
        if (multi != null) result.addAll(multi.values);
    }

    private static final class Node<H> {
        final Map<String, Node<H>> children = new LinkedHashMap<>();
        final List<H> values = new ArrayList<>(1);

        boolean isEmpty() {
            return values.isEmpty() && children.isEmpty();
        }
    }
}

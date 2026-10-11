package art.rehra.mineqtt.twin.core;

import com.google.gson.JsonElement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads a MineQTT blueprint YAML for {@code verifyHaCalls} (M3 plan D15) without a YAML library: the JSON-flow line after
 * a marker comment, and the value of a key inside a block found by its key path. The blueprints keep every value the
 * check compares on one line, as JSON flow (valid YAML), so a reformat by an editor or the blueprint exchange fails the
 * check instead of passing silently.
 */
final class BlueprintFile {
    private final String name;
    private final List<String> lines;

    BlueprintFile(String name, List<String> lines) {
        this.name = name;
        this.lines = List.copyOf(lines);
    }

    static BlueprintFile read(Path file) throws IOException {
        return new BlueprintFile(file.getFileName().toString(), Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    /** The same file with every line containing {@code text} removed, for the negative checks. */
    BlueprintFile without(String text) {
        return new BlueprintFile(name, lines.stream().filter(line -> !line.contains(text)).toList());
    }

    /** The same file with {@code from} replaced by {@code to} in every line (a newline in {@code to} splits it), for the negative checks. */
    BlueprintFile replacing(String from, String to) {
        return new BlueprintFile(name, lines.stream().flatMap(line -> line.replace(from, to).lines()).toList());
    }

    /**
     * The JSON on the line after the comment {@code # <marker>}: that line must be {@code <key>: <json>} on one line.
     *
     * @throws IllegalStateException when the marker is missing or repeated, or the next line is no such key or no JSON
     */
    JsonElement markedJson(String marker, String key) {
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).strip();
            if (trimmed.equals("# " + marker) || trimmed.startsWith("# " + marker + " ")) found.add(i);
        }
        if (found.size() != 1) throw new IllegalStateException(name + ": expected the marker '# " + marker + "' once, found " + found.size());
        int next = found.getFirst() + 1;
        if (next >= lines.size()) throw new IllegalStateException(name + ": nothing follows the marker " + marker);
        String line = lines.get(next).strip();
        if (!line.startsWith(key + ":")) throw new IllegalStateException(name + ": the line after '" + marker + "' is not '" + key + ": …': " + line);
        return json(line.substring(key.length() + 1).strip(), marker);
    }

    /**
     * The raw value of {@code key} inside the block reached by {@code path} (each a key one level deeper), on one line;
     * empty when the path or the key is missing.
     */
    Optional<String> value(String key, String... path) {
        int from = 0;
        int indent = -1;
        for (String step : path) {
            int at = find(step, from, indent);
            if (at < 0) return Optional.empty();
            indent = indentOf(lines.get(at));
            from = at + 1;
        }
        int at = find(key, from, indent);
        if (at < 0) return Optional.empty();
        String line = lines.get(at).strip();
        return Optional.of(line.substring(key.length() + 1).strip());
    }

    /** {@link #value} parsed as JSON (a quoted string or a flow list on one line). */
    Optional<JsonElement> jsonValue(String key, String... path) {
        return value(key, path).map(raw -> json(raw, String.join(".", path) + "." + key));
    }

    /** Whether some line contains {@code text}. */
    boolean contains(String text) {
        return lines.stream().anyMatch(line -> line.contains(text));
    }

    /** How many lines contain {@code text}. */
    long count(String text) {
        return lines.stream().filter(line -> line.contains(text)).count();
    }

    // The first line at or after `from`, deeper than `indent` (and before the block ends), whose key is `key`.
    private int find(String key, int from, int indent) {
        for (int i = from; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || line.strip().startsWith("#")) continue;
            int lineIndent = indentOf(line);
            if (lineIndent <= indent) return -1;
            String trimmed = line.strip();
            if (trimmed.startsWith(key + ":")) return i;
        }
        return -1;
    }

    private static int indentOf(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') indent++;
        return indent;
    }

    // Strict JSON, as the blueprint's JSON-flow lines are written; anything else (an empty value, YAML block style) fails.
    private JsonElement json(String text, String what) {
        return EntityDocuments.strictJson(text).filter(json -> !text.isBlank() && !json.isJsonNull())
                .orElseThrow(() -> new IllegalStateException(name + ": " + what + " is not strict JSON on one line: " + text));
    }
}

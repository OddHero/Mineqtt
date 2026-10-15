package art.rehra.mineqtt.twin.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Where a value sits in a JSON payload (design §5.2, M3 plan D7). The grammar is
 * {@code segment ('.' segment | '[' int ']' | '["' key '"]')*}:
 * <ul>
 *     <li>a segment is one or more letters, digits, {@code _} or {@code -}: {@code a_dict.nested.b};</li>
 *     <li>{@code [n]} is the n-th element of a list, counted from 0, without leading zeros: {@code forecast[0].temperature};</li>
 *     <li>{@code ["key"]} is a key with any other characters ({@code .}, {@code ]}, spaces, …); inside the quotes
 *     {@code \"} is a quote and {@code \\} a backslash, and control characters are refused;</li>
 *     <li>a path may also start with {@code [n]} or {@code ["key"]}, for a payload that is a list;</li>
 *     <li>{@code ""} is the whole payload.</li>
 * </ul>
 * At most {@value #MAX_DEPTH} steps and {@value #MAX_LENGTH} characters; a payload is read only up to
 * {@value #MAX_PAYLOAD_BYTES} bytes, the mirror subscription cap. For Home Assistant a path runs over the entity document
 * {@code {"state", "attributes": {…}, "last_changed", "last_updated"}}, and {@link #statestreamLeaf()} names the one
 * statestream topic it needs. Minecraft-free and immutable.
 */
public final class ValuePath {
    public static final int MAX_DEPTH = 16;
    public static final int MAX_LENGTH = 256;
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    /** The keys of a Home Assistant entity document; except for {@link #ATTRIBUTES} each is its own statestream leaf. */
    public static final String STATE = "state";
    public static final String ATTRIBUTES = "attributes";
    public static final String LAST_CHANGED = "last_changed";
    public static final String LAST_UPDATED = "last_updated";
    /** The whole payload. */
    public static final ValuePath WHOLE = new ValuePath(List.of());
    // An index fits an int with room to spare; no payload under the cap holds a longer list.
    private static final int MAX_INDEX_DIGITS = 9;

    private final List<Step> steps;

    private ValuePath(List<Step> steps) {
        this.steps = List.copyOf(steps);
    }

    /** One step of a path: a key of an object or an index into a list. */
    public sealed interface Step permits Key, Index {
    }

    public record Key(String name) implements Step {
        public Key {
            if (name == null) throw new IllegalArgumentException("A key needs a name");
        }
    }

    public record Index(int index) implements Step {
        public Index {
            if (index < 0) throw new IllegalArgumentException("Negative index " + index);
        }
    }

    /**
     * The path that {@code text} spells, surrounding whitespace ignored.
     *
     * @throws IllegalArgumentException with the reason and the position, for text that is no path
     */
    public static ValuePath parse(String text) {
        return new Parser(text.strip()).parse();
    }

    /** Why {@code text} is no path, or empty when it is one. */
    public static Optional<String> problem(String text) {
        try {
            parse(text);
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.of(e.getMessage());
        }
    }

    /** The path of these keys, e.g. {@code keys("attributes", "unit_of_measurement")}. */
    public static ValuePath keys(String... names) {
        List<Step> steps = new ArrayList<>();
        for (String name : names) steps.add(new Key(name));
        return of(steps);
    }

    /** The path of these steps. */
    public static ValuePath of(List<Step> steps) {
        if (steps.size() > MAX_DEPTH) throw new IllegalArgumentException("A path has at most " + MAX_DEPTH + " steps, not " + steps.size());
        ValuePath path = new ValuePath(steps);
        if (path.toString().length() > MAX_LENGTH) throw new IllegalArgumentException("A path has at most " + MAX_LENGTH + " characters");
        return path;
    }

    public List<Step> steps() {
        return steps;
    }

    /** Whether this is {@code ""}, the whole payload. */
    public boolean whole() {
        return steps.isEmpty();
    }

    /**
     * The value at this path in {@code root}. Empty when a step is missing: a key that is absent, an index past the end, a
     * key into a list, an index into an object, or any step into a scalar or {@code null}. A {@code null} at the end is
     * present and comes back as {@link JsonNull}, so a caller can tell "cleared" from "never sent".
     */
    public Optional<JsonElement> select(JsonElement root) {
        JsonElement current = root;
        for (Step step : steps) {
            JsonElement next = switch (step) {
                case Key key -> current.isJsonObject() ? current.getAsJsonObject().get(key.name()) : null;
                case Index index -> current.isJsonArray() && index.index() < current.getAsJsonArray().size()
                        ? current.getAsJsonArray().get(index.index()) : null;
            };
            if (next == null) return Optional.empty();
            current = next;
        }
        return Optional.of(current);
    }

    /** The value at this path in a raw payload ({@link #payload}). */
    public Optional<JsonElement> read(byte[] payload) {
        return payload(payload).flatMap(this::select);
    }

    /**
     * A raw MQTT payload as JSON: strict JSON when it is that, otherwise its text as a JSON string (Tasmota's {@code ON},
     * zigbee2mqtt's plain values). Empty for a zero-length payload (a cleared retained message), a payload over
     * {@value #MAX_PAYLOAD_BYTES} bytes and one that is not UTF-8. Home Assistant's statestream {@code state} leaf is raw
     * text and never goes through here (M3 plan D1).
     */
    public static Optional<JsonElement> payload(byte[] payload) {
        if (payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) return Optional.empty();
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload))
                    .toString();
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
        return Optional.of(strictJson(text).orElseGet(() -> new JsonPrimitive(text)));
    }

    /**
     * The statestream leaf this path reads in a Home Assistant entity document: {@code state}, {@code last_updated} and
     * {@code last_changed} are their own topics, {@code attributes.x…} reads the topic {@code x}. Empty for the whole
     * document, {@code attributes} alone, an attribute key that is not one topic level ({@code ""}, {@code a/b},
     * {@code +}, {@code #}) and any other first step, which no single topic holds. An attribute named like
     * a reserved leaf overwrites that topic in Home Assistant (M3 plan D1.4); that is documented, not handled.
     */
    public Optional<String> statestreamLeaf() {
        if (steps.isEmpty() || !(steps.getFirst() instanceof Key first)) return Optional.empty();
        return switch (first.name()) {
            case STATE, LAST_UPDATED, LAST_CHANGED -> Optional.of(first.name());
            case ATTRIBUTES -> steps.size() > 1 && steps.get(1) instanceof Key attribute && topicLevel(attribute.name())
                    ? Optional.of(attribute.name()) : Optional.empty();
            default -> Optional.empty();
        };
    }

    // A key Home Assistant can publish as one statestream leaf: a key with '/' makes extra topic levels (ignored as
    // SHAPE), and an empty key, a wildcard or NUL is no topic level at all.
    private static boolean topicLevel(String key) {
        return !key.isEmpty() && key.indexOf('/') < 0 && key.indexOf('+') < 0 && key.indexOf('#') < 0 && key.indexOf('\0') < 0;
    }

    /** The canonical text, which {@link #parse} reads back to an equal path: bare segments where they may be, else quoted keys. */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder();
        for (Step step : steps) {
            switch (step) {
                case Key key when bare(key.name()) -> {
                    if (!text.isEmpty()) text.append('.');
                    text.append(key.name());
                }
                case Key key -> text.append("[\"").append(key.name().replace("\\", "\\\\").replace("\"", "\\\"")).append("\"]");
                case Index index -> text.append('[').append(index.index()).append(']');
            }
        }
        return text.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ValuePath path && steps.equals(path.steps);
    }

    @Override
    public int hashCode() {
        return steps.hashCode();
    }

    private static boolean bare(String name) {
        return !name.isEmpty() && name.codePoints().allMatch(ValuePath::segmentChar);
    }

    private static boolean segmentChar(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || codePoint == '_' || codePoint == '-';
    }

    private static Optional<JsonElement> strictJson(String text) {
        return EntityDocuments.strictJson(text);
    }

    // A recursive-descent reader of the grammar; positions in messages count from 1.
    private static final class Parser {
        private final String text;
        private final List<Step> steps = new ArrayList<>();
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        ValuePath parse() {
            if (text.length() > MAX_LENGTH) throw error("is longer than " + MAX_LENGTH + " characters");
            if (text.isEmpty()) return WHOLE;
            if (peek() == '[') bracket();
            else segment();
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == '.') {
                    pos++;
                    segment();
                } else if (c == '[') {
                    bracket();
                } else {
                    throw error("expects '.' or '[' at " + (pos + 1) + ", not '" + c + "'");
                }
            }
            return new ValuePath(steps);
        }

        private void segment() {
            int start = pos;
            while (pos < text.length()) {
                int codePoint = text.codePointAt(pos);
                if (!segmentChar(codePoint)) break;
                pos += Character.charCount(codePoint);
            }
            if (pos == start) throw error("expects a name at " + (start + 1) + (start < text.length() ? ", not '" + text.charAt(start) + "'" : ""));
            add(new Key(text.substring(start, pos)));
        }

        private void bracket() {
            int open = pos++;
            if (peek() == '"') {
                pos++;
                StringBuilder key = new StringBuilder();
                while (true) {
                    if (pos >= text.length()) throw error("has an unclosed quoted key at " + (open + 1));
                    char c = text.charAt(pos++);
                    if (c == '"') break;
                    if (Character.isISOControl(c)) throw error("has a control character in the key at " + pos);
                    if (c == '\\') {
                        char escaped = pos < text.length() ? text.charAt(pos++) : 0;
                        if (escaped != '"' && escaped != '\\') throw error("has an unknown escape at " + (pos - 1) + "; only \\\" and \\\\ are allowed");
                        key.append(escaped);
                    } else {
                        key.append(c);
                    }
                }
                expect(']');
                add(new Key(key.toString()));
                return;
            }
            int start = pos;
            while (pos < text.length() && text.charAt(pos) >= '0' && text.charAt(pos) <= '9') pos++;
            String digits = text.substring(start, pos);
            if (digits.isEmpty()) throw error("expects an index or a quoted key at " + (start + 1));
            if (digits.length() > 1 && digits.charAt(0) == '0') throw error("has an index with a leading zero at " + (start + 1));
            if (digits.length() > MAX_INDEX_DIGITS) throw error("has an index longer than " + MAX_INDEX_DIGITS + " digits at " + (start + 1));
            expect(']');
            add(new Index(Integer.parseInt(digits)));
        }

        private void expect(char c) {
            if (peek() != c) throw error("expects '" + c + "' at " + (pos + 1));
            pos++;
        }

        private char peek() {
            return pos < text.length() ? text.charAt(pos) : 0;
        }

        private void add(Step step) {
            if (steps.size() == MAX_DEPTH) throw error("has more than " + MAX_DEPTH + " steps");
            steps.add(step);
        }

        private IllegalArgumentException error(String reason) {
            return new IllegalArgumentException("The path \"" + text + "\" " + reason);
        }
    }
}

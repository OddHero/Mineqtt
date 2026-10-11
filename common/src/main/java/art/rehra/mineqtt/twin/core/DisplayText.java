package art.rehra.mineqtt.twin.core;

import art.rehra.mineqtt.flow.core.Coerce;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The Display Panel's text laid out on the server (M3 plan D18): wrapped into finished lines, so the render tag carries at
 * most {@value #MAX_LINES} lines of {@value #MAX_COLUMNS} characters and the client only draws them. Lengths count code
 * points, as the text fields do; the renderer fits each line to the panel's width.
 * <ul>
 *     <li>Text is sanitised ({@link Coerce#sanitize}); a line break in it starts a new line.</li>
 *     <li>Words wrap at spaces; a word longer than a line is split.</li>
 *     <li>Text that does not fit ends its last line with {@value #ELLIPSIS}.</li>
 *     <li>{@link Size#AUTO} picks the largest size that shows the text whole without splitting a word, else the largest
 *     that shows it whole, else {@link Size#SMALL}, cut.</li>
 *     <li>A mirrored panel shows a title (its device name) small over the value ({@link #titled}): the title takes one
 *     small line and the value at most {@value #TITLED_VALUE_LINES}.</li>
 * </ul>
 * Minecraft-free and pure.
 */
public final class DisplayText {
    public static final int MAX_LINES = 4;
    public static final int MAX_COLUMNS = 32;
    public static final String ELLIPSIS = "…";
    /** The most lines a titled value takes, under its title line. */
    public static final int TITLED_VALUE_LINES = MAX_LINES - 1;
    // Work on at most this much text; four lines never show more.
    private static final int MAX_INPUT = 1024;

    private DisplayText() {
    }

    /** A panel's text size: how many lines of how many characters it shows. */
    public enum Size {
        AUTO("auto", 0, 0),
        LARGE("large", 2, 10),
        MEDIUM("medium", 3, 13),
        SMALL("small", 4, 16);

        private final String serializedName;
        private final int lines;
        private final int columns;

        Size(String serializedName, int lines, int columns) {
            this.serializedName = serializedName;
            this.lines = lines;
            this.columns = columns;
        }

        /** The setting value: {@code auto}, {@code large}, {@code medium} or {@code small}. */
        public String serializedName() {
            return serializedName;
        }

        /** The lines it shows; 0 for {@link #AUTO}, which resolves to another size. */
        public int lines() {
            return lines;
        }

        /** The characters per line; 0 for {@link #AUTO}. */
        public int columns() {
            return columns;
        }

        /** The size of a setting value, without regard to case. */
        public static Optional<Size> byName(String name) {
            String key = name.trim().toLowerCase(Locale.ROOT);
            for (Size size : values()) {
                if (size.serializedName.equals(key)) return Optional.of(size);
            }
            return Optional.empty();
        }
    }

    /**
     * Finished lines for the render tag.
     *
     * @param title     the small title line over the value, or null for none
     * @param size      the size the lines were laid out for, never {@link Size#AUTO}
     * @param lines     the value's lines, each at most {@code size.columns()} code points
     * @param truncated whether text was cut to fit
     */
    public record Layout(String title, Size size, List<String> lines, boolean truncated) {
        public Layout {
            lines = List.copyOf(lines);
            if (size == Size.AUTO) throw new IllegalArgumentException("A layout has a resolved size");
            if (lines.size() + (title == null ? 0 : 1) > MAX_LINES) throw new IllegalArgumentException("More than " + MAX_LINES + " lines");
            for (String line : lines) {
                if (length(line) > size.columns()) throw new IllegalArgumentException("A line longer than " + size.columns() + ": " + line);
            }
            if (title != null && length(title) > MAX_COLUMNS) throw new IllegalArgumentException("A title longer than " + MAX_COLUMNS);
        }
    }

    /** {@code text} laid out in {@code size}, {@link Size#AUTO} resolved. */
    public static Layout layout(String text, Size size) {
        return lay(null, text, size, MAX_LINES);
    }

    /** {@code value} under the small title line {@code title} (cut to a small line), the value in at most {@value #TITLED_VALUE_LINES} lines. */
    public static Layout titled(String title, String value, Size size) {
        Wrapped head = wrap(title, Size.SMALL.columns(), 1);
        return lay(head.lines().isEmpty() ? null : head.lines().getFirst(), value, size, TITLED_VALUE_LINES);
    }

    private static Layout lay(String title, String text, Size size, int maxLines) {
        if (size != Size.AUTO) {
            Wrapped wrapped = wrap(text, size.columns(), Math.min(size.lines(), maxLines));
            return new Layout(title, size, wrapped.lines(), wrapped.truncated());
        }
        Wrapped[] candidates = new Wrapped[]{
                wrap(text, Size.LARGE.columns(), Math.min(Size.LARGE.lines(), maxLines)),
                wrap(text, Size.MEDIUM.columns(), Math.min(Size.MEDIUM.lines(), maxLines)),
                wrap(text, Size.SMALL.columns(), Math.min(Size.SMALL.lines(), maxLines))};
        Size[] sizes = {Size.LARGE, Size.MEDIUM, Size.SMALL};
        for (int i = 0; i < sizes.length; i++) {
            if (!candidates[i].truncated() && !candidates[i].splitWord()) return new Layout(title, sizes[i], candidates[i].lines(), false);
        }
        for (int i = 0; i < sizes.length; i++) {
            if (!candidates[i].truncated()) return new Layout(title, sizes[i], candidates[i].lines(), false);
        }
        return new Layout(title, Size.SMALL, candidates[2].lines(), true);
    }

    // Text wrapped to lines of at most `columns` code points, at most `maxLines` of them.
    private record Wrapped(List<String> lines, boolean truncated, boolean splitWord) {
    }

    private static Wrapped wrap(String text, int columns, int maxLines) {
        List<String> lines = new ArrayList<>();
        boolean splitWord = false;
        String input = text.codePointCount(0, text.length()) > MAX_INPUT ? text.substring(0, text.offsetByCodePoints(0, MAX_INPUT)) : text;
        boolean cutInput = input.length() < text.length();
        for (String paragraph : input.split("\\R", -1)) {
            // Tabs and other spacing count as spaces; the sanitiser would drop them and join the words.
            String clean = Coerce.sanitize(paragraph.replaceAll("\\s", " "), MAX_INPUT).strip();
            if (clean.isEmpty()) {
                lines.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : clean.split("\\s+")) {
                int wordLength = length(word);
                if (wordLength > columns) {
                    splitWord = true;
                    if (!line.isEmpty()) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    int offset = 0;
                    while (length(word.substring(offset)) > columns) {
                        int end = word.offsetByCodePoints(offset, columns);
                        lines.add(word.substring(offset, end));
                        offset = end;
                    }
                    line.append(word.substring(offset));
                } else if (line.isEmpty()) {
                    line.append(word);
                } else if (length(line) + 1 + wordLength <= columns) {
                    line.append(' ').append(word);
                } else {
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            if (!line.isEmpty()) lines.add(line.toString());
        }
        // Blank lines at either end show nothing.
        while (!lines.isEmpty() && lines.getFirst().isEmpty()) lines.removeFirst();
        while (!lines.isEmpty() && lines.getLast().isEmpty()) lines.removeLast();
        boolean truncated = cutInput || lines.size() > maxLines;
        if (!truncated) return new Wrapped(lines, false, splitWord);
        List<String> kept = new ArrayList<>(lines.subList(0, Math.min(maxLines, lines.size())));
        if (kept.isEmpty()) return new Wrapped(kept, true, splitWord);
        String last = kept.getLast();
        if (length(last) >= columns) last = last.substring(0, last.offsetByCodePoints(0, columns - 1));
        kept.set(kept.size() - 1, last.stripTrailing() + ELLIPSIS);
        return new Wrapped(kept, true, splitWord);
    }

    private static int length(CharSequence text) {
        return Character.codePointCount(text, 0, text.length());
    }
}

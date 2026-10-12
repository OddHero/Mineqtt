package art.rehra.mineqtt.telemetry.core;

import art.rehra.mineqtt.security.TokenBucket;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;

/**
 * The bridge's broadcast text (M4 plan D8): what a request on {@code bridge/request/broadcast} may put into the chat of
 * every player, and how often. Whatever the broker delivers is plain text and stays plain text: it is never read as a
 * JSON component, a command or a formatting code. Minecraft-free.
 * <ul>
 *     <li>At most {@value #MAX_PAYLOAD_BYTES} bytes before decoding, strict UTF-8.</li>
 *     <li>Formatting codes ({@code §} and the character after it), control characters and the bidirectional controls
 *     (embeddings, overrides and isolates U+202A–U+202E and U+2066–U+2069, and the marks U+200E, U+200F and U+061C,
 *     which could make the text read in another order than it was sent) are removed.</li>
 *     <li>Runs of whitespace become one space, none at either end; the text is cut to {@value #MAX_LENGTH} characters.
 *     Nothing left means nothing is shown.</li>
 *     <li>{@link Limiter}: a burst of {@value #BURST}, then {@value #PER_SECOND} per second.</li>
 * </ul>
 */
public final class BroadcastText {
    /** The largest request taken, before decoding. */
    public static final int MAX_PAYLOAD_BYTES = 1024;
    /** The longest text shown, in characters (code points); a longer one is cut. */
    public static final int MAX_LENGTH = 256;
    public static final int BURST = 3;
    public static final int PER_SECOND = 1;
    // Starts a formatting code in Minecraft's text.
    private static final int SECTION_SIGN = 0xA7;

    /** What became of a request: the text to show, or why nothing is shown. */
    public sealed interface Result {
    }

    /** The text to show, cleaned. */
    public record Accepted(String text) implements Result {
    }

    /** Why nothing is shown, as the request's answer says it. */
    public record Refused(String error) implements Result {
    }

    private BroadcastText() {
    }

    /** A request's payload as the text it shows, or why it shows none. */
    public static Result parse(byte[] payload) {
        if (payload.length > MAX_PAYLOAD_BYTES) return new Refused("message over " + MAX_PAYLOAD_BYTES + " bytes");
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload)).toString();
        } catch (CharacterCodingException e) {
            return new Refused("message is not UTF-8");
        }
        String text = clean(decoded);
        return text.isEmpty() ? new Refused("empty message") : new Accepted(text);
    }

    /** The text without formatting codes, control characters and bidirectional controls, whitespace collapsed, cut. */
    public static String clean(String text) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), 4 * MAX_LENGTH));
        int length = 0;
        boolean space = false;
        for (int i = 0; i < text.length() && length < MAX_LENGTH; ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == SECTION_SIGN) {
                // The code it starts goes with it; a § at the end goes alone.
                if (i < text.length()) i += Character.charCount(text.codePointAt(i));
                continue;
            }
            if (bidiControl(cp)) continue;
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                space = length > 0;
                continue;
            }
            if (Character.isISOControl(cp)) continue;
            if (space) {
                // A space that only the cut would follow is left out.
                if (length + 1 >= MAX_LENGTH) break;
                out.append(' ');
                length++;
                space = false;
            }
            out.appendCodePoint(cp);
            length++;
        }
        return out.toString();
    }

    private static boolean bidiControl(int cp) {
        return cp >= 0x202A && cp <= 0x202E || cp >= 0x2066 && cp <= 0x2069 || cp == 0x200E || cp == 0x200F || cp == 0x061C;
    }

    /** The rate of broadcast text: a burst of {@value #BURST}, then {@value #PER_SECOND} per second. Server thread only. */
    public static final class Limiter {
        private final TokenBucket bucket;

        /** @param millis a monotonic clock in milliseconds */
        public Limiter(LongSupplier millis) {
            this.bucket = new TokenBucket(BURST, PER_SECOND, () -> millis.getAsLong() * 1_000_000L);
        }

        /** Whether a text may be shown now; one that may takes its place in the rate. */
        public boolean admit() {
            return bucket.tryTake();
        }
    }
}

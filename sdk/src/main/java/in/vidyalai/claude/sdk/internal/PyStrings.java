package in.vidyalai.claude.sdk.internal;

/**
 * Python's whitespace rules, for code that must strip text exactly as the
 * Python SDK does (stdout/stderr line framing, first-prompt extraction).
 *
 * <p>
 * Java's {@link String#strip()} misses the no-break spaces (U+00A0, U+2007,
 * U+202F) and U+0085, which Python's {@code str.isspace()} treats as
 * whitespace. Every Python whitespace character is in the BMP, so testing
 * UTF-16 units is exact.
 *
 * <p>
 * Internal: not part of the SDK's API.
 */
public final class PyStrings {

    private PyStrings() {
    }

    /** Python's {@code str.isspace()} for one character. */
    public static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || (c == '\u0085');
    }

    /** Python's {@code str.strip()}. */
    public static String strip(String s) {
        int start = 0;
        int end = s.length();
        while ((start < end) && isSpace(s.charAt(start))) {
            start++;
        }
        while ((end > start) && isSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    /** Python's {@code str.rstrip()}. */
    public static String rstrip(String s) {
        int end = s.length();
        while ((end > 0) && isSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }
}

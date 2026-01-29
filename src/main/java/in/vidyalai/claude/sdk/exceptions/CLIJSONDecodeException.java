package in.vidyalai.claude.sdk.exceptions;

/**
 * Raised when unable to decode JSON from CLI output.
 */
public class CLIJSONDecodeException extends ClaudeSDKException {

    private static final int MAX_LINE_LEN = 100;

    private final String line;

    /**
     * Creates a new exception with the raw line that failed to parse.
     *
     * @param line  the raw line that failed to parse
     * @param cause the original parsing exception
     */
    public CLIJSONDecodeException(String line, Throwable cause) {
        super(buildMessage(line), cause);
        this.line = line;
    }

    private static String buildMessage(String line) {
        String truncated = ((line.length() > MAX_LINE_LEN)
                ? new StringBuilder(line.substring(0, MAX_LINE_LEN)).append("...").toString()
                : line);
        return new StringBuilder("Failed to decode JSON: ").append(truncated).toString();
    }

    /**
     * Gets the raw line that failed to parse.
     *
     * @return the raw line
     */
    public String getLine() {
        return line;
    }

}

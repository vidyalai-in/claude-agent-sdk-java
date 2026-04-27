package in.vidyalai.claude.sdk.types.config;

/**
 * Controls whether thinking text is returned summarized or omitted.
 *
 * <p>Opus 4.7+ defaults to {@link #OMITTED} (signature-only); pass
 * {@link #SUMMARIZED} to receive text. Forwarded to the CLI as the
 * {@code --thinking-display} flag.
 */
public enum ThinkingDisplay {

    /** Return thinking text summarized in the assistant stream. */
    SUMMARIZED("summarized"),

    /** Omit thinking text and only return signature blocks. */
    OMITTED("omitted");

    private final String value;

    ThinkingDisplay(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static ThinkingDisplay fromValue(String value) {
        for (ThinkingDisplay d : values()) {
            if (d.value.equals(value)) {
                return d;
            }
        }
        throw new IllegalArgumentException("Unknown ThinkingDisplay value: " + value);
    }

    @Override
    public String toString() {
        return value;
    }

}

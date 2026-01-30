package in.vidyalai.claude.sdk.types.config;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Beta feature flags for the Claude SDK.
 *
 * <p>
 * Beta features allow access to experimental functionality.
 * Use with
 * {@link in.vidyalai.claude.sdk.ClaudeAgentOptions.Builder#betas(java.util.List)}.
 *
 * <pre>{@code
 * var options = ClaudeAgentOptions.builder()
 *         .betas(List.of(SdkBeta.CONTEXT_1M))
 *         .build();
 * }</pre>
 *
 * @see <a href="https://docs.anthropic.com/en/api/beta-headers">Anthropic Beta
 *      Headers</a>
 */
public enum SdkBeta {

    /**
     * Extended context window (1M tokens) beta.
     */
    CONTEXT_1M("context-1m-2025-08-07");

    private final String value;

    SdkBeta(String value) {
        this.value = value;
    }

    /**
     * Returns the beta header value.
     */
    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * Creates a SdkBeta from its string value.
     *
     * @param value the beta header value
     * @return the corresponding SdkBeta
     * @throws IllegalArgumentException if the value is not recognized
     */
    public static SdkBeta fromValue(String value) {
        for (SdkBeta beta : values()) {
            if (beta.value.equals(value)) {
                return beta;
            }
        }
        throw new IllegalArgumentException("Unknown SDK beta: " + value);
    }

    @Override
    public String toString() {
        return value;
    }

}

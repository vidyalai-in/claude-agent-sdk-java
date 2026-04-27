package in.vidyalai.claude.sdk.types.config;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Adaptive thinking configuration.
 *
 * <p>
 * When adaptive thinking is enabled, the system will use a default token budget
 * of 32,000 tokens for extended thinking. This allows Claude to reason through
 * complex problems adaptively.
 *
 * <p>
 * Example usage:
 *
 * <pre>{@code
 * ThinkingConfig config = new ThinkingConfigAdaptive();
 * // or with explicit display control:
 * ThinkingConfig config = new ThinkingConfigAdaptive(ThinkingDisplay.SUMMARIZED);
 * }</pre>
 *
 * @param display optional display mode forwarded to the CLI as
 *                {@code --thinking-display}; null leaves the model's default
 *                in place.
 *
 * @see ThinkingConfig
 * @see ThinkingConfigEnabled
 * @see ThinkingConfigDisabled
 */
public record ThinkingConfigAdaptive(
        @JsonProperty("display") @Nullable ThinkingDisplay display) implements ThinkingConfig {

    /**
     * Creates an adaptive thinking config without a display override.
     */
    public ThinkingConfigAdaptive() {
        this(null);
    }

    @Override
    public String type() {
        return "adaptive";
    }

}

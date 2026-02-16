package in.vidyalai.claude.sdk.types.config;

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
 * }</pre>
 *
 * @see ThinkingConfig
 * @see ThinkingConfigEnabled
 * @see ThinkingConfigDisabled
 */
public record ThinkingConfigAdaptive() implements ThinkingConfig {

    @Override
    public String type() {
        return "adaptive";
    }

}

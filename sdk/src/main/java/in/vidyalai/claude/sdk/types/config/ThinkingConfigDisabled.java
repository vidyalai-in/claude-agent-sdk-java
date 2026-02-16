package in.vidyalai.claude.sdk.types.config;

/**
 * Disabled thinking configuration.
 *
 * <p>
 * When thinking is disabled, Claude will not use extended thinking tokens.
 * This is useful for simple queries that don't require deep reasoning.
 *
 * <p>
 * Example usage:
 * 
 * <pre>{@code
 * ThinkingConfig config = new ThinkingConfigDisabled();
 * }</pre>
 *
 * @see ThinkingConfig
 * @see ThinkingConfigAdaptive
 * @see ThinkingConfigEnabled
 */
public record ThinkingConfigDisabled() implements ThinkingConfig {

    @Override
    public String type() {
        return "disabled";
    }

}

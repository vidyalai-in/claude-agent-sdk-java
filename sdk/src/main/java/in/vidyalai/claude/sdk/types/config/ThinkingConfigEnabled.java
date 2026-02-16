package in.vidyalai.claude.sdk.types.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Enabled thinking configuration with a specific token budget.
 *
 * <p>
 * When thinking is enabled with a budget, Claude will use up to the specified
 * number of tokens for extended thinking. This allows fine-grained control over
 * the amount of reasoning.
 *
 * <p>
 * Example usage:
 * 
 * <pre>{@code
 * ThinkingConfig config = new ThinkingConfigEnabled(10000);
 * }</pre>
 *
 * @param budgetTokens the maximum number of tokens to use for thinking (must be
 *                     positive)
 * @see ThinkingConfig
 * @see ThinkingConfigAdaptive
 * @see ThinkingConfigDisabled
 */
public record ThinkingConfigEnabled(
        @JsonProperty("budget_tokens") int budgetTokens) implements ThinkingConfig {

    /**
     * Creates an enabled thinking config with the specified token budget.
     *
     * @param budgetTokens the maximum number of tokens to use for thinking
     * @throws IllegalArgumentException if budgetTokens is not positive
     */
    public ThinkingConfigEnabled {
        if (budgetTokens <= 0) {
            throw new IllegalArgumentException("budgetTokens must be positive, got: " + budgetTokens);
        }
    }

    @Override
    public String type() {
        return "enabled";
    }

}

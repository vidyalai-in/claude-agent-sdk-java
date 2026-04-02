package in.vidyalai.claude.sdk.types.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * API-side task budget in tokens.
 *
 * <p>
 * When set, the model is made aware of its remaining token budget so it can
 * pace tool use and wrap up before the limit.
 *
 * <pre>{@code
 * var options = ClaudeAgentOptions.builder()
 *         .taskBudget(new TaskBudget(100000))
 *         .build();
 * }</pre>
 *
 * @param total total token budget
 */
public record TaskBudget(
        @JsonProperty("total") int total) {
}

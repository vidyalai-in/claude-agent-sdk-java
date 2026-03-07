package in.vidyalai.claude.sdk.types.message;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Usage statistics for a task.
 *
 * @param totalTokens total number of tokens used
 * @param toolUses    number of tool uses
 * @param durationMs  task duration in milliseconds
 */
public record TaskUsage(
        @JsonProperty("total_tokens") int totalTokens,
        @JsonProperty("tool_uses") int toolUses,
        @JsonProperty("duration_ms") int durationMs) {
}

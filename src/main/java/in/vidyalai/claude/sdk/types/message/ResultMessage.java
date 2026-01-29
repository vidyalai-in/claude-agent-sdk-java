package in.vidyalai.claude.sdk.types.message;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result message with cost and usage information.
 *
 * <p>
 * This message is sent at the end of a conversation turn and contains
 * information about the API call duration, cost, and token usage.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code snake_case} for JSON
 * field names because it represents data <b>received from the CLI</b>. See
 * {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param subtype          the subtype of the result message
 * @param durationMs       total duration in milliseconds
 * @param durationApiMs    duration of API calls in milliseconds
 * @param isError          whether the result is an error
 * @param numTurns         number of conversation turns
 * @param sessionId        the session identifier
 * @param totalCostUsd     total cost in USD (may be null)
 * @param usage            token usage breakdown (may be null)
 * @param result           the result text (may be null)
 * @param structuredOutput structured output if json_schema was specified (may
 *                         be null)
 */
public record ResultMessage(
        @JsonProperty("subtype") String subtype,
        @JsonProperty("duration_ms") int durationMs,
        @JsonProperty("duration_api_ms") int durationApiMs,
        @JsonProperty("is_error") boolean isError,
        @JsonProperty("num_turns") int numTurns,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("total_cost_usd") @Nullable Double totalCostUsd,
        @JsonProperty("usage") @Nullable Map<String, Object> usage,
        @JsonProperty("result") @Nullable String result,
        @JsonProperty("structured_output") @Nullable Object structuredOutput) implements Message {

    @Override
    public String type() {
        return "result";
    }

}

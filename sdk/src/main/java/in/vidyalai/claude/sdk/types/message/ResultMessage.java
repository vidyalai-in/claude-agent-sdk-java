package in.vidyalai.claude.sdk.types.message;

import java.util.List;
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
 * @param subtype           the subtype of the result message
 * @param durationMs        total duration in milliseconds
 * @param durationApiMs     duration of API calls in milliseconds
 * @param isError           whether the result is an error
 * @param numTurns          number of conversation turns
 * @param sessionId         the session identifier
 * @param stopReason        reason the session stopped (may be null)
 * @param totalCostUsd      total cost in USD (may be null)
 * @param usage             token usage breakdown (may be null)
 * @param result            the result text (may be null)
 * @param structuredOutput  structured output if json_schema was specified (may
 *                          be null)
 * @param modelUsage        per-model usage breakdown (may be null)
 * @param permissionDenials list of permission denials during the session (may
 *                          be null)
 * @param deferredToolUse   tool call deferred by a PreToolUse hook returning
 *                          {@code "defer"} (may be null)
 * @param errors            list of error messages from the CLI (may be null)
 * @param apiErrorStatus    HTTP status code (e.g. 429, 500, 529) of the
 *                          failing API call when {@code isError} is true and
 *                          {@code subtype} is "success"; null otherwise
 * @param uuid              unique identifier for this message in the session
 */
public record ResultMessage(
        @JsonProperty("subtype") String subtype,
        @JsonProperty("duration_ms") int durationMs,
        @JsonProperty("duration_api_ms") int durationApiMs,
        @JsonProperty("is_error") boolean isError,
        @JsonProperty("num_turns") int numTurns,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("stop_reason") @Nullable String stopReason,
        @JsonProperty("total_cost_usd") @Nullable Double totalCostUsd,
        @JsonProperty("usage") @Nullable Map<String, Object> usage,
        @JsonProperty("result") @Nullable String result,
        @JsonProperty("structured_output") @Nullable Object structuredOutput,
        @JsonProperty("modelUsage") @Nullable Map<String, Object> modelUsage,
        @JsonProperty("permission_denials") @Nullable List<Object> permissionDenials,
        @JsonProperty("deferred_tool_use") @Nullable DeferredToolUse deferredToolUse,
        @JsonProperty("errors") @Nullable List<String> errors,
        @JsonProperty("api_error_status") @Nullable Integer apiErrorStatus,
        @JsonProperty("uuid") @Nullable String uuid) implements Message {

    /**
     * Backwards-compatible constructor without modelUsage, permissionDenials,
     * errors, and uuid fields.
     */
    public ResultMessage(String subtype, int durationMs, int durationApiMs,
            boolean isError, int numTurns, String sessionId,
            @Nullable String stopReason, @Nullable Double totalCostUsd,
            @Nullable Map<String, Object> usage, @Nullable String result,
            @Nullable Object structuredOutput) {
        this(subtype, durationMs, durationApiMs, isError, numTurns, sessionId,
                stopReason, totalCostUsd, usage, result, structuredOutput,
                null, null, null, null, null, null);
    }

    /**
     * Backwards-compatible constructor with modelUsage, permissionDenials,
     * errors, and uuid fields (pre-{@code deferredToolUse} / {@code apiErrorStatus}).
     */
    public ResultMessage(String subtype, int durationMs, int durationApiMs,
            boolean isError, int numTurns, String sessionId,
            @Nullable String stopReason, @Nullable Double totalCostUsd,
            @Nullable Map<String, Object> usage, @Nullable String result,
            @Nullable Object structuredOutput,
            @Nullable Map<String, Object> modelUsage,
            @Nullable List<Object> permissionDenials,
            @Nullable List<String> errors,
            @Nullable String uuid) {
        this(subtype, durationMs, durationApiMs, isError, numTurns, sessionId,
                stopReason, totalCostUsd, usage, result, structuredOutput,
                modelUsage, permissionDenials, null, errors, null, uuid);
    }

    @Override
    public String type() {
        return "result";
    }

}

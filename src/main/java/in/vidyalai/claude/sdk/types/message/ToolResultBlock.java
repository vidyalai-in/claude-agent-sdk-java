package in.vidyalai.claude.sdk.types.message;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Tool result content block containing the result of a tool execution.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code snake_case} for JSON
 * field names because it represents data <b>received from the CLI</b>. See
 * {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param toolUseId the ID of the tool use this result corresponds to
 * @param content   the result content (can be string or structured data)
 * @param isError   whether the tool execution resulted in an error
 */
public record ToolResultBlock(
        @JsonProperty("tool_use_id") String toolUseId,
        @JsonProperty("content") @Nullable Object content,
        @JsonProperty("is_error") @Nullable Boolean isError) implements ContentBlock {

    @Override
    public String type() {
        return "tool_result";
    }

}

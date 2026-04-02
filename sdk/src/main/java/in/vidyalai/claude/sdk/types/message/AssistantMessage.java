package in.vidyalai.claude.sdk.types.message;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Assistant message containing Claude's response.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code snake_case} for JSON
 * field names because it represents data <b>received from the CLI</b>. See
 * {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param content         list of content blocks in the response
 * @param model           the model that generated this response
 * @param parentToolUseId if this message is within a tool use context, the tool
 *                        use ID
 * @param error           error information if the response contains an error
 * @param usage           per-turn token usage breakdown (input_tokens,
 *                        output_tokens, cache tokens, etc.) or null if absent
 * @param messageId       unique message ID from the API (e.g.
 *                        "msg_01HRq7YZE3apPqSHydvG77Ve")
 * @param stopReason      reason the model stopped generating (e.g. "end_turn")
 * @param sessionId       session ID this message belongs to
 * @param uuid            unique identifier for this message in the session
 */
public record AssistantMessage(
        @JsonProperty("content") List<ContentBlock> content,
        @JsonProperty("model") String model,
        @JsonProperty("parent_tool_use_id") @Nullable String parentToolUseId,
        @JsonProperty("error") @Nullable AssistantMessageError error,
        @JsonProperty("usage") @Nullable Map<String, Object> usage,
        @JsonProperty("message_id") @Nullable String messageId,
        @JsonProperty("stop_reason") @Nullable String stopReason,
        @JsonProperty("session_id") @Nullable String sessionId,
        @JsonProperty("uuid") @Nullable String uuid) implements Message {

    /**
     * Backwards-compatible constructor without new fields.
     */
    public AssistantMessage(List<ContentBlock> content, String model,
            @Nullable String parentToolUseId, @Nullable AssistantMessageError error) {
        this(content, model, parentToolUseId, error, null, null, null, null, null);
    }

    /**
     * Backwards-compatible constructor with usage but without new fields.
     */
    public AssistantMessage(List<ContentBlock> content, String model,
            @Nullable String parentToolUseId, @Nullable AssistantMessageError error,
            @Nullable Map<String, Object> usage) {
        this(content, model, parentToolUseId, error, usage, null, null, null, null);
    }

    @Override
    public String type() {
        return "assistant";
    }

    /**
     * Extracts all text content from the message.
     *
     * @return concatenated text from all TextBlock content blocks
     */
    public String getTextContent() {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : content) {
            if (block instanceof TextBlock textBlock) {
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append(textBlock.text());
            }
        }
        return sb.toString();
    }

    /**
     * Checks if this message contains any tool use blocks.
     *
     * @return true if the message contains at least one ToolUseBlock
     */
    public boolean hasToolUse() {
        return content.stream().anyMatch(b -> b instanceof ToolUseBlock);
    }

}

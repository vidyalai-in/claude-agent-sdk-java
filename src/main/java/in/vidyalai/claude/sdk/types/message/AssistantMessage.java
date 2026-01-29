package in.vidyalai.claude.sdk.types.message;

import java.util.List;

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
 */
public record AssistantMessage(
        @JsonProperty("content") List<ContentBlock> content,
        @JsonProperty("model") String model,
        @JsonProperty("parent_tool_use_id") @Nullable String parentToolUseId,
        @JsonProperty("error") @Nullable AssistantMessageError error) implements Message {

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

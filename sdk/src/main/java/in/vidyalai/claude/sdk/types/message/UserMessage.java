package in.vidyalai.claude.sdk.types.message;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * User message in a conversation.
 *
 * <p>
 * The content can be either a simple string or a list of content blocks
 * (for structured content including tool results).
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code snake_case} for JSON
 * field names because it represents data <b>received from the CLI</b>. See
 * {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param content         the message content (String or List of ContentBlock)
 * @param uuid            unique identifier for the message
 * @param parentToolUseId if this message is in response to a tool use, the tool
 *                        use ID
 * @param toolUseResult   metadata about tool execution results, including file
 *                        edit details like oldString, newString, and
 *                        structuredPatch
 */
public record UserMessage(
        @JsonProperty("content") Object content,
        @JsonProperty("uuid") @Nullable String uuid,
        @JsonProperty("parent_tool_use_id") @Nullable String parentToolUseId,
        @JsonProperty("tool_use_result") @Nullable Map<String, Object> toolUseResult) implements Message {

    @Override
    public String type() {
        return "user";
    }

    /**
     * Gets the content as a string, if it is a simple string message.
     *
     * @return the content string, or null if content is structured
     */
    @Nullable
    public String contentAsString() {
        return ((content instanceof String s) ? s : null);
    }

    /**
     * Gets the content as a list of content blocks, if it is structured content.
     *
     * @return the content blocks, or null if content is a simple string
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public List<ContentBlock> contentAsBlocks() {
        return ((content instanceof List<?> list) ? ((List<ContentBlock>) list) : null);
    }

}

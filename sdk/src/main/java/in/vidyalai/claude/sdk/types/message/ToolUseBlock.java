package in.vidyalai.claude.sdk.types.message;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Tool use content block representing a tool invocation request.
 *
 * @param id    unique identifier for this tool use
 * @param name  the name of the tool being invoked
 * @param input the input parameters for the tool; null when the block has no
 *              {@code input} or it is not a JSON object
 */
public record ToolUseBlock(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("input") Map<String, Object> input) implements ContentBlock {

    @Override
    public String type() {
        return "tool_use";
    }

}

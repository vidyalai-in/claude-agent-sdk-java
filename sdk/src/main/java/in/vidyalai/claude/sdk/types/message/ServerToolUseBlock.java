package in.vidyalai.claude.sdk.types.message;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Server-side tool use block (e.g. {@code advisor}, {@code web_search}, {@code web_fetch}).
 *
 * <p>These are tools the API executes server-side on the model's behalf, so they appear
 * in the message stream alongside regular {@link ToolUseBlock} blocks but the caller
 * never needs to return a result. The {@code name} field is a discriminator — branch
 * on it to know which server tool was invoked.
 *
 * @param id    unique identifier for this server tool use
 * @param name  the server tool name (one of {@link ServerToolName} values, kept as
 *              a raw string for forward compatibility)
 * @param input the input parameters for the tool
 */
public record ServerToolUseBlock(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("input") Map<String, Object> input) implements ContentBlock {

    @Override
    public String type() {
        return "server_tool_use";
    }

}

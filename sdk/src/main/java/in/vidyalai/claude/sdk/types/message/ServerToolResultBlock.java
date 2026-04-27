package in.vidyalai.claude.sdk.types.message;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result block returned for a server-side tool call (e.g. advisor result).
 *
 * <p>Mirrors {@link ToolResultBlock}'s shape. The {@code content} field is the
 * raw map from the API, opaque to this layer — callers that care about a specific
 * server tool's result schema can inspect {@code content.get("type")}.
 *
 * @param toolUseId the ID of the server tool use this result corresponds to
 * @param content   the raw result content (opaque map from the API)
 */
public record ServerToolResultBlock(
        @JsonProperty("tool_use_id") String toolUseId,
        @JsonProperty("content") Map<String, Object> content) implements ContentBlock {

    @Override
    public String type() {
        return "server_tool_result";
    }

}

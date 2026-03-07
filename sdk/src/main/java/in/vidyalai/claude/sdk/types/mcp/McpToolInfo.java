package in.vidyalai.claude.sdk.types.mcp;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Information about a tool provided by an MCP server.
 *
 * @param name        tool name
 * @param description tool description (may be null)
 * @param annotations tool annotations (may be null)
 */
public record McpToolInfo(
        @JsonProperty("name") String name,
        @JsonProperty("description") @Nullable String description,
        @JsonProperty("annotations") @Nullable McpToolAnnotations annotations) {
}

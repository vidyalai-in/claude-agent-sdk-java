package in.vidyalai.claude.sdk.types.mcp;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response from {@code ClaudeSDKClient.getMcpStatus()}.
 *
 * <p>
 * Wraps the list of server statuses under the {@code mcpServers} key, matching
 * the wire-format response shape.
 *
 * @param mcpServers list of MCP server status entries
 */
public record McpStatusResponse(
        @JsonProperty("mcpServers") List<McpServerStatus> mcpServers) {
}

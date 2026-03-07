package in.vidyalai.claude.sdk.types.mcp;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Status information for an MCP server connection.
 *
 * <p>
 * Returned by {@code ClaudeSDKClient.getMcpStatus()} in the {@code mcpServers}
 * list.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses wire-format field names
 * (camelCase where applicable) since data comes directly from CLI JSON output.
 *
 * @param name       server name as configured
 * @param status     current connection status
 * @param serverInfo server information from MCP handshake (available when
 *                   connected)
 * @param error      error message (available when status is 'failed')
 * @param config     server configuration
 * @param scope      configuration scope (e.g., project, user, local)
 * @param tools      tools provided by this server (available when connected)
 */
public record McpServerStatus(
        @JsonProperty("name") String name,
        @JsonProperty("status") McpServerConnectionStatus status,
        @JsonProperty("serverInfo") @Nullable McpServerInfo serverInfo,
        @JsonProperty("error") @Nullable String error,
        @JsonProperty("config") @Nullable McpServerStatusConfig config,
        @JsonProperty("scope") @Nullable String scope,
        @JsonProperty("tools") @Nullable List<McpToolInfo> tools) {
}

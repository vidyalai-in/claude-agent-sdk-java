package in.vidyalai.claude.sdk.types.mcp;

import com.fasterxml.jackson.annotation.JsonIgnore;

import in.vidyalai.claude.sdk.mcp.SdkMcpServer;

/**
 * SDK MCP server configuration that holds an in-process server instance.
 *
 * <p>
 * Unlike external MCP server configs (stdio, SSE, HTTP), this config
 * contains a reference to an actual {@link SdkMcpServer} instance that
 * runs in-process.
 *
 * <p>
 * Create using {@link SdkMcpServer#toConfig()}:
 * 
 * <pre>{@code
 * SdkMcpServer server = SdkMcpServer.create("myserver", List.of(tool1, tool2));
 * McpSdkServerConfig config = server.toConfig();
 *
 * var options = ClaudeAgentOptions.builder()
 *         .mcpServers(Map.of("myserver", config))
 *         .build();
 * }</pre>
 *
 * @param name     the server name
 * @param instance the server instance (not serialized to CLI)
 */
public record McpSdkServerConfig(
        String name,
        @JsonIgnore SdkMcpServer instance) implements McpServerConfig {

    @Override
    public String type() {
        return "sdk";
    }

}

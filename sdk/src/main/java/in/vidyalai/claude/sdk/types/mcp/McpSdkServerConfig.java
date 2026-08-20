package in.vidyalai.claude.sdk.types.mcp;

import com.fasterxml.jackson.annotation.JsonIgnore;

import in.vidyalai.claude.sdk.mcp.McpMessageHandler;
import in.vidyalai.claude.sdk.mcp.SdkMcpServer;

/**
 * SDK MCP server configuration that holds an in-process server instance.
 *
 * <p>
 * Unlike external MCP server configs (stdio, SSE, HTTP), this config
 * contains a reference to a live {@link McpMessageHandler} that runs
 * in-process — usually an {@link SdkMcpServer}, but any implementation
 * serves.
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
 * <p>
 * Only {@code {"type": "sdk", "name": …}} reaches the CLI; the handler stays
 * in this process and is driven back over the control protocol by name.
 *
 * @param name     the server name
 * @param instance the handler serving this server (not serialized to CLI)
 */
public record McpSdkServerConfig(
        String name,
        @JsonIgnore McpMessageHandler instance) implements McpServerConfig {

    @Override
    public String type() {
        return "sdk";
    }

}

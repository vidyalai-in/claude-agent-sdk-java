package in.vidyalai.claude.sdk.types.mcp;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed interface for MCP server configurations as returned in status
 * responses.
 *
 * <p>
 * This is distinct from {@link McpServerConfig} (used when creating servers).
 * Status configurations can be:
 * <ul>
 * <li>{@link McpStdioServerConfig} - Subprocess-based MCP server</li>
 * <li>{@link McpSseServerConfig} - SSE-based MCP server</li>
 * <li>{@link McpHttpServerConfig} - HTTP-based MCP server</li>
 * <li>{@link McpSdkServerConfigStatus} - In-process SDK MCP server (name
 * only)</li>
 * <li>{@link McpClaudeAIProxyServerConfig} - Claude AI proxy MCP server</li>
 * </ul>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = McpStdioServerConfig.class)
@JsonSubTypes({
        @JsonSubTypes.Type(value = McpStdioServerConfig.class, name = "stdio"),
        @JsonSubTypes.Type(value = McpSseServerConfig.class, name = "sse"),
        @JsonSubTypes.Type(value = McpHttpServerConfig.class, name = "http"),
        @JsonSubTypes.Type(value = McpSdkServerConfigStatus.class, name = "sdk"),
        @JsonSubTypes.Type(value = McpClaudeAIProxyServerConfig.class, name = "claudeai-proxy")
})
public sealed interface McpServerStatusConfig permits
        McpStdioServerConfig,
        McpSseServerConfig,
        McpHttpServerConfig,
        McpSdkServerConfigStatus,
        McpClaudeAIProxyServerConfig {

    /**
     * Returns the server type identifier.
     */
    String type();

}

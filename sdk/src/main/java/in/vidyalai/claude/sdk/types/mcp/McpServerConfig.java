package in.vidyalai.claude.sdk.types.mcp;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed interface for MCP server configurations.
 *
 * <p>
 * MCP servers can be configured as:
 * <ul>
 * <li>{@link McpStdioServerConfig} - Subprocess-based MCP server</li>
 * <li>{@link McpSseServerConfig} - SSE-based MCP server</li>
 * <li>{@link McpHttpServerConfig} - HTTP-based MCP server</li>
 * <li>{@link McpSdkServerConfig} - In-process SDK MCP server</li>
 * </ul>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = McpStdioServerConfig.class)
@JsonSubTypes({
                @JsonSubTypes.Type(value = McpStdioServerConfig.class, name = "stdio"),
                @JsonSubTypes.Type(value = McpSseServerConfig.class, name = "sse"),
                @JsonSubTypes.Type(value = McpHttpServerConfig.class, name = "http"),
                @JsonSubTypes.Type(value = McpSdkServerConfig.class, name = "sdk")
})
public sealed interface McpServerConfig permits
                McpStdioServerConfig,
                McpSseServerConfig,
                McpHttpServerConfig,
                McpSdkServerConfig {

        /**
         * Returns the server type identifier.
         */
        String type();

}

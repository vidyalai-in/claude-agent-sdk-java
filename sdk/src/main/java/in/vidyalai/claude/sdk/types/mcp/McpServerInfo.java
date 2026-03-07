package in.vidyalai.claude.sdk.types.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Server info from MCP initialize handshake (available when connected).
 *
 * @param name    server name
 * @param version server version
 */
public record McpServerInfo(
        @JsonProperty("name") String name,
        @JsonProperty("version") String version) {
}

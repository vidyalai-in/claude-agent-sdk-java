package in.vidyalai.claude.sdk.types.mcp;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * HTTP-based MCP server configuration.
 *
 * @param url     the HTTP endpoint URL
 * @param headers optional HTTP headers
 */
public record McpHttpServerConfig(
        @JsonProperty("url") String url,
        @JsonProperty("headers") @Nullable Map<String, String> headers) implements McpServerConfig {

    @Override
    public String type() {
        return "http";
    }

    /**
     * Creates an HTTP config with just a URL.
     */
    public McpHttpServerConfig(String url) {
        this(url, null);
    }

}

package in.vidyalai.claude.sdk.internal;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.mcp.McpMessageHandler;
import in.vidyalai.claude.sdk.types.mcp.McpSdkServerConfig;

/**
 * Finds the in-process MCP servers configured on a set of options.
 *
 * <p>
 * Shared by {@code ClaudeSDK.query(...)} and
 * {@code ClaudeSDKClient.connect()}, which used to carry a copy each.
 */
public final class McpServers {

    private McpServers() {
    }

    /**
     * Extracts the in-process MCP handlers, keyed by the name the CLI uses to
     * address them.
     *
     * <p>
     * Only a {@code Map}-valued {@code mcpServers} is inspected: a {@code Path}
     * or a JSON {@code String} names external servers the CLI loads itself, and
     * cannot carry a live handler. Entries that are not
     * {@link McpSdkServerConfig} are skipped for the same reason.
     *
     * @param options the options to inspect
     * @return the handlers by name, or null when there are none
     */
    @Nullable
    public static Map<String, McpMessageHandler> extract(ClaudeAgentOptions options) {
        if (!(options.mcpServers() instanceof Map<?, ?> serverMap) || serverMap.isEmpty()) {
            return null;
        }

        Map<String, McpMessageHandler> handlers = new HashMap<>();
        for (Map.Entry<?, ?> entry : serverMap.entrySet()) {
            if (entry.getValue() instanceof McpSdkServerConfig sdkConfig) {
                // The map key, not sdkConfig.name(): the key is what reaches
                // the CLI in --mcp-config and what comes back on mcp_message.
                handlers.put((String) entry.getKey(), sdkConfig.instance());
            }
        }

        return (handlers.isEmpty() ? null : handlers);
    }

}

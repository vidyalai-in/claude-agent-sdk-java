package in.vidyalai.claude.sdk.types.mcp;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Stdio-based MCP server configuration.
 *
 * @param command the command to run
 * @param args    command arguments
 * @param env     environment variables
 */
public record McpStdioServerConfig(
        @JsonProperty("command") String command,
        @JsonProperty("args") @Nullable List<String> args,
        @JsonProperty("env") @Nullable Map<String, String> env) implements McpServerConfig {

    @Override
    public String type() {
        return "stdio";
    }

    /**
     * Creates a stdio config with just a command.
     */
    public McpStdioServerConfig(String command) {
        this(command, null, null);
    }

    /**
     * Creates a stdio config with command and args.
     */
    public McpStdioServerConfig(String command, List<String> args) {
        this(command, args, null);
    }

}

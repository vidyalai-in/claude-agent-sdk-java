package in.vidyalai.claude.sdk.types.config;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Agent definition configuration for custom agents.
 *
 * @param description description of the agent
 * @param prompt      the system prompt for the agent
 * @param tools       list of tools the agent can use (null means inherit from
 *                    parent)
 * @param model       model to use ("sonnet", "opus", "haiku", or "inherit") or
 *                    null
 * @param skills      list of skill names available to the agent (null means
 *                    inherit)
 * @param memory      memory scope (null means inherit)
 * @param mcpServers  list of MCP server references — each entry is either a
 *                    server name ({@code String}) or an inline config
 *                    ({@code Map<String, Object>}). Null means inherit.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentDefinition(
        @JsonProperty("description") String description,
        @JsonProperty("prompt") String prompt,
        @JsonProperty("tools") @Nullable List<String> tools,
        @JsonProperty("model") @Nullable AIModel model,
        @JsonProperty("skills") @Nullable List<String> skills,
        @JsonProperty("memory") @Nullable MemoryScope memory,
        @JsonProperty("mcpServers") @Nullable List<Object> mcpServers) {

    /**
     * Creates an agent definition with just description and prompt.
     */
    public AgentDefinition(String description, String prompt) {
        this(description, prompt, null, null, null, null, null);
    }

    /**
     * Creates an agent definition with description, prompt, tools, and model
     * (backwards compatible).
     */
    public AgentDefinition(String description, String prompt,
            @Nullable List<String> tools, @Nullable AIModel model) {
        this(description, prompt, tools, model, null, null, null);
    }

}

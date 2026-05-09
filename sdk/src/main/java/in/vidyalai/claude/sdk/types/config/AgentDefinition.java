package in.vidyalai.claude.sdk.types.config;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Agent definition configuration for custom agents.
 *
 * @param description    description of the agent
 * @param prompt         the system prompt for the agent
 * @param tools          list of tools the agent can use (null means inherit from
 *                       parent). <b>Deprecated:</b> passing {@code "Skill"} here
 *                       is deprecated; use {@link #skills} instead, which
 *                       configures everything needed (including allowing the
 *                       {@code Skill} tool).
 * @param disallowedTools list of tools the agent cannot use (null means none)
 * @param model          model alias ("sonnet", "opus", "haiku", "inherit") or a
 *                       full model ID, or null
 * @param skills         list of skill names available to the agent (null means
 *                       inherit)
 * @param memory         memory scope (null means inherit)
 * @param mcpServers     list of MCP server references — each entry is either a
 *                       server name ({@code String}) or an inline config
 *                       ({@code Map<String, Object>}). Null means inherit.
 * @param initialPrompt  initial prompt to send when the agent starts (null means
 *                       none)
 * @param maxTurns       maximum number of turns for the agent (null means
 *                       unlimited)
 * @param background     whether to run the agent in the background (null means
 *                       false)
 * @param effort         effort level for the agent ("low", "medium", "high",
 *                       "xhigh", "max") or null. "xhigh" is an Opus 4.7-specific
 *                       level that falls back to "high" on other models.
 * @param permissionMode permission mode for the agent (null means inherit)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentDefinition(
        @JsonProperty("description") String description,
        @JsonProperty("prompt") String prompt,
        @JsonProperty("tools") @Nullable List<String> tools,
        @JsonProperty("disallowedTools") @Nullable List<String> disallowedTools,
        @JsonProperty("model") @Nullable String model,
        @JsonProperty("skills") @Nullable List<String> skills,
        @JsonProperty("memory") @Nullable MemoryScope memory,
        @JsonProperty("mcpServers") @Nullable List<Object> mcpServers,
        @JsonProperty("initialPrompt") @Nullable String initialPrompt,
        @JsonProperty("maxTurns") @Nullable Integer maxTurns,
        @JsonProperty("background") @Nullable Boolean background,
        @JsonProperty("effort") @Nullable String effort,
        @JsonProperty("permissionMode") @Nullable String permissionMode) {

    /**
     * Creates an agent definition with just description and prompt.
     */
    public AgentDefinition(String description, String prompt) {
        this(description, prompt, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Creates an agent definition with description, prompt, tools, and model
     * (backwards compatible).
     */
    public AgentDefinition(String description, String prompt,
            @Nullable List<String> tools, @Nullable String model) {
        this(description, prompt, tools, null, model, null, null, null, null, null, null, null, null);
    }

}

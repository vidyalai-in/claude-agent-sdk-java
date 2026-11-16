package in.vidyalai.claude.sdk.types.config;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
 *                       level that falls back to "high" on other models. Null
 *                       when the effort is numeric; see {@code numericEffort}.
 * @param permissionMode permission mode for the agent (null means inherit)
 * @param numericEffort  effort as a number rather than a level name, sent as
 *                       a JSON number under the same {@code "effort"} key (the
 *                       Python SDK's {@code effort: EffortLevel | int}). At
 *                       most one of {@code effort} and {@code numericEffort}
 *                       may be set; {@link #withEffort(int)} sets this one.
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
        @JsonIgnore @Nullable String effort,
        @JsonProperty("permissionMode") @Nullable String permissionMode,
        @JsonIgnore @Nullable Integer numericEffort) {

    /**
     * Validates that the effort is given at most one way.
     *
     * @throws IllegalArgumentException if both {@code effort} and
     *                                  {@code numericEffort} are set
     */
    public AgentDefinition {
        if ((effort != null) && (numericEffort != null)) {
            throw new IllegalArgumentException(
                    "AgentDefinition takes effort as a level name or as a number, not both: effort="
                            + effort + ", numericEffort=" + numericEffort);
        }
    }

    /**
     * Creates an agent definition with a level-name (or no) effort.
     *
     * <p>
     * The constructor from before {@code numericEffort} was added; it leaves
     * {@code numericEffort} null.
     */
    public AgentDefinition(String description, String prompt,
            @Nullable List<String> tools, @Nullable List<String> disallowedTools,
            @Nullable String model, @Nullable List<String> skills, @Nullable MemoryScope memory,
            @Nullable List<Object> mcpServers, @Nullable String initialPrompt,
            @Nullable Integer maxTurns, @Nullable Boolean background, @Nullable String effort,
            @Nullable String permissionMode) {
        this(description, prompt, tools, disallowedTools, model, skills, memory, mcpServers,
                initialPrompt, maxTurns, background, effort, permissionMode, null);
    }

    /**
     * Creates an agent definition with just description and prompt.
     */
    public AgentDefinition(String description, String prompt) {
        this(description, prompt, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Creates an agent definition with description, prompt, tools, and model
     * (backwards compatible).
     */
    public AgentDefinition(String description, String prompt,
            @Nullable List<String> tools, @Nullable String model) {
        this(description, prompt, tools, null, model, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Returns a copy whose effort is the given level name ({@code "low"},
     * {@code "medium"}, {@code "high"}, {@code "xhigh"}, {@code "max"}), or no
     * effort for null. Clears any numeric effort.
     *
     * @param effort the effort level name, or null
     * @return a copy of this definition with the effort replaced
     */
    public AgentDefinition withEffort(@Nullable String effort) {
        return new AgentDefinition(description, prompt, tools, disallowedTools, model, skills, memory,
                mcpServers, initialPrompt, maxTurns, background, effort, permissionMode, null);
    }

    /**
     * Returns a copy whose effort is the given level.
     *
     * @param effort the effort level
     * @return a copy of this definition with the effort replaced
     */
    public AgentDefinition withEffort(EffortLevel effort) {
        return withEffort(effort.getValue());
    }

    /**
     * Returns a copy whose effort is a number, sent to the CLI as a JSON
     * number (e.g. {@code "effort": 32000}). Clears any level-name effort, so
     * {@link #effort()} returns null on the copy.
     *
     * @param effort the numeric effort
     * @return a copy of this definition with the effort replaced
     */
    public AgentDefinition withEffort(int effort) {
        return new AgentDefinition(description, prompt, tools, disallowedTools, model, skills, memory,
                mcpServers, initialPrompt, maxTurns, background, null, permissionMode, effort);
    }

    /**
     * The {@code "effort"} value on the wire: the level name, the number, or
     * absent when neither is set.
     */
    @JsonProperty("effort")
    @Nullable
    Object effortForJson() {
        return (numericEffort != null) ? numericEffort : effort;
    }

}

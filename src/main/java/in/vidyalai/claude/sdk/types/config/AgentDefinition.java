package in.vidyalai.claude.sdk.types.config;

import java.util.List;

import org.jspecify.annotations.Nullable;

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
 */
public record AgentDefinition(
        @JsonProperty("description") String description,
        @JsonProperty("prompt") String prompt,
        @JsonProperty("tools") @Nullable List<String> tools,
        @JsonProperty("model") @Nullable AIModel model) {

    /**
     * Creates an agent definition with just description and prompt.
     */
    public AgentDefinition(String description, String prompt) {
        this(description, prompt, null, null);
    }

}

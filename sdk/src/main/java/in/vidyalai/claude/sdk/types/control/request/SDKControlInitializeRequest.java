package in.vidyalai.claude.sdk.types.control.request;

import in.vidyalai.claude.sdk.types.config.AgentDefinition;
import in.vidyalai.claude.sdk.types.hook.HookEvent;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Request to initialize the control protocol.
 *
 * <p>
 * Part of the SDK Control Protocol for bidirectional communication
 * between the SDK and CLI. This is the first request sent to establish
 * the control protocol connection and register hooks.
 *
 * <p>
 * This request is always sent via stdin, which has no size limits,
 * allowing arbitrarily large agent definitions to be passed without
 * hitting platform-specific command-line argument length limits (ARG_MAX).
 *
 * @param hooks                   Hook configurations for registering callbacks
 * @param agents                  Agent definitions to register (sent via stdin, no size limit)
 * @param excludeDynamicSections  Optional preset-prompt flag to strip per-user dynamic sections
 *                                for cross-user caching (older CLIs ignore unknown fields)
 * @param skills                  Optional skill allowlist sent so the CLI can filter which
 *                                skills are loaded into the system prompt. Only included
 *                                when the caller passed an explicit list (the wire protocol
 *                                treats omission and {@code "all"} as equivalent).
 * @param forwardSubagentText     Ask the CLI to forward subagent text/thinking blocks, not
 *                                just {@code tool_use}/{@code tool_result}. Only included
 *                                when true (omission is the default behavior)
 */
@JsonTypeName("initialize")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SDKControlInitializeRequest(
        @Nullable Map<HookEvent, List<Map<String, Object>>> hooks,
        @Nullable Map<String, AgentDefinition> agents,
        @Nullable Boolean excludeDynamicSections,
        @Nullable List<String> skills,
        @Nullable Boolean forwardSubagentText) implements SDKControlRequestData {

    /**
     * Backwards-compatible constructor without excludeDynamicSections.
     */
    public SDKControlInitializeRequest(
            @Nullable Map<HookEvent, List<Map<String, Object>>> hooks,
            @Nullable Map<String, AgentDefinition> agents) {
        this(hooks, agents, null, null, null);
    }

    /**
     * Backwards-compatible constructor without skills.
     */
    public SDKControlInitializeRequest(
            @Nullable Map<HookEvent, List<Map<String, Object>>> hooks,
            @Nullable Map<String, AgentDefinition> agents,
            @Nullable Boolean excludeDynamicSections) {
        this(hooks, agents, excludeDynamicSections, null, null);
    }

    /**
     * Backwards-compatible constructor without forwardSubagentText.
     */
    public SDKControlInitializeRequest(
            @Nullable Map<HookEvent, List<Map<String, Object>>> hooks,
            @Nullable Map<String, AgentDefinition> agents,
            @Nullable Boolean excludeDynamicSections,
            @Nullable List<String> skills) {
        this(hooks, agents, excludeDynamicSections, skills, null);
    }

    @Override
    public String subtype() {
        return "initialize";
    }

}

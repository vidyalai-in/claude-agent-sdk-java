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
 */
@JsonTypeName("initialize")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SDKControlInitializeRequest(
        @Nullable Map<HookEvent, List<Map<String, Object>>> hooks,
        @Nullable Map<String, AgentDefinition> agents,
        @Nullable Boolean excludeDynamicSections) implements SDKControlRequestData {

    /**
     * Backwards-compatible constructor without excludeDynamicSections.
     */
    public SDKControlInitializeRequest(
            @Nullable Map<HookEvent, List<Map<String, Object>>> hooks,
            @Nullable Map<String, AgentDefinition> agents) {
        this(hooks, agents, null);
    }

    @Override
    public String subtype() {
        return "initialize";
    }

}

package in.vidyalai.claude.sdk.types.hook.input;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

import in.vidyalai.claude.sdk.types.hook.HookEvent;

/**
 * Input for PermissionRequest hook events.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code snake_case} for JSON
 * field names because it represents data <b>received from the CLI</b>. See
 * {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param sessionId             unique identifier for the session
 * @param transcriptPath        path to the conversation transcript
 * @param cwd                   current working directory
 * @param permissionMode        current permission mode (can be null)
 * @param toolName              name of the tool requiring permission
 * @param toolInput             input parameters for the tool
 * @param permissionSuggestions optional list of permission suggestions
 * @param agentId               sub-agent identifier (present only inside a
 *                              task-spawned sub-agent; absent on main thread)
 * @param agentType             agent type name (e.g. "general-purpose");
 *                              present inside a sub-agent or on main thread
 *                              when started with --agent
 * @param promptId              UUID of the user prompt being processed. Matches
 *                              the {@code prompt.id} attribute on OpenTelemetry
 *                              events. Absent until the first user input
 * @param effort                effort in effect for the current turn. Present
 *                              for hooks that fire within a tool-use context
 *                              (PreToolUse, PostToolUse, Stop, SubagentStop,
 *                              etc.) on models that support effort
 */
public record PermissionRequestHookInput(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("transcript_path") String transcriptPath,
        @JsonProperty("cwd") String cwd,
        @JsonProperty("permission_mode") @Nullable String permissionMode,
        @JsonProperty("tool_name") String toolName,
        @JsonProperty("tool_input") Map<String, Object> toolInput,
        @JsonProperty("permission_suggestions") @Nullable List<Object> permissionSuggestions,
        @JsonProperty("agent_id") @Nullable String agentId,
        @JsonProperty("agent_type") @Nullable String agentType,
        @JsonProperty("prompt_id") @Nullable String promptId,
        @JsonProperty("effort") @Nullable HookEffort effort) implements HookInput {

    /**
     * Backwards-compatible constructor without {@code promptId},
     * {@code effort}.
     */
    public PermissionRequestHookInput(String sessionId, String transcriptPath,
            String cwd, @Nullable String permissionMode, String toolName,
            Map<String, Object> toolInput, @Nullable List<Object> permissionSuggestions,
            @Nullable String agentId, @Nullable String agentType) {
        this(sessionId, transcriptPath, cwd, permissionMode, toolName, toolInput,
                permissionSuggestions, agentId, agentType, null, null);
    }

    @JsonProperty("hook_event_name")
    @Override
    public String hookEventName() {
        return HookEvent.PERMISSION_REQUEST.getValue();
    }

}

package in.vidyalai.claude.sdk.types.hook.input;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JacksonInject;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.OptBoolean;

import in.vidyalai.claude.sdk.types.hook.HookEvent;

/**
 * Input for PostToolUseFailure hook events.
 *
 * <p>
 * Triggered when a tool use fails, providing information about the failure.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code snake_case} for JSON
 * field names because it represents data <b>received from the CLI</b>. See
 * {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param sessionId      unique identifier for the session
 * @param transcriptPath path to the conversation transcript
 * @param cwd            current working directory
 * @param permissionMode current permission mode (can be null)
 * @param toolName       name of the tool that failed
 * @param toolInput      input parameters that were passed to the tool
 * @param toolUseId      unique identifier for this tool use
 * @param error          error message describing the failure
 * @param isInterrupt    whether the failure was due to an interrupt (can be
 *                       null)
 * @param agentId        sub-agent identifier (present only inside a
 *                       task-spawned sub-agent; absent on main thread)
 * @param agentType      agent type name (e.g. "general-purpose"); present
 *                       inside a sub-agent or on main thread when started with
 *                       --agent
 * @param durationMs     tool execution time in milliseconds. Excludes
 *                       permission-prompt and hook time
 * @param promptId       UUID of the user prompt being processed. Matches the
 *                       {@code prompt.id} attribute on OpenTelemetry events.
 *                       Absent until the first user input
 * @param effort         effort in effect for the current turn. Present for
 *                       hooks that fire within a tool-use context (PreToolUse,
 *                       PostToolUse, Stop, SubagentStop, etc.) on models that
 *                       support effort
 * @param raw the CLI's original hook input, every key included (also the
 *            ones this record does not model); empty when constructed
 *            directly
 */
public record PostToolUseFailureHookInput(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("transcript_path") String transcriptPath,
        @JsonProperty("cwd") String cwd,
        @JsonProperty("permission_mode") @Nullable String permissionMode,
        @JsonProperty("tool_name") String toolName,
        @JsonProperty("tool_input") Map<String, Object> toolInput,
        @JsonProperty("tool_use_id") String toolUseId,
        @JsonProperty("error") String error,
        @JsonProperty("is_interrupt") @Nullable Boolean isInterrupt,
        @JsonProperty("agent_id") @Nullable String agentId,
        @JsonProperty("agent_type") @Nullable String agentType,
        @JsonProperty("duration_ms") @Nullable Integer durationMs,
        @JsonProperty("prompt_id") @Nullable String promptId,
        @JsonProperty("effort") @Nullable HookEffort effort,
        @JsonIgnore @JacksonInject(value = HookInput.RAW_INJECTABLE_ID, useInput = OptBoolean.FALSE,
                optional = OptBoolean.TRUE) Map<String, Object> raw) implements HookInput {

    /**
     * Copies {@code raw} (the CLI's original input) into an unmodifiable
     * map; {@code null} reads as empty.
     */
    public PostToolUseFailureHookInput {
        raw = HookInputRaw.copy(raw);
    }

    /**
     * Constructor without {@code raw}: {@link #raw()} is empty.
     */
    public PostToolUseFailureHookInput(String sessionId,
            String transcriptPath,
            String cwd,
            @Nullable String permissionMode,
            String toolName,
            Map<String, Object> toolInput,
            String toolUseId,
            String error,
            @Nullable Boolean isInterrupt,
            @Nullable String agentId,
            @Nullable String agentType,
            @Nullable Integer durationMs,
            @Nullable String promptId,
            @Nullable HookEffort effort) {
        this(sessionId, transcriptPath, cwd, permissionMode, toolName, toolInput, toolUseId,
                error, isInterrupt, agentId, agentType, durationMs, promptId, effort,
                null);
    }

    /**
     * Backwards-compatible constructor without {@code durationMs},
     * {@code promptId}, {@code effort}.
     */
    public PostToolUseFailureHookInput(String sessionId, String transcriptPath,
            String cwd, @Nullable String permissionMode, String toolName,
            Map<String, Object> toolInput, String toolUseId, String error,
            @Nullable Boolean isInterrupt, @Nullable String agentId,
            @Nullable String agentType) {
        this(sessionId, transcriptPath, cwd, permissionMode, toolName, toolInput,
                toolUseId, error, isInterrupt, agentId, agentType, null, null, null);
    }

    @JsonProperty("hook_event_name")
    @Override
    public String hookEventName() {
        return HookEvent.POST_TOOL_USE_FAILURE.getValue();
    }

}

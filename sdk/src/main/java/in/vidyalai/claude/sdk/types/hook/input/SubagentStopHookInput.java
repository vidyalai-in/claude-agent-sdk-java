package in.vidyalai.claude.sdk.types.hook.input;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JacksonInject;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.OptBoolean;

import in.vidyalai.claude.sdk.types.hook.HookEvent;

/**
 * Input for SubagentStop hook events.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code snake_case} for JSON
 * field names because it represents data <b>received from the CLI</b>. See
 * {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param sessionId            unique identifier for the session
 * @param transcriptPath       path to the conversation transcript
 * @param cwd                  current working directory
 * @param permissionMode       current permission mode (can be null)
 * @param stopHookActive       whether the stop hook is currently active
 * @param agentId              unique identifier for the subagent
 * @param agentTranscriptPath  path to the subagent's transcript
 * @param agentType            type of the subagent
 * @param lastAssistantMessage text content of the subagent's last assistant
 *                             message before stopping
 * @param backgroundTasks      in-flight background work, scoped to the parent
 *                             session
 * @param sessionCrons         scheduled wakeups, scoped to the parent session
 * @param promptId             UUID of the user prompt being processed. Matches
 *                             the {@code prompt.id} attribute on OpenTelemetry
 *                             events. Absent until the first user input
 * @param effort               effort in effect for the current turn. Present
 *                             for hooks that fire within a tool-use context
 *                             (PreToolUse, PostToolUse, Stop, SubagentStop,
 *                             etc.) on models that support effort
 * @param raw the CLI's original hook input, every key included (also the
 *            ones this record does not model); empty when constructed
 *            directly
 */
public record SubagentStopHookInput(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("transcript_path") String transcriptPath,
        @JsonProperty("cwd") String cwd,
        @JsonProperty("permission_mode") @Nullable String permissionMode,
        @JsonProperty("stop_hook_active") boolean stopHookActive,
        @JsonProperty("agent_id") String agentId,
        @JsonProperty("agent_transcript_path") String agentTranscriptPath,
        @JsonProperty("agent_type") String agentType,
        @JsonProperty("last_assistant_message") @Nullable String lastAssistantMessage,
        @JsonProperty("background_tasks") @Nullable List<BackgroundTaskSummary> backgroundTasks,
        @JsonProperty("session_crons") @Nullable List<SessionCronSummary> sessionCrons,
        @JsonProperty("prompt_id") @Nullable String promptId,
        @JsonProperty("effort") @Nullable HookEffort effort,
        @JsonIgnore @JacksonInject(value = HookInput.RAW_INJECTABLE_ID, useInput = OptBoolean.FALSE,
                optional = OptBoolean.TRUE) Map<String, Object> raw) implements HookInput {

    /**
     * Copies {@code raw} (the CLI's original input) into an unmodifiable
     * map; {@code null} reads as empty.
     */
    public SubagentStopHookInput {
        raw = HookInputRaw.copy(raw);
    }

    /**
     * Constructor without {@code raw}: {@link #raw()} is empty.
     */
    public SubagentStopHookInput(String sessionId,
            String transcriptPath,
            String cwd,
            @Nullable String permissionMode,
            boolean stopHookActive,
            String agentId,
            String agentTranscriptPath,
            String agentType,
            @Nullable String lastAssistantMessage,
            @Nullable List<BackgroundTaskSummary> backgroundTasks,
            @Nullable List<SessionCronSummary> sessionCrons,
            @Nullable String promptId,
            @Nullable HookEffort effort) {
        this(sessionId, transcriptPath, cwd, permissionMode, stopHookActive, agentId,
                agentTranscriptPath, agentType, lastAssistantMessage, backgroundTasks, sessionCrons,
                promptId, effort,
                null);
    }

    /**
     * Backwards-compatible constructor without {@code lastAssistantMessage},
     * {@code backgroundTasks}, {@code sessionCrons}, {@code promptId},
     * {@code effort}.
     */
    public SubagentStopHookInput(String sessionId, String transcriptPath, String cwd,
            @Nullable String permissionMode, boolean stopHookActive,
            String agentId, String agentTranscriptPath, String agentType) {
        this(sessionId, transcriptPath, cwd, permissionMode, stopHookActive,
                agentId, agentTranscriptPath, agentType, null, null, null, null, null);
    }

    @JsonProperty("hook_event_name")
    @Override
    public String hookEventName() {
        return HookEvent.SUBAGENT_STOP.getValue();
    }

}

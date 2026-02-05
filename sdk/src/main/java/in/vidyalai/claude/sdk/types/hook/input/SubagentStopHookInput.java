package in.vidyalai.claude.sdk.types.hook.input;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

import in.vidyalai.claude.sdk.types.hook.HookEvent;

/**
 * Input for SubagentStop hook events.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code snake_case} for JSON
 * field names because it represents data <b>received from the CLI</b>. See
 * {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param sessionId           unique identifier for the session
 * @param transcriptPath      path to the conversation transcript
 * @param cwd                 current working directory
 * @param permissionMode      current permission mode (can be null)
 * @param stopHookActive      whether the stop hook is currently active
 * @param agentId             unique identifier for the subagent
 * @param agentTranscriptPath path to the subagent's transcript
 * @param agentType           type of the subagent
 */
public record SubagentStopHookInput(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("transcript_path") String transcriptPath,
        @JsonProperty("cwd") String cwd,
        @JsonProperty("permission_mode") @Nullable String permissionMode,
        @JsonProperty("stop_hook_active") boolean stopHookActive,
        @JsonProperty("agent_id") String agentId,
        @JsonProperty("agent_transcript_path") String agentTranscriptPath,
        @JsonProperty("agent_type") String agentType) implements HookInput {

    @JsonProperty("hook_event_name")
    @Override
    public String hookEventName() {
        return HookEvent.SUBAGENT_STOP.getValue();
    }

}

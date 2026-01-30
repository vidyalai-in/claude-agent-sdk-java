package in.vidyalai.claude.sdk.types.hook;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Input for SubagentStop hook events.
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
 * @param stopHookActive whether the stop hook is currently active
 */
public record SubagentStopHookInput(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("transcript_path") String transcriptPath,
        @JsonProperty("cwd") String cwd,
        @JsonProperty("permission_mode") @Nullable String permissionMode,
        @JsonProperty("stop_hook_active") boolean stopHookActive) implements HookInput {

    @JsonProperty("hook_event_name")
    @Override
    public String hookEventName() {
        return HookEvent.SUBAGENT_STOP.getValue();
    }

}

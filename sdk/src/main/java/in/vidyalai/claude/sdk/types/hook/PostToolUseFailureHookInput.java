package in.vidyalai.claude.sdk.types.hook;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

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
        @JsonProperty("is_interrupt") @Nullable Boolean isInterrupt) implements HookInput {

    @JsonProperty("hook_event_name")
    @Override
    public String hookEventName() {
        return HookEvent.POST_TOOL_USE_FAILURE.getValue();
    }

}

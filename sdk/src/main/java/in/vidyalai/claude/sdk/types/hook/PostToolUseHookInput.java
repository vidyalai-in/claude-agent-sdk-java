package in.vidyalai.claude.sdk.types.hook;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Input for PostToolUse hook events.
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
 * @param toolName       name of the tool that was invoked
 * @param toolInput      input parameters that were passed to the tool
 * @param toolResponse   response returned by the tool
 */
public record PostToolUseHookInput(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("transcript_path") String transcriptPath,
        @JsonProperty("cwd") String cwd,
        @JsonProperty("permission_mode") @Nullable String permissionMode,
        @JsonProperty("tool_name") String toolName,
        @JsonProperty("tool_input") Map<String, Object> toolInput,
        @JsonProperty("tool_response") Object toolResponse) implements HookInput {

    @JsonProperty("hook_event_name")
    @Override
    public String hookEventName() {
        return HookEvent.POST_TOOL_USE.getValue();
    }

}

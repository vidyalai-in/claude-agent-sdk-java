package in.vidyalai.claude.sdk.types.hook;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Input for UserPromptSubmit hook events.
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
 * @param prompt         user-submitted prompt text
 */
public record UserPromptSubmitHookInput(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("transcript_path") String transcriptPath,
        @JsonProperty("cwd") String cwd,
        @JsonProperty("permission_mode") @Nullable String permissionMode,
        @JsonProperty("prompt") String prompt) implements HookInput {

    @JsonProperty("hook_event_name")
    @Override
    public String hookEventName() {
        return HookEvent.USER_PROMPT_SUBMIT.getValue();
    }

}

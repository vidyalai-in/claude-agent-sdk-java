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
 */
public record PermissionRequestHookInput(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("transcript_path") String transcriptPath,
        @JsonProperty("cwd") String cwd,
        @JsonProperty("permission_mode") @Nullable String permissionMode,
        @JsonProperty("tool_name") String toolName,
        @JsonProperty("tool_input") Map<String, Object> toolInput,
        @JsonProperty("permission_suggestions") @Nullable List<Object> permissionSuggestions) implements HookInput {

    @JsonProperty("hook_event_name")
    @Override
    public String hookEventName() {
        return HookEvent.PERMISSION_REQUEST.getValue();
    }

}

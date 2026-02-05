package in.vidyalai.claude.sdk.types.hook.input;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

import in.vidyalai.claude.sdk.types.hook.HookEvent;

/**
 * Input for Notification hook events.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code snake_case} for JSON
 * field names because it represents data <b>received from the CLI</b>. See
 * {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param sessionId        unique identifier for the session
 * @param transcriptPath   path to the conversation transcript
 * @param cwd              current working directory
 * @param permissionMode   current permission mode (can be null)
 * @param message          the notification message
 * @param title            optional notification title
 * @param notificationType type of notification
 */
public record NotificationHookInput(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("transcript_path") String transcriptPath,
        @JsonProperty("cwd") String cwd,
        @JsonProperty("permission_mode") @Nullable String permissionMode,
        @JsonProperty("message") String message,
        @JsonProperty("title") @Nullable String title,
        @JsonProperty("notification_type") String notificationType) implements HookInput {

    @JsonProperty("hook_event_name")
    @Override
    public String hookEventName() {
        return HookEvent.NOTIFICATION.getValue();
    }

}

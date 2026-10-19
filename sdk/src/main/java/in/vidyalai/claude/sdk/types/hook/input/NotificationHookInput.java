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
 * @param promptId         UUID of the user prompt being processed. Matches the
 *                         {@code prompt.id} attribute on OpenTelemetry events.
 *                         Absent until the first user input
 * @param effort           effort in effect for the current turn. Present for
 *                         hooks that fire within a tool-use context
 *                         (PreToolUse, PostToolUse, Stop, SubagentStop, etc.)
 *                         on models that support effort
 */
public record NotificationHookInput(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("transcript_path") String transcriptPath,
        @JsonProperty("cwd") String cwd,
        @JsonProperty("permission_mode") @Nullable String permissionMode,
        @JsonProperty("message") String message,
        @JsonProperty("title") @Nullable String title,
        @JsonProperty("notification_type") String notificationType,
        @JsonProperty("prompt_id") @Nullable String promptId,
        @JsonProperty("effort") @Nullable HookEffort effort) implements HookInput {

    /**
     * Backwards-compatible constructor without {@code promptId},
     * {@code effort}.
     */
    public NotificationHookInput(String sessionId, String transcriptPath, String cwd,
            @Nullable String permissionMode, String message,
            @Nullable String title, String notificationType) {
        this(sessionId, transcriptPath, cwd, permissionMode, message, title,
                notificationType, null, null);
    }

    @JsonProperty("hook_event_name")
    @Override
    public String hookEventName() {
        return HookEvent.NOTIFICATION.getValue();
    }

}

package in.vidyalai.claude.sdk.types.hook.input;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JacksonInject;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.OptBoolean;

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
 * @param raw the CLI's original hook input, every key included (also the
 *            ones this record does not model); empty when constructed
 *            directly
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
        @JsonProperty("effort") @Nullable HookEffort effort,
        @JsonIgnore @JacksonInject(value = HookInput.RAW_INJECTABLE_ID, useInput = OptBoolean.FALSE,
                optional = OptBoolean.TRUE) Map<String, Object> raw) implements HookInput {

    /**
     * Copies {@code raw} (the CLI's original input) into an unmodifiable
     * map; {@code null} reads as empty.
     */
    public NotificationHookInput {
        raw = HookInputRaw.copy(raw);
    }

    /**
     * Constructor without {@code raw}: {@link #raw()} is empty.
     */
    public NotificationHookInput(String sessionId,
            String transcriptPath,
            String cwd,
            @Nullable String permissionMode,
            String message,
            @Nullable String title,
            String notificationType,
            @Nullable String promptId,
            @Nullable HookEffort effort) {
        this(sessionId, transcriptPath, cwd, permissionMode, message, title, notificationType,
                promptId, effort,
                null);
    }

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

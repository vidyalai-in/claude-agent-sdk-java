package in.vidyalai.claude.sdk.types.hook.input;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

import in.vidyalai.claude.sdk.types.config.CompactTriggerType;
import in.vidyalai.claude.sdk.types.hook.HookEvent;

/**
 * Input for PreCompact hook events.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code snake_case} for JSON
 * field names because it represents data <b>received from the CLI</b>. See
 * {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param sessionId          unique identifier for the session
 * @param transcriptPath     path to the conversation transcript
 * @param cwd                current working directory
 * @param permissionMode     current permission mode (can be null)
 * @param trigger            what triggered the compaction
 * @param customInstructions optional custom instructions for compaction (can be
 *                           null)
 * @param promptId           UUID of the user prompt being processed. Matches
 *                           the {@code prompt.id} attribute on OpenTelemetry
 *                           events. Absent until the first user input
 * @param effort             effort in effect for the current turn. Present for
 *                           hooks that fire within a tool-use context
 *                           (PreToolUse, PostToolUse, Stop, SubagentStop, etc.)
 *                           on models that support effort
 */
public record PreCompactHookInput(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("transcript_path") String transcriptPath,
        @JsonProperty("cwd") String cwd,
        @JsonProperty("permission_mode") @Nullable String permissionMode,
        @JsonProperty("trigger") CompactTriggerType trigger,
        @JsonProperty("custom_instructions") @Nullable String customInstructions,
        @JsonProperty("prompt_id") @Nullable String promptId,
        @JsonProperty("effort") @Nullable HookEffort effort) implements HookInput {

    /**
     * Backwards-compatible constructor without {@code promptId},
     * {@code effort}.
     */
    public PreCompactHookInput(String sessionId, String transcriptPath, String cwd,
            @Nullable String permissionMode, CompactTriggerType trigger,
            @Nullable String customInstructions) {
        this(sessionId, transcriptPath, cwd, permissionMode, trigger,
                customInstructions, null, null);
    }

    @JsonProperty("hook_event_name")
    @Override
    public String hookEventName() {
        return HookEvent.PRE_COMPACT.getValue();
    }

}

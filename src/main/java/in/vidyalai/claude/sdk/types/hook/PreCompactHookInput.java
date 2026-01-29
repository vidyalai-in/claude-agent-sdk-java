package in.vidyalai.claude.sdk.types.hook;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

import in.vidyalai.claude.sdk.types.config.CompactTriggerType;

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
 */
public record PreCompactHookInput(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("transcript_path") String transcriptPath,
        @JsonProperty("cwd") String cwd,
        @JsonProperty("permission_mode") @Nullable String permissionMode,
        @JsonProperty("trigger") CompactTriggerType trigger,
        @JsonProperty("custom_instructions") @Nullable String customInstructions) implements HookInput {

    @JsonProperty("hook_event_name")
    @Override
    public String hookEventName() {
        return HookEvent.PRE_COMPACT.getValue();
    }

}

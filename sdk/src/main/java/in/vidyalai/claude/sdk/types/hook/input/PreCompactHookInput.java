package in.vidyalai.claude.sdk.types.hook.input;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JacksonInject;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.OptBoolean;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

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
 * @param trigger            what triggered the compaction; null when the CLI
 *                           sends a value this SDK does not know (read it
 *                           from {@link #raw()})
 * @param customInstructions optional custom instructions for compaction (can be
 *                           null)
 * @param promptId           UUID of the user prompt being processed. Matches
 *                           the {@code prompt.id} attribute on OpenTelemetry
 *                           events. Absent until the first user input
 * @param effort             effort in effect for the current turn. Present for
 *                           hooks that fire within a tool-use context
 *                           (PreToolUse, PostToolUse, Stop, SubagentStop, etc.)
 *                           on models that support effort
 * @param raw the CLI's original hook input, every key included (also the
 *            ones this record does not model); empty when constructed
 *            directly
 */
public record PreCompactHookInput(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("transcript_path") String transcriptPath,
        @JsonProperty("cwd") String cwd,
        @JsonProperty("permission_mode") @Nullable String permissionMode,
        @JsonProperty("trigger") @JsonDeserialize(using = LenientCompactTriggerDeserializer.class)
        @Nullable CompactTriggerType trigger,
        @JsonProperty("custom_instructions") @Nullable String customInstructions,
        @JsonProperty("prompt_id") @Nullable String promptId,
        @JsonProperty("effort") @Nullable HookEffort effort,
        @JsonIgnore @JacksonInject(value = HookInput.RAW_INJECTABLE_ID, useInput = OptBoolean.FALSE,
                optional = OptBoolean.TRUE) Map<String, Object> raw) implements HookInput {

    /**
     * Copies {@code raw} (the CLI's original input) into an unmodifiable
     * map; {@code null} reads as empty.
     */
    public PreCompactHookInput {
        raw = HookInputRaw.copy(raw);
    }

    /**
     * Constructor without {@code raw}: {@link #raw()} is empty.
     */
    public PreCompactHookInput(String sessionId,
            String transcriptPath,
            String cwd,
            @Nullable String permissionMode,
            @Nullable CompactTriggerType trigger,
            @Nullable String customInstructions,
            @Nullable String promptId,
            @Nullable HookEffort effort) {
        this(sessionId, transcriptPath, cwd, permissionMode, trigger, customInstructions,
                promptId, effort,
                null);
    }

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

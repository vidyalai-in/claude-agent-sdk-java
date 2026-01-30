package in.vidyalai.claude.sdk.types.hook;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed interface for hook input types.
 *
 * <p>
 * Different hook events receive different input types:
 * <ul>
 * <li>{@link PreToolUseHookInput} - Before a tool is used</li>
 * <li>{@link PostToolUseHookInput} - After a tool is used</li>
 * <li>{@link PostToolUseFailureHookInput} - After a tool use fails</li>
 * <li>{@link UserPromptSubmitHookInput} - When user submits a prompt</li>
 * <li>{@link StopHookInput} - When the session stops</li>
 * <li>{@link SubagentStopHookInput} - When a subagent stops</li>
 * <li>{@link PreCompactHookInput} - Before context compaction</li>
 * </ul>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "hook_event_name")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PreToolUseHookInput.class, name = "PreToolUse"),
        @JsonSubTypes.Type(value = PostToolUseHookInput.class, name = "PostToolUse"),
        @JsonSubTypes.Type(value = PostToolUseFailureHookInput.class, name = "PostToolUseFailure"),
        @JsonSubTypes.Type(value = UserPromptSubmitHookInput.class, name = "UserPromptSubmit"),
        @JsonSubTypes.Type(value = StopHookInput.class, name = "Stop"),
        @JsonSubTypes.Type(value = SubagentStopHookInput.class, name = "SubagentStop"),
        @JsonSubTypes.Type(value = PreCompactHookInput.class, name = "PreCompact")
})
public sealed interface HookInput permits
        PreToolUseHookInput,
        PostToolUseHookInput,
        PostToolUseFailureHookInput,
        UserPromptSubmitHookInput,
        StopHookInput,
        SubagentStopHookInput,
        PreCompactHookInput {

    /**
     * The hook event name.
     *
     * @return the hook event name
     */
    String hookEventName();

    /**
     * Session identifier.
     *
     * @return the session ID
     */
    String sessionId();

    /**
     * Path to the transcript file.
     *
     * @return the transcript file path
     */
    String transcriptPath();

    /**
     * Current working directory.
     *
     * @return the current working directory
     */
    String cwd();

    /**
     * Current permission mode.
     *
     * @return the permission mode, or null if not set
     */
    @Nullable
    String permissionMode();

}

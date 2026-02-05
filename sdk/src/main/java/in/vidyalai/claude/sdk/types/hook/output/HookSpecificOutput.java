package in.vidyalai.claude.sdk.types.hook.output;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed interface for hook-specific output types.
 *
 * <p>
 * Different hook events can return different output types:
 * <ul>
 * <li>{@link PreToolUseHookSpecificOutput} - Output for PreToolUse hooks</li>
 * <li>{@link PostToolUseHookSpecificOutput} - Output for PostToolUse hooks</li>
 * <li>{@link PostToolUseFailureHookSpecificOutput} - Output for
 * PostToolUseFailure hooks</li>
 * <li>{@link UserPromptSubmitHookSpecificOutput} - Output for UserPromptSubmit
 * hooks</li>
 * <li>{@link NotificationHookSpecificOutput} - Output for Notification
 * hooks</li>
 * <li>{@link SubagentStartHookSpecificOutput} - Output for SubagentStart
 * hooks</li>
 * <li>{@link PermissionRequestHookSpecificOutput} - Output for
 * PermissionRequest hooks</li>
 * </ul>
 *
 * <p>
 * <b>JSON Naming Convention:</b> Hook output types use {@code camelCase} for
 * JSON field names because they represent data <b>sent to the CLI</b> in hook
 * responses.
 * See {@link in.vidyalai.claude.sdk.types} package documentation for details.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "hookEventName")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PreToolUseHookSpecificOutput.class, name = "PreToolUse"),
        @JsonSubTypes.Type(value = PostToolUseHookSpecificOutput.class, name = "PostToolUse"),
        @JsonSubTypes.Type(value = PostToolUseFailureHookSpecificOutput.class, name = "PostToolUseFailure"),
        @JsonSubTypes.Type(value = UserPromptSubmitHookSpecificOutput.class, name = "UserPromptSubmit"),
        @JsonSubTypes.Type(value = NotificationHookSpecificOutput.class, name = "Notification"),
        @JsonSubTypes.Type(value = SubagentStartHookSpecificOutput.class, name = "SubagentStart"),
        @JsonSubTypes.Type(value = PermissionRequestHookSpecificOutput.class, name = "PermissionRequest")
})
public sealed interface HookSpecificOutput permits
        PreToolUseHookSpecificOutput,
        PostToolUseHookSpecificOutput,
        PostToolUseFailureHookSpecificOutput,
        UserPromptSubmitHookSpecificOutput,
        NotificationHookSpecificOutput,
        SubagentStartHookSpecificOutput,
        PermissionRequestHookSpecificOutput {

    // JSON serialization field names
    String HOOK_EVENT_NAME = "hookEventName";
    String PERMISSION_DECISION = "permissionDecision";
    String PERMISSION_DECISION_REASON = "permissionDecisionReason";
    String UPDATED_INPUT = "updatedInput";
    String ADDITIONAL_CONTEXT = "additionalContext";
    String UPDATED_MCP_TOOL_OUTPUT = "updatedMCPToolOutput";
    String DECISION = "decision";

    /**
     * The hook event name.
     *
     * @return the hook event name
     */
    String hookEventName();

    /**
     * Converts this hook-specific output to a Map for JSON serialization.
     *
     * @return a map representation of this hook-specific output
     */
    java.util.Map<String, Object> toMap();

}

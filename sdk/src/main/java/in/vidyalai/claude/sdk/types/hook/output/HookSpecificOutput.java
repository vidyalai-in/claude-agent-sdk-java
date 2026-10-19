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
 * <li>{@link SessionStartHookSpecificOutput} - Output for SessionStart hooks</li>
 * <li>{@link UserPromptExpansionHookSpecificOutput} - Output for UserPromptExpansion hooks</li>
 * <li>{@link PostToolBatchHookSpecificOutput} - Output for PostToolBatch hooks</li>
 * <li>{@link StopHookSpecificOutput} - Output for Stop hooks</li>
 * <li>{@link SubagentStopHookSpecificOutput} - Output for SubagentStop hooks</li>
 * <li>{@link PermissionDeniedHookSpecificOutput} - Output for PermissionDenied hooks</li>
 * <li>{@link PreModelSwitchHookSpecificOutput} - Output for PreModelSwitch hooks</li>
 * <li>{@link PostModelSwitchHookSpecificOutput} - Output for PostModelSwitch hooks</li>
 * <li>{@link ElicitationHookSpecificOutput} - Output for Elicitation hooks</li>
 * <li>{@link ElicitationResultHookSpecificOutput} - Output for ElicitationResult hooks</li>
 * <li>{@link CwdChangedHookSpecificOutput} - Output for CwdChanged hooks</li>
 * <li>{@link FileChangedHookSpecificOutput} - Output for FileChanged hooks</li>
 * <li>{@link WorktreeCreateHookSpecificOutput} - Output for WorktreeCreate hooks</li>
 * <li>{@link MessageDisplayHookSpecificOutput} - Output for MessageDisplay hooks</li>
 * </ul>
 *
 * <p>
 * Several of these events (SessionStart, UserPromptExpansion, PostToolBatch,
 * PermissionDenied, the model-switch, elicitation, cwd/file-change,
 * WorktreeCreate and MessageDisplay events) are not in {@link
 * in.vidyalai.claude.sdk.types.hook.HookEvent}, so SDK hook callbacks cannot
 * register for them yet. Their outputs are typed for parity with the Python
 * and TypeScript SDKs, which define the same shapes.
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
        @JsonSubTypes.Type(value = PermissionRequestHookSpecificOutput.class, name = "PermissionRequest"),
        @JsonSubTypes.Type(value = SessionStartHookSpecificOutput.class, name = "SessionStart"),
        @JsonSubTypes.Type(value = UserPromptExpansionHookSpecificOutput.class, name = "UserPromptExpansion"),
        @JsonSubTypes.Type(value = PostToolBatchHookSpecificOutput.class, name = "PostToolBatch"),
        @JsonSubTypes.Type(value = StopHookSpecificOutput.class, name = "Stop"),
        @JsonSubTypes.Type(value = SubagentStopHookSpecificOutput.class, name = "SubagentStop"),
        @JsonSubTypes.Type(value = PermissionDeniedHookSpecificOutput.class, name = "PermissionDenied"),
        @JsonSubTypes.Type(value = PreModelSwitchHookSpecificOutput.class, name = "PreModelSwitch"),
        @JsonSubTypes.Type(value = PostModelSwitchHookSpecificOutput.class, name = "PostModelSwitch"),
        @JsonSubTypes.Type(value = ElicitationHookSpecificOutput.class, name = "Elicitation"),
        @JsonSubTypes.Type(value = ElicitationResultHookSpecificOutput.class, name = "ElicitationResult"),
        @JsonSubTypes.Type(value = CwdChangedHookSpecificOutput.class, name = "CwdChanged"),
        @JsonSubTypes.Type(value = FileChangedHookSpecificOutput.class, name = "FileChanged"),
        @JsonSubTypes.Type(value = WorktreeCreateHookSpecificOutput.class, name = "WorktreeCreate"),
        @JsonSubTypes.Type(value = MessageDisplayHookSpecificOutput.class, name = "MessageDisplay")
})
public sealed interface HookSpecificOutput permits
        PreToolUseHookSpecificOutput,
        PostToolUseHookSpecificOutput,
        PostToolUseFailureHookSpecificOutput,
        UserPromptSubmitHookSpecificOutput,
        NotificationHookSpecificOutput,
        SubagentStartHookSpecificOutput,
        PermissionRequestHookSpecificOutput,
        SessionStartHookSpecificOutput,
        UserPromptExpansionHookSpecificOutput,
        PostToolBatchHookSpecificOutput,
        StopHookSpecificOutput,
        SubagentStopHookSpecificOutput,
        PermissionDeniedHookSpecificOutput,
        PreModelSwitchHookSpecificOutput,
        PostModelSwitchHookSpecificOutput,
        ElicitationHookSpecificOutput,
        ElicitationResultHookSpecificOutput,
        CwdChangedHookSpecificOutput,
        FileChangedHookSpecificOutput,
        WorktreeCreateHookSpecificOutput,
        MessageDisplayHookSpecificOutput {

    // JSON serialization field names
    String HOOK_EVENT_NAME = "hookEventName";
    String PERMISSION_DECISION = "permissionDecision";
    String PERMISSION_DECISION_REASON = "permissionDecisionReason";
    String UPDATED_INPUT = "updatedInput";
    String ADDITIONAL_CONTEXT = "additionalContext";
    String UPDATED_TOOL_OUTPUT = "updatedToolOutput";
    String UPDATED_MCP_TOOL_OUTPUT = "updatedMCPToolOutput";
    String DECISION = "decision";
    String SESSION_TITLE = "sessionTitle";
    String SUPPRESS_ORIGINAL_PROMPT = "suppressOriginalPrompt";
    String INITIAL_USER_MESSAGE = "initialUserMessage";
    String WATCH_PATHS = "watchPaths";
    String RELOAD_SKILLS = "reloadSkills";
    String RETRY = "retry";
    String ACTION = "action";
    String CONTENT = "content";
    String WORKTREE_PATH = "worktreePath";
    String DISPLAY_CONTENT = "displayContent";

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

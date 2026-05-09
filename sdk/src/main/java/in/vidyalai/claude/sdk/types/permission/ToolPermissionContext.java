package in.vidyalai.claude.sdk.types.permission;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Context information for tool permission callbacks.
 *
 * @param signal         reserved for future abort signal support (currently
 *                       always null)
 * @param suggestions    permission suggestions from the CLI
 * @param toolUseId      unique identifier for this specific tool call within
 *                       the assistant message; multiple tool calls in the same
 *                       message will have different toolUseIds. Always a
 *                       non-empty string when delivered to a {@code canUseTool}
 *                       callback (the wire protocol guarantees it); the
 *                       {@code @Nullable} is only for record-component
 *                       compatibility, so callers do not need to handle null.
 * @param agentId        if running within the context of a sub-agent, the
 *                       sub-agent's ID
 * @param blockedPath    the file path that triggered the permission request,
 *                       if applicable. For example, when a Bash command tries
 *                       to access a path outside allowed directories.
 * @param decisionReason explains why this permission request was triggered.
 *                       When a {@code PreToolUse} hook returns
 *                       {@code permissionDecision: "ask"} with a
 *                       {@code permissionDecisionReason}, that reason is
 *                       forwarded here.
 * @param title          full permission prompt sentence (e.g. "Claude wants
 *                       to read foo.txt"). Use this as the primary prompt
 *                       text when present instead of reconstructing from
 *                       tool name + input.
 * @param displayName    short noun phrase for the tool action (e.g. "Read
 *                       file"), suitable for button labels or compact UI.
 * @param description    human-readable subtitle for the permission UI.
 */
public record ToolPermissionContext(
        @Nullable Object signal,
        List<PermissionUpdate> suggestions,
        @Nullable String toolUseId,
        @Nullable String agentId,
        @Nullable String blockedPath,
        @Nullable String decisionReason,
        @Nullable String title,
        @Nullable String displayName,
        @Nullable String description) {

    /**
     * Creates a context with no signal and empty suggestions.
     */
    public ToolPermissionContext() {
        this(null, List.of(), null, null, null, null, null, null, null);
    }

    /**
     * Creates a context with suggestions.
     *
     * @param suggestions the permission suggestions
     */
    public ToolPermissionContext(List<PermissionUpdate> suggestions) {
        this(null, suggestions, null, null, null, null, null, null, null);
    }

    /**
     * Creates a context with signal and suggestions (backwards compatible).
     *
     * @param signal      reserved for future abort signal support
     * @param suggestions the permission suggestions
     */
    public ToolPermissionContext(@Nullable Object signal, List<PermissionUpdate> suggestions) {
        this(signal, suggestions, null, null, null, null, null, null, null);
    }

    /**
     * Creates a context with signal, suggestions, toolUseId, and agentId
     * (backwards compatible with pre-enrichment callers).
     */
    public ToolPermissionContext(
            @Nullable Object signal,
            List<PermissionUpdate> suggestions,
            @Nullable String toolUseId,
            @Nullable String agentId) {
        this(signal, suggestions, toolUseId, agentId, null, null, null, null, null);
    }

}

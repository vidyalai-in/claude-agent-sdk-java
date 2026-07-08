package in.vidyalai.claude.sdk.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;

/**
 * Advisory check for a {@code canUseTool} callback that is silently shadowed by
 * options which auto-approve tool calls before the callback would be consulted.
 *
 * <p>Mirrors the Python SDK's {@code _warn_if_can_use_tool_shadowed} /
 * {@code _get_can_use_tool_shadowed_warning} / {@code _whole_tool_allowed}
 * helpers and the TypeScript SDK's {@code CLAUDE_SDK_CAN_USE_TOOL_SHADOWED}
 * process warning. A {@code canUseTool} callback set alongside
 * {@code allowedTools} entries that allow a whole tool (such as {@code "Read"},
 * {@code "Read()"} or {@code "Read(*)"}), or alongside
 * {@code permissionMode == bypassPermissions}, never fires for those tool calls
 * because they are permitted before reaching a prompt.
 *
 * <p>The warning is emitted via {@code java.util.logging} at {@code WARNING}
 * level on the logger named after this class; suppress it by configuring that
 * logger's level. The check is advisory only (it never throws): shadowing can
 * be intentional, e.g. a callback used solely for tools outside
 * {@code allowedTools}.
 */
public final class CanUseToolShadow {

    private static final Logger logger = Logger.getLogger(CanUseToolShadow.class.getName());

    private CanUseToolShadow() {
        // Utility class
    }

    /**
     * Returns the tool an {@code allowedTools} entry allows outright, else
     * {@code null}.
     *
     * <p>Mirrors the CLI's rule parser: an entry allows a whole tool when it
     * has no {@code (...)} specifier ({@code "Read"}), or when the specifier is
     * empty or a lone wildcard ({@code "Read()"}, {@code "Read(*)"}). A real
     * specifier ({@code "Bash(ls:*)"}) only allows matching invocations.
     * Malformed entries fall back to the whole string as a tool name in the
     * CLI, so they match nothing and are ignored here.
     */
    static @Nullable String wholeToolAllowed(String entry) {
        if (entry.strip().isEmpty()) {
            return null;
        }
        int openIndex = entry.indexOf('(');
        if (openIndex == -1) {
            return entry;
        }
        if (openIndex == 0 || !entry.endsWith(")")) {
            return null;
        }
        String specifier = entry.substring(openIndex + 1, entry.length() - 1);
        return (specifier.isEmpty() || specifier.equals("*")) ? entry.substring(0, openIndex) : null;
    }

    /**
     * Returns the shadowing warning message for these options, or {@code null}
     * when nothing visibly shadows the callback.
     */
    public static @Nullable String getShadowedWarning(
            @Nullable PermissionMode permissionMode,
            List<String> allowedTools) {
        if (permissionMode == PermissionMode.BYPASS_PERMISSIONS) {
            return "canUseTool will not be invoked: permissionMode "
                    + "'bypassPermissions' auto-approves every tool call (except "
                    + "explicit deny rules) before the callback is consulted. To gate "
                    + "every tool call, use a PreToolUse hook instead.";
        }
        // A LinkedHashSet dedupes while preserving order: redundant configs like
        // ["Read", "Read()"] resolve to the same tool and must not report it twice.
        LinkedHashSet<String> shadowed = new LinkedHashSet<>();
        for (String entry : allowedTools) {
            String tool = wholeToolAllowed(entry);
            if (tool != null) {
                shadowed.add(tool);
            }
        }
        if (shadowed.isEmpty()) {
            return null;
        }
        return "canUseTool will not be invoked for: " + String.join(", ", shadowed) + ". "
                + "An allowedTools entry that allows a whole tool auto-approves it "
                + "before the callback is consulted. To gate every tool call, use a "
                + "PreToolUse hook; or narrow the entry so calls fall through to "
                + "canUseTool. Allow rules from settings files can also shadow the "
                + "callback but are not visible here.";
    }

    /**
     * Returns the shadowing warning message for the given options, or
     * {@code null} when the callback is unset or nothing shadows it.
     */
    public static @Nullable String shadowedWarningFor(ClaudeAgentOptions options) {
        if (options.canUseTool() == null) {
            return null;
        }
        // skills="all" makes the transport append a bare "Skill" to the effective
        // allowedTools, so it shadows the callback just like a hand-written entry.
        // skills=[names] appends Skill(name) specifiers, which do not.
        List<String> allowedTools = options.allowedTools();
        if ("all".equals(options.skills()) && !allowedTools.contains("Skill")) {
            allowedTools = new ArrayList<>(allowedTools);
            allowedTools.add("Skill");
        }
        return getShadowedWarning(options.permissionMode(), allowedTools);
    }

    /**
     * Logs a warning if {@code canUseTool} is shadowed. Called once per query
     * construction (client connect / streaming query start). Advisory only.
     */
    public static void warnIfShadowed(ClaudeAgentOptions options) {
        String message = shadowedWarningFor(options);
        if (message != null) {
            logger.warning(message);
        }
    }
}

package in.vidyalai.claude.sdk.types.hook;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import org.jspecify.annotations.Nullable;

import in.vidyalai.claude.sdk.types.hook.input.HookInput;
import in.vidyalai.claude.sdk.types.hook.output.HookOutput;

/**
 * Hook matcher configuration.
 *
 * <p>
 * Defines which hooks to call for specific tool patterns.
 *
 * <p><b>Dispatch order:</b> when multiple matchers are registered on the same
 * event, the CLI dispatches all matching hook callbacks <b>concurrently</b>
 * (in parallel), not sequentially. Design each hook to be independent; do
 * not rely on one completing before another starts.
 *
 * @param matcher        See
 *                       https://docs.anthropic.com/en/docs/claude-code/hooks#structure
 *                       for the expected string value. For example, for
 *                       PreToolUse, the matcher can be a tool name like "Bash"
 *                       or a combination of tool names like
 *                       "Write|MultiEdit|Edit"
 * @param hooks          list of hook callback functions
 * @param timeoutSeconds timeout in seconds for all hooks in this matcher
 *                       (default: 60)
 * @param onFailure      what happens when a callback in this matcher fails.
 *                       With {@link HookOnFailure#BLOCK}, a callback whose
 *                       future completes exceptionally, completes with
 *                       {@code null}, or returns a value the CLI rejects
 *                       blocks the action, so a callback with no opinion must
 *                       return an empty {@code HookOutput}. Unrecognized keys
 *                       are not a failure. Stop and SubagentStop ignore this
 *                       option (so do the CLI's TaskCompleted and TeammateIdle
 *                       events, which {@link HookEvent} does not model). A
 *                       PermissionRequest block counts only if it lands before
 *                       {@code canUseTool} answers. Null or
 *                       {@link HookOnFailure#CONTINUE} counts a failure as no
 *                       decision. With {@code BLOCK}, connecting to a CLI too
 *                       old to honor it raises
 *                       {@link in.vidyalai.claude.sdk.exceptions.CLIConnectionException}.
 */
public record HookMatcher(
        @Nullable String matcher,
        List<HookCallback> hooks,
        @Nullable Double timeoutSeconds,
        @Nullable HookOnFailure onFailure) {

    /**
     * Creates a hook matcher without an {@code onFailure} policy.
     *
     * @param matcher        the pattern to match, or null for all
     * @param hooks          the hook callbacks
     * @param timeoutSeconds timeout in seconds for all hooks in this matcher,
     *                       or null for the CLI default
     */
    public HookMatcher(@Nullable String matcher, List<HookCallback> hooks, @Nullable Double timeoutSeconds) {
        this(matcher, hooks, timeoutSeconds, null);
    }

    /**
     * Creates a hook matcher with just a list of hooks.
     *
     * @param hooks the hook callbacks
     */
    public HookMatcher(List<HookCallback> hooks) {
        this(null, hooks, null, null);
    }

    /**
     * Creates a hook matcher with a pattern and hooks.
     *
     * @param matcher the pattern to match
     * @param hooks   the hook callbacks
     */
    public HookMatcher(String matcher, List<HookCallback> hooks) {
        this(matcher, hooks, null, null);
    }

    /**
     * Functional interface for hook callbacks.
     *
     * <p>
     * Hook callbacks receive:
     * <ul>
     * <li>input - Strongly-typed hook input based on the event type</li>
     * <li>context - Hook context with optional tool use id and abort signal support
     * (placeholder)</li>
     * </ul>
     */
    @FunctionalInterface
    public interface HookCallback extends BiFunction<HookInput, HookContext, CompletableFuture<HookOutput>> {
    }

}

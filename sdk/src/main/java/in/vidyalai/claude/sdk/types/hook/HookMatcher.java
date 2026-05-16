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
 */
public record HookMatcher(
        @Nullable String matcher,
        List<HookCallback> hooks,
        @Nullable Double timeoutSeconds) {

    /**
     * Creates a hook matcher with just a list of hooks.
     *
     * @param hooks the hook callbacks
     */
    public HookMatcher(List<HookCallback> hooks) {
        this(null, hooks, null);
    }

    /**
     * Creates a hook matcher with a pattern and hooks.
     *
     * @param matcher the pattern to match
     * @param hooks   the hook callbacks
     */
    public HookMatcher(String matcher, List<HookCallback> hooks) {
        this(matcher, hooks, null);
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

package in.vidyalai.claude.sdk.types.hook;

import org.jspecify.annotations.Nullable;

/**
 * Context information for hook callbacks.
 *
 * @param toolUseId optional tool use identifier
 * @param signal    reserved for future abort signal support (currently always
 *                  null)
 */
public record HookContext(
        @Nullable String toolUseId,
        @Nullable Object signal) {

    /**
     * Creates a context with just a tool use ID.
     *
     * @param toolUseId the tool use identifier
     */
    public HookContext(String toolUseId) {
        this(toolUseId, null);
    }

    /**
     * Creates an empty context.
     */
    public HookContext() {
        this(null, null);
    }

}

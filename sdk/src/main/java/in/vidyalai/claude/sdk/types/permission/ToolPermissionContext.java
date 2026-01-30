package in.vidyalai.claude.sdk.types.permission;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Context information for tool permission callbacks.
 *
 * @param signal      reserved for future abort signal support (currently always
 *                    null)
 * @param suggestions permission suggestions from the CLI
 */
public record ToolPermissionContext(
        @Nullable Object signal,
        List<PermissionUpdate> suggestions) {

    /**
     * Creates a context with no signal and empty suggestions.
     */
    public ToolPermissionContext() {
        this(null, List.of());
    }

    /**
     * Creates a context with suggestions.
     *
     * @param suggestions the permission suggestions
     */
    public ToolPermissionContext(List<PermissionUpdate> suggestions) {
        this(null, suggestions);
    }

}

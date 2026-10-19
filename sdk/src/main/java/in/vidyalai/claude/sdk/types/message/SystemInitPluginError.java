package in.vidyalai.claude.sdk.types.message;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A plugin load failure listed in {@link SystemInitData}.
 *
 * @param plugin  {@code name@marketplace}, or a positional tag such as
 *                {@code inline[0]} or {@code synced[0]} when a directory entry
 *                failed before it had a name
 * @param type    failure category from an open set (e.g.
 *                {@code "path-not-found"},
 *                {@code "manifest-validation-error"}). Treat unknown values as
 *                a generic failure
 * @param message failure message
 * @param path    present only when a {@code plugins}, {@code --plugin-dir} or
 *                synced directory entry did not load at all: that entry's
 *                path, resolved against the cwd. Use it to match a positional
 *                {@code plugin} tag to your entry
 */
public record SystemInitPluginError(
        @JsonProperty("plugin") String plugin,
        @JsonProperty("type") String type,
        @JsonProperty("message") String message,
        @JsonProperty("path") @Nullable String path) {

}

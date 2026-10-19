package in.vidyalai.claude.sdk.types.hook.input;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Reasoning effort in effect when a hook fires.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code snake_case} for JSON
 * field names because it represents data <b>received from the CLI</b>. See
 * {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param level effort level for the current turn (e.g. {@code "low"},
 *              {@code "high"}, {@code "max"}), after any downgrade for the
 *              selected model
 */
public record HookEffort(
        @JsonProperty("level") String level) {

}

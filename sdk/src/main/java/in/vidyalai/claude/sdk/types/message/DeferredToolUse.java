package in.vidyalai.claude.sdk.types.message;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Tool use that was deferred by a {@code PreToolUse} hook returning
 * {@code "defer"}.
 *
 * <p>When a {@code PreToolUse} hook returns {@code permissionDecision: "defer"}
 * the run stops and the {@link ResultMessage} carries the deferred tool call
 * here so the caller can inspect it and decide whether to resume.
 *
 * <p><b>JSON Naming Convention:</b> This type uses {@code snake_case} for JSON
 * field names because it represents data <b>received from the CLI</b>. See
 * {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param id    unique identifier of the deferred tool call
 * @param name  the tool name
 * @param input the tool input arguments
 */
public record DeferredToolUse(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("input") Map<String, Object> input) {

}

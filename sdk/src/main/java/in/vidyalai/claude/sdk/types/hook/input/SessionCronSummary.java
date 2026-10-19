package in.vidyalai.claude.sdk.types.hook.input;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Session-scoped scheduled wakeup, as listed on {@link StopHookInput} and
 * {@link SubagentStopHookInput}.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code snake_case} for JSON
 * field names because it represents data <b>received from the CLI</b>. See
 * {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param id        wakeup identifier
 * @param schedule  cron expression, e.g. {@code "0 9 * * 1-5"}
 * @param recurring {@code false} for one-shot wakeups; {@code true} for tasks
 *                  that re-fire on every match
 * @param prompt    prompt submitted when the cron fires. Capped at 1000
 *                  characters; a clipped value ends with an in-string
 *                  {@code "… [+N chars]"} marker
 */
public record SessionCronSummary(
        @JsonProperty("id") String id,
        @JsonProperty("schedule") String schedule,
        @JsonProperty("recurring") boolean recurring,
        @JsonProperty("prompt") String prompt) {

}

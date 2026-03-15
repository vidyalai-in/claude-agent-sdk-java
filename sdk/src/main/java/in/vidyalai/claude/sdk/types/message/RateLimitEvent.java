package in.vidyalai.claude.sdk.types.message;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Rate limit event emitted when rate limit info changes.
 *
 * <p>
 * The CLI emits this whenever the rate limit status transitions (e.g. from
 * {@code "allowed"} to {@code "allowed_warning"}). Use this to warn users
 * before they hit a hard limit, or to gracefully back off when
 * {@code status == "rejected"}.
 *
 * <p>
 * Rate limit status values:
 * <ul>
 * <li>{@code "allowed"} - within rate limits</li>
 * <li>{@code "allowed_warning"} - approaching the rate limit</li>
 * <li>{@code "rejected"} - rate limit has been hit</li>
 * </ul>
 *
 * <p>
 * Rate limit type values:
 * <ul>
 * <li>{@code "five_hour"} - 5-hour rolling window</li>
 * <li>{@code "seven_day"} - 7-day rolling window</li>
 * <li>{@code "seven_day_opus"} - 7-day Opus-specific window</li>
 * <li>{@code "seven_day_sonnet"} - 7-day Sonnet-specific window</li>
 * <li>{@code "overage"} - overage/pay-as-you-go limit</li>
 * </ul>
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code snake_case} for JSON
 * field names because it represents data <b>received from the CLI</b>. See
 * {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param rateLimitInfo detailed rate limit status information
 * @param uuid          unique identifier for this event
 * @param sessionId     the session identifier
 */
public record RateLimitEvent(
        @JsonProperty("rate_limit_info") RateLimitInfo rateLimitInfo,
        @JsonProperty("uuid") String uuid,
        @JsonProperty("session_id") String sessionId) implements Message {

    @Override
    public String type() {
        return "rate_limit_event";
    }

}

package in.vidyalai.claude.sdk.types.message;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Rate limit status emitted by the CLI when rate limit state changes.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code snake_case} for JSON
 * field names because it represents data <b>received from the CLI</b>. See
 * {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param status                current rate limit status
 * @param resetsAt              Unix timestamp when the rate limit window resets
 * @param rateLimitType         which rate limit window applies
 * @param utilization           fraction of the rate limit consumed (0.0 - 1.0)
 * @param overageStatus         status of overage/pay-as-you-go usage if
 *                              applicable
 * @param overageResetsAt       Unix timestamp when overage window resets
 * @param overageDisabledReason why overage is unavailable if status is rejected
 * @param raw                   full raw map from the CLI, including any fields
 *                              not modeled above
 */
public record RateLimitInfo(
        @JsonProperty("status") RateLimitStatus status,
        @JsonProperty("resets_at") @Nullable Long resetsAt,
        @JsonProperty("rate_limit_type") @Nullable RateLimitType rateLimitType,
        @JsonProperty("utilization") @Nullable Double utilization,
        @JsonProperty("overage_status") @Nullable RateLimitStatus overageStatus,
        @JsonProperty("overage_resets_at") @Nullable Long overageResetsAt,
        @JsonProperty("overage_disabled_reason") @Nullable String overageDisabledReason,
        @Nullable Map<String, Object> raw) {
}

package in.vidyalai.claude.sdk.types.control.request;

import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Request to get context window usage breakdown.
 */
@JsonTypeName("get_context_usage")
public record SDKControlGetContextUsageRequest() implements SDKControlRequestData {

    @Override
    public String subtype() {
        return "get_context_usage";
    }

}

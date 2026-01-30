package in.vidyalai.claude.sdk.types.control.response;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Success response for SDK Control Protocol requests.
 *
 * <p>
 * Part of the SDK Control Protocol for bidirectional communication
 * between the SDK and CLI. This represents a successful response to
 * a control request.
 *
 * @param requestId the request ID this response corresponds to
 * @param response  the response data (may be null for requests with no response
 *                  data)
 */
@JsonTypeName("success")
public record ControlResponse(
        String requestId,
        @Nullable Map<String, Object> response) implements ControlResponseData {

    @Override
    public String subtype() {
        return "success";
    }

}

package in.vidyalai.claude.sdk.types.control.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wrapper for SDK Control Protocol requests.
 *
 * <p>
 * Part of the SDK Control Protocol for bidirectional communication
 * between the SDK and CLI. This wraps a specific request type along
 * with metadata like request ID.
 */
public record SDKControlRequest(
        String type,
        @JsonProperty("request_id") String requestId,
        SDKControlRequestData request) {

    /**
     * Creates a control request with the standard type.
     *
     * @param requestId unique identifier for tracking request/response pairs
     * @param request   the actual request data (discriminated union)
     */
    public SDKControlRequest(String requestId, SDKControlRequestData request) {
        this("control_request", requestId, request);
    }

}

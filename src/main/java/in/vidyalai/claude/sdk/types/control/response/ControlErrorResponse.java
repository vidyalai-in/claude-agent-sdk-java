package in.vidyalai.claude.sdk.types.control.response;

import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Error response for SDK Control Protocol requests.
 *
 * <p>
 * Part of the SDK Control Protocol for bidirectional communication
 * between the SDK and CLI. This represents an error response to
 * a control request.
 *
 * @param requestId the request ID this response corresponds to
 * @param error     the error message
 */
@JsonTypeName("error")
public record ControlErrorResponse(
        String requestId,
        String error) implements ControlResponseData {

    @Override
    public String subtype() {
        return "error";
    }

}

package in.vidyalai.claude.sdk.types.control.response;

/**
 * Wrapper for SDK Control Protocol responses.
 *
 * <p>
 * Part of the SDK Control Protocol for bidirectional communication
 * between the SDK and CLI. This wraps a specific response type (success
 * or error).
 *
 * @param type     the response type identifier (always "control_response")
 * @param response the actual response data (discriminated union)
 */
public record SDKControlResponse(
        String type,
        ControlResponseData response) {

    /**
     * Creates a control response with the standard type.
     *
     * @param response the actual response data (discriminated union)
     */
    public SDKControlResponse(ControlResponseData response) {
        this("control_response", response);
    }

}

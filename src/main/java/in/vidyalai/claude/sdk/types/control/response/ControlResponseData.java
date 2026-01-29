package in.vidyalai.claude.sdk.types.control.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Base interface for SDK Control Protocol response data.
 *
 * <p>
 * Part of the SDK Control Protocol for bidirectional communication
 * between the SDK and CLI. All specific response types implement this
 * interface.
 *
 * <p>
 * This is a sealed interface with the following permitted subtypes:
 * <ul>
 * <li>{@link ControlResponse} - success response</li>
 * <li>{@link ControlErrorResponse} - error response</li>
 * </ul>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "subtype")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ControlResponse.class, name = "success"),
        @JsonSubTypes.Type(value = ControlErrorResponse.class, name = "error")
})
public sealed interface ControlResponseData permits ControlResponse, ControlErrorResponse {

    /**
     * Gets the response subtype.
     *
     * @return the subtype string ("success" or "error")
     */
    String subtype();

    /**
     * Get request id for all responses.
     */
    @JsonProperty("request_id")
    String requestId();

}

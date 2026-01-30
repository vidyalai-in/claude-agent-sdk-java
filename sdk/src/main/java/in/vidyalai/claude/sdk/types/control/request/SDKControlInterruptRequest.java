package in.vidyalai.claude.sdk.types.control.request;

import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Request to interrupt the current execution.
 *
 * <p>
 * Part of the SDK Control Protocol for bidirectional communication
 * between the SDK and CLI.
 */
@JsonTypeName("interrupt")
public record SDKControlInterruptRequest() implements SDKControlRequestData {

    @Override
    public String subtype() {
        return "interrupt";
    }

}

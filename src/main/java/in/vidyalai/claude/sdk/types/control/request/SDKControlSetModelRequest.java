package in.vidyalai.claude.sdk.types.control.request;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Request to set the AI model.
 *
 * <p>
 * Part of the SDK Control Protocol for bidirectional communication
 * between the SDK and CLI.
 */
@JsonTypeName("set_model")
public record SDKControlSetModelRequest(
        @Nullable String model) implements SDKControlRequestData {

    @Override
    public String subtype() {
        return "set_model";
    }

}
